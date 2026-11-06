# Supabase live migration history — Step139 (2026-11-03)

Project: `ihhxxxqojelbxisreqns`

The following nine migrations were applied successfully through the connected Supabase project and are visible in the project's migration history:

| Version | Migration |
|---|---|
| 20261103100431 | `step139_01_restore_staging_sessions_and_chunks` |
| 20261103100503 | `step139_02_atomic_restore_commit_and_snapshot_readers` |
| 20261103100517 | `step139_03_restore_staging_quotas_and_cleanup` |
| 20261103100523 | `step139_04_single_restore_commit_per_owner` |
| 20261103100558 | `step139_05_require_admin_link_for_global_feed` |
| 20261103100637 | `step139_06_require_commit_ledger_and_admin_link` |
| 20261103101001 | `step139_07_index_restore_and_config_foreign_keys` |
| 20261103101435 | `step139_08_reduce_redundant_rls_policies_and_initplans` |
| 20261103101525 | `step139_09_make_admin_link_read_only_to_clients` |

The active SQL files from Step138 and the preserved `migrations_pending_reconciliation` directory remain in the source ZIP. Do not replay the entire pending directory or run `supabase db push` blindly against the existing manually bootstrapped schema.

All Step139 DDL is additive/replacement of RPC/policy definitions. No business rows, Auth users, Storage objects, or committed restore snapshots were deleted. The rollback tests used synthetic UUIDs and were fully rolled back.
