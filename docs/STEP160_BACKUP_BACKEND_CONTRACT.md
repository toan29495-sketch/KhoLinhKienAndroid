# Step160 — Backup backend candidate contract

Status: **candidate only; not applied to Supabase**.

## Live-state compatibility assumptions

Read-only inspection on 2026-11-03 confirmed:
- `storage.objects` includes `bucket_id`, `name`, `owner_id`, `metadata`, `archived_at`, and `is_delete_marker`.
- `public.is_active_profile()` checks `profiles.id = auth.uid()` and `is_active = true`.
- `public.is_app_admin()` checks both active Admin profile role and membership in `public.app_admins`.
- `storage.allow_any_operation()` supports separating `object.list` from `storage.object.get_authenticated` within SELECT policies.

The live project currently has no `inventory-backups` bucket, backup table, or backup RPC. No live object or user data was changed.

## Candidate contract

- Private bucket `inventory-backups`, ZIP-only, 256 MiB object limit.
- Object path is server-derived: `<auth.uid()>/<backup_uuid>.zip`.
- INSERT policy requires authenticated + active profile + exact owner folder + UUID ZIP filename + a matching unexpired PENDING metadata registration. A checked helper prevents unregistered object uploads; each exact begin retry refreshes a 24-hour upload authorization window to match TUS URL expiry.
- SELECT policy allows only own listing and authenticated download. No UPDATE/upsert or DELETE policy is added. Cross-owner Admin Storage access is intentionally absent.
- Metadata table has RLS enabled and no direct grants to `anon` or `authenticated`; all reads/writes go through checked RPCs.
- `begin_inventory_backup` derives owner and path from `auth.uid()` and registers a PENDING object. Exact retries with the same backup ID and metadata are idempotent and refresh the 24-hour upload authorization window; reusing an ID with different metadata is rejected.
- Android uploads with `upsert=false`; if an upload response is lost and the object already exists, it reconciles the same immutable path rather than overwriting it. It then downloads to a private temporary file and compares actual bytes/size + SHA-256. `complete_inventory_backup` verifies the Storage object row owner and byte size and checks the client-reported digest against the registered digest before recording `VERIFIED` with `CLIENT_DOWNLOAD_SHA256`.
- Important limitation: PostgreSQL does not recompute the object SHA-256 from Storage bytes. The VERIFIED label means the authenticated Android client downloaded and checked the bytes; it is not an independent server-side cryptographic attestation. A trusted server-side verifier would be required for that stronger guarantee.
- `list_my_inventory_backups` returns only the caller's own metadata including path. `list_inventory_backup_status_admin` requires active profile + `is_app_admin()` and omits storage path and download capability.
- Retention deletion is deliberately not implemented in this candidate. There is no client DELETE policy. A future trusted server-side cleanup function/Edge Function must delete only server-selected paths after a replacement is VERIFIED, then mark metadata DELETED. No retention deletion should ship until that mechanism is separately designed and tested.

## Explicitly not included

- No migration application, bucket creation, policy change, SQL execution against live project, Edge Function deployment, Storage upload, retention delete, or user/data deletion.
- No Android Worker or schedule integration in this step.
- No service-role key in Android or GitHub.

## Review points before any live application

1. Confirm the Storage `metadata->>'size'` representation on an actual test upload in a disposable environment.
2. Validate operation names against the deployed Storage version and exercise list vs authenticated download separately.
3. Test owner A cannot list/download owner B; Admin cannot list/download KTV backup objects.
4. Verify upload with `upsert=false` succeeds while UPDATE/upsert fails.
5. Decide whether client-side download-and-hash verification is sufficient or whether a trusted server-side byte verifier is required.
6. Design retention as a trusted server-side action; do not grant arbitrary client DELETE.
7. Only after explicit user approval should this candidate be converted into a proper migration and applied.
