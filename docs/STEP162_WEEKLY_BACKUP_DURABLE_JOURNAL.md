# Step162 — Durable weekly-backup journal and staged ZIP

## Implemented
- Added `weekly_backup_journal` to Room schema v14 and a non-destructive migration from v13.
- Journal is keyed by immutable `backupId` and records owner, state, exact archive path, creation time, snapshot cursor, row counts, archive size, SHA-256, last error and update time.
- Added owner-scoped reads and transactional `beginWeeklyBackup`; only one PREPARING/READY/UPLOADING backup can be active per owner. Reusing an ID with a different path or timestamp is rejected.
- The private staged ZIP can temporarily contain backup photo payloads while an upload is pending; it is not exposed in Downloads or treated as a photo-preview cache, and it is deleted after verified cloud upload. If the upload is interrupted, it remains in app-private storage for exact retry.
- Weekly ZIP now lives at `filesDir/weekly-backup-staging/<ownerId>/<backupId>.zip`, not cacheDir. The caller must supply a stable backupId (future WorkManager input); the journal row is committed before snapshot capture and ZIP creation. Retries reuse the journal creation timestamp rather than generating a new one.
- PREPARING can safely rebuild an incomplete local ZIP because upload cannot start until READY is persisted. READY/UPLOADING retries reuse the exact path and bytes; missing or changed bytes are rejected rather than silently rebuilding under the same TUS identity.
- Uploader requires the matching READY/UPLOADING journal, commits UPLOADING before network calls, and commits VERIFIED only after server confirmation. It deletes the local staged ZIP only after the VERIFIED journal update succeeds. If local confirmation fails, the ZIP remains for reconciliation.
- Verification download remains disposable in cacheDir and is always removed. No remote Storage delete was added.

## Deliberately not done
- No WorkManager schedule or worker wiring yet.
- No Supabase SQL candidate applied and no live Storage upload/RLS test.
- No retention cleanup; remote object deletion remains reserved for a trusted server-side process.
- No Gradle, Android compilation, APK build/install, or phone test.

## Verification
- `tools/verify_step162_weekly_backup_journal.py`: 23/23 PASS, including a disposable SQLite v13-to-v14 migration smoke preserving an existing parts row.
- Step140–161 regression suite is rerun separately and recorded in the Step162 review.
