# Step111 – Backup/restore size and entry-limit parity (2026-11-03)

## Goal
Ensure the app never successfully creates a backup ZIP that its own restore path rejects solely because backup output exceeded the restore parser's supported uncompressed-size or entry-count limits. Protect low-storage devices from unexpectedly huge archives and partial publication.

## Findings
- `ZipRestore` already enforced: 10,002 total ZIP entries; 300 MiB aggregate uncompressed bytes; 100 MiB per JSONL entry; 20 MiB per photo; and 1 MiB manifest.
- `BackupManager` enforced only a 20 MiB source-photo check before streaming. A file could grow while being read, many photos could exceed the restore entry-count limit, and the combined uncompressed archive could exceed the restore total or JSONL limits. This could create a completed backup that the same app could not restore.

## Changes
- Added matching backup-side constants for all restore limits.
- Reject more than 9,999 photo entries (three other files are manifest + two JSONL files) before opening the ZIP output stream.
- Count actual uncompressed bytes as each parts/movements JSONL line, photo chunk, and manifest is written. Enforce the 300 MiB aggregate cap and the matching per-entry cap.
- Photo size is checked while streaming, not only from its initial `File.length()`, so a changing file cannot bypass the limit.
- Manifest size is checked before it is written.
- Existing photo preflight remains before ZIP output is opened. On failure, existing save logic deletes the incomplete MediaStore/app-specific destination; MediaStore output is not published while `IS_PENDING=1`.
- ZIP v2 schema, metadata, checksums, filename, owner binding, and legacy restore compatibility are unchanged.

## Verification
- `tools/verify_step111_backup_restore_size_parity.py`: 14/14 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 + SQLite schema/atomic replacement smoke PASS.
- Every `tools/verify_step*.py` verifier for Steps73–111 passed.
- ZIP integrity and SHA-256 verified for the Step111 checkpoint.

## Not performed / limitations
- No Android compilation, actual large-file/low-storage device test, MediaStore runtime test, process-death test, or corrupted ZIP runtime test.
- Static checks establish source-level parity; they do not prove Android runtime behavior or ZIP library behavior on every device.
- No PostgreSQL/Supabase staging, SQL deployment, APK build/install, Edge Function deployment, or GitHub push.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
