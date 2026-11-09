package com.mapconductor.maptiler.raster

import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.polygon.POLYGON_TILE_LAYER_ID
import com.mapconductor.core.raster.RasterHeaderRuleSet
import com.mapconductor.core.raster.RasterLayerEntityInterface
import com.mapconductor.core.raster.RasterLayerOverlayRendererInterface
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.maptiler.MapTilerOverlayStacking
import com.mapconductor.maptiler.removeLayerAndForget
import com.mapconductor.maptiler.removeSourceAndForget
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.style.MTTileScheme
import com.maptiler.maptilersdk.map.style.dsl.PropertyValue
import com.maptiler.maptilersdk.map.style.layer.MTLayerVisibility
import com.maptiler.maptilersdk.map.style.layer.raster.MTRasterLayer
import com.maptiler.maptilersdk.map.style.source.MTRasterTileSource
import com.maptiler.maptilersdk.map.style.source.MTSourceType
import java.net.URL
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * ラスタータイルを MapTiler（MapLibre GL JS / WebView）のスタイルへ反映するレンダラ。
 *
 * MapTiler SDK はベクター系オーバーレイ（マーカー等）とは異なり、ラスターについては
 * [MTRasterTileSource] と [MTRasterLayer] を `MTStyle.addSource` / `addLayer` で追加できるため、
 * 他プロバイダと同じ [RasterLayerOverlayRendererInterface] 契約を満たす形で実装できる。
 *
 * 追加は「ソース → レイヤ」の順に行う（[com.maptiler.maptilersdk.map.style.MTStyle.addLayer] は
 * 対象ソースが未登録だと例外となり、タイル読み込み完了まで内部キューで待機する）。
 */
