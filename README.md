# MapTiler SDK for MapConductor Android

## Description

MapConductor provides a unified API for Android Jetpack Compose.
You can use MapTiler with Jetpack Compose, but you can also switch to other Maps SDKs
(such as MapLibre, Mapbox, HERE, and so on) at any time using the same API surface.

This module wraps the official [`com.maptiler:maptiler-sdk-kotlin`](https://docs.maptiler.com/mobile-sdk/android/)
(a WebView / MapLibre GL JS based SDK backed by MapTiler Cloud) behind the MapConductor
`MapViewStateInterface` / `MapViewControllerInterface` contracts.

## Setup

https://mapconductor.com/setup/android/maptiler/

### API key

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

## Components

### MapTilerMapView [[docs]](https://mapconductor.com/mapview/)

```kotlin
@Composable
fun MapExample() {
    val initCameraPosition = MapCameraPosition(
        position = GeoPoint(
            latitude = 34.091,
            longitude = -117.886,
        ),
        zoom = 9.0,
        tilt = 60.0,
        bearing = 30.0,
    )

    val mapViewState = rememberMapTilerMapViewState(
        cameraPosition = initCameraPosition,
    )

    MapTilerMapView(mapViewState)
}
```

------------------------------------------------------------------------

### Marker [[docs]](https://mapconductor.com/markers/)

```kotlin
@Composable
fun MarkerExample() {
    val markerState = remember { MarkerState(
        position = GeoPoint(...),
        icon = DefaultMarkerIcon().copy(
            label = "MapTiler",
        ),
        onClick = {
            it.animate(MarkerAnimation.Bounce)
        },
    ) }

    MapTilerMapView(...) {
        Marker(markerState)
    }
}
```

------------------------------------------------------------------------

### InfoBubble [[docs]](https://mapconductor.com/info-bubble/)

```kotlin
@Composable
fun InfoBubbleExample() {
    var selectedMarker by remember { mutableStateOf<MarkerState?>(null) }

    val markerState = remember { MarkerState(
        ...,
        onClick = {
            selectedMarker = it
        },
    ) }

    MapTilerMapView(...) {
        Marker(markerState)
        selectedMarker?.let {
            InfoBubble(
                marker = it,
            ) {
                Text("Hello, world!")
            }
        }
    }
}
```

------------------------------------------------------------------------

### Circle [[docs]](https://mapconductor.com/circle/)

```kotlin
@Composable
fun CircleExample() {

    val circleState = remember { CircleState(
        center = GeoPoint(...),
        radiusMeters = 50.0,
        fillColor = Color.Blue.copy(alpha = 0.5f),
        onClick = {
            it.state.fillColor = Color.Red.copy(alpha = 0.5f)
        }
    ) }

    MapTilerMapView(...) {
        Circle(circleState)
    }
}
```

------------------------------------------------------------------------

### Polyline [[docs]](https://mapconductor.com/polyline/)

```kotlin
@Composable
fun PolylineExample() {

    val polylineState = remember { PolylineState(
            points = airports,
            strokeColor = Color.Blue.copy(alpha = 0.5f),
            strokeWidth = 4.dp,
            geodesic = true,
        ) }

    MapTilerMapView(...) {
        Polyline(polylineState)
    }
}
```

------------------------------------------------------------------------

### Polygon [[docs]](https://mapconductor.com/polygon/)

```kotlin
@Composable
fun PolygonExample() {

    val polygonState = remember { PolygonState(
        points = goryokaku,
        strokeColor = Color.Blue.copy(alpha = 0.5f),
        fillColor =  Color.Red.copy(alpha = 0.7f),
    ) }

    MapTilerMapView(...) {
        Polygon(polygonState)
    }
}
```

------------------------------------------------------------------------

### Polygon Hole

```kotlin
@Composable
fun PolygonHoleExample() {

    val polygonState =
        remember {
            PolygonState(
                points = listOf(...),
                holes = listOf(
                            listOf(...),
                            listOf(...),
                        ),
                fillColor = Color(0xCC787880),
                strokeColor = Color.Red,
                strokeWidth = 2.dp,
            )
        }

    MapTilerMapView(...) {
        Polygon(polygonState)
    }
}
```

------------------------------------------------------------------------

### GroundImage [[docs]](https://mapconductor.com/ground-image/)

```kotlin
@Composable
fun GroundImageExample() {
    val groundImageState = remember { GroundImageState(
        bounds = GeoRectBounds(
            southWest = GeoPoint.fromLatLong(...),
            northEast = GeoPoint.fromLatLong(...),
        ),
        image = image,
        opacity = 0.5f,
    ) }

    MapTilerMapView(state = mapViewState) {
        GroundImage(groundImageState)
    }
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
