-- Step93: enforce owner isolation for direct PostgREST access to inventory rows.
-- Existing permissive policies remain responsible for granting operations;
-- these RESTRICTIVE policies AND every existing policy and bind rows to auth.uid().
-- SECURITY DEFINER sync/restore RPCs keep their explicit owner predicates and
-- continue to serve the supported application workflows.
begin;

alter table public.parts enable row level security;
alter table public.movements enable row level security;

-- Admin manages only their own inventory directly. Global Admin movement
-- history remains available through pull_admin_movement_changes(), whose
-- SECURITY DEFINER implementation performs its own active-Admin check.
drop policy if exists parts_owner_scope_authenticated on public.parts;
create policy parts_owner_scope_authenticated
on public.parts
as restrictive
for all
to authenticated
using (owner_id = (select auth.uid()))
with check (owner_id = (select auth.uid()));

drop policy if exists movements_owner_scope_authenticated on public.movements;
create policy movements_owner_scope_authenticated
on public.movements
as restrictive
for all
to authenticated
using (owner_id = (select auth.uid()))
with check (owner_id = (select auth.uid()));

commit;
