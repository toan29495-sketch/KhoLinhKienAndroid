# Step148 — Partition Compose UI state by authenticated owner

Date: 2026-11-03

## Baseline
- Continued directly from the verified Step147 archive; no older ZIP was used as a source.
- No Supabase, Room schema, account, Storage object, or server-data mutation.

## Finding
The authenticated screen is hosted by one `InventoryApp` composition. Although most data reads and worker operations are already owner-scoped, several transient Compose `remember` values were not keyed by `profile.id`. During an account switch, a still-retained composition could therefore carry UI-only state such as a selected part, open edit/add dialog, search query, pending photo path, restore URI/preview, restore progress flags, or conflict-resolution choices into the next account's screen.

## Change
- Keyed 24 authenticated-screen state holders to `remember(profile.id)`.
- Covered conflict resolution, search/selection/dialog state, QR scan and machine-history selection, pending photo path, restore picker/preview/journals/busy state, and batch import state.
- Existing profile-keyed state (Admin history, tab, selected blocked operation, machine history rows, profile refresh) remains unchanged.
- A profile switch now creates a fresh state slot for the new account. It does not delete old owner's pending photo files, outbox rows, restore journal, or backup files; those remain available to the correct owner.
- Backup/restore continue to validate expected owner and owner-bound journal. New pending photos remain under `files/pending_part_photos/<ownerId>/`.

## Verification
- `tools/verify_step148_account_switch_ui_state.py`: 31/31 PASS (source assertions plus state-key isolation model).
- Regression: Step140 13/13; Step141 15/15; Step142 12/12; Step143 11/11; Step144 14/14; Step145 12/12; Step146 12/12; Step147 12/12.
- `python -m compileall -q tools`: PASS.
- ZIP integrity check: to be recorded after packaging.

## Limits
- This is source/static verification; no Android Compose runtime, WorkManager runtime, device account-switch test, or APK build was performed.
- No Gradle was run or used.
- Legacy pending-photo paths directly under `files/pending_part_photos/` are still accepted by compatibility checks in sender/backup code. Because a legacy path does not encode its owner, this compatibility path deserves a separate audit before a production rollout. No cleanup/deletion was attempted.
