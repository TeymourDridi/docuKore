-- V3: Create cabinets table
-- Cabinet represents a private medical practice (tenant)
-- Each cabinet has its own isolated PostgreSQL schema

CREATE TABLE cabinets (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    schema_name VARCHAR(63) NOT NULL UNIQUE,
    provisioning_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    provisioned_at TIMESTAMPTZ,
    last_provisioning_error TEXT,
    CONSTRAINT valid_provisioning_status CHECK (
        provisioning_status IN ('PENDING', 'PROVISIONING', 'ACTIVE', 'PROVISIONING_FAILED')
    ),
    CONSTRAINT unique_owner_cabinet_name UNIQUE (owner_id, name)
);

-- Indexes for cabinet lookup and status queries
CREATE INDEX idx_cabinets_owner_id ON cabinets(owner_id);
CREATE INDEX idx_cabinets_provisioning_status ON cabinets(provisioning_status);
CREATE INDEX idx_cabinets_created_at ON cabinets(created_at DESC);
CREATE INDEX idx_cabinets_schema_name ON cabinets(schema_name);
