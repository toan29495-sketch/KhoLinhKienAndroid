-- Step98: prevent two distinct restore sessions for one owner from replacing
-- the live inventory in succession when legacy/pre-quota STAGING rows exist.
-- The check runs in the same transaction as commit_inventory_restore; if it
-- fails, the preceding DELETE/INSERT statements are rolled back atomically.
begin;

create or replace function public.enforce_single_inventory_restore_commit()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_active_staging integer;
begin
    if old.status = 'STAGING' and new.status = 'COMMITTED' then
        -- Share the per-owner lock used by Step97 session/chunk quotas so a
        -- concurrent begin cannot race the final single-session check.
        perform pg_advisory_xact_lock(hashtextextended(new.owner_id::text, 97));

        select count(*)::integer
          into v_active_staging
          from public.inventory_restore_sessions
         where owner_id = new.owner_id
           and status = 'STAGING';

        if v_active_staging <> 1 then
            raise exception 'RESTORE_OWNER_HAS_MULTIPLE_STAGING_SESSIONS'
                using errcode = '54000';
        end if;
    end if;
    return new;
end;
$$;

 drop trigger if exists inventory_restore_single_commit_guard on public.inventory_restore_sessions;
create trigger inventory_restore_single_commit_guard
before update of status on public.inventory_restore_sessions
for each row
when (old.status = 'STAGING' and new.status = 'COMMITTED')
execute function public.enforce_single_inventory_restore_commit();

revoke all privileges on function public.enforce_single_inventory_restore_commit() from public, anon, authenticated;

commit;
