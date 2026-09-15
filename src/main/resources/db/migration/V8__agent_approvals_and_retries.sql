ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_status_check;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_status_check
    CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
ALTER TABLE agent_runs ADD COLUMN round_count INTEGER NOT NULL DEFAULT 0 CHECK (round_count >= 0);

CREATE TABLE agent_model_rounds (
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    reservation_id UUID NOT NULL,
    PRIMARY KEY (run_id, reservation_id)
);

CREATE TABLE agent_approvals (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id),
    action TEXT NOT NULL CHECK (action IN ('CHUNK', 'EMBED_MISSING')),
    arguments JSONB NOT NULL,
    arguments_hash TEXT NOT NULL,
    state_fingerprint TEXT NOT NULL,
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    requested_by TEXT NOT NULL,
    decided_by TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX idx_agent_approvals_pending ON agent_approvals(run_id) WHERE status='PENDING';
CREATE INDEX idx_agent_approvals_run ON agent_approvals(run_id, created_at);

CREATE TABLE agent_action_executions (
    id UUID PRIMARY KEY,
    approval_id UUID NOT NULL UNIQUE REFERENCES agent_approvals(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'UNKNOWN')),
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_agent_executions_run ON agent_action_executions(run_id);

ALTER TABLE ingestion_jobs ADD COLUMN error_code TEXT;
ALTER TABLE ingestion_jobs ADD COLUMN agent_execution_id UUID UNIQUE
    REFERENCES agent_action_executions(id);
ALTER TABLE ingestion_jobs ADD COLUMN trace_id TEXT;
