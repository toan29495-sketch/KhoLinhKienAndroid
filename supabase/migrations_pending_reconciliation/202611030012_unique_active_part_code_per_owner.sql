-- Step100: make active QR/part codes unique per owner under concurrent devices.
-- Deleted tombstones retain history but release the code for a future new part.
begin;

-- The remote project was bootstrapped with a full UNIQUE(owner_id, code)
-- constraint. Remove that legacy constraint before creating the partial index;
-- otherwise soft-deleted QR codes remain reserved forever.
alter table public.parts
    drop constraint if exists parts_owner_id_code_key;
drop index if exists public.parts_owner_id_code_key;

do $$
begin
    if exists (
        select 1
          from public.parts
         where deleted_at is null
         group by owner_id, code
        having count(*) > 1
    ) then
        raise exception 'DUPLICATE_ACTIVE_PART_CODES_REQUIRE_RECONCILIATION';
    end if;
end;
$$;

-- This database constraint is the final arbiter when two devices concurrently
-- create different part UUIDs with the same owner's QR code. The partial index
-- matches the local app's behavior: soft-deleted rows do not reserve a code.
create unique index if not exists parts_owner_active_code_uidx
    on public.parts(owner_id, code)
    where deleted_at is null;

commit;
