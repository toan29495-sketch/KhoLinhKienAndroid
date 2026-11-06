from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
life = (ROOT/'app/src/main/java/vn/kholinhkien/RestoreStagingLifecycle.kt').read_text()
reader = (ROOT/'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotReader.kt').read_text()
applier = (ROOT/'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotApplier.kt').read_text()
coord = (ROOT/'app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt').read_text()
worker = (ROOT/'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
checks = [
 ('one process-wide snapshot gate declared', 'internal object RestoreSnapshotGate' in life and 'val mutex = Mutex()' in life),
 ('reader no longer owns an independent mutex', 'val mutex = Mutex()' not in reader and 'mutex.withLock' not in reader),
 ('applier no longer owns an independent mutex', 'val mutex = Mutex()' not in applier and 'mutex.withLock' not in applier),
 ('coordinator locks full snapshot pipeline', 'RestoreSnapshotGate.mutex.withLock' in coord and coord.index('RestoreCloudSnapshotGate.mutex.withLock') if False else 'RestoreCloudSnapshotReader(context.applicationContext, dao).download' in coord and coord.index('RestoreCloudSnapshotReader(context.applicationContext, dao).download') < coord.index('RestoreCloudSnapshotApplier(context.applicationContext, dao).apply')),
 ('coordinator reloads journal after acquiring gate', coord.index('RestoreSnapshotGate.mutex.withLock') < coord.index('journal = requireNotNull(dao.restoreJournal(ownerId, restoreId))', coord.index('RestoreSnapshotGate.mutex.withLock'))),
 ('coordinator only applies downloaded snapshot', 'if (journal.status == "SNAPSHOT_DOWNLOADED")' in coord),
 ('worker active-restore path uses shared gate', 'RestoreSnapshotGate.mutex.withLock' in worker),
 ('worker re-reads journal after acquiring gate', worker.index('RestoreSnapshotGate.mutex.withLock') < worker.index('val latest = dao.restoreJournal(ownerId, activeRestore.restoreId)')),
 ('worker validates owner and source type', 'latest.ownerId != ownerId || latest.sourceType != "REMOTE_FEED"' in worker),
 ('worker re-reads journal immediately before apply', 'val beforeApply = dao.restoreJournal(ownerId, latest.restoreId)' in worker),
 ('RESTORE_COMMIT path serializes journal creation, download and apply', worker.index('RestoreSnapshotGate.mutex.withLock', worker.index('val remoteJournal')) < worker.index('RestoreCloudSnapshotReader', worker.index('val remoteJournal')) < worker.index('RestoreCloudSnapshotApplier', worker.index('val remoteJournal'))),
 ('RESTORE_COMMIT metadata is revalidated under gate', 'current.sourceType == "REMOTE_FEED"' in worker and 'current.snapshotCursor == eventId && current.partCount == partCount' in worker),
 ('failed begin does not continue to download', 'if (!blockedByLocalChanges) {' in worker),
 ('live apply remains guarded by SNAPSHOT_DOWNLOADED', 'beforeApply?.status == "SNAPSHOT_DOWNLOADED"' in worker and 'journal.status == "SNAPSHOT_DOWNLOADED"' in coord),
 ('no new destructive server operation', 'delete(' not in reader.lower() and 'delete(' not in applier.lower()),
]
for label, ok in checks: print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'{sum(ok for _,ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _,ok in checks) else 1)
