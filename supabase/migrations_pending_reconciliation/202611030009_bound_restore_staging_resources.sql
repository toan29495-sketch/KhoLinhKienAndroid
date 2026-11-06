-- Step97: bound per-owner restore staging and expire abandoned staging sessions.
-- COMMITTED snapshots are deliberately retained: other devices may still need
-- them as the immutable source for snapshot pull. Only stale STAGING sessions
-- are expired and their uncommitted chunks removed.
begin;

create or replace function public.cleanup_my_inventory_restore_staging()
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_owner uuid := auth.uid();
    v_expired integer := 0;
    v_deleted_chunks integer := 0;
begin
    if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
    if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;

    with expired as (
        update public.inventory_restore_sessions
           set status = 'ABORTED', updated_at = now()
         where owner_id = v_owner
           and status = 'STAGING'
           and updated_at < now() - interval '14 days'
        returning restore_id
    ), deleted as (
        -- Delete only chunks for sessions expired by this invocation. Committed
        -- snapshots and unrelated aborted rows are deliberately untouched.
        delete from public.inventory_restore_chunks c
         using expired e
         where c.owner_id = v_owner and c.restore_id = e.restore_id
        returning 1
    )
    select (select count(*)::integer from expired),
           (select count(*)::integer from deleted)
      into v_expired, v_deleted_chunks;

    return jsonb_build_object(
        'status', 'cleaned',
        'expired_sessions', v_expired,
        'deleted_chunks', v_deleted_chunks
    );
end;
$$;

-- A user may have only one active staging restore. A retry with the same
-- restore_id resumes the existing row and does not pass through this trigger.
create or replace function public.enforce_inventory_restore_session_quota()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_active integer;
begin
    if new.expected_part_chunks::bigint + new.expected_movement_chunks::bigint > 8192 then
        raise exception 'RESTORE_CHUNK_COUNT_LIMIT_EXCEEDED' using errcode = '54000';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(new.owner_id::text, 97));
    select count(*)::integer into v_active
      from public.inventory_restore_sessions
     where owner_id = new.owner_id and status = 'STAGING';
    if v_active >= 1 then
        raise exception 'RESTORE_ACTIVE_SESSION_LIMIT' using errcode = '54000';
    end if;
    return new;
end;
$$;

create or replace function public.enforce_inventory_restore_chunk_quota()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_bytes bigint;
    v_chunks bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(new.owner_id::text, 97));

    select coalesce(sum(pg_column_size(c.payload)), 0), count(*)
      into v_bytes, v_chunks
      from public.inventory_restore_chunks c
      join public.inventory_restore_sessions s
        on s.owner_id = c.owner_id and s.restore_id = c.restore_id
     where c.owner_id = new.owner_id and s.status = 'STAGING';

    if v_chunks >= 8192 then
        raise exception 'RESTORE_CHUNK_COUNT_LIMIT_EXCEEDED' using errcode = '54000';
    end if;
    if v_bytes + pg_column_size(new.payload) > 268435456 then
        raise exception 'RESTORE_STAGING_BYTES_LIMIT_EXCEEDED' using errcode = '54000';
    end if;
    return new;
end;
$$;

 drop trigger if exists inventory_restore_sessions_quota_before_insert on public.inventory_restore_sessions;
create trigger inventory_restore_sessions_quota_before_insert
before insert on public.inventory_restore_sessions
for each row execute function public.enforce_inventory_restore_session_quota();

drop trigger if exists inventory_restore_chunks_quota_before_insert on public.inventory_restore_chunks;
create trigger inventory_restore_chunks_quota_before_insert
before insert on public.inventory_restore_chunks
for each row execute function public.enforce_inventory_restore_chunk_quota();

revoke all privileges on function public.cleanup_my_inventory_restore_staging() from public, anon, authenticated;
grant execute on function public.cleanup_my_inventory_restore_staging() to authenticated;
revoke all privileges on function public.enforce_inventory_restore_session_quota() from public, anon, authenticated;
revoke all privileges on function public.enforce_inventory_restore_chunk_quota() from public, anon, authenticated;

commit;
