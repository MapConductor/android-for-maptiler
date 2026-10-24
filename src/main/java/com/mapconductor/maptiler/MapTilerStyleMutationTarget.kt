package com.mapconductor.maptiler

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.map.StyleMutationTarget
import com.maptiler.maptilersdk.map.style.MTStyle
import com.maptiler.maptilersdk.map.style.dsl.PropertyValue
import com.maptiler.maptilersdk.map.style.layer.background.MTBackgroundLayer
import org.json.JSONObject

/**
 * Changes a loaded MapTiler style in place.
 *
 * The seven calls [StyleMutationTarget] asks for, against the MapTiler
 * SDK's style API. Everything above them — what a mutation means, which can
 * be skipped, keeping one bad property from costing the rest — is
 * `applyStyleMutations` in core, shared with every other provider.
 *
 * ## Why the compiler's JSON goes straight through
 *
 * The MapTiler Android SDK is a bridge onto MapTiler SDK JS in a WebView,
 * and its `PropertyValue` has a `RawJs` case that reaches the page
 * unchanged. Every JSON value is also a JavaScript literal, so the text the
 * compiler produced is handed over as written — no literal/expression
 * decision, no colour parsing, no typed builders. The same shape as the
 * Mapbox adapter and for a different reason.
 *
 * ## What it cannot do
 *
 * **There is no way to ask whether a layer exists.** The bridge exposes
 * `sourceExists` but nothing for layers, and `MTStyle` only remembers the
 * ones it was told to add. So [hasLayer] says yes to everything — the
 * mutation is attempted, and a layer the document does not have fails inside
 * the page where nothing can see it. Unlike every other provider, a missing
 * layer here is not reported. Adding and removing are unaffected: those
 * concern layers this adapter owns, and it keeps its own list.
 *
 * Saying yes to everything is about the document's contents only. Whether
 * there is a document at all is [isReady], and it has to be answered
 * separately: when the two were the same call, every adjustment made before
 * the style finished loading was reported as one this map could not make.
 *
 * **A layer is added as a typed object, not a document.** `MTStyle.addLayer`
 * takes an `MTLayer`, so [addLayer] builds the one kind the compiler ever
 * adds — a background under a style that has none — and then replays its
 * paint and layout through the property calls, which is how the colour gets
 * across without being parsed here.
 */
@InternalMapConductorApi
internal class MapTilerStyleMutationTarget(
    private val style: () -> MTStyle?,
) : StyleMutationTarget {
    /**
     * The ids this adapter added to the style it added them to.
     *
     * `MTStyle` throws `LayerAlreadyExists` on a duplicate id, and a style
     * reload replaces the `MTStyle` with an empty one -- so the identity of
     * the style has to be part of the record, or putting the set back on a
     * reloaded map would be skipped.
     */
    private var added = mutableSetOf<String>()
    private var addedTo: MTStyle? = null

    /** Always true: see the class comment. */
    override val isReady: Boolean get() = style() != null

    override fun hasLayer(layerId: String): Boolean = true

    override fun setPaint(
        layerId: String,
        key: String,
        valueJson: String,
    ) {
        style()?.setPaintProperty(layerId, key, PropertyValue.RawJs(valueJson))
    }

    override fun setLayout(
        layerId: String,
        key: String,
        valueJson: String,
    ) {
        style()?.setLayoutProperty(layerId, key, PropertyValue.RawJs(valueJson))
    }

    override fun setFilter(
        layerId: String,
        filterJson: String,
    ) {
        style()?.setFilter(layerId, PropertyValue.RawJs(filterJson))
    }

    override fun setZoomRange(
        layerId: String,
        minZoom: Float,
        maxZoom: Float,
    ) {
        style()?.setLayerZoomRange(layerId, minZoom.toDouble(), maxZoom.toDouble())
    }

    override fun addLayer(
        layerId: String,
        layerJson: String,
        beforeId: String?,
    ) {
        val target = style() ?: return
        if (layerId in own(target)) return
        val document = JSONObject(layerJson)
        if (document.optString("type") != "background") {
            throw UnsupportedOperationException("only a background layer can be added here")
        }
        target.addLayer(MTBackgroundLayer(layerId))
        own(target) += layerId
        // Position, then paint: `addLayer` appends, and the compiler asks for
        // the background to go under the layer the document starts with.
        if (beforeId != null) target.moveLayer(layerId, beforeId)
        replay(layerId, document.optJSONObject("paint"), ::setPaint)
        replay(layerId, document.optJSONObject("layout"), ::setLayout)
    }

    override fun removeLayer(layerId: String) {
        val target = style() ?: return
        // `removeLayerById` reaches the page without being checked against
        // anything, and removing an absent layer there is a no-op.
        target.removeLayerById(layerId)
        own(target) -= layerId
    }

    /** The ids we added to [target], forgetting what belonged to an older style. */
    private fun own(target: MTStyle): MutableSet<String> {
        if (addedTo !== target) {
            addedTo = target
            added = mutableSetOf()
        }
        return added
    }

    /**
     * A new layer's own properties, sent the same way an adjustment to an
     * existing layer's would be.
     *
     * `MTBackgroundLayer` could take a colour directly, but only as an ARGB
     * int -- which would mean parsing `#rrggbb` and `rgba(...)` here, a
     * fourth place for the same bug. This way the compiler's text is the
     * only thing that ever states a colour.
     */
    private fun replay(
        layerId: String,
        section: JSONObject?,
        set: (String, String, String) -> Unit,
    ) {
        val properties = section ?: return
        for (key in properties.keys()) {
            set(layerId, key, jsonTextOf(properties.get(key)))
        }
    }

    /**
     * A parsed JSON node back as JSON text, which is what the property calls
     * take -- so a string keeps its quotes and a null stays `null`.
     */
    private fun jsonTextOf(value: Any?): String =
        when {
            value == null || value === JSONObject.NULL -> "null"
            value is String -> JSONObject.quote(value)
            else -> value.toString()
        }
}
