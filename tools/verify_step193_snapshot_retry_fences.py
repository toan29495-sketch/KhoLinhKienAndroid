from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
reader = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotReader.kt').read_text()
applier = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotApplier.kt').read_text()
coordinator = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt').read_text()
committer = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudCommitter.kt').read_text()
sender = (ROOT / 'app/src/main/java/vn/kholinhkien/RestoreCloudSessionSender.kt').read_text()
worker = (ROOT / 'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
checks = {
    'snapshot reader authenticates before journal read': 'val journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))' in reader and 'requireActiveOwner(ownerId)' in reader,
    'snapshot reader reauthenticates before entering downloading state': 'requireActiveOwner(ownerId)\n        dao.saveRestoreJournal(journal.copy(status = "SNAPSHOT_DOWNLOADING"' in reader,
    'snapshot read RPC response discarded after account switch': 'val raw = client.postgrest.rpc(' in reader and 'requireActiveOwner(ownerId)\n            val envelope' in reader,
    'snapshot verification RPC response discarded after account switch': 'val verificationRaw = client.postgrest.rpc(' in reader and 'requireActiveOwner(ownerId)\n            val verification' in reader,
    'snapshot rows are owner-checked before Room staging insert': 'require(client.auth.currentUserOrNull()?.id == ownerId)' in reader and 'dao.insertRestoreStagedRows(rows)' in reader,
    'snapshot downloaded state requires active owner': 'requireActiveOwner(ownerId)\n            dao.saveRestoreJournal(journal.copy(status = "SNAPSHOT_DOWNLOADED"' in reader,
    'snapshot reader retains retryable downloading state on failure': 'status = "SNAPSHOT_DOWNLOADING", lastError' in reader,
    'Room applier validates owner before journal access': 'val authenticatedUserId = client.auth.currentUserOrNull()?.id' in applier and 'require(authenticatedUserId == ownerId)' in applier,
    'Room applier validates owner again immediately before transaction': 'require(client.auth.currentUserOrNull()?.id == ownerId)' in applier and applier.index('require(client.auth.currentUserOrNull()?.id == ownerId)') < applier.index('dao.applyCommittedRestoreSnapshot('),
    'Room applier still requires fully downloaded snapshot': 'journal.status == "SNAPSHOT_DOWNLOADED"' in applier,
    'ambiguous commit remains replayable with same restoreId': 'COMMIT_UNKNOWN' in committer and 'put("p_restore_id", restoreId)' in committer and 'commit_inventory_restore' in committer,
    'confirmed commit avoids a second network commit': 'if (journal.status == "COMMIT_CONFIRMED")' in committer and 'return@withLock CommitResult(' in committer,
    'cloud chunk retries preserve CLOUD_STAGING': 'status = "CLOUD_STAGING"' in sender and 'chunk RPCs are idempotent' in sender,
    'remote feed applies snapshot only under shared snapshot gate': 'RestoreSnapshotGate.mutex.withLock' in worker and 'RestoreCloudSnapshotApplier(applicationContext, dao).apply' in worker,
    'coordinator gates snapshot read and local apply together': 'RestoreSnapshotGate.mutex.withLock' in coordinator and 'RestoreCloudSnapshotApplier(context.applicationContext, dao).apply' in coordinator,
    'Room transaction validates all staged counts before clearing live rows': 'countStagedRestoreRows(ownerId, restoreId, "SNAPSHOT_PART") == expectedPartCount' in (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text() and 'clearMovements(ownerId)' in (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text(),
}
for name, ok in checks.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'{sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()): raise SystemExit(1)
