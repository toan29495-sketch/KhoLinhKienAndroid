create or replace function public.is_app_admin() returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from public.app_admins a join public.profiles p on p.id=a.user_id
 where a.user_id=(select auth.uid()) and p.is_active=true and lower(coalesce(p.role,''))='admin');
$$;
revoke all privileges on function public.is_app_admin() from public,anon,authenticated;
grant execute on function public.is_app_admin() to authenticated;
