-- A durable singleton serializes first-run setup, including across app instances.
-- Existing installations are closed even if their original users are later removed.
CREATE TABLE installation_setup (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    first_owner_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    completed_at TIMESTAMPTZ,
    CONSTRAINT installation_setup_owner_requires_completion
        CHECK (first_owner_id IS NULL OR completed_at IS NOT NULL)
);

INSERT INTO installation_setup (id, completed_at)
SELECT 1, CASE WHEN EXISTS (SELECT 1 FROM users) THEN CURRENT_TIMESTAMP ELSE NULL END;
