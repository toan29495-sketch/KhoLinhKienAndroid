from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
DAO = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
PULL = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
RESTORE = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotApplier.kt').read_text()
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text()

checks = {
    'cursor max update uses a monotonic CASE expression': 'CASE WHEN lastEventId < :eventId THEN :eventId ELSE lastEventId END' in DAO,
    'cursor max update is scoped to exact feed key': 'WHERE ownerId = :cursorOwnerId' in DAO,
    'missing cursor row is inserted without replacing an existing row': '@Insert(onConflict = OnConflictStrategy.IGNORE)\n    suspend fun insertSyncCursorIfAbsent' in DAO,
    'monotonic cursor update and insert execute inside Room transaction': '@Transaction\n    suspend fun savePulledCursorIfUnlocked' in DAO and DAO.index('updateSyncCursorMaximum(cursor.ownerId, cursor.lastEventId)') < DAO.index('insertSyncCursorIfAbsent(cursor)'),
    'pull cursor rejects negative event IDs': 'cursor.lastEventId >= 0L' in DAO,
    'pull cursor accepts only owner or that owner admin-global key': 'cursor.ownerId == ownerId || cursor.ownerId == "admin-global:$ownerId"' in DAO,
    'active restore blocks pull cursor advancement': 'if (activeRestore(ownerId) != null) return false' in DAO,
    'pull worker persists cursor only after event dispatch': PULL.index('when (entityType)') < PULL.index('cursor = eventId\n                    if (!dao.savePulledCursorIfUnlocked'),
    'PART data applies before common cursor advancement': PULL.index('dao.applyPulledPart(ownerId, part, deleted)') < PULL.index('cursor = eventId\n                    if (!dao.savePulledCursorIfUnlocked'),
    'MOVEMENT data applies before common cursor advancement': PULL.index('dao.applyPulledMovement(ownerId,') < PULL.index('cursor = eventId\n                    if (!dao.savePulledCursorIfUnlocked'),
    'part apply retains server-version and tombstone replay guard': 'highestKnownVersion >= part.serverVersion' in DAO and 'savePartTombstone' in DAO,
    'movement apply retains immutable syncId idempotency check excluding mutable ownerName': 'movementBySyncId(movement.syncId)' in DAO and 'existing.copy(id = 0, ownerName = "") == movement.copy(id = 0, ownerName = "")' in DAO,
    'restore snapshot still writes its exact authoritative snapshot cursor': 'saveSyncCursor(SyncCursor(ownerId = ownerId, lastEventId = snapshotCursor))' in DAO,
    'restore snapshot and journal completion remain in same DAO transaction': '@Transaction\n    suspend fun applyCommittedRestoreSnapshot' in DAO and 'saveRestoreJournal(journal.copy(status = "COMPLETE"' in DAO,
    'one-time and periodic workers are separately unique and can overlap': 'ExistingWorkPolicy.KEEP' in WORK and 'ExistingPeriodicWorkPolicy.KEEP' in WORK,
    'cursor SQL avoids newer SQLite UPSERT syntax': 'ON CONFLICT(ownerId) DO UPDATE' not in DAO,
    'pull cursor writes do not use REPLACE semantics': 'savePulledCursorIfUnlocked' in DAO and 'saveSyncCursor(cursor)' not in DAO[DAO.index('suspend fun savePulledCursorIfUnlocked'):DAO.index('@Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun saveRestoreJournal')],
    'worker cancellation remains propagated': 'catch (cancelled: CancellationException)' in PULL and 'throw cancelled' in PULL,
}
for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'STEP131_SOURCE_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)

# Exercise the same UPDATE + INSERT IGNORE algorithm against SQLite. Two workers
# may finish out of order; a stale worker must not roll the durable cursor backward.
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE sync_cursors(ownerId TEXT PRIMARY KEY NOT NULL, lastEventId INTEGER NOT NULL)')

def advance(key: str, event_id: int):
    with db:
        db.execute('UPDATE sync_cursors SET lastEventId = CASE WHEN lastEventId < ? THEN ? ELSE lastEventId END WHERE ownerId = ?', (event_id, event_id, key))
        db.execute('INSERT OR IGNORE INTO sync_cursors(ownerId,lastEventId) VALUES(?,?)', (key, event_id))

advance('owner-A', 400)  # faster worker
advance('owner-A', 200)  # stale worker finishes later
assert db.execute('SELECT lastEventId FROM sync_cursors WHERE ownerId=?', ('owner-A',)).fetchone()[0] == 400
print('PASS SQLite interleaving: stale worker cannot roll cursor 400 back to 200')
advance('owner-A', 450)
advance('owner-A', 450)
assert db.execute('SELECT lastEventId FROM sync_cursors WHERE ownerId=?', ('owner-A',)).fetchone()[0] == 450
print('PASS SQLite replay: duplicate cursor advancement is idempotent')
advance('admin-global:owner-A', 900)
assert db.execute('SELECT lastEventId FROM sync_cursors WHERE ownerId=?', ('owner-A',)).fetchone()[0] == 450
assert db.execute('SELECT lastEventId FROM sync_cursors WHERE ownerId=?', ('admin-global:owner-A',)).fetchone()[0] == 900
print('PASS SQLite partitioning: owner and Admin global cursors remain independent')
# The authoritative restore path intentionally uses REPLACE to set the snapshot
# cursor exactly; this is separate from monotonic ordinary pull advancement.
with db:
    db.execute('INSERT OR REPLACE INTO sync_cursors(ownerId,lastEventId) VALUES(?,?)', ('owner-A', 120))
assert db.execute('SELECT lastEventId FROM sync_cursors WHERE ownerId=?', ('owner-A',)).fetchone()[0] == 120
print('PASS SQLite restore reset: authoritative snapshot cursor can be set exactly')
