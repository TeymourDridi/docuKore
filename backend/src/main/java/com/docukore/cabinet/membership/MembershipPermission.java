package com.docukore.cabinet.membership;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Membership permission assignment.
 *
 * Represents a granular permission granted to a cabinet membership.
 * Supports grant/revoke history: multiple records per (membership_id, permission_code)
 * with only one active grant enforced by partial unique index WHERE revoked_at IS NULL.
 */
@Entity
@Table(name = "membership_permissions", schema = "public")
public class MembershipPermission
{

    /** Permission grant identifier. */
    @Id
    private UUID id;

    /** Membership receiving this permission grant. */
    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    /** Granted permission code, such as READ or READ_WRITE. */
    @Column(name = "permission_code", nullable = false, length = 100)
    private String permissionCode;

    /** Time the permission was granted. */
    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    /** Account that granted this permission. */
    @Column(name = "granted_by")
    private UUID grantedBy;

    /** Time the permission was revoked, or null if the grant is active. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected MembershipPermission()
    {
    }

    /**
     * Create a new active permission grant.
     *
     * @param id the permission record UUID
     * @param membershipId the cabinet membership UUID
     * @param permissionCode the permission code
     * @param grantedAt the grant timestamp
     * @param grantedBy the user who granted this permission
     */
    public MembershipPermission(UUID id, UUID membershipId, String permissionCode,
            OffsetDateTime grantedAt, UUID grantedBy)
    {
        this.id = id;
        this.membershipId = membershipId;
        this.permissionCode = permissionCode;
        this.grantedAt = grantedAt;
        this.grantedBy = grantedBy;
        this.revokedAt = null;
    }

    public UUID id()
    {
        return id;
    }

    public UUID membershipId()
    {
        return membershipId;
    }

    public String permissionCode()
    {
        return permissionCode;
    }

    public OffsetDateTime grantedAt()
    {
        return grantedAt;
    }

    public UUID grantedBy()
    {
        return grantedBy;
    }

    public OffsetDateTime revokedAt()
    {
        return revokedAt;
    }

    /**
     * Check if this permission grant is currently active.
     *
     * @return true if revoked_at IS NULL
     */
    public boolean isActive()
    {
        return revokedAt == null;
    }
}
