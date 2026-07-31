package com.mapconductor.maptiler.polyline

import com.mapconductor.core.polyline.PolylineController
import com.mapconductor.core.polyline.PolylineManager
import com.mapconductor.core.polyline.PolylineManagerInterface

/**
 * MapTiler 用のポリラインコントローラ。
 *
 * 追加・更新・削除の差分計算はコア基底 [PolylineController] が担い、実際のスタイル操作は
 * [MapTilerPolylineOverlayRenderer] へ委譲する。
 */
class MapTilerPolylineController(
    override val renderer: MapTilerPolylineOverlayRenderer,
    polylineManager: PolylineManagerInterface<MapTilerPolylineHandle> = PolylineManager(),
) : PolylineController<MapTilerPolylineHandle>(polylineManager, renderer) {
    /**
     * 既知のポリラインを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapply() {
        renderer.reapply(polylineManager.allEntities().map { it.polyline })
    }
}
