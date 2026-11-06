# Step159 — Weekly Cloud backup Storage contract audit

Date: 2026-11-03  
Baseline: Step158 (`f405ce8903eb605f3ed95c9e61a93cc83d3229369d740aa211d60f02ed4a8a72`)  
Scope: read-only audit of the connected Supabase project and local design. No live schema, bucket, policy, data, function, or deployment changes were made.

## Result

**Step159 audit: PASS.** No live changes were made. The policy catalog was checked including `polpermissive`; existing broad-looking policies are restrictive guards, not permission grants.

### Live project facts observed

- Project `kiem-soat-linh-kien`, ref `ihhxxxqojelbxisreqns`, PostgreSQL 17.11, Singapore, active.
- Migration ledger remains at 16 entries: Step138 (7) and Step139 (9). No migration was applied in this step.
- Current buckets:
  - `app-updates`: public, 50 MiB object limit.
  - `part-images`: private, 20 MiB, JPEG/PNG/WebP.
  - `part-images.`: private legacy typo bucket, 10 MiB.
- No public-schema table with `backup` in its name and no backup RPC/function were found.
- Current inventory and restore business tables remain empty in the previously verified fresh-test baseline; this step did not write to them.

### Storage policy classification — verified

The first read of `pg_policy` showed expressions but not `polpermissive`. A second read explicitly included the policy mode and corrected the initial interpretation:

- `part_images_owner_scope_guard` is **RESTRICTIVE** for all commands to `authenticated`. It narrows access to `part-images` to active-profile owner-folder operations. For other bucket IDs its predicate is true, but this policy does not grant any operation by itself.
- `part_images_no_authenticated_delete` is **RESTRICTIVE** for DELETE to `anon` and `authenticated`. It blocks deletes in `part-images`; it does not grant DELETE in other buckets.
- The actual **PERMISSIVE** policies are bucket-specific: `app_updates_admin_upload/update/delete` for `app-updates`, and owner-scoped `part_images_owner_insert/select/update/delete` for `part-images`.
- No general permissive policy was found that grants access to an arbitrary new bucket. RLS therefore remains default-deny for a new bucket until explicit permissive policies are created.

Conclusion: the earlier apparent broad-policy issue is **not a live access-control bypass**. The restrictive guards can remain as-is for this step. The new bucket still requires its own narrowly scoped permissive INSERT/SELECT/DELETE policies, with owner-folder checks and active-profile validation. Keep `upsert=false` so no UPDATE permission is needed. Keep the legacy `part-images.` bucket untouched until its ownership and purpose are separately confirmed.

## Recommended target contract (not deployed)

### Dedicated bucket

- ID: `inventory-backups`
- Private bucket; no public URLs.
- Per-object size ceiling: 256 MiB initially, subject to actual ZIP size tests and project plan limits.
- MIME allow-list: `application/zip` (confirm SDK upload content type).
- Object path: `<auth.uid()>/<backup_uuid>.zip`.
- Immutable object names; upload with `upsert=false`. Never overwrite an existing backup object.
- No service-role key in Android or GitHub Actions client artifacts.

### Object access

- `INSERT`: authenticated, active profile, bucket is `inventory-backups`, first folder exactly `auth.uid()`.
- `SELECT`: owner-only object listing and authenticated download; ensure listing is scoped by owner and use operation-aware helpers if separating list from download.
- `DELETE`: only the object's owner, and only for backup paths. Admin must not receive cross-owner payload access through Storage.
- No client `UPDATE`/upsert policy. Unique immutable object IDs make retry collisions safe: treat "already exists" as a reconciliation case, not permission to overwrite.
- No `anon` access; no public bucket; no signed public URL.

### Metadata table proposal

`public.inventory_backup_objects` (RLS enabled; direct grants minimal or revoked):

- `backup_id uuid primary key`
- `owner_id uuid not null references auth.users(id)`
- `backup_kind text check in ('WEEKLY_AUTO','MONTHLY_AUTO','PRE_UPDATE')`
- `status text check in ('PENDING','VERIFIED','FAILED','DELETED')`
- `storage_path text not null unique`
- `snapshot_cursor bigint not null check (snapshot_cursor >= 0)`
- `archive_size_bytes bigint not null check (archive_size_bytes > 0)`
- `sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$')`
- `part_count integer not null check (part_count >= 0)`
- `movement_count integer not null check (movement_count >= 0)`
- `created_at`, `verified_at`, `deleted_at` timestamps

