package com.docukore.cabinet.patient;

import com.docukore.audit.AuditService;
import com.docukore.cabinet.access.CabinetMembershipRepository;
import com.docukore.identity.CurrentAccount;
import com.docukore.tenant.AuthorizedCabinetAccessWithPermissions;
import com.docukore.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;

/** Creates only patient identity, its supplied phone contact and a mandatory audit event. */
@Service
public class PatientCreationService
{
    /** Platform memberships used for server-side cabinet discovery. */
    private final CabinetMembershipRepository memberships;
    /** Existing operation permission and trusted tenant gateway. */
    private final AuthorizedCabinetAccessWithPermissions cabinetAccess;
    /** Authenticated creator, never supplied by the request. */
    private final CurrentAccount currentAccount;
    /** Existing tenant patient persistence. */
    private final PatientRepository patients;
    /** Existing append-only auditing. */
    private final AuditService audit;
    /** Factory for the same transaction-bound tenant entity manager. */
    private final EntityManagerFactory entityManagers;
    /** Writable transaction opened only after tenant authorization. */
    private final TransactionTemplate writes;
    /** Existing application clock for date policy and timestamps. */
    private final Clock clock;

    public PatientCreationService(CabinetMembershipRepository memberships,
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
     * Authorize the account's sole available cabinet, then commit all creation records atomically.
     *
     * @param request the validated manual creation information
     * @return the saved identity and backend-generated identifiers
     */
    public PatientCreationResponse create(CreatePatientRequest request)
    {
        UUID actor = currentAccount.requireId();
        var cabinets = memberships.findAvailableCabinets(actor);
        if (cabinets.isEmpty())
        {
            throw new AccessDeniedException("Cabinet access is denied");
        }
        if (cabinets.size() != 1)
        {
            throw new CabinetSelectionRequired();
        }
        String phone = normalizePhone(request.phone());
        LocalDate birth = parseBirthDate(request.dateOfBirth(), clock);
        try
        {
            return cabinetAccess.executeWrite(cabinets.getFirst(), () -> writes.execute(status ->
                persist(request, actor, phone, birth)));
        } catch (RuntimeException failure)
        {
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
            {
                if (cause instanceof ConstraintViolationException violation
                    && "unique_patient_number".equals(violation.getConstraintName()))
                {
                    throw new NumberConflict();
                }
            }
            throw failure;
        }
    }

    private PatientCreationResponse persist(CreatePatientRequest request, UUID actor, String phone, LocalDate birth)
    {
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
        if (entityManager == null || !entityManager.isJoinedToTransaction())
        {
            throw new IllegalStateException("Patient creation requires a tenant transaction");
        }
        UUID cabinet = TenantContext.requireCabinetId();
        String schema = '"' + TenantContext.getSchemaName() + '"';
        long number = ((Number) entityManager.createNativeQuery("SELECT nextval(CAST(:sequence AS regclass))")
            .setParameter("sequence", schema + ".patient_number_seq").getSingleResult()).longValue();
        String patientNumber = String.format(Locale.ROOT, "PAT-%06d", number);
        Patient patient = new Patient(UUID.randomUUID(), cabinet, patientNumber, request.firstName(),
            request.familyName(), actor, clock);
        if (birth != null)
        {
            patient.setBirthDate(new BirthDate(birth.getYear(), birth.getMonthValue(), birth.getDayOfMonth(),
                BirthPrecision.COMPLETE_DATE), clock);
        }
        patient.setInitialNote(request.initialNote(), clock);
        Patient saved = patients.save(patient);
        entityManager.flush();
        int inserted = entityManager.createNativeQuery("""
            INSERT INTO %s.patient_contacts
                (id, patient_id, contact_type, value, value_normalized, is_primary_for_type, is_current, created_at)
            VALUES (:id, :patient, 'PHONE', :value, :normalized, true, true, :created)
            ON CONFLICT (value_normalized)
                WHERE contact_type IN ('PHONE', 'MOBILE') AND is_current = true AND value_normalized IS NOT NULL
            DO NOTHING
            """.formatted(schema))
            .setParameter("id", UUID.randomUUID()).setParameter("patient", saved.getId())
            .setParameter("value", request.phone()).setParameter("normalized", phone)
            .setParameter("created", saved.getCreatedAt()).executeUpdate();
        if (inserted != 1)
        {
            throw new PhoneConflict();
        }
        audit.recordEvent(cabinet, "patient", saved.getId(), "CREATE", null);
        return new PatientCreationResponse(saved.getId(), saved.getPatientNumber(), saved.getLegalGivenName(),
            saved.getLegalFamilyName(), phone, birth, saved.getInitialNote(), saved.getCreatedAt());
    }

    static String normalizePhone(String raw)
    {
        if (raw == null || !raw.matches("\\+?[0-9 ().-]+"))
        {
            throw new InvalidInput("phone");
        }
        String digits = raw.replaceAll("[ ().-]", "");
        String normalized;
        if (digits.startsWith("+"))
        {
            normalized = digits;
        } else if (digits.startsWith("00"))
        {
            normalized = "+" + digits.substring(2);
        } else if (digits.matches("[0-9]{8}"))
        {
            normalized = "+216" + digits;
        } else
        {
            throw new InvalidInput("phone");
        }
        if (!normalized.matches("\\+[1-9][0-9]{6,14}"))
        {
            throw new InvalidInput("phone");
        }
        return normalized;
    }

    static LocalDate parseBirthDate(String raw, Clock clock)
    {
        if (raw == null)
        {
            return null;
        }
        try
        {
            LocalDate date = LocalDate.parse(raw);
            if (date.getYear() <= 0 || date.isAfter(LocalDate.now(clock)))
            {
                throw new InvalidInput("dateOfBirth");
            }
            return date;
        } catch (DateTimeParseException failure)
        {
            throw new InvalidInput("dateOfBirth");
        }
    }

    /** Safe field category for invalid input, without its supplied value. */
    static final class InvalidInput extends RuntimeException
    {
        /** Public contract field name, never a request value. */
        private final String field;

        InvalidInput(String field)
        {
            super("Invalid patient input");
            this.field = field;
        }

        String field()
        {
            return field;
        }
    }

    /** Multiple available memberships cannot silently select a cabinet. */
    static final class CabinetSelectionRequired extends RuntimeException
    {
    }

    /** The existing tenant phone uniqueness rule rejected the contact. */
    static final class PhoneConflict extends RuntimeException
    {
    }

    /** The existing cabinet-local patient number constraint rejected the number. */
    static final class NumberConflict extends RuntimeException
    {
    }
}
