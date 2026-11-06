from pathlib import Path

data = Path('app/src/main/java/vn/kholinhkien/Data.kt').read_text(encoding='utf-8')
sender = Path('app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text(encoding='utf-8')
start = data.index('suspend fun completeDeleteOutbox')
end = data.index('\n    }', start) + 6
block = data[start:end]
checks = {
    'delete acknowledgement is a dedicated DAO transaction': '@Transaction' in data[data.rfind('@Transaction', 0, start):start] and 'suspend fun completeDeleteOutbox' in block,
    'only the exact owner/entity/PART_DELETE IN_FLIGHT row is accepted': 'row.ownerId == ownerId && row.entityId == entityId && row.operationType == "PART_DELETE" && row.status == "IN_FLIGHT"' in block,
    'local Part must remain absent after locally queued deletion': 'partBySyncId(ownerId, entityId) == null' in block,
    'other unresolved mutations for the deleted entity block acknowledgement': 'countOtherUnresolvedPartOperations(ownerId, entityId, operationId) == 0' in block,
    'delete completion does not call server-version rebase': 'recordServerVersionAndRebase' not in block,
    'delete completion marks only its own outbox row synced': 'markOutboxSynced(operationId) == 1' in block,
    'sender routes PART_DELETE through dedicated completion': 'claimed.operationType == "PART_DELETE" -> dao.completeDeleteOutbox' in sender,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STATIC_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)

# Small state model: deleted local row is absent; ack may complete only when no
# other unresolved mutation exists. Any unexpected dependent row fails closed.
def can_ack(local_part_exists: bool, other_unresolved: int) -> bool:
    return not local_part_exists and other_unresolved == 0
assert can_ack(False, 0)
assert not can_ack(True, 0)
assert not can_ack(False, 1)
print('PASS delete-ack fail-closed state model')
