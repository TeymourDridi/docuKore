package com.docukore.tenant;

import com.docukore.cabinet.access.CabinetMembershipRepository;
import com.docukore.identity.CurrentAccount;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.function.Supplier;

/** Cabinet association only; operation permissions and entitlements remain separate. */
@Component
public final class AuthorizedCabinetAccess
{

    /** Current account provider. */
    private final CurrentAccount currentAccount;

    /** Repository for cabinet membership checks. */
    private final CabinetMembershipRepository memberships;

    /** Tenant access manager. */
    private final CabinetTenantAccess tenantAccess;

    public AuthorizedCabinetAccess(CurrentAccount currentAccount, CabinetMembershipRepository memberships,
            CabinetTenantAccess tenantAccess)
    {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.tenantAccess = tenantAccess;
    }

    /**
     * Execute a cabinet-scoped operation with tenant context and authorization.
     *
     * @param cabinetId the cabinet UUID to authorize and open tenant context for
     * @param operation the operation to execute within the authorized tenant context
     * @param <T>       the result type of the operation
     * @return the result of the operation
     * @throws IllegalStateException     if tenant context is already present or a transaction is active
     * @throws IllegalArgumentException  if cabinetId or operation is null
     * @throws AccessDeniedException     if the current user has no active membership in the cabinet
     */
    public <T> T execute(UUID cabinetId, Supplier<T> operation)
    {
        if (TenantContext.isPresent() || TransactionSynchronizationManager.isActualTransactionActive())
        {
            throw new IllegalStateException("Authorize cabinet before starting tenant persistence");
        }
        if (cabinetId == null || cabinetId.equals(new UUID(0, 0)) || operation == null)
        {
            throw new IllegalArgumentException("A valid cabinet UUID and operation are required");
        }
        UUID userId = currentAccount.requireId();
        if (!memberships.hasActiveMembership(cabinetId, userId))
        {
            throw new AccessDeniedException("Cabinet access is denied");
        }
        try (var ignored = tenantAccess.open(cabinetId))
        {
            return operation.get();
        }
    }
}