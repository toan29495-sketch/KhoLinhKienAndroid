-- Chặn quyền truy cập PostgREST của tài khoản đã bị Admin khóa.
-- Policy RESTRICTIVE được AND với policy hiện có, không tự cấp thêm quyền dữ liệu.
-- Hàm SECURITY DEFINER đọc profiles để tránh policy trên profiles tự đệ quy.

create or replace function public.is_active_profile()
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
    );
$$;

revoke all on function public.is_active_profile() from public;
grant execute on function public.is_active_profile() to authenticated;

do $$
declare
    table_name text;
begin
    foreach table_name in array array['profiles', 'parts', 'movements', 'sync_operations']
    loop
        if to_regclass(format('public.%I', table_name)) is not null then
            execute format(
                'drop policy if exists require_active_profile on public.%I',
                table_name
            );
            execute format(
                'create policy require_active_profile on public.%I as restrictive for all to authenticated using (public.is_active_profile()) with check (public.is_active_profile())',
                table_name
            );
        end if;
    end loop;
end;
$$;
