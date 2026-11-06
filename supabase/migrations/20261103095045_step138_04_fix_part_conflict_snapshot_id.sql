create or replace function public.get_inventory_part_conflict_snapshot(p_part_sync_id uuid)
returns jsonb language plpgsql security definer set search_path=pg_catalog,public,auth as $$
declare v_owner uuid:=auth.uid(); v_result jsonb;
begin
 if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode='28000'; end if;
 if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode='42501'; end if;
 if p_part_sync_id is null then raise exception 'INVALID_PART_SYNC_ID'; end if;
 select to_jsonb(p) into v_result from public.parts p where p.id=p_part_sync_id and p.owner_id=v_owner;
 return v_result;
end; $$;
revoke all on function public.get_inventory_part_conflict_snapshot(uuid) from public,anon,authenticated;
grant execute on function public.get_inventory_part_conflict_snapshot(uuid) to authenticated;
