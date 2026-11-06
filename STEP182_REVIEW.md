# Step182 Review — weekly backup photo consistency

## Scope
Audit photo references included in weekly archives, focusing on cloud object disappearance/replacement, local cache leakage, owner isolation, and archive integrity.

## Findings
- Snapshot capture from Step181 refuses unresolved outbox operations, so a local photo waiting for upload or replacement cannot silently enter a weekly archive as if it were a stable cloud photo.
- Cloud photo paths are validated as owner-scoped before authenticated download from the private `part-images` bucket. The authenticated owner is checked again after the transfer; missing, empty, oversized, or over-limit aggregate downloads abort archive creation.
- Cloud photo object names are scoped by owner, part sync ID, and outbox operation ID. The photo sender reads the object back and verifies its bytes against the exact upload payload before publishing the path to the part record.
- Legacy/local photo files are constrained to app-private storage and require an exclusive owner reference. Downloaded cloud photos are placed only in a temporary cache directory and removed in `finally`; local pending files are not moved or deleted.
- The ZIP destination is not opened/truncated until all referenced photos have been resolved and validated. Each photo is SHA-256 hashed while written into the archive, and the checksum is stored in the manifest. A missing or changed object therefore fails closed or produces an archive whose actual bytes are explicitly checksummed; restore validates archive checksums.

## Verification
- `verify_step182_weekly_backup_photo_consistency.py`: 15/15 PASS.
- Regression Steps158–182: 25 verifier scripts, 413/413 checks PASS.
- Python `compileall`: PASS.
- ZIP integrity and SHA-256 checked after packaging.

## Limits / non-actions
- Source-contract audit only; no live Storage mutation/deletion race, no Supabase download, and no Android/Room test.
- No Kotlin/Android compilation, APK build/install, device test, WorkManager runtime test, or live Supabase/TUS/RLS/staging test.
- No Room schema change, SQL/backend candidate, server, account, inventory, history, or Storage mutation. No schedule activation and no GitHub push.
- Gradle intentionally not used.
