package com.mapconductor.maptiler

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.maptiler.maptilersdk.events.MTEvent
import com.maptiler.maptilersdk.map.LngLat
import com.maptiler.maptilersdk.map.MTMapViewContentDelegate
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.types.MTData
import com.maptiler.maptilersdk.map.types.MTPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 地図座標へ貼り付けるコンポーズオーバーレイ。MTMapView と同じ Box に置いて使う。
 *
 * MapTiler SDK の [com.maptiler.maptilersdk.annotations.MTCustomAnnotationView] と同じ役割だが、
 * **ブリッジ呼び出しの失敗でアプリを落とさない**点が違う。あちらは
 *
 * ```
 * scope.launch {
 *     val projectedDeferred = async { controller.project(coordinates) }
 *     ...
 * }
 * ```
 *
 * と書かれていて、`project` は JS の戻り値を `MTPoint` へデコードするだけなので、地図の JS が
 * 壊れていると `MissingFieldException: Fields [x, y] are required` が出る。これは `async` の
 * 失敗として親（`rememberCoroutineScope` の StandaloneCoroutine）へ即座に伝播するため、
 * 呼び出し側で `await()` を try で囲んでも止められず、アプリごと落ちる。
 *
 * 実機 TB520FU（MapTiler SDK JS v4.0.1）では次の順で必ず落ちていた:
 *   1. `Uncaught ReferenceError: map is not defined`
 *   2. `Uncaught TypeError: Cannot read properties of null (reading '0')`（JS 側の初期化失敗）
 *   3. それでも `onMapViewInitialized` は呼ばれるのでマーカーが載る
 *   4. 載った瞬間に上の `project` がデコード失敗 → FATAL
 *
 * 「投影が通るか先に試してから載せる」ようなガードでは防げない。初回のプローブは成功し、
 * その直後に JS が壊れる（実測）ため、健全性の判定そのものが競合する。
 * そこで投影を自前で持ち、失敗したらその回の更新を捨てて非表示のままにする。
 *
 * SDK 版との差:
 * - `mapContainerOriginXPx / mapContainerOriginYPx`（SDK の internal）は使わない。
 *   MTMapView はこのオーバーレイと同じ Box を `fillMaxSize()` で埋めるので原点は一致する。
 * - GLOBE 投影時の可視判定（`getBounds().contains()`）も失敗しうるので、取れなければ表示のままにする。
 *
 * @param controller 投影とイベント購読に使うコントローラ。
 * @param coordinates 貼り付け先の地理座標。
 * @param offset 投影点からのピクセルオフセット（x は右、y は下）。
 * @param anchor コンテンツのどの点を投影点に合わせるか。
 * @param modifier 位置決め Box に足す修飾子。
 * @param content 実際に描く中身。
 */
@Composable
internal fun MapTilerProjectedAnnotation(
    controller: MTMapViewController,
    coordinates: LngLat,
    offset: MTPoint = MTPoint(0.0, 0.0),
    anchor: Alignment = Alignment.Center,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val positionPx = remember { mutableStateOf<IntOffset?>(null) }
    val contentSizePx = remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density

    fun anchorAdjustment(size: IntSize): IntOffset =
        when (anchor) {
            Alignment.TopStart -> IntOffset(0, 0)
            Alignment.TopCenter -> IntOffset(size.width / 2, 0)
            Alignment.TopEnd -> IntOffset(size.width, 0)
            Alignment.CenterStart -> IntOffset(0, size.height / 2)
            Alignment.CenterEnd -> IntOffset(size.width, size.height / 2)
            Alignment.BottomStart -> IntOffset(0, size.height)
            Alignment.BottomCenter -> IntOffset(size.width / 2, size.height)
            Alignment.BottomEnd -> IntOffset(size.width, size.height)
            else -> IntOffset(size.width / 2, size.height / 2)
        }

    fun recalculatePosition() {
        scope.launch {
            // 投影が取れなければ、この回は何もしない（前回位置のまま／未投影なら非表示のまま）。
            // ここを runCatching で包むのが本 Composable の存在理由。
            val projected = runCatching { controller.project(coordinates) }.getOrNull() ?: return@launch

            val x = ((projected.x + offset.x) * density).roundToInt()
            val y = ((projected.y + offset.y) * density).roundToInt()
            val adjustment = anchorAdjustment(contentSizePx.value)
            positionPx.value = IntOffset(x - adjustment.x, y - adjustment.y)
        }
    }

    LaunchedEffect(coordinates, offset) {
        recalculatePosition()
    }

    DisposableEffect(controller) {
        val contentDelegate =
            object : MTMapViewContentDelegate {
                override fun onEvent(
                    event: MTEvent,
                    data: MTData?,
                ) {
                    when (event) {
                        MTEvent.ON_MOVE, MTEvent.ON_ZOOM -> recalculatePosition()
                        else -> Unit
                    }
                }
            }

        controller.addContentDelegate(contentDelegate)
        recalculatePosition()

        onDispose { controller.removeContentDelegate(contentDelegate) }
    }

    Box(
        modifier =
            modifier
                .onGloballyPositioned { coords ->
                    if (coords.size != contentSizePx.value) {
                        contentSizePx.value = coords.size
                        recalculatePosition()
                    }
                }.graphicsLayer {
                    val position = positionPx.value
                    translationX = (position?.x ?: 0).toFloat()
                    translationY = (position?.y ?: 0).toFloat()
                    // 一度も投影できていないうちは描かない。地図が壊れているときに
                    // 左上へ固まって見えるのを防ぐ。
                    alpha = if (position != null) 1f else 0f
                },
    ) {
        content()
    }
}
