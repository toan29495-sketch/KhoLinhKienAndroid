package vn.kholinhkien

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val role: String,
    @SerialName("display_name")
    val displayName: String = "",
    @SerialName("must_change_password")
    val mustChangePassword: Boolean = false,
    @SerialName("is_active")
    val isActive: Boolean = true
)
