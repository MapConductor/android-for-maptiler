package com.mapconductor.maptiler

import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.circle.CircleEvent
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.controller.OverlayControllerInterface
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.maptiler.zoom.ZoomAltitudeConverter
import com.mapconductor.core.groundimage.GroundImageCapableInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MutableMapServiceRegistry
import com.mapconductor.core.map.VisibleRegion
import com.mapconductor.core.marker.MarkerCapableInterface
import com.mapconductor.core.marker.MarkerEventControllerInterface
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerRenderingStrategyInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerRenderingSupportKey
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonCapableInterface
import com.mapconductor.core.polygon.PolygonEvent
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineCapableInterface
import com.mapconductor.core.polyline.PolylineEvent
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterLayerCapableInterface
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.maptiler.circle.MapTilerCircleController
import com.mapconductor.maptiler.circle.MapTilerCircleOverlayRenderer
import com.mapconductor.maptiler.groundimage.MapTilerGroundImageController
import com.mapconductor.maptiler.groundimage.MapTilerGroundImageOverlayRenderer
import com.mapconductor.maptiler.marker.MapTilerClusterMarkerRenderer
import com.mapconductor.maptiler.marker.MapTilerMarkerTileRenderer
import com.mapconductor.maptiler.polygon.MapTilerPolygonController
import com.mapconductor.maptiler.polygon.MapTilerPolygonOverlayRenderer
import com.mapconductor.maptiler.polyline.MapTilerPolylineController
import com.mapconductor.maptiler.polyline.MapTilerPolylineOverlayRenderer
import com.mapconductor.maptiler.raster.MapTilerRasterLayerController
import com.mapconductor.maptiler.raster.MapTilerRasterLayerOverlayRenderer
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.options.MTCameraOptions
import com.maptiler.maptilersdk.map.options.MTFitBoundsOptions
import com.maptiler.maptilersdk.map.options.MTFlyToOptions
import com.maptiler.maptilersdk.map.options.MTPaddingOptions
import com.maptiler.maptilersdk.map.style.MTStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan

/**
 * MapConductor コアと MapTiler SDK（[MTMapViewController]）を橋渡しするマップコントローラ。
 *
 * カメラ操作は MapTiler のナビゲーション API（jumpTo / easeTo / fitBounds）へ委譲する。
 * MapTiler SDK は WebView（MapLibre GL JS）ベースのため、マーカーやポリゴン等のオーバーレイは
 * 各プロバイダの GL レイヤ実装とは仕組みが異なる。本コントローラは地図表示とカメラ制御を担う。
 */
