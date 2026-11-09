package com.mapconductor.maptiler

import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.OverlayStacking
import com.maptiler.maptilersdk.map.style.MTStyle
import com.maptiler.maptilersdk.map.style.layer.MTLayer
import java.util.WeakHashMap

/**
 * 重なり順（[OverlayStacking]: ラスター < GroundImage < ポリゴン < サークル < ポリライン < マーカー）。
 *
 * MapTiler SDK の `MTStyle.addLayer` は beforeId を取らず、常に一番上へ積む。並びを読む
 * API も無い。そこで SDK が足した層の並びをここで持ち、足した直後に `moveLayer` で
 * 「鍵が自分より大きい一番下の層」の下へ動かす。ベースマップの層は見えないので、
 * ラスターもベースマップ全体（ラベルを含む）の上に乗る（GL 系の他プロバイダはラベルの下）。
 *
 * `MTStyle.addLayer` はすぐにはページへ届かない。画像ソース以外は `isSourceLoaded` の
 * 往復を待ち、ソースが読み込み前なら次の `idle` まで SDK の中で待たされる。その前に
 * `moveLayer` が届くと、まだ無い層を動かそうとして何も起きない（逆の順に足すとラスターが
 * 一番上に残る）。そこで足したあとの `idle` で、記録した並びをもう一度ページへ送る
 * （[onIdle]、MapTilerMapView の onEventTriggered）。
 *
 * スタイルを読み直すと層は消える。[reset] で「今スタイルにある層」を空にしてから
 * 帯の順に足し直す（MapTilerMapView の onMapViewInitialized）。
 */
internal object MapTilerOverlayStacking {
    private class Entry(
        val id: String,
        val key: OverlayStacking.Key,
    )

    /** スタイルにある SDK の層（下から上）。 */
    private val stacks = WeakHashMap<MTStyle, MutableList<Entry>>()

    /** 並びを送り直す残りの `idle` の回数。足すたびに [RESTACK_IDLES] へ戻す。 */
    private val pendingIdles = WeakHashMap<MTStyle, Int>()

    /**
     * 足したあと何回の `idle` で並びを送り直すか。1 回目の `idle` で SDK が待たせていた層を
     * ページへ送る。送り直し自体が描き直しを呼ぶので、2 回目で遅れて届いた層も拾って止まる。
     */
    private const val RESTACK_IDLES = 2

    fun key(
        kind: OverlayKind,
        zIndex: Int = 0,
    ) = OverlayStacking.Key(kind, zIndex)

    /** マーカーのタイル: マーカーの帯の一番下。 */
    val MARKER_TILE = key(OverlayKind.Marker, Int.MIN_VALUE)

    /** ポリゴンのタイル: ポリゴンの帯の一番下。 */
    val POLYGON_TILE = key(OverlayKind.Polygon, Int.MIN_VALUE)

    /** ポリラインのタイル（ラスター）: ポリラインの帯の一番下（サークルの上、ネイティブのポリラインの下）。 */
    val POLYLINE_TILE = key(OverlayKind.Polyline, Int.MIN_VALUE)

    fun add(
        style: MTStyle,
        layer: MTLayer,
        key: OverlayStacking.Key,
    ) {
        val id = layer.identifier
        val before =
            synchronized(stacks) {
                val stack = stacks.getOrPut(style) { mutableListOf() }
                stack.removeAll { it.id == id }
                val b = OverlayStacking.insertBefore(stack, key) { it.key }
                val at = if (b == null) stack.size else stack.indexOf(b)
                stack.add(at, Entry(id, key))
                pendingIdles[style] = RESTACK_IDLES
                b?.id
            }
        style.addLayer(layer)
        if (before != null) runCatching { style.moveLayer(id, before) }
    }

    fun remove(
        style: MTStyle,
        layerId: String,
    ) {
        synchronized(stacks) { stacks[style]?.removeAll { it.id == layerId } }
    }

    /**
     * 地図が `idle` になった。足したばかりの層があれば、記録した並び（下から上）を
     * 上から順に `moveLayer` で送り直す。まだページに無い層の `moveLayer` は何もしない。
     *
     * SDK は `idle` を委譲先へ渡した**あと**で、待たせていた層を送る。ここをそのまま
     * 呼ぶと `moveLayer` が層より先に届くので、呼び出し側は 1 度メインスレッドへ戻して
     * から呼ぶこと。
     */
    fun onIdle(style: MTStyle) {
        val ids =
            synchronized(stacks) {
                val left = pendingIdles[style] ?: return
                if (left <= 1) pendingIdles.remove(style) else pendingIdles[style] = left - 1
                stacks[style]?.map { it.id }.orEmpty()
            }
        if (ids.isEmpty()) return
        runCatching { style.moveLayer(ids.last()) }
        for (i in ids.size - 2 downTo 0) {
            runCatching { style.moveLayer(ids[i], ids[i + 1]) }
        }
    }

    /** スタイルを読み直した（SDK の層が全部消えた）。 */
    fun reset(style: MTStyle) {
        synchronized(stacks) {
            stacks[style]?.clear()
            pendingIdles.remove(style)
        }
    }
}
