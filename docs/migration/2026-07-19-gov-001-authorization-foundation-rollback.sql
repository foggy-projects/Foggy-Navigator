-- GOV-001 P1A authorization foundation destructive rollback.
--
-- This is intentionally NOT an operational rollback and must never be run as
-- part of application startup, incident mitigation, or a normal code rollback.
-- The normal P1A rollback keeps these additive tables and all append-only
-- authorization_decision evidence, while legacy enforcement remains in force.
--
-- Before a controlled schema removal: stop new writes, retain/export required
-- decision evidence under the deployment retention policy, obtain explicit
-- owner approval, and verify that no later P1B/P1C deployment depends on the
-- tables. This script neither restores legacy data nor issues/revokes/copies
-- any credential or token.

DROP TABLE IF EXISTS authorization_decision;
DROP TABLE IF EXISTS authorization_tenant_authority;
DROP TABLE IF EXISTS authorization_platform_grant;
DROP TABLE IF EXISTS authorization_management_token;
DROP TABLE IF EXISTS authorization_credential;
DROP TABLE IF EXISTS authorization_principal;
