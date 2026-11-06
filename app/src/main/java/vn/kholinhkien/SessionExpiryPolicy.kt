package vn.kholinhkien

import android.content.Context

/** Absolute app-side sign-in age guard, independent of Supabase access-token refreshes. */
internal object SessionExpiryPolicy {
    const val MAX_SESSION_AGE_DAYS = 30L
    private const val PREFS_NAME = "auth_session_policy"
    private const val KEY_PREFIX = "authenticated_at_ms_"
    private const val MAX_SESSION_AGE_MS = MAX_SESSION_AGE_DAYS * 24L * 60L * 60L * 1000L

    fun markAuthenticated(context: Context, ownerId: String) {
        if (!isCanonicalOwnerId(ownerId)) return
        check(prefs(context).edit().putLong(key(ownerId), System.currentTimeMillis()).commit()) {
            "Không thể lưu thời điểm đăng nhập trên thiết bị"
        }
    }

    /** Missing timestamps deliberately require one fresh sign-in after this policy is introduced. */
    fun isExpired(context: Context, ownerId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isCanonicalOwnerId(ownerId)) return true
        val startedAt = prefs(context).getLong(key(ownerId), 0L)
        if (startedAt <= 0L || startedAt > nowMs) return true
        return nowMs - startedAt >= MAX_SESSION_AGE_MS
    }

    fun clear(context: Context, ownerId: String) {
        if (!isCanonicalOwnerId(ownerId)) return
        prefs(context).edit().remove(key(ownerId)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(ownerId: String) = KEY_PREFIX + ownerId
}
