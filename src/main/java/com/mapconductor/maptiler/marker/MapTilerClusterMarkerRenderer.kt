package com.mapconductor.maptiler.marker

import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.OnMarkerEventHandler

/**
 * マーカークラスタリング（android-marker-clustering）用のマーカーレンダラ。
 *
 * クラスタリングモジュールは [com.mapconductor.core.marker.MarkerRenderingSupport] 経由でこのレンダラを
 * 生成し、ズーム／パンに応じて算出したクラスタ／単体マーカー（[MarkerState]）を onAdd/onChange/onRemove
 * で流し込む。本実装はそれらを収集して [onMarkersChanged] へ渡すだけの薄いアダプタで、実際の描画は
 * Composable 側の [com.maptiler.maptilersdk.annotations.MTCustomAnnotationView] オーバーレイが担う。
 *
 * これにより、少数（クラスタ＋可視単体）のマーカーはコンポーズオーバーレイとして描画され、地図追従・
 * タップ（クラスタは拡大、単体は onClick）が既存経路でそのまま機能する。
 *
 * ActualMarker はクラスタリングモジュールが `Any` 前提で扱うため [Any]（実体はマーカー id 文字列）を用いる。
 */
class MapTilerClusterMarkerRenderer(
    override val holder: MapViewHolderInterface<*, *>,
    private val onMarkersChanged: (List<MarkerState>) -> Unit,
) : MarkerOverlayRendererInterface<Any> {
    override var animateStartListener: OnMarkerEventHandler? = null
    override var animateEndListener: OnMarkerEventHandler? = null

    private val current = LinkedHashMap<String, MarkerState>()

    override suspend fun onAdd(data: List<MarkerOverlayRendererInterface.AddParamsInterface>): List<Any?> {
        data.forEach { current[it.state.id] = it.state }
        return data.map { it.state.id }
    }

    override suspend fun onChange(data: List<MarkerOverlayRendererInterface.ChangeParamsInterface<Any>>): List<Any?> {
        data.forEach { current[it.current.state.id] = it.current.state }
        return data.map { it.current.state.id }
    }

    override suspend fun onRemove(data: List<MarkerEntityInterface<Any>>) {
        data.forEach { current.remove(it.state.id) }
    }

    override suspend fun onAnimate(entity: MarkerEntityInterface<Any>) {
        // 位置アニメーションは扱わない（クラスタ表示自体は onPostProcess の反映で成立する）。
    }

    override suspend fun onPostProcess() {
        onMarkersChanged(current.values.toList())
    }
}
