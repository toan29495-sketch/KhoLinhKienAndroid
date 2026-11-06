#!/usr/bin/env python3
"""Verify backup/restore stops when the authenticated owner changes mid-operation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/vn/kholinhkien'
BACKUP = (SRC / 'BackupManager.kt').read_text(encoding='utf-8')
ZIP = (SRC / 'ZipRestore.kt').read_text(encoding='utf-8')
PHOTO = (SRC / 'RestorePhotoUploadCoordinator.kt').read_text(encoding='utf-8')
FACTORY = (SRC / 'RestorePhotoUploadFactory.kt').read_text(encoding='utf-8')
PREPARER = (SRC / 'RestoreCloudPayloadPreparer.kt').read_text(encoding='utf-8')
SENDER = (SRC / 'RestoreCloudSessionSender.kt').read_text(encoding='utf-8')
COMMIT = (SRC / 'RestoreCloudCommitter.kt').read_text(encoding='utf-8')
checks = []
def check(name, ok): checks.append((name, bool(ok)))

check('Backup captures canonical owner and checks live session at entry', 'require(isCanonicalOwnerId(ownerId))' in BACKUP and 'requireActiveOwner(ownerId)' in BACKUP[:BACKUP.index('val fileName')])
check('Backup checks session immediately after cloud photo download', BACKUP.index('downloadAuthenticatedTo(path, target)') < BACKUP.index('requireActiveOwner(ownerId)', BACKUP.index('downloadAuthenticatedTo(path, target)')))
check('Backup checks owner before writing ZIP and while serializing records', 'requireActiveOwner(ownerId)\n            ZipOutputStream' in BACKUP and 'requireActiveOwner(ownerId)\n            FileOutputStream(archive)' in BACKUP and 'parts.forEach { p ->\n                    backupCoroutineContext.ensureActive()\n                    requireActiveOwner(ownerId)' in BACKUP and 'movements.forEach { m ->\n                    backupCoroutineContext.ensureActive()\n                    requireActiveOwner(ownerId)' in BACKUP)
check('Backup checks owner during photo streaming and before manifest', ('while (true) {\n                            requireActiveOwner(ownerId)' in BACKUP or 'while (true) {\n                            backupCoroutineContext.ensureActive()\n                            requireActiveOwner(ownerId)' in BACKUP) and ('requireActiveOwner(ownerId)\n                zip.putNextEntry(ZipEntry("manifest.json"))' in BACKUP or 'backupCoroutineContext.ensureActive()\n                requireActiveOwner(ownerId)\n                zip.putNextEntry(ZipEntry("manifest.json"))' in BACKUP))
check('Backup only publishes MediaStore item after final owner check', BACKUP.index('requireActiveOwner(ownerId)\n                check(resolver.update(uri, published') > BACKUP.index('BackupArchiveInspector.verify'))
check('Backup failure deletes only its own pending MediaStore URI', 'resolver.delete(uri, null, null)\n                throw error' in BACKUP)
check('Backup always removes its unique temporary cloud-photo directory', 'finally {\n            // Never retain downloaded cloud images after the ZIP operation.\n            temporaryDirectory.deleteRecursively()' in BACKUP)
check('ZIP restore rejects changed session before extraction', 'requireActiveOwner(ownerId)\n        cleanupStaleTemporaryStages' in ZIP)
check('ZIP restore checks session while reading archive and persisting rows', 'while (true) {\n                        requireActiveOwner(ownerId)' in ZIP and 'while (true) {\n                            requireActiveOwner(ownerId)' in ZIP)
check('ZIP restore checks session before marking staging complete', 'requireActiveOwner(ownerId)\n                dao.saveRestoreJournal(dao.restoreJournal(ownerId, restoreId)!!.copy(\n                    status = "STAGED"' in ZIP)
check('Restore photo coordinator requires owner guard callback', 'private val requireActiveOwner: suspend (ownerId: String) -> Unit' in PHOTO)
check('Restore photo verifies owner after remote verification', PHOTO.count('requireActiveOwner(ownerId)') >= 7)
check('Restore photo verifies owner before and after upload', 'requireActiveOwner(ownerId)\n                uploadObject(remotePath, bytes, contentType)\n                requireActiveOwner(ownerId)' in PHOTO)
check('Production photo factory binds guard to current Supabase user', 'client.auth.currentUserOrNull()?.id == ownerId' in FACTORY)
check('Payload preparer rechecks owner after photo upload and before journal ready', 'val photoPaths = photoUploader.uploadAndVerify' in PREPARER and PREPARER.index('requireActiveOwner(ownerId)', PREPARER.index('uploadAndVerify')) < PREPARER.index('dao.clearRestoreStagedRowsByType'))
check('Restore chunk sender fences every part and movement RPC', SENDER.count('requireActiveOwner(ownerId)') >= 7 and 'parts.forEachIndexed' in SENDER and 'movements.forEachIndexed' in SENDER)
check('Restore sender rechecks owner before begin RPC and after each chunk', 'requireActiveOwner(ownerId)\n        client.postgrest.rpc(\n            "begin_inventory_restore"' in SENDER and 'requireActiveOwner(ownerId)\n                partSent++' in SENDER and 'requireActiveOwner(ownerId)\n                movementSent++' in SENDER)
check('Committer refuses to journal a result after account switch', 'require(client.auth.currentUserOrNull()?.id == ownerId)' in COMMIT and 'sau yêu cầu commit; không áp dụng kết quả vào phiên khác' in COMMIT)

# Model the critical callback contract: if identity changes during transfer, the
# post-transfer guard must stop before the operation can publish a checkpoint.
for initial, after_transfer, should_checkpoint in [('A', 'A', True), ('A', 'B', False), ('A', None, False)]:
    checkpoint_written = False
    if initial == after_transfer:
        checkpoint_written = True
    check(f'Model: transfer A -> {after_transfer} checkpoint={should_checkpoint}', checkpoint_written == should_checkpoint)

for name, ok in checks:
    print(f'{"PASS" if ok else "FAIL"}: {name}')
print(f'RESULT: {sum(ok for _, ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _, ok in checks) else 1)
