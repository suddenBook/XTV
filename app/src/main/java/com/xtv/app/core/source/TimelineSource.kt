package com.xtv.app.core.source

import com.xtv.app.core.model.PageResult

/**
 * The one seam between "where posts come from" and everything above it.
 *
 * Above this line the app sees media items, an opaque newest-id, and a small set of semantic
 * outcomes. It never sees `includes` joins, variant ladders, pagination tokens, HTTP status codes or
 * OAuth. That containment is the point: when the upstream shape changes — and it will — the blast
 * radius is one adapter and its fixtures.
 *
 * There is deliberately **no cursor-based paging** in this interface. XTV's timeline emits content
 * roughly eighty times faster than it can be watched, so "page backwards until caught up" is not a
 * thing the app can or should do. It takes a fixed budget from the head and stops.
 */
interface TimelineSource {

    /**
     * Fetch up to [budget] of the newest posts.
     *
     * @param budget how many posts to *read*, not how many media items to return. Billing is per
     *   post read, so this number is the spend, and the yield (~60% video on a measured timeline) is
     *   what it buys.
     * @param sinceId newest post id from a previous run, so already-paid-for posts are not fetched
     *   again. When the gap is larger than [budget] the surplus is deliberately abandoned — there is
     *   no catching up.
     */
    suspend fun loadHead(budget: Int, sinceId: String? = null): PageResult
}
