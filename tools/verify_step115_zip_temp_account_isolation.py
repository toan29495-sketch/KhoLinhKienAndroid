#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ZIP = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
checks = []
def check(name, condition): checks.append((name, bool(condition)))

check('restore requires a canonical UUID owner before creating temp workspace',
      'require(isCanonicalOwnerId(ownerId))' in ZIP and
      ZIP.index('require(isCanonicalOwnerId(ownerId))') < ZIP.index('val stage = File(context.cacheDir'))
check('new ZIP extraction workspace includes authenticated owner namespace',
      'val ownerTempPrefix = "$TEMP_PREFIX${ownerId.lowercase()}_"' in ZIP and
      'File(context.cacheDir, "$ownerTempPrefix${UUID.randomUUID()}")' in ZIP)
check('stale cleanup receives the active owner explicitly',
      'cleanupStaleTemporaryStages(context, ownerId)' in ZIP)
check('cleanup selects only current-owner workspaces (tightened in Step116)',
      'it.isDirectory && isCanonicalTempDirectoryName(it.name, ownerPrefix)' in ZIP and 'legacyPattern' not in ZIP)
check('cleanup validates canonical direct-child location and owner before deletion',
      'canonical.parentFile == cacheRoot' in ZIP and 'canonical.path == candidate.absoluteFile.path' in ZIP and 'canonical.isDirectory' in ZIP)
check('cleanup preserves another KTV owner-prefixed workspace',
      'isCanonicalTempDirectoryName(canonical.name, ownerPrefix)' in ZIP and
      'canonical.parentFile == cacheRoot && canonical.isDirectory' in ZIP and
      'legacyPattern' not in ZIP)
check('legacy unscoped folders are never reclaimed by an arbitrary KTV',
      'Legacy folders have no trustworthy owner marker' in ZIP and
      'legacyPattern' not in ZIP and 'nowMillis - candidate.lastModified() >= STALE_TEMP_AGE_MILLIS' in ZIP)
check('temporary extraction is still deleted immediately after normal completion/failure',
      'finally {' in ZIP and 'runCatching { stage.deleteRecursively() }' in ZIP)
check('durable restore journal and staging namespace are not touched by temp cleanup',
      'restore_staging' not in ZIP[ZIP.index('internal fun cleanupStaleTemporaryStages'):ZIP.index('private fun stableRestoreId')])
check('ZIP checksum and archive size limits remain unchanged',
      'MAX_TOTAL_BYTES = 300L * 1024L * 1024L' in ZIP and
      'MAX_PHOTO_BYTES = 20L * 1024L * 1024L' in ZIP and
      'sha256(File(stage, name))' in ZIP)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
