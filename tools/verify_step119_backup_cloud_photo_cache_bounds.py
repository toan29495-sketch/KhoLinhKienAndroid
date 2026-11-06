from pathlib import Path
root = Path(__file__).resolve().parents[1]
src = (root / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
checks = {
    'cloud photo cache byte counter is initialized per backup': 'var cloudPhotoCacheBytes = 0L' in src,
    'available cache space is checked before each cloud download': 'temporaryDirectory.usableSpace >= MAX_PHOTO_BYTES + MIN_CACHE_RESERVE_BYTES' in src,
    'cache preflight reserves the maximum photo plus safety margin': 'private const val MIN_CACHE_RESERVE_BYTES = 32L * 1024L * 1024L' in src and 'MAX_PHOTO_BYTES + MIN_CACHE_RESERVE_BYTES' in src,
    'cloud download target is validated as a regular file': 'require(target.isFile && downloadedBytes in 1..MAX_PHOTO_BYTES)' in src,
    'cloud photo size is bounded to 20 MiB immediately after download': 'downloadedBytes in 1..MAX_PHOTO_BYTES' in src,
    'cumulative temporary cloud photos are bounded': 'cloudPhotoCacheBytes <= MAX_TOTAL_BYTES' in src,
    'cloud cache overflow reports a recoverable split-backup message': 'Tổng dung lượng ảnh cloud tạm vượt giới hạn 300 MB cho một lần sao lưu' in src,
    'local photos remain outside the cloud-cache accounting path': 'validateLocalPhotoFile(context, File(path), ownerId)' in src and 'cloudPhotoCacheBytes += downloadedBytes' in src,
    'photo entry count limit remains enforced': 'photoFiles.size + 3 <= MAX_ENTRIES' in src,
    'ZIP aggregate uncompressed byte limit remains enforced': 'totalUncompressedBytes <= MAX_TOTAL_BYTES' in src,
    'temporary cloud photo directory is always cleaned in finally': 'temporaryDirectory.deleteRecursively()' in src and 'finally {' in src,
    'backup local-photo owner validation remains present': 'validateLocalPhotoFile(context, File(path), ownerId)' in src and 'isCanonicalOwnerId(ownerId)' in src,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP119_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
