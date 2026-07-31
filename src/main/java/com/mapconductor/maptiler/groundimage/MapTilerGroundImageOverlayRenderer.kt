package com.mapconductor.maptiler.groundimage

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageEntityInterface
import com.mapconductor.core.groundimage.GroundImageOverlayRendererInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.maptiler.maptilersdk.map.LngLat
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.style.dsl.PropertyValue
import com.maptiler.maptilersdk.map.style.layer.MTLayerVisibility
import com.maptiler.maptilersdk.map.style.layer.raster.MTRasterLayer
import com.maptiler.maptilersdk.map.style.source.MTImageSource
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log

/**
 * グラウンドイメージ（地理座標に貼り付けた画像）を MapTiler のスタイルへ反映するレンダラ。
 *
 * MapTiler（MapLibre GL JS）の [MTImageSource]（4 隅の座標＋画像 URL）＋ [MTRasterLayer] で描画する。
 * 画像は [Drawable] を PNG にエンコードして `data:image/png;base64,...` URL として渡す。
 * 更新時はレイヤを作り直さず、`updateImage` / `setCoordinates` / `raster-opacity` で差分のみ反映する。
 */
class MapTilerGroundImageOverlayRenderer(
    private val mtController: MTMapViewController,
) : GroundImageOverlayRendererInterface<MapTilerGroundImageHandle> {
    override suspend fun onAdd(
        data: List<GroundImageOverlayRendererInterface.AddParamsInterface>,
    ): List<MapTilerGroundImageHandle?> = data.map { addGroundImage(it.state) }

    override suspend fun onChange(
        data: List<GroundImageOverlayRendererInterface.ChangeParamsInterface<MapTilerGroundImageHandle>>,
    ): List<MapTilerGroundImageHandle?> =
        data.map { params ->
            updateGroundImage(params.prev.groundImage, params.current.state)
        }

    override suspend fun onRemove(data: List<GroundImageEntityInterface<MapTilerGroundImageHandle>>) {
        data.forEach { removeGroundImage(it) }
    }

    override suspend fun onPostProcess() {}

    /**
     * 既知のグラウンドイメージを全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。
     */
    fun reapply(handles: List<MapTilerGroundImageHandle>) {
        val style = mtController.style ?: return
        handles.forEach { handle ->
            runCatching { style.addSource(handle.source) }
            runCatching { style.addLayer(handle.layer) }
        }
    }

    private fun addGroundImage(state: GroundImageState): MapTilerGroundImageHandle? {
        val coordinates = state.bounds.toImageCoordinates() ?: return null
        val sourceId = "groundimage-source-${state.id}"
        val layerId = "groundimage-layer-${state.id}"
        val source = MTImageSource(sourceId, imageUrl(state.image), coordinates)
        val layer =
            MTRasterLayer(layerId, sourceId).apply {
                opacity = state.opacity.coerceIn(0f, 1f).toDouble()
                visibility = MTLayerVisibility.VISIBLE
            }
        val finger = state.fingerPrint()
        val handle =
            MapTilerGroundImageHandle(
                sourceId = sourceId,
                layerId = layerId,
                source = source,
                layer = layer,
                appliedBounds = finger.bounds,
                appliedImage = finger.image,
                appliedOpacity = finger.opacity,
            )

        val style = mtController.style ?: return handle
        runCatching { style.addSource(source) }
            .onFailure { Log.w(TAG, "addSource failed: ${it.message}") }
        runCatching { style.addLayer(layer) }
            .onFailure { Log.w(TAG, "addLayer failed: ${it.message}") }
        return handle
    }

    /**
     * 既存のソース・レイヤを保ったまま差分のみ反映する。画像変更は [MTImageSource.updateImage]、
     * bounds 変更は [MTImageSource.setCoordinates]、不透明度変更は `raster-opacity` で更新する。
     */
    private fun updateGroundImage(
        handle: MapTilerGroundImageHandle,
        state: GroundImageState,
    ): MapTilerGroundImageHandle {
        val coordinates = state.bounds.toImageCoordinates() ?: return handle
        val style = mtController.style
        val finger = state.fingerPrint()

        when {
            finger.image != handle.appliedImage ->
                runCatching { handle.source.updateImage(imageUrl(state.image), coordinates, mtController) }
                    .onFailure { Log.w(TAG, "updateImage failed: ${it.message}") }

            finger.bounds != handle.appliedBounds ->
                runCatching { handle.source.setCoordinates(coordinates, mtController) }
                    .onFailure { Log.w(TAG, "setCoordinates failed: ${it.message}") }
        }

        if (finger.opacity != handle.appliedOpacity && style != null) {
            runCatching {
                style.setPaintProperty(
                    handle.layerId,
                    RASTER_OPACITY,
                    PropertyValue.of(state.opacity.coerceIn(0f, 1f).toDouble()),
                )
            }.onFailure { Log.w(TAG, "updateOpacity failed: ${it.message}") }
        }

        return handle.copy(
            appliedBounds = finger.bounds,
            appliedImage = finger.image,
            appliedOpacity = finger.opacity,
        )
    }

    private fun removeGroundImage(entity: GroundImageEntityInterface<MapTilerGroundImageHandle>) {
        val style = mtController.style ?: return
        val handle = entity.groundImage
        runCatching { style.removeLayerById(handle.layerId) }
        runCatching { style.removeSourceById(handle.sourceId) }
    }

    /**
     * [GeoRectBounds] を MapLibre GL の画像ソース座標順（左上・右上・右下・左下）へ変換する。
     */
    private fun GeoRectBounds.toImageCoordinates(): List<LngLat>? {
        val sw = southWest ?: return null
        val ne = northEast ?: return null
        return listOf(
            LngLat(sw.longitude, ne.latitude),
            LngLat(ne.longitude, ne.latitude),
            LngLat(ne.longitude, sw.latitude),
            LngLat(sw.longitude, sw.latitude),
        )
    }

    /**
     * [Drawable] を PNG にエンコードし、`data:image/png;base64,...` URL として返す。
     * `data:` は既知のプロトコルではないため、文字列化のみを担う [URLStreamHandler] を与えて生成する。
     */
    private fun imageUrl(drawable: Drawable): URL {
        val bitmap = drawable.toBitmap()
        val bytes =
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        val spec = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val handler =
            object : URLStreamHandler() {
                override fun openConnection(u: URL?): URLConnection = throw UnsupportedOperationException()

                override fun toExternalForm(u: URL?): String = spec
            }
        return URL(null, spec, handler)
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) {
            return bitmap
        }
        val width = intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = intrinsicHeight.takeIf { it > 0 } ?: 1
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = Rect(bounds)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    private companion object {
        const val TAG = "MapTilerGroundImage"
        const val RASTER_OPACITY = "raster-opacity"
    }
}
