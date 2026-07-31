-- This rollback must not be used after any real ENFORCED aggregate exists.
-- After first enforcement the rollback floor is an owner-capable binary.

DELIMITER $$
CREATE PROCEDURE arch001_assert_rollback_floor()
BEGIN
    IF EXISTS (
        SELECT 1 FROM task_lifecycle_snapshots
        WHERE ownership_mode = 'ENFORCED' LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM session_lifecycle_snapshots
        WHERE ownership_mode = 'ENFORCED' LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM worker_lifecycle_snapshots
        WHERE ownership_mode = 'ENFORCED' LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM lifecycle_writer_generations
        WHERE status IN ('ACTIVE', 'ENFORCED') LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM lifecycle_writer_exclusivity_references
        WHERE released_at IS NULL LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM lifecycle_effect_outbox
        WHERE effect_state NOT IN ('RESULT_OBSERVED', 'COMPLETED', 'REJECTED')
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'ARCH001_ROLLBACK_BLOCKED_ENFORCEMENT_FLOOR';
    END IF;
END$$
DELIMITER ;

CALL arch001_assert_rollback_floor();
DROP PROCEDURE arch001_assert_rollback_floor;

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
