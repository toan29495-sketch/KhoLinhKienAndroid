# Step104 – Reject automatic conflict choices when an Outbox dependency chain exists (2026-11-03)

## Finding
The Outbox is serialized at owner scope, not just per entity. `queuedBehindBlocked()` therefore returns later owner operations, including same-entity stock/delete operations and operations for other parts. `prepareConflictDecision()` previously required same-entity successors and no attempted successor, but still allowed a non-manual KEEP_LOCAL/KEEP_SERVER decision to be recorded when unattempted successors existed. The actual resolvers later rejected non-empty dependency lists, so the UI could persist a decision that could never be executed. More importantly, allowing such a choice suggested that the chain could safely be replayed/rebased even though that behavior is not implemented.

## Change
- For `KEEP_LOCAL` and `KEEP_SERVER`, decision preparation now requires the owner Outbox queue behind the blocked operation to be completely empty.
- A chain of any length/type (same entity or another entity) is explicitly rejected with the dependent operation count and must remain manual review.
- `MANUAL_REVIEW` remains recordable and does not mutate or discard any Outbox row.
- Existing executor-level checks remain as defense in depth; immutable operation IDs/payloads, movement history and queue ordering are unchanged.
- No automatic replay, rebase, deletion, or movement rewrite was introduced.

## Verification
- `tools/verify_step104_outbox_chain_guard.py`: 12/12 static assertions PASS.
- Full regression: Step49–72 24/24 + SQLite smoke; every Step73–104 verifier passed.
- ZIP integrity and SHA-256 verified.

## Not performed / limitations
- No Android compile/runtime/device test, PostgreSQL/Supabase staging, SQL deployment, APK build, Edge Function deployment, GitHub push, or production change.
- Multi-operation conflict-chain replay remains intentionally unsupported. A future implementation needs a transaction-aware per-operation review/rebase design, preserving immutable attempted payloads and stock movement semantics. Until then, manual review is the safe path.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.
