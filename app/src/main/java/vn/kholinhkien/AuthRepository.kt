package vn.kholinhkien

import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import io.ktor.client.call.body
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.decodeFromJsonElement


@Serializable
data class AdminAuditEvent(
    val id: String,
    @SerialName("actor_username") val actorUsername: String,
    @SerialName("target_username") val targetUsername: String? = null,
    val action: String,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("finished_at") val finishedAt: String? = null
)

@Serializable
data class AdminUsersResponse(
    val success: Boolean = false,
    val message: String = "",
    val username: String = "",
    val error: String = ""
)

class InactiveProfileException : IllegalStateException("Tài khoản đã bị khóa")

class AuthRepository {
    private val client = SupabaseClientProvider.client

    private fun usernameToEmail(username: String): String {
        val normalized = username.trim().lowercase()
        require(Regex("^[a-z0-9_-]{3,32}$").matches(normalized)) {
            "Tên tài khoản chỉ được gồm 3–32 ký tự chữ thường, số, _ hoặc -"
        }
        val parts = normalized.split("_", limit = 2)
        return if (parts.size == 2) "${parts[0]}+${parts[1]}@gmail.com" else "$normalized@gmail.com"
    }

    suspend fun signIn(username: String, password: String) {
        client.auth.signInWith(Email) {
            email = usernameToEmail(username)
            this.password = password
        }
    }

    suspend fun getCurrentProfile(): UserProfile {
        val userId = client.auth.currentUserOrNull()?.id ?: error("Bạn chưa đăng nhập")
        val profile = client.from("profiles").select {
            filter { eq("id", userId) }
        }.decodeSingle<UserProfile>()
        if (!profile.isActive) {
            try {
                client.auth.signOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The caller still receives the explicit inactive status and must
                // remove privileged UI even if the network sign-out cannot finish.
            }
            throw InactiveProfileException()
        }
        return profile
    }

    suspend fun changePassword(newPassword: String): UserProfile {
        require(newPassword.length >= 6) { "Mật khẩu mới phải có ít nhất 6 ký tự" }
        client.auth.updateUser { password = newPassword }
        client.auth.currentUserOrNull() ?: error("Phiên đăng nhập đã hết hạn")
        // Chỉ RPC SECURITY DEFINER mới được phép tự gỡ cờ sau khi Auth
        // đã chấp nhận mật khẩu mới; tránh cấp quyền UPDATE trực tiếp cho client.
        client.postgrest.rpc("complete_password_change")
        return getCurrentProfile()
    }

    suspend fun getKtvUsers(): List<UserProfile> = client.from("profiles").select {
        filter { eq("role", "ktv") }
    }.decodeList<UserProfile>()

    suspend fun getAdminAuditEvents(limit: Int = 100): List<AdminAuditEvent> {
        require(limit in 1..200) { "Giới hạn nhật ký không hợp lệ" }
        val raw = client.postgrest.rpc("list_admin_audit_events", parameters = buildJsonObject {
            put("p_limit", limit)
        })
        val rows = Json.parseToJsonElement(raw.toString()).jsonArray
        return rows.map { Json.decodeFromJsonElement<AdminAuditEvent>(it) }
    }

    private suspend fun adminAction(
        action: String,
        username: String,
        displayName: String? = null
    ): AdminUsersResponse {
        val response = client.functions.invoke("admin-users", body = buildJsonObject {
            put("action", action)
            put("username", username.trim().lowercase())
            if (displayName != null) put("display_name", displayName.trim())
        })
        val result = response.body<AdminUsersResponse>()
        if (!result.success) error(result.error.ifBlank { result.message.ifBlank { "Thao tác quản trị thất bại" } })
        return result
    }

    suspend fun createKtv(username: String, displayName: String) =
        adminAction("create", username, displayName)

    suspend fun resetKtvPassword(username: String) =
        adminAction("reset_password", username)

    suspend fun lockKtv(username: String) = adminAction("lock", username)

    suspend fun unlockKtv(username: String) = adminAction("unlock", username)

    suspend fun signOut() {
        client.auth.signOut()
    }

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    fun isLoggedIn(): Boolean = client.auth.currentUserOrNull() != null
}
