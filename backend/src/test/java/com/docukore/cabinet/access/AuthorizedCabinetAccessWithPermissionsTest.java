package com.docukore.cabinet.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.docukore.identity.CurrentAccount;
import com.docukore.tenant.AuthorizedCabinetAccess;
import com.docukore.tenant.AuthorizedCabinetAccessWithPermissions;

/**
 * Unit tests for AuthorizedCabinetAccessWithPermissions.
 *
 * Tests permission-enforced cabinet operations and cross-cabinet denial.
 * Does not require database infrastructure or actual tenant contexts.
 */
class AuthorizedCabinetAccessWithPermissionsTest
{

    /** Permission-enforcing cabinet access under test. */
    private AuthorizedCabinetAccessWithPermissions service;

    /** Mock cabinet access used to execute authorized operations. */
    private AuthorizedCabinetAccess baseAccess;

    /** Mock service used to resolve cabinet permissions. */
    private CabinetAuthorizationService authorizationService;

    /** Mock authenticated account used by the access checks. */
    private CurrentAccount currentAccount;

    /** Fictional identifier for the cabinet granting access. */
    private final UUID firstCabinetId = UUID.randomUUID();

    /** Fictional identifier for the cabinet rejecting cross-cabinet access. */
    private final UUID secondCabinetId = UUID.randomUUID();

    /** Fictional identifier for the member account. */
    private final UUID userAccountId = UUID.randomUUID();

    /** Fictional identifier for the cabinet owner account. */
    private final UUID ownerAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp()
    {
        baseAccess = mock(AuthorizedCabinetAccess.class);
        authorizationService = mock(CabinetAuthorizationService.class);
        currentAccount = mock(CurrentAccount.class);
        service = new AuthorizedCabinetAccessWithPermissions(baseAccess, authorizationService,
                currentAccount);
    }

    @Test
    @DisplayName("READ operation succeeds with READ permission")
    void readOperationSucceedsWithReadPermission()
    {
        when(currentAccount.requireId()).thenReturn(userAccountId);
        when(authorizationService.hasPermission(firstCabinetId, userAccountId, Permission.READ)).thenReturn(true);
        when(baseAccess.execute(eq(firstCabinetId), any())).thenAnswer(invocation ->
        {
            var operation = invocation.<java.util.function.Supplier<?>>getArgument(1);
            return operation.get();
        });

        String result = service.executeRead(firstCabinetId, () -> "success");

        assertEquals("success", result);
        verify(authorizationService).hasPermission(firstCabinetId, userAccountId, Permission.READ);
        verify(baseAccess).execute(eq(firstCabinetId), any());
    }

    @Test
    @DisplayName("READ operation fails with no permission")
    void readOperationFailsWithNoPermission()
    {
        when(currentAccount.requireId()).thenReturn(userAccountId);
        when(authorizationService.hasPermission(firstCabinetId, userAccountId, Permission.READ))
            .thenReturn(false);
        when(authorizationService.resolvePermission(firstCabinetId, userAccountId))
            .thenReturn(Permission.NONE);

        assertThrows(com.docukore.cabinet.access.AccessDeniedException.class,
                () -> service.executeRead(firstCabinetId, () -> "success"));

        verify(baseAccess, never()).execute(any(), any());
    }

    @Test
    @DisplayName("WRITE operation succeeds with READ_WRITE permission")
    void writeOperationSucceedsWithReadWritePermission()
    {
        when(currentAccount.requireId()).thenReturn(userAccountId);
        when(authorizationService.hasPermission(firstCabinetId, userAccountId, Permission.READ_WRITE))
            .thenReturn(true);
        when(baseAccess.execute(eq(firstCabinetId), any())).thenAnswer(invocation ->
        {
            var operation = invocation.<java.util.function.Supplier<?>>getArgument(1);
            return operation.get();
        });

        String result = service.executeWrite(firstCabinetId, () -> "created");

        assertEquals("created", result);
        verify(authorizationService).hasPermission(firstCabinetId, userAccountId, Permission.READ_WRITE);
        verify(baseAccess).execute(eq(firstCabinetId), any());
    }

