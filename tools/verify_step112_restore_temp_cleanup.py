#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
checks = []
def check(name, condition): checks.append((name, bool(condition)))

check('restore cleans abandoned ZIP extraction folders before starting',
      source.index('cleanupStaleTemporaryStages(context, ownerId)') < source.index('val stage = File(context.cacheDir'))
check('temporary folder namespace is unique and app-owned',
      'private const val TEMP_PREFIX = "zip_restore_"' in source and 'val ownerTempPrefix = "$TEMP_PREFIX${ownerId.lowercase()}_"' in source and 'File(context.cacheDir, "$ownerTempPrefix${UUID.randomUUID()}")' in source)
check('preflight checks available cache storage before extraction',
      'StatFs(context.cacheDir.absolutePath).availableBytes' in source and 'MIN_FREE_BYTES_BEFORE_RESTORE' in source)
check('low-storage error tells user how to recover',
      'Hãy giải phóng bộ nhớ rồi thử lại' in source)
check('cleanup only targets the dedicated ZIP temp prefix',
      'it.isDirectory && isCanonicalTempDirectoryName(it.name, ownerPrefix)' in source and 'legacyPattern' not in source and 'isCanonicalTempDirectoryName(canonical.name, ownerPrefix)' in source)
check('cleanup refuses paths outside the canonical cache root',
      'canonical.parentFile == cacheRoot' in source and 'canonical.path == candidate.absoluteFile.path' in source and 'canonical.isDirectory' in source)
check('cleanup waits for abandoned directories to be at least 24 hours old',
      'STALE_TEMP_AGE_MILLIS = 24L * 60L * 60L * 1000L' in source and 'nowMillis - candidate.lastModified() >= STALE_TEMP_AGE_MILLIS' in source)
check('current restore temp directory is removed in finally',
      'finally {' in source and 'runCatching { stage.deleteRecursively() }' in source)
check('cleanup does not touch durable restore_staging or live Room tables',
      'restore_staging' not in source[source.index('internal fun cleanupStaleTemporaryStages'):source.index('private fun stableRestoreId')])
check('ZIP validation size and checksum limits remain intact',
      'MAX_TOTAL_BYTES = 300L * 1024L * 1024L' in source and 'MAX_PHOTO_BYTES = 20L * 1024L * 1024L' in source and 'sha256(File(stage, name))' in source)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
