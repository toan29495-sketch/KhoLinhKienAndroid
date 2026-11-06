#!/usr/bin/env python3
"""Source-level regression checks for Admin history freshness and role fencing."""
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
DATA = (ROOT / "app/src/main/java/vn/kholinhkien/Data.kt").read_text(encoding="utf-8")
ADMIN = (ROOT / "app/src/main/java/vn/kholinhkien/AdminScreen.kt").read_text(encoding="utf-8")
AUTH = (ROOT / "app/src/main/java/vn/kholinhkien/AuthRepository.kt").read_text(encoding="utf-8")
checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

check("Admin movement insertion watermark is a scalar Flow", 'fun observeMovementMaxId(): Flow<Long?>' in DATA and 'SELECT MAX(id) FROM movements' in DATA)
check("MainActivity observes movement insertion watermark", 'dao.observeMovementMaxId().collectAsState(initial = null)' in MAIN)
check("New or delayed synced rows and restore changes are signalled against a refresh snapshot", 'movementMaxId != adminHistoryWatermark' in MAIN and 'Lịch sử đã thay đổi. Hãy làm mới để cập nhật.' in MAIN)
check("Refresh action remains available", 'TextButton(onClick = { if (!adminHistoryLoading) adminHistoryRefresh++ }' in MAIN)
check("KTV history remains owner-scoped", 'dao.movementsForOwner(profile.id).collectAsState(initial = emptyList())' in MAIN and 'val movements = if (isAdmin) adminHistoryRows.toList() else ownMovements' in MAIN)
check("Admin downgrade resets selected Admin-only tab", 'LaunchedEffect(profile.id, isAdmin)' in MAIN and 'if (!isAdmin && tab == 3) tab = 0' in MAIN)
check("Restore snapshot replacement is owner-scoped", 'DELETE FROM movements WHERE ownerId = :ownerId' in DATA)
check("Admin account tools still receive only own movements", 'ToolsScreen(\n                    parts, ownMovements, profile.id' in MAIN)
check("KTV admin UI has no display-name edit action", 'display_name' not in ADMIN and 'rename' not in ADMIN.lower())
check("Client admin repository supports create/reset/lock only", 'adminAction("create"' in AUTH and 'adminAction("reset_password"' in AUTH and 'adminAction("lock"' in AUTH and 'adminAction("rename"' not in AUTH)
check("Owner name can be enriched only when movement is pulled", 'updatePulledMovementOwnerName' in DATA and 'movement.ownerName.isNotBlank()' in DATA)
check("Machine history query is still scoped for KTV", '(:ownerId IS NULL OR ownerId = :ownerId)' in DATA)

# SQLite model: deterministic head detection and paged list remain stable.
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE movements(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, syncId TEXT NOT NULL UNIQUE, ownerId TEXT NOT NULL)')
for i in range(1, 451):
    db.execute('INSERT INTO movements(time,syncId,ownerId) VALUES(?,?,?)', (i // 3, f's{i}', f'ktv{i%4}'))
page = db.execute('SELECT id,time,syncId FROM movements ORDER BY time DESC,id DESC LIMIT 200').fetchall()
max_id_before = max(r[0] for r in page)
for i in range(451, 456):
    # Delayed events deliberately carry older timestamps than the current head.
    db.execute('INSERT INTO movements(time,syncId,ownerId) VALUES(?,?,?)', (20, f's{i}', 'ktv-new'))
max_id_after = db.execute('SELECT MAX(id) FROM movements').fetchone()[0]
check("SQLite smoke: delayed old-timestamp sync changes insertion watermark", max_id_after > max_id_before)
# The older-page cursor must remain anchored to the previous page, not jump over new rows.
oldest_time, oldest_id = page[-1][1], page[-1][0]
next_page = db.execute('SELECT id,time,syncId FROM movements WHERE time < ? OR (time = ? AND id < ?) ORDER BY time DESC,id DESC LIMIT 200', (oldest_time, oldest_time, oldest_id)).fetchall()
all_loaded = page + next_page
check("SQLite smoke: keyset pages have no overlap", len({r[0] for r in all_loaded}) == len(all_loaded))
check("SQLite smoke: keyset pages are descending", all((a[1],a[0]) > (b[1],b[0]) for a,b in zip(all_loaded, all_loaded[1:])))

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f"STEP135_VERIFIER={sum(ok for _,ok in checks)}/{len(checks)}")
if not all(ok for _,ok in checks):
    raise SystemExit(1)
