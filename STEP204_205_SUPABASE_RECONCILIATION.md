# Step204–Step205 Supabase reconciliation

Date: 2026-11-04  
Baseline: `KhoLinhKienAndroid-step203-admin-ui-hardening-20261104.zip`  
Remote project: `ihhxxxqojelbxisreqns` (`kiem-soat-linh-kien`)  
Scope: read-only remote inspection + local source reconciliation. No deploy or database mutation.

## Findings

1. Remote migration history confirms the four migrations:
   - `20261103161808_step204_enforce_forced_password_change`
   - `20261103162008_step204_01_reject_temporary_password_completion`
   - `20261103162229_step205_admin_account_audit_log`
   - `20261103162458_step205_01_limit_audit_table_mutations`
2. The Step203 ZIP did not contain these migration source files or the deployed Step205 audit implementation.
3. Live Step204 behavior:
   - `password_reset_guard` is RLS-enabled; anon/authenticated have no table privileges; service_role has SELECT/INSERT/UPDATE/DELETE.
   - `auth.users` has trigger `on_auth_user_password_changed`, AFTER UPDATE OF encrypted_password.
   - `handle_forced_password_change()` clears the profile flag only when the new hash is not the temporary `000000`, no suppression guard is active, and the profile is active and flagged.
   - `is_active_profile()` requires active and `must_change_password=false`.
   - `is_app_admin()` requires active profile, role admin, `must_change_password=false`, and an `app_admins` link.
   - `complete_password_change()` is verification-only; it does not clear the flag. It raises `PASSWORD_CHANGE_NOT_CONFIRMED` unless the Auth trigger already cleared it.
4. Live Step205 behavior:
   - `admin_audit_events` is RLS-enabled with no client policies.
   - anon/authenticated have no direct table privileges. service_role has SELECT/INSERT/UPDATE, not DELETE.
   - action/status/finish-state constraints and created/target indexes are present.
   - `list_admin_audit_events(p_limit)` requires active Admin, limits 1..200, returns newest events, and is executable by authenticated/service_role but not anon.
5. `admin-users` Edge Function is ACTIVE version 6 with `verify_jwt=false`. Its handler manually validates the bearer token through `auth.getUser`, verifies active Admin + completed password change + `app_admins` membership, and writes STARTED/SUCCEEDED/FAILED audit rows. Password reset uses the short-lived `password_reset_guard` around the Auth password update.
6. Step203 source had an older `admin-users` handler without the forced-password check, audit writes, or reset guard. The current deployed version 6 source has now been mirrored into `supabase/functions/admin-users/index.ts`.
7. Admin UI gap: `AdminScreen.kt`/`AuthRepository.kt` do not call `list_admin_audit_events`; audit history is stored and guarded on the backend but is not viewable in the app yet.
8. Migration-history provenance correction: the current remote `list_migrations` output did not include the nine Step139 version strings, although restore staging/commit/snapshot tables and RPCs exist. Do not claim Step139 appears in remote migration history until separately reconciled.

## Source artifacts added

- `supabase/remote_migration_snapshots/20261103161808_step204_enforce_forced_password_change.sql`
- `supabase/remote_migration_snapshots/20261103162008_step204_01_reject_temporary_password_completion.sql`
- `supabase/remote_migration_snapshots/20261103162229_step205_admin_account_audit_log.sql`
- `supabase/remote_migration_snapshots/20261103162458_step205_01_limit_audit_table_mutations.sql`
- `supabase/remote_migration_snapshots/README.md`

These SQL files are reconstructions from the live catalog, not recovered original migration text. They live outside `supabase/migrations/` and must not be executed or replayed against production.

## Supabase Advisors (read-only, 2026-11-04)

- Security: 6 RLS-enabled tables have no policies (`admin_audit_events`, restore staging/commit/session tables, `inventory_sync_events`, `password_reset_guard`). For the audit and guard tables this is intentional deny-all direct access; guarded RPC/service-role paths are used. The restore/sync tables need their owner/RPC access paths reviewed as part of the broader Step139 provenance reconciliation.
- Security: 20 `SECURITY DEFINER` functions are executable by `authenticated`. This is an advisor warning, not proof of an exploit. Each function must be reviewed for owner binding, role checks, bounded inputs, qualified names and grants. `list_admin_audit_events` was inspected and has active-Admin checks plus a 1..200 limit.
- Security: Supabase Auth leaked-password protection is disabled. This is an account-level setting and was not changed.
- Performance: `admin_audit_events.actor_id` and `target_user_id` foreign keys lack covering indexes. Six indexes were reported unused; do not remove them solely from this early usage snapshot.

## Verification limits

- Remote inspection was read-only. No migration, SQL, Edge Function deployment, Auth mutation, Storage mutation, or inventory operation was performed.
- No live Admin/KTV mutation or password-change test was performed.
- The remote Edge Function bundle fingerprint is recorded in `supabase/REMOTE_MIGRATION_MANIFEST.md`; local source is mirrored from the returned deployed function content.
- No Deno/TypeScript compiler or Kotlin/Android build was run in this phase.
