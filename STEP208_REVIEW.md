# Step208 review — Cloud backup verification trust model

Date: 2026-11-04
Baseline: Step207 source archive.

## Work performed
- Traced the official Android backup flow from `SupabaseWeeklyBackupUploader` and the deployed Step187 SQL source in `supabase/migrations`.
- Confirmed the official app uploads to the private owner folder, performs authenticated readback, checks exact size and SHA-256, and calls `complete_inventory_backup` only after successful readback.
- Confirmed the RPC independently checks active caller, owner-scoped metadata row, registered digest equality, Storage object ownership/existence and Storage metadata size. It does not independently hash the object bytes.
- Confirmed the schema records `verification_method='CLIENT_DOWNLOAD_SHA256'`; therefore the technical distinction is represented in metadata even though the generic `VERIFIED` status can be misunderstood.
- Reviewed impact on restore: the Android restore path separately downloads and checks exact size, SHA-256, ZIP structure, manifest owner and payload before applying inventory.
- Documented options and recommendation in `BACKUP_VERIFICATION_TRUST_MODEL.md`.

## Decision boundary
Recommendation is to retain the current official-client readback plus mandatory restore-time verification, while clarifying the UI semantics. A server-side Edge verifier is a separate architectural change requiring streaming-hash design, measured 256 MiB/concurrency resource tests, backend migration and explicit approval. No implementation or deployment was made.

## Verification limits / safety
Static source review only. No hostile custom client, real Storage upload/download, large-object test, Edge resource test or restore runtime test was performed. No Supabase schema, data, Storage object, Auth user, Edge Function, grant, policy or bucket setting changed. No Gradle, APK build/install, device test or GitHub push.
