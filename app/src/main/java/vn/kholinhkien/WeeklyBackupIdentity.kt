package vn.kholinhkien

import io.github.jan.supabase.auth.auth
import java.util.UUID

/** Shared fail-closed identity checks for owner-scoped weekly backup components. */
internal fun isCanonicalOwnerId(value: String): Boolean {
    if (!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value)) {
        return false
    }
    return runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }
        .getOrDefault(false)
}

/** Refuse owner-scoped local recovery if the active Supabase identity changed. */
internal fun requireActiveOwner(ownerId: String) {
    val activeId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
    if (activeId != ownerId) {
        throw WeeklyBackupIdentityException(
            "Tài khoản đã thay đổi hoặc phiên đăng nhập hết hạn; đã dừng thao tác để bảo vệ dữ liệu"
        )
    }
}
