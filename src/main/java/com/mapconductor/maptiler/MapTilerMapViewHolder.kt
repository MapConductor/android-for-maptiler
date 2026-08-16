package com.mapconductor.maptiler

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.projection.WebMercatorScreenProjection
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.types.MTPoint

/**
 * MapTiler 用の [MapViewHolderInterface] 実装。
 *
 * MapTiler SDK は WebView（MapLibre GL JS）ベースで、ネイティブの `MapView` / `Map` を直接公開しない。
 * そのため実体としては [MTMapViewController] を保持し、非同期の座標変換はブリッジ API 経由で行う。
 *
 * ブリッジは**同期**の座標変換 API を持たない（`project` / `unproject` は suspend）。
 * しかし MapTiler の既定スタイルは Web Mercator なので、投影はカメラとビューの大きさだけで
 * 決まる。式はコアの [WebMercatorScreenProjection] にあり、ここはカメラとサイズを渡すだけ。
 * **各所で式を書き直さないこと。** android-for-longdo の [LongdoMapViewHolder] と同じ扱い。
 */
class MapTilerMapViewHolder(
    private val controller: MTMapViewController,
) : MapViewHolderInterface<MTMapViewController, MTMapViewController> {
    override val mapView: MTMapViewController = controller
    override val map: MTMapViewController = controller

    /**
     * 投影に使うカメラの取り出し口。[MapTilerMapViewController] が自身の直近カメラを繋ぐ。
     * ホルダーはコントローラより先に作られるので、コンストラクタでは受け取れない。
     */
    internal var cameraProvider: (() -> MapCameraPosition?)? = null

    /**
     * 地図ビューの大きさ（**端末ピクセル**）の取り出し口。地図の Composable が
     * `onGloballyPositioned` から繋ぐ。SDK 側の `mapContainerWidthPx` は internal で外から読めない。
     */
    internal var viewportSizeProvider: (() -> IntSize?)? = null

    /**
     * 地理座標 → 画面座標（**端末ピクセル**）。カメラかビューの大きさが未確定なら null。
     *
     * ホルダーの契約は端末ピクセル（MapLibre 等の `toScreenLocation` に合わせている）。
     * 一方 [WebMercatorScreenProjection] の世界の大きさ `256 * 2^zoom` は
     * **密度非依存の単位**（dp / CSS ピクセル）なので、密度を挟まずに px を渡すと
     * 中心からのずれが density 分だけ小さくなり、マーカーと吹き出しがずれる。
     */
    override fun toScreenOffset(position: GeoPointInterface): Offset? {
        val camera = cameraProvider?.invoke() ?: return null
        val size = viewportSizeProvider?.invoke() ?: return null
        val density = ResourceProvider.getDensity()
        val offsetDp =
            WebMercatorScreenProjection.toScreenOffset(
                position = position,
                camera = camera,
                widthPx = size.width / density,
                heightPx = size.height / density,
            ) ?: return null
        return Offset(offsetDp.x * density, offsetDp.y * density)
    }

    /**
     * 画面座標 → 地理座標（非同期）。MapTiler の `unproject`（JS ブリッジ）を用いる。
     *
     * 同期版と違い GLOBE 投影や tilt も SDK 側が正しく扱うので、**待てる経路はこちらが正**。
     * 同期版は待てない場所（タップの当たり判定など）専用。
     */
    override suspend fun fromScreenOffset(offset: Offset): GeoPoint? =
        runCatching {
            controller.unproject(MTPoint(offset.x.toDouble(), offset.y.toDouble())).toGeoPoint()
        }.getOrNull()

    /**
     * 画面座標 → 地理座標（同期）。ブリッジの応答を待てない場所で使う。
     */
    override fun fromScreenOffsetSync(offset: Offset): GeoPoint? {
        val camera = cameraProvider?.invoke() ?: return null
        val size = viewportSizeProvider?.invoke() ?: return null
        val density = ResourceProvider.getDensity()
        return WebMercatorScreenProjection.fromScreenOffset(
            offset = Offset(offset.x / density, offset.y / density),
            camera = camera,
            widthPx = size.width / density,
            heightPx = size.height / density,
        )
    }
}
