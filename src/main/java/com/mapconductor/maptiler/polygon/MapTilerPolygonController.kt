package com.mapconductor.maptiler.polygon

import com.mapconductor.core.polygon.PolygonController
import com.mapconductor.core.polygon.PolygonManager
import com.mapconductor.core.polygon.PolygonManagerInterface

/**
 * MapTiler 用のポリゴンコントローラ。
 *
 * 追加・更新・削除の差分計算はコア基底 [PolygonController] が担い、実際のスタイル操作は
 * [MapTilerPolygonOverlayRenderer] へ委譲する。
 */
class MapTilerPolygonController(
    override val renderer: MapTilerPolygonOverlayRenderer,
    polygonManager: PolygonManagerInterface<MapTilerPolygonHandle> = PolygonManager(),
) : PolygonController<MapTilerPolygonHandle>(polygonManager, renderer) {
    /**
     * 既知のポリゴンを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapply() {
        renderer.reapply(polygonManager.allEntities().map { it.polygon })
    }
}
