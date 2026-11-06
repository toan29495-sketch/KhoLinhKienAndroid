# Step 24 — Restore status UX and completed-session cleanup retry

## Implemented
- Kept the Vietnamese phase title/description mapping for active restore journal states.
- Added a separate card for the newest `COMPLETE` journal with part count, movement count, and snapshot cursor.
- Added a retry action that passes the exact restore ID to the existing resume coordinator.
- The coordinator's `COMPLETE` branch performs only owner/cursor-checked local staging cleanup. It does not resend cloud chunks, recommit, or apply the snapshot again.
- Updated user feedback to distinguish a newly completed restore from a cleanup retry on an already completed journal.

## Safety review
- The action uses the journal's exact restore ID and checks the current owner before invoking the coordinator.
- `cleanupCompleted` requires journal status `COMPLETE`, matching owner, matching snapshot cursor, and a canonical staging directory under the app's restore staging root.
- Cleanup removes only local staging rows/files. It does not alter live inventory, movement history, outbox, sync cursor, or cloud Storage objects.
- No Room schema change or migration was introduced.

## Not done / not verified
- No Gradle build, Kotlin compiler, unit/instrumentation tests, device test, or APK build was run.
- SQL migrations and Supabase RPC/Storage policies have not been executed against a project.
- The UI does not claim a percentage because durable per-phase chunk progress is not yet stored in the journal.
- The existing outbox guard remains: newly created unsent operations can block a later apply; they are never silently discarded.
