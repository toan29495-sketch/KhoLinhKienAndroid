#!/usr/bin/env python3
"""Apply Step212: absolute 30-day session expiry and 30-day automatic cloud backup."""
from pathlib import Path

ROOT = Path(".")
JAVA = ROOT / "app/src/main/java/vn/kholinhkien"
policy = JAVA / "SessionExpiryPolicy.kt"
policy.parent.mkdir(parents=True, exist_ok=True)
policy.write_text("""package vn.kholinhkien

import android.content.Context

/** Absolute app-side sign-in age guard, independent of Supabase token refreshes. */
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
""")

login = JAVA / "LoginScreen.kt"
s = login.read_text()
# Ensure the Compose screen exposes an Android Context for session timestamp storage.
context_import = "import androidx.compose.ui.platform.LocalContext"
if context_import not in s:
    anchor = "import androidx.compose.runtime.*"
    if anchor not in s:
        raise SystemExit("Step212: cannot locate Compose runtime imports in LoginScreen.kt")
    s = s.replace(anchor, anchor + "\\n" + context_import, 1)
if "val context = LocalContext.current" not in s:
    anchor = "fun LoginScreen(\\n    onLoginSuccess: (UserProfile) -> Unit\\n) {"
    if anchor not in s:
        raise SystemExit("Step212: cannot locate LoginScreen composable body")
    s = s.replace(anchor, anchor + "\\n    val context = LocalContext.current", 1)
login.write_text(s)
old = "repository.signIn(username, password)\n                            repository.getCurrentProfile()"
new = "repository.signIn(username, password)\n                            repository.currentUserId()?.let { SessionExpiryPolicy.markAuthenticated(context, it) }\n                            repository.getCurrentProfile()"
if old in s:
    s = s.replace(old, new, 1)
elif "SessionExpiryPolicy.markAuthenticated(context, it)" not in s:
    raise SystemExit("Step212: cannot locate successful login/profile validation sequence")
login.write_text(s)

work = JAVA / "WeeklyBackupWorker.kt"
s = work.read_text()
s = s.replace("const val PERIODIC_INTERVAL_DAYS = 7L", "const val PERIODIC_INTERVAL_DAYS = 30L")
s = s.replace("ExistingPeriodicWorkPolicy.KEEP, request", "ExistingPeriodicWorkPolicy.UPDATE, request")
if "PERIODIC_INTERVAL_DAYS = 30L" not in s or "ExistingPeriodicWorkPolicy.UPDATE" not in s:
    raise SystemExit("Step212: failed to update periodic backup interval/policy")
work.write_text(s)

main = JAVA / "MainActivity.kt"
s = main.read_text()
old = "val restored = withContext(Dispatchers.IO) { authRepository.getCurrentProfile() }\n                currentProfile = restored"
new = """if (SessionExpiryPolicy.isExpired(context, restoringOwnerId)) {
                        if (isCanonicalOwnerId(restoringOwnerId)) {
                            SyncWorkScheduler.cancelOwner(context, restoringOwnerId)
                            RestoreRecoveryScheduler.cancelOwner(context, restoringOwnerId)
                            WeeklyBackupWorkScheduler.cancel(context, restoringOwnerId)
                        }
                        authRepository.signOut()
                        check(!authRepository.isLoggedIn()) { "Không thể kết thúc phiên đã quá 30 ngày" }
                        SessionExpiryPolicy.clear(context, restoringOwnerId)
                        currentProfile = null
                        sessionRestoreError = "Phiên đăng nhập đã đủ 30 ngày. Vui lòng đăng nhập lại. Dữ liệu kho trên máy không bị xóa."
                    } else {
                        val restored = withContext(Dispatchers.IO) { authRepository.getCurrentProfile() }
                        currentProfile = restored
                    }"""
if old in s:
    s = s.replace(old, new, 1)
