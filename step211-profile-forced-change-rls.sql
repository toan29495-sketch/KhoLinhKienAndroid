-- Step211: allow an active user to read only their own profile while a
-- forced password change is pending. Operational tables and RPCs continue to
-- use is_active_profile(), which also requires must_change_password = false.
begin;

create or replace function public.is_active_account()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, auth
as $$
  select exists (
    select 1
      from public.profiles p
     where p.id = (select auth.uid())
       and p.is_active = true
  );
$$;

-- This helper is only needed by the profiles RLS policy.
revoke all privileges on function public.is_active_account() from public, anon;
grant execute on function public.is_active_account() to authenticated;

-- A forced-password-change account must be able to read its own profile to
-- discover the pending flag, but must not gain access to operational data.
drop policy if exists require_active_profile on public.profiles;

create policy require_active_profile
on public.profiles
as restrictive
for select
to authenticated
using (public.is_active_account());

-- Preserve the previous active-profile gate for profile mutations.
create policy require_active_profile_insert
on public.profiles
as restrictive
for insert
to authenticated
with check (public.is_active_profile());

create policy require_active_profile_update
on public.profiles
as restrictive
for update
to authenticated
using (public.is_active_profile())
with check (public.is_active_profile());

create policy require_active_profile_delete
on public.profiles
as restrictive
for delete
to authenticated
using (public.is_active_profile());

commit;
