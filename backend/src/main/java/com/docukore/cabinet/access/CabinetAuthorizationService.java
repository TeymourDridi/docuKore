package com.docukore.cabinet.access;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.docukore.cabinet.membership.CabinetMembership;
import com.docukore.cabinet.membership.MembershipPermissionRepository;

/**
 * Centralized cabinet authorization service.
 *
 * Resolves permission levels for users in cabinets and enforces authorization rules.
 *
 * Authorization follows these rules:
 * - OWNER members have full access (READ_WRITE).
 * - MEMBER members must have explicit permission grants from the owner.
 * - Permissions are checked against membership_permissions table (active only).
 * - Users without active membership in a cabinet have NONE permission.
 * - Missing permissions deny by default (fail-safe).
 * - Permission levels form a hierarchy: READ_WRITE permits all, READ permits READ only.
 */
@Service
public class CabinetAuthorizationService
{

    /** Resolves active cabinet memberships. */
    private final CabinetMembershipRepository membershipRepository;

    /** Resolves explicit permission grants for cabinet memberships. */
    private final MembershipPermissionRepository permissionRepository;

    public CabinetAuthorizationService(CabinetMembershipRepository membershipRepository,
            MembershipPermissionRepository permissionRepository)
    {
        this.membershipRepository = membershipRepository;
        this.permissionRepository = permissionRepository;
    }

    /**
     * Resolve the permission level for a user in a cabinet.
     *
     * - OWNER members return READ_WRITE (full access).
     * - MEMBER members are checked against membership_permissions table.
     * - If a MEMBER has no active permissions, returns NONE (deny by default).
     * - Users without active membership return NONE.
     *
     * @param cabinetId the cabinet UUID
     * @param userId the user UUID
     * @return the resolved Permission level (READ_WRITE, READ, or NONE)
     */
    public Permission resolvePermission(UUID cabinetId, UUID userId)
    {
        // Find active membership
        Optional<CabinetMembership> membership = membershipRepository.findActiveMembership(cabinetId,
                userId);

        if (membership.isEmpty())
        {
            return Permission.NONE;
        }

        CabinetMembership m = membership.get();

        // OWNER members have full access
        if ("OWNER".equals(m.membershipRole()))
        {
            return Permission.READ_WRITE;
        }

        // MEMBER members: check explicit permissions
        // If no permissions granted, deny by default
        if (!permissionRepository.hasActivePermissions(m.id()))
        {
            return Permission.NONE;
        }

        // Check for READ_WRITE permission first
        if (permissionRepository.findActivePermission(m.id(), "READ_WRITE").isPresent())
        {
            return Permission.READ_WRITE;
        }

        // Check for READ permission
        if (permissionRepository.findActivePermission(m.id(), "READ").isPresent())
        {
            return Permission.READ;
        }

        // No recognizable permissions
        return Permission.NONE;
    }

    /**
     * Check if a user has at least the required permission level in a cabinet.
     *
     * @param cabinetId the cabinet UUID
     * @param userId the user UUID
     * @param required the minimum required permission level
     * @return true if the user's resolved permission permits the required level
     */
    public boolean hasPermission(UUID cabinetId, UUID userId, Permission required)
    {
        Permission actual = resolvePermission(cabinetId, userId);
        return actual.permits(required);
    }

    /**
     * Check if a user is an OWNER of a cabinet.
     *
     * @param cabinetId the cabinet UUID
     * @param userId the user UUID
     * @return true if the user has OWNER role and active membership
     */
    public boolean isOwner(UUID cabinetId, UUID userId)
    {
        return membershipRepository.findActiveMembership(cabinetId, userId)
            .map(m -> "OWNER".equals(m.membershipRole()))
            .orElse(false);
    }
}
