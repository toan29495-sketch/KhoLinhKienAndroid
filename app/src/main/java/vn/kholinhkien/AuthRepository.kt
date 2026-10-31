package vn.kholinhkien

import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from

class AuthRepository {
    private val client = SupabaseClientProvider.client

    private fun usernameToEmail(username: String): String {
        val normalized = username.trim().lowercase()
        require(Regex("^[a-z0-9_-]{3,32}$").matches(normalized)) {
            "Tên tài khoản không hợp lệ"
        }

        val parts = normalized.split("_", limit = 2)
        return if (parts.size == 2) {
            "${parts[0]}+${parts[1]}@gmail.com"
        } else {
            "$normalized@gmail.com"
        }
    }

    suspend fun signIn(username: String, password: String) {
        val email = usernameToEmail(username)
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun getCurrentProfile(): UserProfile {
        val userId = client.auth.currentUserOrNull()?.id
            ?: error("Bạn chưa đăng nhập")

        val profile = client.from("profiles")
            .select {
                filter {
                    eq("id", userId)
                }
            }
            .decodeSingle<UserProfile>()

        if (!profile.isActive) {
            client.auth.signOut()
            error("Tài khoản đã bị khóa")
        }

        return profile
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    fun isLoggedIn(): Boolean {
        return client.auth.currentUserOrNull() != null
    }
}
