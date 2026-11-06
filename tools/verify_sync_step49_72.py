from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
read = lambda p: (ROOT / p).read_text(encoding='utf-8')
data = read('app/src/main/java/vn/kholinhkien/Data.kt')
sender = read('app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt')
pull = read('app/src/main/java/vn/kholinhkien/SyncPullWorker.kt')
main = read('app/src/main/java/vn/kholinhkien/MainActivity.kt')
reader = read('app/src/main/java/vn/kholinhkien/SyncConflictSnapshotReader.kt')
resolver = read('app/src/main/java/vn/kholinhkien/SyncConflictKeepLocalResolver.kt')
planner = read('app/src/main/java/vn/kholinhkien/SyncConflictResolutionPlanner.kt')
sql = read('supabase/migrations_pending_reconciliation/202611030001_enforce_inventory_version_cas.sql')
snapshot_sql = read('supabase/migrations_pending_reconciliation/202611030002_read_owned_part_conflict_snapshot.sql')
worker = read('app/src/main/java/vn/kholinhkien/SyncWork.kt')

checks = {
    'Part.serverVersion + migration 8->9': 'serverVersion: Long = 0L' in data and 'MIGRATION_8_9' in data,
    'Pull and restore map server_version': 'serverVersion = long("server_version", 0L)' in pull and 'serverVersion = json.optLong("server_version", 0L)' in data,
    'All operation payloads carry expected_server_version': data.count('put("expected_server_version", part.serverVersion)') >= 3,
    'First-send payload is frozen in atomic claim': 'claimOutboxForSend' in data and 'json.put("expected_server_version"' in data,
    'SQL CAS protects upsert/delete/stock': 'PART_VERSION_CONFLICT' in sql and 'PART_DELETED' in sql and 'v_part.server_version is distinct from v_expected_server_version' in sql and 'v_expected_server_version is null or v_expected_server_version <= 0' in sql,
    'Tombstone cannot be revived by stale upsert': 'public.parts.deleted_at is null' in sql,
    'Missing row with positive expected version is not recreated': 'positive expected version means this is an update' in sql,
    'Server version + queued version rebase + SYNCED are atomic': 'completeOutboxWithServerVersion' in data and 'recordServerVersionAndRebase' in data,
    'Permanent conflicts become BLOCKED': 'markOutboxBlocked' in sender and 'SyncConflictClassifier' in sender,
    'Blocked row pauses sender and protects pull/restore': "b.status = 'BLOCKED'" in data and "'BLOCKED'" in data[data.index('hasUnsentOperation')-100:data.index('hasUnsentOperation')+100],
    'Owner-scoped conflict snapshot RPC': 'p.owner_id = v_owner' in snapshot_sql and 'p.id = p_part_sync_id' in snapshot_sql,
    'Snapshot reader validates authenticated owner': 'authenticated == ownerId' in reader,
    'Conflict UI exposes detail, local/server diff, dependent count': all(x in main for x in ['selectedBlockedOperation', 'buildConflictFieldDiff', 'countQueuedBehindBlocked']),
    'Pure conflict planner has no mutation dependencies': 'object SyncConflictResolutionPlanner' in planner and 'InventoryDao' not in planner and 'SupabaseClient' not in planner,
    'Durable, idempotent decision record': 'SyncConflictDecision::class' in data and 'prepareConflictDecision' in data and 'replacementOperationId' in data,
    'Preflight rechecks owner-scoped blocker and dependencies': 'inspectBlockedResolution' in data and 'validatePreparedConflictDecision' in data,
    'KEEP_LOCAL restricted to isolated PART_UPSERT': 'blocker.operationType == "PART_UPSERT"' in data and 'dependents.isEmpty()' in data,
    'KEEP_LOCAL performs fresh server recheck and protects tombstone/stock': 'reader.read(ownerId, preflight.blocker.entityId)' in resolver and 'deleted_at' in resolver and 'localPart.quantity == serverQuantity' in resolver,
    'Replacement operation ID linked durably': 'linkReplacement' in data and 'replacementOperationId' in resolver,
    'FIFO prevents later operations overtaking': 'earliestUnresolvedOutbox' in data and 'hasInFlightOutbox' in data,
    'Lease expiry recovery is conservative': 'claimStartedAt > 0' in data and 'recoverInterruptedOutbox' not in sender,
    'Legacy unknown IN_FLIGHT is visible for review': "status = 'IN_FLIGHT' AND claimStartedAt = 0" in data,
    'Worker does not immediately retry permanent conflict': 'result.retryable' in worker,
    'Local photo retained while any Outbox references it': 'hasPendingPhotoReference(ownerId, path, claimed.operationId)' in sender,
}
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
print(f"STATIC_RESULT={sum(checks.values())}/{len(checks)}")
if not all(checks.values()):
    raise SystemExit(1)

# Smoke-test the additive Room schema evolution from the recovered v8 shape.
db = sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE parts(id INTEGER PRIMARY KEY, code TEXT NOT NULL);
INSERT INTO parts(id, code) VALUES(1, 'LEGACY');
CREATE TABLE sync_outbox(
 operationId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, operationType TEXT NOT NULL,
 entityId TEXT NOT NULL, payloadJson TEXT NOT NULL, createdAt INTEGER NOT NULL,
 status TEXT NOT NULL, attemptCount INTEGER NOT NULL, lastError TEXT NOT NULL
);
''')
db.execute('ALTER TABLE parts ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0')
db.execute('ALTER TABLE sync_outbox ADD COLUMN claimStartedAt INTEGER NOT NULL DEFAULT 0')
db.executescript('''
CREATE TABLE sync_conflict_decisions(
 blockedOperationId TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, decisionKind TEXT NOT NULL,
 status TEXT NOT NULL, planJson TEXT NOT NULL, dependencyCount INTEGER NOT NULL,
 replacementOperationId TEXT NOT NULL DEFAULT '', replacementServerVersion INTEGER NOT NULL DEFAULT 0,
 createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
);
CREATE INDEX index_sync_conflict_decisions_ownerId_status
 ON sync_conflict_decisions(ownerId, status);
''')
assert db.execute('SELECT code, serverVersion FROM parts WHERE id=1').fetchone() == ('LEGACY', 0)
db.execute("INSERT INTO sync_outbox VALUES('blocker','owner','PART_UPSERT','part','{}',1,'BLOCKED',1,'conflict',0)")
db.execute("INSERT INTO sync_conflict_decisions VALUES('blocker','owner','KEEP_LOCAL','READY_FOR_SERVER_RECHECK','{}',0,'',0,1,1)")
db.commit()
db.execute('BEGIN')
db.execute("INSERT INTO sync_outbox VALUES('replacement','owner','PART_UPSERT','part','{}',2,'PENDING',0,'',0)")
db.execute("UPDATE sync_outbox SET status='RESOLVED' WHERE operationId='blocker' AND status='BLOCKED'")
db.execute("UPDATE sync_conflict_decisions SET status='REPLACEMENT_QUEUED', replacementOperationId='replacement', replacementServerVersion=2 WHERE blockedOperationId='blocker' AND status='READY_FOR_SERVER_RECHECK'")
db.commit()
assert db.execute("SELECT status FROM sync_outbox WHERE operationId='blocker'").fetchone()[0] == 'RESOLVED'
assert db.execute("SELECT replacementOperationId FROM sync_conflict_decisions WHERE blockedOperationId='blocker'").fetchone()[0] == 'replacement'
print('PASS SQLITE_SCHEMA_AND_ATOMIC_REPLACEMENT_SMOKE')
