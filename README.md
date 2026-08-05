# MapTiler SDK for MapConductor Android

## Description

MapConductor provides a unified API for Android Jetpack Compose.
You can use MapTiler with Jetpack Compose, but you can also switch to other Maps SDKs
(such as MapLibre, Mapbox, HERE, and so on) at any time using the same API surface.

This module wraps the official [`com.maptiler:maptiler-sdk-kotlin`](https://docs.maptiler.com/mobile-sdk/android/)
(a WebView / MapLibre GL JS based SDK backed by MapTiler Cloud) behind the MapConductor
`MapViewStateInterface` / `MapViewControllerInterface` contracts.

## Setup

Add your MapTiler Cloud API key. This module reads it from an
`AndroidManifest.xml` `<meta-data>` entry (injected by the Secrets Gradle Plugin from
`secrets.properties`):

```xml
<meta-data android:name="MAPTILER_API_KEY" android:value="${MAPTILER_API_KEY}" />
```

Alternatively, set it directly before showing a map:

```kotlin
com.maptiler.maptilersdk.MTConfig.apiKey = "YOUR_MAPTILER_API_KEY"
```

## Usage

```kotlin
@Composable
fun MapView(modifier: Modifier = Modifier) {
    val center = GeoPoint(latitude = 35.6812, longitude = 139.7671)

    val mapViewState =
        rememberMapTilerMapViewState(
            mapDesign = MapTilerDesign.Streets,
            cameraPosition = MapCameraPosition(position = center, zoom = 11.0),
        )

    MapTilerMapView(
        state = mapViewState,
        modifier = modifier,
    )
}
```

## Available designs

`MapTilerDesign` exposes MapTiler Cloud reference styles and variants, including:
`Streets`, `StreetsDark`, `StreetsLight`, `Basic`, `Bright`, `Satellite`, `Outdoor`,
`Winter`, `Topo`, `Toner`, `Dataviz`, `Backdrop`, `Ocean`, `Landscape`, `Aquarelle`,
`OpenStreetMap`.

## Supported overlays

Marker (including clustering and tile-rendered large marker sets), Polyline, Polygon
(holes supported), Circle, GroundImage, RasterLayer and InfoBubble — the same unified API
as the native-GL providers.

## Notes

MapTiler's official Android SDK renders MapLibre GL JS inside a WebView, so overlays reach
the map as style layers rather than native GL annotations: each shape adds one GeoJSON
source plus a `fill` / `line` layer pair via `MTGeoJSONSource`, `MTFillLayer` and
`MTLineLayer`. Geodesic interpolation, antimeridian splitting and hole unioning reuse the
shared core utilities (`buildUnwrappedPolygonRings`, `unionHoles`), so the resulting
geometry matches the other providers, and hit-testing is handled by the core managers
rather than the SDK.

Map display, camera control (move / animate / fitBounds), style switching, and tap and
camera-move events all go through the SDK bridge.

## License

Apache License 2.0
