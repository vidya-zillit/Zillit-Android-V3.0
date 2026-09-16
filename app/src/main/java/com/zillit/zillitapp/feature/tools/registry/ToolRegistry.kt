package com.zillit.zillitapp.feature.tools.registry

import com.zillit.zillitapp.core.logging.ZillitLog
import com.zillit.zillitapp.feature.tools.registry.specs.toolSpecs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The index of every tool the app knows.
 *
 * Deliberately assembled from one file per tool rather than one long table: a tool belongs to
 * whoever is building it, and a fifty-entry table is a merge conflict on every branch. This
 * is the only place that knows the whole set.
 *
 * A tool the server sends that has no spec is **not dropped** — it is rendered with a
 * fallback icon and no destination, so a tool added server-side appears immediately instead
 * of vanishing until the app catches up.
 */
@Singleton
class ToolRegistry @Inject constructor() {

    private val byIdentifier: Map<String, ToolSpec> = toolSpecs.associateBy { it.identifier }
        .also { map ->
            // `associateBy` keeps the last of a duplicate and says nothing, which would
            // leave one tool's icon and explainer quietly serving another's tile.
            if (map.size != toolSpecs.size) {
                val duplicates = toolSpecs.groupingBy { it.identifier }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                ZillitLog.w(TAG, "duplicate tool specs: $duplicates")
            }
        }

    operator fun get(identifier: String): ToolSpec? =
        byIdentifier[identifier].also {
            // Logged rather than ignored. A tool the server sends that nothing here
            // describes still renders — with a fallback icon and a generic explainer — and
            // this is the only sign that a spec is missing.
            if (it == null && unreported.add(identifier)) {
                ZillitLog.w(TAG, "no spec for tool '$identifier'")
            }
        }

    private val unreported = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    val size: Int get() = byIdentifier.size

    private companion object {
        const val TAG = "ToolRegistry"
    }
}
