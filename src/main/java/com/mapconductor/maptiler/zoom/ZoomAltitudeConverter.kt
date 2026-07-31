package com.mapconductor.maptiler.zoom

import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * MapTiler SDK は MapLibre GL JS ベース（512px タイル / web mercator）のため、Google Maps
 * （256px タイル）とはズームが約 1.0 ずれる（`GoogleZoom ≈ MapTilerZoom + 1.0`）。
 * ズーム換算は [MapTilerMapViewController] の `coreZoomToMapTiler` / `mapTilerZoomToCore` と同一。
 *
 * このクラスは tilt < 0 の擬似表現に必要な高度（altitude）計算のために追加した。
 * ロジックは MapLibre 実装（android-for-maplibre の zoom.ZoomAltitudeConverter）と同一。
 */
class ZoomAltitudeConverter(
    zoom0Altitude: Double = DEFAULT_ZOOM0_ALTITUDE,
) : AbstractZoomAltitudeConverter(zoom0Altitude) {
    companion object {
        /** `GoogleZoom ≈ MapTilerZoom + 1.0`（MapLibre 実装と同一値）。 */
        const val MAPTILER_TO_GOOGLE_ZOOM_OFFSET = 1.0

        fun maptilerZoomToGoogleZoom(maptilerZoom: Double): Double =
            (maptilerZoom + MAPTILER_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)

        fun googleZoomToMaptilerZoom(googleZoom: Double): Double =
            (googleZoom - MAPTILER_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
    }

    private fun cosLatitudeFactor(latitudeDeg: Double): Double {
        val clampedLat = latitudeDeg.coerceIn(-85.0, 85.0)
        val latRad = Math.toRadians(clampedLat)
        return max(MIN_COS_LAT, abs(cos(latRad)))
    }

    private fun cosTiltFactor(tiltDeg: Double): Double {
        val clampedTilt = tiltDeg.coerceIn(0.0, 90.0)
        val tiltRad = Math.toRadians(clampedTilt)
        return max(MIN_COS_TILT, cos(tiltRad))
    }

    override fun zoomLevelToAltitude(
        zoomLevel: Double,
        latitude: Double,
        tilt: Double,
    ): Double {
        val googleZoom = maptilerZoomToGoogleZoom(zoomLevel)
        val cosLat = cosLatitudeFactor(latitude)
        val cosTilt = cosTiltFactor(tilt)
        val distance = (zoom0Altitude * cosLat) / ZOOM_FACTOR.pow(googleZoom)
        val altitude = distance * cosTilt
        return altitude.coerceIn(MIN_ALTITUDE, MAX_ALTITUDE)
    }

    override fun altitudeToZoomLevel(
        altitude: Double,
        latitude: Double,
        tilt: Double,
    ): Double {
        val clampedAltitude = altitude.coerceIn(MIN_ALTITUDE, MAX_ALTITUDE)
        val cosLat = cosLatitudeFactor(latitude)
        val cosTilt = cosTiltFactor(tilt)
        val distance = clampedAltitude / cosTilt
        val googleZoom = log2((zoom0Altitude * cosLat) / distance)
        return googleZoomToMaptilerZoom(googleZoom)
    }
}
