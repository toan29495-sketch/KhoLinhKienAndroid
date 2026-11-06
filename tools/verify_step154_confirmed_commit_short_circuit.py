#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/vn/kholinhkien'
COMMIT = (BASE / 'RestoreCloudCommitter.kt').read_text(encoding='utf-8')
COORD = (BASE / 'RestoreCloudCoordinator.kt').read_text(encoding='utf-8')
SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611030013_restore_preserve_deleted_part_history.sql').read_text(encoding='utf-8')
checks = []
def check(name, condition): checks.append((name, bool(condition)))

confirmed = COMMIT.index('if (journal.status == "COMMIT_CONFIRMED")')
rpc = COMMIT.index('"commit_inventory_restore"')
unknown = COMMIT.index('status = "COMMIT_UNKNOWN"')
confirmed_end = COMMIT.index('// Reissuing the same idempotent RPC', confirmed)
check('confirmed commit is short-circuited before any RPC', confirmed < rpc)
check('confirmed path returns the durable snapshot cursor', 'snapshotCursor = journal.snapshotCursor' in COMMIT[confirmed:rpc])
check('confirmed path validates cursor before returning', 'require(journal.snapshotCursor > 0L)' in COMMIT[confirmed:rpc])
check('confirmed path preserves durable counts and manifest', all(x in COMMIT[confirmed:rpc] for x in ('journal.manifestSha256', 'journal.partCount', 'journal.movementCount')))
check('confirmed path cannot rewrite journal to COMMIT_UNKNOWN', 'saveRestoreJournal' not in COMMIT[confirmed:confirmed_end] and confirmed_end < unknown < rpc)
check('ambiguous path still persists COMMIT_UNKNOWN before replay RPC', unknown < rpc)
check('ambiguous path replays same restoreId idempotently', 'put("p_restore_id", restoreId)' in COMMIT)
check('server ledger returns already_committed metadata for same restoreId', "'status', 'already_committed'" in SQL and "'snapshot_cursor', v_existing.snapshot_cursor" in SQL)
check('server rejects owner or manifest conflict instead of accepting a different restore', 'RESTORE_ID_CONFLICT' in SQL)
check('coordinator continues snapshot download only after commit confirmation', '"COMMIT_CONFIRMED", "SNAPSHOT_DOWNLOADING"' in COORD)
check('local live inventory remains gated on verified downloaded snapshot', 'if (journal.status == "SNAPSHOT_DOWNLOADED")' in COORD)
check('same restore journal remains owner-bound', 'journal.ownerId == ownerId' in COMMIT)
check('no new server RPC or schema dependency introduced', COMMIT.count('client.postgrest.rpc(') == 1)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
