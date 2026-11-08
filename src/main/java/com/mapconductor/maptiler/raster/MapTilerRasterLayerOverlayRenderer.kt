package com.mapconductor.maptiler.raster

import com.mapconductor.core.raster.RasterHeaderRuleSet
import com.mapconductor.core.raster.RasterLayerEntityInterface
import com.mapconductor.core.raster.RasterLayerOverlayRendererInterface
import com.mapconductor.core.raster.RasterLayerSource
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.core.raster.TileScheme
import com.mapconductor.maptiler.awaitPageLayers
import com.mapconductor.maptiler.removeOwnedLayer
import com.mapconductor.maptiler.removeOwnedSource
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
     *
     * Returns once the layers are on the page's map, so `onAdded` of the
     * layer -- and of the polygons and markers drawn into it as tiles --
     * comes after they are (see [com.mapconductor.maptiler.PageLayerWait]).
     */
    override suspend fun onAdd(
        data: List<RasterLayerOverlayRendererInterface.AddParamsInterface>,
    ): List<MapTilerRasterLayerHandle?> {
        val handles =
            data.map {
                RasterHeaderRuleSet.warnUnsupported(provider = "MapTiler", state = it.state)
                addLayer(it.state)
            }
        awaitOnMap(handles)
        return handles
    }

    /** Returns once a layer added again under a new source is on the map, as [onAdd]. */
    override suspend fun onChange(
        data: List<RasterLayerOverlayRendererInterface.ChangeParamsInterface<MapTilerRasterLayerHandle>>,
    ): List<MapTilerRasterLayerHandle?> {
        val replaced = mutableListOf<MapTilerRasterLayerHandle?>()
        val handles = data.map { params ->
            val prev = params.prev
            val next = params.current.state
            if (prev.state.source != next.source) {
                removeLayer(prev)
                // Under new ids: the removal above is not applied before the
                // add below, which then fails with "Source already exists" and
                // leaves the layer gone. The marker and polygon tiles change
                // source on every in-place change.
                addLayer(next, generation = ++replacements).also { replaced += it }
            } else {
                updatePaint(prev.layer, next)
                prev.layer
            }
        }
        awaitOnMap(replaced)
        return handles
    }

    private suspend fun awaitOnMap(handles: List<MapTilerRasterLayerHandle?>) {
        if (mtController.style == null) return
        mtController.awaitPageLayers(handles.mapNotNull { it?.layerId })
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
            runCatching { style.addLayer(handle.layer) }
        }
    }

    /** Counts source replacements, so each one gets ids of its own. */
    private var replacements = 0

    private fun addLayer(
        state: RasterLayerState,
        generation: Int = 0,
    ): MapTilerRasterLayerHandle? {
        val suffix = if (generation == 0) "" else "-$generation"
        val sourceId = "raster-source-${state.id}$suffix"
        val layerId = "raster-layer-${state.id}$suffix"
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
        runCatching { style.addLayer(layer) }
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
        style.removeOwnedLayer(handle.layer)
        style.removeOwnedSource(handle.source)
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
