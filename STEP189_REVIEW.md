# Step189 Review — Authenticated Cloud backup download and integrity verification

Date: 03/11/2026

## Source base
- Exact input: `KhoLinhKienAndroid-step188-cloud-backup-catalog-ui-20261103.zip`
- Input SHA-256: `549eeaa73cdc702de8973b69e3635b36570f5e734aa1e05f8d19ead2609b1c3d`
- The Step188 ZIP was extracted and used as the source base; no older archive was substituted.
- Historical provenance correction: Step188 source was Step186, not a Step187 source ZIP. Step187 was a live Supabase deployment. The unverified Step187 source archive/hash previously written in Step188 review are withdrawn; Step188 review and PROGRESS.md now record the actual Step186 input hash.

## Changes
1. Added `WeeklyBackupCloudDownloader`, using the existing Supabase authenticated Storage download API against the private `inventory-backups` bucket.
2. Download is accepted only for a catalog entry that is `VERIFIED`, has `verified_at`, has no `deleted_at`, and has the exact owner/backup UUID path.
3. Before transfer, checks available cache capacity for the archive size plus a 16 MiB safety margin. Downloads to a private-cache `.partial` file; partial bytes are removed in `finally` on failure/cancellation.
4. Checks the active Supabase UID before/after transfer and while hashing. Checks exact archive size and SHA-256 against the catalog metadata.
5. Runs `BackupArchiveInspector.verify` over the downloaded ZIP, validating supported archive structure, owner identity, required entries, entry checksums, and record counts.
6. Independently compares manifest owner, part count, movement count, backup kind and snapshot cursor against the Cloud catalog row. Only then atomically renames the file to a private-cache verified filename.
7. Added an explicit per-entry “Tải và xác minh backup này” action in Tools UI. The action runs on `Dispatchers.IO`, reports errors separately, and displays a verified-but-not-restored state. It does not invoke `RestoreCloudCoordinator`, alter Room, or mutate Cloud.
8. Existing uploader authenticated readback verification remains unchanged.

## Verification
- `tools/verify_step189_cloud_backup_download.py`: 18/18 PASS.
- Regression: all verifier scripts under `tools/verify_step*.py` were executed; results recorded in the Step189 delivery summary.
- `python -m compileall -q tools`: PASS.
- ZIP integrity and SHA-256 checked after packaging.

## Not verified / remaining gates
- No Kotlin compilation or Android build was run; Gradle remains intentionally skipped.
- No APK was built, installed, or tested on the phone.
- No real Android-authenticated Storage download was run; therefore Storage SELECT policy, real transfer, actual Cloud SHA-256 and archive validation have not been exercised against a production object.
- No restore action is wired to the downloaded file. The verified archive remains in app-private cache and may be removed by Android cache eviction; this is an intermediate step, not durable restore staging.
- No restore, Room mutation, cloud restore commit, data deletion, or Supabase migration was performed.

## Data safety
- No Supabase data, backup metadata, Storage object, inventory, movement, profile, or restore session was created, updated, or deleted.
- No Worker scheduling, GitHub push, Gradle, APK build, or device test.
