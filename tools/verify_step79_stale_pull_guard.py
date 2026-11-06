from pathlib import Path
import sqlite3

source = Path('app/src/main/java/vn/kholinhkien/Data.kt').read_text()
start = source.index('suspend fun applyPulledPart(')
end = source.index('\n    }', start) + 6
body = source[start:end]
checks = {
    'pull rejects blank or mismatched owner/entity': 'part.ownerId == ownerId' in body and 'part.syncId.isNotBlank()' in body,
    'pull requires positive server version': 'require(part.serverVersion > 0L)' in body,
    'pull still pauses for active restore': 'if (activeRestore(ownerId) != null) return false' in body,
    'pull still pauses behind unresolved local mutations': 'if (hasUnsentOperation(ownerId, part.syncId)) return false' in body,
    'existing local row is loaded before mutation': 'val existing = partBySyncId(ownerId, part.syncId)' in body,
    'stale or duplicate events cannot overwrite newer live or tombstone version': 'highestKnownVersion >= part.serverVersion) return true' in body,
    'tombstone deletion persists version before physical delete': body.index('savePartTombstone(') < body.index('deletePulledPart('),
    'live upsert clears only superseded tombstone': 'clearPartTombstone(ownerId, part.syncId)' in body and 'upsertPulledPart(part.copy(id = existing?.id ?: 0L, ownerId = ownerId))' in body,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)

# Exercise the exact monotonic-version decision used by the DAO against SQLite state.
con = sqlite3.connect(':memory:')
con.execute('CREATE TABLE parts(sync_id TEXT PRIMARY KEY, server_version INTEGER NOT NULL, deleted INTEGER NOT NULL)')
con.execute('INSERT INTO parts VALUES (?, ?, ?)', ('p1', 12, 0))

def apply(event_version, deleted):
    row = con.execute('SELECT server_version FROM parts WHERE sync_id=?', ('p1',)).fetchone()
    if row is not None and row[0] >= event_version:
        return 'ignored_stale_or_duplicate'
    if deleted:
        con.execute('DELETE FROM parts WHERE sync_id=?', ('p1',))
    else:
        con.execute('INSERT INTO parts VALUES (?, ?, 0) ON CONFLICT(sync_id) DO UPDATE SET server_version=excluded.server_version, deleted=0', ('p1', event_version))
    return 'applied'

checks['SQLite model ignores old live event after newer row'] = apply(11, False) == 'ignored_stale_or_duplicate' and con.execute('SELECT server_version FROM parts').fetchone()[0] == 12
checks['SQLite model ignores old tombstone after newer live row'] = apply(10, True) == 'ignored_stale_or_duplicate' and con.execute('SELECT server_version FROM parts').fetchone()[0] == 12
checks['SQLite model applies newer tombstone'] = apply(13, True) == 'applied' and con.execute('SELECT COUNT(*) FROM parts').fetchone()[0] == 0
# Step80 adds a durable tombstone/version fence so replay after process death
# cannot resurrect a row with an older live event.
pull = Path('app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
checks['pull cursor advances in common path after event dispatch'] = pull.rindex('cursor = eventId') > pull.index('dao.applyPulledPart(ownerId, part, deleted)')

for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STATIC_AND_SQLITE_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
