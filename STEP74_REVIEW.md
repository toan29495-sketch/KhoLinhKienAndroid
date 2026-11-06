# Step74 — Reject NULL operation discriminators (2026-11-03)

## Finding
A source audit of `apply_inventory_operation` found two SQL three-valued-logic gaps. In PL/pgSQL, an `IF` condition evaluating to NULL is not treated as TRUE. Therefore `p_entity_type NOT IN (...)` did not reject a NULL operation type, and `v_type NOT IN (...)` did not reject a NULL stock movement type when the other disjuncts were false. The RPC's normal Android caller supplies non-null values, but the exposed authenticated RPC must reject malformed direct requests deterministically.

## Change
- Explicitly reject `p_entity_type IS NULL` alongside unsupported operation types.
- Explicitly reject `v_type IS NULL` alongside unsupported stock movement types.
- Preserve stable error tokens (`UNSUPPORTED_OPERATION_TYPE`, `INVALID_STOCK_CHANGE`).
- No schema change and no change to valid operation behavior.

## Verification
- `tools/verify_step74_null_validation.py`: 5/5 static assertions PASS.
- `tools/verify_step73_idempotency.py`: 5/5 static assertions PASS.
- `tools/verify_sync_step49_72.py`: 24/24 source-level assertions PASS; SQLite schema/atomic replacement smoke test PASS.

## Not verified
No PostgreSQL parser/runtime or Supabase staging execution was available. This is source-level hardening only; no SQL deployment, Android runtime, APK build, device test, or GitHub push was performed.
