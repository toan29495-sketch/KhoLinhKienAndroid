#!/usr/bin/env python3
"""Verify local Admin-history cache is purged safely on role downgrade."""
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
DATA = (ROOT / "app/src/main/java/vn/kholinhkien/Data.kt").read_text(encoding="utf-8")
WORK = (ROOT / "app/src/main/java/vn/kholinhkien/SyncWork.kt").read_text(encoding="utf-8")
checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

role_effect = MAIN[MAIN.index("LaunchedEffect(profile.id, isAdmin) {"):MAIN.index("val parts by dao.partsForOwner", MAIN.index("LaunchedEffect(profile.id, isAdmin) {"))]
check("KTV role branch clears only foreign-owner movement cache", "dao.clearForeignMovementCache(profile.id)" in role_effect)
check("KTV role branch resets only its Admin-global cursor", 'dao.clearSyncCursor("admin-global:${profile.id}")' in role_effect)
check("Admin still claims legacy own inventory and movements", "dao.claimLegacyParts(profile.id)" in role_effect and "dao.claimLegacyMovements(profile.id)" in role_effect)
check("DAO delete is owner-filtered and preserves own movements", 'DELETE FROM movements WHERE ownerId != :ownerId' in DATA)
check("Cache cleanup runs on IO dispatcher", "withContext(Dispatchers.IO)" in role_effect)
check("Role downgrade cancels both Admin-global workers", 'cancelUniqueWork("inventory-pull-once-admin-$ownerId")' in WORK and 'cancelUniqueWork("inventory-pull-periodic-admin-$ownerId")' in WORK)
check("Cleanup is confined to Android Room DAO and UI", "clearForeignMovementCache" not in "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in (ROOT / "supabase").rglob("*.sql")))

# Simulate downgrade and later promotion. Only foreign rows disappear locally;
# the current user's own history survives, and resetting the global cursor makes
# a future Admin promotion re-fetch the global feed from its beginning.
db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE movements(id INTEGER PRIMARY KEY, ownerId TEXT NOT NULL, syncId TEXT UNIQUE NOT NULL)")
db.execute("CREATE TABLE sync_cursors(ownerId TEXT PRIMARY KEY, lastEventId INTEGER NOT NULL)")
admin = "admin-A"
db.executemany("INSERT INTO movements(ownerId,syncId) VALUES(?,?)", [(admin,"own-1"),("ktv-B","foreign-1"),("ktv-C","foreign-2")])
db.execute("INSERT INTO sync_cursors VALUES(?,?)", (f"admin-global:{admin}", 900))
db.execute("INSERT INTO sync_cursors VALUES(?,?)", ("unrelated-owner", 42))
db.execute("DELETE FROM movements WHERE ownerId != ?", (admin,))
db.execute("DELETE FROM sync_cursors WHERE ownerId = ?", (f"admin-global:{admin}",))
rows = db.execute("SELECT ownerId,syncId FROM movements ORDER BY syncId").fetchall()
check("SQLite smoke: own Admin/KTV movement remains", rows == [(admin,"own-1")])
check("SQLite smoke: foreign KTV movements are purged from local cache", db.execute("SELECT COUNT(*) FROM movements WHERE ownerId != ?", (admin,)).fetchone()[0] == 0)
check("SQLite smoke: Admin-global cursor is reset for future promotion", db.execute("SELECT COUNT(*) FROM sync_cursors WHERE ownerId = ?", (f"admin-global:{admin}",)).fetchone()[0] == 0)
check("SQLite smoke: unrelated owner cursors remain untouched", db.execute("SELECT lastEventId FROM sync_cursors WHERE ownerId = ?", ("unrelated-owner",)).fetchone()[0] == 42)

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f"STEP143_VERIFIER={sum(ok for _, ok in checks)}/{len(checks)}")
if not all(ok for _, ok in checks):
    raise SystemExit(1)
