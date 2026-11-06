# Step85 review — photo Outbox race and safe acknowledgement

## Finding
`completePhotoOutbox()` previously required the live `parts.photoPath` to still equal the local path being uploaded (or the remote path). If a user changed the photo again while the older immutable `PART_UPSERT` was in flight, the server could successfully apply the older operation but the local acknowledgement would throw because the Part already referenced the newer local image. The sender then marked the already-applied operation FAILED, and FIFO could remain stuck on its replay. The same failure occurred if the user deleted the Part while its earlier upsert was in flight; the queued `PART_DELETE` could never be reached.

## Change
- Validate the exact Outbox row (`ownerId`, `entityId`, `PART_UPSERT`, `IN_FLIGHT`) before reconciling.
- If the Part still exists, promote its photo path only when it still matches the uploaded local path (or already has the remote path). A newer local photo path is left untouched.
- Record the server-confirmed version for the live Part and rebase later queued mutations sequentially.
- If the Part no longer exists, acknowledge the old upload only when a later queued `PART_DELETE` for the same owner/entity exists; otherwise fail closed.
- Keep the Outbox acknowledgement in the same Room transaction.
- Retain the existing local-file cleanup guard: delete a local image only after successful RPC + local acknowledgement and only when no other unresolved Outbox payload references that path.
- No cloud Storage object deletion is implemented. Old cloud images may remain orphaned; this is intentionally safer than deleting an object still referenced by another device, backup, restore, or pending transaction.

## Photo lifecycle review
- MainActivity copies picked/captured photos into app-private storage and updates the Part through the atomic DAO edit path.
- ZIP backup includes both local pending images and downloads cloud images; backup fails if a referenced local image is missing.
- ZIP restore stages images, uploads and verifies them under deterministic owner/restore/hash paths, then maps those paths into the cloud restore payload. Restore remains owner-scoped and requires no unresolved Outbox operations.
- No eager cloud object deletion is present in the inspected paths.

## Verification
- `tools/verify_step85_photo_outbox_race.py`: 13/13 PASS (source guards and lifecycle model cases).
- Regression suites Step84 through Step73 and reconstructed Step49–72 are rerun separately and recorded in PROGRESS.

## Limits
- These are source/static/model checks, not Android Room runtime or Supabase Storage/PostgreSQL staging tests.
- No APK build, device test, production deployment, SQL migration, or GitHub push was performed.
- Cloud Storage orphan cleanup remains deferred until a server-side reference scan can prove that no other owner-scoped Part, backup/restore flow, or device can still need the object.
