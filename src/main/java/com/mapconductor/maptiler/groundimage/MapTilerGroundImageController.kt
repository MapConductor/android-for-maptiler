package com.mapconductor.maptiler.groundimage

import com.mapconductor.core.groundimage.GroundImageController
import com.mapconductor.core.groundimage.GroundImageManager
import com.mapconductor.core.groundimage.GroundImageManagerInterface

/**
 * MapTiler 用のグラウンドイメージコントローラ。
 *
 * 追加・更新・削除の差分計算はコア基底 [GroundImageController] が担い、実際のスタイル操作は
 * [MapTilerGroundImageOverlayRenderer] へ委譲する。
 */
class MapTilerGroundImageController(
    override val renderer: MapTilerGroundImageOverlayRenderer,
    groundImageManager: GroundImageManagerInterface<MapTilerGroundImageHandle> = GroundImageManager(),
) : GroundImageController<MapTilerGroundImageHandle>(groundImageManager, renderer) {
    /**
     * 既知のグラウンドイメージを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapply() {
        renderer.reapply(groundImageManager.allEntities().map { it.groundImage })
    }
}
