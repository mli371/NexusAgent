ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_status_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_status_check CHECK (
    status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'RECOVERY_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED')
);
ALTER TABLE agent_runs ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0 CHECK (recovery_count >= 0);
ALTER TABLE agent_runs ADD COLUMN cancel_requested_at TIMESTAMPTZ;

ALTER TABLE agent_approvals DROP CONSTRAINT agent_approvals_status_check;
ALTER TABLE agent_approvals ADD CONSTRAINT agent_approvals_status_check CHECK (
    status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')
);
