package com.mapconductor.maptiler

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mapconductor.compose.info.InfoBubbleEntry
import com.mapconductor.core.marker.DefaultMarkerIcon
import com.mapconductor.core.marker.MarkerAnimation
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.settings.Settings
import com.maptiler.maptilersdk.annotations.MTCustomAnnotationView
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.types.MTPoint
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.view.animation.BounceInterpolator
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * マーカーを MapTiler 上のコンポーズオーバーレイとして描画する。
 *
 * [MTCustomAnnotationView] は地図の `ON_MOVE` / `ON_ZOOM` に追従して自動で再投影されるため、
 * 地図ドラッグ時にマーカーが正しく追従する。マーカードラッグ中は開始時のスクリーン座標に累積移動量を
 * 加えた点を unproject して [MarkerState.position] を随時更新するため、`onDrag` の購読側（例: ポリゴン
 * 頂点の追従）も正しく動作し、同じ座標に紐づく InfoBubble も追従する。
 */
@Composable
internal fun MapTilerMarkerOverlay(
    controller: MTMapViewController,
    marker: MarkerState,
) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val bitmapIcon = remember(marker.icon) { (marker.icon ?: DefaultMarkerIcon()).toBitmapIcon() }
    val widthDp = remember(bitmapIcon) { (bitmapIcon.size.width / density).dp }
    val heightDp = remember(bitmapIcon) { (bitmapIcon.size.height / density).dp }

    // ドラッグ開始時のマーカー投影点（web px）と累積移動量（device px）。
    val dragBase = remember(marker.id) { AtomicReference<MTPoint?>(null) }
    val dragAccum = remember(marker.id) { AtomicReference(Offset.Zero) }
    val dragInFlight = remember(marker.id) { AtomicBoolean(false) }
    val applyDrag: (Boolean) -> Unit = { end ->
        updateDragPosition(scope, controller, marker, dragBase, dragAccum, density, dragInFlight, end)
    }

    // マーカードロップ/バウンス演出。他プロバイダの MarkerAnimationOverlayLayer と同様に、アイコンを
    // 画面上方から所定位置へ落下させる。MapTiler はマーカーを MTCustomAnnotationView（非同期投影）で
    // 配置するため、その内容へ縦方向 translation を重ねてアニメーションさせる。演出は id ごとに一度だけ。
    // アニメーション指定がある間は最初のフレームで画面外へ退避し、着地位置がちらつくのを防ぐ。
    val dropTranslation =
        remember(marker.id) {
            Animatable(if (marker.getAnimation() != null) MARKER_ANIMATION_OFFSCREEN_PX else 0f)
        }
    // marker.id だけでなく現在のアニメーション指定もキーにする。Marker Animation ページのように
    // 既存マーカー（同一 id）へ後から animate(Drop/Bounce) を設定するケースでも再生させるため。
    LaunchedEffect(marker.id, marker.getAnimation()) {
        val animation = marker.getAnimation() ?: return@LaunchedEffect
        // 落下開始位置：現在のスクリーンY（投影 web px → device px）＋アイコン高だけ上（＝画面上端の外）。
        // 投影が取れない場合はアイコン高の一定倍から落とす。
        val projectedY = runCatching { controller.project(marker.position.toLngLat()).y.toFloat() }.getOrNull()
        val startTranslation =
            if (projectedY != null) {
                -(projectedY * density + bitmapIcon.size.height)
            } else {
                -(bitmapIcon.size.height * MARKER_ANIMATION_FALLBACK_HEIGHTS)
            }
        val durationMs =
            when (animation) {
                MarkerAnimation.Drop -> Settings.Default.markerDropAnimateDuration
                MarkerAnimation.Bounce -> Settings.Default.markerBounceAnimateDuration
            }.toInt().coerceAtLeast(1)
        val interpolator =
            when (animation) {
                MarkerAnimation.Bounce -> BounceInterpolator()
                MarkerAnimation.Drop -> LinearInterpolator()
            }
        dropTranslation.snapTo(startTranslation)
        // 演出開始を通知する（他プロバイダの onAnimate 経路と同じく onAnimateStart / onAnimateEnd を発火する）。
        // MapTiler のマーカーはコンポーズオーバーレイのため、コアの MarkerController 経由ではなくこの演出効果から
        // 直接発火する。これにより onAnimateEnd 購読側（例: ドロップ完了後に InfoBubble を表示する画面）が機能する。
        marker.onAnimateStart?.invoke(marker)
        dropTranslation.animateTo(
            targetValue = 0f,
            animationSpec =
                tween(
                    durationMillis = durationMs,
                    easing =
                        Easing { f ->
                            interpolator.getInterpolation(f)
                        },
                ),
        )
        // 演出完了を通知してからアニメーション指定を解除する（演出は一度きり）。
        marker.onAnimateEnd?.invoke(marker)
        marker.animate(null)
    }

    MTCustomAnnotationView(
        controller = controller,
        coordinates = marker.position.toLngLat(),
        anchor = alignmentForAnchor(bitmapIcon.anchor),
    ) {
        val gestures =
            Modifier
                .then(
                    if (marker.clickable) {
                        Modifier.pointerInput(marker.id) {
                            detectTapGestures { marker.onClick?.invoke(marker) }
                        }
                    } else {
                        Modifier
                    },
                ).then(
                    if (marker.draggable) {
                        Modifier.pointerInput(marker.id) {
                            detectDragGestures(
                                onDragStart = {
                                    dragAccum.set(Offset.Zero)
                                    dragBase.set(null)
                                    scope.launch {
                                        runCatching { dragBase.set(controller.project(marker.position.toLngLat())) }
                                    }
                                    marker.onDragStart?.invoke(marker)
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragAccum.set(dragAccum.get() + delta)
                                    applyDrag(false)
                                },
                                onDragEnd = { applyDrag(true) },
                                onDragCancel = { applyDrag(true) },
                            )
                        }
                    } else {
                        Modifier
                    },
                )

        Image(
            bitmap = bitmapIcon.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier =
                Modifier
                    .size(widthDp, heightDp)
                    .graphicsLayer { translationY = dropTranslation.value }
                    .then(gestures),
        )
    }
}