class MapTilerRasterLayerOverlayRenderer(
    private val mtController: MTMapViewController,
    override val coroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : RasterLayerOverlayRendererInterface<MapTilerRasterLayerHandle> {
    /**
     * MapTiler Android は WebView(MapLibre GL JS) 越しにタイルを取るため、
     * ネイティブ側からリクエストヘッダを差し替える口が無い。
     */
    override suspend fun onAdd(
        data: List<RasterLayerOverlayRendererInterface.AddParamsInterface>,
    ): List<MapTilerRasterLayerHandle?> =
        data.map {
            RasterHeaderRuleSet.warnUnsupported(provider = "MapTiler", state = it.state)
            addLayer(it.state)
        }

    override suspend fun onChange(
        data: List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<MapTilerRasterLayerHandle>>,
    ): List<MapTilerRasterLayerHandle?> =
        data.map { params ->
            val prev = params.prev
            val next = params.current.state
            if (prev.state.source != next.source) {
                removeLayer(prev)
                // Under new ids: the removal above is not applied before the
                // add below, which then fails with "Source already exists" and
                // leaves the layer gone. The marker and polygon tiles change
                // source on every in-place change.
                addLayer(next, generation = ++replacements)
            } else {
                updatePaint(prev.layer, next)
                prev.layer
            }
        }

    override suspend fun onRemove(data: List<RasterLayerEntityInterface<MapTilerRasterLayerHandle>>) {
        data.forEach { removeLayer(it) }
    }

    override suspend fun onPostProcess() {}

    /**
     * 既知のラスターレイヤを全て再追加する（地図 `ready` 後やスタイル再読込後の復元用）。
     */
    fun reapply(handles: List<MapTilerRasterLayerHandle>) {
        val style = mtController.style ?: return
        handles.forEach { handle ->
            runCatching { style.addSource(handle.source) }
            runCatching { MapTilerOverlayStacking.add(style, handle.layer, stackKey(handle.layer.identifier)) }
        }
    }

    /** Counts source replacements, so each one gets ids of its own. */
    private var replacements = 0

    /** 層の id → zIndex（帯の中の順）。handle は公開型なので持たせずここで覚える。 */
    private val zIndexByLayer = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * ラスターの帯（一番下）。マーカーのタイルはマーカーの帯の一番下、
     * ポリゴンのタイルはポリゴンの帯の一番下（画像になったポリゴンであって、地図の画像ではない）。
     */
    private fun stackKey(layerId: String) =
        if (layerId.startsWith("raster-layer-marker-tile-")) {
            MapTilerOverlayStacking.MARKER_TILE
        } else if (layerId.startsWith("raster-layer-$POLYGON_TILE_LAYER_ID")) {
            MapTilerOverlayStacking.POLYGON_TILE
        } else {
            MapTilerOverlayStacking.key(OverlayKind.RasterLayer, zIndexByLayer[layerId] ?: 0)
        }

    private fun addLayer(
        state: RasterLayerState,
        generation: Int = 0,
    ): MapTilerRasterLayerHandle? {
        val suffix = if (generation == 0) "" else "-$generation"
        val sourceId = "raster-source-${state.id}$suffix"
        val layerId = "raster-layer-${state.id}$suffix"
        zIndexByLayer[layerId] = state.zIndex
        val source = buildSource(sourceId, state.source) ?: return null

        val layer =
            MTRasterLayer(layerId, sourceId).apply {
                opacity = state.opacity.coerceIn(0f, 1f).toDouble()
                visibility = if (state.visible) MTLayerVisibility.VISIBLE else MTLayerVisibility.NONE
                (state.source as? RasterLayerSource.UrlTemplate)?.let { url ->
                    url.minZoom?.let { minZoom = it.toDouble() }
                    url.maxZoom?.let { maxZoom = it.toDouble() }
                }
            }

        val handle = MapTilerRasterLayerHandle(sourceId, layerId, source, layer)

        val style = mtController.style ?: return handle
        // ソース → レイヤの順に追加する。既存の場合の例外は無視する。
        runCatching { style.addSource(source) }
            .onFailure { Log.w(TAG, "addSource failed: ${it.message}") }
        runCatching { MapTilerOverlayStacking.add(style, layer, stackKey(layer.identifier)) }
            .onFailure { Log.w(TAG, "addLayer failed: ${it.message}") }
        return handle
    }

    private fun updatePaint(
        handle: MapTilerRasterLayerHandle,
        state: RasterLayerState,
    ) {
        val style = mtController.style ?: return
        val opacity = state.opacity.coerceIn(0f, 1f).toDouble()
        runCatching {
            style.setPaintProperty(handle.layerId, RASTER_OPACITY, PropertyValue.of(opacity))
            style.setLayoutProperty(
                handle.layerId,
                VISIBILITY,
                PropertyValue.of(if (state.visible) "visible" else "none"),
            )
        }.onFailure { Log.w(TAG, "updatePaint failed: ${it.message}") }
    }

    private fun removeLayer(entity: RasterLayerEntityInterface<MapTilerRasterLayerHandle>) {
        val style = mtController.style ?: return
        val handle = entity.layer
        // id で外すと MTStyle の記録が残り、同じ id で戻せなくなる（MapTilerStyleRemoval.kt）。
        // ソースを替えるとき（onChange）も同じ id で足し直すので、ここで記録まで消す。
        MapTilerOverlayStacking.remove(style, handle.layer.identifier)
        zIndexByLayer.remove(handle.layer.identifier)
        style.removeLayerAndForget(handle.layer)
        style.removeSourceAndForget(handle.source)
    }

    private fun buildSource(
        sourceId: String,
        source: RasterLayerSource,
    ): MTRasterTileSource? =
        when (source) {
            is RasterLayerSource.UrlTemplate -> {
                val tileUrl = source.template.toUrlOrNull() ?: return null
                MTRasterTileSource(
                    identifier = sourceId,
                    attribution = null,
                    bounds = doubleArrayOf(-180.0, -85.051129, 180.0, 85.051129),
                    maxZoom = source.maxZoom?.toDouble() ?: 22.0,
                    minZoom = source.minZoom?.toDouble() ?: 0.0,
                    tiles = arrayOf(tileUrl),
                    url = null,
                    type = MTSourceType.RASTER,
                    scheme = if (source.scheme == TileScheme.TMS) MTTileScheme.TMS else MTTileScheme.XYZ,
                    tileSize = source.tileSize,
                )
            }

            is RasterLayerSource.TileJson -> {
                val url = source.url.toUrlOrNull() ?: return null
                MTRasterTileSource(identifier = sourceId, url = url)
            }

            is RasterLayerSource.ArcGisService -> {
                val base = source.serviceUrl.trimEnd('/')
                val tileUrl = "$base/tile/{z}/{y}/{x}".toUrlOrNull() ?: return null
                MTRasterTileSource(
                    identifier = sourceId,
                    attribution = null,
                    bounds = doubleArrayOf(-180.0, -85.051129, 180.0, 85.051129),
                    maxZoom = 22.0,
                    minZoom = 0.0,
                    tiles = arrayOf(tileUrl),
                    url = null,
                    type = MTSourceType.RASTER,
                    scheme = MTTileScheme.XYZ,
                    tileSize = RasterLayerSource.DEFAULT_TILE_SIZE,
                )
            }
        }

    private fun String.toUrlOrNull(): URL? = runCatching { URL(this) }.getOrNull()

    private companion object {
        const val TAG = "MapTilerRaster"
        const val RASTER_OPACITY = "raster-opacity"
        const val VISIBILITY = "visibility"
    }
}

/**
 * MapTiler のラスターレイヤ実体（ソース＋レイヤ）を参照するハンドル。
 */
data class MapTilerRasterLayerHandle(
    val sourceId: String,
    val layerId: String,
    val source: MTRasterTileSource,
    val layer: MTRasterLayer,
)
