package com.mapconductor.maptiler.marker

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.marker.MarkerHitPick
import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTileRenderer
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.core.tileserver.TileServerRegistry
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import java.util.UUID

/**
 * 大量マーカー（「Bunch of Markers」等）を、他モジュール（maplibre 等）と同じ方式＝
 * マーカータイリングで描画するためのレンダラ。
 *
 * コアの [MarkerTileRenderer] がマーカーを PNG ラスタータイルへ描画し、プロセス共有の
 * ローカルタイルサーバ（[TileServerRegistry]）に登録する。その URL テンプレートを持つ
 * [RasterLayerState]（UrlTemplate）を返し、呼び出し側（コントローラ）が既存のラスターレイヤ
 * コントローラへ upsert することで、マーカーがラスターレイヤとして表示される。
 */
class MapTilerMarkerTileRenderer(
    private val markerTiling: MarkerTilingOptions,
) {
    private val markerManager = MarkerManager.defaultManager<Unit>(minMarkerCount = markerTiling.minMarkerCount)
    private val tileServer = TileServerRegistry.get()
    private var tileRenderer: MarkerTileRenderer<Unit>? = null
    private var groupId: String? = null
    private var cacheVersion: Int = 0
    private var rasterState: RasterLayerState? = null

    /** ヒットテスト用に保持する現在のマーカー一覧。 */
    var markers: List<MarkerState> = emptyList()
        private set

    /**
     * マーカーをタイルへ再描画し、表示すべき [RasterLayerState] を返す。
     * マーカーが無ければ null（＝ラスターレイヤ削除）。
     */
    fun render(markers: List<MarkerState>): RasterLayerState? {
        this.markers = markers
        markerManager.clear()
        // ドラッグ／アニメーション対象はタイリングに載せない（対話的マーカーは別経路）。
        markers.filter { !it.draggable && it.getAnimation() == null }.forEach { state ->
            markerManager.registerEntity(
                MarkerEntity(marker = null, state = state, visible = true, isRendered = true, tiling = true),
            )
        }
        if (markerManager.allEntities().isEmpty()) {
            rasterState = null
            return null
        }

        val renderer = getOrCreateTileRenderer()
        // マーカー変更時はキャッシュを破棄して再取得させる（URL の ?v= を更新）。
        cacheVersion = (cacheVersion + 1) and 0x7fffffff
        renderer.invalidate()

        val gid = groupId!!
        val state =
            RasterLayerState(
                id = "marker-tile-$gid",
                source =
                    RasterLayerSource.UrlTemplate(
                        template = "${tileServer.urlTemplate(gid, renderer.tileSize)}?v=$cacheVersion",
                        tileSize = renderer.tileSize,
                        maxZoom = 22,
                        scheme = TileScheme.XYZ,
                    ),
                opacity = 1.0f,
                visible = true,
            )
        rasterState = state
        return state
    }

    /** 現在のマーカータイル・ラスターレイヤ id（未生成なら null）。 */
    val rasterLayerId: String?
        get() = rasterState?.id

    fun clear() {
        groupId?.let { tileServer.unregister(it) }
        groupId = null
        tileRenderer = null
        rasterState = null
        markerManager.clear()
        markers = emptyList()
    }

    /**
     * タップ座標に当たるマーカーを返す。選び方はコアの [MarkerHitPick.pick] と同じで、
     * 指の下にアイコンかラベルが描かれているマーカーが、先端の近い別のマーカーより優先する。
     *
     * @param project 地理座標をビューの画面座標へ投影する（holder の toScreenOffset）。
     */
    @OptIn(InternalMapConductorApi::class)
    fun findMarkerAt(
        tap: GeoPointInterface,
        project: (GeoPointInterface) -> Offset?,
    ): MarkerState? {
        if (markers.isEmpty()) return null
        val touchScreen = project(tap) ?: return null
        val bounds = MarkerHitPick.searchBounds(tap, touchScreen, project) ?: return null
        val candidates = markers.filter { bounds.contains(it.position) }
        return MarkerHitPick.pickBy(touchScreen, candidates, { it }, project)
    }

    /**
     * 旧来の判定（先端からの距離だけを見る）。ラベルやアイコンの大きさを見ないので、
     * 指の下に描かれているマーカーより先端の近い別のマーカーを選ぶことがある。
     */
    @Deprecated(
        "先端からの距離だけで選ぶ旧来の判定。findMarkerAt(tap, project) を使う。",
        ReplaceWith("findMarkerAt(tap, project)"),
    )
    fun findMarkerAt(
        tap: GeoPointInterface,
        nativeZoom: Double,
    ): MarkerState? {
        if (markers.isEmpty()) return null
        val latRad = Math.toRadians(tap.latitude)
        val metersPerPixel = EARTH_CIRCUMFERENCE_M * cos(latRad) / (TILE_SIZE * 2.0.pow(nativeZoom))
        val thresholdMeters = TAP_THRESHOLD_PX * metersPerPixel

        var best: MarkerState? = null
        var bestDistance = Double.MAX_VALUE
        markers.forEach { marker ->
            val dLat = (marker.position.latitude - tap.latitude) * METERS_PER_DEGREE
            val dLng = (marker.position.longitude - tap.longitude) * METERS_PER_DEGREE * cos(latRad)
            val distance = sqrt(dLat * dLat + dLng * dLng)
            if (distance < thresholdMeters && distance < bestDistance) {
                bestDistance = distance
                best = marker
            }
        }
        return best
    }

    private fun getOrCreateTileRenderer(): MarkerTileRenderer<Unit> {
        tileRenderer?.let { return it }
        val gid = UUID.randomUUID().toString()
        groupId = gid
        val renderer =
            MarkerTileRenderer<Unit>(
                markerManager = markerManager,
                tileSize = TILE_SIZE.toInt(),
                cacheSizeBytes = markerTiling.cacheSize,
                debugTileOverlay = markerTiling.debugTileOverlay,
                iconScaleCallback = markerTiling.iconScaleCallback,
                declutterPx = markerTiling.declutterPx,
            )
        tileRenderer = renderer
        tileServer.register(gid, renderer)
        return renderer
    }

    private companion object {
        const val TILE_SIZE = 256.0
        const val EARTH_CIRCUMFERENCE_M = 40075016.686
        const val METERS_PER_DEGREE = 111320.0
        const val TAP_THRESHOLD_PX = 24.0
    }
}
