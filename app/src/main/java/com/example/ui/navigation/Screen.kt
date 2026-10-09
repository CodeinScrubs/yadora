package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed class Screen {
    @Serializable data object Today : Screen()
    @Serializable data object Library : Screen()
    @Serializable data object Progress : Screen()
    @Serializable data object AddUnit : Screen()
    /** [fromReview]: opened from a review in progress (its pencil), whose card is this topic already. */
    @Serializable data class EditUnit(val unitId: Long, val fromReview: Boolean = false) : Screen()
    /**
     * [ignoreLimit]: the learner chose "review more anyway" after today's limit was used up.
     * [ahead]: "review ahead" -- topics not yet due, weakest first (ReviewAhead).
     * [kind]: for ONE topic opened from Today, where in Today's list it was (a SessionKind name: PLAN in today's share,
     * EXTRA below its line, AHEAD in "next up"), so the logs keep what the session used to tell (2026-10-09). Null = a
     * topic opened anywhere else (TOPIC).
     */
    @Serializable data class ReviewSession(
        val unitId: Long = -1L,
        val ignoreLimit: Boolean = false,
        val ahead: Boolean = false,
        val kind: String? = null,
    ) : Screen()
    @Serializable data object Settings : Screen()
    @Serializable data object ThemeSettings : Screen()
}