elif "SessionExpiryPolicy.isExpired(context, restoringOwnerId)" not in s:
    raise SystemExit("Step212: cannot locate cold-start session restore insertion point")

needle = 'check(!authRepository.isLoggedIn()) {\n                    "Supabase chưa xác nhận kết thúc phiên đăng nhập"\n                }'
if needle in s and "SessionExpiryPolicy.clear(context, ownerId)" not in s:
    s = s.replace(needle, needle + "\n                SessionExpiryPolicy.clear(context, ownerId)", 1)

foreground_anchor = "val refreshed = withContext(Dispatchers.IO) {\n                            authRepository.getCurrentProfile()\n                        }"
foreground_insert = """if (SessionExpiryPolicy.isExpired(context, profile.id)) {
                            SyncWorkScheduler.cancelOwner(context, profile.id)
                            RestoreRecoveryScheduler.cancelOwner(context, profile.id)
                            WeeklyBackupWorkScheduler.cancel(context, profile.id)
                            authRepository.signOut()
                            check(!authRepository.isLoggedIn()) { "Không thể kết thúc phiên đã quá 30 ngày" }
                            SessionExpiryPolicy.clear(context, profile.id)
                            currentProfile = null
                            message = "Phiên đăng nhập đã đủ 30 ngày. Vui lòng đăng nhập lại. Dữ liệu kho trên máy không bị xóa."
                            return@launch
                        }
                        val refreshed = withContext(Dispatchers.IO) {
                            authRepository.getCurrentProfile()
                        }"""
if foreground_anchor in s:
    s = s.replace(foreground_anchor, foreground_insert, 1)
elif "SessionExpiryPolicy.isExpired(context, profile.id)" not in s:
    raise SystemExit("Step212: cannot locate foreground profile refresh insertion point")

sync_line = "SyncWorkScheduler.schedule(context, profile.id, isAdmin)"
if sync_line in s and "WeeklyBackupWorkScheduler.schedule(context, profile.id)" not in s:
    s = s.replace(sync_line, sync_line + "\n        WeeklyBackupWorkScheduler.schedule(context, profile.id)", 1)
elif "WeeklyBackupWorkScheduler.schedule(context, profile.id)" not in s:
    raise SystemExit("Step212: cannot locate validated-profile sync scheduler call site")
main.write_text(s)

(ROOT / "tools").mkdir(exist_ok=True)
(ROOT / "tools/verify_step212_session_expiry_backup.py").write_text("""#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
policy = (root / "app/src/main/java/vn/kholinhkien/SessionExpiryPolicy.kt").read_text()
login = (root / "app/src/main/java/vn/kholinhkien/LoginScreen.kt").read_text()
main = (root / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text()
worker = (root / "app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt").read_text()
checks = {
    "30-day absolute session age": "MAX_SESSION_AGE_DAYS = 30L" in policy and "nowMs - startedAt >= MAX_SESSION_AGE_MS" in policy,
    "per-owner committed sign-in timestamp": "KEY_PREFIX + ownerId" in policy and ".commit()" in policy,
    "timestamp recorded after successful authentication": "SessionExpiryPolicy.markAuthenticated(context, it)" in login,
    "cold-start expiry guard": "SessionExpiryPolicy.isExpired(context, restoringOwnerId)" in main,
    "foreground expiry guard": "SessionExpiryPolicy.isExpired(context, profile.id)" in main,
    "expiry retains local database": "AppDb" not in policy,
    "30-day recurring backup": "PERIODIC_INTERVAL_DAYS = 30L" in worker,
    "old periodic schedule updated": "ExistingPeriodicWorkPolicy.UPDATE" in worker,
    "validated profile schedules backup": "WeeklyBackupWorkScheduler.schedule(context, profile.id)" in main,
}
for label, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " - " + label)
print(f"RESULT: {sum(checks.values())}/{len(checks)} PASS")
if not all(checks.values()):
    raise SystemExit(1)
""")
print("Step212 patch script and verifier created.")
