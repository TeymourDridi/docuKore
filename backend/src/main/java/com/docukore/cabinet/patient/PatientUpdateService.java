package com.docukore.cabinet.patient;

import com.docukore.audit.AuditService;
import com.docukore.cabinet.access.CabinetMembershipRepository;
import com.docukore.identity.CurrentAccount;
import com.docukore.tenant.AuthorizedCabinetAccessWithPermissions;
import com.docukore.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Updates patient identity and contact lifecycle with mandatory auditing in one authorized tenant transaction. */
@Service
public class PatientUpdateService
{
    /** Existing bounded server-side cabinet discovery. */
    private final CabinetMembershipRepository memberships;
    /** Existing READ_WRITE authorization and trusted tenant gateway. */
    private final AuthorizedCabinetAccessWithPermissions cabinetAccess;
    /** Authenticated actor, never accepted as input. */
    private final CurrentAccount currentAccount;
    /** Existing entity repository and eight-field scalar detail projection. */
    private final PatientRepository patients;
    /** Existing append-only audit service participating in the business transaction. */
    private final AuditService audit;
    /** Factory for the already joined tenant entity manager. */
    private final EntityManagerFactory entityManagers;
    /** Writable boundary opened only after authorization. */
    private final TransactionTemplate writes;
    /** Existing clock for complete-date policy and update timestamps. */
    private final Clock clock;

    public PatientUpdateService(CabinetMembershipRepository memberships,
                                AuthorizedCabinetAccessWithPermissions cabinetAccess,
                                CurrentAccount currentAccount, PatientRepository patients, AuditService audit,
                                EntityManagerFactory entityManagers,
                                @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
                                Clock clock)
    {
        this.memberships = memberships;
        this.cabinetAccess = cabinetAccess;
        this.currentAccount = currentAccount;
        this.patients = patients;
        this.audit = audit;
        this.entityManagers = entityManagers;
        this.writes = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Applies only supplied editable fields in the account's sole available cabinet.
     *
     * @param patientId the requested UUID, never used for tenant selection
     * @param patch the presence-aware document with explicit optional clears
     * @return the existing persisted patient detail response after the atomic update
     */
    public PatientDetailResponse update(UUID patientId, UpdatePatientRequest patch)
    {
        UUID actor = currentAccount.requireId();
        var cabinets = memberships.findAvailableCabinets(actor);
        if (cabinets.isEmpty())
        {
            throw new AccessDeniedException("Cabinet access is denied");
        }
        if (cabinets.size() != 1)
        {
            throw new PatientCreationService.CabinetSelectionRequired();
        }
        return cabinetAccess.executeWrite(cabinets.getFirst(), () ->
        {
            CreatePatientRequest values = patch.normalized();
            String phone = values.phone() == null ? null : PatientCreationService.normalizePhone(values.phone());
            LocalDate birth = PatientCreationService.parseBirthDate(values.dateOfBirth(), clock);
            return writes.execute(status -> persist(patientId, actor, patch, values, phone, birth));
        });
    }

    private PatientDetailResponse persist(UUID patientId, UUID actor, UpdatePatientRequest patch,
                                         CreatePatientRequest values, String phone, LocalDate birth)
    {
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
        if (entityManager == null || !entityManager.isJoinedToTransaction())
        {
            throw new IllegalStateException("Patient update requires a tenant transaction");
        }
        UUID cabinet = TenantContext.requireCabinetId();
        String schema = '"' + TenantContext.getSchemaName() + '"';
        // Lock a cabinet-scoped row inside the joined transaction, never during repository startup parsing.
        var locked = entityManager.createNativeQuery("""
            SELECT id FROM %s.patients WHERE cabinet_id = :cabinet AND id = :patient FOR UPDATE
            """.formatted(schema)).setParameter("cabinet", cabinet).setParameter("patient", patientId)
            .getResultList();
        if (locked.isEmpty())
        {
            throw new PatientDetailService.NotFound();
        }
        Patient patient = patients.findById(patientId).orElseThrow(PatientDetailService.NotFound::new);
        PatientDetailResponse before = patients.findDetail(patientId).orElseThrow(PatientDetailService.NotFound::new);
        boolean changed = applyIdentity(patient, patch, values, birth);
        if (patch.has("phone") && !Objects.equals(phone, before.phone()))
        {
            replacePhone(entityManager, patientId, values.phone(), phone);
            changed = true;
        }
        if (!changed)
        {
            return before;
        }
        patient.setUpdatedBy(actor, clock);
        patients.save(patient);
        entityManager.flush();
        audit.recordEvent(cabinet, "patient", patientId, "UPDATE", null);
        return patients.findDetail(patientId).orElseThrow(PatientDetailService.NotFound::new);
    }

    private boolean applyIdentity(Patient patient, UpdatePatientRequest patch, CreatePatientRequest values,
                                  LocalDate birth)
    {
        boolean changed = false;
        if (patch.has("firstName") && !Objects.equals(values.firstName(), patient.getLegalGivenName()))
        {
            patient.setLegalGivenName(values.firstName(), clock);
            changed = true;
        }
        if (patch.has("familyName") && !Objects.equals(values.familyName(), patient.getLegalFamilyName()))
        {
            patient.setLegalFamilyName(values.familyName(), clock);
            changed = true;
        }
        if (patch.has("dateOfBirth"))
        {
            BirthDate requested = birth == null ? BirthDate.unknown()
                : new BirthDate(birth.getYear(), birth.getMonthValue(), birth.getDayOfMonth(),
                    BirthPrecision.COMPLETE_DATE);
            if (!requested.equals(patient.getBirthDate()))
            {
                patient.setBirthDate(requested, clock);
                changed = true;
            }
        }
        if (patch.has("initialNote") && !Objects.equals(values.initialNote(), patient.getInitialNote()))
        {
            patient.setInitialNote(values.initialNote(), clock);
            changed = true;
        }
        return changed;
    }

    private void replacePhone(EntityManager entityManager, UUID patientId, String raw, String normalized)
    {
        String schema = '"' + TenantContext.getSchemaName() + '"';
        // The locked patient was matched by both cabinet and UUID. Only retire current PHONE/MOBILE history.
        entityManager.createNativeQuery("""
            UPDATE %s.patient_contacts SET is_current = false
            WHERE patient_id = :patient AND contact_type IN ('PHONE', 'MOBILE') AND is_current = true
            """.formatted(schema)).setParameter("patient", patientId).executeUpdate();
        if (normalized == null)
        {
            return;
        }
        int inserted = entityManager.createNativeQuery("""
            INSERT INTO %s.patient_contacts
                (id, patient_id, contact_type, value, value_normalized, is_primary_for_type, is_current, created_at)
            VALUES (:id, :patient, 'PHONE', :value, :normalized, true, true, :created)
            ON CONFLICT (value_normalized)
                WHERE contact_type IN ('PHONE', 'MOBILE') AND is_current = true AND value_normalized IS NOT NULL
            DO NOTHING
            """.formatted(schema))
            .setParameter("id", UUID.randomUUID()).setParameter("patient", patientId)
            .setParameter("value", raw).setParameter("normalized", normalized)
            .setParameter("created", clock.instant()).executeUpdate();
        if (inserted != 1)
        {
            throw new PatientCreationService.PhoneConflict();
        }
    }
}
