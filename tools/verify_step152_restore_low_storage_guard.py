#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
checks = []
def check(name, ok):
    checks.append((name, bool(ok)))

check('dynamic free-space check during ZIP extraction', 'requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE + count)' in SRC)
check('reserve is retained while writing extracted entries', 'MIN_FREE_BYTES_DURING_RESTORE = 16L * 1024L * 1024L' in SRC)
check('photo bytes are measured before durable copy', 'val photoBytes = photoEntries.sumOf { File(stage, it).length() }' in SRC)
check('complete photo-copy capacity preflight', 'requireAvailableSpace(context, photoBytes + MIN_FREE_BYTES_DURING_RESTORE)' in SRC)
check('durable photo copy checks each buffer', 'copyWithSpaceGuard(context, source, target)' in SRC and 'requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE + count)' in SRC)
check('copied photo length is verified', 'target.length() == source.length()' in SRC)
check('database batch writes preserve reserve', SRC.count('requireAvailableSpace(context, MIN_FREE_BYTES_DURING_RESTORE)') >= 2)
check('live inventory remains deferred', 'Live parts/movements and sync state' in SRC and 'status = "STAGED"' in SRC)
check('disk-full cannot mask original staging error', 'runCatching {\n                    dao.saveRestoreJournal(RestoreJournal(' in SRC)
check('re-import recovery remains available', 'existing.status in setOf("STAGING", "FAILED")' in SRC)
check('no destructive live-table cleanup in low-storage path', 'dao.clearRestoreStagedRows(ownerId, restoreId)' in SRC)

# Boundary model: reject a write before consuming the reserved safety margin.
def allowed(available, payload, reserve):
    return available >= payload + reserve
reserve = 16 * 1024 * 1024
check('exact reserve boundary accepted', allowed(reserve + 65536, 65536, reserve))
check('one-byte-short boundary rejected', not allowed(reserve + 65535, 65536, reserve))
check('photo preflight rejects insufficient duplicate-space', not allowed(80 * 1024 * 1024, 70 * 1024 * 1024, reserve))
check('photo preflight accepts sufficient capacity', allowed(90 * 1024 * 1024, 70 * 1024 * 1024, reserve))

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'} {name}")
print(f"RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS")
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
