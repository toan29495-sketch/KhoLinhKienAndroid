#!/usr/bin/env python3
"""Verify cold-start session restore fails closed without discarding valid sessions."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
AUTH = (ROOT / "app/src/main/java/vn/kholinhkien/AuthRepository.kt").read_text(encoding="utf-8")
checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

start = MAIN.index("// Keep a valid Supabase session on transient failures.")
end = MAIN.index("val signOut: () -> Unit", start)
restore = MAIN[start:end]
ui_start = MAIN.index("if (currentProfile == null) {")
ui_end = MAIN.index("val profile = currentProfile ?: return", ui_start)
ui = MAIN[ui_start:ui_end]
check("Cold-start restore checks whether a Supabase session exists", "authRepository.isLoggedIn()" in restore)
check("Profile validation runs on IO dispatcher", "withContext(Dispatchers.IO) { authRepository.getCurrentProfile() }" in restore)
check("Coroutine cancellation is propagated", "catch (cancelled: CancellationException)" in restore and "throw cancelled" in restore)
check("Inactive account is distinguished from transport failure", "catch (inactive: InactiveProfileException)" in restore and "catch (error: Exception)" in restore)
transient_start = restore.index("catch (error: Exception)")
transient_end = restore.index("        }", transient_start)
transient_handler = restore[transient_start:transient_end]
check("Transient failure does not sign out", "catch (error: Exception)" in restore and "authRepository.signOut()" not in transient_handler)
check("Transient failure keeps the auth token and fails closed", "currentProfile = null" in restore and "do not expose cached Admin UI" in restore)
check("Retry screen appears only while session exists and restore failed", "sessionRestoreError.isNotBlank() && authRepository.isLoggedIn()" in ui)
check("Retry action reuses the same restore flow", "onRetry = { authScope.launch { restoreSession() } }" in ui)
check("User can explicitly sign out from restore screen", "onSignOut =" in ui and "authRepository.signOut()" in ui)
check("Normal login screen remains available without a session", "LoginScreen(onLoginSuccess" in ui)
check("Restore screen explains local data is preserved and inventory stays locked", "Dữ liệu trên điện thoại vẫn được giữ nguyên" in MAIN and "Kho chỉ mở sau khi xác minh" in MAIN)
check("Repository still throws explicit inactive status", "throw InactiveProfileException()" in AUTH)

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f"RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS")
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
