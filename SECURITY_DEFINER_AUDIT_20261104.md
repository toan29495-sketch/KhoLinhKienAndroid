# Supabase SECURITY DEFINER audit — 2026-11-04

Project: `ihhxxxqojelbxisreqns`. This is a read-only catalog audit; no SQL, grants, policies, data, Auth users, or Storage objects were changed.

## Scope and corrected count

The live catalog query returns **27** `SECURITY DEFINER` functions in schema `public` (not 20 or 25). The earlier Advisor summary count was not the complete catalog count. Current privilege query:

- `anon` EXECUTE: **0/27**
- `PUBLIC` EXECUTE: **0/27**
- `authenticated` EXECUTE: **20/27**
- Remaining 7 are internal trigger/event-trigger functions with no anon/authenticated EXECUTE grant.
- All 27 have a fixed `search_path` setting; several intentionally use an empty path and schema-qualify objects.

## Internal trigger-only functions

The following have no anon/authenticated EXECUTE and are attached to database triggers:

- `assign_inventory_sync_event_commit_order()` — BEFORE INSERT on `inventory_sync_events`; serializes commit-order assignment.
- `enforce_inventory_restore_chunk_quota()` — BEFORE INSERT on `inventory_restore_chunks`; per-owner chunk/byte quota.
- `enforce_inventory_restore_session_quota()` — BEFORE INSERT on `inventory_restore_sessions`; per-owner active-session and chunk quota.
- `enforce_single_inventory_restore_commit()` — BEFORE status transition STAGING → COMMITTED on `inventory_restore_sessions`.
- `handle_forced_password_change()` — AFTER UPDATE encrypted_password on `auth.users`; clears forced-change flag only for non-temporary password and absent reset suppression.
- `handle_new_user()` — AFTER INSERT on `auth.users`; creates KTV profile with forced password change.

`rls_auto_enable()` is an event-trigger function, not a client-callable RPC. It is configured with `search_path=pg_catalog`; catalog query showed no anon/authenticated EXECUTE.

## Authenticated RPCs

20 functions are executable by `authenticated`. Catalog-level checks show no anon/PUBLIC execution. The application-facing owner-scoped operations inspected in this pass use `auth.uid()` and active-profile checks, and constrain reads/writes to the caller's owner ID. Admin RPCs inspected require active Admin checks through `is_active_profile()` + `is_app_admin()` or equivalent. Helper predicates (`is_active_profile`, `is_app_admin`, `is_active_admin_profile`) necessarily return authorization state for the current JWT identity.

Reviewed in detail in this pass:

- `complete_password_change()` — checks authenticated identity and requires the Auth-triggered profile state to already be active with `must_change_password=false`; it does not itself clear the flag.
- `list_admin_audit_events(integer)` — active Admin gate, 1..200 limit, newest-first; no direct table access for clients.
- `list_inventory_backup_status_admin()` — active Admin gate.
- `pull_admin_movement_changes(bigint,integer)` — active Admin gate and cursor pagination.
- `pull_inventory_changes(bigint,integer)` — active-profile gate and owner filter.
- `read_inventory_restore_snapshot_chunk(...)` and `verify_inventory_restore_snapshot_chunk(...)` — active-profile gate, owner-scoped restore session and committed snapshot checks.
- `stage_inventory_restore_chunk(...)`, `begin_inventory_restore(...)`, `commit_inventory_restore(...)`, `cleanup_my_inventory_restore_staging()` — active-profile gate and owner-scoped restore session paths.
- `begin_inventory_backup(...)`, `complete_inventory_backup(...)`, `list_my_inventory_backups()`, `can_upload_inventory_backup_object(text)` — active-profile/owner-scoped backup access.
- `apply_inventory_operation(...)` — active-profile gate, `auth.uid()` owner binding, idempotency and payload conflict checks.
- `get_inventory_part_conflict_snapshot(uuid)` — active-profile and owner-scoped conflict lookup.

## Remaining limitations / follow-up

The catalog-level static review is complete for all 20 authenticated-callable RPC definitions (see the RPC-by-RPC table below), and the six trigger attachments plus event-trigger classification were checked. This is not adversarial runtime testing and does not prove every branch safe under malicious inputs, concurrent requests, or compromised service-role credentials. Authenticated runtime tests, malicious-input/fault-injection tests, and concurrency tests remain outstanding. Keep the internal trigger functions non-callable to anon/authenticated. Do not grant broad EXECUTE to PUBLIC to silence advisor notices.

The previous advisor notice about 20 `SECURITY DEFINER` functions is not itself a vulnerability finding. This audit found 27 total; zero are executable by anon or PUBLIC. Do not change grants solely to reduce the count.

## Completed RPC-by-RPC source review (catalog definitions, 2026-11-04)

