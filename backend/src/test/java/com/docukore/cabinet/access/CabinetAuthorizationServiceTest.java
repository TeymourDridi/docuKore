package com.docukore.cabinet.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.docukore.cabinet.membership.CabinetMembership;
import com.docukore.cabinet.membership.MembershipPermission;
import com.docukore.cabinet.membership.MembershipPermissionRepository;

/**
 * Unit tests for CabinetAuthorizationService.
 *
 * Tests permission resolution, permission checking, and OWNER bypass.
 * Does not require database infrastructure.
 */
class CabinetAuthorizationServiceTest
{

    /** Cabinet authorization service under test. */
    private CabinetAuthorizationService service;

    /** Mock repository used to resolve active cabinet memberships. */
    private CabinetMembershipRepository membershipRepo;

    /** Mock repository used to resolve explicit membership permissions. */
    private MembershipPermissionRepository permissionRepo;

    /** Fictional identifier for the cabinet being authorized. */
    private final UUID cabinetId = UUID.randomUUID();

    /** Fictional identifier for the member account. */
    private final UUID userAccountId = UUID.randomUUID();

    /** Fictional identifier for the cabinet owner account. */
    private final UUID ownerAccountId = UUID.randomUUID();

    /** Fictional identifier for the active cabinet membership. */
    private final UUID membershipId = UUID.randomUUID();

    @BeforeEach
    void setUp()
    {
        membershipRepo = mock(CabinetMembershipRepository.class);
        permissionRepo = mock(MembershipPermissionRepository.class);
        service = new CabinetAuthorizationService(membershipRepo, permissionRepo);
    }

    @Test
    @DisplayName("OWNER members have READ_WRITE permission")
    void ownerMembersHaveFullAccess()
    {
        CabinetMembership membership = new CabinetMembership(membershipId, cabinetId, ownerAccountId, "OWNER",
                null, "ACTIVE");
        when(membershipRepo.findActiveMembership(cabinetId, ownerAccountId)).thenReturn(Optional.of(membership));

        Permission result = service.resolvePermission(cabinetId, ownerAccountId);

        assertEquals(Permission.READ_WRITE, result);
    }

    @Test
    @DisplayName("MEMBER with READ permission")
    void memberWithReadPermission()
    {
        CabinetMembership membership = new CabinetMembership(membershipId, cabinetId, userAccountId, "MEMBER",
                null, "ACTIVE");
        MembershipPermission permission = new MembershipPermission(UUID.randomUUID(), membershipId,
                "READ", OffsetDateTime.now(), ownerAccountId);

        when(membershipRepo.findActiveMembership(cabinetId, userAccountId)).thenReturn(Optional.of(membership));
        when(permissionRepo.hasActivePermissions(membershipId)).thenReturn(true);
        when(permissionRepo.findActivePermission(membershipId, "READ_WRITE"))
            .thenReturn(Optional.empty());
        when(permissionRepo.findActivePermission(membershipId, "READ")).thenReturn(Optional.of(permission));

        Permission result = service.resolvePermission(cabinetId, userAccountId);

        assertEquals(Permission.READ, result);
    }

    @Test
    @DisplayName("MEMBER with READ_WRITE permission")
    void memberWithReadWritePermission()
    {
        CabinetMembership membership = new CabinetMembership(membershipId, cabinetId, userAccountId, "MEMBER",
                null, "ACTIVE");
        MembershipPermission permission = new MembershipPermission(UUID.randomUUID(), membershipId,
                "READ_WRITE", OffsetDateTime.now(), ownerAccountId);

        when(membershipRepo.findActiveMembership(cabinetId, userAccountId)).thenReturn(Optional.of(membership));
        when(permissionRepo.hasActivePermissions(membershipId)).thenReturn(true);
        when(permissionRepo.findActivePermission(membershipId, "READ_WRITE"))
            .thenReturn(Optional.of(permission));

        Permission result = service.resolvePermission(cabinetId, userAccountId);

        assertEquals(Permission.READ_WRITE, result);
    }

