# Step94 – Restrict direct access to sync idempotency ledger (2026-11-03)

## Scope
Audit `public.sync_operations`, the server-side idempotency ledger used by `apply_inventory_operation()`, after direct owner RLS hardening for profiles, parts, and movements.

## Finding
- The Android client has no direct `sync_operations` table reference; all inventory mutations call `apply_inventory_operation()`.
- The reconstructed migration archive alters `sync_operations` but does not include the base migration that creates the table, its original grants/policies, or its owner. Direct PostgREST table access therefore cannot be assumed safe.
- The RPC uses `SECURITY DEFINER`, binds the operation to `auth.uid()`, checks active profile, validates owner/entity/type tuple, checks exact frozen payload before cached replay, and stores result/processed_at server-side.

## Change
Added additive migration `202611030006_lock_down_sync_operation_ledger.sql`:
- Revokes all table privileges on `public.sync_operations` from `PUBLIC`, `anon`, and `authenticated`.
- Leaves the ledger accessible only to privileged server-side function execution, rather than exposing operation payloads/results to client PostgREST.
- Does not change the idempotency RPC or table schema.

## Verification
- `tools/verify_step94_sync_ledger_access.py`: 12/12 static assertions PASS.
- Re-ran `tools/verify_sync_step49_72.py`: 24/24 + SQLite schema/atomic replacement smoke test PASS.
- Re-ran every Step73–94 verification script; all passed.
- ZIP integrity and SHA-256 verified for the Step94 checkpoint.

## Deployment prerequisites / limitations
- The base `sync_operations` table migration is absent from the reconstructed archive. Before deploying, inspect table owner, grants, policies, and `apply_inventory_operation()` owner privileges. The SECURITY DEFINER function owner must retain required SELECT/INSERT/UPDATE privileges after client grants are revoked; otherwise sync will fail closed. Do not deploy until this is confirmed on staging.
- No PostgreSQL/Supabase staging execution or live two-account direct PostgREST test was performed.
- No SQL deployment, APK build, Android device test, GitHub push, or production deployment.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of missing original source.
