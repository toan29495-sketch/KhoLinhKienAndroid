# Step161 — Android Cloud backup uploader adapter

Status: source-level adapter only. It is not scheduled or wired to a WorkManager Worker, and the Step160 SQL candidate is not deployed.

## Pinned SDK verification

The project pins Supabase Kotlin BOM `3.0.1`. The exact `supabase/supabase-kt` tag `3.0.1` was inspected through the public source tree:

- `Storage/src/commonMain/kotlin/io/github/jan/supabase/storage/resumable/ResumableClient.kt`
- `Storage/src/androidAndJvmMain/kotlin/io/github/jan/supabase/storage/ResumableUtils.kt`
- `Storage/src/commonMain/kotlin/io/github/jan/supabase/storage/Storage.kt`
- `Storage/src/androidAndJvmMain/kotlin/io/github/jan/supabase/storage/JvmUtils.kt`

The tag confirms the File-based resumable upload API and `UploadOptionBuilder` lambda are available in 3.0.1. The adapter explicitly sets `upsert=false` and `contentType=application/zip`; it does not rely on a newer SDK API or change the dependency version. Resumable upload uses the SDK's configured/default 6 MiB chunk size.

## Adapter behavior

- Requires an authenticated session whose user ID matches the prepared backup owner.
- Requires a canonical lower-case UUID `backupId`, supplied by a future durable worker journal and reused on retries.
- Validates the archive is a ZIP directly in the app-private cache directory, within 256 MiB, and recalculates SHA-256 before registration.
- Calls `begin_inventory_backup` with the same parameter names and values as the Step160 SQL candidate; validates the returned row/path/status.
- Uploads the `File` using resumable TUS, `application/zip`, and `upsert=false`.
- On an uncertain upload result, it only accepts an already-existing object after authenticated download to a private temp file and exact size/SHA-256 match.
- Downloads the object to a temp file rather than loading the whole archive into RAM; checks free disk space first, streams SHA-256, checks owner/session at network boundaries, and always deletes the verification temp file.
- Calls `complete_inventory_backup` only after client-side download verification succeeds. The server records `CLIENT_DOWNLOAD_SHA256`; this is not independent server-side hashing.
- Does not delete the prepared archive or any cloud object. The future Worker/journal owns staging cleanup.

## Critical retry constraint discovered

The 3.0.1 SDK's resumable fingerprint is based on the source file path and file size. The same backup ID therefore must be paired with the exact same staged archive file path and bytes across retries. The current Step158 preparer creates a unique cache filename for each invocation, and cache files can be evicted by Android. Therefore this adapter must **not** be wired to a periodic Worker yet. A follow-up step must add a durable owner-scoped upload journal and app-private staged archive lifecycle so process-death retry can reopen the same bytes. If the staged archive is lost, the app must not reuse the same backupId for a newly captured snapshot; it must reconcile or abandon that PENDING record through a separately designed server flow.

## Verification boundary

Static source verifier only. No Kotlin/Android compile, APK build, Storage upload, real TUS retry, RLS impersonation, or phone test has been run. No Gradle was run. No Supabase project change was made.
