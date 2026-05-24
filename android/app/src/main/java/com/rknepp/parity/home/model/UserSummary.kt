package com.rknepp.parity.home.model

import kotlinx.serialization.Serializable

@Serializable
data class UserSummary(
    val id: Long,
    val username: String,
    val display_name: String,
) {
    val displayName: String get() = display_name
}
