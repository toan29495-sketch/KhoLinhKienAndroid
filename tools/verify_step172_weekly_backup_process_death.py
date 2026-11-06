#!/usr/bin/env python3
"""Step172 contracts for process-death-safe weekly backup upload/retry."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
UP = (ROOT / 'app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt').read_text()
REC = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
WORK = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
DAO = (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text()
checks = {
    'durable UPLOADING journal is committed before remote registration': UP.index('dao.markWeeklyBackupUploading') < UP.index('beginRegistration(prepared, backupId, journal.archiveSizeBytes)'),
    'retry resumes exact owner/backup path from durable journal': 'journal.archivePath != prepared.archive.canonicalPath' in UP and 'val expectedPath = "${prepared.ownerId}/$backupId.zip"' in UP,
    'READY and UPLOADING journal states are both accepted for resume': 'journal.state !in setOf("READY", "UPLOADING")' in UP,
    'recovery preserves valid READY/UPLOADING archive bytes': '"READY", "UPLOADING" -> {' in REC and 'resumable++ // Preserve exact path + bytes' in REC,
    'recovery marks lost or corrupt staged archive terminal rather than rebuilding under same identity': 'Archive staging có kích thước hoặc SHA-256 không khớp journal' in REC and 'WeeklyBackupStorageException' in REC and 'dao.markWeeklyBackupFailed(ownerId, row.backupId' in REC,
    'process retry uses existing active backupId before generating a new UUID': 'WeeklyBackupLifecycle.backupIdForAttempt(' in WORK and 'return active.backupId' in (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupLifecycle.kt').read_text(),
    'worker rethrows cancellation so WorkManager interruption is not treated as success': 'catch (cancelled: CancellationException) {\n            throw cancelled' in WORK,
    'upload journal state transition permits idempotent UPLOADING retry': "state IN ('READY','UPLOADING')" in DAO,
    'verification temp is always disposable and staged archive is not removed in finally': 'finally {\n            // The verification copy is disposable' in UP and 'verifyFile.delete()' in UP and 'prepared.archive.delete()' not in UP[UP.index('finally {\n            // The verification copy is disposable'):],
    'archive digest loop checks cancellation and owner on every chunk': 'private suspend fun sha256File(file: File, ownerId: String)' in UP and 'coroutineContext.ensureActive()\n                    requireActiveOwner(ownerId)\n                    val count = input.read(buffer)' in UP,
    'digest finalization is fenced before returning': 'coroutineContext.ensureActive()\n        requireActiveOwner(ownerId)\n        return digest.digest()' in UP,
    'cancellation checks protect completion RPC and local VERIFIED transition': 'uploadCoroutineContext.ensureActive()\n                requireActiveOwner(prepared.ownerId)\n                val completedRaw' in UP and 'uploadCoroutineContext.ensureActive()\n            requireActiveOwner(prepared.ownerId)\n            require(dao.markWeeklyBackupVerified' in UP,
    'Room journal remains owner-scoped for lookup and recovery': 'WHERE ownerId = :ownerId AND backupId = :backupId' in DAO and 'WHERE ownerId = :ownerId ORDER BY createdAt DESC' in DAO,
    'worker does not create a replacement identity while active journal exists': 'val active = dao.activeWeeklyBackup(ownerId)' in WORK and 'WeeklyBackupLifecycle.backupIdForAttempt(' in WORK and 'return active.backupId' in (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupLifecycle.kt').read_text(),
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()): raise SystemExit(1)
