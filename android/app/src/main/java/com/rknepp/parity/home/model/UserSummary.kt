package com.rknepp.parity.home.model

import kotlinx.serialization.Serializable

@Serializable
data class UserSummary(
    val id: Long,
    val username: String,
    val display_name: String,
    // Present only on self-views (/auth/me); defaults false elsewhere.
    val is_admin: Boolean = false,
    // Recovery email — only on self-views (/auth/me); null (absent) on
    // public views and when the account has none set.
    val email: String? = null,
) {
    val displayName: String get() = display_name
    val isAdmin: Boolean get() = is_admin
}
