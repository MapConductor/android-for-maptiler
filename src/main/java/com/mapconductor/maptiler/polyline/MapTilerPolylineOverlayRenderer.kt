package com.mapconductor.maptiler.polyline

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.features.normalize
import com.mapconductor.core.polyline.PolylineEntityInterface
import com.mapconductor.core.polyline.PolylineOverlayRendererInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.spherical.createInterpolatePoints
import com.mapconductor.core.spherical.createLinearInterpolatePoints
import com.mapconductor.core.spherical.splitByMeridian
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.style.dsl.PropertyValue
import com.maptiler.maptilersdk.map.style.layer.MTLayerVisibility
import com.maptiler.maptilersdk.map.style.layer.line.MTLineCap
import com.maptiler.maptilersdk.map.style.layer.line.MTLineJoin
import com.maptiler.maptilersdk.map.style.layer.line.MTLineLayer
import com.maptiler.maptilersdk.map.style.source.MTGeoJSONSource
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.net.URLStreamHandler
import android.util.Log

/**
 * ポリラインを MapTiler（MapLibre GL JS / WebView）のスタイルへ反映するレンダラ。
 *
 * ポリライン 1 本につき GeoJSON ソース（MultiLineString）＋ [MTLineLayer] を 1 組追加する。
 * 測地線補間・子午線分割はコアの共通ユーティリティを再利用する。
 */
