#!/usr/bin/env python3
"""Verify Admin-global pull workers revalidate server role and clean stale cache."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
PULL = (ROOT / "app/src/main/java/vn/kholinhkien/SyncPullWorker.kt").read_text(encoding="utf-8")
WORK = (ROOT / "app/src/main/java/vn/kholinhkien/SyncWork.kt").read_text(encoding="utf-8")
checks = []
def check(name, condition): checks.append((name, bool(condition)))
check("Admin worker validates active session before profile lookup", "client.auth.currentUserOrNull()?.id != ownerId" in PULL and "hasActiveAdminProfile(ownerId)" in PULL)
check("Admin role is read from authoritative profiles table", 'client.from("profiles").select' in PULL and 'eq("id", ownerId)' in PULL)
check("Profile must be active and explicitly Admin", "profile.isActive" in PULL and 'profile.role.equals("admin", ignoreCase = true)' in PULL)
check("Session is rechecked after profile lookup", PULL.count("client.auth.currentUserOrNull()?.id == ownerId") >= 1)
check("Admin role is checked before starting global feed", PULL.index("if (isAdmin && !hasActiveAdminProfile(ownerId))") < PULL.index("val activeRestore = dao.activeRestore(ownerId)"))
check("Admin role is checked again after RPC response", PULL.index("if (isAdmin && !hasActiveAdminProfile(ownerId))", PULL.index("val response =")) > PULL.index("val response ="))
check("Role downgrade during a page triggers cache cleanup", "If the role changed while applying a fetched page" in PULL)
check("Cleanup preserves own movements by using foreign-owner DAO method", PULL.count("dao.clearForeignMovementCache(ownerId)") >= 3)
check("Only Admin-global cursor is reset", PULL.count("dao.clearSyncCursor(cursorKey)") >= 3 and 'cursorKey = if (isAdmin) "admin-global:$ownerId" else ownerId' in PULL)
check("Normal KTV owner pull remains independent of Admin role", 'if (isAdmin) "pull_admin_movement_changes" else "pull_inventory_changes"' in PULL)
check("Scheduler still cancels global work on KTV role", 'cancelUniqueWork("inventory-pull-once-admin-$ownerId")' in WORK and 'cancelUniqueWork("inventory-pull-periodic-admin-$ownerId")' in WORK)
check("Cancellation remains propagated", "catch (cancelled: CancellationException)" in PULL and "throw cancelled" in PULL)
for name, ok in checks: print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f"RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS")
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
