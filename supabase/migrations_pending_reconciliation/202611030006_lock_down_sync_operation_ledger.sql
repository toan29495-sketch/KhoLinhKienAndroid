-- Step94: the idempotency ledger is an RPC-internal table, not a client API.
-- All writes/reads must pass through apply_inventory_operation(), whose
-- SECURITY DEFINER body binds every operation to auth.uid() and checks the
-- immutable operation tuple/payload before replay or mutation.
-- Revoke table privileges from all client-facing roles. The SECURITY DEFINER
-- function owner must retain its existing table privileges; verify that on
-- staging before deployment because the base table/owner migration is absent.
begin;

revoke all privileges on table public.sync_operations from public, anon, authenticated;

commit;
