package com.mapconductor.maptiler.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.maptiler.MapTilerDesign
import com.mapconductor.maptiler.MapTilerMapView
import com.mapconductor.maptiler.rememberMapTilerMapViewState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleMap()
                }
            }
        }
    }
}

@Composable
private fun SampleMap() {
    val tokyo =
        GeoPoint(
            latitude = 35.6812,
            longitude = 139.7671,
        )
    val mapViewState =
        rememberMapTilerMapViewState(
            mapDesign = MapTilerDesign.Streets,
            cameraPosition =
                MapCameraPosition(
                    position = tokyo,
                    zoom = 11.0,
                ),
        )

    MapTilerMapView(
        state = mapViewState,
        modifier = Modifier.fillMaxSize(),
    )
}