The table stores metadata only, not inventory rows, image bytes, or ZIP contents. KTV reads only own metadata. Admin gets a checked status-only RPC returning owner display name, counts, timestamps, kind, size, status and checksum; it must not return `storage_path` or a download capability for other owners.

### RPC lifecycle proposal

1. `begin_inventory_backup(...)`: require authenticated active profile; derive owner from `auth.uid()` (never trust caller-supplied owner); validate kind/cursor/size/hash/counts; insert `PENDING` metadata and return the server-approved object path.
2. Android uploads to that exact path with `upsert=false`.
3. Android verifies the stored object before marking complete. Preferred client verification is download-to-private-temp-file and streaming SHA-256/size comparison; do not load a potentially large ZIP wholly into RAM. If this is too expensive, the contract must explicitly downgrade the guarantee to object-size/metadata verification and must not call it cryptographic byte verification.
4. `complete_inventory_backup(...)`: owner-bound; require a matching object row in `storage.objects` for bucket/path/owner and matching byte size; only then change `PENDING` to `VERIFIED` and stamp `verified_at`. It cannot independently prove the file's SHA-256 unless a trusted server-side verifier reads the object bytes. The client-side digest is evidence from the authenticated client, not a server-computed digest.
5. `list_my_inventory_backups(...)`: only own metadata.
6. `list_inventory_backup_status_admin(...)`: require active Admin profile **and** `app_admins` link; status-only across owners; no object paths or payload access.
7. Retention: after a new upload is verified, retain the newest 4 weekly, 1 monthly and 1 pre-update per owner; delete older owner objects through authenticated Storage DELETE scoped to that owner; mark metadata `DELETED` only after Storage confirms deletion. Never delete the last verified backup before a replacement is verified. Do not auto-restore.

SECURITY DEFINER RPCs, if needed for checked metadata/admin operations, must live in a non-exposed schema where possible, pin `search_path`, derive caller from `auth.uid()`, validate active profile and Admin link where relevant, revoke EXECUTE from `PUBLIC`/`anon`, and grant only `authenticated`. Do not introduce broad direct grants on `storage.objects` or public metadata rows.

## Android client/API notes

- The project pins Supabase Kotlin BOM `3.0.1` and `storage-kt`.
- Current official Kotlin reference documents `bucket.upload(path, file, upsert = false)`, `bucket.resumable.createOrContinueUpload(path, File)`, `startOrResumeUploading()`, and authenticated download-to-file APIs. The local existing `docs/SUPABASE_STORAGE_301_RESTORE_ADAPTER.md` also records the 3.0.1 adapter signatures for upload/download.
- Use `File`-based/resumable upload for large ZIPs, not `readBytes()` into memory.
- Stage the archive under app-private `filesDir` with bounded cleanup, not cache-only storage if upload must survive process death/OS cache eviction. Rebuild from a fresh coherent snapshot if the staged file is lost; do not reuse a stale partial ZIP after the underlying inventory changed.
- Keep upload path and metadata owner-bound. Re-check active session/owner before begin, before upload, before completion, and before retention deletes.
- The Step158 snapshot gate already rejects active restore and any unsent outbox; keep that precondition.
- WorkManager cadence: unique periodic work per owner, every 7 days, connected network, charging, battery-not-low; cancel on logout/account switch/Admin downgrade. Do not schedule until backend contract and upload adapter exist.

## Retention and failure safety

- Upload uses a unique immutable object key. If the network fails after upload but before completion, retry reconciles the same `backup_id`/path; it must never overwrite another object.
- An object remains `PENDING` until verified. Retention ignores PENDING/FAILED rows and never counts them toward the verified quota.
- Delete only after a new `VERIFIED` backup exists. If deletion fails, keep metadata and retry later; if object deletion succeeds but metadata update fails, reconciliation marks it deleted on a later pass.
- Do not automatically restore any backup or overwrite current inventory.
- Admin can see all KTV backup statuses but cannot download another KTV's backup contents. Admin's own account continues to have its own KTV backup and own inventory.

## Current-step verification and limitations

- Read-only live audit: PASS (project, migration ledger, table inventory, bucket configuration, `storage.objects` policies, backup-related public tables/functions).
- Supabase docs lookup: PASS (Kotlin upload/download/delete and Storage RLS/access-control/ownership guidance).
- No SQL DDL, bucket creation, policy changes, metadata writes, uploads, deletes, or Edge Function deployments were run.
- No Android compile, APK build, device test, WorkManager execution, or live Storage upload test was run.
- No arbitrary-bucket permissive grant was found. Step160 can prepare a dedicated bucket migration candidate, but it must include owner-scoped policies and be reviewed/tested before any live application.
