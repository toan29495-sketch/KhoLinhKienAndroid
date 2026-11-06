# Step135 – Admin history freshness, RESTORE refresh and role downgrade audit

Date: 2026-11-03

## Baseline and scope

- Continued from the exact Step134 source ZIP after SHA-256 verification (`b21ae7532ab981f69c0b7d2012daf53af8869ecc47a931d49a9ecd5a484d4e8e`) and successful ZIP integrity test.
- No Room schema/version, SQL migration, Supabase RPC, Edge Function, Storage policy, or sync payload change.
- Changes are limited to one scalar Room observation, Admin history UI freshness messaging, and role-downgrade navigation cleanup.

## Findings and changes

1. **New movements while Admin is already viewing history**
   - Step134 provided manual refresh and refresh-on-tab-entry, but the paged UI did not signal when another KTV's newly synced rows arrived while Admin remained on the History tab.
   - Added `observeMovementMaxId(): Flow<Long?>`, which observes only `MAX(id)` rather than materializing a history list.
   - The Admin UI compares this insertion watermark with the maximum local row ID among currently loaded pages. When they differ, it displays `Lịch sử đã thay đổi. Hãy làm mới để cập nhật.` and retains the existing explicit refresh action.
   - Using insertion ID rather than event timestamp/head is intentional: a delayed sync can insert a row whose event timestamp is older than the current newest event. The watermark still detects it. The UI does not silently replace or reorder loaded pages.

2. **KTV display-name changes**
   - The current Admin account UI supports create, reset password, lock and unlock; it does not expose a rename/display-name edit action. Therefore there is no current in-app rename operation to verify end-to-end.
   - `ownerName` remains mutable profile display metadata, not immutable transaction identity. Existing rows can be enriched when a movement is replayed with a nonblank current name; this step does not claim that renaming a profile retroactively rewrites every historical row. A future rename feature should define and test the desired historical-name policy and a safe metadata refresh mechanism.

3. **RESTORE after loading multiple Admin pages**
   - Existing RESTORE transaction remains owner-scoped: it replaces only rows for the restored owner and does not erase foreign KTV history or the Admin-global cursor.
   - The normal restore flow is on the Tools tab; returning to History invokes the existing tab-entry refresh. The new insertion watermark also signals a changed row set where the resulting maximum local ID differs from the loaded-page watermark.
   - The UI intentionally requires refresh rather than mutating the current paged list in the background.

4. **Admin → KTV role downgrade**
   - KTV display continues to switch to `ownMovements` (owner-scoped Flow), never the Admin global list.
   - Added a role-keyed effect that returns the user to the Inventory tab if the Admin-only tab was selected when the role changes. Admin history state is cleared by the existing role effect.
   - The Admin global sync scheduler already cancels Admin-only workers on role downgrade; prior role and server RPC guards remain intact.

## Verification

- `tools/verify_step135_admin_history_freshness_role_restore.py`: 15/15 PASS.
- SQLite smoke: inserted delayed movements with older event timestamps; `MAX(id)` watermark changed. Keyset pages remained disjoint and descending.
- Step134 local Admin paging: 29/29 PASS; SQLite 1,250 rows in 7 pages, no gaps/duplicates; machine-history owner isolation PASS.
- Step133 Admin pagination/RESTORE/session: 45/45 PASS; 10,137-event model drains without gaps.
- Step132 multi-device/Admin integration: 53/53 PASS.
- Step131 monotonic cursor, Step130 commit-order, Step82 idempotency, Step90 session fencing, Step91 Admin role guard, Step96 restore RPC grants, Step99 restore fence, Step124–129 restore/photo regressions, and Step49–72 sync/SQLite smoke all PASS in the regression run.
- Python tooling `compileall`: PASS.

## Verification boundaries

- Source/static checks and SQLite-equivalent query smoke only. No Android Room annotation processing, Compose runtime/rendering, WorkManager execution, emulator or physical-device test.
- No live Supabase/PostgreSQL staging, role transition, cross-device sync, SQL/Edge deployment, APK build/install, or GitHub push.
- No Gradle used, in accordance with the project instruction.
- Source lineage remains reconstructed from preserved checkpoints, not byte-for-byte recovery of the original source archive.
