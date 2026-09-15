CREATE TABLE agent_runs (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    trace_id TEXT NOT NULL,
    question TEXT NOT NULL CHECK (length(question) BETWEEN 1 AND 2000),
    execution_mode TEXT NOT NULL CHECK (execution_mode IN ('scripted', 'pi')),
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    protocol_version INTEGER NOT NULL DEFAULT 1,
    prompt_version TEXT NOT NULL DEFAULT 'diagnostics-v1',
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    version BIGINT NOT NULL DEFAULT 0,
    idempotency_key TEXT,
    request_hash TEXT NOT NULL,
    claim_hash TEXT,
    attempt INTEGER NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    tool_count INTEGER NOT NULL DEFAULT 0,
    event_sequence BIGINT NOT NULL DEFAULT 0,
    report JSONB,
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, actor_id, idempotency_key)
);

CREATE INDEX idx_agent_runs_owner ON agent_runs (tenant_id, actor_id, created_at DESC);
CREATE INDEX idx_agent_runs_queue ON agent_runs (status, created_at);
-- Phase 1 deliberately permits only one active worker assignment globally.
CREATE UNIQUE INDEX idx_agent_runs_one_active ON agent_runs (status) WHERE status = 'RUNNING';

CREATE TABLE agent_run_documents (
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id),
    PRIMARY KEY (run_id, document_id)
);

CREATE TABLE agent_tool_calls (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    invocation_id UUID NOT NULL,
    tool_name TEXT NOT NULL,
    arguments JSONB NOT NULL,
    arguments_hash TEXT NOT NULL,
    retry_of UUID,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    UNIQUE (run_id, invocation_id)
);

CREATE TABLE agent_run_events (
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, sequence)
);
