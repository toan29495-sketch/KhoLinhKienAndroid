-- Offline-first sync foundation for KhoLinhKienAndroid.
-- ADDITIVE migration: does not drop/recreate existing tables or existing policies.
-- Run only after 202611010002_enforce_active_profiles.sql.

begin;

-- Private image bucket. Existing object policies already scope paths to auth.uid().
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'part-images',
    'part-images',
    false,
    20971520,
    array['image/jpeg', 'image/png', 'image/webp']::text[]
)
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

-- Extend the existing idempotency ledger without changing its primary key.
alter table public.sync_operations
    add column if not exists payload jsonb,
    add column if not exists result jsonb,
    add column if not exists processed_at timestamptz;

-- Append-only change feed. event_id is a global monotonic cursor; every event
-- is still scoped to one owner and exposed only through the checked RPC below.
create table if not exists public.inventory_sync_events (
    event_id bigserial primary key,
    owner_id uuid not null references auth.users(id) on delete cascade,
    entity_type text not null check (entity_type in ('PART', 'MOVEMENT')),
    entity_id uuid not null,
    payload jsonb not null,
    created_at timestamptz not null default now()
);
create index if not exists inventory_sync_events_owner_event_idx
    on public.inventory_sync_events(owner_id, event_id);
alter table public.inventory_sync_events enable row level security;
revoke all on table public.inventory_sync_events from anon, authenticated;

-- Restrictive storage policy: existing owner-folder policies still decide which
-- objects a user may access; this additionally blocks inactive profiles.
drop policy if exists part_images_require_active_profile on storage.objects;
create policy part_images_require_active_profile
on storage.objects
as restrictive
for all
to authenticated
using (
    bucket_id <> 'part-images' or public.is_active_profile()
)
with check (
    bucket_id <> 'part-images' or public.is_active_profile()
);

