#!/usr/bin/env python3
"""Verify foreground profile refresh and safe handling of temporary failures."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
AUTH = (ROOT / "app/src/main/java/vn/kholinhkien/AuthRepository.kt").read_text(encoding="utf-8")
WORK = (ROOT / "app/src/main/java/vn/kholinhkien/SyncWork.kt").read_text(encoding="utf-8")
PULL = (ROOT / "app/src/main/java/vn/kholinhkien/SyncPullWorker.kt").read_text(encoding="utf-8")
SENDER = (ROOT / "app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt").read_text(encoding="utf-8")
checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

block_start = MAIN.index("// Refresh the authoritative role/profile whenever the app returns to foreground.")
block_end = MAIN.index("if (profile.mustChangePassword)", block_start)
refresh = MAIN[block_start:block_end]
check("Lifecycle observer listens for foreground ON_START", "Lifecycle.Event.ON_START" in refresh and "LifecycleEventObserver" in refresh)
check("Observer is registered and removed with DisposableEffect", "lifecycle.addObserver(observer)" in refresh and "removeObserver(observer)" in refresh)
check("Refresh uses IO dispatcher", "withContext(Dispatchers.IO)" in refresh and "authRepository.getCurrentProfile()" in refresh)
check("Refresh is guarded against concurrent requests", "!profileRefreshInFlight" in refresh and "profileRefreshInFlight = true" in refresh)
check("Refreshed profile is applied only to the same account", "refreshed.id == profile.id" in refresh and "currentProfile?.id == profile.id" in refresh)
check("Cancellation is propagated", "catch (cancelled: CancellationException)" in refresh and "throw cancelled" in refresh)
check("Inactive profile clears current privileged session", "catch (inactive: InactiveProfileException)" in refresh and "currentProfile = null" in refresh)
check("Transient errors retain current profile and do not sign out", "catch (error: Exception)" in refresh and "vẫn giữ dữ liệu ngoại tuyến" in refresh and "authRepository.signOut()" not in refresh)
check("Repository distinguishes inactive profile explicitly", "class InactiveProfileException" in AUTH and "throw InactiveProfileException()" in AUTH)
check("Inactive profile sign-out preserves cancellation and still reports locked status", "catch (cancelled: CancellationException)" in AUTH and AUTH.index("throw InactiveProfileException()") > AUTH.index("client.auth.signOut()"))
check("Existing role transition effects still run from profile state", "LaunchedEffect(profile.id, isAdmin)" in MAIN and "clearForeignMovementCache(profile.id)" in MAIN)
check("KTV scheduler continues cancelling Admin-global workers", 'cancelUniqueWork("inventory-pull-once-admin-$ownerId")' in WORK and 'cancelUniqueWork("inventory-pull-periodic-admin-$ownerId")' in WORK)
check("Pull worker rechecks owner after RPC and per event", "client.auth.currentUserOrNull()?.id != ownerId" in PULL and PULL.count("client.auth.currentUserOrNull()?.id != ownerId") >= 2)
check("Outbox sender fences session at network boundaries", SENDER.count("requireActiveOwner(ownerId)") >= 5)

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f"RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS")
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
