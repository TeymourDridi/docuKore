-- V6: Create invitations table
-- Token-based staff invitations with secure hashing
-- Tokens are never stored in plaintext; only bcrypt hashes are persisted

CREATE TABLE invitations (
    id UUID PRIMARY KEY,
    cabinet_id UUID NOT NULL REFERENCES cabinets(id) ON DELETE CASCADE,
    invited_email_or_identifier VARCHAR(255) NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    staff_type VARCHAR(50) NOT NULL,
    initial_permission_template VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    accepted_at TIMESTAMPTZ,
    accepted_by UUID REFERENCES users(id) ON DELETE SET NULL,
    rejected_at TIMESTAMPTZ,
    rejected_by UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_staff_type CHECK (
        staff_type IN ('DOCTOR', 'SECRETARY', 'SYSTEM')
    ),
    CONSTRAINT valid_invitation_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'REVOKED')
    )
);

-- Indexes for invitation queries
CREATE INDEX idx_invitations_cabinet_id ON invitations(cabinet_id);
CREATE INDEX idx_invitations_token_hash ON invitations(token_hash);
CREATE INDEX idx_invitations_status ON invitations(status);
CREATE INDEX idx_invitations_expires_at ON invitations(expires_at);
CREATE INDEX idx_invitations_accepted_by ON invitations(accepted_by) WHERE accepted_by IS NOT NULL;
CREATE INDEX idx_invitations_pending ON invitations(cabinet_id, status) WHERE status = 'PENDING';