    @Test
    @DisplayName("MEMBER with no permissions denied by default")
    void memberWithNoPermissionsDeniedByDefault()
    {
        CabinetMembership membership = new CabinetMembership(membershipId, cabinetId, userAccountId, "MEMBER",
                null, "ACTIVE");

        when(membershipRepo.findActiveMembership(cabinetId, userAccountId)).thenReturn(Optional.of(membership));
        when(permissionRepo.hasActivePermissions(membershipId)).thenReturn(false);

        Permission result = service.resolvePermission(cabinetId, userAccountId);

        assertEquals(Permission.NONE, result);
    }

    @Test
    @DisplayName("User without membership denied")
    void userWithoutMembershipDenied()
    {
        when(membershipRepo.findActiveMembership(cabinetId, userAccountId)).thenReturn(Optional.empty());

        Permission result = service.resolvePermission(cabinetId, userAccountId);

        assertEquals(Permission.NONE, result);
    }

    @Test
    @DisplayName("Permission.permits() checks READ_WRITE permits all")
    void readWritePermitsAll()
    {
        assertTrue(Permission.READ_WRITE.permits(Permission.READ_WRITE));
        assertTrue(Permission.READ_WRITE.permits(Permission.READ));
        assertTrue(Permission.READ_WRITE.permits(Permission.NONE));
    }

    @Test
    @DisplayName("Permission.permits() checks READ permits only READ")
    void readPermitsOnlyRead()
    {
        assertFalse(Permission.READ.permits(Permission.READ_WRITE));
        assertTrue(Permission.READ.permits(Permission.READ));
        assertTrue(Permission.READ.permits(Permission.NONE));
    }

    @Test
    @DisplayName("hasPermission() checks permission chain")
    void hasPermissionChecksChain()
    {
        CabinetMembership membership = new CabinetMembership(membershipId, cabinetId, userAccountId, "MEMBER",
                null, "ACTIVE");
        MembershipPermission permission = new MembershipPermission(UUID.randomUUID(), membershipId,
                "READ", OffsetDateTime.now(), ownerAccountId);

        when(membershipRepo.findActiveMembership(cabinetId, userAccountId)).thenReturn(Optional.of(membership));
        when(permissionRepo.hasActivePermissions(membershipId)).thenReturn(true);
        when(permissionRepo.findActivePermission(membershipId, "READ_WRITE"))
            .thenReturn(Optional.empty());
        when(permissionRepo.findActivePermission(membershipId, "READ")).thenReturn(Optional.of(permission));

        assertTrue(service.hasPermission(cabinetId, userAccountId, Permission.READ));
        assertFalse(service.hasPermission(cabinetId, userAccountId, Permission.READ_WRITE));
    }

    @Test
    @DisplayName("isOwner() returns true for OWNER role")
    void isOwnerChecksRole()
    {
        CabinetMembership ownerMembership = new CabinetMembership(membershipId, cabinetId, ownerAccountId,
                "OWNER", null, "ACTIVE");

        when(membershipRepo.findActiveMembership(cabinetId, ownerAccountId))
            .thenReturn(Optional.of(ownerMembership));

        assertTrue(service.isOwner(cabinetId, ownerAccountId));
    }

    @Test
    @DisplayName("isOwner() returns false for MEMBER role")
    void isOwnerReturnsFalseForMember()
    {
        CabinetMembership memberMembership = new CabinetMembership(membershipId, cabinetId, userAccountId,
                "MEMBER", null, "ACTIVE");

        when(membershipRepo.findActiveMembership(cabinetId, userAccountId))
            .thenReturn(Optional.of(memberMembership));

        assertFalse(service.isOwner(cabinetId, userAccountId));
    }

    @Test
    @DisplayName("isOwner() returns false for user without membership")
    void isOwnerReturnsFalseForNonMember()
    {
        when(membershipRepo.findActiveMembership(cabinetId, userAccountId)).thenReturn(Optional.empty());

        assertFalse(service.isOwner(cabinetId, userAccountId));
    }
}
