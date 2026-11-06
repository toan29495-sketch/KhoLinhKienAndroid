# Step139 – Cloud Restore backend, ownership and RLS hardening (2026-11-03)

## Baseline and scope
- Continued from Step138 ZIP with verified SHA-256 `f1b0825246f818175fd4fa5672c51bb09dc4b74c23cce019d02af529d6f69b58`.
- Supabase project `ihhxxxqojelbxisreqns`; no account, inventory, movement, Storage object or committed restore data was deleted or created.
- Changes were deployed as nine additive migrations recorded in the live Supabase migration history:
  1. `step139_01_restore_staging_sessions_and_chunks`
  2. `step139_02_atomic_restore_commit_and_snapshot_readers`
  3. `step139_03_restore_staging_quotas_and_cleanup`
  4. `step139_04_single_restore_commit_per_owner`
  5. `step139_05_require_admin_link_for_global_feed`
  6. `step139_06_require_commit_ledger_and_admin_link`
  7. `step139_07_index_restore_and_config_foreign_keys`
  8. `step139_08_reduce_redundant_rls_policies_and_initplans`
  9. `step139_09_make_admin_link_read_only_to_clients`

## Restore backend deployed
- Added owner-bound `inventory_restore_sessions`, `inventory_restore_chunks`, and `inventory_restore_commits`.
- Added authenticated-only RPCs:
  - `begin_inventory_restore`
  - `stage_inventory_restore_chunk`
  - `commit_inventory_restore`
  - `read_inventory_restore_snapshot_chunk`
  - `verify_inventory_restore_snapshot_chunk`
  - `cleanup_my_inventory_restore_staging`
- Session and chunk staging tables have RLS enabled and no direct client policies or grants. RPCs use `auth.uid()` and active-profile checks.
- Restore staging enforces at most one active session per owner, at most 8,192 combined chunks, at most 1 MiB per chunk and 256 MiB aggregate staging payload. STAGING sessions older than 14 days can be cleaned only by their owner; committed snapshots are retained.
- Commit is owner-scoped, serialized against normal inventory mutations, checks completeness and chunk indexes before replacing that owner's rows, keeps deleted-part movement history (`part_id` may remain NULL), writes an immutable snapshot ledger and publishes one RESTORE_COMMIT cursor event.
- Snapshot read and verify require a committed session and matching owner commit ledger.
- No real restore was executed against persistent user data. Integration tests ran inside SQL transactions and rolled back.

## Admin and RLS hardening
- Admin global feed now requires `is_app_admin()` (active profile + current `admin` role + `app_admins` link), not only a role string.
- `is_active_admin_profile()` delegates to the same linked-Admin predicate.
- Client access to `app_admins` is read-only; only a linked Admin can read it. Clients cannot insert/update/delete the Admin link.
- Removed redundant broad Admin ALL policies from `movements`, `profiles` and `sync_operations`. Admin global history remains available through the redacted RPC; direct movement access remains owner-scoped and append/read-only.
- Rewrote remaining owner/profile policies to use initplan-stable `(select auth.uid())` / `(select is_app_admin())`.
- Added indexes for restore/config foreign keys. Because the test database has no real restore or update-config workload, the advisor currently reports these indexes as unused; retain them for FK lookup paths and re-evaluate after real workload.

## Live verification
- All six required restore RPCs now exist. All required sync/password/restore RPCs deny `anon` and grant `authenticated`.
- Transaction rollback test covered:
  - Begin + stage PART/MOVEMENT chunks.
  - Atomic commit.
  - A movement whose QR/part no longer exists is retained with `part_id=NULL`.
  - Immutable snapshot read + checksum verification.
  - Cross-owner snapshot read denied.
- Additional transaction tests covered:
  - Exact chunk retry returns `already_staged`.
  - A second active restore session for the same owner is rejected.
  - Admin role without `app_admins` link is rejected by global feed.
  - Admin can read both profiles; KTV can read only its own profile.
  - KTV cannot read another owner's inventory or Admin restore snapshots.
  - Admin link cannot be deleted by a client.
  - PART_UPSERT + STOCK_CHANGE + owner pull + Admin pull work together; Admin feed maps PART to SKIP and exposes movement only.
- Every test transaction was rolled back. Final live counts: 2 profiles (1 Admin, 1 KTV), 1 Admin link, 0 parts, 0 movements, 0 sync operations/events, 0 restore sessions/chunks/commits, 0 Storage objects.

## Advisor status and remaining items
- Performance Advisor no longer reports RLS initplan or multiple permissive policy warnings. It reports only unused indexes, expected on an empty test project.
- Security Advisor reports internal tables with RLS enabled and intentionally no direct policies; direct client grants are revoked. It also reports 14 authenticated SECURITY DEFINER functions: these are the intended checked RPCs/helpers; `anon` has no EXECUTE.
- Leaked-password protection remains disabled. Do not enable while the app still creates KTV accounts with the shared temporary password `000000`; first replace that onboarding flow with a generated temporary credential or another safe invitation method.
- `admin-users` Edge Function remains ACTIVE at version 3. A proposed version 4 link-check hardening was not deployed; the database now makes the Admin link read-only to clients and checks the link in all database Admin RPC/RLS paths. Edge Function role check remains a defense-in-depth follow-up.
- Cloud photo garbage collection is intentionally not implemented. Client-side deletes remain denied because operation history, event payloads and retained restore snapshots can still reference old objects.
- No Android framework compile, Room/Compose/WorkManager runtime, device test, APK build/install or GitHub push was performed. No Gradle was used.

## Outcome
Cloud restore RPC surface and security boundaries are now deployed and transaction-tested. This is not yet a release-ready APK and does not replace end-to-end Android restore/network/process-kill testing.
