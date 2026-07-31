package com.mapconductor.maptiler.circle

import com.mapconductor.core.circle.CircleController
import com.mapconductor.core.circle.CircleManager
import com.mapconductor.core.circle.CircleManagerInterface

/**
 * MapTiler 用の円コントローラ。
 *
 * 追加・更新・削除の差分計算はコア基底 [CircleController] が担い、実際のスタイル操作は
 * [MapTilerCircleOverlayRenderer] へ委譲する。
 */
class MapTilerCircleController(
    override val renderer: MapTilerCircleOverlayRenderer,
    circleManager: CircleManagerInterface<MapTilerCircleHandle> = CircleManager(),
) : CircleController<MapTilerCircleHandle>(circleManager, renderer) {
    /**
     * 既知の円を再適用する（地図 `ready` 後の復元用）。
     */
    fun reapply() {
        renderer.reapply(circleManager.allEntities().map { it.circle })
    }
}
