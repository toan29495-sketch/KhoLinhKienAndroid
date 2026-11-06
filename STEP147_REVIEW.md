# Step147 review — Owner-scoped outbox failure transitions (03/11/2026)

## Finding
`SyncOutboxSender` already captures the worker's immutable `ownerId`, validates the active Supabase user before reading pending work, and rechecks the session around each claim and network boundary. Successful completion paths also validate owner/entity in Room transactions. However, the generic `markOutboxFailed` and `markOutboxBlocked` DAO updates were keyed only by `operationId`. UUIDs are globally unique in practice, but these state transitions did not encode the owner boundary in SQL, making the last local mutation less explicit than the rest of the sync contract.

## Change
- Changed both DAO update queries to require `ownerId`, `operationId`, and `status='IN_FLIGHT'`.
- Updated `SyncOutboxSender` to pass its captured `ownerId` for retryable and permanent failure transitions.
- This ensures an old worker for account A cannot mutate a row belonging to account B even if an operation ID were accidentally reused or collided.
- No change to RPC payload, operation IDs, idempotency, retry policy, claim leases, or successful completion flow.

## Verification
- `tools/verify_step147_outbox_owner_fence.py`: 12/12 PASS, including SQLite smoke tests with the same operation ID under two owners.
- `tools/verify_step146_admin_worker_role_fence.py`: 12/12 PASS.
- Python `compileall`: PASS.

## Limits / unchanged
- Static source and SQLite contract tests only. No Android/Room compilation, WorkManager runtime, live account-switch test, APK build/install, device test, QR test, two-device test, or GitHub push.
- No Gradle use.
- No Supabase schema, SQL, RPC, Edge Function, Auth, Storage, or server data changes.
