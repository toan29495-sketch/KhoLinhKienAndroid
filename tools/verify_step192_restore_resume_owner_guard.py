from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt').read_text()
checks = {
    'URI import checks active owner before outbox read': 'importAndRestore(uri: Uri, ownerId: String): Result = RestoreOperationGate.mutex.withLock {\n        requireActiveOwner(ownerId)' in source,
    'Cloud inbox import checks active owner': 'importAndRestore(archive: File, ownerId: String): Result = RestoreOperationGate.mutex.withLock {\n        requireActiveOwner(ownerId)' in source,
    'resume path checks active owner before journal lookup': 'private suspend fun resumeLocked(ownerId: String, restoreId: String): Result {\n        requireActiveOwner(ownerId)' in source,
    'resume path rechecks active owner after journal lookup': 'require(journal.ownerId == ownerId)' in source and 'Recheck after the Room read' in source and 'requireActiveOwner(ownerId)' in source,
    'COMPLETE cleanup is owner-authenticated': 'if (journal.status == "COMPLETE") {\n            // COMPLETE still belongs to the original owner; do not run even local\n            // cleanup through a stale screen after logout/account switch.\n            requireActiveOwner(ownerId)' in source,
    'pending outbox check is bracketed by owner checks': 'private suspend fun requireNoPendingOutbox(ownerId: String) {\n        requireActiveOwner(ownerId)\n        val pending = dao.countUnsentOutbox(ownerId)\n        requireActiveOwner(ownerId)' in source,
    'owner guard compares Supabase Auth UID exactly': 'SupabaseClientProvider.client.auth.currentUserOrNull()?.id == ownerId' in source,
    'restore still serializes all entry points': 'RestoreOperationGate.mutex.withLock' in source,
    'local apply remains after snapshot downloaded': 'journal.status == "SNAPSHOT_DOWNLOADED"' in source,
    'outbox remains required empty before remote work': 'requireNoPendingOutbox(ownerId)' in source,
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'{sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()): raise SystemExit(1)
