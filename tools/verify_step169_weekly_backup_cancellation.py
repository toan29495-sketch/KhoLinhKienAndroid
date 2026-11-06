from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
backup = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
guard = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupGuardedOutputStream.kt').read_text()
worker = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()

checks = {
    'weekly ZIP guard accepts a cancellation probe': 'private val checkActive: () -> Unit = {}' in guard,
    'guard checks cancellation before capacity/write': 'private fun ensureCapacity(nextBytes: Long) {\n        checkActive()' in guard,
    'weekly preparation supplies coroutine cancellation probe': 'checkActive = { backupCoroutineContext.ensureActive() }' in backup,
    'coroutine context captured in weekly preparation': 'val backupCoroutineContext = currentCoroutineContext()' in backup,
    'archive SHA-256 loop checks cancellation': 'backupCoroutineContext.ensureActive()\n                    requireActiveOwner(ownerId)\n                    val count = input.read(buffer)' in backup,
    'photo discovery loop checks cancellation': 'for (part in parts) {\n                backupCoroutineContext.ensureActive()' in backup,
    'parts serialization loop checks cancellation': 'parts.forEach { p ->\n                    backupCoroutineContext.ensureActive()' in backup,
    'movement serialization loop checks cancellation': 'movements.forEach { m ->\n                    backupCoroutineContext.ensureActive()' in backup,
    'photo ZIP copy loop checks cancellation': 'while (true) {\n                            backupCoroutineContext.ensureActive()' in backup,
    'temporary cloud photo cache is still cleaned in finally': 'temporaryDirectory.deleteRecursively()' in backup,
    'PREPARING archive cleanup still occurs on cancellation/error': 'catch (error: Throwable)' in backup and 'archive.delete()\n            throw error' in backup,
    'READY/UPLOADING archives remain outside rebuild try path': backup.index('if (journal.state == "READY" || journal.state == "UPLOADING")') < backup.index('try {\n            val backupCoroutineContext'),
    'worker continues to propagate coroutine cancellation': 'catch (cancelled: CancellationException) {\n            throw cancelled' in worker,
    'no schema or scheduler activation added': 'WeeklyBackupWorkScheduler.schedule(' not in worker,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
