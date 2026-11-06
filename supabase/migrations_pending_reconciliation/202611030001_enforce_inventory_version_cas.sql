-- Enforce optimistic concurrency and tombstone safety for every inventory mutation.
-- Additive replacement of apply_inventory_operation; run after 202611020001.
begin;

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
    v_expected_server_version bigint;
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

    if p_entity_type is null or p_entity_type not in ('PART_UPSERT', 'PART_DELETE', 'STOCK_CHANGE') then
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

    -- Idempotency requires the exact original payload, even after processing.
    -- Check this before returning a cached result; otherwise a reused operation_id
    -- with a changed payload could be silently accepted as an ordinary retry.
    if v_existing.payload is distinct from p_payload then
        raise exception 'OPERATION_PAYLOAD_CONFLICT' using errcode = '23505';
    end if;

    if v_existing.processed_at is not null then
        return coalesce(v_existing.result, jsonb_build_object('status', 'already_processed'));
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
        -- A positive expected version means this is an update, never a recreate.
        -- If the row disappeared, fail closed rather than resurrecting a purged part.
        if p_payload ? 'expected_server_version'
           and (p_payload->>'expected_server_version')::bigint > 0
           and not exists (select 1 from public.parts where id = p_entity_id and owner_id = v_owner) then
            raise exception 'PART_VERSION_CONFLICT' using errcode = '40001';
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
           and p_payload ? 'expected_server_version'
           and public.parts.server_version = (p_payload->>'expected_server_version')::bigint
           and public.parts.deleted_at is null;

        if not found then
            select * into v_part from public.parts where id = p_entity_id and owner_id = v_owner;
            if not found then raise exception 'PART_NOT_OWNED' using errcode = '42501'; end if;
            if v_part.deleted_at is not null then raise exception 'PART_DELETED' using errcode = 'P0002'; end if;
            raise exception 'PART_VERSION_CONFLICT' using errcode = '40001';
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
           and p_payload ? 'expected_server_version'
           and server_version = (p_payload->>'expected_server_version')::bigint
        returning * into v_part;
        if not found then
            select * into v_part from public.parts where id = p_entity_id and owner_id = v_owner;
            if not found then raise exception 'PART_NOT_OWNED' using errcode = '42501'; end if;
            if v_part.deleted_at is not null then raise exception 'PART_DELETED' using errcode = 'P0002'; end if;
            raise exception 'PART_VERSION_CONFLICT' using errcode = '40001';
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

        if v_type is null or v_type not in ('NHẬP', 'XUẤT', 'KIỂM KÊ', 'ĐẦU KỲ') or v_amount is null
           or (v_type = 'KIỂM KÊ' and v_amount < 0)
           or (v_type in ('NHẬP', 'XUẤT', 'ĐẦU KỲ') and v_amount <= 0) then
            raise exception 'INVALID_STOCK_CHANGE';
        end if;

        -- A present-but-NULL/blank version is not a valid concurrency token.
        -- SQL three-valued logic would otherwise let `IF NOT (version = NULL)`
        -- evaluate to NULL and skip the conflict branch.
        if not (p_payload ? 'expected_server_version') then
            raise exception 'PART_VERSION_REQUIRED' using errcode = '22023';
        end if;
        begin
            v_expected_server_version := nullif(btrim(p_payload->>'expected_server_version'), '')::bigint;
        exception
            when invalid_text_representation or numeric_value_out_of_range then
                raise exception 'PART_VERSION_REQUIRED' using errcode = '22023';
        end;
        if v_expected_server_version is null or v_expected_server_version <= 0 then
            raise exception 'PART_VERSION_REQUIRED' using errcode = '22023';
        end if;

        select * into v_part from public.parts
         where id = p_entity_id and owner_id = v_owner and deleted_at is null
         for update;
        if not found then raise exception 'PART_NOT_FOUND' using errcode = 'P0002'; end if;
        if v_part.server_version is distinct from v_expected_server_version then
            raise exception 'PART_VERSION_CONFLICT' using errcode = '40001';
        end if;

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

commit;
