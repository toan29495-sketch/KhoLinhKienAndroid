# Step 23 — Safe restore UI integration and interrupted-session resume

## Implemented
- Replaced the MainActivity ZIP restore call with `RestoreCloudCoordinator.importAndRestore`.
- Restricted the document picker to ZIP formats. The legacy JSON direct Room replacement path is no longer reachable from the restore UI; JSON is rejected with an explanatory message.
- Added owner-scoped restore journal loading in the Tools screen.
- Added an explicit “Continue restore” action for the current owner's unfinished journal, passing its exact `restoreId` to the coordinator.
- Disabled starting another restore while an unfinished journal exists or restore work is in progress.
- Added a busy indicator and refreshes the journal list after success or failure.
- The UI reports completion counts and whether local staging cleanup completed.

## Safety behavior
- The coordinator remains the only ZIP restore entrypoint from UI.
- Existing inventory is not directly replaced by the UI. The snapshot applier remains the only live Room replacement path after cloud commit and verification.
- JSON legacy direct restore is intentionally blocked to avoid bypassing cloud verification and restore journaling.
- An unfinished restore must be resumed before a new restore can begin.

## Not done / not verified
- No Gradle build, Kotlin compiler, unit tests, device test, or APK build was run.
- SQL migrations and Supabase RPC/Storage policies have not been executed against a project.
- Step 24 adds Vietnamese phase titles/descriptions; no percentage is shown because the journal does not yet store durable per-phase progress counters.
- A restore may be blocked if new local outbox operations are created while it is running; the coordinator deliberately refuses to apply rather than deleting those operations.
- Step 24 surfaces a safe retry action for local cleanup on a `COMPLETE` journal.
