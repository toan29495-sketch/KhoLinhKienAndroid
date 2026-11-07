# Supabase remote migration manifest

Project: `ihhxxxqojelbxisreqns`  
Last audited: 2026-11-04

The live Supabase migration history currently contains:

## Step138 (active SQL files are present under `supabase/migrations/`)
- `20261103094857_step138_01_complete_password_change`
- `20261103094931_step138_02_backend_foundation_and_security`
- `20261103095005_step138_03_inventory_sync_and_admin_feed`
- `20261103095045_step138_04_fix_part_conflict_snapshot_id`
- `20261103095057_step138_05_require_active_admin_profile_for_admin_link`
- `20261103095135_step138_06_revoke_anon_password_change_rpc`
- `20261103095727_step138_07_admin_feed_requires_admin_link`

## Step139 (remote catalog objects present; migration-history provenance unresolved)
- `20261103100431_step139_01_restore_staging_sessions_and_chunks`
- `20261103100503_step139_02_atomic_restore_commit_and_snapshot_readers`
- `20261103100517_step139_03_restore_staging_quotas_and_cleanup`
- `20261103100523_step139_04_single_restore_commit_per_owner`
- `20261103100558_step139_05_require_admin_link_for_global_feed`
- `20261103100637_step139_06_require_commit_ledger_and_admin_link`
- `20261103101001_step139_07_index_restore_and_config_foreign_keys`
- `20261103101435_step139_08_reduce_redundant_rls_policies_and_initplans`
- `20261103101525_step139_09_make_admin_link_read_only_to_clients`

**Important correction from the 2026-11-04 live migration-history query:** none of the nine Step139 version strings above appeared in `list_migrations`, although the corresponding restore staging/commit/snapshot tables and RPCs are present in the live catalog. Do not describe Step139 as confirmed in the remote migration history. The objects are deployed/present; the exact migration-history provenance and original SQL transaction boundaries remain unresolved.

## Important
- This file is a manifest, not executable SQL.
- Do not replay files in `migrations_pending_reconciliation` as a batch.
- Do not use blanket `db push` against the manually bootstrapped remote schema.
- Exact Step139 SQL must be exported/reconstructed from the remote live definitions before a clean reproducible migration chain can be handed off.

## Step142 read-only remote fingerprint (2026-11-03)
The current remote catalog was inspected without changing it. It reported 10 public base tables (all RLS enabled), 22 policies, 22 indexes, and 15 authenticated table-grant rows. This is a current-state fingerprint only, not a schema dump and not a replacement for the original migration SQL.

Security-sensitive function fingerprints (`md5(pg_get_functiondef(...))`):

| Function | Identity arguments | Definition MD5 |
|---|---|---|
| `apply_inventory_operation` | `p_operation_id uuid, p_entity_type text, p_entity_id uuid, p_payload jsonb` | `7d9e7271add6214abd3202d7f89a0fb6` |
| `begin_inventory_restore` | `p_restore_id uuid, p_manifest_sha256 text, p_expected_part_count integer, p_expected_movement_count integer, p_expected_part_chunks integer, p_expected_movement_chunks integer` | `3ba8c07306bad56b5c8648fc379f3a95` |
| `cleanup_my_inventory_restore_staging` | none | `fb583d3f8d272965fce8c949c7dbeab5` |
| `commit_inventory_restore` | `p_restore_id uuid` | `d6760158a3d83d57ba5e80dc9ce459d9` |
| `complete_password_change` | none | `5b16b595149b9def2fcdb5822202fc0a` |
| `get_inventory_part_conflict_snapshot` | `p_part_sync_id uuid` | `e21dff501ee257a2d54cb6d8aecd263a` |
| `is_app_admin` | none | `0927b84a1a88a600ffabd0bbd1764ff1` |
| `pull_admin_movement_changes` | `p_after_event_id bigint, p_limit integer` | `367870b754a48cfc5187918e4b1723d5` |
| `pull_inventory_changes` | `p_after_event_id bigint, p_limit integer` | `47c5c4488bd02c5760af9b190e7c5bef` |
| `read_inventory_restore_snapshot_chunk` | `p_restore_id uuid, p_entity_type text, p_chunk_index integer` | `34d3f71a062f7ec98561f04022f19c2e` |
| `stage_inventory_restore_chunk` | `p_restore_id uuid, p_entity_type text, p_chunk_index integer, p_payload jsonb` | `d562368841884e1bf4465518d51cb26b` |
| `verify_inventory_restore_snapshot_chunk` | `p_restore_id uuid, p_entity_type text, p_chunk_index integer, p_payload jsonb` | `87340355bfbf3608e933db79ae326af6` |

These hashes can identify drift in the inspected function bodies. They do not prove the complete database schema is identical, and MD5 is used here only as a compact drift fingerprint, not as a security primitive.

The live Edge Function was separately re-read: `admin-users` is ACTIVE at version 3 with `verify_jwt=false`; its handler validates the bearer token with `auth.getUser`. The v4 candidate remains undeployed and adds the explicit `app_admins` membership check. Do not claim it is live until deployment is confirmed from Supabase.


