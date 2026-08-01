package com.mapconductor.maptiler.polygon

import androidx.compose.ui.graphics.toArgb
import com.mapconductor.core.geometry.OverlayGeoJson
import com.mapconductor.core.geometry.buildUnwrappedPolygonRings
import com.mapconductor.core.polygon.PolygonEntityInterface
import com.mapconductor.core.polygon.PolygonOverlayRendererInterface
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polygon.unionHoles
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
 * ポリゴンを MapTiler（MapLibre GL JS / WebView）のスタイルへ反映するレンダラ。
 *
 * ポリゴン 1 個につき GeoJSON ソース（Polygon／MultiPolygon、穴対応）と、塗り用 [MTFillLayer]・
 * 輪郭用 [MTLineLayer] を 1 組ずつ追加する。測地線補間・子午線分割はコアの共通ユーティリティを再利用する。
 */
class MapTilerPolygonOverlayRenderer(
    private val mtController: MTMapViewController,
) : PolygonOverlayRendererInterface<MapTilerPolygonHandle> {
    override suspend fun onAdd(
        data: List<PolygonOverlayRendererInterface.AddParamsInterface>,
    ): List<MapTilerPolygonHandle?> = data.map { addPolygon(it.state) }

    override suspend fun onChange(
        data: List<PolygonOverlayRendererInterface.ChangeParamsInterface<MapTilerPolygonHandle>>,
    ): List<MapTilerPolygonHandle?> =
        data.map { params ->
            val next = params.current.state
            // 形状変更の判定は、頂点ドラッグ等で points が同一リストのまま in-place で書き換わっても
            // 検出できるよう、登録時に確定した prev のフィンガープリント（値）と現在値を比較する。
            // prev.state と current.state は同一インスタンスになり得るため参照比較は使えない。
            val prevFinger = params.prev.fingerPrint
            val nextFinger = next.fingerPrint()
            val geometryChanged =
                prevFinger.points != nextFinger.points ||
                    prevFinger.holes != nextFinger.holes ||
                    prevFinger.geodesic != nextFinger.geodesic
            // 形状が変わってもソース／レイヤは作り直さず、ソースのデータのみ差し替える。
            // 頂点ドラッグ中はこの経路が高頻度で走るため、レイヤ再生成による競合（輪郭レイヤの
            // 取りこぼし）を避けるべく、ソースの setData で GeoJSON を更新して塗り・輪郭を維持する。
            if (geometryChanged) {
                updateGeometry(params.prev.polygon, next)
            }
            updatePaint(params.prev.polygon, next)
            params.prev.polygon
        }

    override suspend fun onRemove(data: List<PolygonEntityInterface<MapTilerPolygonHandle>>) {
        data.forEach { removePolygon(it) }
    }

    override suspend fun onPostProcess() {}

    /**
     * 既知のポリゴンを全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。
     */
    fun reapply(handles: List<MapTilerPolygonHandle>) {
        val style = mtController.style ?: return
        handles.forEach { handle ->
            runCatching { style.addSource(handle.source) }
            runCatching { style.addLayer(handle.fillLayer) }
            runCatching { style.addLayer(handle.lineLayer) }
        }
    }

    private fun addPolygon(state: PolygonState): MapTilerPolygonHandle? {
        val json = buildGeoJson(state) ?: return null
        val sourceId = "polygon-source-${state.id}"
        val fillLayerId = "polygon-fill-${state.id}"
        val lineLayerId = "polygon-line-${state.id}"
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

        val handle = MapTilerPolygonHandle(sourceId, fillLayerId, lineLayerId, source, fillLayer, lineLayer)

        val style = mtController.style ?: return handle
        // ソース → 塗り → 輪郭の順に追加（輪郭を塗りの上に重ねる）。
        runCatching { style.addSource(source) }
            .onFailure { Log.w(TAG, "addSource failed: ${it.message}") }
        runCatching { style.addLayer(fillLayer) }
            .onFailure { Log.w(TAG, "addLayer(fill) failed: ${it.message}") }
        runCatching { style.addLayer(lineLayer) }
            .onFailure { Log.w(TAG, "addLayer(line) failed: ${it.message}") }
        return handle
    }

    /**
     * 既存のソース・レイヤを保ったまま、GeoJSON データのみを差し替えて形状を更新する。
     * data URL 経由の `map.getSource(id).setData(...)` を用いるため、塗り・輪郭レイヤは維持される。
     */
    private fun updateGeometry(
        handle: MapTilerPolygonHandle,
        state: PolygonState,
    ) {
        val json = buildGeoJson(state) ?: return
        // スタイル再読込時の復元（reapply）でも最新形状を再現できるよう jsonString も更新する。
        runCatching { handle.source.jsonString = json }
        runCatching { handle.source.setData(dataUrl(json), mtController) }
            .onFailure { Log.w(TAG, "setData failed: ${it.message}") }
    }

    /**
     * インライン GeoJSON を `data:` URL として表現する。MapLibre GL JS の `setData` は URL 文字列を
     * 受け付けるため、SDK の [MTGeoJSONSource.setData] にこの URL を渡すことでソースデータを更新できる。
     * `data:` は既知のプロトコルではないため、文字列化のみを担う [URLStreamHandler] を与えて生成する。
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
        handle: MapTilerPolygonHandle,
        state: PolygonState,
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

    private fun removePolygon(entity: PolygonEntityInterface<MapTilerPolygonHandle>) {
        val style = mtController.style ?: return
        val handle = entity.polygon
        runCatching { style.removeLayerById(handle.lineLayerId) }
        runCatching { style.removeLayerById(handle.fillLayerId) }
        runCatching { style.removeSourceById(handle.sourceId) }
    }

    /**
     * [PolygonState] から Polygon／MultiPolygon の GeoJSON 文字列を生成する。
     * 座標は経度・緯度の順。子午線をまたぐ場合は MultiPolygon に分割する（その場合は穴を含めない）。
     *
     * MapTiler（MapLibre GL）は Polygon の inner ring で複数の穴を描画できるため、他プロバイダ
     * （MapLibre/TomTom 等）と同様に、複数穴は [unionHoles] で結合してから渡す。結合しないと重なった
     * 穴が偶奇規則で打ち消し合い（重なり部分が塗られる）表示が崩れる。頂点ドラッグ後の更新経路でも
     * 確実に結合されるよう、Compose 層だけでなくレンダラ側でも結合する。
     */
    private fun buildGeoJson(state: PolygonState): String? {
        val resolved = if (state.holes.size > 1) state.unionHoles() else state
        // unwrap 座標の外周 1 リング + 全穴。MapLibre GL JS は ±180 超の経度を扱えるため
        // 分割不要で、±180 跨ぎのポリゴンでも穴を保持できる。
        return OverlayGeoJson.polygonFeature(
            buildUnwrappedPolygonRings(resolved.points, resolved.holes, resolved.geodesic),
        )
    }

    private companion object {
        const val TAG = "MapTilerPolygon"
        const val FILL_COLOR = "fill-color"
        const val FILL_OPACITY = "fill-opacity"
        const val LINE_COLOR = "line-color"
        const val LINE_OPACITY = "line-opacity"
        const val LINE_WIDTH = "line-width"
        const val ALPHA_MASK = -0x1000000 // 0xFF000000: alpha を不透明に強制（透過は opacity で表現）
    }
}

/**
 * MapTiler のポリゴン実体（GeoJSON ソース＋塗りレイヤ＋輪郭レイヤ）を参照するハンドル。
 */
data class MapTilerPolygonHandle(
    val sourceId: String,
    val fillLayerId: String,
    val lineLayerId: String,
    val source: MTGeoJSONSource,
    val fillLayer: MTFillLayer,
    val lineLayer: MTLineLayer,
)
