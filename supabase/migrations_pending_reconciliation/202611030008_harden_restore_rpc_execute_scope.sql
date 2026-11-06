-- Step96: pin restore and Admin-feed RPC execution to authenticated callers.
-- These SECURITY DEFINER functions still bind all data access to auth.uid();
-- this migration removes any historical direct anon grants explicitly.
begin;

-- Restore staging and snapshot RPCs. Revoke any explicit grants from anon,
-- then grant only to authenticated. Revoke PUBLIC as well because PUBLIC
-- privileges are inherited by every database role unless overridden.
revoke all privileges on function public.begin_inventory_restore(uuid, text, integer, integer, integer, integer)
    from public, anon, authenticated;
grant execute on function public.begin_inventory_restore(uuid, text, integer, integer, integer, integer)
    to authenticated;

revoke all privileges on function public.stage_inventory_restore_chunk(uuid, text, integer, jsonb)
    from public, anon, authenticated;
grant execute on function public.stage_inventory_restore_chunk(uuid, text, integer, jsonb)
    to authenticated;

revoke all privileges on function public.commit_inventory_restore(uuid)
    from public, anon, authenticated;
grant execute on function public.commit_inventory_restore(uuid)
    to authenticated;

revoke all privileges on function public.read_inventory_restore_snapshot_chunk(uuid, text, integer)
    from public, anon, authenticated;
grant execute on function public.read_inventory_restore_snapshot_chunk(uuid, text, integer)
    to authenticated;

revoke all privileges on function public.verify_inventory_restore_snapshot_chunk(uuid, text, integer, jsonb)
    from public, anon, authenticated;
grant execute on function public.verify_inventory_restore_snapshot_chunk(uuid, text, integer, jsonb)
    to authenticated;

-- Admin global history is authenticated-only and performs its own active
-- Admin + NULL-safe role check inside the SECURITY DEFINER function.
revoke all privileges on function public.pull_admin_movement_changes(bigint, integer)
    from public, anon, authenticated;
grant execute on function public.pull_admin_movement_changes(bigint, integer)
    to authenticated;

commit;
