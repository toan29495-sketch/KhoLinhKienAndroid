-- Step187.02: add a restrictive deny-all policy as defense in depth.
-- Client table grants remain revoked; guarded SECURITY DEFINER RPCs are the only
-- intended access path for authenticated backup operations.
create policy inventory_backup_metadata_deny_client_access
on public.inventory_backup_objects
as restrictive
for all
to anon, authenticated
using (false)
with check (false);
