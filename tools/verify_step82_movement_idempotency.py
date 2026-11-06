from pathlib import Path

root = Path(__file__).resolve().parents[1]
data = (root / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
pull = (root / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
checks = {
    'DAO looks up movement by globally unique syncId': 'movementBySyncId(syncId: String)' in data,
    'duplicate replay compares all immutable fields while excluding mutable ownerName': 'existing.copy(id = 0, ownerName = "") == movement.copy(id = 0, ownerName = "")' in data,
    'cross-owner or payload collision fails closed': 'Xung đột syncId lịch sử' in data,
    'insert IGNORE race is verified by rereading row': 'val raced = movementBySyncId(movement.syncId)' in data,
    'restore lock remains in same transaction': 'if (activeRestore(ownerId) != null) return false' in data,
    'pull mapper requires canonical UUID': 'Snapshot MOVEMENT có UUID không hợp lệ' in pull and 'canonicalId == cloudId.lowercase()' in pull,
    'owner-scoped mapper rejects mismatched owner; Admin-global path validates a supplied UUID': 'MovementOwnerResolver.resolve(ownerId, payloadOwnerId, allowForeignOwner)' in pull and 'payloadOwnerId == null || payloadOwnerId == expectedOwnerId' in (root / 'app/src/main/java/vn/kholinhkien/MovementOwnerResolver.kt').read_text(),
    'pull mapper validates supported movement type and arithmetic': 'type in setOf("NHẬP", "XUẤT", "KIỂM KÊ", "ĐẦU KỲ")' in pull and 'afterQty.toLong() == beforeQty.toLong() + amount.toLong()' in pull and 'beforeQty >= amount && afterQty.toLong() == beforeQty.toLong() - amount.toLong()' in pull,
    'pull mapper rejects missing or invalid timestamps instead of substituting now': 'Snapshot MOVEMENT thiếu thời gian' in pull and 'Snapshot MOVEMENT có thời gian không hợp lệ' in pull and 'System.currentTimeMillis()' not in pull[pull.index('private fun kotlinx.serialization.json.JsonObject.toLocalMovement'):],
    'movement syncId remains uniquely indexed': 'Index(value = ["syncId"], unique = true)' in data,
}

# Model immutable event equality while ownerName is a mutable profile label.
def apply(store, owner, event_id, payload):
    immutable = tuple(payload[:-1])
    incoming_name = payload[-1]
    existing = store.get(event_id)
    candidate = (owner, immutable, incoming_name)
    if existing is not None:
        if existing[0] != owner or existing[1] != immutable:
            raise ValueError('syncId collision')
        if incoming_name:
            existing = (owner, immutable, incoming_name)
            store[event_id] = existing
        return 'replay'
    store[event_id] = candidate
    return 'inserted'

store = {}
event = ('P-001', 'Máy bơm', 'NHẬP', 2, 3, 5, 'bổ sung', 'kho', 1000, 'KTV A')
checks['model: first movement event inserts'] = apply(store, 'owner-A', 'uuid-1', event) == 'inserted'
checks['model: exact duplicate event is idempotent'] = apply(store, 'owner-A', 'uuid-1', event) == 'replay'
checks['model: Admin feed may enrich ownerName without duplicating movement'] = apply(store, 'owner-A', 'uuid-1', event[:-1] + ('Tên KTV mới',)) == 'replay' and store['uuid-1'][2] == 'Tên KTV mới'
checks['model: later owner feed with blank name does not erase enrichment'] = apply(store, 'owner-A', 'uuid-1', event[:-1] + ('',)) == 'replay' and store['uuid-1'][2] == 'Tên KTV mới'
try:
    apply(store, 'owner-A', 'uuid-1', ('P-001', 'Máy bơm', 'XUẤT', 1, 3, 2, '', '', 1001, 'KTV A'))
    collision_rejected = False
except ValueError:
    collision_rejected = True
checks['model: same syncId with changed immutable payload is rejected'] = collision_rejected
try:
    apply(store, 'owner-B', 'uuid-1', event)
    owner_collision_rejected = False
except ValueError:
    owner_collision_rejected = True
checks['model: same syncId cannot cross owner boundary'] = owner_collision_rejected

for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP82_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
