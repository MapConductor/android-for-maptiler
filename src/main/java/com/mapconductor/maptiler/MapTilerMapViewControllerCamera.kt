package com.mapconductor.maptiler

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapGesture
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.map.MapUISettingsDiagnostics
import com.mapconductor.core.map.VisibleRegion
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.maptiler.zoom.ZoomAltitudeConverter
import com.maptiler.maptilersdk.map.gestures.MTGestureType
import com.maptiler.maptilersdk.map.options.MTCameraOptions
import com.maptiler.maptilersdk.map.options.MTFitBoundsOptions
import com.maptiler.maptilersdk.map.options.MTFlyToOptions
import com.maptiler.maptilersdk.map.options.MTPaddingOptions
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan

// カメラの読み書きとジェスチャ設定。
// MapTiler のズームは MapConductor の論理ズームと 1 段ずれるため
// ZoomAltitudeConverter を通す。範囲制限はネイティブ API が無いので
// カメラ停止時にクランプして再適用する。

/**
 * MapTiler から現在のカメラ状態を取得し、可視領域（[VisibleRegion]）を付与した [MapCameraPosition] を返す。
 * クラスタリングは `visibleRegion.bounds` を必要とするため、[com.maptiler.maptilersdk.map.MTMapViewController.getBounds] を用いる。
 */
internal suspend fun MapTilerMapViewController.currentCameraWithRegion(): MapCameraPosition {
    val center = mtController.getCenter()
    val zoom = mtController.getZoom()
    val bearing = mtController.getBearing()
    val pitch = mtController.getPitch()
    val region =
        runCatching { mtController.getBounds() }.getOrNull()?.let { bounds ->
            VisibleRegion(
                bounds =
                    GeoRectBounds(
                        southWest = GeoPoint(bounds.southwest.lat, bounds.southwest.lng),
                        northEast = GeoPoint(bounds.northeast.lat, bounds.northeast.lng),
                    ),
                nearLeft = null,
                nearRight = null,
                farLeft = null,
                farRight = null,
            )
        }
    val raw =
        MapCameraPosition(
            position = center.toGeoPoint(),
            zoom = MapTilerMapViewController.mapTilerZoomToCore(zoom),
            bearing = bearing,
            tilt = pitch,
            visibleRegion = region,
        )
    return recoverLogicalCameraPosition(raw)
}

/**
 * 生のカメラ状態（MapTiler の正ピッチ・統一ズーム換算済み）から、直近に要求した論理 tilt が
 * 負のときに元の位置・ズーム・負tilt を復元した [MapCameraPosition] を返す。
 * tilt < 0 の擬似表現（[toCameraOptions]）の逆変換で、MapLibre 実装と同一ロジック。
 */
internal fun MapTilerMapViewController.recoverLogicalCameraPosition(raw: MapCameraPosition): MapCameraPosition {
    val logicalTiltHint = lastLogicalCameraPosition?.tilt
    val pitchAbsDeg = abs(raw.tilt).coerceIn(0.0, 60.0)
    if (logicalTiltHint == null || logicalTiltHint >= 0.0 || pitchAbsDeg == 0.0) return raw

    val pitchAbsRad = Math.toRadians(pitchAbsDeg)
    val shiftedCenter = raw.position
    val originalGoogleZoom =
        raw.zoom - MapTilerMapViewController.NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT * (pitchAbsDeg / 60.0)
    val altitude =
        zoomConverter.zoomLevelToAltitude(
            ZoomAltitudeConverter.googleZoomToMaptilerZoom(originalGoogleZoom),
            shiftedCenter.latitude,
            0.0,
        )
    val distanceBackward =
        altitude * cos(pitchAbsRad) * tan(pitchAbsRad) * MapTilerMapViewController.NEGATIVE_TILT_TARGET_DISTANCE_SCALE
    val originalPosition = Spherical.computeOffset(shiftedCenter, distanceBackward, raw.bearing + 180.0)

    return raw.copy(
        position = originalPosition,
        zoom = originalGoogleZoom,
        tilt = -pitchAbsDeg,
    )
}

internal fun MapTilerMapViewController.handleMoveCamera(position: MapCameraPosition) {
    lastLogicalCameraPosition = position
    mtController.jumpTo(toCameraOptions(position))
}

