-- V5: Create membership_permissions table
-- Granular permission assignments per membership
-- Permissions are owner-controlled and revalidated on every request (not cached)
-- Allows grant/revoke history: multiple records per (membership_id, permission_code) pair
-- Only ONE active grant per permission enforced via partial unique index WHERE revoked_at IS NULL

CREATE TABLE membership_permissions (
    id UUID PRIMARY KEY,
    membership_id UUID NOT NULL REFERENCES cabinet_memberships(id) ON DELETE CASCADE,
    permission_code VARCHAR(100) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    granted_by UUID REFERENCES users(id) ON DELETE SET NULL,
    revoked_at TIMESTAMPTZ
);

-- Partial unique index: only one active grant per membership and permission
CREATE UNIQUE INDEX idx_membership_permissions_active_unique ON membership_permissions(membership_id, permission_code) WHERE revoked_at IS NULL;

-- Indexes for permission queries
CREATE INDEX idx_membership_permissions_membership_id ON membership_permissions(membership_id);
CREATE INDEX idx_membership_permissions_permission_code ON membership_permissions(permission_code);
CREATE INDEX idx_membership_permissions_active_by_membership ON membership_permissions(membership_id) WHERE revoked_at IS NULL;