create or replace function public.apply_inventory_operation(
    p_operation_id uuid,
    p_entity_type text,
    p_entity_id uuid,
    p_payload jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_owner uuid := auth.uid();
    v_existing public.sync_operations%rowtype;
    v_part public.parts%rowtype;
    v_code text;
    v_name text;
    v_quantity integer;
    v_min_quantity integer;
    v_unit text;
    v_photo_path text;
    v_note text;
    v_tracking_mode text;
    v_type text;
    v_amount integer;
    v_before integer;
    v_after integer;
    v_reason text;
    v_machine text;
    v_movement_id uuid;
    v_movement public.movements%rowtype;
    v_result jsonb;
begin
    if v_owner is null then
        raise exception 'AUTH_REQUIRED' using errcode = '28000';
    end if;

    if not public.is_active_profile() then
        raise exception 'PROFILE_INACTIVE' using errcode = '42501';
    end if;

    if p_operation_id is null or p_entity_id is null or p_payload is null then
        raise exception 'INVALID_OPERATION';
    end if;

    if p_entity_type not in ('PART_UPSERT', 'PART_DELETE', 'STOCK_CHANGE') then
        raise exception 'UNSUPPORTED_OPERATION_TYPE';
    end if;

    -- The primary key serializes concurrent retries with the same operation UUID.
    insert into public.sync_operations
        (operation_id, owner_id, entity_type, entity_id, payload)
    values
        (p_operation_id, v_owner, p_entity_type, p_entity_id, p_payload)
    on conflict (operation_id) do nothing;

    select * into v_existing
      from public.sync_operations
     where operation_id = p_operation_id
     for update;

    if v_existing.owner_id <> v_owner
       or v_existing.entity_type <> p_entity_type
       or v_existing.entity_id <> p_entity_id then
        raise exception 'OPERATION_ID_CONFLICT' using errcode = '23505';
    end if;

    if v_existing.processed_at is not null then
        return coalesce(v_existing.result, jsonb_build_object('status', 'already_processed'));
    end if;

    if v_existing.payload is distinct from p_payload then
        raise exception 'OPERATION_PAYLOAD_CONFLICT' using errcode = '23505';
    end if;

    if p_entity_type = 'PART_UPSERT' then
        v_code := nullif(btrim(p_payload->>'code'), '');
        v_name := nullif(btrim(p_payload->>'name'), '');
        v_quantity := (p_payload->>'quantity')::integer;
        v_min_quantity := coalesce((p_payload->>'min_quantity')::integer, 0);
        v_unit := coalesce(nullif(btrim(p_payload->>'unit'), ''), 'cái');
        v_photo_path := coalesce(p_payload->>'photo_path', '');
        v_note := coalesce(p_payload->>'note', '');
        v_tracking_mode := coalesce(p_payload->>'tracking_mode', 'SINGLE');

        if v_code is null or v_name is null or v_quantity is null or v_quantity < 0
           or v_min_quantity < 0 or v_tracking_mode not in ('SINGLE', 'BULK') then
            raise exception 'INVALID_PART_PAYLOAD';
        end if;
        if v_photo_path <> '' and split_part(v_photo_path, '/', 1) <> v_owner::text then
            raise exception 'PHOTO_PATH_OWNER_MISMATCH' using errcode = '42501';
        end if;

        insert into public.parts
            (id, owner_id, code, name, quantity, min_quantity, unit,
             photo_path, note, tracking_mode, updated_at, deleted_at, server_version)
        values
            (p_entity_id, v_owner, v_code, v_name, 0, v_min_quantity,
             v_unit, v_photo_path, v_note, v_tracking_mode, now(), null, 1)
        on conflict (id) do update
           set code = excluded.code,
               name = excluded.name,
               -- Keep current stock: metadata sync must never overwrite stock.
               min_quantity = excluded.min_quantity,
               unit = excluded.unit,
               photo_path = excluded.photo_path,
               note = excluded.note,
               tracking_mode = excluded.tracking_mode,
               updated_at = now(),
               deleted_at = null,
               server_version = coalesce(public.parts.server_version, 0) + 1
         where public.parts.owner_id = v_owner
           and (p_payload->>'expected_server_version' is null
                or public.parts.server_version = (p_payload->>'expected_server_version')::bigint);

        if not found then
            raise exception 'PART_NOT_OWNED' using errcode = '42501';
        end if;

        select * into v_part from public.parts
         where id = p_entity_id and owner_id = v_owner;
        v_result := jsonb_build_object(
            'status', 'applied', 'entity_id', v_part.id,
            'server_version', v_part.server_version,
            'updated_at', v_part.updated_at
        );

    elsif p_entity_type = 'PART_DELETE' then
        update public.parts
           set deleted_at = now(), updated_at = now(),
               server_version = coalesce(server_version, 0) + 1
         where id = p_entity_id and owner_id = v_owner and deleted_at is null
        returning * into v_part;
        if not found then
            select * into v_part from public.parts
             where id = p_entity_id and owner_id = v_owner;
            if not found then raise exception 'PART_NOT_OWNED' using errcode = '42501'; end if;
        end if;
        v_result := jsonb_build_object(
            'status', 'applied', 'entity_id', p_entity_id,
            'server_version', v_part.server_version,
            'deleted_at', v_part.deleted_at
        );

    else -- STOCK_CHANGE
        v_type := p_payload->>'type';
        v_amount := (p_payload->>'amount')::integer;
        v_movement_id := coalesce(nullif(p_payload->>'movement_sync_id', '')::uuid, p_operation_id);
        v_reason := coalesce(p_payload->>'reason', '');
        v_machine := coalesce(p_payload->>'machine_or_store', '');

        if v_type not in ('NHẬP', 'XUẤT', 'KIỂM KÊ', 'ĐẦU KỲ') or v_amount is null
           or (v_type = 'KIỂM KÊ' and v_amount < 0)
           or (v_type in ('NHẬP', 'XUẤT', 'ĐẦU KỲ') and v_amount <= 0) then
            raise exception 'INVALID_STOCK_CHANGE';
        end if;

        select * into v_part from public.parts
         where id = p_entity_id and owner_id = v_owner and deleted_at is null
         for update;
        if not found then raise exception 'PART_NOT_FOUND' using errcode = 'P0002'; end if;

        if v_type = 'XUẤT' and v_part.tracking_mode = 'SINGLE' and v_amount <> 1 then
            raise exception 'SINGLE_ITEM_EXPORT_MUST_BE_ONE';
        end if;
        v_before := v_part.quantity;
        if v_type = 'ĐẦU KỲ' and v_before <> 0 then
            raise exception 'OPENING_STOCK_REQUIRES_ZERO_CLOUD_STOCK';
        end if;
        v_after := case
            when v_type = 'NHẬP' then v_before + v_amount
            when v_type = 'XUẤT' then v_before - v_amount
            else v_amount
        end;
        if v_after < 0 then raise exception 'INSUFFICIENT_STOCK'; end if;

        update public.parts
           set quantity = v_after, updated_at = now(),
               server_version = coalesce(server_version, 0) + 1
         where id = p_entity_id and owner_id = v_owner
        returning * into v_part;

        insert into public.movements
            (id, owner_id, part_id, code, part_name, type, amount,
             before_qty, after_qty, reason, machine_or_store,
             occurred_at, created_at)
        values
            (v_movement_id, v_owner, v_part.id, v_part.code, v_part.name,
             v_type, v_amount, v_before, v_after, v_reason, v_machine, now(), now())
        returning * into v_movement;

        v_result := jsonb_build_object(
            'status', 'applied', 'entity_id', v_part.id,
            'movement_id', v_movement_id, 'before_qty', v_before,
            'after_qty', v_after, 'server_version', v_part.server_version
        );
    end if;

    -- Publish complete snapshots in the same transaction as the mutation.
    -- Devices can resume from event_id without relying on timestamp precision.
    if p_entity_type in ('PART_UPSERT', 'PART_DELETE') then
        insert into public.inventory_sync_events(owner_id, entity_type, entity_id, payload)
        values (v_owner, 'PART', p_entity_id, to_jsonb(v_part));
    else
        insert into public.inventory_sync_events(owner_id, entity_type, entity_id, payload)
        values
            (v_owner, 'PART', p_entity_id, to_jsonb(v_part)),
            (v_owner, 'MOVEMENT', v_movement.id, to_jsonb(v_movement));
    end if;

    update public.sync_operations
       set result = v_result, processed_at = now()
     where operation_id = p_operation_id and owner_id = v_owner;

    return v_result;
end;
$$;

revoke all on function public.apply_inventory_operation(uuid, text, uuid, jsonb) from public;
grant execute on function public.apply_inventory_operation(uuid, text, uuid, jsonb) to authenticated;


-- Pull only the authenticated owner's changes. The cursor is global and
-- monotonic, while the owner predicate prevents cross-KTV data disclosure.
create or replace function public.pull_inventory_changes(
    p_after_event_id bigint default 0,
    p_limit integer default 200
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_owner uuid := auth.uid();
    v_events jsonb;
    v_next bigint;
begin
    if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
    if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
    if p_after_event_id < 0 then raise exception 'INVALID_CURSOR'; end if;

    with page as (
        select event_id, entity_type, entity_id, payload, created_at
          from public.inventory_sync_events
         where owner_id = v_owner and event_id > p_after_event_id
         order by event_id asc
         limit greatest(1, least(coalesce(p_limit, 200), 500))
    )
    select coalesce(jsonb_agg(jsonb_build_object(
               'event_id', event_id,
               'entity_type', entity_type,
               'entity_id', entity_id,
               'payload', payload,
               'created_at', created_at
           ) order by event_id), '[]'::jsonb),
           coalesce(max(event_id), p_after_event_id)
      into v_events, v_next
      from page;

    return jsonb_build_object('events', v_events, 'next_cursor', v_next);
end;
$$;
revoke all on function public.pull_inventory_changes(bigint, integer) from public;
grant execute on function public.pull_inventory_changes(bigint, integer) to authenticated;

commit;
