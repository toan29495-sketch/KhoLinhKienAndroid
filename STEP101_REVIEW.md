# Step101 – Concurrent part deletion vs stock movement (2026-11-03)

## Audit result

Reviewed the two-device race where one device deletes a part while another device records an inbound/outbound/stocktake operation.

The authoritative server mutation path already has the required fail-closed ordering:

- `PART_DELETE` updates only an active row whose `server_version` equals the operation's expected version.
- `STOCK_CHANGE` selects the active part `FOR UPDATE`, validates the expected version, updates quantity and increments the version, then inserts the movement in the same PostgreSQL RPC transaction.
- Both paths publish the part snapshot/change-feed event in that same transaction. A rejected/stale operation raises before the operation ledger is marked processed; the transaction rolls back.
- Thus, when both devices start from the same version, only the first server transaction can commit. If stock wins, delete becomes a version conflict and the part plus movement remain. If delete wins, stock fails because the active part no longer exists; no movement row is inserted.
- Stable operation IDs and payload comparison protect retries. The local delete queues PART_DELETE and physically removes the local row in the same Room transaction; the pull worker does not overwrite entities that have unsent operations. Blocked outbox rows stop later sends.

## Important UX / reconciliation limitation

The server-side inventory and movement ledger remain consistent, but a stale `PART_DELETE` or `STOCK_CHANGE` can remain BLOCKED and require manual reconciliation. The local delete removes the visible Room part immediately, while its durable intent remains in Outbox; a blocked delete is not automatically reversed from the server snapshot. The current conflict screen exposes the error and server snapshot reader, but no dedicated automated delete-vs-stock reconciliation policy exists. This is intentionally not “fixed” by blindly retrying, resurrecting a tombstone, discarding a movement, or silently choosing local/server state. A future dedicated resolver should require explicit user choice and preserve movement history.

## Change decision

No production SQL or Android behavior change was justified by this audit: the server CAS, row lock, single-transaction movement insertion, operation ledger, local Outbox ordering, and pull fence already prevent quantity/history corruption. Added a regression verifier and this review record rather than introducing an unsafe automatic conflict resolution.

## Verification

- `tools/verify_step101_delete_stock_race.py`: 16/16 static assertions plus deterministic two-order CAS model.
- Full regression: Step49–72 verifier and SQLite smoke, plus every Step73–101 verifier.
- ZIP integrity and SHA-256 verified.

## Not performed

No PostgreSQL/Supabase staging race test, two-device runtime test, SQL deployment, APK build, Android device test, Edge Function deployment, GitHub push, or production change. The source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of lost original source.
