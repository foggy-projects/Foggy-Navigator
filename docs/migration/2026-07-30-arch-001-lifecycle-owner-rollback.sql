-- This rollback must not be used after any real ENFORCED aggregate exists.
-- After first enforcement the rollback floor is an owner-capable binary.

DROP TABLE IF EXISTS worker_lifecycle_sentinel_leases;
DROP TABLE IF EXISTS lifecycle_writer_exclusivity_references;
DROP TABLE IF EXISTS lifecycle_writer_exclusivity_proofs;
DROP TABLE IF EXISTS lifecycle_writer_instance_registrations;
DROP TABLE IF EXISTS lifecycle_writer_generations;
DROP TABLE IF EXISTS task_terminal_cleanup_plan;
DROP TABLE IF EXISTS task_terminal_tombstones;
DROP TABLE IF EXISTS lifecycle_effect_outbox;
DROP TABLE IF EXISTS session_lifecycle_snapshots;
DROP TABLE IF EXISTS task_lifecycle_snapshots;
DROP TABLE IF EXISTS worker_lifecycle_snapshots;
DROP TABLE IF EXISTS lifecycle_facts;
