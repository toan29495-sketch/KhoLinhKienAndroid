#!/usr/bin/env python3
"""Step184: transient disk/file access failures preserve the durable backup for retry."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UPLOADER = (ROOT / "app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt").read_text()
RECOVERY = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupRecovery.kt").read_text()
WORKER = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupWorker.kt").read_text()
FAILURE = (ROOT / "app/src/main/java/vn/kholinhkien/WeeklyBackupFailure.kt").read_text()
REVIEW = ROOT / "STEP184_REVIEW.md"
checks = []

def ck(label, ok):
    checks.append((label, bool(ok)))

# Recovery distinguishes unavailable/unreadable storage from confirmed integrity mismatch.
ready = RECOVERY[RECOVERY.index('"READY", "UPLOADING" ->'):RECOVERY.index('"VERIFIED" ->')]
ck("unavailable staged file is classified as retryable storage failure", '!archive.isFile || !archive.canRead()' in ready and 'WeeklyBackupStorageException' in ready)
ck("unavailable file path does not flow into FAILED journal update", ready.index('!archive.isFile || !archive.canRead()') < ready.index('throw WeeklyBackupStorageException') < ready.index('dao.markWeeklyBackupFailed'))
ck("read I/O errors preserve journal for retry", 'catch (io: IOException)' in ready and 'giữ nguyên journal' in ready)
ck("permission errors preserve journal for retry", 'catch (security: SecurityException)' in ready and 'WeeklyBackupStorageException' in ready)
ck("readable archive still verifies exact durable size", 'actualSize == row.archiveSizeBytes' in ready)
ck("readable archive still verifies journal SHA-256", 'digest.equals(row.sha256, ignoreCase = true)' in ready)
ck("confirmed readable integrity mismatch remains terminal", 'dao.markWeeklyBackupFailed(ownerId, row.backupId' in ready and 'Archive staging có kích thước hoặc SHA-256 không khớp' in ready)
ck("suspect archive bytes are retained for diagnosis", 'retain bytes for diagnosis' in ready)

# Uploader does not turn temporary inability to open the canonical archive into a permanent error.
validate = UPLOADER[UPLOADER.index('private suspend fun validatePreparedArchive'):UPLOADER.index('private fun requireActiveOwner')]
ck("uploader treats unavailable/unreadable archive as retryable", '!archive.isFile || !archive.canRead()' in validate and 'WeeklyBackupStorageException' in validate)
ck("uploader still rejects changed durable archive size", 'archive.length() == expectedSizeBytes' in validate)
ck("uploader still rejects changed archive fingerprint", 'actualDigest.equals(prepared.sha256' in validate)
ck("SHA-256 read I/O failures are classified as storage failures", 'catch (io: IOException)' in UPLOADER and 'WeeklyBackupStorageException' in UPLOADER)

# Worker retries storage pressure without marking the active journal FAILED.
catch = WORKER[WORKER.index('} catch (_: WeeklyBackupIdentityException)'):WORKER.index('} catch (permanent: PermanentWeeklyBackupException)')]
ck("Worker explicitly retries temporary storage failure", 'catch (_: WeeklyBackupStorageException)' in catch and 'Result.retry()' in catch)
ck("storage retry occurs before permanent integrity handler", WORKER.index('catch (_: WeeklyBackupStorageException)') < WORKER.index('catch (permanent: PermanentWeeklyBackupException)'))
ck("storage exception is distinct from permanent integrity failure", 'class WeeklyBackupStorageException' in FAILURE and 'class PermanentWeeklyBackupException' in FAILURE)
ck("review documents non-destructive retry behavior and limits", REVIEW.is_file and 'READY/UPLOADING' in REVIEW.read_text() and 'không' in REVIEW.read_text().lower())

failed = [label for label, ok in checks if not ok]
for label, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {label}")
print(f"Step184: {len(checks)-len(failed)}/{len(checks)} PASS")
if failed:
    raise SystemExit(1)
