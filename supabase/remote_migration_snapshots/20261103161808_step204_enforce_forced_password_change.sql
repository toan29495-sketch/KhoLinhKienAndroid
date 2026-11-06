-- RECONSTRUCTED REMOTE CATALOG SNAPSHOT — NOT ORIGINAL MIGRATION SOURCE.
-- Remote migration: 20261103161808_step204_enforce_forced_password_change
-- Do not execute against production. See this directory's README.md.

create table if not exists public.password_reset_guard (
  user_id uuid primary key references auth.users(id) on delete cascade,
  suppress_until timestamptz not null
);

comment on table public.password_reset_guard is
  'Internal Edge Function-only short suppression window for Admin-set temporary passwords; no client grants or policies.';

alter table public.password_reset_guard enable row level security;
revoke all privileges on table public.password_reset_guard from public, anon, authenticated;
grant select, insert, update, delete on table public.password_reset_guard to service_role;

create or replace function public.handle_forced_password_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
  if new.encrypted_password is distinct from old.encrypted_password then
    if not exists (
      select 1
      from public.password_reset_guard g
      where g.user_id = new.id
        and g.suppress_until > now()
    ) and extensions.crypt('000000', new.encrypted_password) <> new.encrypted_password then
      update public.profiles p
         set must_change_password = false,
             updated_at = now()
       where p.id = new.id
         and p.is_active = true
         and p.must_change_password = true;
    end if;
  end if;
  return new;
end;
$function$;

revoke all privileges on function public.handle_forced_password_change() from public, anon, authenticated;
grant execute on function public.handle_forced_password_change() to service_role;

drop trigger if exists on_auth_user_password_changed on auth.users;
create trigger on_auth_user_password_changed
after update of encrypted_password on auth.users
for each row
when (old.encrypted_password is distinct from new.encrypted_password)
execute function public.handle_forced_password_change();

-- The live helper also requires a user to have completed the forced password change.
create or replace function public.is_active_profile()
returns boolean
language sql
stable
security definer
set search_path = 'pg_catalog', 'public', 'auth'
as $function$
  select exists (
    select 1
    from public.profiles p
    where p.id = (select auth.uid())
      and p.is_active = true
      and p.must_change_password = false
  );
$function$;

revoke all privileges on function public.is_active_profile() from public, anon;
grant execute on function public.is_active_profile() to authenticated, service_role;

create or replace function public.is_app_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
  select exists (
    select 1
    from public.app_admins a
    join public.profiles p on p.id = a.user_id
    where a.user_id = (select auth.uid())
      and p.is_active = true
      and p.must_change_password = false
      and lower(coalesce(p.role, '')) = 'admin'
  );
$function$;

revoke all privileges on function public.is_app_admin() from public, anon;
grant execute on function public.is_app_admin() to authenticated, service_role;

create or replace function public.is_active_admin_profile()
returns boolean
language sql
stable
security definer
set search_path = 'pg_catalog', 'public', 'auth'
as $function$
  select public.is_app_admin();
$function$;

revoke all privileges on function public.is_active_admin_profile() from public, anon;
grant execute on function public.is_active_admin_profile() to authenticated, service_role;
