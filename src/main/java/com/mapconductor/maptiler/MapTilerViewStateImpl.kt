package com.mapconductor.maptiler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mapconductor.compose.map.BaseMapViewSaver
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapCameraPositionInterface
import com.mapconductor.core.map.MapViewState
import android.os.Bundle

/**
 * MapTiler 用の [MapViewState] 実装インターフェース。
 * 他プロバイダの `*ViewStateInterface` と同様に、example-app の型ディスパッチで用いられる。
 */
interface MapTilerViewStateInterface : com.mapconductor.core.map.MapViewStateInterface<MapTilerMapDesignTypeInterface>

/**
 * MapTiler の地図状態。カメラ位置・デザイン・コントローラを保持し、
 * MapConductor コアのカメラ操作 API を [MapTilerMapViewController] へ委譲する。
 */
class MapTilerViewState(
    mapDesignType: MapTilerMapDesignTypeInterface,
    override val id: String,
    initialCameraPosition: MapCameraPosition = MapCameraPosition.Default,
) : MapViewState<MapTilerMapDesignTypeInterface>(
        initialCameraPosition = initialCameraPosition,
        // WebView ブリッジ越しでカメライベントの往復が遅い。楽観更新しないと、
        // moveCameraTo の直後に cameraPosition を読んだとき古い値が返る。
        optimisticCameraUpdate = true,
    ),
    MapTilerViewStateInterface {
    private var _mapDesignType by mutableStateOf(mapDesignType)

    private var controller: MapTilerMapViewController? = null

    override var mapDesignType: MapTilerMapDesignTypeInterface
        get() = _mapDesignType
        set(value) {
            _mapDesignType = value
            controller?.setMapDesignType(value)
        }

    /** MapView 生成時にコントローラを紐付ける。 */
    fun setController(controller: MapTilerMapViewController) {
        this.controller = controller
        // 初期カメラは MTMapOptions で地図生成時に渡しているので、接続時には移動しない。
        attachController(controller, moveToInitialCamera = false)
    }

    /** 現在のカメラ位置を更新する（地図移動イベントからの反映用）。 */
    fun updateCameraPosition(position: MapCameraPosition) {
        setCameraPositionInternal(position)
    }

    /** 戻り型をこのプロバイダのホルダーへ絞る（アプリが `?.map` を取れる形を保つため）。 */
    override fun getMapViewHolder(): MapTilerMapViewHolder? = super.getMapViewHolder() as? MapTilerMapViewHolder
}

/**
 * [MapTilerViewState] の保存・復元を行う Saver。
 * デザインは [MapTilerMapDesignTypeInterface.id] を保存し、[MapTilerDesign.fromId] で復元する。
 */
class MapTilerMapViewSaver : BaseMapViewSaver<MapTilerViewState>() {
    override fun saveMapDesign(
        state: MapTilerViewState,
        bundle: Bundle,
    ) {
        bundle.putString(KEY_DESIGN_ID, state.mapDesignType.id)
    }

    override fun createState(
        stateId: String,
        mapDesignBundle: Bundle?,
        cameraPosition: MapCameraPosition,
    ): MapTilerViewState {
        val designId = mapDesignBundle?.getString(KEY_DESIGN_ID)
        return MapTilerViewState(
            mapDesignType = MapTilerDesign.fromId(designId),
            id = stateId,
            initialCameraPosition = cameraPosition,
        )
    }

    override fun getStateId(state: MapTilerViewState): String = state.id

    private companion object {
        const val KEY_DESIGN_ID = "maptiler_design_id"
    }
}

/**
 * [MapTilerViewState] を生成・記憶する Composable ファクトリ。
 *
 * @param mapDesign 初期の地図デザイン。
 * @param cameraPosition 初期カメラ位置。
 */
@Composable
fun rememberMapTilerMapViewState(
    mapDesign: MapTilerMapDesignTypeInterface = MapTilerDesign.Streets,
    cameraPosition: MapCameraPositionInterface = MapCameraPosition.Default,
): MapTilerViewState {
    val initialCamera =
        cameraPosition as? MapCameraPosition
            ?: MapCameraPosition(
                position = cameraPosition.position,
                zoom = cameraPosition.zoom,
                bearing = cameraPosition.bearing,
                tilt = cameraPosition.tilt,
                paddings = cameraPosition.paddings,
            )
    return rememberSaveable(saver = MapTilerMapViewSaver().createSaver()) {
        MapTilerViewState(
            mapDesignType = mapDesign,
            id = "maptiler-${initialCamera.hashCode()}",
            initialCameraPosition = initialCamera,
        )
    }
}
