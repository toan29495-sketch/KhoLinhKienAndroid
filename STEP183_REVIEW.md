# Step183 Review — weekly backup photo-after-archive immutability

## Scope
Audit the race where a referenced Cloud/local photo changes or disappears after the weekly ZIP has been prepared but before its upload starts. The durable ZIP, not the mutable source photo, must remain the upload payload and TUS identity.

## Changes
- `SupabaseWeeklyBackupUploader.beginRegistration` now receives the persisted journal `archiveSizeBytes`; the RPC no longer samples a potentially changed file length for its registration metadata.
- For `PENDING` registrations, immediately before creating/resuming the TUS upload, the uploader revalidates the canonical staged ZIP against the exact journal size and prepared SHA-256. The check is after the remote registration round trip, narrowing the time-of-check/time-of-use window.
- A changed size or same-size changed bytes fail closed as `PermanentWeeklyBackupException`; the code does not regenerate photo entries, replace the ZIP, or reuse the same `backupId` with a new fingerprint.
- Original photo paths are not consulted by the uploader. Once ZIP preparation completes, later source-photo deletion/replacement cannot alter the already embedded photo bytes or their manifest checksums. TUS remains `upsert=false`; remote readback still verifies exact size + SHA-256 before completion.
- Existing recovery still hashes READY/UPLOADING staged ZIP against the durable journal and retains suspect bytes for diagnosis rather than silently rebuilding them.

## Verification
- `verify_step183_weekly_backup_photo_after_archive_immutability.py`: 15/15 PASS.
- Regression Steps158–183: 26 verifier scripts; all checks PASS.
- Python `compileall`: PASS.
- ZIP integrity and SHA-256 checked after packaging.

## Limits / non-actions
- Source-contract verification only; no live photo replacement/deletion race, no real TUS transfer, and no Supabase object mutation/download.
- No Kotlin/Android compilation, APK build/install, device test, Room/WorkManager runtime test, or staging test.
- No Room schema, SQL/backend candidate, server, account, inventory, history, or Storage mutation. No schedule activation and no GitHub push.
- Gradle intentionally not used.