    @Test
    @DisplayName("WRITE operation fails with READ permission only")
    void writeOperationFailsWithReadPermissionOnly()
    {
        when(currentAccount.requireId()).thenReturn(userAccountId);
        when(authorizationService.hasPermission(firstCabinetId, userAccountId, Permission.READ_WRITE))
            .thenReturn(false);
        when(authorizationService.resolvePermission(firstCabinetId, userAccountId)).thenReturn(Permission.READ);

        assertThrows(com.docukore.cabinet.access.AccessDeniedException.class,
                () -> service.executeWrite(firstCabinetId, () -> "created"));

        verify(baseAccess, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Cross-cabinet access denied (cabinet 1 permission, cabinet 2 access)")
    void crossCabinetAccessDenied()
    {
        when(currentAccount.requireId()).thenReturn(userAccountId);
        when(authorizationService.hasPermission(firstCabinetId, userAccountId, Permission.READ))
            .thenReturn(true);
        when(authorizationService.hasPermission(secondCabinetId, userAccountId, Permission.READ))
            .thenReturn(false);
        when(authorizationService.resolvePermission(secondCabinetId, userAccountId)).thenReturn(Permission.NONE);

        // User has READ in firstCabinetId, but not in secondCabinetId
        assertThrows(com.docukore.cabinet.access.AccessDeniedException.class,
                () -> service.executeRead(secondCabinetId, () -> "data"));

        // Should not access secondCabinetId
        verify(baseAccess, never()).execute(eq(secondCabinetId), any());
    }

    @Test
    @DisplayName("OWNER member bypasses explicit permission checks (READ_WRITE by role)")
    void ownerMemberBypassesExplicitPermissions()
    {
        when(currentAccount.requireId()).thenReturn(ownerAccountId);
        when(authorizationService.hasPermission(firstCabinetId, ownerAccountId, Permission.READ_WRITE))
            .thenReturn(true);
        when(baseAccess.execute(eq(firstCabinetId), any())).thenAnswer(invocation ->
        {
            var operation = invocation.<java.util.function.Supplier<?>>getArgument(1);
            return operation.get();
        });

        String result = service.executeWrite(firstCabinetId, () -> "owner_action");

        assertEquals("owner_action", result);
        verify(baseAccess).execute(eq(firstCabinetId), any());
    }

    @Test
    @DisplayName("Permission check error message includes permission levels")
    void permissionErrorMessageIncludesLevels()
    {
        when(currentAccount.requireId()).thenReturn(userAccountId);
        when(authorizationService.hasPermission(firstCabinetId, userAccountId, Permission.READ_WRITE))
            .thenReturn(false);
        when(authorizationService.resolvePermission(firstCabinetId, userAccountId)).thenReturn(Permission.READ);

        com.docukore.cabinet.access.AccessDeniedException ex = assertThrows(
                com.docukore.cabinet.access.AccessDeniedException.class,
                () -> service.executeWrite(firstCabinetId, () -> null));

        assertTrue(ex.getMessage().contains("READ"), "Error should mention actual permission");
        assertTrue(ex.getMessage().contains("READ_WRITE"), "Error should mention required permission");
    }

    @Test
    @DisplayName("execute() with explicit permission level")
    void executeWithExplicitPermissionLevel()
    {
        when(currentAccount.requireId()).thenReturn(userAccountId);
        when(authorizationService.hasPermission(firstCabinetId, userAccountId, Permission.READ))
            .thenReturn(true);
        when(baseAccess.execute(eq(firstCabinetId), any())).thenAnswer(invocation ->
        {
            var operation = invocation.<java.util.function.Supplier<?>>getArgument(1);
            return operation.get();
        });

        String result = service.execute(firstCabinetId, Permission.READ, () -> "data");

        assertEquals("data", result);
        verify(authorizationService).hasPermission(firstCabinetId, userAccountId, Permission.READ);
    }
}
