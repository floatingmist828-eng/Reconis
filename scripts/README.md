# Reconis development scripts

Run PowerShell scripts from the repository root. Environment configuration and credentials stay in the ignored `private/` directory.

| Script | Purpose |
| --- | --- |
| `bootstrap.ps1` | Prepare development tools |
| `build.ps1` | Test and package the application |
| `initialize.ps1` | Initialize a database and local configuration |
| `migrate.ps1` | Upgrade an existing database |
| `start.ps1` | Start the application or check configuration |
| `select-release.ps1` | Select a release or roll back |
| `backup.ps1`, `verify-restore.ps1` | Back up and verify database recovery |
| `verify-business-flow.ps1`, `verify-browser.ps1` | Check API workflows and browser interactions |

Use `build.ps1 -NoSelect` to build without changing the selected release. Database integration tests require `-DatabaseTests` and a configured SQL Server instance. See the [runbook](../docs/runbook.md) for prerequisites and environment-specific commands.
