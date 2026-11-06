-- RECONSTRUCTED REMOTE CATALOG SNAPSHOT — NOT ORIGINAL MIGRATION SOURCE.
-- Remote migration: 20261103162008_step204_01_reject_temporary_password_completion
-- Do not execute against production. See this directory's README.md.

-- Auth's encrypted_password trigger clears must_change_password only when the
-- new password is not the temporary value 000000 and no Admin-reset guard is active.
-- This RPC is verification-only; it must never clear the flag itself.
create or replace function public.complete_password_change()
returns void
language plpgsql
security definer
set search_path = ''
as $function$
begin
  if auth.uid() is null then
    raise exception 'AUTHENTICATION_REQUIRED' using errcode = '42501';
  end if;

  if not exists (
    select 1
    from public.profiles p
    where p.id = (select auth.uid())
      and p.is_active = true
      and p.must_change_password = false
  ) then
    raise exception 'PASSWORD_CHANGE_NOT_CONFIRMED' using errcode = '42501';
  end if;
end;
$function$;

revoke all privileges on function public.complete_password_change() from public, anon;
grant execute on function public.complete_password_change() to authenticated, service_role;
