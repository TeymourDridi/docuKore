package com.docukore.cabinet.patient;

import com.docukore.cabinet.access.CabinetMembershipRepository;
import com.docukore.identity.CurrentAccount;
import com.docukore.tenant.AuthorizedCabinetAccessWithPermissions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads only a bounded directory page inside an authorized cabinet snapshot. */
@Service
public class PatientListingService
{
    /** Existing bounded server-side cabinet discovery. */
    private final CabinetMembershipRepository memberships;
    /** Existing READ permission and trusted tenant gateway. */
    private final AuthorizedCabinetAccessWithPermissions cabinetAccess;
    /** Authenticated account, never accepted as request input. */
    private final CurrentAccount currentAccount;
    /** Existing patient repository with directory queries. */
    private final PatientRepository patients;
    /** Read-only snapshot opened only after cabinet authorization. */
    private final TransactionTemplate reads;

    public PatientListingService(CabinetMembershipRepository memberships,
                                 AuthorizedCabinetAccessWithPermissions cabinetAccess,
                                 CurrentAccount currentAccount, PatientRepository patients,
                                 @Qualifier("transactionManager") PlatformTransactionManager transactionManager)
    {
        this.memberships = memberships;
        this.cabinetAccess = cabinetAccess;
        this.currentAccount = currentAccount;
        this.patients = patients;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Validate pagination, reuse sole-cabinet discovery and authorize READ before persistence.
     *
     * @param page the zero-based requested page
     * @param size the requested page size, from 1 through 100
     * @param search the optional literal search text
     * @return the cabinet's matching directory page and exact totals
     */
    public PatientPageResponse list(int page, int size, String search)
    {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
        {
            throw new InvalidQuery();
        }
        String term = search == null ? "" : search.strip();
        if (term.length() > 512)
        {
            throw new InvalidQuery();
        }
        var cabinets = memberships.findAvailableCabinets(currentAccount.requireId());
        if (cabinets.isEmpty())
        {
            throw new AccessDeniedException("Cabinet access is denied");
        }
        if (cabinets.size() != 1)
        {
            throw new PatientCreationService.CabinetSelectionRequired();
        }
        return cabinetAccess.executeRead(cabinets.getFirst(), () -> reads.execute(status ->
            patients.findPage(page, size, term)));
    }

    /** Safe pagination/search failure without retaining or echoing the supplied query. */
    static final class InvalidQuery extends RuntimeException
    {
    }
}
