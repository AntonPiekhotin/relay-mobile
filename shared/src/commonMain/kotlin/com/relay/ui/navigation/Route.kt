package com.relay.ui.navigation

import kotlinx.serialization.Serializable

sealed interface Route {

    @Serializable
    data object Home : Route

    @Serializable
    data class Chat(val dialogId: String) : Route
}
