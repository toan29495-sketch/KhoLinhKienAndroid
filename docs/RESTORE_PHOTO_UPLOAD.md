# ZIP restore photo upload

## Current implementation boundary

`RestorePhotoUploadCoordinator` is an owner- and restore-scoped, resumable uploader. It expects the durable directory `filesDir/restore_staging/<restoreId>` and photo entries such as `photos/1.jpg`.

For each photo it:

1. Validates the canonical path, filename, extension and 1..20 MiB size.
2. Computes SHA-256 from the staged file and derives a deterministic object path under `<ownerId>/restore-<restoreId>/`.
3. Checks the authenticated Storage object against expected size and digest. This also recovers the crash window after upload but before local checkpoint write.
4. If the object is absent or mismatched, uploads one photo (the pinned SDK adapter currently accepts a bounded `ByteArray`) and reads it back to verify size and SHA-256.
5. Atomically replaces `photos_uploaded.json` after each verified object. The checkpoint is advisory; it is never trusted without re-verifying the remote object.
6. Returns the mapping `ZIP entry -> Storage object path`; source photos remain in staging.

A retry after process death can safely call `uploadAndVerify` again with the same owner, restore ID, photo list and staging directory. Deterministic object paths and remote verification avoid unnecessary re-upload of already verified objects.

## Not yet integrated

The factory/coordinator is not yet invoked by `ZipRestore` or a restore worker. The caller must not treat this component alone as a completed restore. The returned map still needs to feed `RestorePayloadMapper`, then chunk staging RPCs, cloud commit, snapshot pull and local Room reconciliation. Do not delete staging photos or uploaded cloud objects until the full restore commit and local reconciliation are confirmed.

## Limits

- One photo is buffered at a time, maximum 20 MiB, because the pinned Storage upload and verification adapter uses `ByteArray`.
- No whole ZIP or collection of photos is loaded into memory.
- Storage access uses the authenticated Supabase client and private bucket `part-images`.


## Cloud payload preparation (step 13)
`RestoreCloudPayloadPreparer` is the next durable boundary after ZIP staging. It loads only the current owner's staged rows, extracts the exact referenced photo entries, runs the resumable authenticated Storage uploader, and maps records through `RestorePayloadMapper`. It persists derived `PART_PAYLOAD` and `MOVEMENT_PAYLOAD` rows in Room, with at most 200 rows and a 900 KiB target per chunk (below the SQL RPC 1 MiB cap). The original `PART`/`MOVEMENT` rows and photo checkpoint remain intact for retry.

The journal reaches `PAYLOAD_READY` only after all expected mapped rows are present. This preparer is deliberately not called automatically by ZIP import: a remote restore session/RPC orchestration must exist first, otherwise a failed attempt could leave unreferenced cloud objects. Live Room tables, outbox, and sync cursor remain untouched.
