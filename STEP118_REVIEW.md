# Step118 – Backup local-photo ownership validation

Date: 2026-11-03  
Baseline: Step117 checkpoint `KhoLinhKienAndroid-step117-zip-temp-directory-hardening-20261103.zip`.

## Scope
Close a local-photo ownership gap in ZIP backup generation without changing photo storage, upload behavior, archive format, or existing data.

## Finding
- `BackupManager` previously accepted any absolute `Part.photoPath` if it resolved to a file.
- That bypassed the owner-scoped pending-photo rule enforced by the photo preview and Outbox sender. A malformed or cross-account local path could therefore be included in another KTV's backup ZIP.

## Changes
- Backup now requires a canonical UUID owner ID before proceeding.
- Absolute local photo paths are canonicalized and must resolve inside the app-private `filesDir`.
- New pending photos under `pending_part_photos/<ownerId>/` are accepted only when the direct parent is the active owner's directory.
- Backward compatibility is retained only for legacy direct-child `pending_part_photos/pending_*` files used by older Outbox rows.
- Other KTV pending-photo folders and arbitrary external absolute paths are rejected before ZIP output is opened.
- Legacy app-private restored photos outside the pending-photo namespace remain supported; their historical ownership cannot be independently proven because older paths contain no owner marker.
- Original local photos are read in place and are not moved or deleted. Cloud paths continue through the existing owner-prefix validator and authenticated download path.

## Verification
- `tools/verify_step118_backup_local_photo_owner_isolation.py`: 12/12 PASS.
- Updated Step86 backup photo preflight verifier: 7/7 PASS.
- Step107 owner local photo isolation: 14/14 PASS.
- Step111 backup/restore size parity: 14/14 PASS.
- Additional restore isolation/cancellation and sync regression checks recorded in PROGRESS.md.

## Limitations
- Static/source verification only. No Android compilation, APK build/install, physical-device backup test, multi-account runtime test, or process-kill test.
- No live Supabase/PostgreSQL staging, SQL or Edge Function deployment, or GitHub push.
- Legacy shared `part_photos` files have no embedded owner marker; they remain supported for compatibility and cannot be independently owner-attributed from the path alone.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
- Fixed-backup filename regression verifier was updated to expect strict canonical UUID owner validation (rather than the superseded nonblank-only check): Step109 13/13 PASS.
- Backup metadata preview: Step110 20/20 PASS; filename generation: Step108 11/11 PASS.
- ZIP-temp cleanup/isolation: Step112 10/10, Step115 10/10, Step116 11/11, Step117 11/11 PASS; cancellation/resume Step113 14/14; restore account isolation Step114 11/11.
- Sync regression Step49–72: 24/24 + SQLite schema/atomic replacement smoke PASS; Python tooling compileall PASS.
