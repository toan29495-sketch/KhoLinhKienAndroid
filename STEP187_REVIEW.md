# Step187 Review — Supabase Cloud backup backend deployment

## Scope
Deploy the previously reviewed owner-scoped weekly Cloud backup SQL to the live Supabase project, verify the backend contract, and record the runtime defect discovered by authenticated testing.

## Target
- Project: `kiem-soat-linh-kien`
- Project ref: `ihhxxxqojelbxisreqns`
- Region: `ap-southeast-1`

## Deployed migrations
1. `20261103150733_step187_weekly_backup_backend`
2. `20261103150823_step187_01_fix_backup_registration_ambiguity`
3. `20261103151110_step187_02_deny_direct_backup_metadata_access`

## Live objects verified
- Private bucket `inventory-backups`; 268435456-byte limit; only `application/zip`.
- `public.inventory_backup_objects` exists with RLS enabled, no `anon`/`authenticated` table grants, and a restrictive deny-all policy for those client roles as defense in depth.
- `begin_inventory_backup`, `complete_inventory_backup`, `list_my_inventory_backups`, `list_inventory_backup_status_admin`, and helper `can_upload_inventory_backup_object` exist.
- No `anon` EXECUTE grants; `authenticated` EXECUTE grants exist.
- Storage policies are owner-scoped and require active profiles. No UPDATE/DELETE policy was added for backup objects.
- Remote migration history contains both Step187 migrations.

## Defect found and corrected
The first authenticated invocation of `begin_inventory_backup` failed with SQLSTATE `42702` because `ON CONFLICT (backup_id)` was ambiguous between the output parameter and table column. Migration Step187.01 replaces the conflict target with `ON CONFLICT ON CONSTRAINT inventory_backup_objects_pkey`. A subsequent authenticated invocation succeeded and returned the expected owner-scoped path and `PENDING` state.

## Authenticated tests
- Admin registration returned `backup_id`, exact `<admin-uid>/<backup-id>.zip` path, and `PENDING`; repeating the same begin request returned the same identity and state idempotently.
- Admin catalog returned its own pending row.
- A different active KTV saw zero rows for the same catalog.
- A KTV invocation of `list_inventory_backup_status_admin()` failed closed with `ADMIN_REQUIRED`.
- After adding the restrictive deny-all table policy, authenticated begin/list was run again and still passed through the guarded SECURITY DEFINER RPC. The upload authorization helper returned `true` for the owning Admin and `false` for a different KTV against the same pending path. Temporary test metadata rows were deleted. Final `inventory_backup_objects` row count and `inventory-backups` Storage object count are both zero. No test Storage objects were uploaded.

## Security / advisor notes
- Supabase security advisor still reports `rls_enabled_no_policy` for internal restore staging tables and the new backup metadata table. These tables intentionally have no client policies and no client table grants; access is through guarded SECURITY DEFINER RPCs.
- Advisor reports SECURITY DEFINER RPC warnings for existing sync/restore functions and the newly deployed backup RPCs. New RPCs are intentionally callable only by `authenticated`, derive owner from `auth.uid()`, check active profile, and admin RPC additionally checks `is_app_admin()`. The helper is callable by authenticated because the Storage policy evaluates it. Existing unrelated RPCs were not changed in this step.
- Leaked-password protection remains disabled in project Auth settings; not changed as it is unrelated to backup deployment and requires a separate Auth configuration decision.

## Not yet verified
- Actual Android TUS upload through the Storage API.
- Storage API INSERT/SELECT policy behavior with a real JWT request.
- Authenticated object download, exact archive size and SHA-256 readback, and `complete_inventory_backup` transition to `VERIFIED`.
- Catalog UI, remote restore download, archive inspection, restore coordinator integration, uninstall/reinstall and device test.
- Kotlin compile, APK build or phone installation. Gradle was not run, per user instruction.

## Data safety
No existing bucket was modified or removed. No existing Storage object, user, profile, part, movement, sync ledger, restore session or restore snapshot was modified. The only inserted metadata rows were temporary test rows and were deleted after verification.
