package com.mapconductor.maptiler

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.maptiler.maptilersdk.map.LngLat
import com.maptiler.maptilersdk.map.types.MTBounds

/**
 * MapConductor コアの座標型と MapTiler SDK の座標型の相互変換ヘルパ。
 *
 * MapTiler SDK は MapLibre GL JS 相当のため、[LngLat] は経度・緯度の順で構築する点に注意する。
 */
internal fun GeoPointInterface.toLngLat(): LngLat = LngLat(longitude, latitude)

internal fun LngLat.toGeoPoint(): GeoPoint = GeoPoint(latitude = lat, longitude = lng)

internal fun GeoRectBounds.toMTBounds(): MTBounds? {
    val sw = southWest ?: return null
    val ne = northEast ?: return null
    return MTBounds(
        west = sw.longitude,
        south = sw.latitude,
        east = ne.longitude,
        north = ne.latitude,
    )
}
