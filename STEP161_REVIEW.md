# Step161 — Android weekly backup uploader adapter (03/11/2026)

Baseline: Step160 local candidate state; no live Supabase state was changed.

## Source work

- Added `app/src/main/java/vn/kholinhkien/SupabaseWeeklyBackupUploader.kt`.
- The adapter validates canonical owner/backup UUIDs, active session, private-cache ZIP location, 256 MiB cap, snapshot metadata, and re-computes source SHA-256 before any RPC.
- It calls `begin_inventory_backup` using the Step160 candidate parameter contract and validates the server-derived object path.
- It uses Supabase Kotlin 3.0.1 File-based resumable upload with explicit `upsert=false` and `application/zip`.
- The exact upstream `supabase/supabase-kt` tag `3.0.1` source was inspected: `ResumableClient.kt`, `ResumableUtils.kt`, `Storage.kt`, and `JvmUtils.kt`. The `createOrContinueUpload(path, file, options)` extension and the download-to-file API are present in this pinned tag; no dependency upgrade was made.
- On uncertain upload completion, it only accepts an existing object after authenticated download-to-private-temp-file and exact byte-size + SHA-256 match. It checks available disk space before downloading and deletes the verification copy in `finally`.
- It checks active owner at RPC and Storage boundaries, propagates coroutine cancellation, and never deletes the prepared ZIP or any remote object.
- Refined Step160 SQL candidate: added `upload_authorized_until`; exact idempotent begin retries refresh the 24-hour authorization window. The Storage policy helper now checks this field, avoiding a stale pending registration blocking a TUS retry after URL expiry.

## Critical limitation before Worker integration

The SDK's resumable fingerprint is based on source file path and size. The same `backupId` must use the exact same archive path and bytes across retries. Step158 currently creates a random-named ZIP in `cacheDir`, and Android may evict cache files. Therefore this adapter is deliberately not connected to WorkManager. A future step must add a durable owner-scoped upload journal and app-private staged ZIP lifecycle, preserving the exact archive until upload is verified. If the archive is lost, the app must not silently reuse the same backupId for a newly captured snapshot.

## Verification

- `verify_step160_backup_backend_candidate.py`: 31/31 PASS after the upload-window refresh addition.
- `verify_step161_weekly_backup_uploader.py`: 21/21 PASS.
- Regression Step140–156, Step158–159: all PASS; Python compileall PASS.
- No Kotlin/Android compile or APK build was run. No Gradle was used. No live Storage upload/TUS/RLS test, WorkManager run, or phone test was performed.
- SQL candidate was not executed; no bucket, table, policy, RPC, migration, or Edge Function was created/deployed. No Supabase data or objects were modified/deleted.

## Next

Step162: add a durable, owner-scoped automatic-backup upload journal and staged archive lifecycle. Only after that can a WorkManager Worker safely reuse the same backup ID/file across process death. Retention remains deferred until a trusted server-side delete mechanism exists.
