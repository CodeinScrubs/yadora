package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed class Screen {
    @Serializable data object Today : Screen()
    @Serializable data object Library : Screen()
    @Serializable data object Progress : Screen()
    @Serializable data object AddUnit : Screen()
    @Serializable data class EditUnit(val unitId: Long) : Screen()
    @Serializable data class ReviewSession(val unitId: Long = -1L) : Screen()
    @Serializable data object Settings : Screen()
    @Serializable data object ThemeSettings : Screen()
}
