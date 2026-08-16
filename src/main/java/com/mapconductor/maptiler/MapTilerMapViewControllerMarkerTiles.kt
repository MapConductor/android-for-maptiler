package com.mapconductor.maptiler

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.maptiler.marker.MapTilerClusterMarkerRenderer
import com.mapconductor.maptiler.marker.MapTilerMarkerTileRenderer
import kotlinx.coroutines.flow.StateFlow

// マーカーをタイル画像として描く経路。
// MapTiler SDK にはネイティブのマーカーが無いため、マーカーを 1 枚のラスターへ
// 焼いて載せる。タップは描画結果ではなく元データへの当たり判定で拾う。

internal fun MapTilerMapViewController.tileRenderer(): MapTilerMarkerTileRenderer =
    markerTileRenderer ?: MapTilerMarkerTileRenderer(
        markerTilingOptions ?: MarkerTilingOptions.Default,
    ).also { markerTileRenderer = it }

internal suspend fun MapTilerMapViewController.renderTiledMarkers(data: List<MarkerState>) {
    val state = tileRenderer().render(data)
    if (state != null) {
        markerRasterId = state.id
        rasterLayerController.upsert(state)
    } else {
        markerRasterId?.let { rasterLayerController.removeById(it) }
        markerRasterId = null
    }
}

/**
 * タップ座標付近のタイリング・マーカーを [MarkerState.onClick] へ配送する。
 *
 * クリックカスケードの先頭。マーカーが受け取ったら下のオーバーレイにも地図クリックにも
 * 流さない（他プロバイダと同じ）。
 *
 * @param point タップ座標。
 * @param nativeZoom MapTiler ネイティブズーム。
 * @return マーカーがタップを消費したら true。
 */
internal fun MapTilerMapViewController.handleMarkerTap(
    point: GeoPoint,
    nativeZoom: Double,
): Boolean {
    val state = markerTileRenderer?.findMarkerAt(point, nativeZoom) ?: return false
    // clickable = false のマーカーは透過させる（コアの clickableOnly と同じ方針）。
    if (!state.clickable) return false
    state.onClick?.invoke(state)
    return true
}

internal fun MapTilerMapViewController.createMarkerRenderingSupport(): MarkerRenderingSupport<Any> =
    object : MarkerRenderingSupport<Any> {
        override fun createMarkerRenderer(
            strategy: MarkerRenderingStrategyInterface<Any>,
        ): MarkerOverlayRendererInterface<Any> =
            // クラスタ／単体マーカーは既存のコンポーズオーバーレイ（[markers] フロー）として描画する。
            MapTilerClusterMarkerRenderer(holder) { rendered -> publishMarkers(rendered) }

        override fun createMarkerEventController(
            controller: StrategyMarkerController<Any>,
            renderer: MarkerOverlayRendererInterface<Any>,
        ): MarkerEventControllerInterface<Any> =
            // クリックはコンポーズのタップ（marker.onClick）で配送するため、イベントコントローラは空実装。
            object : MarkerEventControllerInterface<Any> {}

        override fun registerMarkerEventController(controller: MarkerEventControllerInterface<Any>) = Unit

        override val mapLoadedState: StateFlow<Boolean>
            get() = mapLoaded

        override fun onMarkerRenderingReady() {
            dispatchInitialCameraToOverlays()
        }
    }
