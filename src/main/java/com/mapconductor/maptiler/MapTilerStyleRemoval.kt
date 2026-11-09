package com.mapconductor.maptiler

import com.mapconductor.core.InternalMapConductorApi
import com.maptiler.maptilersdk.map.style.MTStyle
import com.maptiler.maptilersdk.map.style.layer.MTLayer
import com.maptiler.maptilersdk.map.style.source.MTSource

/*
 * MTStyle からレイヤ・ソースを外すときは、id ではなくオブジェクトで外す。
 *
 * MTStyle は addLayer / addSource したものを id で覚えていて、同じ id をもう一度足そうとすると
 * LayerAlreadyExists / SourceAlreadyExists を投げる。ところが removeLayerById / removeSourceById は
 * ページ（MapLibre GL JS）から消すだけで、この記録を消さない。id で外したポリゴン・円・ラスター等を
 * 同じ id（= 同じ state.id）で戻すと add が投げて、地図に戻らなかった。
 * removeLayer(layer) / removeSource(source) は記録も消してからページへ渡す。
 *
 * 記録に無いとき（add の記録は SDK のコルーチンで遅れて入る）は NotFound を投げるので、
 * そのときは id でページから消す。
 */

@InternalMapConductorApi
internal fun MTStyle.removeLayerAndForget(layer: MTLayer) {
    runCatching { removeLayer(layer) }
        .onFailure { runCatching { removeLayerById(layer.identifier) } }
}

@InternalMapConductorApi
internal fun MTStyle.removeSourceAndForget(source: MTSource) {
    runCatching { removeSource(source) }
        .onFailure { runCatching { removeSourceById(source.identifier) } }
}