The 20 authenticated-callable functions were individually retrieved from `pg_get_functiondef` and reviewed for identity binding, active-profile/Admin checks, owner filters, parameter bounds, and fixed search paths:

| RPC | Gate / scope observed | Review note |
|---|---|---|
| `apply_inventory_operation` | `auth.uid()`, active profile, owner-bound ledger/part/movement writes, idempotency, expected server version | Owner isolation and conflict checks present. Runtime concurrency testing remains required. |
| `begin_inventory_backup` | `auth.uid()`, active profile, owner-bound backup ID/path, metadata bounds and checksum format | Size cap 256 MiB; metadata is immutable on replay. |
| `complete_inventory_backup` | `auth.uid()`, active profile, owner-scoped metadata row lock, expected checksum and Storage object owner/size | Important trust boundary: server checks object size and client-supplied SHA-256 equality, not the bytes' SHA-256 independently. `VERIFIED` therefore depends on the official client having performed authenticated download + hash readback; a hostile custom client can call the RPC directly with the stored hash if object size matches. Consider server-side Edge verification if this trust model is unacceptable. No production change made. |
| `can_upload_inventory_backup_object` | active profile + `auth.uid()` owner + PENDING path + upload authorization expiry | Owner-scoped Storage predicate. |
| `list_my_inventory_backups` | owner filter + active-profile predicate | Unauthenticated identity returns no rows. |
| `list_inventory_backup_status_admin` | active profile + active Admin; system-wide backup metadata | Admin-only; result currently unpaginated, so growth should be monitored. |
| `begin_inventory_restore` | active profile, owner/restoreId lock, manifest hash and count/chunk bounds, owner-scoped resume | Session quota trigger serializes owner staging quota. |
| `stage_inventory_restore_chunk` | active profile, owner/session lock, STAGING status, entity/index/array/row/byte bounds, exact replay conflict check | Per-owner chunk/byte quota trigger also serializes inserts. |
| `commit_inventory_restore` | active profile, owner/session lock, commit ledger, complete contiguous chunks, payload validation before owner-scoped replacement | Destructive replacement is in one Postgres transaction; any later failure rolls back. Must still test malformed/adversarial payloads and runtime rollback. |
| `cleanup_my_inventory_restore_staging` | active profile + `auth.uid()` owner; only own STAGING sessions older than 14 days | Does not clean other owners or committed sessions. |
| `read_inventory_restore_snapshot_chunk` | active profile, owner-scoped COMMITTED session + commit ledger + chunk index | Only returns own committed snapshot. |
| `verify_inventory_restore_snapshot_chunk` | active profile, owner-scoped COMMITTED session + commit ledger + exact stored payload comparison | Caller can verify only own stored chunk. |
| `pull_inventory_changes` | active profile + `owner_id=auth.uid()` + cursor | Page size clamped 1..500. |
| `pull_admin_movement_changes` | active profile + Admin gate + cursor | Page size clamped 1..500; PART events are intentionally emitted as SKIP markers so cursor advances without exposing part snapshots. |
| `get_inventory_part_conflict_snapshot` | active profile + part ID and `owner_id=auth.uid()` | Returns only the caller's part row. |
| `complete_password_change` | `auth.uid()` and profile must already be active with forced-change flag false | Verification-only; cannot clear the flag itself. |
| `is_active_profile` | identity-bound profile predicate | Boolean helper only. |
| `is_app_admin` | identity-bound immutable `app_admins` membership + active role + forced-change false | Boolean helper only. |
| `is_active_admin_profile` | delegates to `is_app_admin` | Boolean helper only. |
| `list_admin_audit_events` | `auth.uid()`, active profile, active Admin, limit 1..200 | Read-only audit RPC; no direct client table grants. |

Internal 7 functions: 6 trigger functions plus the `rls_auto_enable` event-trigger function. All are non-executable by `anon` and `authenticated`, and have fixed search paths. Trigger attachment was confirmed for all six trigger functions; the event-trigger function has no client EXECUTE privilege.

## Outstanding security/performance items

- No adversarial runtime tests were run with authenticated sessions. Static catalog review cannot prove every branch under malicious JSON, races, or revoked sessions.
- The backup `VERIFIED` semantics above are the one concrete trust-model caveat. It is documented, not silently changed, because moving byte hashing server-side requires an explicit design/deployment decision and may affect large-object limits/cost.
- `admin_audit_events` has indexes `(created_at DESC,id DESC)` and `(target_username,created_at DESC)`, but no index on either FK `actor_id` or `target_user_id`. Additive indexes are candidates; no DDL was applied. Check actual row counts/query plans before adding them.
- `list_inventory_backup_status_admin()` is not paginated. Consider bounded pagination if backup metadata grows materially.
- No grants were broadened and no Advisor warnings were suppressed by weakening access control.
