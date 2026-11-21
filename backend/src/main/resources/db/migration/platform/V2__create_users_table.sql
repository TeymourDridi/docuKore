-- V2: Create users table
-- Global user identity across all cabinets
-- Email is mandatory for hosted deployments; optional for local installations
-- Username is case-insensitive; stored as provided but uniqueness enforced on normalized (lowercase) value
-- Email allows multiple NULL values but enforces uniqueness on non-null normalized values

CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    username_normalized VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255),
    email_normalized VARCHAR(255),
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    deployment_mode VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    account_status VARCHAR(50) NOT NULL DEFAULT 'PENDING_ACTIVATION',
    CONSTRAINT email_not_blank CHECK (email IS NULL OR email <> ''),
    CONSTRAINT valid_account_status CHECK (
        account_status IN ('PENDING_ACTIVATION', 'ACTIVE', 'LOCKED', 'SUSPENDED')
    ),
    CONSTRAINT valid_deployment_mode CHECK (
        deployment_mode IS NULL OR deployment_mode IN ('local', 'hosted')
    )
);

-- Partial unique index: only enforce uniqueness on non-null normalized emails
CREATE UNIQUE INDEX idx_users_email_normalized_unique ON users(email_normalized) WHERE email_normalized IS NOT NULL;

-- Regular indexes for authentication and lookup
CREATE INDEX idx_users_username_normalized ON users(username_normalized);
CREATE INDEX idx_users_created_at ON users(created_at DESC);
