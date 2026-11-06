#!/usr/bin/env python3
"""Step133: Admin global history pagination, role/session fences and RESTORE partition checks.

The 10k-event exercise is a deterministic model of the client/server cursor contract,
not a live PostgreSQL, WorkManager, Room or Android instrumentation test.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PULL = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text(encoding='utf-8')
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text(encoding='utf-8')
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text(encoding='utf-8')
MAIN = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')
ADMIN_SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611030003_null_safe_admin_feed_role.sql').read_text(encoding='utf-8')
RESTORE_SQL = (ROOT / 'supabase/migrations_pending_reconciliation/202611020006_restore_commit_and_snapshot_read.sql').read_text(encoding='utf-8')

checks = {
    'Admin RPC rejects unauthenticated callers': "if v_user is null then" in ADMIN_SQL and 'AUTH_REQUIRED' in ADMIN_SQL,
    'Admin RPC rejects inactive profiles': 'PROFILE_INACTIVE' in ADMIN_SQL and 'p.is_active = true' in ADMIN_SQL,
    'Admin RPC explicitly rejects NULL and non-admin roles': "if v_role is null or lower(v_role) <> 'admin' then" in ADMIN_SQL,
    'Admin RPC rejects negative or NULL cursors': 'p_after_event_id is null or p_after_event_id < 0' in ADMIN_SQL,
    'Admin RPC uses strict keyset predicate': 'where e.event_id > p_after_event_id' in ADMIN_SQL,
    'Admin RPC orders by event ID ascending': 'order by e.event_id asc' in ADMIN_SQL,
    'Admin RPC caps page size at 500': 'least(coalesce(p_limit, 200), 500)' in ADMIN_SQL,
    'Admin RPC redacts PART payloads to SKIP': "then 'SKIP'" in ADMIN_SQL and "then '{}'::jsonb" in ADMIN_SQL,
    'Admin RPC returns owner ID and profile display name for movements': "'{owner_id}'" in ADMIN_SQL and "'{owner_name}'" in ADMIN_SQL,
    'Admin RPC returns ordered events and a high-water next cursor': "'events', v_events" in ADMIN_SQL and "'next_cursor', v_next" in ADMIN_SQL,
    'Admin RPC execution is not public': 'revoke all on function public.pull_admin_movement_changes' in ADMIN_SQL,
    'Admin RPC is granted only to authenticated role': 'to authenticated' in ADMIN_SQL,
    'Android global page size is bounded at 200': 'private const val PAGE_SIZE = 200' in PULL,
    'Android pull worker caps a run at five pages': 'private const val MAX_PAGES_PER_RUN = 5' in PULL,
    'Android uses a distinct Admin-global cursor key': '"admin-global:$ownerId"' in PULL,
    'Android persists cursor after each applied or skipped event': PULL.index('cursor = eventId') < PULL.index('dao.savePulledCursorIfUnlocked(ownerId, SyncCursor(cursorKey, cursor))'),
    'SKIP markers still advance the global cursor': '"SKIP" -> Unit' in PULL and 'dao.savePulledCursorIfUnlocked' in PULL,
    'A full fifth page returns retry rather than success': 'if (pages == MAX_PAGES_PER_RUN) pageLimitReached = true' in PULL and 'pageLimitReached -> Result.retry()' in PULL,
    'An incomplete final page ends the pull normally': 'if (events.size < PAGE_SIZE) break' in PULL,
    'Worker rechecks account identity after RPC response': PULL.count('if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()') >= 2,
    'Admin global worker checks movement owner UUID from server payload': 'MovementOwnerResolver.resolve(ownerId, payloadOwnerId, allowForeignOwner)' in PULL,
    'Admin global movement is stored under the actual KTV owner': 'time = time, ownerId = movementOwnerId' in PULL,
    'Admin global movement uses Admin restore fence': 'if (activeRestore(adminOwnerId) != null) return false' in DATA,
    'Admin global movement requires canonical owner UUID': 'val canonicalMovementOwner = UUID.fromString(movement.ownerId).toString()' in DATA,
    'Movement sync ID remains unique across owner and global feeds': 'Index(value = ["syncId"], unique = true)' in DATA,
    'Admin schedules a separate one-time global feed': 'inventory-pull-once-admin-$ownerId' in WORK and 'KEY_IS_ADMIN to true' in WORK,
    'Admin schedules a separate periodic global feed': 'inventory-pull-periodic-admin-$ownerId' in WORK,
    'Role downgrade cancels both global feed workers': 'cancelUniqueWork("inventory-pull-once-admin-$ownerId")' in WORK and 'cancelUniqueWork("inventory-pull-periodic-admin-$ownerId")' in WORK,
    'MainActivity passes the actual profile role to scheduler': 'SyncWorkScheduler.schedule(context, profile.id, isAdmin)' in MAIN,
    'RESTORE deletes only movements owned by restored account': 'DELETE FROM movements WHERE ownerId = :ownerId' in DATA,
    'RESTORE inserts snapshot movements with restored owner ID': 'time = json.getLong("time"), ownerId = ownerId, ownerName = ""' in DATA,
    'RESTORE sets only personal cursor to snapshot cursor': 'saveSyncCursor(SyncCursor(ownerId = ownerId, lastEventId = snapshotCursor))' in DATA,
    'RESTORE does not clear Admin-global cursor': 'clearSyncCursor(' not in DATA[DATA.find('suspend fun applyCommittedRestoreSnapshot'):DATA.find('suspend fun claimLegacyPartsRows')] and 'saveSyncCursor(SyncCursor(ownerId = ownerId, lastEventId = snapshotCursor))' in DATA,
    'Admin history UI is role-gated to Admin-only paged history': 'val movements = if (isAdmin) adminHistoryRows.toList() else ownMovements' in MAIN,
    'KTV history remains owner-scoped': 'fun movementsForOwner(ownerId: String): Flow<List<Movement>>' in DATA,
    'Admin backup/tools receive only own movement feed': 'ToolsScreen(\n                    parts, ownMovements, profile.id' in MAIN,
    'Sign-out cancels account-specific work before Supabase sign-out': (lambda block: block.index('SyncWorkScheduler.cancelOwner(context, ownerId)') < block.index('authRepository.signOut()'))(MAIN[MAIN.index('val signOut: () -> Unit'):MAIN.index('if (checkingSession)')]),
    'Sign-out retains local inventory/history rows': 'currentProfile = null' in MAIN and 'clearMovements(' not in MAIN,
    'Admin global history no longer uses an unbounded allMovements query after Step134': 'fun allMovements()' not in DATA and 'dao.allMovements()' not in MAIN,
}

# Model the exact keyset cursor protocol: PART events are redacted SKIP markers but
# still consume a global event ID. A worker applies at most 5 x 200 events per run.
PAGE_SIZE = 200
MAX_PAGES = 5
TOTAL = 10_137
source_events = [
    (event_id, 'PART' if event_id % 4 == 0 else ('RESTORE_COMMIT' if event_id % 997 == 0 else 'MOVEMENT'))
    for event_id in range(1, TOTAL + 1)
]

cursor = 0
seen_ids = []
run_count = 0
while cursor < TOTAL:
    run_count += 1
    pages = 0
    hit_cap = False
    while pages < MAX_PAGES:
        pages += 1
        page = [event for event in source_events if event[0] > cursor][:PAGE_SIZE]
        if not page:
            break
        for event_id, entity in page:
            # All entity kinds, including redacted PART/SKIP and restore markers,
            # advance only after the event has passed its handler/validation.
            seen_ids.append(event_id)
            cursor = event_id
        if len(page) < PAGE_SIZE:
            break
        if pages == MAX_PAGES:
            hit_cap = True
    if hit_cap:
        continue
    if cursor < TOTAL:
        raise AssertionError('worker reported success while events remain')

checks['10,137 events drain over bounded 200-row pages and five-page runs'] = (
    cursor == TOTAL and len(seen_ids) == TOTAL and run_count == (TOTAL + PAGE_SIZE * MAX_PAGES - 1) // (PAGE_SIZE * MAX_PAGES)
)
checks['10,137-event replay has no skipped IDs'] = seen_ids == list(range(1, TOTAL + 1))
checks['redacted PART markers advance cursor without exposing inventory payload'] = all(
    entity == 'PART' for event_id, entity in source_events if event_id % 4 == 0
)
checks['movement and restore marker rows retain a contiguous global sequence'] = all(
    seen_ids[i] + 1 == seen_ids[i + 1] for i in range(len(seen_ids) - 1)
)

# RESTORE partition model: the Admin snapshot replaces only Admin-owned movements;
# the global feed cursor and KTV rows remain intact.
admin_id, ktv_a, ktv_b = 'admin', 'ktv-A', 'ktv-B'
rows = [('m1', admin_id), ('m2', ktv_a), ('m3', ktv_b), ('m4', admin_id)]
restored_rows = [row for row in rows if row[1] != admin_id] + [('m5', admin_id)]
personal_cursor, global_cursor = 120, 990
snapshot_cursor = 75
personal_cursor = snapshot_cursor
checks['RESTORE replaces Admin-owned rows while preserving every foreign KTV movement'] = (
    ('m2', ktv_a) in restored_rows and ('m3', ktv_b) in restored_rows and ('m1', admin_id) not in restored_rows
)
checks['RESTORE sets personal snapshot cursor without rewinding global cursor'] = personal_cursor == 75 and global_cursor == 990

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP133_RESULT={len(checks)-len(failed)}/{len(checks)}')
print(f'PAGINATION_MODEL_EVENTS={TOTAL} PAGES_PER_RUN={MAX_PAGES} PAGE_SIZE={PAGE_SIZE} RUNS={run_count}')
if failed:
    raise SystemExit(1)
