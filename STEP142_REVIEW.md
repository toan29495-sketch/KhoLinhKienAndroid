# Step142 – Remote schema fingerprint and Admin function parity review (2026-11-03)

## Baseline and scope
- Continues the Step141 working tree; no older archive was restored over it.
- Scope is read-only Supabase inspection plus source documentation/verifiers.
- No live SQL, migration, account, Storage, Edge Function, or business-data changes were made.
- No Gradle, Android Studio, Android SDK, emulator, or device build was used.

## Migration ledger reconciliation
- Re-read the remote migration ledger: exactly 16 entries, comprising seven Step138 and nine Step139 migrations.
- Remote first/last migration versions: `20261103094857` / `20261103101525`.
- The remote ledger stores migration versions and names, not the original SQL text. The connector exposes the current database definitions but does not return the original SQL body for each historical migration.
- Therefore, exact historical Step139 migration files cannot safely be recreated from the ledger alone. Reconstructing a set of new migrations from today's final schema and labeling them as the original Step139 SQL would be misleading and could replay DDL/data transformations incorrectly.
- Decision: keep Step139 marked as deployed-but-source-unreconciled; do not promote files from `migrations_pending_reconciliation`; do not run `db push` or replay anything. A current-state fingerprint is recorded in `supabase/REMOTE_MIGRATION_MANIFEST.md` for future drift checks, not as a substitute for migration SQL.

## Current remote schema fingerprint
Read-only catalog query returned:
- 10 public base tables, all RLS enabled.
- 22 public RLS policies.
- 22 public indexes.
- 15 authenticated table-grant rows.
- 12 security-sensitive function definitions inspected; their MD5 fingerprints are recorded in the migration manifest.
- The six restore RPCs are present. The current live definitions for owner-scoped restore, commit-ledger checks, sync operations, and Admin feed are fingerprinted; this is not a full schema dump.

## Admin Edge Function
- Live `admin-users` remains ACTIVE, version 3, `verify_jwt=false` (the function performs explicit bearer-token validation with `auth.getUser`).
- Live v3 checks active Admin profile (`role=admin`, `is_active=true`) but does not explicitly check `app_admins` link.
- Candidate v4 adds a service-role lookup of `app_admins` after validating the caller's identity/profile. The check is appropriate because service-role queries bypass RLS.
- Candidate v4 is source-only and NOT deployed. The active source remains v3 to avoid source/live mismatch. Deployment was previously blocked by the platform safety guard; no bypass or alternate deployment path was attempted in this step.
- No additional user permission was needed for the read-only audit. If a manual Dashboard deployment becomes necessary, request the user's approval before that action.

## Live table shape observed
- `profiles`: 2 rows; `app_admins`: 1 row.
- `parts`, `movements`, `sync_operations`, `inventory_sync_events`, `inventory_restore_sessions`, `inventory_restore_chunks`, `inventory_restore_commits`: 0 rows each.
- `app_update_config`: 1 row.
- All 10 public base tables reported RLS enabled.
- This preserves the intended clean test inventory while retaining the two login profiles and Admin link.

## Not claimed
- No migration replay, database modification, Edge Function deployment, APK build/install, Android framework compile, device test, QR scan test, or GitHub push occurred.
