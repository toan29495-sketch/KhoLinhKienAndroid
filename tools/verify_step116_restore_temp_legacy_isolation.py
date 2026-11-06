#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ZIP = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
checks = []
def check(name, condition): checks.append((name, bool(condition)))

check('owner UUID is validated at restore entry before cleanup or filesystem work',
      ZIP.index('require(isCanonicalOwnerId(ownerId))') < ZIP.index('cleanupStaleTemporaryStages(context, ownerId)') < ZIP.index('StatFs('))
check('owner UUID validator requires canonical 8-4-4-4-12 hexadecimal layout',
      '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' in ZIP)
check('owner UUID is parsed and canonical round-trip checked',
      'UUID.fromString(value).toString().equals(value, ignoreCase = true)' in ZIP)
check('invalid owner IDs cannot trigger stale-temp cleanup',
      'require(isCanonicalOwnerId(ownerId))' in ZIP[ZIP.index('internal fun cleanupStaleTemporaryStages'):ZIP.index('private fun isCanonicalOwnerId')])
check('cleanup enumerates only current-owner namespace',
      'it.isDirectory && isCanonicalTempDirectoryName(it.name, ownerPrefix)' in ZIP and 'legacyPattern' not in ZIP)
check('legacy unscoped folders are explicitly excluded because owner cannot be proven',
      'Legacy folders have no trustworthy owner marker' in ZIP)
check('deletion still requires canonical direct child and exact owner prefix',
      'canonical.path == candidate.absoluteFile.path' in ZIP and 'canonical.parentFile == cacheRoot && canonical.isDirectory' in ZIP and 'isCanonicalTempDirectoryName(canonical.name, ownerPrefix)' in ZIP)
check('stale age threshold remains 24 hours',
      'STALE_TEMP_AGE_MILLIS = 24L * 60L * 60L * 1000L' in ZIP)
check('immediate finally cleanup remains present',
      'finally {' in ZIP and 'runCatching { stage.deleteRecursively() }' in ZIP)
check('archive bounds and SHA-256 validation remain present',
      'MAX_TOTAL_BYTES = 300L * 1024L * 1024L' in ZIP and 'sha256(File(stage, name))' in ZIP)
check('durable owner-scoped restore staging is not part of cache cleanup',
      'restore_staging' not in ZIP[ZIP.index('internal fun cleanupStaleTemporaryStages'):ZIP.index('private fun isCanonicalOwnerId')])

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
