from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
reader = (ROOT/'app/src/main/java/vn/kholinhkien/RestoreCloudSnapshotReader.kt').read_text()
coord = (ROOT/'app/src/main/java/vn/kholinhkien/RestoreCloudCoordinator.kt').read_text()
worker = (ROOT/'app/src/main/java/vn/kholinhkien/SyncPullWorker.kt').read_text()
checks = [
 ('reader receives application context', 'private val context: Context' in reader),
 ('StatFs checks persistent files volume', 'StatFs(context.filesDir.absolutePath).availableBytes' in reader),
 ('16 MB reserve exists', '16L * 1024L * 1024L' in reader),
 ('SQLite write amplification accounted', 'SQLITE_WRITE_MULTIPLIER = 2L' in reader),
 ('estimate uses UTF-8 payload bytes', 'payloadJson.toByteArray(Charsets.UTF_8).size.toLong()' in reader),
 ('space checked before each durable chunk insert', reader.index('requireSnapshotSpace(estimatedWriteBytes)') < reader.index('dao.insertRestoreStagedRows(rows)')),
 ('owner checked before chunk write', reader.index('require(client.auth.currentUserOrNull()?.id == ownerId)') < reader.index('dao.insertRestoreStagedRows(rows)')),
 ('owner checked after chunk write', reader.index('dao.insertRestoreStagedRows(rows)') < reader.rindex('require(client.auth.currentUserOrNull()?.id == ownerId)')),
 ('snapshot staged rows remain separate buckets', 'SNAPSHOT_PART' in reader and 'SNAPSHOT_MOVEMENT' in reader),
 ('retry clears only partial snapshot buckets', 'dao.clearRestoreStagedRowsByType(ownerId, restoreId, SNAPSHOT_PART)' in reader and 'dao.clearRestoreStagedRowsByType(ownerId, restoreId, SNAPSHOT_MOVEMENT)' in reader),
 ('live inventory apply remains downstream', 'RestoreCloudSnapshotApplier' in coord and 'SNAPSHOT_DOWNLOADED' in coord),
 ('coordinator passes application context', 'RestoreCloudSnapshotReader(context.applicationContext, dao)' in coord),
 ('worker passes application context', worker.count('RestoreCloudSnapshotReader(applicationContext, dao)') == 2),
 ('low-space message says live inventory untouched', 'Dữ liệu kho hiện tại chưa bị thay đổi' in reader),
 ('no destructive server cleanup introduced', 'delete(' not in reader.lower() and 'remove(' not in reader.lower()),
]
for label, ok in checks: print(('PASS' if ok else 'FAIL') + ' ' + label)
print(f'{sum(ok for _,ok in checks)}/{len(checks)} PASS')
raise SystemExit(0 if all(ok for _,ok in checks) else 1)
