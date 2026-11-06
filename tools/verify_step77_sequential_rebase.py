from pathlib import Path

source = Path('app/src/main/java/vn/kholinhkien/Data.kt').read_text(encoding='utf-8')
block_start = source.index('suspend fun recordServerVersionAndRebase')
block_end = source.index('\n    }', block_start) + 6
block = source[block_start:block_end]
checks = {
    'confirmed server version must be positive': 'require(version > 0L)' in block,
    'local part version update must affect exactly one owner-scoped row': 'check(updatePartServerVersion(ownerId, entityId, version) == 1)' in block,
    'queued operations are read in deterministic FIFO order': 'queuedPartOperations(ownerId, entityId).forEach' in block,
    'first queued operation uses confirmed server version': 'var nextExpectedVersion = version' in block and 'json.put("expected_server_version", nextExpectedVersion)' in block,
    'each later queued operation advances expected version': 'nextExpectedVersion = Math.addExact(nextExpectedVersion, 1L)' in block,
    'payload rewrite must succeed or transaction fails': 'check(updateQueuedPayload(row.operationId, json.toString()) == 1)' in block,
    'known server version is not overwritten with projected future version': 'updatePartServerVersion(ownerId, entityId, nextExpectedVersion)' not in block,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STATIC_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)

# Model the FIFO version chain and overflow boundary independently of Android runtime.
def assign(base, count):
    values = []
    current = base
    for _ in range(count):
        values.append(current)
        if current == 2**63 - 1:
            raise OverflowError
        current += 1
    return values
assert assign(8, 3) == [8, 9, 10]
assert assign(1, 1) == [1]
try:
    assign(2**63 - 1, 2)
except OverflowError:
    pass
else:
    raise AssertionError('expected version overflow must fail closed')
print('PASS FIFO version-chain model and overflow fail-closed')
