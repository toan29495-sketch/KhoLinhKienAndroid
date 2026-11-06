from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT/'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
SENDER = (ROOT/'app/src/main/java/vn/kholinhkien/SyncOutboxSender.kt').read_text()
WORK = (ROOT/'app/src/main/java/vn/kholinhkien/SyncWork.kt').read_text()
checks = {
    'camera photos are created under the active owner directory': 'ownerPendingPhotoDirectory(context, profile.id)' in MAIN,
    'picked photos are copied with the active owner id': 'copyImageToPrivateStorage(context, uri, profile.id)' in MAIN,
    'new pending photo names use collision-resistant UUIDs': 'pending_${java.util.UUID.randomUUID()}.jpg' in MAIN,
    'owner directory validates UUID-shaped owner id': 'ownerPendingPhotoDirectory(context: android.content.Context, ownerId: String)' in MAIN and 'Mã chủ kho không hợp lệ để lưu ảnh' in MAIN,
    'photo picker helper requires owner id': 'fun copyImageToPrivateStorage(context: android.content.Context, uri: Uri, ownerId: String)' in MAIN,
    'photo picker helper writes only inside owner folder': 'ownerPendingPhotoDirectory(context, ownerId).apply { mkdirs() }' in MAIN,
    'photo preview rejects local paths outside app private storage': 'Ảnh cục bộ nằm ngoài vùng dữ liệu ứng dụng' in MAIN,
    'photo preview checks owner folder for scoped pending photos': 'canonicalSource.parentFile == File(pendingRoot, ownerId).canonicalFile' in MAIN,
    'sender receives app private files root explicitly': 'applicationContext.filesDir' in WORK and 'private val appFilesDirectory: File' in SENDER,
    'sender validates owner id before resolving photo directory': 'Mã chủ kho không hợp lệ khi tải ảnh' in SENDER,
    'sender accepts exact owner-scoped pending directory': 'canonicalFile.parentFile == ownerPendingDir' in SENDER,
    'sender retains narrow legacy direct-folder compatibility for existing Outbox': 'canonicalFile.parentFile == legacyPendingDir' in SENDER and 'canonicalFile.name.startsWith("pending_")' in SENDER,
    'sender rejects photo paths outside owner or legacy pending folders': 'require(isOwnerScoped || isLegacyPending)' in SENDER,
    'uploaded object path remains owner/entity/operation scoped': 'val remotePath = "$ownerId/$partSyncId/$operationId.$extension"' in SENDER,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'RESULT: {len(checks)-len(failed)}/{len(checks)} PASS')
if failed: raise SystemExit(1)
