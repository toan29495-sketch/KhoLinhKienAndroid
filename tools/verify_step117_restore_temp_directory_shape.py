#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZIP = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
CLEANUP = ZIP[ZIP.index('internal fun cleanupStaleTemporaryStages'):ZIP.index('private fun isCanonicalTempDirectoryName')]
HELPER = ZIP[ZIP.index('private fun isCanonicalTempDirectoryName'):ZIP.index('private fun isCanonicalOwnerId')]
checks = []
def check(name, condition): checks.append((name, bool(condition)))

check('cleanup enumerates directories only', 'it.isDirectory && isCanonicalTempDirectoryName(it.name, ownerPrefix)' in CLEANUP)
check('temporary directory suffix must be a canonical UUID', 'UUID.fromString(suffix).toString() == suffix' in HELPER)
check('only the active owner prefix is accepted', 'name.startsWith(ownerPrefix)' in HELPER)
check('candidate canonical path must equal its listed absolute path', 'canonical.path == candidate.absoluteFile.path' in CLEANUP)
check('candidate must remain a direct child of canonical cache root', 'canonical.parentFile == cacheRoot' in CLEANUP)
check('candidate must still be a directory at deletion time', 'canonical.isDirectory' in CLEANUP)
check('directory name is revalidated immediately before deletion', 'isCanonicalTempDirectoryName(canonical.name, ownerPrefix)' in CLEANUP)
check('24-hour stale threshold remains enforced', 'nowMillis - candidate.lastModified() >= STALE_TEMP_AGE_MILLIS' in CLEANUP)
check('recursive deletion occurs only after all safety predicates', CLEANUP.index('if (isSafeDirectory &&') < CLEANUP.index('candidate.deleteRecursively()'))
check('legacy unscoped cache names remain excluded', 'Legacy folders have no trustworthy owner marker' in CLEANUP)
check('durable restore staging is not part of cache cleanup', 'restore_staging' not in CLEANUP)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
