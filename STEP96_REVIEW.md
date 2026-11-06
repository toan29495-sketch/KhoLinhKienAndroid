# Step96 – Pin restore and Admin-feed RPC execute grants (2026-11-03)

## Scope
Review SECURITY DEFINER RPC entry points used by restore staging, committed snapshot reads, snapshot verification, and the Admin global movement feed. Ensure anonymous callers cannot inherit or retain a direct execute grant and preserve authenticated-only invocation.

## Findings and changes
- The reconstructed migrations revoked `PUBLIC` and granted `authenticated` for the restore RPCs and Admin global feed, but did not explicitly revoke a possible historical direct grant to `anon`.
- Added additive migration `202611030008_harden_restore_rpc_execute_scope.sql`.
- Explicitly revoke all privileges from `PUBLIC`, `anon`, and `authenticated`, then grant only `EXECUTE` to `authenticated` for:
  - `begin_inventory_restore(uuid,text,integer,integer,integer,integer)`
  - `stage_inventory_restore_chunk(uuid,text,integer,jsonb)`
  - `commit_inventory_restore(uuid)`
  - `read_inventory_restore_snapshot_chunk(uuid,text,integer)`
  - `verify_inventory_restore_snapshot_chunk(uuid,text,integer,jsonb)`
  - `pull_admin_movement_changes(bigint,integer)`
- The migration does not change function bodies, ownership predicates, restore transaction behavior, or Admin role checks. Each function continues to enforce `auth.uid()` / active-profile / Admin authorization internally.
- The superseded single-request `restore_inventory_snapshot` RPC is not reintroduced.

## Verification
- `tools/verify_step96_restore_rpc_grants.py`: 21/21 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 static checks + SQLite atomic replacement smoke PASS.
- Every verifier from Step73 through Step96 rerun and passed.
- ZIP integrity and SHA-256 verified for the Step96 checkpoint.

## Limitations / deployment prerequisites
- Source-level SQL review only. No PostgreSQL/Supabase staging execution, catalog privilege inspection, role-based RPC invocation test, SQL deployment, or production deployment.
- Base migrations and historical direct grants are incomplete in the reconstructed archive. This migration explicitly revokes `anon` grants if present, but deployment must confirm the target functions exist with the exact signatures and inspect their owners and SECURITY DEFINER privileges.
- No APK build, Android device test, GitHub push, Edge Function deployment, or production deployment.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of the missing original source.
