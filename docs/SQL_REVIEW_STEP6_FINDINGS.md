# SQL review findings — update 2026-11-02

## Resolved in this working copy
- `BackupManager` now writes stable `syncId` values for PART and MOVEMENT JSONL records.
- `ZipRestore` now restores those IDs, with a one-time UUID fallback for legacy ZIP records.

## Still blocking
1. ZIP JSONL camelCase fields must be mapped to SQL snake_case before staging. The mapper is specified in `ZIP_RESTORE_CHUNK_STAGING.md`, but the staging caller is not implemented yet.
2. `photoEntry` is only a ZIP path. It must be uploaded to the owner-scoped Storage bucket and verified before mapping to `photo_path`.
3. `SyncPullWorker` calls `pull_admin_movement_changes`, but no migration defines it.
4. The current migrations alter/use `public.parts`, `public.movements`, and `public.sync_operations` without defining their base schema. Obtain/verify the authoritative base schema before executing the migration chain.
5. Migration 006's `commit_inventory_restore` inserts `parts` and `movements` using assumed columns (`id`, `owner_id`, `part_id`, `occurred_at`, etc.). Those assumptions remain unverified until the base schema is available.

## Test status
- Static source edit only; no Gradle execution.
- No PostgreSQL parser/server or Supabase database was available; no SQL migration has been executed.
- No Supabase deployment, GitHub push, APK build, installation, or device test.

## Decision
Do not deploy migration 006 yet. Complete the Android staging mapper and photo upload contract, define the Admin pull RPC, and verify the authoritative base schema first.
