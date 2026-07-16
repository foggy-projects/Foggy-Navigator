-- Destructive rollback for 2026-07-16-error-diagnostics.sql.
-- Disable NAVIGATOR_ERROR_DIAGNOSTIC_PUBLIC_SHARING_ENABLED, stop all launcher
-- instances, export any still-needed redacted diagnostics, then run this file.

DROP TABLE IF EXISTS error_diagnostic_shares;
DROP TABLE IF EXISTS error_diagnostics;
