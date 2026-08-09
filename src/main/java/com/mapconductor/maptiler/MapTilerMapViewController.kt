package com.mapconductor.maptiler

import com.mapconductor.core.circle.CircleCapableInterface
import com.mapconductor.core.circle.OnCircleEventHandler
import com.mapconductor.core.controller.BaseMapViewController
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageCapableInterface
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.groundimage.OnGroundImageEventHandler
import com.mapconductor.core.map.CameraRestriction
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapUISettings
import com.mapconductor.core.marker.MarkerCapableInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.MarkerTilingOptions
import com.mapconductor.core.marker.OnMarkerEventHandler
import com.mapconductor.core.polygon.OnPolygonEventHandler
import com.mapconductor.core.polygon.PolygonCapableInterface
import com.mapconductor.core.polyline.OnPolylineEventHandler
import com.mapconductor.core.polyline.PolylineCapableInterface
import com.mapconductor.core.polyline.PolylineState
import com.mapconductor.core.raster.RasterLayerCapableInterface
import com.mapconductor.core.raster.RasterLayerState
import com.mapconductor.maptiler.circle.MapTilerCircleController
import com.mapconductor.maptiler.circle.MapTilerCircleOverlayRenderer
import com.mapconductor.maptiler.groundimage.MapTilerGroundImageController
import com.mapconductor.maptiler.groundimage.MapTilerGroundImageOverlayRenderer
import com.mapconductor.maptiler.marker.MapTilerMarkerTileRenderer
import com.mapconductor.maptiler.polygon.MapTilerPolygonController
import com.mapconductor.maptiler.polygon.MapTilerPolygonOverlayRenderer
import com.mapconductor.maptiler.polyline.MapTilerPolylineController
import com.mapconductor.maptiler.polyline.MapTilerPolylineOverlayRenderer
import com.mapconductor.maptiler.raster.MapTilerRasterLayerController
import com.mapconductor.maptiler.raster.MapTilerRasterLayerOverlayRenderer
import com.mapconductor.maptiler.zoom.ZoomAltitudeConverter
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.style.MTStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MapConductor コアと MapTiler SDK（[MTMapViewController]）を橋渡しするマップコントローラ。
 *
 * カメラ操作は MapTiler のナビゲーション API（jumpTo / easeTo / fitBounds）へ委譲する。
 * MapTiler SDK は WebView（MapLibre GL JS）ベースのため、マーカーやポリゴン等のオーバーレイは
 * 各プロバイダの GL レイヤ実装とは仕組みが異なる。本コントローラは地図表示とカメラ制御を担う。
 */
