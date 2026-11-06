#!/usr/bin/env python3
"""Regression checks for a stable Admin-history freshness watermark."""
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/vn/kholinhkien/MainActivity.kt").read_text(encoding="utf-8")
DATA = (ROOT / "app/src/main/java/vn/kholinhkien/Data.kt").read_text(encoding="utf-8")
checks = []
def check(name, ok):
    checks.append((name, bool(ok)))

check("Room exposes a Flow insertion watermark", 'fun observeMovementMaxId(): Flow<Long?>' in DATA)
check("Room exposes a one-shot watermark snapshot", 'suspend fun currentMovementMaxId(): Long?' in DATA)
check("Snapshot query uses MAX local insertion ID", 'SELECT MAX(id) FROM movements' in DATA)
check("Admin state stores the page snapshot watermark", 'var adminHistoryWatermark by remember(profile.id)' in MAIN)
check("Watermark is read before the first-page query", MAIN.index('val watermark = dao.currentMovementMaxId()') < MAIN.index('val rows = dao.latestAdminMovementPage(ADMIN_HISTORY_PAGE_SIZE)'))
check("Loaded watermark is retained independently of visible page rows", 'adminHistoryWatermark = pageSnapshot.first' in MAIN)
check("Freshness warning compares live watermark with snapshot", 'movementMaxId != adminHistoryWatermark' in MAIN)
check("Old page-derived watermark comparison is removed", 'movementMaxId != loadedMaxId' not in MAIN)
check("Role downgrade clears the Admin watermark", 'adminHistoryWatermark = null' in MAIN)
check("KTV history remains owner scoped", 'val movements = if (isAdmin) adminHistoryRows.toList() else ownMovements' in MAIN)

# Reproduce the false-positive in Step135: an older-timestamp delayed event
# can have a larger insertion ID yet be outside the first time-ordered page.
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE movements(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, syncId TEXT UNIQUE NOT NULL)')
for i in range(500):
    db.execute('INSERT INTO movements(time,syncId) VALUES(?,?)', (10_000-i, f'base-{i}'))
# Delayed sync exists before refresh, but its old event timestamp puts it past page 1.
db.execute('INSERT INTO movements(time,syncId) VALUES(?,?)', (1, 'delayed-before-refresh'))
watermark = db.execute('SELECT MAX(id) FROM movements').fetchone()[0]
page = db.execute('SELECT id,time FROM movements ORDER BY time DESC,id DESC LIMIT 200').fetchall()
check("SQLite: old-timestamp row can be outside first page", all(row[0] != watermark for row in page))
check("SQLite: snapshot watermark does not stay stale after refresh", db.execute('SELECT MAX(id) FROM movements').fetchone()[0] == watermark)
# A later insertion, even with an old timestamp, must trigger the warning.
db.execute('INSERT INTO movements(time,syncId) VALUES(?,?)', (0, 'delayed-after-snapshot'))
live = db.execute('SELECT MAX(id) FROM movements').fetchone()[0]
check("SQLite: insertion after snapshot triggers freshness warning", live != watermark)
# A normal newest event after snapshot is also detected.
db.execute('INSERT INTO movements(time,syncId) VALUES(?,?)', (20_000, 'newest-after-snapshot'))
live2 = db.execute('SELECT MAX(id) FROM movements').fetchone()[0]
check("SQLite: newest event after snapshot triggers freshness warning", live2 != watermark)

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
print(f"STEP136_VERIFIER={sum(ok for _, ok in checks)}/{len(checks)}")
if not all(ok for _, ok in checks):
    raise SystemExit(1)
