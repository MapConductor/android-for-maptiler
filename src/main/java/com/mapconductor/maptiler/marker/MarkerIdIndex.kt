package com.mapconductor.maptiler.marker

import com.mapconductor.core.marker.MarkerState

/**
 * The ids of a marker list, rebuilt only when the list itself is replaced.
 *
 * `hasMarker` is asked once per marker after every composition (that is how
 * `MarkerState.onAdded` finds out what is on the map) and on every in-place
 * change. A linear search of the list made the first of those quadratic: 1e5
 * markers, 1e10 comparisons.
 */
internal class MarkerIdIndex {
    private var list: List<MarkerState>? = null
    private var ids: Set<String> = emptySet()

    @Synchronized
    fun contains(
        markers: List<MarkerState>,
        id: String,
    ): Boolean {
        if (markers !== list) {
            ids = markers.mapTo(HashSet(markers.size * 2)) { it.id }
            list = markers
        }
        return id in ids
    }
}