class MapTilerMapViewController(
    override val holder: MapTilerMapViewHolder,
    internal val mtController: MTMapViewController,
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
    internal var markerTileRenderer: MapTilerMarkerTileRenderer? = null
    internal var markerRasterId: String? = null

    /** tilt < 0 の擬似表現に必要な高度計算用コンバータ（MapLibre と同一ロジック）。 */
    internal val zoomConverter = ZoomAltitudeConverter()

    /**
     * 直近に要求した論理カメラ位置。tilt < 0 は MapTiler（MapLibre GL JS）側で正ピッチへ変換される
     * ため、カメラ状態の読み戻し時に元の負tilt を復元するヒントとして保持する（MapLibre と同方針）。
     */
    internal var lastLogicalCameraPosition: MapCameraPosition? = null

    /**
     * markerTiling（多数マーカー）ページで用いるタイリング設定。[useMarkerLayer] と併せて設定する。
     */
    var markerTilingOptions: MarkerTilingOptions? = null

    /**
     * true のとき、マーカーをマーカータイリング（ラスターレイヤ）で描画する。
     * false（既定）では少数の対話的マーカーをコンポーズオーバーレイ（[markers] フロー）として描画する。
     */
    var useMarkerLayer: Boolean = false

    // --- MarkerCapableInterface ---

    override fun moveCamera(position: MapCameraPosition) = handleMoveCamera(position)

    override fun animateCamera(
        position: MapCameraPosition,
        duration: Long,
    ) = handleAnimateCamera(position, duration)

    override fun fitBounds(
        bounds: GeoRectBounds,
        padding: Int,
    ) = handleFitBounds(bounds, padding)

    /**
     * カメラの可動範囲（パン範囲・ズーム上下限）を制限する。
     *
     * MapTiler SDK はネイティブに範囲制限 API（[com.maptiler.maptilersdk.map.MTMapViewController.setMaxBounds] /
     * `setMinZoom` / `setMaxZoom`）を持つため、Google/Mapbox/MapLibre と同じくネイティブ適用で
     * スムーズに制限する（クランプ方式は不要）。
     *
     * ズームは統一ズーム（Google 準拠）で受け取り、MapTiler のズーム体系へ変換して適用する。
     * 未指定時は既定の下限/上限へ戻すことで制限解除とする。
     */
    override fun setCameraRestriction(restriction: CameraRestriction?) {
        super<BaseMapViewController>.setCameraRestriction(restriction)
        mainCoroutine.launch {
            runCatching {
                // 解除は **null** を渡す。全球の MTBounds（緯度 ±90）を「制限なし」として
                // 渡すと MapLibre GL JS が
                // `Uncaught TypeError: Cannot read properties of null (reading '0')`
                // を投げ、そこから先はブリッジの問い合わせが全部壊れる（project は x/y の
                // 無い JSON を返し、地図も操作できなくなる）。実機 TB520FU で確認。
                // MapTiler SDK 自身も解除には setMaxBounds(null) を使っている。
                mtController.setMaxBounds(
                    restriction?.bounds?.toMTBounds(),
                )
                mtController.setMinZoom(
                    restriction?.minZoom?.let { ZoomAltitudeConverter.googleZoomToMaptilerZoom(it) }
                        ?: DEFAULT_MIN_ZOOM,
                )
                mtController.setMaxZoom(
                    restriction?.maxZoom?.let { ZoomAltitudeConverter.googleZoomToMaptilerZoom(it) }
                        ?: DEFAULT_MAX_ZOOM,
                )
            }
        }
    }

    override fun applyUISettings(settings: MapUISettings) = applyGestureSettings(settings)

    // 拡張ファイルからは基底クラスの protected へ触れないため、内部向けの入口。
    internal suspend fun emitCameraPosition(position: MapCameraPosition) {
        notifyMapCameraPosition(position)
    }

    /** 拡張ファイルから合成済みマーカーを差し替えるための入口。 */
    internal fun publishMarkers(rendered: List<MarkerState>) {
        _markers.value = rendered
    }

    override suspend fun compositionMarkers(data: List<MarkerState>) {
        if (useMarkerLayer) {
            renderTiledMarkers(data)
        } else {
            _markers.value = data
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

    // --- Marker clustering (android-marker-clustering) 連携 ---

    /**
     * マーカークラスタリング等のプラグインが解決する capability。
     *
     * レジストリはこのコントローラではなく **state が持つ**（react-sdk / ios-sdk と同じ）。
     * [MapTilerMapView] がコントローラ生成時に `state.serviceRegistry` へ登録する。
     */
    val markerRenderingSupport: MarkerRenderingSupport<Any> = createMarkerRenderingSupport()

    /** 地図の準備完了状態。Composable が `ready` で true を設定し、クラスタリング開始の合図に用いる。 */
    val mapLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

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
    internal val rasterLayerController: MapTilerRasterLayerController =
        MapTilerRasterLayerController(
            renderer = MapTilerRasterLayerOverlayRenderer(mtController, mainCoroutine),
        )

    /**
     * ポリラインコントローラ。GeoJSON ソース＋ラインレイヤで描画する。
     */
    internal val polylineController: MapTilerPolylineController =
        MapTilerPolylineController(
            renderer = MapTilerPolylineOverlayRenderer(mtController),
        )

    /**
     * ポリゴンコントローラ。GeoJSON ソース＋塗り／輪郭レイヤで描画する。
     */
    internal val polygonController: MapTilerPolygonController =
        MapTilerPolygonController(
            renderer = MapTilerPolygonOverlayRenderer(mtController),
        )

    /**
     * 円コントローラ。中心・半径から生成した多角形リング（GeoJSON ソース＋塗り／輪郭レイヤ）で描画する。
     */
    internal val circleController: MapTilerCircleController =
        MapTilerCircleController(
            renderer = MapTilerCircleOverlayRenderer(mtController),
        )

    /**
     * グラウンドイメージコントローラ。画像ソース（[com.maptiler.maptilersdk.map.style.source.MTImageSource]）
     * ＋ラスターレイヤで、地理座標に画像を貼り付けて描画する。
     */
    internal val groundImageController: MapTilerGroundImageController =
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

    @Deprecated("Use PolygonState.onClick instead.")
    override fun setOnPolygonClickListener(listener: OnPolygonEventHandler?) {
        polygonController.clickListener = listener
    }

    /**
     * 既知のポリゴンを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapplyPolygons() {
        polygonController.reapply()
    }

    // --- CircleCapableInterface ---

    @Deprecated("Use CircleState.onClick instead.")
    override fun setOnCircleClickListener(listener: OnCircleEventHandler?) {
        circleController.clickListener = listener
    }

    /**
     * 既知の円を再適用する（地図 `ready` 後の復元用）。
     */
    fun reapplyCircles() {
        circleController.reapply()
    }

    // --- GroundImageCapableInterface ---

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
     * 地図タップ時のヒットテスト。
     *
     * 公開済みの入口なので残してあるが、中身はコア共通のカスケード
     * （[dispatchOverlayTap]）に置き換わっている。以前はポリライン・ポリゴン・円の
     * **すべて**に配送していたが、いまは他プロバイダと同じく先に当たった 1 つだけ。
     */
    @Deprecated(
        "オーバーレイのタップ配送はコアが行う。",
        ReplaceWith("dispatchOverlayTap(point)"),
    )
    @Suppress("UNUSED_PARAMETER")
    suspend fun handleTap(point: GeoPoint) {
        dispatchOverlayTap(point)
    }

    /**
     * 現在のカメラ（可視領域つき）をオーバーレイコントローラへ伝播する。
     * ポリラインのヒットテスト、およびマーカークラスタリング（可視領域 bounds に基づくクラスタ算出）に用いる。
     */
    suspend fun dispatchCameraToOverlays() {
        runCatching { notifyMapCameraPosition(currentCameraWithRegion()) }
    }

    // --- RasterLayerCapableInterface ---

    override fun hasRasterLayer(state: RasterLayerState): Boolean =
        rasterLayerController.rasterLayerManager.getEntity(state.id) != null

    /**
     * 既知のラスターレイヤを再適用する（地図 `ready` 後の復元用）。
     */
    fun reapplyRasterLayers() {
        rasterLayerController.reapply()
    }

    override fun setMapDesignType(value: MapTilerMapDesignTypeInterface) {
        mtController.style = MTStyle(value.referenceStyle, value.variant)
    }

    /**
     * 移動アニメーションを停止する（外部からの停止要求用の補助 API）。
     */
    @Suppress("unused")
    fun stop() {
        mtController.stop()
    }

    companion object {
        /** MapTiler(MapLibre GL) の既定ズーム下限・上限。 */
        internal const val DEFAULT_MIN_ZOOM = 0.0
        internal const val DEFAULT_MAX_ZOOM = 22.0

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
        internal const val NEGATIVE_TILT_TARGET_DISTANCE_SCALE = 1.83
        internal const val NEGATIVE_TILT_ZOOM_OFFSET_AT_MAX_TILT = -0.9

        /** 統一ズーム（Google）→ MapTiler ネイティブズーム。 */
        internal fun coreZoomToMapTiler(coreZoom: Double): Double =
            (coreZoom - MAPTILER_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** MapTiler ネイティブズーム → 統一ズーム（Google）。 */
        internal fun mapTilerZoomToCore(mapTilerZoom: Double): Double =
            (mapTilerZoom + MAPTILER_TO_GOOGLE_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
