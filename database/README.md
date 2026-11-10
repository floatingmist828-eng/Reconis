# Reconis database

SQL Server schema and migrations for payments, immutable transaction entries, reconciliation and audit history.

| Files | Purpose |
| --- | --- |
| `001_schema.sql` | Base tables and columns |
| `002_constraints_indexes.sql` | Integrity constraints and indexes |
| `003_views_functions_procedures.sql` | Views, functions and business procedures |
| `004_triggers_fulltext.sql` | Triggers and full-text search |
| `005_bootstrap_admin.sql` | Initial administrator setup |
| `migrations/` | Incremental schema updates |
| `seed_demo.sql`, `demo/` | Sample merchants and transactions |

Use `scripts/initialize.ps1` for a new database and `scripts/migrate.ps1` for an existing database, from the repository root. Back up existing data before migration. DEMO and ALIPAY_SANDBOX use separate databases; sample transactions belong to DEMO.

See the [runbook](../docs/runbook.md) for configuration, backup and restore.
