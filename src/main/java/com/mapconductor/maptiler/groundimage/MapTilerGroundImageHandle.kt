package com.mapconductor.maptiler.groundimage

import com.maptiler.maptilersdk.map.style.layer.raster.MTRasterLayer
import com.maptiler.maptilersdk.map.style.source.MTImageSource

/**
 * MapTiler のグラウンドイメージ（画像ソース＋ラスターレイヤ）を参照するハンドル。
 *
 * 変更検出のため、適用済みの bounds／image／opacity のハッシュ（[appliedBounds] 等）を保持する。
 */
data class MapTilerGroundImageHandle(
    val sourceId: String,
    val layerId: String,
    val source: MTImageSource,
    val layer: MTRasterLayer,
    val appliedBounds: Int,
    val appliedImage: Int,
    val appliedOpacity: Int,
)
