# Step168 Review — Weekly backup low-storage protection

## Scope
Protect weekly backup preparation and Cloud verification from low-storage failures without corrupting a resumable archive, losing journal identity, or deleting remote objects.

## Changes
- Added `WeeklyBackupGuardedOutputStream.kt`, which checks free space before each ZIP output write, preserves a 16 MiB free-space reserve, and caps the weekly archive at the backend's 256 MiB limit.
- Weekly ZIP creation now writes through the guarded stream. If storage becomes insufficient or the archive would exceed the cap, the write fails as a retryable storage exception. The PREPARING partial ZIP is removed by the existing failure path; its journal identity remains for safe reconstruction on retry.
- Cloud photo download preflight now accounts for both the per-photo/cache reserve and free space reserved for the final archive.
- Cloud verification removes a stale partial verification temp before measuring cache free space. Insufficient cache space is a retryable `WeeklyBackupStorageException`; the durable staged ZIP is not touched. The verification temp remains cleaned in `finally` after transfer attempts.
- Added `WeeklyBackupStorageException`; Worker already retries ordinary transient `Exception`, so low-space pressure waits for a later retry rather than marking the journal FAILED.
- No Room schema change (v14), no SQL/backend change, no remote object deletion, no scheduler activation.

## Verification
- `verify_step168_weekly_backup_storage_guard.py`: 18/18 PASS.
- Step140–167 regression: 465 PASS / 0 FAIL (Step140–166 448; Step167 17).
- Step162 journal/SQLite smoke 23/23; Step163 recovery 19/19; Step164 worker 22/22; Step165 identity 12/12; Step166 retry 19/19; Step167 lifecycle 17/17.
- Python compileall PASS; ZIP integrity verified.

## Not verified / not done
- No Kotlin/Android compilation, WorkManager runtime test, APK build/install, or phone test.
- No live Supabase SQL, bucket, RLS, RPC, TUS upload/download, or staging test.
- No scheduler enqueue/activation, account/profile/inventory mutation, remote/local retention deletion, or GitHub push.
- No Gradle used.

## Safety
- Room remains v14.
- Low-space failure leaves the journal recoverable; READY/UPLOADING archive bytes are not written through the guard or regenerated.
- No Supabase/live project changes, no service-role key, no remote deletion.
