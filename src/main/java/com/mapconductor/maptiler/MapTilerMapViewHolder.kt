package com.mapconductor.maptiler

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapViewHolderInterface
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.types.MTPoint

/**
 * MapTiler 用の [MapViewHolderInterface] 実装。
 *
 * MapTiler SDK は WebView（MapLibre GL JS）ベースで、ネイティブの `MapView` / `Map` を直接公開しない。
 * そのため実体としては [MTMapViewController] を保持し、座標変換はコントローラのブリッジ API 経由で行う。
 */
class MapTilerMapViewHolder(
    private val controller: MTMapViewController,
) : MapViewHolderInterface<MTMapViewController, MTMapViewController> {
    override val mapView: MTMapViewController = controller
    override val map: MTMapViewController = controller

    /**
     * 地理座標 → 画面座標。MapTiler の `project` は suspend（JS ブリッジ）で同期変換できないため null を返す。
     */
    override fun toScreenOffset(position: GeoPointInterface): Offset? = null

    /**
     * 画面座標 → 地理座標。MapTiler の `unproject`（JS ブリッジ）を用いる。
     */
    override suspend fun fromScreenOffset(offset: Offset): GeoPoint? =
        runCatching {
            controller.unproject(MTPoint(offset.x.toDouble(), offset.y.toDouble())).toGeoPoint()
        }.getOrNull()
}
