# Remote migration source reconciliation snapshots (2026-11-04)

These files are **catalog reconstructions**, not recovered byte-for-byte copies of the original SQL submitted to Supabase. They were assembled from the live project's migration history, `pg_get_functiondef`, `pg_get_triggerdef`, `pg_policies`, table/column/constraint/index catalogs, RLS flags, and privilege checks.

They are deliberately stored outside `supabase/migrations/`. Do not run `supabase db push`, `apply_migration`, or execute these snapshots against the current production project. The listed Step204/205 migrations are already present in the remote migration history. These files exist to restore source provenance and support review/reconstruction only.

Known limitation: the database catalog exposes the final definitions, not the original migration transaction boundaries or every intermediate statement. Therefore each file documents the final state attributable to its remote migration name, but is not proof of the original SQL byte sequence.

Remote project: `ihhxxxqojelbxisreqns` (`kiem-soat-linh-kien`), region `ap-southeast-1`, PostgreSQL 17.11.0.003.
