package com.mapconductor.maptiler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mapconductor.compose.map.BaseMapViewSaver
import com.mapconductor.core.features.GeoPoint
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
) : MapViewState<MapTilerMapDesignTypeInterface>(),
    MapTilerViewStateInterface {
    private var _cameraPosition by mutableStateOf(initialCameraPosition)
    private var _mapDesignType by mutableStateOf(mapDesignType)

    private var controller: MapTilerMapViewController? = null

    override val cameraPosition: MapCameraPosition
        get() = _cameraPosition

    override var mapDesignType: MapTilerMapDesignTypeInterface
        get() = _mapDesignType
        set(value) {
            _mapDesignType = value
            controller?.setMapDesignType(value)
        }

    /** MapView 生成時にコントローラを紐付ける。 */
    fun setController(controller: MapTilerMapViewController) {
        this.controller = controller
    }

    /** 現在のカメラ位置を更新する（地図移動イベントからの反映用）。 */
    fun updateCameraPosition(position: MapCameraPosition) {
        _cameraPosition = position
    }

    override fun moveCameraTo(
        cameraPosition: MapCameraPosition,
        durationMillis: Long?,
    ) {
        _cameraPosition = cameraPosition
        val ctrl = controller ?: return
        if ((durationMillis ?: 0) > 0) {
            ctrl.animateCamera(cameraPosition, durationMillis!!)
        } else {
            ctrl.moveCamera(cameraPosition)
        }
    }

    override fun moveCameraTo(
        position: GeoPoint,
        durationMillis: Long?,
    ) {
        moveCameraTo(_cameraPosition.copy(position = position), durationMillis)
    }

    override fun fitBounds(
        bounds: com.mapconductor.core.features.GeoRectBounds,
        padding: Int,
    ) {
        controller?.fitBounds(bounds, padding)
    }

    override fun getMapViewHolder(): MapTilerMapViewHolder? = controller?.holder as? MapTilerMapViewHolder
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
