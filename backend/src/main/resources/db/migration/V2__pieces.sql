-- Typed connections + polling cursors for the pieces framework.
ALTER TABLE credentials ADD COLUMN IF NOT EXISTS auth_type TEXT NOT NULL DEFAULT 'generic';
ALTER TABLE credentials ADD COLUMN IF NOT EXISTS app TEXT;

CREATE TABLE IF NOT EXISTS trigger_state (
  workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
  node_id TEXT NOT NULL,
  cursor_key TEXT NOT NULL DEFAULT 'id',
  cursor_value TEXT,
  checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (workflow_id, node_id)
);
