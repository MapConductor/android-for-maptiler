package com.mapconductor.maptiler.circle

import com.maptiler.maptilersdk.map.style.layer.fill.MTFillLayer
import com.maptiler.maptilersdk.map.style.layer.line.MTLineLayer
import com.maptiler.maptilersdk.map.style.source.MTGeoJSONSource

/**
 * MapTiler の円（GeoJSON ソース＋塗りレイヤ＋輪郭レイヤ）を参照するハンドル。
 *
 * 円は中心・半径から生成した多角形リングとして表現し、ポリゴンと同じく塗り・輪郭の 2 レイヤで描画する。
 */
data class MapTilerCircleHandle(
    val sourceId: String,
    val fillLayerId: String,
    val lineLayerId: String,
    val source: MTGeoJSONSource,
    val fillLayer: MTFillLayer,
    val lineLayer: MTLineLayer,
)
