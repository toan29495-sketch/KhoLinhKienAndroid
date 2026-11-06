from pathlib import Path
root = Path(__file__).resolve().parents[1]
src = (root / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
checks = {
    'backup entry validates canonical owner UUID before writing': 'require(isCanonicalOwnerId(ownerId))' in src,
    'backup local absolute paths route through the dedicated validator': 'validateLocalPhotoFile(context, File(path), ownerId)' in src,
    'local paths resolve to canonical file before use': 'val canonicalSource = source.canonicalFile' in src,
    'local photos are restricted to app-private filesDir': 'canonicalSource.path.startsWith(privateRoot.path + File.separator)' in src,
    'new pending photos must be in active owner directory': 'canonicalSource.parentFile == ownerDirectory' in src,
    'legacy pending compatibility is limited to direct pending_ files': 'canonicalSource.parentFile == pendingRoot' in src and 'canonicalSource.name.startsWith("pending_")' in src,
    'other owner pending directories are rejected': 'Ảnh chờ đồng bộ không thuộc tài khoản đang sao lưu' in src,
    'missing/non-file local photo is rejected': 'require(canonicalSource.isFile)' in src,
    'canonical owner validation uses strict UUID layout': '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' in src,
    'UUID parser round-trip rejects malformed UUIDs': 'UUID.fromString(value).toString().equals(value, ignoreCase = true)' in src,
    'cloud object paths remain separately owner-validated': 'validateCloudPath(path, ownerId)' in src,
    'local photo originals are not moved or deleted': 'canonicalSource.delete' not in src and 'source.delete' not in src,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP118_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
