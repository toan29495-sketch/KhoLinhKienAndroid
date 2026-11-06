# Step 20 review — Admin global movement feed

## Implemented
- Added `pull_admin_movement_changes(p_after_event_id, p_limit)` migration.
- Requires authenticated, active profile and `role=admin` (case-insensitive).
- Returns global MOVEMENT and RESTORE_COMMIT events, enriched with owner_id and owner_name.
- Redacts PART events to payload-free `SKIP` markers so event ordering/cursor continuity is preserved without exposing inventory rows.
- SyncPullWorker consumes SKIP without changing inventory and stores MOVEMENT under the actual owner from payload.

## Static checks
- RPC name and parameter names match SyncPullWorker.
- Role name matches MainActivity's `profile.role.equals("admin", ignoreCase = true)`.
- UserProfile exposes role, display_name, username, is_active.
- SQL does not return PART event payload or entity ID.

## Not verified
- SQL has not been executed against PostgreSQL/Supabase.
- Kotlin/Gradle compilation and tests have not been run.
- No APK build, deployment, or device test.
