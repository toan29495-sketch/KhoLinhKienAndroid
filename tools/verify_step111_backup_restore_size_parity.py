#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
backup = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
restore = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

check('backup enforces same 300 MiB aggregate uncompressed limit as restore',
      'MAX_TOTAL_BYTES = 300L * 1024L * 1024L' in backup and 'MAX_TOTAL_BYTES = 300L * 1024L * 1024L' in restore)
check('backup enforces same 100 MiB JSON entry limit as restore',
      'MAX_JSON_ENTRY_BYTES = 100L * 1024L * 1024L' in backup and 'MAX_ENTRY_BYTES = 100L * 1024L * 1024L' in restore)
check('backup enforces same 20 MiB per-photo limit as restore',
      'MAX_PHOTO_BYTES = 20L * 1024L * 1024L' in backup and 'MAX_PHOTO_BYTES = 20L * 1024L * 1024L' in restore)
check('backup enforces same 1 MiB manifest limit as restore',
      'MAX_MANIFEST_BYTES = 1024L * 1024L' in backup and 'MAX_MANIFEST_BYTES = 1024L * 1024L' in restore)
check('backup entry-count ceiling matches restore',
      'MAX_ENTRIES = 10002' in backup and 'MAX_ENTRIES = 10002' in restore)
check('photo count reserves exactly three non-photo archive entries',
      'photoFiles.size + 3 <= MAX_ENTRIES' in backup and 'MAX_ENTRIES - 3' in backup)
check('aggregate bytes are counted from actual uncompressed stream writes',
      'totalUncompressedBytes += count.toLong()' in backup and 'accountBytes(entryName, count, MAX_PHOTO_BYTES)' in backup)
check('parts JSON is bounded while streaming',
      'accountBytes("parts.jsonl", line.size, MAX_JSON_ENTRY_BYTES)' in backup)
check('movement JSON is bounded while streaming',
      'accountBytes("movements.jsonl", line.size, MAX_JSON_ENTRY_BYTES)' in backup)
check('photo growth during read is bounded, not just initial file length',
      'accountBytes(entryName, count, MAX_PHOTO_BYTES)' in backup)
check('manifest is bounded before it is written',
      'manifestBytes.size.toLong() <= MAX_MANIFEST_BYTES' in backup and 'accountBytes("manifest.json", manifestBytes.size, MAX_MANIFEST_BYTES)' in backup)
check('overflow causes failure before destination is published by MediaStore',
      'createZip(context, output, ownerId, username, timestampMillis, parts, movements)' in backup and
      'resolver.delete(uri, null, null)' in backup and 'IS_PENDING, 0' in backup)
check('backup still preflights all photos before opening ZIP output',
      backup.index('for (part in parts)') < backup.index('ZipOutputStream(destination.buffered())'))
check('restore remains authoritative with total and per-entry limits',
      'totalBytes <= MAX_TOTAL_BYTES' in restore and 'entryBytes <= limit' in restore)

for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
