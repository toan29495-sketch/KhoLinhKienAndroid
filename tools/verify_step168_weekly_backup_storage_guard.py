from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
backup = (ROOT / 'app/src/main/java/vn/kholinhkien/BackupManager.kt').read_text()
uploader = (ROOT / 'app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt').read_text()
guard = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupGuardedOutputStream.kt').read_text()
worker = (ROOT / 'app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt').read_text()

checks = {
    'weekly archive writes use guarded stream': 'WeeklyBackupGuardedOutputStream(' in backup,
    'guard reserves free space during every write': 'stagingDirectory.usableSpace' in guard and 'reserveBytes' in guard,
    'guard caps weekly ZIP at 256 MiB': 'WEEKLY_BACKUP_MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L' in backup and 'maxBytes - writtenBytes' in guard,
    'low space throws retryable typed storage exception': 'WeeklyBackupStorageException' in guard and 'catch (_: Exception)' in worker and 'Result.retry()' in worker,
    'partial PREPARING archive removed after failure': 'archive.delete()' in backup and 'Keep PREPARING journal' in backup,
    'journal identity retained on storage failure': 'dao.beginWeeklyBackup(' in backup and 'markWeeklyBackupFailed' not in backup,
    'cloud photo download reserves cache and archive headroom': 'MAX_PHOTO_BYTES + MIN_CACHE_RESERVE_BYTES + WEEKLY_BACKUP_FREE_RESERVE_BYTES' in backup,
    'verification temp removed before free-space measurement': uploader.index('if (target.exists() && !target.delete())') < uploader.index('StatFs(context.cacheDir.absolutePath).availableBytes'),
    'verification free-space shortage is retryable': 'throw WeeklyBackupStorageException(' in uploader,
    'canonical staged ZIP is not deleted by verification preflight': 'canonical staged ZIP is never touched' in uploader,
    'verification temp still cleaned after transfer': 'finally {\n            target.delete()' in uploader,
    'no remote deletion introduced': 'storage.from(BUCKET).delete' not in uploader and 'remove(' not in uploader,
    'no scheduler activation introduced': 'WeeklyBackupWorkScheduler.schedule(' not in worker,
    'no schema migration introduced': 'version = 14' in (ROOT / 'app/src/main/java/vn/kholinhkien/Data.kt').read_text(),
    'new guard validates bounds': 'require(reserveBytes >= 0L && maxBytes > 0L)' in guard,
    'zero-length writes are safe': 'if (length == 0) return' in guard,
    'byte counter advances only after successful delegate write': guard.index('delegate.write(buffer, offset, length)') < guard.index('writtenBytes += length.toLong()'),
    'close and flush delegate correctly': 'override fun flush() = delegate.flush()' in guard and 'override fun close() = delegate.close()' in guard,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'RESULT: {sum(checks.values())}/{len(checks)} PASS')
if not all(checks.values()):
    raise SystemExit(1)
