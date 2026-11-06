# Step102 – Guard conflict-resolution choices for delete and stock operations (2026-11-03)

## Finding
The existing conflict UI displayed KEEP_LOCAL and KEEP_SERVER choices for every blocked operation type. The executor for KEEP_LOCAL is intentionally limited to an isolated `PART_UPSERT`; delete and stock-change conflicts have no safe automatic executor. Although those choices did not immediately mutate inventory, exposing them was misleading and allowed unsupported decisions to be persisted.

## Change
- Added a pure operation-type policy in `SyncConflictResolutionPlanner.allowedDecisions()`.
- `PART_DELETE` and `STOCK_CHANGE` now expose only `MANUAL_REVIEW`.
- Unknown operation types also default to `MANUAL_REVIEW` only.
- UI disables unsupported choices and explains why delete/stock conflicts cannot be automatically replayed.
- DAO re-reads the owner-scoped BLOCKED operation and enforces the same policy before persisting a decision. This is a persistence-boundary guard, not merely a UI restriction.
- Existing KEEP_LOCAL resolver remains restricted to isolated `PART_UPSERT`; no tombstone resurrection, stock replay, history deletion, or implicit queue unlock was added.

## Verification
- `tools/verify_step102_conflict_choice_guard.py`: 10/10 static assertions.
- Full regression: Step49–72 verifier + SQLite smoke, and all Step73–102 verifier scripts.
- ZIP integrity and SHA-256 verified.

## Not performed
No Android runtime/device test, PostgreSQL/Supabase staging test, SQL deployment, APK build, Edge Function deployment, or GitHub push. This change narrows local decision choices and does not claim to complete automated reconciliation of delete/stock conflicts.
