# Step 14 – Cloud restore session and chunk sender

## Implemented in source
- Added `RestoreCloudSessionSender` using the project's existing Supabase Kotlin PostgREST RPC pattern.
- Verifies the active Supabase user matches the restore owner before any RPC.
- Calls `begin_inventory_restore` with the ZIP manifest digest, exact row counts, and actual persisted chunk counts.
- Sends PART and MOVEMENT chunks sequentially using `stage_inventory_restore_chunk`.
- Uses stable restore ID + chunk index, matching the server's idempotent replay behavior.
- Persists `CLOUD_STAGING` before chunk transfer and `CLOUD_STAGED` only after every RPC returns successfully; failures retain `CLOUD_STAGING` and save the error for retry.
- Does not call the destructive commit RPC and does not mutate live Room inventory, outbox, or cursor.

## Important SQL contract correction
The existing payload preparer can split chunks early to remain below 900 KiB. The original SQL migrations incorrectly required every chunk to contain exactly 200 rows except the final chunk and derived chunk count from `ceil(total/200)`. That made byte-bounded chunks incompatible with the server contract.

Updated the not-yet-deployed migrations 005 and 006:
- Accept actual chunk counts, bounded by total row counts.
- Permit variable chunk row counts from 1 to 200.
- Keep contiguous zero-based chunk indexes and exact aggregate row-count validation at commit.
- Keep each JSONB chunk capped at 1 MiB.

## Not verified
- No Gradle build or Kotlin compilation.
- No PostgreSQL/Supabase migration execution.
- No real authenticated RPC call.
- No destructive commit, snapshot pull, or Room swap is implemented by this step.
- The sender is a callable component but is not yet wired into the user-facing restore flow.
