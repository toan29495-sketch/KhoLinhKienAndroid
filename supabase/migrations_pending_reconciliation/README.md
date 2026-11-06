# Pending migration reconciliation

These 25 original SQL migrations are preserved verbatim (except the explicit source corrections noted below) and are not in the active `supabase/migrations` directory because the remote project's migration history was empty while its schema had already been bootstrapped manually.

Do not run these files blindly with `supabase db push`. Step138 deployed a reviewed, additive subset under the seven versioned migrations in `supabase/migrations`. The restore staging/commit/snapshot RPC chain is present in the live catalog, but its nine Step139 version strings are absent from the current remote migration-history listing. Therefore Step139 deployment provenance and exact transaction boundaries remain unresolved; these pending files are retained as source evidence and must not be replayed blindly.

Source corrections made during Step138:
- `202611030002_read_owned_part_conflict_snapshot.sql`: use `parts.id` (the live schema has no `parts.sync_id`).
- `202611030012_unique_active_part_code_per_owner.sql`: drop the bootstrap full unique constraint before creating the active-only partial unique index, so soft-deleted QR codes can be reused.
- `202611010001_complete_password_change.sql`: explicitly revoke anon execution.

The old bucket `part-images.` was deliberately retained. Step138 created the expected private bucket `part-images`; no existing objects or accounts were deleted.


## Step204–Step205 reconciliation note (2026-11-04)
The remote migration history now confirms Step204/205 are already applied. Their original SQL source was not present in the Step203 source ZIP. Read-only catalog reconstructions are stored under `supabase/remote_migration_snapshots/`; they are deliberately outside `supabase/migrations/` and must not be replayed. See `supabase/REMOTE_MIGRATION_MANIFEST.md` and `STEP204_205_SUPABASE_RECONCILIATION.md`.
