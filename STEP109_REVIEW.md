# Step109 – Fixed account-bound backup filename (2026-11-03)

## User clarification
The user explicitly requires that the backup filename cannot be changed in the app and is automatically tied to the currently signed-in account. The prior Step108 suggestion picker was therefore not acceptable: Android's `CreateDocument` contract allows editing the suggested filename.

## Implementation
- Renamed the helper to `BackupManager.backupFileName(username, timestampMillis)` to describe a fixed generated name.
- Filename format remains `SL<USERNAME><ddMMHHmm>.zip`; username is trimmed, uppercased with `Locale.ROOT`, and filtered to ASCII letters/digits. Empty token fallback remains `KTV`.
- Removed ZIP `ActivityResultContracts.CreateDocument` launcher. Backup now writes directly without asking the user for a name.
- Android 10+: uses `MediaStore.Downloads` and app-specified `DISPLAY_NAME`, under `Downloads/KiemSoatLinhKien/`. The item stays `IS_PENDING=1` until the ZIP is complete, then is published.
- Android 8/9: uses app-specific external Documents directory because public Downloads insertion without legacy storage permission is not available through this implementation.
- Duplicate filename is rejected rather than overwriting a prior backup. A failed write removes the incomplete MediaStore item/file.
- The app displays the actual saved filename and destination. UI has a busy guard to avoid repeated tap races.
- ZIP payload, manifest owner ID, checksums, photo preflight and restore format are unchanged.

## Verification
- `tools/verify_step109_fixed_backup_filename.py`: 13/13 PASS.
- Updated `tools/verify_step108_backup_filename.py`: 11/11 PASS.
- Updated `tools/verify_step86_backup_photo_preflight.py` for the output stream abstraction: 7/7 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 + SQLite schema/atomic replacement smoke PASS.
- Every `tools/verify_step*.py` from Step73 through Step109 passed.

## Not performed / limitations
- No Android device test of MediaStore, visible Downloads path, storage-provider behavior, or Android 8/9 fallback.
- On Android 8/9 the fallback is app-specific and may not appear in the public Downloads collection; this is deliberate to avoid broad storage permission.
- The app does not offer a rename prompt or editable filename. The Android file manager may still let a user rename a file externally; the app cannot prohibit OS-level file-manager actions.
- No Supabase/PostgreSQL staging or deployment, APK build/install, Edge Function deployment, or GitHub push.

Step109 supersedes Step108's earlier manual-rename statement. Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
