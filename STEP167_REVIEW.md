# Step167 Review — Weekly backup stale-journal lifecycle diagnostics

## Scope
Classify old local weekly-backup journal entries without unsafe automatic abandonment, replacement, or deletion. A long offline period must not destroy the only resumable archive.

## Changes
- Added `WeeklyBackupLifecycle.kt`, a pure age/state classifier with no DAO, filesystem, Supabase, or WorkManager dependency.
- `PREPARING` is classified as stale/recoverable after 24 hours; existing recovery behavior may remove a crash-truncated local ZIP while preserving the journal identity.
- `READY` / `UPLOADING` is classified as stale-but-resumable after 30 days. The exact archive path/bytes remain preserved for TUS resume; no age-based abandonment or re-creation occurs.
- `VERIFIED` is classified as local-cleanup-only; recovery continues idempotent local ZIP cleanup.
- `FAILED` / `ABANDONED` remain terminal audit records. No automatic local or remote deletion is introduced.
- Recovery report now exposes counts for stale PREPARING, stale resumable uploads, and retained terminal records. Age classification clamps negative age to zero if the device clock moves backwards.
- Unknown journal states fail closed. No Room schema change (still v14), no SQL/backend changes, no remote Storage deletion, and no scheduler activation.

## Verification
- `verify_step167_weekly_backup_lifecycle.py`: 17/17 PASS.
- Step140–156 regression scripts: all passed, including Step141 SQLite migration smoke.
- Step158–166 regression scripts: all passed; Step162 journal + SQLite smoke 23/23; Step163 recovery 19/19; Step164 worker 22/22; Step165 identity 12/12; Step166 retry classification 19/19.
- Total prior Step140–166 regression: 448 PASS lines / 0 FAIL; Step167 adds 17/17 PASS.
- `python3 -m compileall -q tools`: PASS.
- ZIP integrity verified after packaging.

## Not verified / not done
- No Kotlin/Android compilation or WorkManager runtime test; no APK build/install or phone test.
- No live Supabase SQL, bucket, RLS, RPC, TUS upload/download, or staging verification.
- No periodic schedule enqueue/activation; no local/remote backup retention deletion; no account/profile/inventory mutation; no GitHub push.
- No Gradle used.

## Safety
- No Room migration; database remains v14.
- No Supabase/live project changes; no service-role key requested or used.
- Stale age is diagnostic only; it never authorizes deletion or abandonment.
