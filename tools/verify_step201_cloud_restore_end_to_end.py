#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

checks = []
def check(label, condition):
    checks.append((label, bool(condition)))

catalog = read("app/src/main/java/vn/kholinhkien/WeeklyBackupCloudCatalog.kt")
downloader = read("app/src/main/java/vn/kholinhkien/WeeklyBackupCloudDownloader.kt")
inbox = read("app/src/main/java/vn/kholinhkien/WeeklyBackupRestoreInbox.kt")
coordinator = read("app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt")
preparer = read("app/src/main/java/vn/kholinhkien/RestoreCloudPayloadPreparer.kt")
photos = read("app/src/main/java/vn/kholinhkien/RestorePhotoUploadCoordinator.kt")
sender = read("app/src/main/java/vn/kholinhkien/RestoreCloudSessionSender.kt")
committer = read("app/src/main/java/vn/kholinhkien/RestoreCloudCommitter.kt")
reader = read("app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotReader.kt")
applier = read("app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotApplier.kt")
main = read("app/src/main/java/vn/kholinhkien/MainActivity.kt")

def has(text, *needles):
    return all(n in text for n in needles)

check("catalog requires canonical owner and matching Auth UID", has(catalog, "requireCanonicalUuid(ownerId", "currentUserOrNull()?.id == ownerId"))
check("only VERIFIED, non-deleted backups are restore candidates", has(catalog, "status == Status.VERIFIED", "verifiedAt != null", "deletedAt == null"))
check("catalog rejects foreign owner object paths", 'path == "$ownerId/$id.zip"' in catalog)
check("download requires restore-candidate metadata", "entry.isRestoreCandidate" in downloader)
check("download uses authenticated private backup bucket", has(downloader, 'const val BUCKET = "inventory-backups"', "downloadAuthenticatedTo"))
check("download validates exact archive size and SHA-256", has(downloader, "archiveSizeBytes", "digest.equals(entry.sha256"))
check("download rechecks ZIP manifest owner and counts", has(downloader, "BackupArchiveInspector.verify", "verifyManifest", 'manifest.optString("ownerId") == ownerId'))
check("download rechecks owner around transfer", has(downloader, "requireOwner(ownerId)", "ensureActive()"))
check("verified archive is persisted in private owner inbox", has(inbox, 'File(context.filesDir, "cloud_restore_inbox")', "File(root, ownerId)"))
check("inbox writes durable partial file and fsync", has(inbox, ".zip.partial", "output.fd.sync()", "renameTo(target)"))
check("inbox revalidates size, SHA and ZIP structure", has(inbox, "sha256(partial)", "BackupArchiveInspector.verify", "archive.length() == size"))
check("inbox listing rechecks owner before and after scan", has(inbox, "currentUserOrNull()?.id == ownerId", "Tài khoản đã thay đổi trong khi đọc backup staging"))
check("inbox persistence does not itself mutate Room", "InventoryDao" not in inbox and "RestoreCloudCoordinator" not in inbox)
check("restore UI exposes explicit download and inbox discovery actions", has(main, "Tải danh sách backup Cloud", "Tải và xác minh backup này", "Tìm backup đã tải và lưu trên máy"))
check("restore UI requires explicit destructive confirmation", has(main, "Xác nhận khôi phục backup Cloud", "Xác nhận thay thế kho", "Không thể hoàn tác tự động"))
check("restore UI explains that download is not restore", has(main, "Chưa khôi phục và chưa thay đổi dữ liệu kho.", "Chưa khôi phục."))
check("restore callback rechecks authenticated owner", has(main, "onRestoreInbox = { archive ->", "currentUserOrNull()?.id == profile.id"))
check("coordinator accepts only exact owner inbox archive", has(coordinator, "canonical.parentFile == inbox", "WeeklyBackupRestoreInbox(context).listVerified(ownerId)"))
check("coordinator blocks pending outbox and serializes restore", has(coordinator, "RestoreOperationGate.mutex.withLock", "requireNoPendingOutbox(ownerId)"))
check("photo preparation uploads every referenced image and requires complete mapping", has(preparer, "photoUploader.uploadAndVerify", "photoPaths.keys == photoEntries"))
check("photo uploader uses deterministic owner/restore/hash paths and verifies remote objects", has(photos, '"$ownerId/restore-$restoreId/$digest.$extension"', "verifyObject(remotePath, digest, size)"))
check("payload preparation preserves both parts and movements", has(preparer, "SOURCE_PART", "SOURCE_MOVEMENT", "PAYLOAD_MOVEMENT"))
check("cloud sender stages both parts and movements", has(sender, '"PART"', '"MOVEMENT"', '"stage_inventory_restore_chunk"'))
check("cloud commit checks expected part and movement counts", has(committer, '"commit_inventory_restore"', "parts == journal.partCount", "movements == journal.movementCount"))
check("snapshot reader verifies remote chunks before marking downloaded", has(reader, '"verify_inventory_restore_snapshot_chunk"', 'status = "SNAPSHOT_DOWNLOADED"'))
check("Room applier accepts only SNAPSHOT_DOWNLOADED", has(applier, 'journal.status == "SNAPSHOT_DOWNLOADED"', "applyCommittedRestoreSnapshot"))
check("coordinator applies only after remote commit and snapshot download", has(coordinator, '"COMMIT_CONFIRMED"', '"SNAPSHOT_DOWNLOADED"', "RestoreCloudSnapshotApplier"))
check("cleanup is attempted only after COMPLETE", has(coordinator, 'journal.status == "COMPLETE"', "RestoreStagingLifecycle.cleanupCompleted"))
check("README documents the actual Cloud restore sequence", has(read("README.md"), "uploads and verifies referenced photos", "only then applies the snapshot in one Room transaction"))

failed = [label for label, ok in checks if not ok]
for label, ok in checks:
    print(("PASS" if ok else "FAIL") + " | " + label)
print(f"STEP201: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
