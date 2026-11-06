# Step180 Review — distinguish a new weekly backup from resuming an active one

## Scope
Clarify the identity decision when a periodic/retry trigger arrives while a prior weekly backup is still active. A fresh backup identity is allowed only when the owner has no active journal. Existing active work must resume under its durable identity.

## Changes
- Added `WeeklyBackupLifecycle.backupIdForAttempt(active, proposedBackupId)` as the explicit policy boundary.
- With no active journal, the proposed canonical UUID becomes the new backup identity.
- With `PREPARING`, `READY`, or `UPLOADING`, the original canonical backupId is reused. `PREPARING` may reconstruct under its existing identity; `READY`/`UPLOADING` continue to validate and reuse the exact archive path, bytes, size, SHA-256 and TUS identity.
- Terminal/unsupported states fail closed; they are not silently replaced with a new backup.
- Worker passes the resolved identity unchanged to preparation and uploader. Room's transactional active-journal guard remains the durable defense against duplicate active rows.
- Updated Steps164, 166, 172 and 179 source-contract verifiers to assert the explicit policy helper rather than the former inline Elvis expression.

## Verification
- `verify_step180_weekly_backup_new_vs_resume.py`: 14/14 PASS.
- Targeted regression Step158–180: 23 verifier scripts, 381/381 checks PASS.
- Python `compileall`: PASS.
- ZIP integrity and SHA-256 verified after packaging.

## Limits / non-actions
- Static source-contract verification only; no Kotlin/Android compilation, APK, device test, WorkManager runtime, TUS/Supabase/RLS/staging test.
- No Room schema change (remains v14), no SQL/backend candidate changes, no server/data/Storage mutations, no schedule activation, no GitHub push.
- Gradle intentionally not used.
