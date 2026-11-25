package com.docukore.cabinet.patient;

import com.docukore.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Scalar-only patient listing using the trusted schema and existing tenant transaction. */
@Repository
public class PatientListingQueriesImpl implements PatientListingQueries
{
    /** Cabinet predicate shared by both count and page queries. */
    private static final String CABINET_PREDICATE = " WHERE p.cabinet_id = :cabinet";

    /** Literal search predicate, with trusted schema and optional normalized phone clause substituted. */
    private static final String SEARCH_PREDICATE = """
         AND (
             p.legal_given_name ILIKE :search ESCAPE '!'
             OR p.legal_family_name ILIKE :search ESCAPE '!'
             OR (p.legal_given_name || ' ' || p.legal_family_name) ILIKE :search ESCAPE '!'
             OR p.patient_number ILIKE :search ESCAPE '!'
             OR EXISTS (
                 SELECT 1 FROM %s.patient_contacts search_contact
                 WHERE search_contact.patient_id = p.id
                     AND search_contact.contact_type IN ('PHONE', 'MOBILE')
                     AND search_contact.is_current = true
                     AND (
                         search_contact.value ILIKE :search ESCAPE '!'
                         OR search_contact.value_normalized ILIKE :search ESCAPE '!'
                         %s
                     )
             )
         )
        """;

    /** Additional phone search is present only for a phone-shaped term containing a digit. */
    private static final String NORMALIZED_PHONE_PREDICATE =
        "OR search_contact.value ILIKE :phone_search ESCAPE '!'"
            + " OR search_contact.value_normalized ILIKE :phone_search ESCAPE '!'";

    /** Only the seven public listing fields; the current phone choice is deterministic. */
    private static final String PAGE_SELECT = """
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
            p.created_at AS created_at
        FROM %s.patients p
        """;

    /** Factory used to obtain the joined, transaction-bound tenant entity manager. */
    private final EntityManagerFactory entityManagers;

    public PatientListingQueriesImpl(EntityManagerFactory entityManagers)
    {
        this.entityManagers = entityManagers;
    }

    @Override
    public PatientPageResponse findPage(int page, int size, String search)
    {
        UUID cabinet = TenantContext.requireCabinetId();
        String schema = '"' + TenantContext.getSchemaName() + '"';
        var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
        if (entityManager == null || !entityManager.isJoinedToTransaction())
        {
            throw new IllegalStateException("Patient listing requires a tenant transaction");
        }
        Session session = entityManager.unwrap(Session.class);
        String searchPattern = search == null || search.isEmpty() ? null : literalPattern(search);
        String phonePattern = searchPattern == null ? null : normalizedPhonePattern(search);
        String where = CABINET_PREDICATE;
        if (searchPattern != null)
        {
            where += SEARCH_PREDICATE.formatted(schema,
                phonePattern == null ? "" : NORMALIZED_PHONE_PREDICATE);
        }

        NativeQuery<Long> countQuery = session.createNativeQuery(
            "SELECT COUNT(*) AS total_elements FROM " + schema + ".patients p" + where, Long.class)
            .addScalar("total_elements", Long.class);
        bindPredicates(countQuery, cabinet, searchPattern, phonePattern);
        long total = countQuery.getSingleResult();

        NativeQuery<Object[]> pageQuery = session.createNativeQuery(
            PAGE_SELECT.formatted(schema, schema) + where + " ORDER BY p.created_at DESC, p.id DESC", Object[].class)
            .addScalar("patient_id", UUID.class)
            .addScalar("patient_number", String.class)
            .addScalar("first_name", String.class)
            .addScalar("family_name", String.class)
            .addScalar("phone", String.class)
            .addScalar("date_of_birth", LocalDate.class)
            .addScalar("created_at", Instant.class);
        bindPredicates(pageQuery, cabinet, searchPattern, phonePattern);
        pageQuery.setFirstResult(Math.toIntExact((long) page * size));
        pageQuery.setMaxResults(size);
        List<PatientListItem> content = pageQuery.getResultList().stream()
            .map(PatientListingQueriesImpl::toListItem).toList();
        int totalPages = Math.toIntExact(total / size + (total % size == 0 ? 0 : 1));
        return new PatientPageResponse(content, page, size, total, totalPages);
    }

    private static void bindPredicates(NativeQuery<?> query, UUID cabinet, String searchPattern, String phonePattern)
    {
        query.setParameter("cabinet", cabinet);
        if (searchPattern != null)
        {
            query.setParameter("search", searchPattern);
        }
        if (phonePattern != null)
        {
            query.setParameter("phone_search", phonePattern);
        }
    }

    private static String literalPattern(String term)
    {
        return "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String normalizedPhonePattern(String term)
    {
        if (!term.matches("[+0-9 ().-]+") || term.chars().noneMatch(Character::isDigit))
        {
            return null;
        }
        String normalized = term.replaceAll("[ ().-]", "");
        if (normalized.startsWith("00"))
        {
            normalized = "+" + normalized.substring(2);
        }
        return normalized.chars().noneMatch(Character::isDigit) ? null : literalPattern(normalized);
    }

    private static PatientListItem toListItem(Object[] row)
    {
        return new PatientListItem((UUID) row[0], (String) row[1], (String) row[2], (String) row[3],
            (String) row[4], (LocalDate) row[5], (Instant) row[6]);
    }
}
