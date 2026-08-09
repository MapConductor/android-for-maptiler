package com.mapconductor.maptiler.zoom

import com.mapconductor.core.zoom.WebMercatorZoomAltitudeConverter

/**
 * 統一ズーム（Google Maps 基準・256px タイル）⇄ 高度の変換。
 *
 * MapTiler は 512px タイルのベクタエンジンなので、統一ズームはネイティブズーム + 1。
 * 換算式はコアの [WebMercatorZoomAltitudeConverter] にある。
 */
class ZoomAltitudeConverter(
    zoom0Altitude: Double = DEFAULT_ZOOM0_ALTITUDE,
) : WebMercatorZoomAltitudeConverter(zoom0Altitude, zoomOffset = MAPTILER_TO_GOOGLE_ZOOM_OFFSET) {
    companion object {
        /**
         * 実測のオフセット:
         * GoogleZoom ≈ MapTilerSDK.zoom + 1.0
         */
        const val MAPTILER_TO_GOOGLE_ZOOM_OFFSET = 1.0

        fun maptilerZoomToGoogleZoom(maptilerZoom: Double): Double =
            (maptilerZoom + MAPTILER_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)

        fun googleZoomToMaptilerZoom(googleZoom: Double): Double =
            (googleZoom - MAPTILER_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
    }
}
