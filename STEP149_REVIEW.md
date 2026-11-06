# Step149 — Legacy local-photo ownership fence (2026-11-03)

## Scope
Continue from the exact Step148 archive. Address the compatibility exception for absolute local photo paths stored before owner-scoped `pending_part_photos/<ownerId>/` directories were introduced.

## Finding
The sender and backup code allowed a legacy file directly under `files/pending_part_photos/` to be read without proving which account owned it. Older restored local photos under app-private `part_photos/` also had no directory-level owner marker. This was a cross-account privacy risk when one Room database was used after switching KTV accounts.

## Changes
- Added `InventoryDao.isLocalPhotoReferencedOnlyByOwner(ownerId, photoPath)`.
- The DAO returns true only if the exact absolute path is referenced by the requesting owner's `parts` row or immutable `sync_outbox` payload, and there is no reference from any other owner in either table.
- `SyncOutboxSender` still accepts new owner-scoped pending files directly. A legacy flat pending file is retryable only after the DAO proves exclusive ownership. Ambiguous, shared, foreign-referenced, and orphan files are rejected without deletion.
- `BackupManager.validateLocalPhotoFile` is now suspend and requires the same exclusive-owner proof for every local absolute photo path before including it in a ZIP. Cloud photo paths retain their existing owner-prefix validation.
- The UI preview continues to reject flat legacy pending paths; no cross-owner preview fallback was added. This is intentionally fail-closed until the app can safely associate a path.
- No file is migrated, renamed, deleted, or copied. Immutable outbox payloads and operation IDs are not rewritten.

## Verification
- `verify_step149_legacy_photo_owner_fence.py`: 14/14 PASS, including SQLite cases for owner-only, shared, foreign outbox, outbox-only retry, and orphan paths.
- Step140: 13/13.
- Step141 restore contract: 15/15.
- Step142: 12/12.
- Step143: 11/11.
- Step144: 14/14.
- Step145: 12/12.
- Step146: 12/12.
- Step147: 12/12.
- Step148: 31/31.
- `python3 -m compileall -q tools`: PASS.

## Limitations / not claimed
- These are source/static and SQLite predicate tests. Android/Room compilation and runtime instrumentation have not been run.
- No APK build/install, real account-switch device test, QR test, two-device test, or GitHub push.
- No Supabase schema, migration, RPC, Edge Function, Auth, Storage, account, or data changes.
- No Gradle execution or use.
