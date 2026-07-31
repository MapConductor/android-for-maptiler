package com.mapconductor.maptiler.circle

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.circle.CircleEntityInterface
import com.mapconductor.core.circle.CircleOverlayRendererInterface
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.normalize
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.core.spherical.splitByMeridian
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.style.dsl.PropertyValue
import com.maptiler.maptilersdk.map.style.layer.MTLayerVisibility
import com.maptiler.maptilersdk.map.style.layer.fill.MTFillLayer
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
 * 円を MapTiler（MapLibre GL JS / WebView）のスタイルへ反映するレンダラ。
 *
 * MapTiler にネイティブの円レイヤは無いため、中心・半径から測地線上の多角形リングを生成し、
 * ポリゴンと同じく GeoJSON ソース＋塗り [MTFillLayer]・輪郭 [MTLineLayer] で描画する。
 * 形状（中心・半径）変更時はレイヤを作り直さず、ソースの `setData`（data URL）でジオメトリのみ差し替える。
 */
class MapTilerCircleOverlayRenderer(
    private val mtController: MTMapViewController,
) : CircleOverlayRendererInterface<MapTilerCircleHandle> {
    override suspend fun onAdd(
        data: List<CircleOverlayRendererInterface.AddParamsInterface>,
    ): List<MapTilerCircleHandle?> = data.map { addCircle(it.state) }

    override suspend fun onChange(
        data: List<CircleOverlayRendererInterface.ChangeParamsInterface<MapTilerCircleHandle>>,
    ): List<MapTilerCircleHandle?> =
        data.map { params ->
            val next = params.current.state
            // 中心・半径の変更は登録時に確定した prev のフィンガープリント（値）と現在値で判定する。
            val prevFinger = params.prev.fingerPrint
            val nextFinger = next.fingerPrint()
            val geometryChanged =
                prevFinger.center != nextFinger.center ||
                    prevFinger.radiusMeters != nextFinger.radiusMeters ||
                    prevFinger.geodesic != nextFinger.geodesic
            if (geometryChanged) {
                updateGeometry(params.prev.circle, next)
            }
            updatePaint(params.prev.circle, next)
            params.prev.circle
        }

    override suspend fun onRemove(data: List<CircleEntityInterface<MapTilerCircleHandle>>) {
        data.forEach { removeCircle(it) }
    }

    override suspend fun onPostProcess() {}

    /**
     * 既知の円を全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。
     */
    fun reapply(handles: List<MapTilerCircleHandle>) {
        val style = mtController.style ?: return
        handles.forEach { handle ->
            runCatching { style.addSource(handle.source) }
            runCatching { style.addLayer(handle.fillLayer) }
            runCatching { style.addLayer(handle.lineLayer) }
        }
    }

    private fun addCircle(state: CircleState): MapTilerCircleHandle? {
        val json = buildGeoJson(state) ?: return null
        val sourceId = "circle-source-${state.id}"
        val fillLayerId = "circle-fill-${state.id}"
        val lineLayerId = "circle-line-${state.id}"
        val source = MTGeoJSONSource.fromJsonString(sourceId, json)

        val fillLayer =
            MTFillLayer(fillLayerId, sourceId).apply {
                color = state.fillColor.toArgb() or ALPHA_MASK
                opacity = state.fillColor.alpha.toDouble()
                visibility = MTLayerVisibility.VISIBLE
            }
        val lineLayer =
            MTLineLayer(lineLayerId, sourceId).apply {
                color = state.strokeColor.toArgb() or ALPHA_MASK
                opacity = state.strokeColor.alpha.toDouble()
                width = state.strokeWidth.value.toDouble()
                cap = MTLineCap.ROUND
                join = MTLineJoin.ROUND
                visibility = MTLayerVisibility.VISIBLE
            }

        val handle = MapTilerCircleHandle(sourceId, fillLayerId, lineLayerId, source, fillLayer, lineLayer)

        val style = mtController.style ?: return handle
        runCatching { style.addSource(source) }
            .onFailure { Log.w(TAG, "addSource failed: ${it.message}") }
        runCatching { style.addLayer(fillLayer) }
            .onFailure { Log.w(TAG, "addLayer(fill) failed: ${it.message}") }
        runCatching { style.addLayer(lineLayer) }
            .onFailure { Log.w(TAG, "addLayer(line) failed: ${it.message}") }
        return handle
    }

    /**
     * 既存のソース・レイヤを保ったまま、GeoJSON データのみを差し替えて円の形状を更新する。
     * 半径ドラッグ中は高頻度で走るため、レイヤ再生成による競合（輪郭の取りこぼし）を避ける。
     */
    private fun updateGeometry(
        handle: MapTilerCircleHandle,
        state: CircleState,
    ) {
        val json = buildGeoJson(state) ?: return
        runCatching { handle.source.jsonString = json }
        runCatching { handle.source.setData(dataUrl(json), mtController) }
            .onFailure { Log.w(TAG, "setData failed: ${it.message}") }
    }

    private fun updatePaint(
        handle: MapTilerCircleHandle,
        state: CircleState,
    ) {
        val style = mtController.style ?: return
        runCatching {
            style.setPaintProperty(handle.fillLayerId, FILL_COLOR, PropertyValue.color(state.fillColor.toArgb()))
            style.setPaintProperty(handle.fillLayerId, FILL_OPACITY, PropertyValue.of(state.fillColor.alpha.toDouble()))
            style.setPaintProperty(handle.lineLayerId, LINE_COLOR, PropertyValue.color(state.strokeColor.toArgb()))
            style.setPaintProperty(
                handle.lineLayerId,
                LINE_OPACITY,
                PropertyValue.of(state.strokeColor.alpha.toDouble()),
            )
            style.setPaintProperty(handle.lineLayerId, LINE_WIDTH, PropertyValue.of(state.strokeWidth.value.toDouble()))
        }.onFailure { Log.w(TAG, "updatePaint failed: ${it.message}") }
    }

    private fun removeCircle(entity: CircleEntityInterface<MapTilerCircleHandle>) {
        val style = mtController.style ?: return
        val handle = entity.circle
        runCatching { style.removeLayerById(handle.lineLayerId) }
        runCatching { style.removeLayerById(handle.fillLayerId) }
        runCatching { style.removeSourceById(handle.sourceId) }
    }

    /**
     * 中心・半径から円周上の点列（多角形リング）を生成し、Polygon／MultiPolygon の GeoJSON を作る。
     * 子午線をまたぐ場合は MultiPolygon へ分割する。
     */
    private fun buildGeoJson(state: CircleState): String? {
        if (state.radiusMeters <= 0.0) return null
        val ring =
            (0 until SEGMENTS)
                .map { i -> Spherical.computeOffset(state.center, state.radiusMeters, 360.0 * i / SEGMENTS) }
                .map { it.normalize() }
        val rings = splitByMeridian(ring, geodesic = true).filter { it.size >= 3 }
        if (rings.isEmpty()) return null

        return if (rings.size == 1) {
            val coordinates = "[${ringToJson(rings.first())}]"
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":$coordinates},\"properties\":{}}"
        } else {
            val polygons = rings.joinToString(separator = ",") { "[${ringToJson(it)}]" }
            "{\"type\":\"Feature\",\"geometry\":" +
                "{\"type\":\"MultiPolygon\",\"coordinates\":[$polygons]},\"properties\":{}}"
        }
    }

    private fun ringToJson(ring: List<GeoPointInterface>): String {
        val closed = if (ring.first() != ring.last()) ring + ring.first() else ring
        return closed.joinToString(separator = ",", prefix = "[", postfix = "]") { point ->
            "[${point.longitude},${point.latitude}]"
        }
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

    private companion object {
        const val TAG = "MapTilerCircle"
        const val SEGMENTS = 128
        const val FILL_COLOR = "fill-color"
        const val FILL_OPACITY = "fill-opacity"
        const val LINE_COLOR = "line-color"
        const val LINE_OPACITY = "line-opacity"
        const val LINE_WIDTH = "line-width"
        const val ALPHA_MASK = -0x1000000 // 0xFF000000: alpha を不透明に強制（透過は opacity で表現）
    }
}
