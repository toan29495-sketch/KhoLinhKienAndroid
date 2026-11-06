# Step184 Review — weekly backup storage pressure and staging-file access retry

## Scope
Handle device storage exhaustion and temporary loss of access to the durable ZIP or verification cache while a weekly backup is being prepared, uploaded, resumed, or verified. A transient local I/O problem must not destroy the resumable journal or mark an otherwise valid backup permanently failed.

## Changes
- `WeeklyBackupRecovery` now treats a READY/UPLOADING archive that cannot currently be opened/read (`!isFile`, `!canRead`, `IOException`, or `SecurityException`) as `WeeklyBackupStorageException`. Recovery stops before changing the journal or deleting the staged ZIP, allowing WorkManager to retry after storage/access recovers.
- Recovery still marks a READY/UPLOADING journal `FAILED` only after it can read and measure the archive and confirms a durable size or SHA-256 mismatch. Suspect bytes are retained for diagnosis; no ZIP is silently rebuilt under the same `backupId`.
- `SupabaseWeeklyBackupUploader.validatePreparedArchive` distinguishes an unavailable/unreadable staged ZIP from a confirmed size/fingerprint mismatch. Temporary access errors remain retryable; a readable but changed archive remains a permanent integrity failure.
- SHA-256 file-read `IOException` and `SecurityException` in recovery/uploader are translated to the retryable storage exception. Coroutine cancellation continues to propagate unchanged.
- `WeeklyBackupWorker` explicitly maps `WeeklyBackupStorageException` to `Result.retry()` before the permanent-integrity handler. The READY/UPLOADING journal and canonical ZIP remain the retry source.
- Existing verification-download free-space guard remains in place. If cache space is insufficient, `verifyStoredObject` throws `WeeklyBackupStorageException`; its temporary target is cleaned in `finally`, while the canonical staging ZIP is untouched. A TUS upload failure followed by inability to verify because the cache is full also retries without terminalizing the journal.

## Verification
- `verify_step184_weekly_backup_storage_pressure_retry.py`: 16/16 PASS.
- Regression Steps158–184: run all targeted verifier scripts and record the aggregate result in `PROGRESS.md` after execution.
- Python compileall, ZIP integrity, and SHA-256 are checked after packaging.

## Limits / non-actions
- Source-contract checks only; no actual low-storage Android simulation, permission revocation, filesystem fault injection, WorkManager retry, TUS transfer, or Supabase staging test.
- A file that remains permanently missing/unreadable is intentionally retained in READY/UPLOADING for retry/manual diagnosis rather than being auto-marked FAILED based on ambiguous `File.isFile`/`canRead` results. This favors preservation over automatic cleanup and may require later operator intervention; dữ liệu không bị tự ý xóa.
- No Kotlin/Android compilation, APK build/install, device test, Room schema, SQL/backend candidate, server, account, inventory, history, or Storage mutation. No schedule activation and no GitHub push. Gradle intentionally not used.
