package com.mapconductor.maptiler

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mapconductor.compose.CollectAndRenderOverlays
import com.mapconductor.compose.MapViewScope
import com.mapconductor.compose.circle.LocalCircleCollector
import com.mapconductor.compose.groundimage.LocalGroundImageCollector
import com.mapconductor.compose.info.LocalInfoBubbleCollector
import com.mapconductor.compose.marker.LocalMarkerCollector
import com.mapconductor.compose.polygon.LocalPolygonCollector
import com.mapconductor.compose.polyline.LocalPolylineCollector
import com.mapconductor.compose.raster.LocalRasterLayerCollector
import com.mapconductor.core.OnCameraMoveHandler
import com.mapconductor.core.OnMapEventHandler
import com.mapconductor.core.OnMapLoadedHandler
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.LocalMapOverlayRegistry
import com.mapconductor.core.map.LocalMapServiceRegistry
import com.mapconductor.core.map.LocalMapViewController
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.MarkerTilingOptions
import com.maptiler.maptilersdk.events.MTEvent
import com.maptiler.maptilersdk.map.MTMapOptions
import com.maptiler.maptilersdk.map.MTMapView
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.MTMapViewDelegate
import com.maptiler.maptilersdk.map.types.MTData
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * MapTiler の地図を表示する Composable。他プロバイダの `*MapView` と同じ引数体系を持ち、
 * example-app の型ディスパッチ（`MapViewContainer`）からそのまま利用できる。
 *
 * MapTiler SDK は WebView（MapLibre GL JS）ベースのため、地図表示・カメラ制御・タップ/移動イベントを
 * [MTMapViewController] のブリッジ API 経由で扱う。オーバーレイ（マーカー等）の [content] は
 * GL レイヤ方式の他プロバイダとは仕組みが異なるため、本実装では基図表示とカメラ連携を主眼とし、
 * [content] は他プロバイダと同じ CompositionLocal 群の下で評価する（描画自体は行わない）。
 *
 * @param state 地図状態。デザイン・カメラ・コントローラを保持する。
 * @param onMapLoaded 地図の初期描画完了（MapTiler の `ready` イベント）で呼ばれる。
 * @param onMapClick 地図タップ時に、タップ座標付きで呼ばれる。
 * @param onCameraMoveStart / onCameraMove / onCameraMoveEnd カメラ移動の各段階で呼ばれる。
 */
