# Step138 – Live Supabase backend alignment and initial sync deployment (2026-11-03)

## Source baseline
- Continued from the exact Step137 ZIP after verifying SHA-256 `16377d3764f859a3e97a2ce653dade4fbd33b658f1ead552aeeee7da46b7af79` and ZIP integrity.
- Project: `kiem-soat-linh-kien` / `ihhxxxqojelbxisreqns`, Supabase Cloud, PostgreSQL 17.11, ap-southeast-1.
- Confirmed old Android app had already been deleted; this test cycle begins with fresh local app state and fresh QR registration. No server rows were deleted.

## Live Supabase state before Step138
- `profiles=2` (one active Admin, one active KTV); `app_admins=1`; Auth users=2; no orphan Auth/profile records.
- `parts=0`, `movements=0`, `sync_operations=0`, Storage objects=0.
- Existing tables were bootstrapped manually and had no tracked migrations. The project already had 6 public tables, 15 public RLS policies, and one `admin-users` Edge Function v2.
- Existing image bucket was named `part-images.` (trailing period); Android code expects `part-images`.
- Required offline-sync RPCs were absent. Existing bootstrap `parts` table had a full unique `(owner_id, code)` constraint, which would prevent reusing a soft-deleted QR.
- The old Edge Function attempted to insert `profiles` after the `auth.users` trigger had already inserted that profile, causing a duplicate primary-key failure on KTV creation.

## Live changes deployed (seven tracked migrations)
1. `20261103094857_step138_01_complete_password_change`: deployed authenticated-only password completion RPC; explicitly revokes PUBLIC/anon.
2. `20261103094931_step138_02_backend_foundation_and_security`: created the correct private `part-images` bucket (20 MiB, JPEG/PNG/WebP); added sync-operation ledger columns and `inventory_sync_events`; installed active-profile guards, profile write blocks, owner-only direct inventory policies, private owner-folder image guard, authenticated image-delete deny, active-only QR unique index, and revoked client execution from trigger/event-trigger functions. Existing typo bucket was retained, not deleted.
3. `20261103095005_step138_03_inventory_sync_and_admin_feed`: deployed `apply_inventory_operation`, owner-scoped `pull_inventory_changes`, redacted Admin `pull_admin_movement_changes`, and transaction-serialized event ID allocation. Includes idempotency, expected-server-version CAS, owner fencing and Admin PART-to-SKIP redaction.
4. `20261103095045_step138_04_fix_part_conflict_snapshot_id`: corrected conflict snapshot lookup to use `parts.id` (the live table has no `sync_id` column).
5. `20261103095057_step138_05_require_active_admin_profile_for_admin_link`: `is_app_admin()` now requires both the `app_admins` link and an active profile whose role is still `admin`; a stale Admin link cannot preserve Admin RLS privileges after role downgrade.
6. `20261103095135_step138_06_revoke_anon_password_change_rpc`: explicit anon revocation for the password completion RPC.
7. `20261103095727_step138_07_admin_feed_requires_admin_link`: Admin global feed now also calls `is_app_admin()`, requiring both current Admin role/active status and the `app_admins` link.

## Edge Function
- Deployed `admin-users` version 3, ACTIVE, with JWT verification still disabled as before because the handler explicitly validates the bearer token using `auth.getUser`.
- KTV creation now updates the profile row created by the `auth.users` trigger rather than attempting a second insert. Existing admin role/active checks and action allowlist remain.
- No new Auth user was created during testing.

## Live verification
- Read-only post-deploy checks confirmed `parts=0`, `movements=0`, `sync_operations=0`, `inventory_sync_events=0`; the two existing accounts and Admin link remain intact.
- Correct private bucket `part-images` exists; old `part-images.` bucket remains untouched; both have zero objects.
- RLS is enabled on `inventory_sync_events`; direct grants to anon/authenticated are revoked.
- Transaction-rollback integration test exercised a real `PART_UPSERT`, `STOCK_CHANGE`, owner pull and Admin global pull; verified expected quantity, three ordered feed events, Admin PART redaction to SKIP, then rolled back the transaction. No test part, movement or outbox record persisted.
- Impersonated the active KTV inside a rolled-back SQL test: `is_app_admin()` returned false and Admin global feed rejected the call with `ADMIN_REQUIRED`. Then simulated demoting the actual Admin profile to `ktv` inside a rolled-back transaction while deliberately leaving its `app_admins` row intact; `is_app_admin()` still returned false, Admin global feed rejected it, and the personal owner feed remained available. Active Admin feed succeeded. Conflict snapshot returned null for a non-existent part UUID.
- Edge Function v3 is confirmed ACTIVE. No live KTV creation/reset/lock action was run because it would mutate Auth accounts.
- Static verifier `tools/verify_step138_supabase_remote_alignment.py` checks the source/migration contracts.

## Migration history reconciliation
- The remote database had an empty migration history despite pre-existing schema. Seven new reviewed migrations are now tracked remotely and represented in the active `supabase/migrations` directory.
- The 25 original migration files are preserved under `supabase/migrations_pending_reconciliation/` rather than being blindly replayed. Their full restore chain is not deployed yet. Do not run `supabase db push` against the archived set or move it back wholesale.
- Corrected the archived conflict snapshot migration to use `parts.id`; corrected the archived QR unique migration to remove the legacy full unique constraint before creating the active-only partial unique index. Existing files remain preserved for audit.

## Remaining blockers before full app test
- Cloud restore is NOT ready: `begin_inventory_restore`, `stage_inventory_restore_chunk`, `commit_inventory_restore`, `read_inventory_restore_snapshot_chunk`, `verify_inventory_restore_snapshot_chunk`, and `cleanup_my_inventory_restore_staging` are still absent. Do not test cloud backup/restore until the remaining restore migration chain is reconciled and deployed.
- `get_advisors(security)` still reports eight authenticated SECURITY DEFINER helper/RPC functions. These are the intentionally authenticated-only RPCs/helpers that must run as the function owner to enforce owner/role checks while accessing the locked sync ledger/event table; no anon execution is granted. Leaked-password protection is disabled. `get_advisors(performance)` reports auth RLS initplan/multiple permissive policy warnings, one unindexed FK, and an unused index. These require a separate review; no risky blanket revokes were applied.
- The existing app update bucket/config remain as found; no APK URL/version was changed.
- No Android Room annotation processing, Compose runtime, WorkManager runtime, Android framework compile, emulator/device test, APK build/install, GitHub push or Gradle use.

## Step138 test status
- Static verifier: 24/24 PASS.
- Live SQL transaction tests: Admin/owner pull, inventory mutation, stock movement, redaction, KTV role gate and conflict snapshot passed; transaction rolled back.
- Prior regression suites were rerun. Any test updated for the corrected `parts.id` key must assert the live schema's primary key, not the obsolete `sync_id` column.
