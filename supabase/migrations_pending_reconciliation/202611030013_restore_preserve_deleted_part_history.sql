-- Step125: preserve historical movements even when their part was deleted.
-- The app deliberately retains movement history after deleting a QR/part from
-- the current catalog. Such rows keep code/name/quantities but have no live part
-- relation; part_id is therefore nullable for historical orphan movements.
begin;

alter table public.movements
    alter column part_id drop not null;

create or replace function public.commit_inventory_restore(p_restore_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_owner uuid := auth.uid();
    v_session public.inventory_restore_sessions%rowtype;
    v_existing public.inventory_restore_commits%rowtype;
    v_part_chunks integer;
    v_movement_chunks integer;
    v_part_rows bigint;
    v_movement_rows bigint;
    v_cursor bigint;
begin
    if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
    if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
    if p_restore_id is null then raise exception 'INVALID_RESTORE_ID'; end if;

    -- Serialize all restores for one owner, then lock this session.
    perform pg_advisory_xact_lock(hashtextextended(v_owner::text, 0));
    select * into v_session
      from public.inventory_restore_sessions
     where restore_id = p_restore_id and owner_id = v_owner
     for update;
    if not found then raise exception 'RESTORE_SESSION_NOT_FOUND'; end if;

    select * into v_existing
      from public.inventory_restore_commits
     where restore_id = p_restore_id
     for update;
    if found then
        if v_existing.owner_id <> v_owner
           or v_existing.manifest_sha256 <> v_session.manifest_sha256 then
            raise exception 'RESTORE_ID_CONFLICT' using errcode = '23505';
        end if;
        return jsonb_build_object(
            'status', 'already_committed',
            'restore_id', p_restore_id,
            'manifest_sha256', v_existing.manifest_sha256,
            'snapshot_cursor', v_existing.snapshot_cursor,
            'part_count', v_session.expected_part_count,
            'movement_count', v_session.expected_movement_count
        );
    end if;
    if v_session.status <> 'STAGING' then raise exception 'RESTORE_SESSION_NOT_STAGING'; end if;

    select count(*) filter (where entity_type = 'PART'),
           count(*) filter (where entity_type = 'MOVEMENT'),
           coalesce(sum(row_count) filter (where entity_type = 'PART'), 0),
           coalesce(sum(row_count) filter (where entity_type = 'MOVEMENT'), 0)
      into v_part_chunks, v_movement_chunks, v_part_rows, v_movement_rows
      from public.inventory_restore_chunks
     where owner_id = v_owner and restore_id = p_restore_id;

    if v_part_chunks <> v_session.expected_part_chunks
       or v_movement_chunks <> v_session.expected_movement_chunks
       or v_part_rows <> v_session.expected_part_count
       or v_movement_rows <> v_session.expected_movement_count then
        raise exception 'RESTORE_CHUNKS_INCOMPLETE';
    end if;

    -- Verify exact contiguous indexes. Chunk row counts may vary because the client
    -- also bounds serialized payload bytes, not only the number of rows. Total counts
    -- were verified above.
    if exists (
        select 1
          from generate_series(0, v_session.expected_part_chunks - 1) g(i)
          left join public.inventory_restore_chunks c
            on c.owner_id = v_owner and c.restore_id = p_restore_id
           and c.entity_type = 'PART' and c.chunk_index = g.i
         where c.restore_id is null
            or c.row_count < 1 or c.row_count > 200
    ) or exists (
        select 1
          from generate_series(0, v_session.expected_movement_chunks - 1) g(i)
          left join public.inventory_restore_chunks c
            on c.owner_id = v_owner and c.restore_id = p_restore_id
           and c.entity_type = 'MOVEMENT' and c.chunk_index = g.i
         where c.restore_id is null
            or c.row_count < 1 or c.row_count > 200
    ) then raise exception 'RESTORE_CHUNK_INDEX_GAP'; end if;

    -- Validate PART rows across all chunks before any destructive statement.
    if exists (
        select 1
          from public.inventory_restore_chunks c
          cross join lateral jsonb_to_recordset(c.payload) as x(
            sync_id uuid, code text, name text, quantity integer,
            min_quantity integer, unit text, photo_path text, note text,
            tracking_mode text, updated_at bigint)
         where c.owner_id = v_owner and c.restore_id = p_restore_id and c.entity_type = 'PART'
           and (x.sync_id is null or nullif(btrim(x.code), '') is null
             or nullif(btrim(x.name), '') is null or x.quantity is null or x.quantity < 0
             or coalesce(x.min_quantity, 0) < 0
             or coalesce(x.tracking_mode, 'SINGLE') not in ('SINGLE', 'BULK')
             or (coalesce(x.photo_path, '') <> ''
                 and split_part(x.photo_path, '/', 1) <> v_owner::text))
    ) then raise exception 'INVALID_RESTORE_PART'; end if;

    if (select count(*) from (
        select x.sync_id
          from public.inventory_restore_chunks c
          cross join lateral jsonb_to_recordset(c.payload) as x(sync_id uuid)
         where c.owner_id = v_owner and c.restore_id = p_restore_id and c.entity_type = 'PART'
    ) q where q.sync_id is null) > 0 then raise exception 'INVALID_RESTORE_PART_IDS'; end if;

    if exists (
        select 1 from (
            select x.sync_id from public.inventory_restore_chunks c
            cross join lateral jsonb_to_recordset(c.payload) as x(sync_id uuid)
            where c.owner_id = v_owner and c.restore_id = p_restore_id and c.entity_type = 'PART'
            group by x.sync_id having count(*) > 1
        ) d
    ) then raise exception 'DUPLICATE_RESTORE_PART_ID'; end if;
    if exists (
        select 1 from (
            select x.code from public.inventory_restore_chunks c
            cross join lateral jsonb_to_recordset(c.payload) as x(code text)
            where c.owner_id = v_owner and c.restore_id = p_restore_id and c.entity_type = 'PART'
            group by x.code having count(*) > 1
        ) d
    ) then raise exception 'DUPLICATE_RESTORE_PART_CODE'; end if;

    -- Movement history is independent of the current parts catalog. A part may
    -- have been deleted while its immutable movement history is intentionally retained.
    if exists (
        select 1
          from public.inventory_restore_chunks m
          cross join lateral jsonb_to_recordset(m.payload) as x(
            sync_id uuid, code text, part_name text, type text, amount integer,
            before_qty integer, after_qty integer, reason text,
            machine_or_store text, time bigint)
         where m.owner_id = v_owner and m.restore_id = p_restore_id and m.entity_type = 'MOVEMENT'
           and (x.sync_id is null or nullif(btrim(x.code), '') is null
             or nullif(btrim(x.type), '') is null or nullif(btrim(x.part_name), '') is null
             or coalesce(x.type, '') not in ('NHẬP', 'XUẤT', 'KIỂM KÊ', 'ĐẦU KỲ')
             or x.amount is null or x.amount < 0 or x.before_qty is null or x.before_qty < 0
             or x.after_qty is null or x.after_qty < 0 or x.time is null
             -- Match the local Room business rules so a crafted ZIP cannot inject
             -- impossible movement history into the cloud snapshot.
             or (x.type in ('NHẬP', 'XUẤT', 'ĐẦU KỲ') and x.amount <= 0)
             or (x.type = 'NHẬP' and x.after_qty::bigint <> x.before_qty::bigint + x.amount::bigint)
             or (x.type = 'XUẤT' and (x.before_qty < x.amount
                                      or x.after_qty::bigint <> x.before_qty::bigint - x.amount::bigint))
             or (x.type = 'KIỂM KÊ' and x.after_qty <> x.amount)
             or (x.type = 'ĐẦU KỲ' and (x.before_qty <> 0 or x.after_qty <> x.amount)))
    ) then raise exception 'INVALID_RESTORE_MOVEMENT'; end if;
    if exists (
        select 1 from (
            select x.sync_id from public.inventory_restore_chunks c
            cross join lateral jsonb_to_recordset(c.payload) as x(sync_id uuid)
            where c.owner_id = v_owner and c.restore_id = p_restore_id and c.entity_type = 'MOVEMENT'
            group by x.sync_id having count(*) > 1
        ) d
    ) then raise exception 'DUPLICATE_RESTORE_MOVEMENT_ID'; end if;

    -- Only the authenticated owner's live inventory is replaced. Staged chunks
    -- remain immutable after commit and serve as the authoritative pull snapshot.
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
      from public.inventory_restore_chunks c
      cross join lateral jsonb_to_recordset(c.payload) as x(
        sync_id uuid, code text, name text, quantity integer,
        min_quantity integer, unit text, photo_path text, note text,
        tracking_mode text, updated_at bigint)
     where c.owner_id = v_owner and c.restore_id = p_restore_id and c.entity_type = 'PART';

    insert into public.movements
        (id, owner_id, part_id, code, part_name, type, amount,
         before_qty, after_qty, reason, machine_or_store, occurred_at, created_at)
    select x.sync_id, v_owner, p.id, x.code, x.part_name, x.type,
           x.amount, x.before_qty, x.after_qty, coalesce(x.reason, ''),
           coalesce(x.machine_or_store, ''), to_timestamp(x.time / 1000.0),
           to_timestamp(x.time / 1000.0)
      from public.inventory_restore_chunks c
      cross join lateral jsonb_to_recordset(c.payload) as x(
        sync_id uuid, code text, part_name text, type text, amount integer,
        before_qty integer, after_qty integer, reason text,
        machine_or_store text, time bigint)
      left join public.parts p on p.owner_id = v_owner and p.code = x.code
     where c.owner_id = v_owner and c.restore_id = p_restore_id and c.entity_type = 'MOVEMENT';

    insert into public.inventory_restore_commits(restore_id, owner_id, manifest_sha256, snapshot_cursor)
    values (p_restore_id, v_owner, v_session.manifest_sha256, 0);

    -- Exactly one control event represents the replacement. It is inserted in
    -- the same transaction as live data and the commit ledger.
    insert into public.inventory_sync_events(owner_id, entity_type, entity_id, payload)
    values (v_owner, 'RESTORE_COMMIT', p_restore_id,
        jsonb_build_object(
            'restore_id', p_restore_id,
            'manifest_sha256', v_session.manifest_sha256,
            'part_count', v_session.expected_part_count,
            'movement_count', v_session.expected_movement_count,
            'part_chunks', v_session.expected_part_chunks,
            'movement_chunks', v_session.expected_movement_chunks
        ))
    returning event_id into v_cursor;

    update public.inventory_restore_commits
       set snapshot_cursor = v_cursor
     where restore_id = p_restore_id and owner_id = v_owner;
    update public.inventory_restore_sessions
       set status = 'COMMITTED', updated_at = now()
     where restore_id = p_restore_id and owner_id = v_owner;

    return jsonb_build_object(
        'status', 'committed', 'restore_id', p_restore_id,
        'manifest_sha256', v_session.manifest_sha256,
        'snapshot_cursor', v_cursor,
        'part_count', v_session.expected_part_count,
        'movement_count', v_session.expected_movement_count
    );
end;
$$;

revoke all privileges on function public.commit_inventory_restore(uuid)
    from public, anon, authenticated;
grant execute on function public.commit_inventory_restore(uuid) to authenticated;

commit;
