-- Step130: make the global event_id cursor safe under concurrent transactions.
-- A sequence value is allocated before its transaction commits. Without commit-order
-- serialization, transaction A can reserve id N, transaction B can reserve N+1 and
-- commit first, and a pull can advance past N before A becomes visible. That would
-- permanently skip A for both owner and Admin global feeds.
--
-- The BEFORE INSERT trigger takes one transaction-scoped advisory lock, then assigns
-- the event_id from the owned sequence while holding that lock. The lock is retained
-- until commit/rollback, so event_id assignment order now follows commit visibility
-- order across every writer, including restore commits and future SQL writers.
-- This is intentionally global because the Admin feed has one global cursor spanning
-- all owners. It serializes only the short change-feed publication tail of a mutation.

begin;

create or replace function public.assign_inventory_sync_event_commit_order()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    v_sequence_name text;
begin
    -- Fixed application-specific advisory key; xact lock releases automatically
    -- on commit/rollback and is re-entrant for multi-row inserts in one transaction.
    perform pg_catalog.pg_advisory_xact_lock(130130, 1);

    v_sequence_name := pg_catalog.pg_get_serial_sequence(
        'public.inventory_sync_events', 'event_id'
    );
    if v_sequence_name is null then
        raise exception 'SYNC_EVENT_SEQUENCE_NOT_FOUND';
    end if;

    -- Do not trust caller-supplied IDs. The column default is removed below so
    -- every event must pass through this serialized assignment path.
    new.event_id := pg_catalog.nextval(v_sequence_name::pg_catalog.regclass);
    return new;
end;
$$;

revoke all on function public.assign_inventory_sync_event_commit_order() from public, anon, authenticated;

-- The trigger becomes the sole event_id allocator. The owned sequence is retained.
alter table public.inventory_sync_events alter column event_id drop default;

drop trigger if exists inventory_sync_events_commit_order on public.inventory_sync_events;
create trigger inventory_sync_events_commit_order
before insert on public.inventory_sync_events
for each row
execute function public.assign_inventory_sync_event_commit_order();

commit;
