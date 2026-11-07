-- Owner-scoped destructive warehouse reset. Storage objects are removed by the
-- authenticated Edge Function before this transaction; this RPC only mutates rows.
begin;

create or replace function public.purge_my_inventory_data()
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_owner uuid := (select auth.uid());
  v_parts integer;
  v_movements integer;
begin
  if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
  if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;

  -- Same per-owner advisory lock used by restore/sync, so a concurrent write
  -- or restore cannot cross the wipe boundary.
  perform pg_advisory_xact_lock(hashtextextended(v_owner::text, 0));

  select count(*) into v_parts from public.parts where owner_id = v_owner;
  select count(*) into v_movements from public.movements where owner_id = v_owner;

  delete from public.inventory_restore_chunks where owner_id = v_owner;
  delete from public.inventory_restore_sessions where owner_id = v_owner;
  delete from public.inventory_restore_commits where owner_id = v_owner;
  delete from public.inventory_backup_objects where owner_id = v_owner;
  delete from public.sync_operations where owner_id = v_owner;
  delete from public.inventory_sync_events where owner_id = v_owner;
  delete from public.movements where owner_id = v_owner;
  delete from public.parts where owner_id = v_owner;

  -- Intentionally preserve profiles/auth.users, app_admins and admin audit
  -- history. An Admin invoking this RPC only purges auth.uid()'s own warehouse.
  return jsonb_build_object(
    'success', true,
    'owner_id', v_owner,
    'parts_deleted', v_parts,
    'movements_deleted', v_movements
  );
end;
$$;

revoke all on function public.purge_my_inventory_data() from public, anon;
grant execute on function public.purge_my_inventory_data() to authenticated;

commit;
