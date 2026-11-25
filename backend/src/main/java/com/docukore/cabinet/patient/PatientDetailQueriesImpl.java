package com.docukore.cabinet.patient;

import com.docukore.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Scalar-only patient detail using the trusted schema and existing tenant transaction. */
@Repository
public class PatientDetailQueriesImpl implements PatientDetailQueries
{
    /** Only the eight public fields, with the same deterministic current phone choice as listing. */
    private static final String DETAIL_SELECT = """
        SELECT p.id AS patient_id,
            p.patient_number AS patient_number,
            p.legal_given_name AS first_name,
            p.legal_family_name AS family_name,
            (
                SELECT contact.value_normalized FROM %s.patient_contacts contact
                WHERE contact.patient_id = p.id
                    AND contact.contact_type IN ('PHONE', 'MOBILE')
                    AND contact.is_current = true
                ORDER BY contact.is_primary_for_type DESC NULLS LAST,
                    CASE WHEN contact.contact_type = 'PHONE' THEN 0 ELSE 1 END,
                    contact.created_at DESC, contact.id DESC
                LIMIT 1
            ) AS phone,
            CASE WHEN p.birth_precision = 'COMPLETE_DATE'
                THEN make_date(p.birth_year, p.birth_month, p.birth_day)
                ELSE NULL
            END AS date_of_birth,
            p.initial_note AS initial_note,
            p.created_at AS created_at
        FROM %s.patients p
        WHERE p.cabinet_id = :cabinet AND p.id = :patient_id
        """;

    /** Factory used to obtain the joined, transaction-bound tenant entity manager. */
    private final EntityManagerFactory entityManagers;

    public PatientDetailQueriesImpl(EntityManagerFactory entityManagers)
    {
        this.entityManagers = entityManagers;
    }

    @Override
    public Optional<PatientDetailResponse> findDetail(UUID patientId)
    {
        UUID cabinet = TenantContext.requireCabinetId();
        String schema = '"' + TenantContext.getSchemaName() + '"';
        var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
        if (entityManager == null || !entityManager.isJoinedToTransaction())
        {
            throw new IllegalStateException("Patient detail requires a tenant transaction");
        }
        Session session = entityManager.unwrap(Session.class);
        NativeQuery<Object[]> query = session.createNativeQuery(
            DETAIL_SELECT.formatted(schema, schema), Object[].class)
            .addScalar("patient_id", UUID.class)
            .addScalar("patient_number", String.class)
            .addScalar("first_name", String.class)
            .addScalar("family_name", String.class)
            .addScalar("phone", String.class)
            .addScalar("date_of_birth", LocalDate.class)
            .addScalar("initial_note", String.class)
            .addScalar("created_at", Instant.class);
        query.setParameter("cabinet", cabinet);
        query.setParameter("patient_id", patientId);
        return query.getResultList().stream().findFirst().map(PatientDetailQueriesImpl::toDetail);
    }

    private static PatientDetailResponse toDetail(Object[] row)
    {
        return new PatientDetailResponse((UUID) row[0], (String) row[1], (String) row[2], (String) row[3],
            (String) row[4], (LocalDate) row[5], (String) row[6], (Instant) row[7]);
    }
}
