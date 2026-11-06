-- Verify that the JSON payload received by Android is semantically identical
-- to the immutable committed JSONB chunk. PostgreSQL performs both jsonb::text
-- renderings, avoiding a Kotlin/PostgreSQL serializer mismatch.
begin;

create or replace function public.verify_inventory_restore_snapshot_chunk(
    p_restore_id uuid,
    p_entity_type text,
    p_chunk_index integer,
    p_payload jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_owner uuid := auth.uid();
    v_session public.inventory_restore_sessions%rowtype;
    v_chunk public.inventory_restore_chunks%rowtype;
    v_actual_md5 text;
    v_actual_rows integer;
begin
    if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
    if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
    if p_restore_id is null or p_entity_type is null
       or p_entity_type not in ('PART', 'MOVEMENT')
       or p_chunk_index is null or p_chunk_index < 0
       or p_payload is null or jsonb_typeof(p_payload) <> 'array' then
        raise exception 'INVALID_SNAPSHOT_VERIFY_REQUEST';
    end if;

    select * into v_session
      from public.inventory_restore_sessions
     where restore_id = p_restore_id and owner_id = v_owner;
    if not found or v_session.status <> 'COMMITTED' then
        raise exception 'RESTORE_SNAPSHOT_NOT_COMMITTED';
    end if;

    select * into v_chunk
      from public.inventory_restore_chunks
     where owner_id = v_owner and restore_id = p_restore_id
       and entity_type = p_entity_type and chunk_index = p_chunk_index;
    if not found then raise exception 'SNAPSHOT_CHUNK_MISSING'; end if;

    v_actual_rows := jsonb_array_length(p_payload);
    v_actual_md5 := md5(p_payload::text);

    return jsonb_build_object(
        'verified', v_actual_rows = v_chunk.row_count
                    and v_actual_md5 = v_chunk.payload_md5
                    and p_payload = v_chunk.payload,
        'payload_md5', v_actual_md5,
        'row_count', v_actual_rows
    );
end;
$$;

revoke all on function public.verify_inventory_restore_snapshot_chunk(uuid, text, integer, jsonb) from public;
grant execute on function public.verify_inventory_restore_snapshot_chunk(uuid, text, integer, jsonb) to authenticated;

commit;
