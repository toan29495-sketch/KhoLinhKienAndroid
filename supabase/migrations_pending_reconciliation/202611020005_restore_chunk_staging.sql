-- Restore staging primitives. This migration intentionally does NOT replace live
-- inventory rows. A separate reviewed commit migration will consume these rows
-- atomically after all chunks and photo uploads have been verified.
begin;

create table if not exists public.inventory_restore_sessions (
    restore_id uuid primary key,
    owner_id uuid not null references auth.users(id) on delete cascade,
    manifest_sha256 text not null check (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    expected_part_count integer not null check (expected_part_count between 0 and 500000),
    expected_movement_count integer not null check (expected_movement_count between 0 and 1000000),
    expected_part_chunks integer not null check (expected_part_chunks between 0 and 500000),
    expected_movement_chunks integer not null check (expected_movement_chunks between 0 and 1000000),
    status text not null default 'STAGING' check (status in ('STAGING','COMMITTED','ABORTED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (owner_id, restore_id)
);

create table if not exists public.inventory_restore_chunks (
    owner_id uuid not null references auth.users(id) on delete cascade,
    restore_id uuid not null,
    entity_type text not null check (entity_type in ('PART','MOVEMENT')),
    chunk_index integer not null check (chunk_index >= 0),
    row_count integer not null check (row_count between 1 and 200),
    payload jsonb not null check (jsonb_typeof(payload) = 'array'),
    payload_md5 text not null check (payload_md5 ~ '^[a-f0-9]{32}$'),
    created_at timestamptz not null default now(),
    primary key (restore_id, entity_type, chunk_index),
    constraint inventory_restore_chunks_session_owner_fk
        foreign key (owner_id, restore_id)
        references public.inventory_restore_sessions(owner_id, restore_id)
        on delete cascade
);

create index if not exists inventory_restore_sessions_owner_status_idx
    on public.inventory_restore_sessions(owner_id, status, updated_at desc);

alter table public.inventory_restore_sessions enable row level security;
alter table public.inventory_restore_chunks enable row level security;
revoke all on public.inventory_restore_sessions, public.inventory_restore_chunks from anon, authenticated;

create or replace function public.begin_inventory_restore(
    p_restore_id uuid,
    p_manifest_sha256 text,
    p_expected_part_count integer,
    p_expected_movement_count integer,
    p_expected_part_chunks integer,
    p_expected_movement_chunks integer
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_owner uuid := auth.uid();
    v_session public.inventory_restore_sessions%rowtype;
begin
    if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
    if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
    if p_restore_id is null or p_manifest_sha256 is null or p_manifest_sha256 !~ '^[a-fA-F0-9]{64}$'
       or p_expected_part_count is null or p_expected_movement_count is null
       or p_expected_part_chunks is null or p_expected_movement_chunks is null
       or p_expected_part_count not between 0 and 500000
       or p_expected_movement_count not between 0 and 1000000
       or p_expected_part_chunks not between 0 and 500000
       or p_expected_movement_chunks not between 0 and 1000000
       or p_expected_part_chunks > p_expected_part_count
       or p_expected_movement_chunks > p_expected_movement_count
       or (p_expected_part_count = 0 and p_expected_part_chunks <> 0)
       or (p_expected_movement_count = 0 and p_expected_movement_chunks <> 0)
       or (p_expected_part_count > 0 and p_expected_part_chunks = 0)
       or (p_expected_movement_count > 0 and p_expected_movement_chunks = 0) then
        raise exception 'INVALID_RESTORE_MANIFEST';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(v_owner::text || ':' || p_restore_id::text, 0));
    select * into v_session from public.inventory_restore_sessions
     where restore_id = p_restore_id for update;
    if found then
        if v_session.owner_id <> v_owner
           or v_session.manifest_sha256 <> lower(p_manifest_sha256)
           or v_session.expected_part_count <> p_expected_part_count
           or v_session.expected_movement_count <> p_expected_movement_count
           or v_session.expected_part_chunks <> p_expected_part_chunks
           or v_session.expected_movement_chunks <> p_expected_movement_chunks then
            raise exception 'RESTORE_ID_CONFLICT' using errcode = '23505';
        end if;
        if v_session.status <> 'STAGING' then raise exception 'RESTORE_SESSION_NOT_STAGING'; end if;
        return jsonb_build_object('status','resumed','restore_id',p_restore_id);
    end if;

    insert into public.inventory_restore_sessions(
        restore_id, owner_id, manifest_sha256, expected_part_count,
        expected_movement_count, expected_part_chunks, expected_movement_chunks)
    values (p_restore_id, v_owner, lower(p_manifest_sha256), p_expected_part_count,
        p_expected_movement_count, p_expected_part_chunks, p_expected_movement_chunks);
    return jsonb_build_object('status','created','restore_id',p_restore_id);
end;
$$;

create or replace function public.stage_inventory_restore_chunk(
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
    v_expected_chunks integer;
    v_expected_total integer;
    v_rows integer;
    v_existing public.inventory_restore_chunks%rowtype;
    v_payload_md5 text;
begin
    if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
    if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
    if p_restore_id is null or p_entity_type is null
       or p_entity_type not in ('PART','MOVEMENT')
       or p_chunk_index is null or p_chunk_index < 0
       or p_payload is null or jsonb_typeof(p_payload) <> 'array' then
        raise exception 'INVALID_RESTORE_CHUNK';
    end if;

    v_rows := jsonb_array_length(p_payload);
    if v_rows < 1 or v_rows > 200 then
        raise exception 'RESTORE_CHUNK_SIZE_INVALID';
    end if;
    -- A row-count limit alone does not bound request size (e.g. very long notes).
    -- Keep each JSONB chunk bounded so PostgREST requests remain manageable.
    if pg_column_size(p_payload) > 1048576 then
        raise exception 'RESTORE_CHUNK_BYTES_LIMIT_EXCEEDED';
    end if;
    -- Compute from PostgreSQL's canonical JSONB text so Android does not need
    -- to reproduce PostgreSQL serialization byte-for-byte.
    v_payload_md5 := md5(p_payload::text);

    select * into v_session from public.inventory_restore_sessions
     where restore_id = p_restore_id and owner_id = v_owner for update;
    if not found then raise exception 'RESTORE_SESSION_NOT_FOUND'; end if;
    if v_session.status <> 'STAGING' then raise exception 'RESTORE_SESSION_NOT_STAGING'; end if;

    if p_entity_type = 'PART' then
        v_expected_chunks := v_session.expected_part_chunks;
        v_expected_total := v_session.expected_part_count;
    else
        v_expected_chunks := v_session.expected_movement_chunks;
        v_expected_total := v_session.expected_movement_count;
    end if;
    if p_chunk_index >= v_expected_chunks then raise exception 'RESTORE_CHUNK_INDEX_OUT_OF_RANGE'; end if;
    if v_rows > v_expected_total then raise exception 'RESTORE_CHUNK_ROW_COUNT_MISMATCH'; end if;

    select * into v_existing from public.inventory_restore_chunks
     where restore_id = p_restore_id and entity_type = p_entity_type and chunk_index = p_chunk_index;
    if found then
        if v_existing.owner_id <> v_owner or v_existing.payload_md5 <> v_payload_md5
           or v_existing.payload <> p_payload then
            raise exception 'RESTORE_CHUNK_REPLAY_CONFLICT' using errcode = '23505';
        end if;
        return jsonb_build_object('status','already_staged','entity_type',p_entity_type,'chunk_index',p_chunk_index);
    end if;

    insert into public.inventory_restore_chunks(
        owner_id, restore_id, entity_type, chunk_index, row_count, payload, payload_md5)
    values (v_owner, p_restore_id, p_entity_type, p_chunk_index, v_rows, p_payload, v_payload_md5);
    update public.inventory_restore_sessions set updated_at = now() where restore_id = p_restore_id;
    return jsonb_build_object('status','staged','entity_type',p_entity_type,'chunk_index',p_chunk_index,'row_count',v_rows);
end;
$$;

revoke all on function public.begin_inventory_restore(uuid,text,integer,integer,integer,integer) from public;
revoke all on function public.stage_inventory_restore_chunk(uuid,text,integer,jsonb) from public;
grant execute on function public.begin_inventory_restore(uuid,text,integer,integer,integer,integer) to authenticated;
grant execute on function public.stage_inventory_restore_chunk(uuid,text,integer,jsonb) to authenticated;

commit;