class MapTilerMapViewController(
    override val holder: MapTilerMapViewHolder,
    private val mtController: MTMapViewController,
    override val mainCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Main),
    override val defaultCoroutine: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : BaseMapViewController(),
    MapTilerMapViewControllerInterface,
    RasterLayerCapableInterface,
    PolylineCapableInterface,
    PolygonCapableInterface,
    CircleCapableInterface,
    GroundImageCapableInterface,
    MarkerCapableInterface {
    /**
     * 現在のマーカー一覧。MapTiler では GL シンボルレイヤではなく、コンポーズのオーバーレイ
     * （[com.maptiler.maptilersdk.annotations.MTCustomAnnotationView]）として描画するため、
     * ここでは状態を保持して Composable 側へ公開する。
     */
    private val _markers = MutableStateFlow<List<MarkerState>>(emptyList())
    val markers: StateFlow<List<MarkerState>> = _markers.asStateFlow()

    /**
     * 大量マーカーをマーカータイリング（→ ラスターレイヤ）で描画するレンダラ。他モジュールと同方式。
     * markerTiling が指定されたページで [useMarkerLayer] を true にして使う。
     */
    private var markerTileRenderer: MapTilerMarkerTileRenderer? = null
    private var markerRasterId: String? = null

    /** tilt < 0 の擬似表現に必要な高度計算用コンバータ（MapLibre と同一ロジック）。 */
    private val zoomConverter = ZoomAltitudeConverter()

    /**
     * 直近に要求した論理カメラ位置。tilt < 0 は MapTiler（MapLibre GL JS）側で正ピッチへ変換される
     * ため、カメラ状態の読み戻し時に元の負tilt を復元するヒントとして保持する（MapLibre と同方針）。
     */
    private var lastLogicalCameraPosition: MapCameraPosition? = null

    /**
     * markerTiling（多数マーカー）ページで用いるタイリング設定。[useMarkerLayer] と併せて設定する。
     */
    var markerTilingOptions: MarkerTilingOptions? = null

    /**
     * true のとき、マーカーをマーカータイリング（ラスターレイヤ）で描画する。
     * false（既定）では少数の対話的マーカーをコンポーズオーバーレイ（[markers] フロー）として描画する。
     */
    var useMarkerLayer: Boolean = false

    private fun tileRenderer(): MapTilerMarkerTileRenderer =
        markerTileRenderer ?: MapTilerMarkerTileRenderer(
            markerTilingOptions ?: MarkerTilingOptions.Default,
        ).also { markerTileRenderer = it }

    // --- MarkerCapableInterface ---

    override suspend fun compositionMarkers(data: List<MarkerState>) {
        if (useMarkerLayer) {
            renderTiledMarkers(data)
        } else {
            _markers.value = data
        }
    }

    private suspend fun renderTiledMarkers(data: List<MarkerState>) {
        val state = tileRenderer().render(data)
        if (state != null) {
            markerRasterId = state.id
            rasterLayerController.upsert(state)
        } else {
            markerRasterId?.let { rasterLayerController.removeById(it) }
            markerRasterId = null
        }
    }

    override suspend fun updateMarker(state: MarkerState) {
        if (useMarkerLayer) {
            val current = markerTileRenderer?.markers ?: return
            renderTiledMarkers(current.map { if (it.id == state.id) state else it })
        } else {
            _markers.value = _markers.value.map { if (it.id == state.id) state else it }
        }
    }

    override fun hasMarker(state: MarkerState): Boolean =
        if (useMarkerLayer) {
            markerTileRenderer?.markers?.any { it.id == state.id } ?: false
        } else {
            _markers.value.any { it.id == state.id }
        }

    /**
     * タップ座標付近のタイリング・マーカーを [MarkerState.onClick] へ配送する。
     *
     * @param point タップ座標。
     * @param nativeZoom MapTiler ネイティブズーム。
     */
    fun handleMarkerTap(
        point: GeoPoint,
        nativeZoom: Double,
    ) {
        markerTileRenderer?.findMarkerAt(point, nativeZoom)?.let { it.onClick?.invoke(it) }
    }

    // --- Marker clustering (android-marker-clustering) 連携 ---

    /**
     * マーカークラスタリング等のプラグインが参照するサービスレジストリ。
     * [MarkerRenderingSupport] を登録し、Composable 側で `LocalMapServiceRegistry` へ供給する。
     */
    val serviceRegistry: MutableMapServiceRegistry =
        MutableMapServiceRegistry().apply {
            put(MarkerRenderingSupportKey, createMarkerRenderingSupport())
        }

    /** 地図の準備完了状態。Composable が `ready` で true を設定し、クラスタリング開始の合図に用いる。 */
    val mapLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private fun createMarkerRenderingSupport(): MarkerRenderingSupport<Any> =
        object : MarkerRenderingSupport<Any> {
            override fun createMarkerRenderer(
                strategy: MarkerRenderingStrategyInterface<Any>,
            ): MarkerOverlayRendererInterface<Any> =
                // クラスタ／単体マーカーは既存のコンポーズオーバーレイ（[markers] フロー）として描画する。
                MapTilerClusterMarkerRenderer(holder) { rendered -> _markers.value = rendered }

            override fun createMarkerEventController(
                controller: StrategyMarkerController<Any>,
                renderer: MarkerOverlayRendererInterface<Any>,
            ): MarkerEventControllerInterface<Any> =
                // クリックはコンポーズのタップ（marker.onClick）で配送するため、イベントコントローラは空実装。
                object : MarkerEventControllerInterface<Any> {}

            override fun registerMarkerEventController(controller: MarkerEventControllerInterface<Any>) = Unit

            override val mapLoadedState: StateFlow<Boolean>
                get() = mapLoaded

            override fun onMarkerRenderingReady() {
                dispatchInitialCameraToOverlays()
            }
        }

    /**
     * 現在のカメラをオーバーレイコントローラ（クラスタ用 StrategyMarkerController 等）へ初期通知する。
     * これにより初期表示時のクラスタが算出される。
     */
    fun dispatchInitialCameraToOverlays() {
        mainCoroutine.launch {
            runCatching { notifyMapCameraPosition(currentCameraWithRegion()) }
        }
    }

    @Deprecated("Use MarkerState.onDragStart instead.")
    override fun setOnMarkerDragStart(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onDrag instead.")
    override fun setOnMarkerDrag(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onDragEnd instead.")
    override fun setOnMarkerDragEnd(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onAnimateStart instead.")
    override fun setOnMarkerAnimateStart(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onAnimateEnd instead.")
    override fun setOnMarkerAnimateEnd(listener: OnMarkerEventHandler?) = Unit

    @Deprecated("Use MarkerState.onClick instead.")
    override fun setOnMarkerClickListener(listener: OnMarkerEventHandler?) = Unit

    /**
     * ラスターレイヤコントローラ。MapTiler はラスタータイルソース／レイヤを
     * `MTStyle.addSource` / `addLayer` で扱えるため、他プロバイダと同様にラスター表示に対応する。
     */
    private val rasterLayerController: MapTilerRasterLayerController =
        MapTilerRasterLayerController(
            renderer = MapTilerRasterLayerOverlayRenderer(mtController, mainCoroutine),
        )

    /**
     * ポリラインコントローラ。GeoJSON ソース＋ラインレイヤで描画する。
     */
    private val polylineController: MapTilerPolylineController =
        MapTilerPolylineController(
            renderer = MapTilerPolylineOverlayRenderer(mtController),
        )

    /**
     * ポリゴンコントローラ。GeoJSON ソース＋塗り／輪郭レイヤで描画する。
     */
    private val polygonController: MapTilerPolygonController =
        MapTilerPolygonController(
            renderer = MapTilerPolygonOverlayRenderer(mtController),
        )

    /**
     * 円コントローラ。中心・半径から生成した多角形リング（GeoJSON ソース＋塗り／輪郭レイヤ）で描画する。
     */
    private val circleController: MapTilerCircleController =
        MapTilerCircleController(
            renderer = MapTilerCircleOverlayRenderer(mtController),
        )

    /**
     * グラウンドイメージコントローラ。画像ソース（[com.maptiler.maptilersdk.map.style.source.MTImageSource]）
     * ＋ラスターレイヤで、地理座標に画像を貼り付けて描画する。
     */
    private val groundImageController: MapTilerGroundImageController =
        MapTilerGroundImageController(
            renderer = MapTilerGroundImageOverlayRenderer(mtController),
        )

    init {
        registerOverlayController(rasterLayerController)
        registerOverlayController(polylineController)
        registerOverlayController(polygonController)
        registerOverlayController(circleController)
        registerOverlayController(groundImageController)
    }

    override suspend fun clearOverlays() {
        rasterLayerController.clear()
        polylineController.clear()
        polygonController.clear()
        circleController.clear()
        groundImageController.clear()
        markerRasterId?.let { rasterLayerController.removeById(it) }
        markerRasterId = null
        markerTileRenderer?.clear()
    }

    override fun destroy() {
        // タイルサーバのルート登録を解除する（プロセス共有サーバ自体は停止しない）。
        markerTileRenderer?.clear()
        markerTileRenderer = null
        super.destroy()
    }

    // --- PolylineCapableInterface ---

    override suspend fun compositionPolylines(data: List<PolylineState>) {
        polylineController.add(data)
    }

    override suspend fun updatePolyline(state: PolylineState) {
        polylineController.update(state)
    }

    @Deprecated("Use PolylineState.onClick instead.")
    override fun setOnPolylineClickListener(listener: OnPolylineEventHandler?) {
        polylineController.clickListener = listener
    }

    override fun hasPolyline(state: PolylineState): Boolean =
        polylineController.polylineManager.getEntity(state.id) != null

    /**
     * 既知のポリラインを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapplyPolylines() {
        polylineController.reapply()
    }

    // --- PolygonCapableInterface ---

    override suspend fun compositionPolygons(data: List<PolygonState>) {
        polygonController.add(data)
    }

    override suspend fun updatePolygon(state: PolygonState) {
        polygonController.update(state)
    }

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        polygonController.clickListener = listener
    }

    override fun hasPolygon(state: PolygonState): Boolean = polygonController.polygonManager.getEntity(state.id) != null

    /**
     * 既知のポリゴンを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapplyPolygons() {
        polygonController.reapply()
    }

    // --- CircleCapableInterface ---

    override suspend fun compositionCircles(data: List<CircleState>) {
        circleController.add(data)
    }

    override suspend fun updateCircle(state: CircleState) {
        circleController.update(state)
    }

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        circleController.clickListener = listener
    }

    override fun hasCircle(state: CircleState): Boolean = circleController.circleManager.getEntity(state.id) != null

    /**
     * 既知の円を再適用する（地図 `ready` 後の復元用）。
     */
    fun reapplyCircles() {
        circleController.reapply()
    }

    // --- GroundImageCapableInterface ---

    override suspend fun compositionGroundImages(data: List<GroundImageState>) {
        groundImageController.add(data)
    }

    override suspend fun updateGroundImage(state: GroundImageState) {
        groundImageController.update(state)
    }

    @Deprecated("Use GroundImageState.onClick instead.")
    override fun setOnGroundImageClickListener(listener: OnGroundImageEventHandler?) {
        groundImageController.clickListener = listener
    }

    override fun hasGroundImage(state: GroundImageState): Boolean =
        groundImageController.groundImageManager.getEntity(state.id) != null

    /**
     * 既知のグラウンドイメージを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapplyGroundImages() {
        groundImageController.reapply()
    }

    /**
     * 地図タップ時のヒットテスト。ポリライン上・ポリゴン内・円内のタップを onClick へ配送する。
     */
    suspend fun handleTap(point: GeoPoint) {
        polylineController.findWithClosestPoint(point)?.let { hit ->
            polylineController.dispatchClick(PolylineEvent(hit.entity.state, hit.closestPoint))
        }
        polygonController.find(point)?.let { entity ->
            polygonController.dispatchClick(PolygonEvent(entity.state, point))
        }
        circleController.find(point)?.let { entity ->
            circleController.dispatchClick(CircleEvent(entity.state, point))
        }
    }

    /**
     * 現在のカメラ（可視領域つき）をオーバーレイコントローラへ伝播する。
     * ポリラインのヒットテスト、およびマーカークラスタリング（可視領域 bounds に基づくクラスタ算出）に用いる。
     */
    suspend fun dispatchCameraToOverlays() {
        runCatching { notifyMapCameraPosition(currentCameraWithRegion()) }
    }

    /**
     * MapTiler から現在のカメラ状態を取得し、可視領域（[VisibleRegion]）を付与した [MapCameraPosition] を返す。
     * クラスタリングは `visibleRegion.bounds` を必要とするため、[com.maptiler.maptilersdk.map.MTMapViewController.getBounds] を用いる。
     */
    private suspend fun currentCameraWithRegion(): MapCameraPosition {
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
                zoom = mapTilerZoomToCore(zoom),
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
    internal fun recoverLogicalCameraPosition(raw: MapCameraPosition): MapCameraPosition {
        val logicalTiltHint = lastLogicalCameraPosition?.tilt
        val pitchAbsDeg = abs(raw.tilt).coerceIn(0.0, 60.0)
        if (logicalTiltHint == null || logicalTiltHint >= 0.0 || pitchAbsDeg == 0.0) return raw

        val pitchAbsRad = Math.toRadians(pitchAbsDeg)
        val shiftedCenter = raw.position
        val originalGoogleZoom = raw.zoom - NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT * (pitchAbsDeg / 60.0)
        val altitude =
            zoomConverter.zoomLevelToAltitude(
                ZoomAltitudeConverter.googleZoomToMaptilerZoom(originalGoogleZoom),
                shiftedCenter.latitude,
                0.0,
            )
        val distanceBackward = altitude * cos(pitchAbsRad) * tan(pitchAbsRad) * NEGATIVE_TILT_TARGET_DISTANCE_SCALE
        val originalPosition = Spherical.computeOffset(shiftedCenter, distanceBackward, raw.bearing + 180.0)

        return raw.copy(
            position = originalPosition,
            zoom = originalGoogleZoom,
            tilt = -pitchAbsDeg,
        )
    }

    // --- RasterLayerCapableInterface ---

    override suspend fun compositionRasterLayers(data: List<RasterLayerState>) {
        rasterLayerController.add(data)
    }

    override suspend fun updateRasterLayer(state: RasterLayerState) {
        rasterLayerController.update(state)
    }

    override fun hasRasterLayer(state: RasterLayerState): Boolean =
        rasterLayerController.rasterLayerManager.getEntity(state.id) != null

    /**
     * 既知のラスターレイヤを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapplyRasterLayers() {
        rasterLayerController.reapply()
    }

    override fun moveCamera(position: MapCameraPosition) {
        lastLogicalCameraPosition = position
        mtController.jumpTo(position.toCameraOptions())
    }

    override fun animateCamera(
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
        mtController.flyTo(position.toCameraOptions(), flyToOptions)
    }

    override fun fitBounds(
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

    override fun setMapDesignType(value: MapTilerMapDesignTypeInterface) {
        mtController.style = MTStyle(value.referenceStyle, value.variant)
    }

    override fun getControllers(): Map<String, OverlayControllerInterface<*, *>> =
        mapOf(
            "raster_layer" to rasterLayerController,
            "polyline" to polylineController,
            "polygon" to polygonController,
            "circle" to circleController,
            "ground_image" to groundImageController,
        )

    /**
     * 移動アニメーションを停止する（外部からの停止要求用の補助 API）。
     */
    @Suppress("unused")
    fun stop() {
        mtController.stop()
    }

    private fun MapCameraPosition.toCameraOptions(): MTCameraOptions {
        if (tilt >= 0) {
            return MTCameraOptions(
                center = position.toLngLat(),
                zoom = coreZoomToMapTiler(zoom),
                bearing = bearing,
                pitch = tilt.coerceIn(0.0, 60.0),
            )
        }

        // tilt < 0: MapTiler（MapLibre GL JS）は上向きピッチを直接表現できない。
        // MapLibre 実装と同じ方式で、地上ターゲットを進行方向へ前進させ abs(tilt) の下向き
        // ピッチで描画することで、擬似的に上向き（負tilt）視点を再現する。
        val tiltAbsDeg = abs(tilt).coerceIn(0.0, 60.0)
        val tiltAbsRad = Math.toRadians(tiltAbsDeg)
        val altitude =
            zoomConverter.zoomLevelToAltitude(
                ZoomAltitudeConverter.googleZoomToMaptilerZoom(zoom),
                position.latitude,
                0.0,
            )
        val distanceForward =
            altitude *
                cos(tiltAbsRad) *
                tan(tiltAbsRad) *
                NEGATIVE_TILT_TARGET_DISTANCE_SCALE
        val target = Spherical.computeOffset(position, distanceForward, bearing)
        val adjustedZoom = zoom + NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT * (tiltAbsDeg / 60.0)

        return MTCameraOptions(
            center = target.toLngLat(),
            zoom = coreZoomToMapTiler(adjustedZoom),
            bearing = bearing,
            pitch = tiltAbsDeg,
        )
    }

    companion object {
        /**
         * MapTiler（MapLibre GL / web mercator）ネイティブズームと統一ズーム（Google Maps 準拠）の差。
         *
         * MapTiler SDK は MapLibre GL JS ベースで、そのズームは Google Maps より 1 段小さい。
         * すなわち `GoogleZoom ≈ MapTilerZoom + 1.0`（MapLibre 実装 `MAPLIBRE_TO_GOOGLE_ZOOM_OFFSET` と同一）。
         * これにより Camera Sync で Google Maps とズームレベルが一致する。
         */
        private const val MAPTILER_TO_GOOGLE_ZOOM_OFFSET = 1.0

        private const val MIN_ZOOM = 0.0
        private const val MAX_ZOOM = 24.0

        // tilt < 0（上向きピッチ）の擬似表現に用いる定数。MapLibre 実装と同一値。
        private const val NEGATIVE_TILT_TARGET_DISTANCE_SCALE = 1.83
        private const val NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT = -0.9

        /** 統一ズーム（Google）→ MapTiler ネイティブズーム。 */
        internal fun coreZoomToMapTiler(coreZoom: Double): Double =
            (coreZoom - MAPTILER_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** MapTiler ネイティブズーム → 統一ズーム（Google）。 */
        internal fun mapTilerZoomToCore(mapTilerZoom: Double): Double =
            (mapTilerZoom + MAPTILER_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
