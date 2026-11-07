package com.mapconductor.maptiler

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.AttributionRule
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapUISettings
import com.maptiler.maptilersdk.map.style.MTMapReferenceStyle
import com.maptiler.maptilersdk.map.style.MTMapStyleVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import android.os.Bundle
import android.os.Parcel

/**
 * 回転などの構成変更で state が [MapTilerMapViewSaver] を通って作り直されても、デザイン（id・
 * attributionRules ほか）と uiSettings・カメラが落ちないこと。Bundle は実機の Parcel を一度通す。
 */
@RunWith(AndroidJUnit4::class)
class MapTilerMapViewSaverTest {
    private val rules =
        listOf(AttributionRule("© test"), AttributionRule("© ranged", minZoom = 3, maxZoom = 15))

    private val camera =
        MapCameraPosition(
            position = GeoPoint(21.38, -157.93),
            zoom = 12.0,
            bearing = 20.0,
            tilt = 10.0,
        )

    @Test
    fun keepsCustomStyleAndAttributionRules() {
        // 以前は id だけを保存して fromId で戻しており、定義に無い id は Streets になっていた。
        val design =
            MapTilerDesign(
                id = "my-style",
                referenceStyle = MTMapReferenceStyle.CUSTOM(URL("https://example.com/style.json")),
                attributionRules = rules,
            )
        val state = MapTilerViewState(mapDesignType = design, id = "maptiler-1", initialCameraPosition = camera)
        state.uiSettings = MapUISettings(rotateGesture = false, tiltGesture = false)

        val restored = roundTrip(MapTilerMapViewSaver().createSaver(), state)

        val actual = restored.mapDesignType
        assertEquals("my-style", actual.id)
        val style = actual.referenceStyle
        assertTrue(style is MTMapReferenceStyle.CUSTOM)
        assertEquals("https://example.com/style.json", (style as MTMapReferenceStyle.CUSTOM).url.toString())
        assertEquals(null, actual.variant)
        assertEquals(rules, actual.attributionRules)
        assertCommon(restored, "maptiler-1")
    }

    @Test
    fun keepsReferenceStyleAndVariant() {
        val design = MapTilerDesign("outdoor-dark", MTMapReferenceStyle.OUTDOOR, MTMapStyleVariant.DARK)
        val restored = roundTrip(MapTilerMapViewSaver().createSaver(), MapTilerViewState(design, "maptiler-2"))
        assertEquals(design, restored.mapDesignType)
    }

    @Test
    fun keepsPresetDesign() {
        val restored = roundTrip(MapTilerMapViewSaver().createSaver(), MapTilerViewState(MapTilerDesign.StreetsDark, "maptiler-3"))
        assertEquals(MapTilerDesign.StreetsDark, restored.mapDesignType)
    }

    private fun assertCommon(
        restored: com.mapconductor.core.map.MapViewStateInterface<*>,
        id: String,
    ) {
        assertEquals(id, restored.id)
        assertEquals(false, restored.uiSettings.rotateGesture)
        assertEquals(false, restored.uiSettings.tiltGesture)
        assertEquals(true, restored.uiSettings.scrollGesture)
        assertEquals(12.0, restored.cameraPosition.zoom, 1e-9)
        assertEquals(20.0, restored.cameraPosition.bearing, 1e-9)
        assertEquals(21.38, restored.cameraPosition.position.latitude, 1e-9)
    }

    private fun <T : Any> roundTrip(
        saver: Saver<T, Bundle>,
        value: T,
    ): T {
        val saved = with(saver) { SaverScope { true }.save(value) }!!
        // 一度 Parcel に書いて読み戻す（プロセスが落ちたあとの復元と同じ経路）。
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            return saver.restore(parcel.readBundle(javaClass.classLoader)!!)!!
        } finally {
            parcel.recycle()
        }
    }
}
