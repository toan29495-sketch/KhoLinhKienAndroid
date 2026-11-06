from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
recovery = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt').read_text()
worker = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()
checks = {
    'recovery imports coroutine cancellation primitives': 'import kotlinx.coroutines.currentCoroutineContext' in recovery and 'import kotlinx.coroutines.ensureActive' in recovery,
    'journal iteration checks cancellation before processing each row': 'for (row in rows) {\n            recoveryCoroutineContext.ensureActive()\n            requireActiveOwner(ownerId)' in recovery,
    'archive hashing is suspendable and owner-scoped': 'private suspend fun sha256(file: File, ownerId: String)' in recovery,
    'hash loop checks cancellation for every buffer': 'while (true) {\n                    recoveryCoroutineContext.ensureActive()\n                    requireActiveOwner(ownerId)\n                    val count = input.read(buffer)' in recovery,
    'hash completion rechecks cancellation and owner': 'recoveryCoroutineContext.ensureActive()\n        requireActiveOwner(ownerId)\n        return digest.digest()' in recovery,
    'PREPARING deletion is fenced by cancellation and owner': 'recoveryCoroutineContext.ensureActive()\n                        requireActiveOwner(ownerId)\n                        check(archive.isFile)' in recovery,
    'invalid archive journal mutation is fenced': 'recoveryCoroutineContext.ensureActive()\n                        requireActiveOwner(ownerId)\n                        check(dao.markWeeklyBackupFailed' in recovery,
    'VERIFIED cleanup is fenced': 'recoveryCoroutineContext.ensureActive()\n                        requireActiveOwner(ownerId)\n                        check(archive.isFile)' in recovery,
    'worker still propagates CancellationException': 'catch (cancelled: CancellationException) {\n            throw cancelled' in worker,
    'READY/UPLOADING archives are still validated not rewritten': '"READY", "UPLOADING" -> {' in recovery and 'archive.delete()' not in recovery[recovery.index('"READY", "UPLOADING" -> {'):recovery.index('"VERIFIED" -> {')],
    'recovery remains local-only and documents no remote mutation': 'It never contacts Supabase' in recovery and 'never deletes remote objects' in recovery and 'SupabaseClientProvider' not in recovery,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()): raise SystemExit(1)