class MapTilerPolylineOverlayRenderer(
    private val mtController: MTMapViewController,
) : PolylineOverlayRendererInterface<MapTilerPolylineHandle> {
    override suspend fun onAdd(
        data: List<PolylineOverlayRendererInterface.AddParamsInterface>,
    ): List<MapTilerPolylineHandle?> = data.map { addLine(it.state) }

    override suspend fun onChange(
        data: List<PolylineOverlayRendererInterface.ChangeParamsInterface<MapTilerPolylineHandle>>,
    ): List<MapTilerPolylineHandle?> =
        data.map { params ->
            val next = params.current.state
            // 頂点ドラッグ等で points が同一リストのまま in-place 書き換えされても検出できるよう、
            // 登録時に確定した prev のフィンガープリント（値）と現在値を比較する（参照比較は不可）。
            val prevFinger = params.prev.fingerPrint
            val nextFinger = next.fingerPrint()
            val geometryChanged =
                prevFinger.points != nextFinger.points || prevFinger.geodesic != nextFinger.geodesic
            // 形状が変わってもソース／レイヤは作り直さず、ソースのデータのみ差し替える。
            // レイヤ再生成による競合を避けるため、setData で GeoJSON を更新してラインレイヤを維持する。
            if (geometryChanged) {
                updateGeometry(params.prev.polyline, next)
            }
            updatePaint(params.prev.polyline, next)
            params.prev.polyline
        }

    override suspend fun onRemove(data: List<PolylineEntityInterface<MapTilerPolylineHandle>>) {
        data.forEach { removeLine(it) }
    }

    override suspend fun onPostProcess() {}

    /**
     * 既知のポリラインを全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。
     */
    fun reapply(handles: List<MapTilerPolylineHandle>) {
        val style = mtController.style ?: return
        handles.forEach { handle ->
            runCatching { style.addSource(handle.source) }
            runCatching { style.addLayer(handle.layer) }
        }
    }

    private fun addLine(state: PolylineState): MapTilerPolylineHandle? {
        val json = buildGeoJson(state) ?: return null
        val sourceId = "polyline-source-${state.id}"
        val layerId = "polyline-layer-${state.id}"
        val source = MTGeoJSONSource.fromJsonString(sourceId, json)
        // 色の alpha は line-opacity で表現し、line-color は不透明 RGB とする（二重適用を避ける）。
        val opaqueColor = state.strokeColor.toArgb() or ALPHA_MASK
        val layer =
            MTLineLayer(layerId, sourceId).apply {
                color = opaqueColor
                opacity = state.strokeColor.alpha.toDouble()
                width = state.strokeWidth.value.toDouble()
                cap = MTLineCap.ROUND
                join = MTLineJoin.ROUND
                visibility = MTLayerVisibility.VISIBLE
            }

        val handle = MapTilerPolylineHandle(sourceId, layerId, source, layer)

        val style = mtController.style ?: return handle
        runCatching { style.addSource(source) }
            .onFailure { Log.w(TAG, "addSource failed: ${it.message}") }
        runCatching { style.addLayer(layer) }
            .onFailure { Log.w(TAG, "addLayer failed: ${it.message}") }
        return handle
    }

    /**
     * 既存のソース・レイヤを保ったまま、GeoJSON データのみを差し替えて形状を更新する。
     * data URL 経由の `map.getSource(id).setData(...)` を用いるため、ラインレイヤは維持される。
     */
    private fun updateGeometry(
        handle: MapTilerPolylineHandle,
        state: PolylineState,
    ) {
        val json = buildGeoJson(state) ?: return
        // スタイル再読込時の復元（reapply）でも最新形状を再現できるよう jsonString も更新する。
        runCatching { handle.source.jsonString = json }
        runCatching { handle.source.setData(dataUrl(json), mtController) }
            .onFailure { Log.w(TAG, "setData failed: ${it.message}") }
    }

    /**
     * インライン GeoJSON を `data:` URL として表現する。`data:` は既知のプロトコルではないため、
     * 文字列化のみを担う [URLStreamHandler] を与えて生成する。
     */
    private fun dataUrl(json: String): URL {
        val spec = "data:application/json," + URLEncoder.encode(json, "UTF-8")
        val handler =
            object : URLStreamHandler() {
                override fun openConnection(u: URL?): URLConnection = throw UnsupportedOperationException()

                override fun toExternalForm(u: URL?): String = spec
            }
        return URL(null, spec, handler)
    }

    private fun updatePaint(
        handle: MapTilerPolylineHandle,
        state: PolylineState,
    ) {
        val style = mtController.style ?: return
        runCatching {
            style.setPaintProperty(handle.layerId, LINE_COLOR, PropertyValue.color(state.strokeColor.toArgb()))
            style.setPaintProperty(handle.layerId, LINE_OPACITY, PropertyValue.of(state.strokeColor.alpha.toDouble()))
            style.setPaintProperty(handle.layerId, LINE_WIDTH, PropertyValue.of(state.strokeWidth.value.toDouble()))
        }.onFailure { Log.w(TAG, "updatePaint failed: ${it.message}") }
    }

    private fun removeLine(entity: PolylineEntityInterface<MapTilerPolylineHandle>) {
        val style = mtController.style ?: return
        val handle = entity.polyline
        runCatching { style.removeLayerById(handle.layerId) }
        runCatching { style.removeSourceById(handle.sourceId) }
    }

    /**
     * [PolylineState] から MultiLineString の GeoJSON 文字列を生成する。
     * 座標は経度・緯度の順。子午線をまたぐ場合は複数セグメントに分割する。
     */
    private fun buildGeoJson(state: PolylineState): String? {
        if (state.points.size < 2) return null
        val interpolated =
            when (state.geodesic) {
                true -> createInterpolatePoints(state.points)
                false -> createLinearInterpolatePoints(state.points)
            }.map { it.normalize() }

        val segments = splitByMeridian(interpolated, state.geodesic).filter { it.size >= 2 }
        if (segments.isEmpty()) return null

        val coordinates =
            segments.joinToString(separator = ",") { segment ->
                segment.joinToString(separator = ",", prefix = "[", postfix = "]") { point ->
                    "[${point.longitude},${point.latitude}]"
                }
            }

        return "{\"type\":\"Feature\",\"geometry\":" +
            "{\"type\":\"MultiLineString\",\"coordinates\":[$coordinates]},\"properties\":{}}"
    }

    private companion object {
        const val TAG = "MapTilerPolyline"
        const val LINE_COLOR = "line-color"
        const val LINE_OPACITY = "line-opacity"
        const val LINE_WIDTH = "line-width"
        const val ALPHA_MASK = -0x1000000 // 0xFF000000: alpha を不透明に強制
    }
}

/**
 * MapTiler のポリライン実体（GeoJSON ソース＋ラインレイヤ）を参照するハンドル。
 */
data class MapTilerPolylineHandle(
    val sourceId: String,
    val layerId: String,
    val source: MTGeoJSONSource,
    val layer: MTLineLayer,
)