@Composable
fun MapTilerMapView(
    state: MapTilerViewState,
    modifier: Modifier = Modifier,
    markerTiling: MarkerTilingOptions? = null,
    cameraRestriction: CameraRestriction? = null,
    onMapLoaded: OnMapLoadedHandler? = null,
    onMapClick: OnMapEventHandler? = null,
    @Suppress("UNUSED_PARAMETER") onMapLongClick: OnMapEventHandler? = null,
    onCameraMoveStart: OnCameraMoveHandler? = null,
    onCameraMove: OnCameraMoveHandler? = null,
    onCameraMoveEnd: OnCameraMoveHandler? = null,
    content: (@Composable MapViewScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    remember(context) {
        MapTilerInitSDK.ensureInitialized(context)
        ResourceProvider.init(context)
        true
    }

    val design = state.mapDesignType

    // デザイン（スタイル）切替時はコントローラと WebView を作り直し、確実に新スタイルを反映する。
    key(design.id) {
        val mtController = remember { MTMapViewController(context) }
        val holder = remember(mtController) { MapTilerMapViewHolder(mtController) }
        val controller =
            remember(mtController) {
                MapTilerMapViewController(holder, mtController).also { state.setController(it) }
            }
        // markerTiling 指定ページ（多数マーカー）はマーカータイリング（ラスターレイヤ）経路で描画する。
        controller.useMarkerLayer = markerTiling != null
        controller.markerTilingOptions = markerTiling

        // This provider builds its view itself rather than going through
        // MapViewBase, so the shared gesture dispatch has to be wired here.
        // Re-key on the loaded flag as well: gesture calls go over the JS bridge,
        // which drops anything sent before the page is ready.
        val mapLoaded by controller.mapLoaded.collectAsState()
        LaunchedEffect(controller, state.uiSettings, mapLoaded) {
            controller.applyUISettings(state.uiSettings)
        }

        // 範囲制限も JS ブリッジ経由なので、ページ読み込み完了フラグを key に含める。
        LaunchedEffect(controller, cameraRestriction, mapLoaded) {
            controller.setCameraRestriction(cameraRestriction)
        }

        val options =
            remember(mtController) {
                val cam = state.cameraPosition
                MTMapOptions(
                    cam.position.toLngLat(),
                    MapTilerMapViewController.coreZoomToMapTiler(cam.zoom),
                    cam.bearing,
                    cam.tilt,
                )
            }

        val currentState by rememberUpdatedState(state)
        val onLoaded by rememberUpdatedState(onMapLoaded)
        val onClick by rememberUpdatedState(onMapClick)
        val onMove by rememberUpdatedState(onCameraMove)
        val onMoveStart by rememberUpdatedState(onCameraMoveStart)
        val onMoveEnd by rememberUpdatedState(onCameraMoveEnd)

        val coroutineScope = rememberCoroutineScope()
        val cameraQueryInFlight = remember { AtomicBoolean(false) }
        var mapReady by remember(mtController) { mutableStateOf(false) }

        DisposableEffect(mtController) {
            mtController.delegate =
                object : MTMapViewDelegate {
                    override fun onMapViewInitialized() {
                        mapReady = true
                        controller.reapplyRasterLayers()
                        controller.reapplyPolylines()
                        controller.reapplyPolygons()
                        controller.reapplyCircles()
                        controller.reapplyGroundImages()
                        controller.mapLoaded.value = true
                        controller.dispatchInitialCameraToOverlays()
                        onLoaded?.invoke(currentState)
                    }

                    override fun onEventTriggered(
                        event: MTEvent,
                        data: MTData?,
                    ) {
                        when (event) {
                            MTEvent.ON_TAP ->
                                data?.coordinate?.toGeoPoint()?.let { point ->
                                    onClick?.invoke(point)
                                    coroutineScope.launch {
                                        controller.handleTap(point)
                                        // シンボルレイヤ・マーカーのヒットテスト（現在ズームで換算）。
                                        runCatching { controller.handleMarkerTap(point, mtController.getZoom()) }
                                    }
                                }

                            MTEvent.ON_MOVE_START ->
                                emitCamera(coroutineScope, mtController, controller, cameraQueryInFlight, force = true) { pos ->
                                    onMoveStart?.invoke(pos)
                                }

                            MTEvent.ON_MOVE ->
                                emitCamera(coroutineScope, mtController, controller, cameraQueryInFlight, force = false) { pos ->
                                    onMove?.invoke(pos)
                                }

                            MTEvent.ON_MOVE_END ->
                                emitCamera(coroutineScope, mtController, controller, cameraQueryInFlight, force = true) { pos ->
                                    currentState.updateCameraPosition(pos)
                                    coroutineScope.launch { controller.dispatchCameraToOverlays() }
                                    onMoveEnd?.invoke(pos)
                                }

                            else -> Unit
                        }
                    }
                }
            onDispose { mtController.delegate = null }
        }

        val overlayScope = remember { MapTilerMapViewScope() }
        val registry = remember(overlayScope) { overlayScope.buildRegistry() }
        val markers by controller.markers.collectAsState()
        val bubbles by overlayScope.bubbleFlow.collectAsState()

        // 地図と、その上に重ねるコンポーズオーバーレイ（マーカー／InfoBubble）を同一 Box に配置する。
        // MTCustomAnnotationView は自身を絶対ピクセル位置へ配置するため、MTMapView と同じ Box が必要。
        Box(modifier = modifier) {
            MTMapView(
                referenceStyle = design.referenceStyle,
                options = options,
                controller = mtController,
                modifier = Modifier.fillMaxSize(),
                styleVariant = design.variant,
            )

            if (mapReady) {
                markers.forEach { markerState ->
                    key(markerState.id) {
                        MapTilerMarkerOverlay(mtController, markerState)
                    }
                }
                bubbles.values.forEach { entry ->
                    key(entry.id) {
                        MapTilerInfoBubbleOverlay(mtController, entry)
                    }
                }
            }
        }

        // オーバーレイ content は他プロバイダと同じ CompositionLocal 群の下で評価する。
        // ラスターレイヤ・ポリラインは MapTiler のスタイルへ描画し、マーカー／InfoBubble は
        // 上記の MTCustomAnnotationView オーバーレイとして描画される。
        content?.let { overlay ->
            CompositionLocalProvider(
                LocalMapOverlayRegistry provides registry,
                LocalMapServiceRegistry provides controller.serviceRegistry,
                LocalMapViewController provides controller,
                LocalMarkerCollector provides overlayScope.markerCollector,
                LocalInfoBubbleCollector provides overlayScope.bubbleFlow,
                LocalCircleCollector provides overlayScope.circleCollector,
                LocalPolylineCollector provides overlayScope.polylineCollector,
                LocalPolygonCollector provides overlayScope.polygonCollector,
                LocalGroundImageCollector provides overlayScope.groundImageCollector,
                LocalRasterLayerCollector provides overlayScope.rasterLayerCollector,
            ) {
                with(overlayScope) { overlay() }
            }
        }

        // 地図の準備完了後にラスターレイヤ／ポリラインの合成・描画を駆動する。
        if (mapReady) {
            CollectAndRenderOverlays(registry = registry, controller = controller)

            DisposableEffect(controller) {
                overlayScope.rasterLayerCollector.setUpdateHandler { rasterLayerState ->
                    if (controller.hasRasterLayer(rasterLayerState)) {
                        controller.updateRasterLayer(rasterLayerState)
                    }
                }
                overlayScope.polylineCollector.setUpdateHandler { polylineState ->
                    if (controller.hasPolyline(polylineState)) {
                        controller.updatePolyline(polylineState)
                    }
                }
                overlayScope.polygonCollector.setUpdateHandler { polygonState ->
                    if (controller.hasPolygon(polygonState)) {
                        controller.updatePolygon(polygonState)
                    }
                }
                overlayScope.circleCollector.setUpdateHandler { circleState ->
                    if (controller.hasCircle(circleState)) {
                        controller.updateCircle(circleState)
                    }
                }
                overlayScope.groundImageCollector.setUpdateHandler { groundImageState ->
                    if (controller.hasGroundImage(groundImageState)) {
                        controller.updateGroundImage(groundImageState)
                    }
                }
                overlayScope.markerCollector.setUpdateHandler { markerState ->
                    if (controller.hasMarker(markerState)) {
                        controller.updateMarker(markerState)
                    }
                }
                onDispose {
                    overlayScope.rasterLayerCollector.setUpdateHandler(null)
                    overlayScope.polylineCollector.setUpdateHandler(null)
                    overlayScope.polygonCollector.setUpdateHandler(null)
                    overlayScope.circleCollector.setUpdateHandler(null)
                    overlayScope.groundImageCollector.setUpdateHandler(null)
                    overlayScope.markerCollector.setUpdateHandler(null)
                }
            }
        }
    }
}

/**
 * MapTiler のカメラ状態（中心・ズーム・方位・ピッチ）をブリッジ経由で取得し、[emit] へ渡す。
 *
 * [force] が false のときは前回の取得が完了するまで新規取得をスキップし、`move` の連続発火に対する
 * ブリッジ往復の過負荷を防ぐ。
 */
private fun emitCamera(
    scope: kotlinx.coroutines.CoroutineScope,
    controller: MTMapViewController,
    logicalController: MapTilerMapViewController,
    inFlight: AtomicBoolean,
    force: Boolean,
    emit: (MapCameraPosition) -> Unit,
) {
    if (!force && !inFlight.compareAndSet(false, true)) return
    if (force) inFlight.set(true)

    scope.launch {
        try {
            val center = controller.getCenter()
            val zoom = controller.getZoom()
            val bearing = controller.getBearing()
            val pitch = controller.getPitch()
            val cameraPosition =
                MapCameraPosition(
                    position = center.toGeoPoint(),
                    zoom = MapTilerMapViewController.mapTilerZoomToCore(zoom),
                    bearing = bearing,
                    tilt = pitch,
                )
            // tilt < 0 の擬似表現時は、正ピッチ・前進ターゲットの生状態から論理的な負tilt・元位置へ復元する。
            emit(logicalController.recoverLogicalCameraPosition(cameraPosition))
        } catch (_: Throwable) {
            // 地図が未初期化などでブリッジ取得に失敗した場合は無視する。
        } finally {
            inFlight.set(false)
        }
    }
}
