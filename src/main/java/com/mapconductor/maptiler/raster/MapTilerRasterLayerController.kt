package com.mapconductor.maptiler.raster

import com.mapconductor.core.raster.RasterLayerController
import com.mapconductor.core.raster.RasterLayerManager
import com.mapconductor.core.raster.RasterLayerManagerInterface

/**
 * MapTiler 用のラスターレイヤコントローラ。
 *
 * 追加・更新・削除の差分計算はコア基底 [RasterLayerController] が担い、実際のスタイル操作は
 * [MapTilerRasterLayerOverlayRenderer] へ委譲する。
 */
class MapTilerRasterLayerController(
    rasterLayerManager: RasterLayerManagerInterface<MapTilerRasterLayerHandle> = RasterLayerManager(),
    override val renderer: MapTilerRasterLayerOverlayRenderer,
) : RasterLayerController<MapTilerRasterLayerHandle>(rasterLayerManager, renderer) {
    /**
     * 既知のラスターレイヤを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapply() {
        renderer.reapply(rasterLayerManager.allEntities().map { it.layer })
    }
}
