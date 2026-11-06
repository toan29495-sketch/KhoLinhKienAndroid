# Step120 – Interrupted backup partial-file isolation and publication

Date: 2026-11-03  
Baseline: Step119 checkpoint `KhoLinhKienAndroid-step119-bounded-backup-cloud-photo-cache-20261103.zip` (SHA-256 verified before extraction).

## Finding
Step109 already used MediaStore `IS_PENDING` on Android 10+, but an abrupt process death could leave an unpublished pending row. On Android 8/9, the fallback wrote directly to the final fixed filename, so process death could leave a truncated ZIP at a name that looked complete. Ordinary exception cleanup did not cover process death.

## Changes
- Android 8/9 now writes to a hidden, owner-scoped `.backup_<ownerId>_<uuid>.partial` file in the same app-specific Documents directory, closes the ZIP, then renames to the final fixed filename. Existing final backups are never overwritten; failed writes remove the partial file.
- On a later backup, stale Android 8/9 partials are removed only when they are regular direct-child files, canonical path checks pass, their owner prefix and UUID suffix match exactly, and they are at least 24 hours old.
- Android 10+ MediaStore pending rows carry an exact owner marker in DESCRIPTION. On a later backup, only rows still marked `IS_PENDING=1`, in the app's exact Downloads subfolder, with the exact active-owner marker, and older than 24 hours are deleted. Published backups are not selected.
- ZIP v2 format, fixed final filename, duplicate-name protection, photo validation, checksums, and restore compatibility remain unchanged.

## Verification
- `tools/verify_step120_backup_interruption_atomic_publish.py`: 13/13 PASS.
- Step119, Step118, Step109, Step108, Step111, Step107, and Step86 focused source verifiers rerun; Step49–72 sync/SQLite regression rerun.
- ZIP integrity and Python tooling syntax checked.

## Limitations
- Source/static checks only. No actual Android process-kill, MediaStore provider, Android 8/9 filesystem rename, low-storage, compilation, APK, or device test.
- No live Supabase/PostgreSQL staging, SQL/Edge deployment, or GitHub push. No Gradle used.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.
