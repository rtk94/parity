package com.rknepp.parity.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object Connect : Route
    @Serializable data class Login(val prefillUsername: String? = null) : Route
    @Serializable data object Register : Route
    @Serializable data object Home : Route
    @Serializable data object RelationshipList : Route
    @Serializable data class RelationshipDetail(val relationshipId: Long) : Route
}
