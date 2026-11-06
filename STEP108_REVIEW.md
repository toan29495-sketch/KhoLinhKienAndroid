# Step108 – KTV + timestamp backup filename (2026-11-03)

## User request
Each ZIP backup should have a recognizable name containing the KTV identifier and the backup date/time. Example supplied: `SLKTVA03111219`.

## Interpretation and implementation
- Interpret `SL` as the backup prefix, `KTVA` as the stable KTV login username, and `03111219` as `ddMMHHmm` (day, month, 24-hour hour, minute).
- `BackupManager.suggestedFileName(username, timestampMillis)` produces `SL<USERNAME><ddMMHHmm>.zip`.
- Username is trimmed, uppercased with `Locale.ROOT`, and restricted to ASCII letters/digits so the suggested document name cannot contain path separators or unsafe punctuation. Empty/unsupported usernames fall back to `KTV`.
- The Tools screen supplies the authenticated profile's username. Android's CreateDocument picker still allows the user to change the suggested name before saving.
- The ZIP payload, manifest ownership checks, backup contents, checksums, photo preflight, and restore protocol are unchanged.

## Verification
- `tools/verify_step108_backup_filename.py`: 11/11 PASS.
- Full regression: `tools/verify_sync_step49_72.py` 24/24 plus SQLite schema/atomic replacement smoke test; every `tools/verify_step*.py` from Step73 through Step108 passed.
- Example contract: username `ktva`, 03 November 12:19 -> `SLKTVA03111219.zip`.

## Not performed
- No Android runtime/document-picker device test.
- No PostgreSQL/Supabase staging or deployment.
- No APK build/install, Edge Function deployment, or GitHub push.
- This is a source-level naming change only; no backup payload or restore compatibility changes.

Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
