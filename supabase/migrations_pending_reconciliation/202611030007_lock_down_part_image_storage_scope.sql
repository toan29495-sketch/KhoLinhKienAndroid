-- Step95: enforce owner-folder isolation as a RESTRICTIVE Storage policy.
-- Existing permissive policies may grant access, but none may widen this guard.
-- Every part-images object must live under <auth.uid()>/... and belong to an
-- active profile. Other buckets are unaffected by this policy.
begin;

drop policy if exists part_images_owner_scope_guard on storage.objects;
create policy part_images_owner_scope_guard
on storage.objects
as restrictive
for all
to authenticated
using (
    bucket_id <> 'part-images'
    or (
        public.is_active_profile()
        and coalesce((storage.foldername(name))[1], '') = (select auth.uid())::text
    )
)
with check (
    bucket_id <> 'part-images'
    or (
        public.is_active_profile()
        and coalesce((storage.foldername(name))[1], '') = (select auth.uid())::text
    )
);

commit;
