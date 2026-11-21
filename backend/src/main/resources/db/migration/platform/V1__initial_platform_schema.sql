-- Initial platform schema baseline
-- This schema will contain platform-level metadata for the multi-tenant SaaS.
-- Future migrations will add tenant provisioning and platform configuration tables.
-- Tenant-specific clinical and business data will reside in separate schemas.

-- Platform metadata placeholder
-- This baseline establishes Flyway history without creating business tables yet.

CREATE TABLE IF NOT EXISTS platform_info (
    id SERIAL PRIMARY KEY,
    schema_version VARCHAR(50) NOT NULL,
    initialized_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_info (schema_version) VALUES ('0.1.0-baseline');
