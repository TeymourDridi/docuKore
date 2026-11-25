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

import java.util.UUID;

/** Reads persisted patient detail only after server-side cabinet discovery and READ authorization. */
@Service
public class PatientDetailService
{
    /** Existing bounded server-side cabinet discovery. */
    private final CabinetMembershipRepository memberships;
    /** Existing READ permission and trusted tenant gateway. */
    private final AuthorizedCabinetAccessWithPermissions cabinetAccess;
    /** Authenticated account, never accepted as request input. */
    private final CurrentAccount currentAccount;
    /** Existing patient repository with scalar detail queries. */
    private final PatientRepository patients;
    /** Read-only snapshot opened only after cabinet authorization. */
    private final TransactionTemplate reads;

    public PatientDetailService(CabinetMembershipRepository memberships,
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
     * Reuse sole-cabinet discovery and authorize READ before querying patient detail.
     *
     * @param patientId the requested patient UUID, never used to select a cabinet or schema
     * @return the persisted public detail in the authorized cabinet
     */
    public PatientDetailResponse get(UUID patientId)
    {
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
            patients.findDetail(patientId).orElseThrow(NotFound::new)));
    }

    /** Indistinguishable missing/foreign patient failure without retaining the supplied identifier. */
    static final class NotFound extends RuntimeException
    {
    }
}
