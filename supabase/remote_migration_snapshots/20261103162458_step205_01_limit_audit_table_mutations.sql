-- RECONSTRUCTED REMOTE CATALOG SNAPSHOT — NOT ORIGINAL MIGRATION SOURCE.
-- Remote migration: 20261103162458_step205_01_limit_audit_table_mutations
-- Do not execute against production. See this directory's README.md.

-- The audit table intentionally has no client-facing RLS policies.
-- Authenticated Admin reads only through the guarded RPC; Edge Function uses
-- service_role for INSERT/SELECT/UPDATE. Direct DELETE is not granted.
revoke all privileges on table public.admin_audit_events from public, anon, authenticated, service_role;
grant select, insert, update on table public.admin_audit_events to service_role;
