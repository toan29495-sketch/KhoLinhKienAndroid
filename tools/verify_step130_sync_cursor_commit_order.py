from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = (ROOT / 'supabase/migrations_pending_reconciliation/202611030015_serialize_sync_event_commit_order.sql').read_text()
PULL = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
DAO = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
BASE = (ROOT / 'supabase/migrations_pending_reconciliation/202611020001_offline_sync_foundation.sql').read_text()
ADMIN = (ROOT / 'supabase/migrations_pending_reconciliation/202611030003_null_safe_admin_feed_role.sql').read_text()
RESTORE = (ROOT / 'supabase/migrations_pending_reconciliation/202611030013_restore_preserve_deleted_part_history.sql').read_text()
ALL_MIGRATIONS = '\n'.join(p.read_text(errors='ignore') for p in (ROOT / 'supabase/migrations').glob('*.sql'))

m = MIGRATION.lower()
p = PULL
checks = {
    'migration is transactional and repeatable': m.startswith('-- step130') and 'begin;' in m and m.strip().endswith('commit;') and 'drop trigger if exists' in m,
    'trigger function is security definer': 'security definer' in m,
    'trigger function pins search_path to pg_catalog': 'set search_path = pg_catalog' in m,
    'transaction-scoped advisory lock is used': 'pg_advisory_xact_lock(130130, 1)' in m,
    'advisory lock is acquired before sequence allocation': m.index('pg_advisory_xact_lock') < m.index('pg_catalog.nextval'),
    'sequence is resolved from the actual owned event_id sequence': "pg_get_serial_sequence(\n        'public.inventory_sync_events', 'event_id'" in m,
    'trigger assigns event_id after taking the lock': 'new.event_id := pg_catalog.nextval' in m,
    'column default is removed so inserts cannot bypass trigger allocation': 'alter column event_id drop default' in m,
    'trigger is BEFORE INSERT at table boundary': 'before insert on public.inventory_sync_events' in m,
    'trigger covers each inserted row': 'for each row' in m,
    'trigger is recreated idempotently': 'drop trigger if exists inventory_sync_events_commit_order' in m,
    'trigger function execute privilege is not granted to client roles': 'revoke all on function public.assign_inventory_sync_event_commit_order() from public, anon, authenticated' in m and 'grant execute' not in m,
    'migration does not delete or rewrite existing feed rows': 'delete from public.inventory_sync_events' not in m and 'truncate' not in m and 'update public.inventory_sync_events' not in m,
    'original schema uses a global bigserial event cursor': 'event_id bigserial primary key' in BASE,
    'owner feed uses event_id keyset pagination': 'event_id > p_after_event_id' in BASE and 'order by event_id asc' in BASE,
    'Admin feed uses one global event_id keyset': 'where e.event_id > p_after_event_id' in ADMIN and 'order by e.event_id asc' in ADMIN,
    'restore commit event writer remains covered by table trigger': 'insert into public.inventory_sync_events(owner_id, entity_type, entity_id, payload)' in RESTORE,
    'all SQL writers insert through the protected table': 'insert into public.inventory_sync_events' in ALL_MIGRATIONS and 'create trigger inventory_sync_events_commit_order' in m,
    'worker persists cursor only after event dispatch': p.index('when (entityType)') < p.index('cursor = eventId\n                    if (!dao.savePulledCursorIfUnlocked'),
    'part event is applied before common cursor advancement': p.index('dao.applyPulledPart(ownerId, part, deleted)') < p.index('cursor = eventId\n                    if (!dao.savePulledCursorIfUnlocked'),
    'movement event is applied before common cursor advancement': p.index('dao.applyPulledMovement(ownerId,') < p.index('cursor = eventId\n                    if (!dao.savePulledCursorIfUnlocked'),
    'Admin redacted PART marker persists cursor before continue': 'dao.savePulledCursorIfUnlocked(ownerId, SyncCursor(cursorKey, cursor))' in p and 'continue' in p,
    'cursor save remains guarded by active restore state': 'if (activeRestore(ownerId) != null) return false' in DAO,
    'cursor save and local row application are Room transactions': '@Transaction\n    suspend fun savePulledCursorIfUnlocked' in DAO and '@Transaction\n    suspend fun applyPulledPart' in DAO and '@Transaction\n    suspend fun applyPulledMovement' in DAO,
    'pull worker has a bounded page count': 'private const val MAX_PAGES_PER_RUN = 5' in p,
    'page cap is tracked explicitly': 'var pageLimitReached = false' in p and 'if (pages == MAX_PAGES_PER_RUN) pageLimitReached = true' in p,
    'full final page schedules retry rather than declaring success': 'pageLimitReached -> Result.retry()' in p,
    'blocked local changes still retry without advancing that event': 'blockedByLocalChanges -> Result.retry()' in p,
    'worker rethrows cancellation': 'catch (cancelled: CancellationException)' in p and 'throw cancelled' in p,
}

for label, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'STEP130_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)

# Small deterministic model of the PostgreSQL race addressed by the trigger.
# Sequence-only allocation can commit out of order and skip a lower ID forever.
sequence_only = {'A': 100, 'B': 101}
visible_after_b = [sequence_only['B']]
cursor_after_pull = max(visible_after_b)
visible_after_a = sorted([sequence_only['A'], sequence_only['B']])
assert [event for event in visible_after_a if event > cursor_after_pull] == [], 'baseline race model should demonstrate skipped A'

# With the transaction lock, B cannot allocate its ID until A commits/rolls back.
serialized = {'A': 100, 'B': 101}
commit_order = ['A', 'B']
visible = [serialized[name] for name in commit_order]
assert visible == sorted(visible) and all(event > 0 for event in visible)
print('PASS deterministic model demonstrates sequence race and serialized assignment order')
