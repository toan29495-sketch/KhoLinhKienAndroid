# Step110 – Backup identity and restore preflight preview (2026-11-03)

## Goal
Show who created a ZIP backup, when it was created, and how much data it contains before the user confirms restore. Reject cross-account archives before starting the restore workflow.

## Changes
- Extended the existing ZIP v2 manifest additively with `backupUsername`, `createdAt` (device-local epoch milliseconds), and the immutable generated `fileName`. Existing `ownerId`, part/movement counts, and checksums remain unchanged; manifest version remains 2 so old v2 archives remain readable.
- Added `BackupArchiveInspector`, which reads only the bounded manifest (maximum 1 MiB) from the selected ZIP without extracting the archive payload. It validates version, owner ID, and count ranges. It is a preview, not a substitute for the full ZIP validator.
- After a user selects a ZIP, the app inspects it on `Dispatchers.IO`. The confirmation dialog displays filename, KTV username, timestamp, part count, and movement count.
- Confirmation is disabled while inspecting, when the manifest is invalid, or when the archive owner differs from the active profile. The existing `ZipRestore` independently rechecks owner ID and all archive entries/checksums/payload before any restore is applied.
- Older v2 archives without the new optional metadata show a legacy KTV label and indicate that the timestamp is unavailable. Owner ID remains mandatory.

## Verification
- `tools/verify_step110_backup_metadata_preview.py`: 20/20 PASS.
- `tools/verify_step109_fixed_backup_filename.py`: 13/13 PASS.
- `tools/verify_step108_backup_filename.py`: 11/11 PASS.
- `tools/verify_step86_backup_photo_preflight.py`: 7/7 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 + SQLite schema/atomic replacement smoke PASS.
- All `tools/verify_step*.py` scripts for Steps73–110 passed in the regression run.

## Not performed / limitations
- No Android compilation, device UI test, real ZIP picker test, Android process-death test, or large/corrupt ZIP runtime test.
- No PostgreSQL/Supabase staging, SQL deployment, APK build/install, Edge Function deployment, or GitHub push.
- Preview reads only the manifest and is intentionally not a full ZIP integrity check; `ZipRestore` remains the authoritative validator and rejects duplicate entries, unsafe paths, mismatched checksums, and malformed payloads before staging.
- Backup timestamp reflects the device's local clock; a misconfigured device clock can produce an inaccurate displayed time or filename.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
