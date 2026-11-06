from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
inbox = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupRestoreInbox.kt').read_text()
downloader = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupCloudDownloader.kt').read_text()
ui = (ROOT / 'app/src/main/java/vn/kholinhkien/MainActivity.kt').read_text()
checks = {
    'private filesDir inbox': 'File(context.filesDir, "cloud_restore_inbox")' in inbox,
    'owner-scoped directory': 'File(root, ownerId)' in inbox,
    'candidate required': 'entry.isRestoreCandidate' in inbox,
    'auth owner checked': 'currentUserOrNull()?.id == ownerId' in inbox,
    'temporary partial file': '.${entry.backupId}.zip.partial' in inbox,
    'fsync archive': 'output.fd.sync()' in inbox,
    'exact size check': 'partial.length() == entry.archiveSizeBytes' in inbox,
    'cloud sha check': 'digest.equals(entry.sha256, true)' in inbox,
    're-run archive inspector': 'BackupArchiveInspector.verify(it, ownerId)' in inbox,
    'metadata includes identity': '.put("ownerId", ownerId)' in inbox and '.put("backupId", entry.backupId)' in inbox,
    'metadata includes checksum': '.put("sha256", entry.sha256.lowercase())' in inbox,
    'no Room or restore coordinator mutation': 'RestoreCloudCoordinator' not in inbox and 'InventoryDao' not in inbox,
    'download promoted into durable inbox': 'WeeklyBackupRestoreInbox(context).persist(ownerId, entry, verified)' in ui,
    'UI says not restored': 'Chưa khôi phục' in ui,
    'download remains authenticated': 'downloadAuthenticatedTo(entry.storagePath, partial)' in downloader,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'{sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
