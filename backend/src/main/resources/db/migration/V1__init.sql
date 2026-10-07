CREATE TABLE IF NOT EXISTS workflows (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  definition_json JSONB NOT NULL DEFAULT '{}',
  status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','VALID')),
  version INT NOT NULL DEFAULT 1,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS executions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
  workflow_version INT NOT NULL,
  definition_snapshot_json JSONB NOT NULL,
  status TEXT NOT NULL DEFAULT 'QUEUED'
    CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILED','CANCELLED')),
  trigger_type TEXT NOT NULL DEFAULT 'manual',
  trigger_payload_json JSONB NOT NULL DEFAULT '{}',
  result_json JSONB,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  error_message TEXT
);

CREATE TABLE IF NOT EXISTS execution_nodes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
  node_id TEXT NOT NULL,
  attempt INT NOT NULL DEFAULT 1,
  status TEXT NOT NULL DEFAULT 'WAITING'
    CHECK (status IN ('WAITING','RUNNING','SUCCESS','FAILED','SKIPPED','CANCELLED')),
  input_json JSONB,
  output_json JSONB,
  output_size_bytes INT NOT NULL DEFAULT 0,
  truncated BOOLEAN NOT NULL DEFAULT FALSE,
  error_message TEXT,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS execution_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
  seq INT NOT NULL,
  type TEXT NOT NULL,
  node_id TEXT,
  payload_json JSONB NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (execution_id, seq)
);

CREATE TABLE IF NOT EXISTS llm_tier_state (
  tier_id TEXT PRIMARY KEY,
  state TEXT NOT NULL DEFAULT 'AVAILABLE',
  until TIMESTAMPTZ,
  used_today INT NOT NULL DEFAULT 0,
  day_utc DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE TABLE IF NOT EXISTS credentials (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT UNIQUE NOT NULL,
  provider TEXT NOT NULL DEFAULT 'custom',
  encrypted_value BYTEA NOT NULL,
  nonce BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_executions_workflow_started
  ON executions (workflow_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_execution_nodes_execution
  ON execution_nodes (execution_id);
CREATE INDEX IF NOT EXISTS idx_execution_events_execution_seq
  ON execution_events (execution_id, seq);