## Step187 (deployed remotely; source files now reconciled)
- `20261103150733_step187_weekly_backup_backend` — private `inventory-backups` bucket (256 MiB, ZIP only), owner-scoped backup metadata table, Storage INSERT/SELECT policies, authenticated `begin_inventory_backup`, `complete_inventory_backup`, `list_my_inventory_backups`, and admin status RPC.
- `20261103150823_step187_01_fix_backup_registration_ambiguity` — fixes a PL/pgSQL `backup_id` ambiguity discovered by the first authenticated RPC test.
- `20261103151110_step187_02_deny_direct_backup_metadata_access` — adds a restrictive deny-all policy for `anon` and `authenticated` as defense in depth; clients still have no table grants, and guarded RPCs remain the intended access path.
- Live project verification: bucket settings, table, RLS state, RPC execute grants and Storage policies match the intended contract. `anon` has no execute on backup RPCs; table grants to client roles are revoked.
- Authenticated role tests: Admin can register a PENDING backup and list only its own row; a second KTV sees zero rows; a KTV call to the admin status RPC fails with `ADMIN_REQUIRED`. Test metadata rows were removed after each test; no Storage object or business data was created.
- Full TUS upload, authenticated object download, byte/SHA-256 readback, completion transition, and Android reinstall restore still require an actual app session and remain unverified.


## Step204–Step205 (confirmed in remote migration history; source reconstructed)
The 2026-11-04 `list_migrations` result confirms these four remote versions:
- `20261103161808_step204_enforce_forced_password_change`
- `20261103162008_step204_01_reject_temporary_password_completion`
- `20261103162229_step205_admin_account_audit_log`
- `20261103162458_step205_01_limit_audit_table_mutations`

The original SQL files were absent from the Step203 ZIP. The live catalog was inspected read-only and source reconstructions were placed under `supabase/remote_migration_snapshots/`, outside the active migration directory. These are not byte-for-byte original SQL and must not be replayed.

### Step204 current live contract
- `password_reset_guard` is RLS-enabled, has no client grants/policies, and grants service_role SELECT/INSERT/UPDATE/DELETE.
- `auth.users` has an `AFTER UPDATE OF encrypted_password` trigger `on_auth_user_password_changed` invoking `public.handle_forced_password_change()`.
- The trigger clears `profiles.must_change_password` only if the password hash changed, the new password is not the temporary `000000`, the suppression guard is not active, and the profile is active and currently flagged.
- `is_active_profile()` and `is_app_admin()` both require `must_change_password=false`; Admin authorization also requires the immutable `app_admins` link and role `admin`.
- `complete_password_change()` is verification-only: it checks that the Auth-triggered state transition has already happened; it no longer clears the flag itself.

### Step205 current live contract
- `admin_audit_events` is RLS-enabled with no client policies; direct table access is denied to anon/authenticated.
- service_role has SELECT/INSERT/UPDATE but no DELETE. Audit events enforce the allowed action/status values and STARTED/finished_at consistency.
- `list_admin_audit_events(integer)` is SECURITY DEFINER, requires active Admin authorization, and caps reads at 200 rows. EXECUTE is granted to authenticated/service_role, not anon.
- Deployed `admin-users` Edge Function is ACTIVE version 6, `verify_jwt=false`; handler manually validates the bearer token with `auth.getUser`, then verifies active Admin, `must_change_password=false`, and `app_admins` membership. It writes STARTED/SUCCEEDED/FAILED audit records and uses `password_reset_guard` during password reset.
- Live function bundle fingerprint reported by Supabase: `6fd84795d68c6f71a827764757edda112a56146d1f2113ae21e0200a368b94ca`. The corresponding source was mirrored into `supabase/functions/admin-users/index.ts`.

### Remaining source/client gaps
- `AdminScreen.kt` and `AuthRepository.kt` do not yet expose `list_admin_audit_events`; Step205 backend audit exists, but there is no in-app audit-history screen.
- No live account-management mutation or password-change test was run during this read-only reconciliation.
- The source snapshots are reconstructed from catalog definitions; original migration SQL is not recovered.

### Step204/205 advisor snapshot (2026-11-04)
- Security advisor: six RLS-enabled/no-policy tables (including the internal audit/guard tables and restore/sync staging tables); 20 authenticated-executable `SECURITY DEFINER` functions; leaked-password protection disabled. No setting or grant was changed during this audit.
- Performance advisor: two unindexed foreign keys on `admin_audit_events` (`actor_id`, `target_user_id`) and six currently unused indexes. No indexes were added or removed.


## Step220 — Owner-scoped warehouse wipe (2026-11-07)
- Applied active migration 20261107100000_owner_scoped_warehouse_wipe. It adds the authenticated SECURITY DEFINER RPC purge_my_inventory_data(), whose deletion predicate is always auth.uid(); it preserves profiles/auth.users, app_admins and admin audit history.
- Applied active migration 20261107101500_warehouse_wipe_mutation_fence. It adds private table inventory_warehouse_wipes, blocks INSERT/UPDATE on eight owner-scoped inventory/sync/restore/backup tables while a wipe is pending, and exposes finish_my_inventory_wipe() to release the fence only after cleanup.
- Live read-only verification: both RPC signatures exist, the wipe-lock table exists, and eight named mutation guard triggers are installed.
- Edge Function purge-own-warehouse is ACTIVE version 2 with verify_jwt=true. It validates the caller token/profile, calls the database purge with the caller JWT, uses service-role Storage API only to remove objects under the caller's UUID folder in part-images and inventory-backups, then calls finish_my_inventory_wipe(). A failed Storage cleanup leaves the mutation fence in place so the same account can retry.
- No production warehouse rows, Storage objects, backup records, user profiles or Auth accounts were deleted while implementing/deploying these server-side capabilities. Actual wipe behavior remains untested and must be tested only with a disposable owner account.
