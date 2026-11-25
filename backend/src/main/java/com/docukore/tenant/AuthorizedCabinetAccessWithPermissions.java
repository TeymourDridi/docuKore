package com.docukore.tenant;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.docukore.cabinet.access.AccessDeniedException;
import com.docukore.cabinet.access.CabinetAuthorizationService;
import com.docukore.cabinet.access.Permission;
import com.docukore.identity.CurrentAccount;

/**
 * Cabinet access with granular permission enforcement.
 *
 * Extends the base AuthorizedCabinetAccess with per-operation permission checks.
 * Verifies that the current user has the required permission level before executing
 * operations within a cabinet context.
 *
 * Authorization flow:
 * 1. Resolve the authenticated user from SecurityContext
 * 2. Query the user's active membership in the cabinet
 * 3. Query the user's active permissions for that membership
 * 4. Check if the required permission level is satisfied
 * 5. If authorized, open tenant context and execute operation
 * 6. If not authorized, throw AccessDeniedException (not Spring's)
 *
 * Tenant isolation is mandatory: permission enforcement respects tenant boundaries.
 */
@Component
public class AuthorizedCabinetAccessWithPermissions
{

    /** Opens the authorized cabinet's isolated tenant context. */
    private final AuthorizedCabinetAccess baseAccess;

    /** Enforces the required cabinet permission level. */
    private final CabinetAuthorizationService authorizationService;

    /** Resolves the authenticated account. */
    private final CurrentAccount currentAccount;

    public AuthorizedCabinetAccessWithPermissions(AuthorizedCabinetAccess baseAccess,
            CabinetAuthorizationService authorizationService, CurrentAccount currentAccount)
    {
        this.baseAccess = baseAccess;
        this.authorizationService = authorizationService;
        this.currentAccount = currentAccount;
    }

    /**
     * Execute an operation within a cabinet context with permission verification.
     *
     * - Resolves the authenticated user
     * - Verifies active membership in the cabinet
     * - Checks the required permission level
     * - Opens tenant context (tenant isolation enforced by baseAccess)
     * - Executes the operation
     *
     * @param cabinetId the cabinet UUID
     * @param requiredPermission the minimum permission level required
     * @param operation the operation to execute
     * @param <T> the result type
     * @return the operation's result
     * @throws com.docukore.cabinet.access.AccessDeniedException if permission check fails
     * @throws IllegalStateException if tenant context is already present
     */
    public <T> T execute(UUID cabinetId, Permission requiredPermission, Supplier<T> operation)
    {
        UUID userId = currentAccount.requireId();

        // Verify permission
        if (!authorizationService.hasPermission(cabinetId, userId, requiredPermission))
        {
            Permission actual = authorizationService.resolvePermission(cabinetId, userId);
            throw new AccessDeniedException("User has " + actual + " but requires "
                    + requiredPermission + " in cabinet");
        }

        // Execute within tenant context (baseAccess handles cabinet association + isolation)
        return baseAccess.execute(cabinetId, operation);
    }

    /**
     * Execute a READ operation (minimum READ permission required).
     *
     * @param cabinetId the cabinet UUID
     * @param operation the operation to execute
     * @param <T> the result type
     * @return the operation's result
     */
    public <T> T executeRead(UUID cabinetId, Supplier<T> operation)
    {
        return execute(cabinetId, Permission.READ, operation);
    }

    /**
     * Execute a WRITE operation (minimum READ_WRITE permission required).
     *
     * @param cabinetId the cabinet UUID
     * @param operation the operation to execute
     * @param <T> the result type
     * @return the operation's result
     */
    public <T> T executeWrite(UUID cabinetId, Supplier<T> operation)
    {
        return execute(cabinetId, Permission.READ_WRITE, operation);
    }
}
