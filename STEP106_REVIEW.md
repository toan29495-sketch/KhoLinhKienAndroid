# Step106 – Logout/account-switch fencing for in-flight sync (2026-11-03)

## Finding
The sync sender and pull worker already rechecked the authenticated Supabase user ID around network boundaries. However, logout did not cancel owner-scoped WorkManager jobs and swallowed sign-out failures before clearing the UI profile. In addition, Kotlin `CancellationException` is an `Exception`: the sender could treat WorkManager cancellation as an ordinary send failure and try to mark an `IN_FLIGHT` operation `FAILED`; the pull worker could convert cancellation into a retry result. This blurred cancellation/session termination with business failure.

## Changes
- Added `SyncWorkScheduler.cancelOwner(context, ownerId)` to cancel that owner's push one-shot/periodic and pull one-shot/periodic jobs, including Admin pull job names.
- Logout now cancels owner work before ending the shared Supabase session.
- Logout verifies that the shared client no longer has an authenticated user before clearing the profile. If sign-out fails while the old session remains active, the UI remains signed in and the owner's sync jobs are rescheduled; it does not pretend the session ended.
- Account photo cache is cleared only after the session has ended.
- `SyncOutboxSender`, `SyncPullWorker`, and `SyncOutboxWorker` explicitly rethrow coroutine cancellation. A cancelled sender leaves its exact `IN_FLIGHT` row, operation ID and immutable payload intact. Lease recovery later retries with the same idempotency key; no synthetic FAILED state is written just because work was cancelled.
- Sign-out coroutine cancellation is also rethrown instead of being handled as an authentication error.

## Recovery semantics
- If the server committed immediately before cancellation, a later retry of the same operation ID/payload receives the cached idempotent result and cannot create a second movement.
- If the server did not commit, the same operation can be retried after its known claim lease expires.
- If the request outcome is unknown, the Outbox remains `IN_FLIGHT` until conservative lease recovery; it is not deleted or reassigned to another owner.
- A new account cannot send the previous owner's rows: workers are cancelled by owner and the sender/pull worker still enforce the current Supabase user ID.

## Verification
- `tools/verify_step106_logout_inflight_fence.py`: 13/13 PASS.
- `tools/verify_sync_step49_72.py`: 24/24 plus SQLite schema/atomic replacement smoke PASS.
- All Step73–106 source verifiers passed.
- ZIP integrity and SHA-256 checked.

## Not performed / limitations
- WorkManager cancellation and Supabase sign-out ordering were verified by source/static inspection, not on-device lifecycle/network fault injection.
- No live Supabase staging test, Android runtime/device test, SQL deployment, APK build, Edge Function deployment, GitHub push, or production change.
- Cancellation cannot prove whether a request already accepted by the server committed; safety relies on the existing server idempotency ledger and immutable operation ID/payload.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte restoration of the lost original source.
