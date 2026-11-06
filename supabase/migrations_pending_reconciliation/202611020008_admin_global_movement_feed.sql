-- Admin-only global movement feed. PART payloads are redacted to SKIP markers
-- so the client can advance a contiguous global cursor without seeing inventories.
begin;

create or replace function public.pull_admin_movement_changes(
    p_after_event_id bigint default 0,
    p_limit integer default 200
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_user uuid := auth.uid();
    v_role text;
    v_events jsonb;
    v_next bigint;
begin
    if v_user is null then
        raise exception 'AUTH_REQUIRED' using errcode = '28000';
    end if;

    select p.role into v_role
      from public.profiles p
     where p.id = v_user and p.is_active = true;

    if not found then
        raise exception 'PROFILE_INACTIVE' using errcode = '42501';
    end if;
    if lower(v_role) <> 'admin' then
        raise exception 'ADMIN_REQUIRED' using errcode = '42501';
    end if;
    if p_after_event_id is null or p_after_event_id < 0 then
        raise exception 'INVALID_CURSOR';
    end if;

    with page as (
        select e.event_id, e.entity_type, e.entity_id, e.owner_id,
               e.payload, e.created_at,
               coalesce(nullif(btrim(pr.display_name), ''),
                        nullif(btrim(pr.username), ''),
                        e.owner_id::text) as owner_name
          from public.inventory_sync_events e
          left join public.profiles pr on pr.id = e.owner_id
         where e.event_id > p_after_event_id
         order by e.event_id asc
         limit greatest(1, least(coalesce(p_limit, 200), 500))
    ),
    shaped as (
        select event_id,
               case when entity_type = 'PART' then 'SKIP' else entity_type end as entity_type,
               case when entity_type = 'PART' then null else entity_id end as entity_id,
               case
                   when entity_type = 'PART' then '{}'::jsonb
                   else jsonb_set(
                       jsonb_set(coalesce(payload, '{}'::jsonb),
                                 '{owner_id}', to_jsonb(owner_id::text), true),
                       '{owner_name}', to_jsonb(owner_name), true)
               end as payload,
               created_at
          from page
    )
    select coalesce(jsonb_agg(jsonb_build_object(
               'event_id', event_id,
               'entity_type', entity_type,
               'entity_id', entity_id,
               'payload', payload,
               'created_at', created_at
           ) order by event_id), '[]'::jsonb),
           coalesce(max(event_id), p_after_event_id)
      into v_events, v_next
      from shaped;

    return jsonb_build_object('events', v_events, 'next_cursor', v_next);
end;
$$;

revoke all on function public.pull_admin_movement_changes(bigint, integer) from public;
grant execute on function public.pull_admin_movement_changes(bigint, integer) to authenticated;

commit;
