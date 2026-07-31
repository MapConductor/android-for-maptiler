package com.mapconductor.maptiler

import com.mapconductor.core.controller.MapViewControllerInterface

/**
 * MapTiler 用のマップコントローラインターフェース。
 *
 * 他プロバイダの `*ViewControllerInterface` と同様に [MapViewControllerInterface] を拡張し、
 * 実行時の地図デザイン変更を追加で公開する。
 */
interface MapTilerMapViewControllerInterface : MapViewControllerInterface {
    /** 実行時に地図デザイン（スタイル）を切り替える。 */
    fun setMapDesignType(value: MapTilerMapDesignTypeInterface)
}
