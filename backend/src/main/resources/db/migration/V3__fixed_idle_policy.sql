-- A final save is not the same as an older periodic checkpoint. Persist the cleanup
-- commit so a backend restart or a partially successful Fly delete can be retried.
ALTER TABLE sandboxes ADD COLUMN cleanup_snapshot_id VARCHAR(40);
ALTER TABLE sandboxes ADD COLUMN cleanup_target VARCHAR(30);
ALTER TABLE sandboxes ADD COLUMN policy_retry_at BIGINT;
ALTER TABLE sandboxes ADD COLUMN volume_gb INT;

-- Preserve starting.next_action, which records the recovery source for telemetry.
UPDATE sandboxes SET next_action=NULL,next_action_at=NULL WHERE state<>'starting';
