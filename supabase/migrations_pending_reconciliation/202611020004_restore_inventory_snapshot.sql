-- Atomic replacement of the authenticated user's disposable test inventory.
-- The caller must upload and verify all photos before invoking this RPC.
begin;

create table if not exists public.inventory_restore_commits (
    restore_id uuid primary key,
    owner_id uuid not null references auth.users(id) on delete cascade,
    manifest_sha256 text not null check (manifest_sha256 ~ '^[a-fA-F0-9]{64}$'),
    snapshot_cursor bigint not null default 0,
    committed_at timestamptz not null default now()
);
alter table public.inventory_restore_commits enable row level security;
revoke all on table public.inventory_restore_commits from anon, authenticated;

create or replace function public.restore_inventory_snapshot(
    p_restore_id uuid,
    p_manifest_sha256 text,
    p_parts jsonb,
    p_movements jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_owner uuid := auth.uid();
    v_existing public.inventory_restore_commits%rowtype;
    v_part_count integer;
    v_movement_count integer;
    v_cursor bigint;
begin
    if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
    if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
    if p_restore_id is null or p_manifest_sha256 !~ '^[a-fA-F0-9]{64}$'
       or jsonb_typeof(p_parts) <> 'array' or jsonb_typeof(p_movements) <> 'array' then
        raise exception 'INVALID_RESTORE_PAYLOAD';
    end if;

    -- Serialize restores for this owner and make retry with the same id safe.
    perform pg_advisory_xact_lock(hashtextextended(v_owner::text, 0));
    select * into v_existing from public.inventory_restore_commits
     where restore_id = p_restore_id for update;
    if found then
        if v_existing.owner_id <> v_owner or v_existing.manifest_sha256 <> lower(p_manifest_sha256) then
            raise exception 'RESTORE_ID_CONFLICT' using errcode = '23505';
        end if;
        return jsonb_build_object('status', 'already_committed',
            'snapshot_cursor', v_existing.snapshot_cursor,
            'part_count', jsonb_array_length(p_parts),
            'movement_count', jsonb_array_length(p_movements));
    end if;

    v_part_count := jsonb_array_length(p_parts);
    v_movement_count := jsonb_array_length(p_movements);
    if v_part_count > 500000 or v_movement_count > 1000000 then
        raise exception 'RESTORE_LIMIT_EXCEEDED';
    end if;

    -- Validate all part identifiers and values before deleting current rows.
    if exists (
        select 1 from jsonb_to_recordset(p_parts) as x(
            sync_id uuid, code text, name text, quantity integer,
            min_quantity integer, unit text, photo_path text, note text,
            tracking_mode text, updated_at bigint)
        where x.sync_id is null or nullif(btrim(x.code), '') is null
           or nullif(btrim(x.name), '') is null or x.quantity is null or x.quantity < 0
           or coalesce(x.min_quantity, 0) < 0
           or coalesce(x.tracking_mode, 'SINGLE') not in ('SINGLE', 'BULK')
           or (coalesce(x.photo_path, '') <> '' and split_part(x.photo_path, '/', 1) <> v_owner::text)
    ) then raise exception 'INVALID_RESTORE_PART'; end if;
    if (select count(*) from jsonb_to_recordset(p_parts) as x(sync_id uuid)) <> v_part_count then
        raise exception 'INVALID_RESTORE_PART_IDS';
    end if;
    if exists (select 1 from (select code from jsonb_to_recordset(p_parts) as x(code text)
                              group by code having count(*) > 1) d) then
        raise exception 'DUPLICATE_RESTORE_PART_CODE';
    end if;
    if exists (select 1 from (select sync_id from jsonb_to_recordset(p_parts) as x(sync_id uuid)
                              group by sync_id having count(*) > 1) d) then
        raise exception 'DUPLICATE_RESTORE_PART_ID';
    end if;

    if exists (
        select 1 from jsonb_to_recordset(p_movements) as x(
            sync_id uuid, code text, part_name text, type text, amount integer,
            before_qty integer, after_qty integer, reason text,
            machine_or_store text, time bigint)
        where x.sync_id is null or nullif(btrim(x.code), '') is null
           or nullif(btrim(x.type), '') is null or nullif(btrim(x.part_name), '') is null
           or x.amount is null or x.amount < 0 or x.before_qty is null or x.before_qty < 0
           or x.after_qty is null or x.after_qty < 0 or x.time is null
           or not exists (select 1 from jsonb_to_recordset(p_parts) as p(code text) where p.code = x.code)
    ) then raise exception 'INVALID_RESTORE_MOVEMENT'; end if;
    if exists (select 1 from (select sync_id from jsonb_to_recordset(p_movements) as x(sync_id uuid)
                              group by sync_id having count(*) > 1) d) then
        raise exception 'DUPLICATE_RESTORE_MOVEMENT_ID';
    end if;

    -- Destructive scope is exclusively auth.uid(); Admin global-history permission
    -- does not widen this predicate to other KTV owners.
    delete from public.movements where owner_id = v_owner;
    delete from public.parts where owner_id = v_owner;

    insert into public.parts
        (id, owner_id, code, name, quantity, min_quantity, unit, photo_path,
         note, tracking_mode, updated_at, deleted_at, server_version)
    select x.sync_id, v_owner, x.code, x.name, x.quantity,
           coalesce(x.min_quantity, 0), coalesce(nullif(x.unit, ''), 'cái'),
           coalesce(x.photo_path, ''), coalesce(x.note, ''),
           coalesce(x.tracking_mode, 'SINGLE'),
           coalesce(to_timestamp(x.updated_at / 1000.0), now()), null, 1
      from jsonb_to_recordset(p_parts) as x(
        sync_id uuid, code text, name text, quantity integer,
        min_quantity integer, unit text, photo_path text, note text,
        tracking_mode text, updated_at bigint);

    insert into public.movements
        (id, owner_id, part_id, code, part_name, type, amount,
         before_qty, after_qty, reason, machine_or_store, occurred_at, created_at)
    select x.sync_id, v_owner, p.id, x.code, x.part_name, x.type,
           x.amount, x.before_qty, x.after_qty, coalesce(x.reason, ''),
           coalesce(x.machine_or_store, ''), to_timestamp(x.time / 1000.0),
           to_timestamp(x.time / 1000.0)
      from jsonb_to_recordset(p_movements) as x(
        sync_id uuid, code text, part_name text, type text, amount integer,
        before_qty integer, after_qty integer, reason text,
        machine_or_store text, time bigint)
      join public.parts p on p.owner_id = v_owner and p.code = x.code;

    -- Publish the committed replacement as fresh owner-scoped snapshots.
    insert into public.inventory_sync_events(owner_id, entity_type, entity_id, payload)
    select v_owner, 'PART', p.id, to_jsonb(p) from public.parts p where p.owner_id = v_owner;
    insert into public.inventory_sync_events(owner_id, entity_type, entity_id, payload)
    select v_owner, 'MOVEMENT', m.id, to_jsonb(m) from public.movements m where m.owner_id = v_owner;

    select coalesce(max(event_id), 0) into v_cursor
      from public.inventory_sync_events where owner_id = v_owner;
    insert into public.inventory_restore_commits(restore_id, owner_id, manifest_sha256, snapshot_cursor)
    values (p_restore_id, v_owner, lower(p_manifest_sha256), v_cursor);

    return jsonb_build_object('status', 'committed', 'snapshot_cursor', v_cursor,
        'part_count', v_part_count, 'movement_count', v_movement_count);
end;
$$;

revoke all on function public.restore_inventory_snapshot(uuid, text, jsonb, jsonb) from public;
grant execute on function public.restore_inventory_snapshot(uuid, text, jsonb, jsonb) to authenticated;
commit;
