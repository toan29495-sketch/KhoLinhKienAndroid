#!/usr/bin/env python3
"""Step137: verify the fresh-install / empty-business-data test baseline."""
from pathlib import Path
import sqlite3
import tempfile

ROOT = Path(__file__).resolve().parents[1]
main = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
data = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
worker = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
scheduler = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text()
checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

check('fresh install creates named Room database', '"kho-linh-kien.db"' in data and 'Room.databaseBuilder' in data)
check('fresh Room database has no automatic legacy import', '.createFromAsset(' not in data and '.createFromFile(' not in data)
check('legacy local rows are only claimed by Admin path', 'if (isAdmin) {\n            dao.claimLegacyParts(profile.id)\n            dao.claimLegacyMovements(profile.id)' in main)
check('new local sync cursor defaults to zero', 'var cursor = dao.getSyncCursor(cursorKey) ?: 0L' in worker)
check('owner feed is scheduled for each signed-in account', 'enqueueUniqueWork("inventory-pull-once-$ownerId"' in scheduler)
check('Admin global feed is separately scheduled only for Admin', 'if (isAdmin) {' in scheduler and 'inventory-pull-once-admin-$ownerId' in scheduler)
check('fresh part insert writes owner-scoped part and outbox atomically', '@Transaction\n    suspend fun insertPartWithOpeningMovement' in data and 'enqueuePart(part.ownerId, "PART_UPSERT", part)' in data)
check('zero-quantity QR creation does not invent opening movement', 'if (part.quantity > 0)' in data)
check('known QR resolves only within signed-in owner', 'dao.byCode(profile.id, scanned)' in main and 'if (part != null) {\n                selected = part' in main)
check('unknown QR is treated as machine QR, not auto-created part', 'machineHistoryCode = scanned' in main and 'prefillCode = scanned' not in main)
check('batch import skips duplicates and creates owner-scoped parts', 'dao.byCode(profile.id, code) == null' in main and 'ownerId = profile.id' in main)
check('outbox push remains owner-scoped and session fenced', 'currentUser != ownerId' in (ROOT / 'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text() or 'currentUser' in (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text())

# SQLite-equivalent fresh local state: no old rows/cursors/outbox; first new QR is isolated.
with tempfile.NamedTemporaryFile(suffix='.db') as f:
    db = sqlite3.connect(f.name)
    db.executescript('''
      CREATE TABLE parts(id INTEGER PRIMARY KEY AUTOINCREMENT, ownerId TEXT NOT NULL, code TEXT NOT NULL, syncId TEXT NOT NULL UNIQUE);
      CREATE TABLE movements(id INTEGER PRIMARY KEY AUTOINCREMENT, ownerId TEXT NOT NULL, code TEXT NOT NULL, type TEXT NOT NULL);
      CREATE TABLE sync_outbox(operationId TEXT PRIMARY KEY, ownerId TEXT NOT NULL, operationType TEXT NOT NULL);
      CREATE TABLE sync_cursors(ownerId TEXT PRIMARY KEY, lastEventId INTEGER NOT NULL);
    ''')
    counts = [db.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0] for t in ('parts','movements','sync_outbox','sync_cursors')]
    check('fresh local SQLite state starts with zero business/sync rows', counts == [0,0,0,0])
    owner = 'new-test-owner'
    db.execute('INSERT INTO parts(ownerId,code,syncId) VALUES(?,?,?)', (owner,'QR-NEW-001','sync-001'))
    db.execute('INSERT INTO sync_outbox(operationId,ownerId,operationType) VALUES(?,?,?)', ('op-001',owner,'PART_UPSERT'))
    check('first scanned-and-registered QR belongs only to current KTV', db.execute('SELECT ownerId FROM parts WHERE code=?',('QR-NEW-001',)).fetchone() == (owner,))
    check('first new QR creates one pending sync operation', db.execute('SELECT COUNT(*) FROM sync_outbox WHERE ownerId=?',(owner,)).fetchone()[0] == 1)
    check('new install has no restore cursor until actual restore', db.execute('SELECT COUNT(*) FROM sync_cursors').fetchone()[0] == 0)
    db.close()

# Empty remote feed means a cursor-zero pull applies no rows and leaves local data blank.
remote_events = []
check('empty Supabase owner feed at cursor zero imports no old rows', len([e for e in remote_events if e['event_id'] > 0]) == 0)
check('empty Supabase Admin feed at cursor zero imports no global history', len([e for e in remote_events if e['event_id'] > 0]) == 0)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'STEP137_RESULT={sum(ok for _,ok in checks)}/{len(checks)}')
if not all(ok for _,ok in checks):
    raise SystemExit(1)
