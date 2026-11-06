# Step90 – Fence background sync against account-session changes (2026-11-03)

## Scope
Protect owner-partitioned local inventory when WorkManager jobs outlive the UI session and the shared Supabase client changes authenticated user during an in-flight push or pull.

## Finding
- `SyncOutboxSender.sendPending(ownerId)` checked the Supabase user only once at worker start. A session switch during a photo Storage upload or RPC could leave the sender continuing with the captured owner ID.
- `SyncPullWorker` checked the user once before pagination. A session switch while a page RPC was in flight could cause the response to be mapped and applied using the old captured `ownerId`, despite the shared client now carrying another account's session.

## Change
- Outbox sender rechecks exact authenticated user ID before each queued operation, after claim, immediately before Storage upload, before RPC, and after RPC before interpreting/acknowledging its response.
- On mismatch, sender fails closed through its existing exception path. The exact operation remains retryable with the same operation ID and frozen payload; it is not acknowledged. Existing server idempotency protects a retry if the server accepted an operation before the session change was detected.
- Pull worker rechecks the exact authenticated user after every page RPC and before processing each event. A mismatched session discards the fetched page and exits without applying events or advancing the cursor.
- All DAO changes remain owner-scoped; no global table clearing or cross-owner cleanup was introduced.

## Verification
- `tools/verify_step90_session_fencing.py`: 10/10 static checks passed.
- Re-ran Step49–72 suite: 24/24 static checks + SQLite smoke test passed.
- Re-ran Step73–89 regression suites: all passed (including SQLite/model tests where present).
- ZIP integrity and SHA-256 verified.

## Limits / not verified
- This is best-effort session fencing around network boundaries, not an atomic binding between a Supabase SDK request and a particular session token. A session could theoretically switch in the tiny interval between a check and the SDK dispatch. Server-side `auth.uid()` ownership checks remain the final security boundary.
- No Android runtime/WorkManager race test, real Supabase account-switch test, Room instrumentation, APK build, SQL deployment, GitHub push, or production operation was performed.
- The source lineage remains the reconstructed Step49–72 branch, not byte-for-byte recovery of the lost original source.
