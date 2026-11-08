package com.mapconductor.maptiler

import com.maptiler.maptilersdk.bridge.MTBridgeReturnType
import com.maptiler.maptilersdk.bridge.MTCommand
import com.maptiler.maptilersdk.map.MTMapViewController
import com.maptiler.maptilersdk.map.style.MTStyle
import com.maptiler.maptilersdk.map.style.layer.MTLayer
import com.maptiler.maptilersdk.map.style.source.MTSource
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits until the page's map has the layers an overlay was just given.
 *
 * The MapTiler SDK hands a layer to the page later than `MTStyle.addLayer`
 * returns: the call launches a coroutine, and a layer whose source has not
 * loaded yet waits in the SDK's queue until the map's next `idle`.
 * So a renderer's `onAdd` returned before its layers were on the map, and
 * the overlay's `onAdded` (asked for right after the hand-over) came while
 * the map still showed nothing. Boston's 604 downtown polygons reported
 * `onAdded` 0.1 s after the hand-over; the last of their layers reached the
 * map 15 s after it (Lenovo TB520FU).
 * Renderers call [await] at the end of `onAdd`, so `compositionXxx`
 * returns, and `onAdded` is called, once the layers are there.
 *
 * Only that the layers are in the style is waited for, not their tiles or
 * images (same as every other provider).
 */
internal object PageLayerWait {
    /** The page's map, or a falsy value while there is none. */
    const val MAP_EXPR = "window.map"

    /**
     * Only a guard against a layer the page refused, which never shows up:
     * a few hundred layers can take the page well over 10 s.
     */
    const val TIMEOUT_MS = 60_000L
    const val INTERVAL_MS = 100L

    /**
     * Script that answers how many of [ids], from the front, the map has
     * (as text, e.g. `"3"`); `"0"` while there is no map.
     */
    fun script(ids: List<String>): String {
        val list = ids.joinToString(",", "[", "]") { quote(it) }
        return "(function(){try{var m=$MAP_EXPR;if(!m)return '0';var ids=$list;" +
            "for(var i=0;i<ids.length;i++){if(!m.getLayer(ids[i]))return String(i);}" +
            "return String(ids.length);}catch(e){return '0';}})()"
    }

    /** Reads [script]'s answer from what the bridge returned (it may come back JSON-quoted). */
    fun parse(result: String?): Int = result?.trim()?.trim('"')?.toIntOrNull() ?: 0

    /**
     * Returns once the map has every one of [ids], asking through [evaluate]
     * every [intervalMs]. Gives up after [timeoutMs] (returns false and logs):
     * a layer the page refused never shows up, and the overlay is reported
     * then rather than never.
     */
    suspend fun await(
        ids: List<String>,
        timeoutMs: Long = TIMEOUT_MS,
        intervalMs: Long = INTERVAL_MS,
        evaluate: suspend (String) -> String?,
    ): Boolean {
        if (ids.isEmpty()) return true
        var missing = ids
        val done =
            withTimeoutOrNull(timeoutMs) {
                while (missing.isNotEmpty()) {
                    val present =
                        try {
                            parse(evaluate(script(missing)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            0
                        }
                    // Layers mostly arrive in the order they were added: ask only for the rest.
                    missing = missing.drop(present)
                    if (missing.isNotEmpty()) delay(intervalMs)
                }
                true
            } ?: false
        if (!done) {
            runCatching { Log.w(TAG, "${missing.size} of ${ids.size} layers not on the map after $timeoutMs ms") }
        }
        return done
    }

    private fun quote(text: String): String =
        buildString {
            append('"')
            for (c in text) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' || c == '\u2028' || c == '\u2029' -> append(String.format("\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    private const val TAG = "PageLayerWait"
}

/** [PageLayerWait.await] against this map's page. */
internal suspend fun MTMapViewController.awaitPageLayers(ids: List<String>): Boolean =
    PageLayerWait.await(ids) { js ->
        when (val result = execute(PageScript(js))) {
            is MTBridgeReturnType.StringValue -> result.value
            is MTBridgeReturnType.DoubleValue -> result.value.toInt().toString()
            else -> null
        }
    }

/** A script whose (string) value comes back. */
private class PageScript(
    private val js: String,
) : MTCommand {
    override val isPrimitiveReturnType: Boolean = true

    override fun toJS(): String = js
}

/**
 * Takes [layer] off the map and out of the style's own record of ids.
 *
 * `removeLayerById` only reaches the page: `MTStyle` still lists the id, so
 * adding a layer of the same id later failed with "Layer already exists" and
 * nothing was drawn -- an overlay removed and added again (Boston's Downtown
 * after City parcels) stayed off the map while reporting `onAdded`.
 * `removeLayer` with the object clears both. The by-id call stays as the
 * fallback for a layer the style never recorded.
 */
internal fun MTStyle.removeOwnedLayer(layer: MTLayer) {
    runCatching { removeLayer(layer) }.onFailure { runCatching { removeLayerById(layer.identifier) } }
}

/** [removeOwnedLayer] for a source. */
internal fun MTStyle.removeOwnedSource(source: MTSource) {
    runCatching { removeSource(source) }.onFailure { runCatching { removeSourceById(source.identifier) } }
}
