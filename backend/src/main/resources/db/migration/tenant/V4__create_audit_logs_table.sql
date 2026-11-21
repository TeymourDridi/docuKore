-- V4: Create audit logs table
-- Comprehensive audit trail for tenant operations

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    cabinet_id UUID NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID NOT NULL,
    operation VARCHAR(50) NOT NULL,
    user_id UUID NOT NULL,
    user_role VARCHAR(50),
    timestamp TIMESTAMPTZ NOT NULL,
    ip_address INET,
    user_agent TEXT,
    changed_fields JSONB,
    previous_values JSONB,
    new_values JSONB,
    metadata JSONB,
    CONSTRAINT valid_operation CHECK (
        operation IN ('CREATE', 'UPDATE', 'DELETE', 'VIEW', 'EXPORT', 'PRINT', 'SHARE')
    )
);

-- Indexes for audit queries
CREATE INDEX idx_audit_logs_cabinet_timestamp ON audit_logs(cabinet_id, timestamp DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id, timestamp DESC);
CREATE INDEX idx_audit_logs_user ON audit_logs(user_id, timestamp DESC);
CREATE INDEX idx_audit_logs_operation ON audit_logs(cabinet_id, operation, timestamp DESC);
