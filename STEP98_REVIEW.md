# Step98 – Serialize restore commits across devices (2026-11-03)

## Scope
Audit two devices restoring different ZIP snapshots under the same authenticated owner. Preserve same-restore retry/idempotency and ensure a second legacy staging session cannot replace live inventory after a first restore.

## Finding
- Step97 limits new owners to one active `STAGING` session and serializes quota checks with the per-owner advisory lock derived using seed `97`.
- `commit_inventory_restore()` serializes commit attempts with a separate per-owner advisory lock and locks the target session row. Same-session concurrent chunk staging is serialized by `FOR UPDATE`; a chunk arriving after commit sees a non-STAGING session and fails closed.
- However, pre-Step97 or otherwise legacy data could already contain multiple `STAGING` sessions for one owner. The commit RPC did not independently reject that state. Two devices could therefore commit distinct legacy sessions sequentially, with the later restore replacing the first device's just-restored inventory.

## Changes
- Added `202611030010_single_restore_commit_per_owner.sql`.
- Added a trigger on `inventory_restore_sessions` for the exact `STAGING` -> `COMMITTED` transition.
- The trigger takes the same owner quota advisory transaction lock used by Step97, counts that owner's remaining `STAGING` sessions, and requires exactly one.
- If multiple staging sessions exist, it raises `RESTORE_OWNER_HAS_MULTIPLE_STAGING_SESSIONS`. Because this occurs in the same PostgreSQL transaction as the live inventory DELETE/INSERT and commit ledger updates, the entire commit rolls back; no partial replacement is retained.
- Already-COMMITTED idempotent retries do not trigger the guard. Other owners' sessions do not affect the count.
- Trigger function EXECUTE privileges are revoked from client roles; it is invoked only by the database trigger.

## Verification
- `tools/verify_step98_restore_concurrency.py`: 16/16 PASS (static guard/locking assertions plus deterministic legacy-session model).
- `tools/verify_sync_step49_72.py`: 24/24 plus SQLite schema/atomic replacement smoke PASS.
- All Step73–98 verifier scripts passed.
- Step97 quota, Step98 guard and commit migration were reviewed together for lock ordering: commit locks owner/session then obtains quota lock in the status trigger; staging locks the session then obtains the same quota lock in its chunk trigger. No reverse path that waits for the commit's owner lock was found in the inspected code.
- ZIP integrity and SHA-256 verified for this checkpoint.

## Limits / deployment notes
- No PostgreSQL/Supabase staging execution or multi-device concurrency/fault-injection test was performed.
- Before deployment, inspect existing duplicate STAGING sessions per owner. The new guard intentionally blocks commit while more than one exists; reconcile/abort extra sessions through a reviewed owner-safe operation rather than deleting committed snapshots.
- Existing committed snapshots remain immutable and retained. This step only prevents multiple active/legacy staging sessions from independently committing.
- No APK build, Android device test, SQL deployment, Edge Function deployment, GitHub push, or production change.
- Source lineage remains reconstructed Step49–72 branch, not byte-for-byte restoration of the lost original source.
