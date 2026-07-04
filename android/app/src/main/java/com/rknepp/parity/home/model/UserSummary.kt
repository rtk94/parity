package com.rknepp.parity.home.model

import kotlinx.serialization.Serializable

@Serializable
data class UserSummary(
    val id: Long,
    val username: String,
    val display_name: String,
    // Present only on self-views (/auth/me); defaults false elsewhere.
    val is_admin: Boolean = false,
) {
    val displayName: String get() = display_name
    val isAdmin: Boolean get() = is_admin
}
