package com.docukore.cabinet.membership;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Cabinet membership record.
 *
 * Defines the relationship between users and cabinets with roles and permissions.
 * Each membership has a role (OWNER or MEMBER) and status (ACTIVE, INACTIVE,
 * PENDING_INVITATION).
 */
@Entity
@Table(name = "cabinet_memberships", schema = "public")
public class CabinetMembership
{

    /** Membership identifier. */
    @Id
    private UUID id;

    /** Cabinet associated with this membership. */
    @Column(name = "cabinet_id", nullable = false)
    private UUID cabinetId;

    /** Account associated with this membership. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Membership role, OWNER or MEMBER. */
    @Column(name = "membership_role", nullable = false, length = 50)
    private String membershipRole;

    /** Staff type, or null when not specified. */
    @Column(name = "staff_type", length = 50)
    private String staffType;

    /** Membership lifecycle status. */
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    /** Time the membership invitation was issued. */
    @Column(name = "invited_at")
    private OffsetDateTime invitedAt;

    /** Time the membership was activated. */
    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    /** Time the membership was revoked, or null if not revoked. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected CabinetMembership()
    {
    }

    /**
     * Create a cabinet membership.
     *
     * @param id the membership UUID
     * @param cabinetId the cabinet UUID
     * @param userId the user UUID
     * @param membershipRole the role (OWNER or MEMBER)
     * @param staffType the staff type or null
     * @param status the status
     */
    public CabinetMembership(UUID id, UUID cabinetId, UUID userId, String membershipRole,
            String staffType, String status)
    {
        this.id = id;
        this.cabinetId = cabinetId;
        this.userId = userId;
        this.membershipRole = membershipRole;
        this.staffType = staffType;
        this.status = status;
    }

    public UUID id()
    {
        return id;
    }

    public UUID cabinetId()
    {
        return cabinetId;
    }

    public UUID userId()
    {
        return userId;
    }

    public String membershipRole()
    {
        return membershipRole;
    }

    public String staffType()
    {
        return staffType;
    }

    public String status()
    {
        return status;
    }

    public OffsetDateTime invitedAt()
    {
        return invitedAt;
    }

    public OffsetDateTime activatedAt()
    {
        return activatedAt;
    }

    public OffsetDateTime revokedAt()
    {
        return revokedAt;
    }

    /**
     * Check if this membership is currently active.
     *
     * @return true if status is ACTIVE and revoked_at IS NULL
     */
    public boolean isActive()
    {
        return "ACTIVE".equals(status) && revokedAt == null;
    }
}
