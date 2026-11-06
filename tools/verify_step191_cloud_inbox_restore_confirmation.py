from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
inbox = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupRestoreInbox.kt').read_text()
zip_restore = (ROOT / 'app/src/main/java/vn/kholinhkien/ZipRestore.kt').read_text()
coordinator = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt').read_text()
ui = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
checks = {
    'inbox discovery method': 'fun listVerified(ownerId: String)' in inbox,
    'discovery checks active owner before read': 'currentUserOrNull()?.id == ownerId' in inbox,
    'discovery validates owner metadata': 'json.getString("ownerId") == ownerId' in inbox,
    'discovery validates canonical backup id': 'UUID.fromString(id).toString() == id' in inbox,
    'discovery validates exact storage path': 'json.getString("storagePath") == "$ownerId/$id.zip"' in inbox,
    'discovery validates archive size and digest': 'archive.length() == size' in inbox and 'sha256(archive).equals(sha, true)' in inbox,
    'discovery rechecks archive structure': 'BackupArchiveInspector.verify(it, ownerId)' in inbox,
    'discovery checks owner after scan': 'Tài khoản đã thay đổi trong khi đọc backup staging' in inbox,
    'ZipRestore accepts only owner inbox file': 'archive.canonicalFile' in zip_restore and 'canonical.parentFile == inboxRoot' in zip_restore,
    'ZipRestore retains URI import compatibility': 'restore(context: Context, uri: Uri' in zip_restore,
    'coordinator validates inbox item before staging': 'listVerified(ownerId).firstOrNull' in coordinator,
    'coordinator blocks pending outbox': 'requireNoPendingOutbox(ownerId)' in coordinator,
    'UI can rediscover saved inbox': 'WeeklyBackupRestoreInbox(context).listVerified(ownerId)' in ui,
    'UI has explicit destructive confirmation': 'Xác nhận khôi phục backup Cloud' in ui and 'Xác nhận thay thế kho' in ui,
    'UI warns that current inventory is replaced': 'thay thế kho + lịch sử hiện tại' in ui,
    'UI restore callback checks current auth owner': 'currentUserOrNull()?.id == profile.id' in ui,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'{sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()): raise SystemExit(1)
