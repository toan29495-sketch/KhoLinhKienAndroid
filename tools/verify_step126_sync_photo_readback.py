from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SENDER = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
INTEGRITY = (ROOT / 'app/src/main/java/vn/kholinhkien/PhotoIntegrity.kt').read_text()
RESTORE = (ROOT / 'app/src/main/java/vn/kholinhkien/RestorePhotoUploadCoordinator.kt').read_text()
STORAGE = (ROOT / 'app/src/main/java/vn/kholinhkien/SupabaseRestorePhotoStorage.kt').read_text()
UI = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()

checks = {
    'normal photo upload keeps owner-scoped deterministic object path': '"$ownerId/$partSyncId/$operationId.$extension"' in SENDER,
    'normal photo upload retains 20 MiB source limit': 'file.length() in 1..20L * 1024L * 1024L' in SENDER,
    'upload bytes are read once before hashing and upload': 'val photoBytes = file.readBytes()' in SENDER and 'upload(remotePath, photoBytes)' in SENDER,
    'post-read size is bounded before upload': 'photoBytes.size in 1..20 * 1024 * 1024' in SENDER,
    'SHA-256 is computed from the exact upload bytes': 'PhotoIntegrity.sha256(photoBytes)' in SENDER,
    'storage object is read back through authenticated session': 'downloadAuthenticated(remotePath)' in SENDER,
    'owner is rechecked after upload and before readback': SENDER.count('requireActiveOwner(ownerId)') >= 4,
    'owner is rechecked after readback before accepting result': SENDER.index('downloadAuthenticated(remotePath)') < SENDER.index('requireActiveOwner(ownerId)', SENDER.index('downloadAuthenticated(remotePath)')),
    'readback size and SHA-256 must both match': 'PhotoIntegrity.matches(photoBytes.size, expectedDigest, storedBytes)' in SENDER,
    'cloud payload is constructed only after readback validation': SENDER.index('PhotoIntegrity.matches') < SENDER.index('val cloudPayload = buildJsonObject'),
    'failed verification cannot return remote path for database RPC': SENDER.index('check(PhotoIntegrity.matches') < SENDER.index('return PhotoUploadResult(cloudPayload, localPath, remotePath)'),
    'local pending photo deletion remains after successful RPC only': SENDER.index('completePhotoOutbox(') < SENDER.index('File(path).delete()'),
    'restore uploader retains readback verification': 'check(verifyObject(remotePath, digest, size))' in RESTORE,
    'restore storage verification checks byte count and SHA-256': 'downloaded.size.toLong() == expectedBytes' in STORAGE and 'sha256(downloaded).equals(expectedSha256, ignoreCase = true)' in STORAGE,
    'cloud viewer uses RAM-only authenticated download': '.downloadAuthenticated(photoPath)' in UI and 'downloadAuthenticatedTo' not in UI and 'part_photo_cache' not in UI,
    'logout no longer needs photo-cache cleanup': 'clearAccountPhotoCache' not in UI and 'clearPartPhotoCache' not in UI,
    'photo preview buffer is bounded and zeroed': 'MAX_PART_PHOTO_BYTES = 20 * 1024 * 1024' in UI and 'imageBytes.fill(0)' in UI,
    'photo preview does not persist cloud bytes to filesDir': 'downloadAuthenticatedTo' not in UI and 'part_photo_cache' not in UI,
    'cloud photo preview creates no disk cache path': 'File(context.cacheDir, "part_photo_cache")' not in UI,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'STEP126_RESULT={sum(checks.values())}/{len(checks)}')
if not all(checks.values()):
    raise SystemExit(1)
