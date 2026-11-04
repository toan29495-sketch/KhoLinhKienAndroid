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

revoke all privileges on function public.is_active_account() from public, anon;
grant execute on function public.is_active_account() to authenticated;

-- The old restrictive policy used is_active_profile(), which intentionally
-- returns false until the password is changed. That made the profile itself
-- unreadable, preventing the app from discovering must_change_password.
drop policy if exists require_active_profile on public.profiles;
create policy require_active_profile
on public.profiles
as restrictive
for all
to authenticated
using (public.is_active_account())
with check (public.is_active_account());

commit;
