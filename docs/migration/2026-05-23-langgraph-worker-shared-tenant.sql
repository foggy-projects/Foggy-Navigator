-- Purpose:
--   Mark the local/dev LangGraph BizWorker used by TMS frame-report smoke as a shared worker.
--
-- Background:
--   TMS OpenAPI frame-report reads failed with "LangGraph worker tenant mismatch" when:
--     langgraph_tasks.tenant_id = 88800
--     langgraph_tasks.worker_id = dev-langgraph-worker-20260504123547
--     langgraph_workers.tenant_id = tenant_upstream_sandbox
--
-- Ownership rule:
--   langgraph_workers.tenant_id IS NULL means the worker is shared across tenants.
--   langgraph_workers.tenant_id IS NOT NULL means the worker is dedicated to that tenant.
--
-- Safety:
--   This script is idempotent for the known stale local/dev row. It only updates the exact
--   stale tenant value and becomes a no-op after the worker has already been marked shared.
--   Review worker_id/base_url before applying to non-local environments.

START TRANSACTION;

UPDATE langgraph_workers
SET tenant_id = NULL
WHERE worker_id = 'dev-langgraph-worker-20260504123547'
  AND tenant_id = 'tenant_upstream_sandbox';

SELECT worker_id, tenant_id, base_url, status
FROM langgraph_workers
WHERE worker_id = 'dev-langgraph-worker-20260504123547';

COMMIT;
