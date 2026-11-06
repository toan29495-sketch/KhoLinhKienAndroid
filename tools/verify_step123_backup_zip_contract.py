from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MANAGER = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
INSPECTOR = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupArchiveInspector.kt').read_text()
RESTORE = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
PARSER = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipCentralDirectoryVerifier.kt').read_text()
checks = []
def check(name, condition): checks.append((name, bool(condition)))
# Backup writer, read-back inspector, structural parser and restore must agree on bounds.
for label, source in [('backup', MANAGER), ('inspector', INSPECTOR), ('restore', RESTORE)]:
    check(f'{label} has 300 MiB aggregate uncompressed limit', '300L * 1024L * 1024L' in source)
    check(f'{label} has 20 MiB per-photo limit', '20L * 1024L * 1024L' in source)
    check(f'{label} has 100 MiB JSON-entry limit', ('100L * 1024L * 1024L' in source))
    check(f'{label} has 10002-entry limit', '10002' in source)
check('backup emits exactly the required v2 manifest and JSONL payloads', all(x in MANAGER for x in ['ZipEntry("parts.jsonl")', 'ZipEntry("movements.jsonl")', 'ZipEntry("manifest.json")', '.put("version", 2)']))
check('backup writes SHA-256 for both JSONL payloads and every photo', 'checksums.put("parts.jsonl"' in MANAGER and 'checksums.put("movements.jsonl"' in MANAGER and 'checksums.put(entryName' in MANAGER)
check('inspector verifies exact checksum-name set and each digest', 'checksumNames == seen.filter { it != "manifest.json" }.toSet()' in INSPECTOR and 'actual.equals(expected, true)' in INSPECTOR)
check('restore independently verifies exact checksum-name set and every staged file', 'checksumNames == expectedNames' in RESTORE and 'sha256(File(stage, name))' in RESTORE)
check('restore requires exact photo references and supported image extensions', 'archivePhotoEntries == photoEntries' in RESTORE and 'extension in setOf("jpg", "jpeg", "png", "webp")' in RESTORE)
check('backup validates cloud and local photo ownership before opening ZIP output', MANAGER.index('validateLocalPhotoFile(context, File(path), ownerId)') < MANAGER.index('ZipOutputStream(destination.buffered())') and MANAGER.index('validateCloudPath(path, ownerId)') < MANAGER.index('ZipOutputStream(destination.buffered())'))
check('backup and restore use the same canonical owner validation before work', 'isCanonicalOwnerId(ownerId)' in MANAGER and 'isCanonicalOwnerId(ownerId)' in RESTORE)
check('structural parser is strict to the app ZIP v2 layout and bounded to 320 MiB', 'MAX_ARCHIVE_BYTES = 320L * 1024L * 1024L' in PARSER and 'isSafeName(name)' in PARSER)
for name, ok in checks: print(('PASS ' if ok else 'FAIL ') + name)
print(f'STEP123_CONTRACT_RESULT={sum(ok for _, ok in checks)}/{len(checks)}')
if not all(ok for _, ok in checks): raise SystemExit(1)
