# Step190 Review — Durable owner-scoped Cloud restore inbox

Date: 03/11/2026

## Source base
- Exact input: `KhoLinhKienAndroid-step189-cloud-backup-download-verify-20261103.zip`
- Input SHA-256: `a7c210a9433e47856c1ccba63a2a3a028c0b7e34fad366883897ff69d6c8301c`
- Extracted Step189 and continued incrementally; no older ZIP was substituted.

## Changes
1. Added `WeeklyBackupRestoreInbox`, a durable app-private inbox under `filesDir/cloud_restore_inbox/<ownerId>/`, separate from evictable cache and separate from the existing restore journal/staging workspace.
2. Promotion accepts only a catalog entry still marked as restore-eligible and a matching previously verified backup ID. It rechecks current Supabase Auth UID, exact archive size, SHA-256 against both catalog metadata and the verified download result, and reruns `BackupArchiveInspector.verify` before publication.
3. Copies to an owner-scoped `.partial` file, flushes it with `FileDescriptor.sync()`, then atomically renames to the final UUID-named ZIP. Existing final files are never overwritten with different bytes.
4. Writes a sidecar metadata record containing owner, backup ID, storage path, size, SHA-256, backup kind, snapshot cursor, counts, and saved timestamp. No credentials or access tokens are stored.
5. Tools UI now promotes the verified cache archive into the durable inbox and reports that it is saved but not restored. The temporary verified cache file is removed after successful promotion.
6. This step does not create `RestoreJournal`, invoke `ZipRestore`, invoke `RestoreCloudCoordinator`, upload photos, call restore RPCs, or change Room/Supabase business data.

## Verification
- `tools/verify_step190_durable_cloud_restore_inbox.py`: 15/15 PASS.
- Targeted regression `verify_step150` through `verify_step190` (40 scripts present in this range): 656 counted PASS, 0 failing scripts.
- `python -m compileall -q tools`: PASS.
- ZIP integrity and SHA-256 checked after packaging.
- A broader historical sweep was attempted but timed out; it surfaced pre-existing failures in older verifier scripts (e.g. Step105, 106, 127, 138). Those were not introduced or modified by Step190 and are not represented as passing.

## Not verified / remaining gates
- No Kotlin compilation or Android build; Gradle intentionally skipped.
- No APK build, install, or physical-device test.
- No real authenticated Storage download or durable promotion test against an actual Cloud backup object.
- Inbox recovery/listing after process death is not yet surfaced in UI; durable bytes and sidecar are retained, but Step191 must add safe re-discovery and explicit restore confirmation.
- No restore journal, remote restore, Room replacement, SQL migration, or destructive operation was executed.

## Data safety
- No Supabase metadata, Storage object, user/profile, inventory, movement, restore journal, or outbox was created, updated, or deleted.
- No GitHub push, Gradle, APK build, or device test.
