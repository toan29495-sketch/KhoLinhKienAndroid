# Step75 — Reject NULL or invalid stock concurrency token (2026-11-03)

## Finding
The `STOCK_CHANGE` RPC previously checked the expected part version with `v_part.server_version <> (p_payload->>'expected_server_version')::bigint`. If the JSON key existed but its value was JSON `null` (or blank text), the comparison evaluated to SQL `NULL`. In PL/pgSQL, `IF NULL` does not enter the conflict branch, so a malformed stock operation could bypass the optimistic-concurrency check.

## Change
- Parse `expected_server_version` once into a `bigint` local variable.
- Reject missing, JSON-null, blank, malformed, out-of-range, zero, and negative values with stable `PART_VERSION_REQUIRED`.
- Compare the locked part's server version with the parsed token using `IS DISTINCT FROM`.
- Keep the existing `PART_VERSION_CONFLICT` token for stale but otherwise valid versions; the Android classifier already treats `PART_VERSION_REQUIRED` as permanent.
- Update the Step49–72 regression assertion to validate the null-safe implementation rather than the superseded expression.

## Verification
- `tools/verify_step75_stock_version_null.py`: 7/7 static assertions PASS.
- `tools/verify_step74_null_validation.py`: 5/5 static assertions PASS.
- `tools/verify_step73_idempotency.py`: 5/5 static assertions PASS.
- `tools/verify_sync_step49_72.py`: 24/24 source-level assertions PASS; SQLite schema/atomic replacement smoke test PASS.

## Not verified
No PostgreSQL parser/runtime or Supabase staging execution was available. SQL exception handling and RPC behavior are source-reviewed, not runtime-proven. No SQL deployment, Android runtime, APK build, device test, or GitHub push was performed.
