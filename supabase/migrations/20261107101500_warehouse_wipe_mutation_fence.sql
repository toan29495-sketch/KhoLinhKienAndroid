-- Keep the owner warehouse fenced while Storage cleanup is in progress.
-- A failed Storage deletion is retryable and must not allow a second device to
-- recreate inventory before the wipe is finished.
begin;

create table if not exists public.inventory_warehouse_wipes (
  owner_id uuid primary key references auth.users(id) on delete cascade,
  started_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.inventory_warehouse_wipes enable row level security;
revoke all on table public.inventory_warehouse_wipes from public, anon, authenticated;

create or replace function public.reject_inventory_mutation_during_warehouse_wipe()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if exists (
    select 1 from public.inventory_warehouse_wipes w
    where w.owner_id = coalesce(new.owner_id, old.owner_id)
  ) then
    raise exception 'WAREHOUSE_WIPE_IN_PROGRESS' using errcode = '55000';
  end if;
  return new;
end;
$$;
revoke all on function public.reject_inventory_mutation_during_warehouse_wipe() from public, anon, authenticated;

do $$
declare
  t text;
begin
  foreach t in array array[
    'parts','movements','sync_operations','inventory_sync_events',
    'inventory_restore_sessions','inventory_restore_chunks',
    'inventory_restore_commits','inventory_backup_objects'
  ] loop
    execute format('drop trigger if exists reject_mutation_during_warehouse_wipe on public.%I', t);
    execute format(
      'create trigger reject_mutation_during_warehouse_wipe before insert or update on public.%I for each row execute function public.reject_inventory_mutation_during_warehouse_wipe()',
      t
    );
  end loop;
end;
$$;

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

  perform pg_advisory_xact_lock(hashtextextended(v_owner::text, 0));
  insert into public.inventory_warehouse_wipes(owner_id)
  values (v_owner) on conflict (owner_id) do update set updated_at = now();

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

  return jsonb_build_object(
    'success', true, 'owner_id', v_owner,
    'parts_deleted', v_parts, 'movements_deleted', v_movements,
    'storage_cleanup_pending', true
  );
end;
$$;

create or replace function public.finish_my_inventory_wipe()
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_owner uuid := (select auth.uid());
begin
  if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
  if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
  if not exists (select 1 from public.inventory_warehouse_wipes where owner_id = v_owner) then
    return jsonb_build_object('success', true, 'already_finished', true);
  end if;

  -- Refuse to release the fence if any owner-scoped rows or Storage backup
  -- metadata have appeared again. Storage objects are checked by the Edge Function.
  if exists (select 1 from public.parts where owner_id = v_owner)
     or exists (select 1 from public.movements where owner_id = v_owner)
     or exists (select 1 from public.sync_operations where owner_id = v_owner)
     or exists (select 1 from public.inventory_sync_events where owner_id = v_owner)
     or exists (select 1 from public.inventory_restore_sessions where owner_id = v_owner)
     or exists (select 1 from public.inventory_restore_commits where owner_id = v_owner)
     or exists (select 1 from public.inventory_backup_objects where owner_id = v_owner) then
    raise exception 'WAREHOUSE_NOT_EMPTY';
  end if;

  delete from public.inventory_warehouse_wipes where owner_id = v_owner;
  return jsonb_build_object('success', true, 'already_finished', false);
end;
$$;

revoke all on function public.purge_my_inventory_data() from public, anon;
revoke all on function public.finish_my_inventory_wipe() from public, anon;
grant execute on function public.purge_my_inventory_data() to authenticated;
grant execute on function public.finish_my_inventory_wipe() to authenticated;

commit;
