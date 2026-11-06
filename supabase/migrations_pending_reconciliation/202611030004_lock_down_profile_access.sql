-- Step92: enforce profile confidentiality and prevent direct client-side
-- privilege/profile mutation. Admin account management remains in the
-- service-role Edge Function; password completion remains in its SECURITY
-- DEFINER RPC. This is additive and does not replace existing permissive RLS.
begin;

alter table public.profiles enable row level security;

create or replace function public.is_active_admin_profile()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, auth
as $$
    select exists (
        select 1
          from public.profiles p
         where p.id = auth.uid()
           and p.is_active = true
           and lower(coalesce(p.role, '')) = 'admin'
    );
$$;

revoke all on function public.is_active_admin_profile() from public, anon;
grant execute on function public.is_active_admin_profile() to authenticated;

-- Restrictive SELECT policy narrows every existing permissive SELECT policy:
-- KTV can read only self; an active Admin can read profiles for the KTV list.
drop policy if exists profiles_select_self_or_active_admin on public.profiles;
create policy profiles_select_self_or_active_admin
on public.profiles
as restrictive
for select
to authenticated
using (
    id = (select auth.uid())
    or (select public.is_active_admin_profile())
);

-- No authenticated client may directly insert, update or delete profile rows.
-- Service-role Edge Functions and owner-executed SECURITY DEFINER RPCs retain
-- their intended paths; this also prevents self-promotion/lock-state tampering.
drop policy if exists profiles_block_client_insert on public.profiles;
create policy profiles_block_client_insert
on public.profiles
as restrictive
for insert
to authenticated
with check (false);

drop policy if exists profiles_block_client_update on public.profiles;
create policy profiles_block_client_update
on public.profiles
as restrictive
for update
to authenticated
using (false)
with check (false);

drop policy if exists profiles_block_client_delete on public.profiles;
create policy profiles_block_client_delete
on public.profiles
as restrictive
for delete
to authenticated
using (false);

commit;
