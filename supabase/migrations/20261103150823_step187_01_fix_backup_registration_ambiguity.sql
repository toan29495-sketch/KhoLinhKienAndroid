-- Step187.01: fix PL/pgSQL ambiguity between OUT parameter backup_id and table column.
-- The initial begin RPC deployed in Step187.00 failed at runtime before any row was committed.
-- Use the named primary-key constraint to make the conflict target unambiguous.
create or replace function public.begin_inventory_backup(
  p_backup_id uuid,
  p_backup_kind text,
  p_snapshot_cursor bigint,
  p_archive_size_bytes bigint,
  p_sha256 text,
  p_part_count integer,
  p_movement_count integer
)
returns table (backup_id uuid, storage_path text, status text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_owner uuid := (select auth.uid());
  v_path text;
  v_existing public.inventory_backup_objects%rowtype;
begin
  if v_owner is null then raise exception 'AUTH_REQUIRED' using errcode = '42501'; end if;
  if not public.is_active_profile() then raise exception 'PROFILE_INACTIVE' using errcode = '42501'; end if;
  if p_backup_id is null
     or p_backup_kind not in ('WEEKLY_AUTO', 'MONTHLY_AUTO', 'PRE_UPDATE')
     or p_snapshot_cursor < 0
     or p_archive_size_bytes <= 0 or p_archive_size_bytes > 268435456
     or p_sha256 !~ '^[a-f0-9]{64}$'
     or p_part_count < 0 or p_part_count > 500000
     or p_movement_count < 0 or p_movement_count > 1000000 then
    raise exception 'INVALID_BACKUP_METADATA' using errcode = '22023';
  end if;

  v_path := v_owner::text || '/' || p_backup_id::text || '.zip';
  insert into public.inventory_backup_objects as target (
    backup_id, owner_id, backup_kind, storage_path, snapshot_cursor,
    archive_size_bytes, sha256, part_count, movement_count
  ) values (
    p_backup_id, v_owner, p_backup_kind, v_path, p_snapshot_cursor,
    p_archive_size_bytes, p_sha256, p_part_count, p_movement_count
  ) on conflict on constraint inventory_backup_objects_pkey do nothing;

  select * into v_existing
  from public.inventory_backup_objects b
  where b.backup_id = p_backup_id and b.owner_id = v_owner
  for update;
  if not found then raise exception 'BACKUP_ID_CONFLICT' using errcode = '23505'; end if;
  if v_existing.backup_kind is distinct from p_backup_kind
     or v_existing.storage_path is distinct from v_path
     or v_existing.snapshot_cursor is distinct from p_snapshot_cursor
     or v_existing.archive_size_bytes is distinct from p_archive_size_bytes
     or v_existing.sha256 is distinct from p_sha256
     or v_existing.part_count is distinct from p_part_count
     or v_existing.movement_count is distinct from p_movement_count then
    raise exception 'BACKUP_ID_METADATA_CONFLICT' using errcode = '23505';
  end if;

  if v_existing.status = 'PENDING' then
    update public.inventory_backup_objects b
    set upload_authorized_until = now() + interval '24 hours'
    where b.backup_id = p_backup_id and b.owner_id = v_owner
    returning * into v_existing;
  end if;

  return query select v_existing.backup_id, v_existing.storage_path, v_existing.status;
end;
$$;
