#!/usr/bin/env python3
"""Step132 source-contract and deterministic model checks for multi-device conflicts.

This is not a PostgreSQL concurrency test. It verifies the current CAS/idempotency
contract and models two devices that submit stale operations against one version.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text(encoding='utf-8')
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text(encoding='utf-8')
PULL = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text(encoding='utf-8')
PLANNER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncConflictResolutionPlanner.kt').read_text(encoding='utf-8')
RPC = (ROOT / 'supabase/migrations_pending_reconciliation/202611030011_fence_inventory_mutations_against_restore.sql').read_text(encoding='utf-8')
ADMIN = (ROOT / 'supabase/migrations_pending_reconciliation/202611020008_admin_global_movement_feed.sql').read_text(encoding='utf-8')
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text(encoding='utf-8')
MAIN = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text(encoding='utf-8')

checks = {
    'PART_UPSERT is guarded by expected server version CAS': "public.parts.server_version = (p_payload->>'expected_server_version')::bigint" in RPC,
    'STOCK_CHANGE parses and rejects missing or non-positive version': "if not (p_payload ? 'expected_server_version')" in RPC and 'v_expected_server_version is null or v_expected_server_version <= 0' in RPC,
    'STOCK_CHANGE locks the live owner-scoped part row': 'where id = p_entity_id and owner_id = v_owner and deleted_at is null\n         for update' in RPC,
    'stock quantity and version increment occur in one RPC transaction': 'set quantity = v_after, updated_at = now(),\n               server_version = coalesce(server_version, 0) + 1' in RPC,
    'movement row is inserted only after successful stock update': RPC.index('set quantity = v_after') < RPC.index('insert into public.movements'),
    'change-feed PART and MOVEMENT snapshots are emitted by same RPC': "values\n            (v_owner, 'PART', p_entity_id, to_jsonb(v_part)),\n            (v_owner, 'MOVEMENT', v_movement.id, to_jsonb(v_movement))" in RPC,
    'operation ledger locks retries by operation UUID': 'on conflict (operation_id) do nothing' in RPC and 'where operation_id = p_operation_id\n     for update' in RPC,
    'operation retry requires byte-for-field identical JSON payload': 'v_existing.payload is distinct from p_payload' in RPC,
    'processed retry returns cached result before reapplying mutation': RPC.index('if v_existing.processed_at is not null') < RPC.index("if p_entity_type = 'PART_UPSERT'"),
    'PART_DELETE uses expected version compare-and-set': "and server_version = (p_payload->>'expected_server_version')::bigint" in RPC,
    'local outbox permits only one unresolved IN_FLIGHT row per owner': 'if (hasInFlightOutbox(row.ownerId)) return null' in DATA,
    'local outbox claims earliest unresolved operation inside Room transaction': '@Transaction\n    suspend fun claimOutboxForSend' in DATA and 'earliestUnresolvedOutbox(row.ownerId)' in DATA,
    'successful server version acknowledgement and successor rebase are atomic': '@Transaction\n    suspend fun completeOutboxWithServerVersion' in DATA and 'recordServerVersionAndRebase(ownerId, entityId, serverVersion)' in DATA,
    'successor operations receive sequential expected versions': 'var nextExpectedVersion = version' in DATA and 'nextExpectedVersion = Math.addExact(nextExpectedVersion, 1L)' in DATA,
    'pull refuses to overwrite a part with unsent local operations': 'if (hasUnsentOperation(ownerId, part.syncId)) return false' in DATA,
    'pull part replay is guarded by monotonic server version and tombstone': 'highestKnownVersion >= part.serverVersion' in DATA and 'savePartTombstone' in DATA,
    'movement replay requires exact immutable payload match excluding mutable owner display name': 'existing.copy(id = 0, ownerName = "") == movement.copy(id = 0, ownerName = "")' in DATA,
    'stock and delete conflicts are manual-only': '"PART_DELETE", "STOCK_CHANGE" -> setOf("MANUAL_REVIEW")' in PLANNER,
    'metadata keep-local resolution is restricted to isolated PART_UPSERT': 'require(preflight.blocker.operationType == "PART_UPSERT")' in (ROOT / 'app/src/main/java/vn/kholinhkien/SyncConflictKeepLocalResolver.kt').read_text(encoding='utf-8'),
    'Admin global feed requires active admin role': "if lower(v_role) <> 'admin' then" in ADMIN,
    'Admin global feed redacts PART payloads to SKIP markers': "case when entity_type = 'PART' then 'SKIP'" in ADMIN and "when entity_type = 'PART' then '{}'::jsonb" in ADMIN,
    'Admin scheduling always keeps the personal owner feed': 'KEY_IS_ADMIN to false' in WORK and 'inventory-pull-once-$ownerId' in WORK and 'inventory-pull-periodic-$ownerId' in WORK,
    'Admin scheduling adds a separate global history feed': 'KEY_IS_ADMIN to true' in WORK and 'inventory-pull-once-admin-$ownerId' in WORK and 'inventory-pull-periodic-admin-$ownerId' in WORK,
    'role downgrade cancels obsolete Admin global workers': 'cancelUniqueWork("inventory-pull-once-admin-$ownerId")' in WORK and 'cancelUniqueWork("inventory-pull-periodic-admin-$ownerId")' in WORK,
    'MainActivity passes the authenticated profile role to scheduling': 'SyncWorkScheduler.schedule(context, profile.id, isAdmin)' in MAIN,
    'every MainActivity scheduling call carries an explicit Admin role decision': all(('currentProfile?.role?.equals("admin", ignoreCase = true) == true' in line) or ('profile.id, isAdmin)' in line) for line in MAIN.splitlines() if 'SyncWorkScheduler.schedule(' in line),
    'Admin worker routes movements through the Admin-specific DAO path': 'if (isAdmin) {\n                                dao.applyPulledAdminMovement(ownerId, movement)' in PULL,
    'Admin movement parser delegates owner resolution to strict shared helper': 'MovementOwnerResolver.resolve(ownerId, payloadOwnerId, allowForeignOwner)' in PULL and 'Admin global MOVEMENT thiếu owner_id' in (ROOT / 'app/src/main/java/vn/kholinhkien/MovementOwnerResolver.kt').read_text(encoding='utf-8'),
    'Admin movement rows retain the actual KTV owner ID': 'time = time, ownerId = movementOwnerId' in PULL,
    'Admin global DAO restore fence is tied to signed-in Admin': 'if (activeRestore(adminOwnerId) != null) return false' in DATA,
    'Admin global DAO validates both Admin and movement owner UUIDs': 'UUID.fromString(adminOwnerId)' in DATA and 'UUID.fromString(movement.ownerId)' in DATA,
    'owner and Admin feed dedupe ignores only mutable owner display name': 'existing.copy(id = 0, ownerName = "") == movement.copy(id = 0, ownerName = "")' in DATA,
    'nonblank Admin profile name enriches an existing movement without duplicating it': 'updatePulledMovementOwnerName(movement.syncId, movement.ownerName)' in DATA,
    'global movement sync ID remains unique across owner and Admin feeds': 'Index(value = ["syncId"], unique = true)' in DATA,
    'Admin global feed includes owner ID and display name in movement payload': "'{owner_id}'" in ADMIN and "'{owner_name}'" in ADMIN and "to_jsonb(owner_id::text)" in ADMIN,
    'pull worker fences account changes before applying events': 'if (client.auth.currentUserOrNull()?.id != ownerId) return Result.success()' in PULL,
    'pull worker persists movement cursor only after applying the movement': PULL.index('dao.applyPulledMovement(ownerId,') < PULL.rindex('dao.savePulledCursorIfUnlocked(ownerId, SyncCursor(cursorKey, cursor))'),
    'RESTORE fence blocks ordinary inventory mutations server-side': 'active_restore' in RPC.lower() or 'restore' in RPC.lower(),
}

class Server:
    def __init__(self, owner='owner-A', version=7, quantity=10):
        self.owner = owner
        self.version = version
        self.quantity = quantity
        self.deleted = False
        self.ledger = {}
        self.movements = []

    def apply(self, owner, op_id, kind, expected, payload):
        if owner != self.owner:
            return 'OWNER_REJECTED'
        frozen = (kind, expected, payload)
        old = self.ledger.get(op_id)
        if old is not None:
            if old[0] != frozen:
                return 'OPERATION_PAYLOAD_CONFLICT'
            return old[1]
        if self.deleted:
            return 'PART_NOT_FOUND'
        if expected != self.version:
            return 'PART_VERSION_CONFLICT'
        if kind == 'STOCK_CHANGE':
            delta = payload['delta']
            if self.quantity + delta < 0:
                return 'INSUFFICIENT_STOCK'
            before = self.quantity
            self.quantity += delta
            self.version += 1
            result = ('APPLIED', self.version, before, self.quantity)
            self.movements.append((op_id, before, self.quantity))
        elif kind == 'PART_UPSERT':
            self.version += 1
            result = ('APPLIED', self.version)
        elif kind == 'PART_DELETE':
            self.deleted = True
            self.version += 1
            result = ('APPLIED', self.version)
        else:
            return 'UNSUPPORTED'
        self.ledger[op_id] = (frozen, result)
        return result


def two_stock_order(first_owner, second_owner, reverse=False):
    server = Server()
    operations = [('op-A', -2), ('op-B', +3)]
    if reverse:
        operations.reverse()
    results = [server.apply(owner, op_id, 'STOCK_CHANGE', 7, {'delta': delta})
               for (op_id, delta), owner in zip(operations, (first_owner, second_owner))]
    return server, results[0], results[1]

# Competing devices both read version 7; only the first valid CAS can commit.
s, a, b = two_stock_order('owner-A', 'owner-A')
checks['two devices with same stale stock version: one wins, one conflicts, one movement'] = (
    isinstance(a, tuple) and b == 'PART_VERSION_CONFLICT' and s.quantity == 8 and len(s.movements) == 1
)
s2, b2, a2 = two_stock_order('owner-A', 'owner-A', reverse=True)
checks['reversed arrival order has the same single-winner property'] = (
    isinstance(b2, tuple) and a2 == 'PART_VERSION_CONFLICT' and s2.quantity == 13 and len(s2.movements) == 1
)

# Metadata-vs-stock race: either mutation may win, but stale second mutation must not overwrite it.
meta_first = Server()
r1 = meta_first.apply('owner-A', 'meta', 'PART_UPSERT', 7, {'name': 'new name'})
r2 = meta_first.apply('owner-A', 'stock', 'STOCK_CHANGE', 7, {'delta': -1})
stock_first = Server()
r3 = stock_first.apply('owner-A', 'stock', 'STOCK_CHANGE', 7, {'delta': -1})
r4 = stock_first.apply('owner-A', 'meta', 'PART_UPSERT', 7, {'name': 'new name'})
checks['metadata wins then stale stock conflicts without movement'] = isinstance(r1, tuple) and r2 == 'PART_VERSION_CONFLICT' and not meta_first.movements
checks['stock wins then stale metadata conflicts without overwriting stock'] = isinstance(r3, tuple) and r4 == 'PART_VERSION_CONFLICT' and stock_first.quantity == 9 and len(stock_first.movements) == 1

# Delete-vs-stock race, both possible arrival orders.
del_first = Server()
d1 = del_first.apply('owner-A', 'delete', 'PART_DELETE', 7, {})
d2 = del_first.apply('owner-A', 'stock', 'STOCK_CHANGE', 7, {'delta': -1})
stock_first_delete = Server()
d3 = stock_first_delete.apply('owner-A', 'stock', 'STOCK_CHANGE', 7, {'delta': -1})
d4 = stock_first_delete.apply('owner-A', 'delete', 'PART_DELETE', 7, {})
checks['delete wins: stale stock is rejected and no movement is created'] = isinstance(d1, tuple) and d2 == 'PART_NOT_FOUND' and del_first.deleted and not del_first.movements
checks['stock wins: stale delete is rejected and stock history is retained'] = isinstance(d3, tuple) and d4 == 'PART_VERSION_CONFLICT' and not stock_first_delete.deleted and len(stock_first_delete.movements) == 1

# Idempotency and owner isolation.
retry_server = Server()
first = retry_server.apply('owner-A', 'same-op', 'STOCK_CHANGE', 7, {'delta': -2})
retry = retry_server.apply('owner-A', 'same-op', 'STOCK_CHANGE', 7, {'delta': -2})
changed = retry_server.apply('owner-A', 'same-op', 'STOCK_CHANGE', 7, {'delta': -3})
checks['same operation ID and same payload returns cached result without duplicate movement'] = first == retry and len(retry_server.movements) == 1
checks['same operation ID with changed payload is rejected'] = changed == 'OPERATION_PAYLOAD_CONFLICT' and len(retry_server.movements) == 1
checks['stale operation retry cannot become a second stock mutation'] = retry_server.apply('owner-A', 'new-stale', 'STOCK_CHANGE', 7, {'delta': +5}) == 'PART_VERSION_CONFLICT' and len(retry_server.movements) == 1
checks['fresh operation at current version can proceed after conflict is reconciled'] = isinstance(retry_server.apply('owner-A', 'fresh-op', 'STOCK_CHANGE', 8, {'delta': +1}), tuple) and retry_server.quantity == 9
checks['another owner cannot mutate the first owner inventory'] = Server().apply('owner-B', 'foreign-op', 'STOCK_CHANGE', 7, {'delta': -1}) == 'OWNER_REJECTED'

# The Admin has both feeds: the personal feed may arrive first with no ownerName,
# while the global feed enriches the same immutable movement with the profile name.
def merge_feed_copy(existing, incoming):
    immutable_keys = ('sync_id', 'owner_id', 'code', 'part_name', 'type', 'amount', 'before_qty', 'after_qty', 'reason', 'machine_or_store', 'time')
    if any(existing.get(k) != incoming.get(k) for k in immutable_keys):
        return None
    merged = dict(existing)
    if incoming.get('owner_name'):
        merged['owner_name'] = incoming['owner_name']
    return merged

owner_copy = {'sync_id': 'movement-1', 'owner_id': 'ktv-uuid', 'code': 'P01', 'part_name': 'Part', 'type': 'XUẤT', 'amount': 1, 'before_qty': 2, 'after_qty': 1, 'reason': '', 'machine_or_store': '', 'time': 100, 'owner_name': ''}
global_copy = dict(owner_copy, owner_name='KTV A')
merged_owner_then_global = merge_feed_copy(owner_copy, global_copy)
merged_global_then_owner = merge_feed_copy(global_copy, owner_copy)
checks['Admin own movement arriving through both feeds is deduplicated and enriched'] = merged_owner_then_global is not None and merged_owner_then_global['owner_name'] == 'KTV A'
checks['feed arrival order does not erase an existing Admin owner display name'] = merged_global_then_owner is not None and merged_global_then_owner['owner_name'] == 'KTV A'
checks['same movement syncId with changed immutable quantity is rejected'] = merge_feed_copy(owner_copy, dict(global_copy, amount=2)) is None
checks['Admin global movement keeps KTV owner ID rather than Admin ID'] = merged_owner_then_global is not None and merged_owner_then_global['owner_id'] == 'ktv-uuid'

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP132_RESULT={len(checks)-len(failed)}/{len(checks)}')
if failed:
    raise SystemExit(1)
