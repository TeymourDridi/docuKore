package com.docukore.platform.cabinet;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class CabinetRegistrationRepository
{

    /** JdbcTemplate for platform schema access. */
    private final JdbcTemplate platformJdbc;

    /** Transaction template for registration operations. */
    private final TransactionTemplate transactions;

    /** Validated, quoted SQL identifier; never supplied by a registration request. */
    private final String platformSchema;

    public CabinetRegistrationRepository(DataSource dataSource,
                                         PlatformTransactionManager transactionManager,
                                         String platformSchema)
    {
        if (platformSchema == null || !platformSchema.matches("[a-z][a-z0-9_]{0,62}"))
        {
            throw new IllegalArgumentException("Invalid platform schema identifier");
        }
        this.platformSchema = '"' + platformSchema + '"';
        this.platformJdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "Data source is required"));
        this.transactions = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Platform transaction manager is required"));
        // Permanent allocation commits before any caller's recoverable provisioning transaction.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Allocation(UUID cabinetId, UUID reservationId, long schemaNumber, String schemaName)
    {
    }

    public Allocation allocate(UUID originalRegistrationId)
    {
        if (originalRegistrationId == null)
        {
            throw new IllegalArgumentException("Registration ID is required");
        }
        return Objects.requireNonNull(transactions.execute(status -> allocateLocked(originalRegistrationId)),
            "Allocation transaction returned no result");
    }

    private Allocation allocateLocked(UUID originalRegistrationId)
    {
        Registration registration;
        try
        {
            // Lock both the original request and its eligibility state until commit.
            registration = platformJdbc.queryForObject("""
                SELECT r.account_id, r.cabinet_id, r.cabinet_name,
                       u.account_status, u.email_verified_at, u.deployment_mode
                FROM %s.initial_cabinet_registrations r
                JOIN %s.users u ON u.id = r.account_id
                WHERE r.id = ? FOR UPDATE OF r, u
                """.formatted(platformSchema, platformSchema),
                (rs, rowNumber) -> new Registration(
                    rs.getObject("account_id", UUID.class),
                    rs.getObject("cabinet_id", UUID.class),
                    rs.getString("cabinet_name"),
                    rs.getString("account_status"),
                    rs.getObject("email_verified_at") != null,
                    rs.getString("deployment_mode")), originalRegistrationId);
        } catch (EmptyResultDataAccessException failure)
        {
            throw new IllegalStateException("Registration not found", failure);
        }
        Objects.requireNonNull(registration, "Registration query returned no result");

        if (!"ACTIVE".equals(registration.accountStatus()))
        {
            throw new IllegalStateException("Account is not active");
        }
        if (!"local".equals(registration.deploymentMode()) && !"hosted".equals(registration.deploymentMode()))
        {
            throw new IllegalStateException("Account deployment mode is not eligible");
        }
        if ("hosted".equals(registration.deploymentMode()) && !registration.emailVerified())
        {
            throw new IllegalStateException("Email verification required for hosted account");
        }

        // Only the durable original link authorizes replay, even after the cabinet row is deleted.
        if (registration.cabinetId() != null)
        {
            return Objects.requireNonNull(platformJdbc.queryForObject("""
                SELECT cabinet_id, reservation_id, schema_number, schema_name
                FROM %s.cabinet_schema_allocations
                WHERE cabinet_id = ? AND allocation_kind = 'SEQUENTIAL'
                """.formatted(platformSchema),
                (rs, rowNumber) -> new Allocation(
                    rs.getObject("cabinet_id", UUID.class),
                    rs.getObject("reservation_id", UUID.class),
                    rs.getLong("schema_number"),
                    rs.getString("schema_name")), registration.cabinetId()),
                "Original cabinet allocation is missing");
        }

        // PostgreSQL sequence calls survive rollback: gaps are allowed and never reclaimed.
        Long schemaNumber = platformJdbc.queryForObject(
            "SELECT pg_catalog.nextval(CAST(? AS pg_catalog.regclass))", Long.class,
            platformSchema + ".cabinet_schema_number_seq");
        if (schemaNumber == null || schemaNumber < 0)
        {
            throw new IllegalStateException("Invalid cabinet schema sequence number");
        }
        String schemaName = "cabinet" + String.format(Locale.ROOT, "%05d", schemaNumber);
        UUID cabinetId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        platformJdbc.update("""
            INSERT INTO %s.cabinet_schema_allocations
                (reservation_id, cabinet_id, allocation_kind, schema_number, schema_name, allocated_at)
            VALUES (?, ?, 'SEQUENTIAL', ?, ?, CURRENT_TIMESTAMP)
            """.formatted(platformSchema), reservationId, cabinetId, schemaNumber, schemaName);

        // A conflicting cabinet name is a real conflict, not evidence of an earlier allocation.
        // Let the exception roll back the whole transaction; never query an aborted transaction.
        platformJdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name, created_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """.formatted(platformSchema), cabinetId, registration.accountId(), registration.cabinetName(), schemaName);

        int linked = platformJdbc.update("""
            UPDATE %s.initial_cabinet_registrations SET cabinet_id = ? WHERE id = ?
            """.formatted(platformSchema), cabinetId, originalRegistrationId);
        if (linked != 1)
        {
            throw new IllegalStateException("Original registration could not be linked");
        }
        return new Allocation(cabinetId, reservationId, schemaNumber, schemaName);
    }

    private record Registration(UUID accountId, UUID cabinetId, String cabinetName,
                                String accountStatus, boolean emailVerified, String deploymentMode)
    {
    }
}
