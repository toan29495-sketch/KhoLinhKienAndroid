#!/usr/bin/env python3
"""Static and deterministic model checks for concurrent delete vs stock change."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RPC = (ROOT / 'supabase/migrations_pending_reconciliation/202611030011_fence_inventory_mutations_against_restore.sql').read_text()
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
PULL = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
PLANNER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncConflictResolutionPlanner.kt').read_text()
checks = {
    'RPC serializes duplicate operation IDs through ledger row lock': 'on conflict (operation_id) do nothing' in RPC and 'for update' in RPC,
    'RPC validates immutable operation payload on replay': 'v_existing.payload is distinct from p_payload' in RPC,
    'delete uses optimistic version compare-and-set': "p_entity_type = 'PART_DELETE'" in RPC and 'server_version = (p_payload->>\'expected_server_version\')::bigint' in RPC,
    'stock mutation locks active part row': 'for update;' in RPC and 'deleted_at is null' in RPC,
    'stock quantity and version update are atomic': 'set quantity = v_after' in RPC and 'server_version = coalesce(server_version, 0) + 1' in RPC,
    'movement insert occurs in same RPC transaction after stock update': RPC.index('set quantity = v_after') < RPC.index('insert into public.movements'),
    'change-feed part and movement snapshots are written in same RPC': 'insert into public.inventory_sync_events' in RPC and "'MOVEMENT', v_movement.id" in RPC,
    'failed operation cannot be marked processed before successful mutation': RPC.index('insert into public.movements') < RPC.index('update public.sync_operations\n       set result'),
    'local delete queues immutable expected-version token': 'operationType = "PART_DELETE"' in DATA and 'expected_server_version' in DATA,
    'local delete and outbox enqueue share Room transaction': '@Transaction\n    suspend fun deletePartByCode' in DATA and 'enqueue(SyncOutbox' in DATA and 'deletePartRow(ownerId, code)' in DATA,
    'blocked conflicts stop later outbox operations': "status = 'BLOCKED'" in DATA and 'NOT EXISTS (SELECT 1 FROM sync_outbox b' in DATA and "b.status = 'BLOCKED'" in DATA,
    'pull preserves unsent local entity instead of overwriting it': 'if (hasUnsentOperation(ownerId, part.syncId)) return false' in PULL,
    'deleted/not-found conflict is explicitly classified': 'PART_DELETED' in PLANNER and 'PART_NOT_FOUND' in PLANNER,
}

# Model optimistic CAS for two transactions that read the same version.
def apply_order(first, second):
    version, qty, deleted, movements = 7, 5, False, []
    def apply(op):
        nonlocal version, qty, deleted, movements
        expected = 7
        if version != expected or deleted:
            return False
        if op == 'delete':
            deleted = True
        else:
            qty += 2
            movements.append((5, qty))
        version += 1
        return True
    a = apply(first)
    b = apply(second)
    return a, b, version, qty, deleted, movements

stock_then_delete = apply_order('stock', 'delete')
delete_then_stock = apply_order('delete', 'stock')
checks['stock wins: exactly one movement, delete conflicts on stale version'] = (
    stock_then_delete[0] and not stock_then_delete[1] and stock_then_delete[3] == 7
    and not stock_then_delete[4] and len(stock_then_delete[5]) == 1
)
checks['delete wins: stock conflicts and no orphan movement is inserted'] = (
    delete_then_stock[0] and not delete_then_stock[1] and delete_then_stock[4]
    and delete_then_stock[3] == 5 and len(delete_then_stock[5]) == 0
)
checks['neither ordering permits both stale operations to commit'] = (
    sum(stock_then_delete[:2]) == 1 and sum(delete_then_stock[:2]) == 1
)

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed:
    raise SystemExit(1)
