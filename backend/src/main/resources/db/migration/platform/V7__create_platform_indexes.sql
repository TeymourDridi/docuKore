-- V7: Create platform schema indexes
-- Additional composite and performance indexes for common query patterns

-- Foreign key indexes (for join performance)
CREATE INDEX idx_cabinets_owner_id_active ON cabinets(owner_id, provisioning_status) WHERE provisioning_status = 'ACTIVE';

-- Composite indexes for common membership queries
CREATE INDEX idx_cabinet_memberships_status_role ON cabinet_memberships(status, membership_role);
CREATE INDEX idx_cabinet_memberships_cabinet_status_active ON cabinet_memberships(cabinet_id, status) WHERE status = 'ACTIVE';

-- Invitation acceptance queries
CREATE INDEX idx_invitations_status_expires ON invitations(status, expires_at);
CREATE INDEX idx_invitations_cabinet_pending_expires ON invitations(cabinet_id, status, expires_at) WHERE status = 'PENDING';
