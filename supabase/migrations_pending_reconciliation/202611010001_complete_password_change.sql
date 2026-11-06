-- Chỉ cho phép người dùng đã đăng nhập tự hoàn tất trạng thái đổi mật khẩu.
-- Ứng dụng gọi RPC này sau khi Supabase Auth xác nhận updateUser(password).
create or replace function public.complete_password_change()
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;

    update public.profiles
       set must_change_password = false,
           updated_at = now()
     where id = auth.uid()
       and is_active = true;

    if not found then
        raise exception 'Active profile not found';
    end if;
end;
$$;

revoke all on function public.complete_password_change() from public, anon;
grant execute on function public.complete_password_change() to authenticated;