internal fun MapTilerMapViewController.handleAnimateCamera(
    position: MapCameraPosition,
    duration: Long,
) {
    lastLogicalCameraPosition = position
    // easeTo は線形補間で、しかも MapTiler SDK ラッパーが duration を渡せないため MapLibre GL JS
    // 既定の約 300ms 固定で動く。ズーム差が大きい移動（例: 世界 z0 → 都市 z10）では一気にズームして
    // タイル読み込みが追いつかず激しくカクつく。flyTo（van Wijk のズームアーク）に切り替え、指定
    // duration を maxDuration として渡すことで、タイル追従しつつ滑らかにアニメーションさせる。
    val flyToOptions =
        MTFlyToOptions(null, null, null, null, null).apply {
            maxDuration = duration.toDouble()
        }
    mtController.flyTo(toCameraOptions(position), flyToOptions)
}

internal fun MapTilerMapViewController.handleFitBounds(
    bounds: GeoRectBounds,
    padding: Int,
) {
    val mtBounds = bounds.toMTBounds() ?: return
    val options =
        MTFitBoundsOptions(
            padding =
                MTPaddingOptions(
                    left = padding.toDouble(),
                    top = padding.toDouble(),
                    right = padding.toDouble(),
                    bottom = padding.toDouble(),
                ),
        )
    mtController.fitBounds(mtBounds, options)
}

internal fun MapTilerMapViewController.applyGestureSettings(settings: MapUISettings) {
    val gestures = mtController.gestureService ?: return

    if (settings.scrollGesture) {
        gestures.enableDragPanGesture()
    } else {
        gestures.disableGesture(MTGestureType.DRAG_PAN)
    }

    if (settings.tiltGesture) {
        gestures.enableTwoFingerDragPitchGesture()
    } else {
        gestures.disableGesture(MTGestureType.TWO_FINGERS_DRAG_PITCH)
    }

    if (settings.zoomGesture) {
        gestures.enableDoubleTapZoomInGesture()
    } else {
        gestures.disableGesture(MTGestureType.DOUBLE_TAP_ZOOM_IN)
    }

    // MapTiler bundles pinch-zoom and rotation into one PINCH_ROTATE_AND_ZOOM
    // gesture, so neither can be switched off alone; only drop it when both are
    // off. The discrete double-tap zoom above still follows zoomGesture.
    if (settings.zoomGesture || settings.rotateGesture) {
        gestures.enablePinchRotateAndZoomGesture()
    } else {
        gestures.disableGesture(MTGestureType.PINCH_ROTATE_AND_ZOOM)
    }

    if (settings.zoomGesture != settings.rotateGesture) {
        MapUISettingsDiagnostics.warnIfRequested(
            false,
            gesture = if (settings.zoomGesture) MapGesture.Rotate else MapGesture.Zoom,
            provider = "MapTiler",
            reason = "pinch zoom and rotation share one gesture, so they can only be disabled together",
        )
    }
}

internal fun MapTilerMapViewController.toCameraOptions(camera: MapCameraPosition): MTCameraOptions {
    if (camera.tilt >= 0) {
        return MTCameraOptions(
            center = camera.position.toLngLat(),
            zoom = MapTilerMapViewController.coreZoomToMapTiler(camera.zoom),
            bearing = camera.bearing,
            pitch = camera.tilt.coerceIn(0.0, 60.0),
        )
    }

    // camera.tilt < 0: MapTiler（MapLibre GL JS）は上向きピッチを直接表現できない。
    // MapLibre 実装と同じ方式で、地上ターゲットを進行方向へ前進させ abs(camera.tilt) の下向き
    // ピッチで描画することで、擬似的に上向き（負tilt）視点を再現する。
    val tiltAbsDeg = abs(camera.tilt).coerceIn(0.0, 60.0)
    val tiltAbsRad = Math.toRadians(tiltAbsDeg)
    val altitude =
        zoomConverter.zoomLevelToAltitude(
            ZoomAltitudeConverter.googleZoomToMaptilerZoom(camera.zoom),
            camera.position.latitude,
            0.0,
        )
    val distanceForward =
        altitude *
            cos(tiltAbsRad) *
            tan(tiltAbsRad) *
            MapTilerMapViewController.NEGATIVE_TILT_TARGET_DISTANCE_SCALE
    val target = Spherical.computeOffset(camera.position, distanceForward, camera.bearing)
    val adjustedZoom =
        camera.zoom + MapTilerMapViewController.NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT * (tiltAbsDeg / 60.0)

    return MTCameraOptions(
        center = target.toLngLat(),
        zoom = MapTilerMapViewController.coreZoomToMapTiler(adjustedZoom),
        bearing = camera.bearing,
        pitch = tiltAbsDeg,
    )
}
