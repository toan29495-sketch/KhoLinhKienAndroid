# Step172 Review — Weekly backup process-death and upload-resume safety

## Scope
Audit the upload lifecycle for Android process termination while preparing, registering, uploading, verifying, or finalizing a weekly backup. Preserve owner-scoped journal identity and the exact staged archive needed by the TUS SDK.

## Changes
- `SupabaseWeeklyBackupUploader.uploadAndVerify` now captures the coroutine context and checks cancellation + authenticated owner at critical boundaries: before marking UPLOADING, before remote registration, before the completion RPC, and before committing local VERIFIED state.
- Archive SHA-256 verification is now suspendable and checks cancellation + owner for every 64 KiB chunk and before digest finalization. This applies both to the staged ZIP and the downloaded Cloud verification copy.
- The uploader still persists UPLOADING before remote registration. The worker reuses the active journal's backupId; recovery preserves a valid READY/UPLOADING archive at its exact canonical path and bytes. A missing/corrupt staged archive is marked FAILED rather than silently rebuilding different bytes under the same backupId.
- Cancellation propagates. The `finally` block deletes only the disposable cache verification copy; it does not delete the durable staged ZIP. The staged ZIP is removed only after the local journal is committed VERIFIED.
- Added `verify_step172_weekly_backup_process_death.py` to enforce these contracts.

## Verification
- Step172 verifier: 14/14 PASS.
- Steps 169–171 verifiers: 3 scripts PASS.
- Python compileall: PASS.
- ZIP integrity and SHA-256: checked for the output artifact.

## Not verified / not done
- Static source contracts are not a real Android process-kill test. No force-stop/reboot/WorkManager runtime test was run.
- No Kotlin/Android compilation, APK build/install, or physical-device test.
- No live Supabase registration, TUS resume, Storage download, completion RPC, RLS, or staging test.
- No scheduling activation/enqueue, schema/backend change, account/profile/inventory/history mutation, remote object operation, or GitHub push.
- No Gradle used, per explicit instruction.

## Safety
- No Room schema migration; Room remains v14.
- No READY/UPLOADING archive bytes or TUS fingerprint are rewritten by recovery.
- No remote object deletion or server-side mutation was performed.
