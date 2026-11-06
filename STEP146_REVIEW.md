# Step146 review — Admin-global worker role fence (03/11/2026)

## Finding
`SyncOutboxSender` already fences its work by authenticated owner at the start, before each outbox claim, and around Storage/RPC boundaries. `SyncPullWorker` already checked owner ID after the feed RPC and before each event. But an Admin-global worker could continue processing a page fetched under an Admin role after the same account was downgraded to KTV while the app was backgrounded.

## Change
- Admin-global pull now checks the authenticated owner and authoritative `profiles` row (`is_active=true`, `role=admin`) before starting.
- Rechecks role after the RPC response, after processing each page, and once more before returning.
- If the profile is no longer active Admin, stop the worker, clear only foreign-owner movement cache, and reset only the Admin-global cursor. The user's own movement history and owner cursor remain intact.
- A profile-query transport error is not treated as downgrade; the existing outer exception handler returns `Result.retry()`.
- Normal KTV owner-feed remains independent of Admin role. Existing scheduler cancellation on downgrade remains in place.

## Verification
- `tools/verify_step146_admin_worker_role_fence.py`: 12/12 PASS.
- Step145 cold-start restore: 12/12 PASS.
- Step144 foreground profile refresh: 14/14 PASS.
- Step143 local foreign-cache cleanup: 11/11 PASS.
- Step142 remote reconciliation: 12/12 PASS.
- Step141 restore contract: 15/15 PASS.
- Step140 cloud photo memory-only: 13/13 PASS.
- Python compileall PASS.

## Limits / unchanged
- Static source verification only. No Android/Compose compilation, WorkManager runtime, device test, APK build/install, QR test, two-device test, or GitHub push.
- No Gradle use.
- No Supabase schema, SQL, RPC, Edge Function, Auth, Storage, or server data changes.
- No business data deletion. Cleanup affects only local Room cache rows owned by other KTVs and the Admin-global cursor.