/** ドロップ演出開始前にアイコンを退避させておく画面外の縦位置（device px）。 */
private const val MARKER_ANIMATION_OFFSCREEN_PX = -10000f

/** 投影が取れない場合に、アイコン高の何倍上から落とすか。 */
private const val MARKER_ANIMATION_FALLBACK_HEIGHTS = 6f

/**
 * ドラッグ中／終了時に、開始点＋累積移動量を unproject してマーカー座標を更新し、[MarkerState.onDrag] /
 * [MarkerState.onDragEnd] を呼ぶ。[end] が false のときは処理中スキップで unproject の過負荷を防ぐ。
 */
private fun updateDragPosition(
    scope: CoroutineScope,
    controller: MTMapViewController,
    marker: MarkerState,
    dragBase: AtomicReference<MTPoint?>,
    dragAccum: AtomicReference<Offset>,
    density: Float,
    inFlight: AtomicBoolean,
    end: Boolean,
) {
    val base = dragBase.get() ?: return
    if (!end && !inFlight.compareAndSet(false, true)) return
    val delta = dragAccum.get()
    scope.launch {
        try {
            val target =
                MTPoint(
                    x = base.x + delta.x / density,
                    y = base.y + delta.y / density,
                )
            marker.position = controller.unproject(target).toGeoPoint()
            if (end) marker.onDragEnd?.invoke(marker) else marker.onDrag?.invoke(marker)
        } catch (_: Throwable) {
            // 地図未初期化などで unproject が失敗した場合はドラッグ更新をスキップする。
        } finally {
            if (!end) inFlight.set(false)
        }
    }
}

/**
 * InfoBubble を MapTiler 上のコンポーズオーバーレイとして描画する。
 *
 * 配置はコアの `InfoBubbleOverlay` と同一：バブル側の接続点 [InfoBubbleEntry.tailOffset] を、マーカー
 * 投影点 + (infoAnchor − iconAnchor) × iconSize に一致させる。位置は [InfoBubbleEntry.positionProvider]
 * が返すマーカー座標に紐づくため、地図ドラッグ・マーカードラッグのどちらにも自動追従する。
 */
@Composable
internal fun MapTilerInfoBubbleOverlay(
    controller: MTMapViewController,
    entry: InfoBubbleEntry,
) {
    val density = LocalDensity.current.density
    val position = entry.positionProvider()
    val bitmapIcon = remember(entry.icon) { entry.icon?.toBitmapIcon() }
    val iconAnchor = entry.icon?.anchor ?: Offset(0.5f, 1f)
    val infoAnchor = entry.icon?.infoAnchor ?: Offset(0.5f, 0f)
    val iconSize = bitmapIcon?.size ?: androidx.compose.ui.geometry.Size.Zero

    val mtOffset =
        MTPoint(
            x = ((infoAnchor.x - iconAnchor.x) * iconSize.width / density).toDouble(),
            y = ((infoAnchor.y - iconAnchor.y) * iconSize.height / density).toDouble(),
        )

    MTCustomAnnotationView(
        controller = controller,
        coordinates = position.toLngLat(),
        offset = mtOffset,
        anchor = alignmentForAnchor(entry.tailOffset),
    ) {
        entry.content()
    }
}

/**
 * anchor（0..1 の割合）を [MTCustomAnnotationView] の [Alignment] へ変換する。
 */
private fun alignmentForAnchor(anchor: Offset): Alignment {
    val horizontal =
        when {
            anchor.x <= 0.25f -> -1
            anchor.x >= 0.75f -> 1
            else -> 0
        }
    val vertical =
        when {
            anchor.y <= 0.25f -> -1
            anchor.y >= 0.75f -> 1
            else -> 0
        }
    return when (vertical to horizontal) {
        -1 to -1 -> Alignment.TopStart
        -1 to 0 -> Alignment.TopCenter
        -1 to 1 -> Alignment.TopEnd
        0 to -1 -> Alignment.CenterStart
        0 to 1 -> Alignment.CenterEnd
        1 to -1 -> Alignment.BottomStart
        1 to 0 -> Alignment.BottomCenter
        1 to 1 -> Alignment.BottomEnd
        else -> Alignment.Center
    }
}
