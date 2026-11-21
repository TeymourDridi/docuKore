-- V4: Create cabinet_memberships table
-- Defines the relationship between users and cabinets
-- Each membership has a role (OWNER or MEMBER) and status (ACTIVE, INACTIVE, PENDING_INVITATION)

CREATE TABLE cabinet_memberships (
    id UUID PRIMARY KEY,
    cabinet_id UUID NOT NULL REFERENCES cabinets(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    membership_role VARCHAR(50) NOT NULL,
    staff_type VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    invited_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT valid_membership_role CHECK (
        membership_role IN ('OWNER', 'MEMBER')
    ),
    CONSTRAINT valid_staff_type CHECK (
        staff_type IS NULL OR staff_type IN ('DOCTOR', 'SECRETARY', 'SYSTEM')
    ),
    CONSTRAINT valid_status CHECK (
        status IN ('ACTIVE', 'INACTIVE', 'PENDING_INVITATION')
    ),
    CONSTRAINT unique_cabinet_user UNIQUE (cabinet_id, user_id)
);

-- Indexes for membership queries
CREATE INDEX idx_cabinet_memberships_cabinet_id ON cabinet_memberships(cabinet_id);
CREATE INDEX idx_cabinet_memberships_user_id ON cabinet_memberships(user_id);
CREATE INDEX idx_cabinet_memberships_status ON cabinet_memberships(status);
CREATE INDEX idx_cabinet_memberships_membership_role ON cabinet_memberships(membership_role);
CREATE INDEX idx_cabinet_memberships_cabinet_user_status ON cabinet_memberships(cabinet_id, user_id, status);
