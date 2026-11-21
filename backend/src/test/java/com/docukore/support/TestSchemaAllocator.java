package com.docukore.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Coordinated test-only schema allocator ensuring globally unique cabinet schema names
 * across independent platform scopes and test runs.
 *
 * Uses a database-wide session advisory lock on a dedicated connection for each physical
 * allocation, held through candidate selection, sequence advancement, physical creation,
 * receipt commit and ownership registration.
 */
public final class TestSchemaAllocator
{
    /** Advisory lock key serializing physical test schema allocation across platform scopes. */
    private static final String ALLOCATOR_LOCK_KEY = "docukore-test-allocator";
    /** Hook invoked before schema registration while the advisory lock is held. */
    private static volatile Runnable ON_REGISTER_EXISTING_HOOK;

    private TestSchemaAllocator()
    {
    }

    /**
     * Sets a test hook that is invoked just before registerExisting is called,
     * while the session-level advisory lock is still held.
     * Only for test use.
     *
     * @param hook the hook to execute before registerExisting
     */
    public static void setOnRegisterExistingHook(Runnable hook)
    {
        ON_REGISTER_EXISTING_HOOK = hook;
    }

    /**
     * Allocates a new cabinet schema name using coordinated database-wide advisory lock.
     * Advances the platform sequence and registers the created schema with OwnedTestDatabase.
     *
     * @param platformJdbc     JdbcTemplate connected to platform schema
     * @param platformSchema   the platform schema name (e.g., "public", "test_platform_xxx")
     * @param resources        OwnedTestDatabase.Scope for tracking created schemas
     * @param ownerId          the cabinet owner UUID
     * @return a record containing cabinetId, schemaName, and schemaNumber
     */
    public static AllocationResult allocateSchema(JdbcTemplate platformJdbc, String platformSchema,
                                                  OwnedTestDatabase.Scope resources, UUID ownerId)
    {
        return allocatePhysicalSchema(platformSchema, resources, ownerId, true);
    }

    /**
     * Allocates a schema with physical creation but without OwnedTestDatabase registration.
     * Used for Spring Boot integration tests that don't track schemas for cleanup.
     *
     * @param platformJdbc   JdbcTemplate connected to platform schema
     * @param platformSchema the platform schema name
     * @param ownerId        the cabinet owner UUID
     * @return a record containing cabinetId, schemaName, and schemaNumber
     */
    public static AllocationResult allocateSchemaWithoutTracking(
            JdbcTemplate platformJdbc, String platformSchema, UUID ownerId)
    {
        return allocatePhysicalSchema(platformSchema, null, ownerId, false);
    }

    private static AllocationResult allocatePhysicalSchema(String platformSchema, OwnedTestDatabase.Scope resources,
                                                           UUID ownerId, boolean tracked)
    {
        UUID cabinetId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        AllocationResult allocation;
        // Direct, validated connection: never borrow a pooled or shared PostgreSQL session for the lock.
        try (Connection lockConn = OwnedTestDatabase.connection())
        {
            boolean lockAcquired = false;
            Throwable failure = null;
            try
            {
                lockConn.setAutoCommit(false);
                try (PreparedStatement lockStmt = lockConn.prepareStatement(
                        "SELECT pg_catalog.pg_advisory_lock(pg_catalog.hashtextextended(?, 0))"))
                {
                    lockStmt.setString(1, ALLOCATOR_LOCK_KEY);
                    lockStmt.execute();
                    lockAcquired = true;
                }

                // Advance until the candidate is physically unused, while holding the database-wide lock.
                Long schemaNumber;
                String schemaName;
                while (true)
                {
                    try (PreparedStatement seqStmt = lockConn.prepareStatement(
                            "SELECT pg_catalog.nextval(CAST(? AS pg_catalog.regclass))"))
                    {
                        seqStmt.setString(1, platformSchema + ".cabinet_schema_number_seq");
                        try (ResultSet rs = seqStmt.executeQuery())
                        {
                            if (!rs.next())
                            {
                                throw new SQLException("Sequence query returned no result");
                            }
                            schemaNumber = rs.getLong(1);
                        }
                    }
                    schemaName = "cabinet" + String.format("%05d", schemaNumber);
                    try (PreparedStatement checkStmt = lockConn.prepareStatement(
                            "SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)"))
                    {
                        checkStmt.setString(1, schemaName);
                        try (ResultSet rs = checkStmt.executeQuery())
                        {
                            if (rs.next() && rs.getBoolean(1))
                            {
                                continue;
                            }
                        }
                    }
                    break;
                }

                if (resources != null)
                {
                    resources.authorizeAllocation(platformSchema, schemaName, reservationId, cabinetId);
                }
                try (PreparedStatement createStmt = lockConn.prepareStatement("CREATE SCHEMA " + schemaName))
                {
                    createStmt.execute();
                }

                long namespaceOid;
                long ownerOid;
                try (PreparedStatement oidStmt = lockConn.prepareStatement(
                        "SELECT oid::bigint, nspowner::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?"))
                {
                    oidStmt.setString(1, schemaName);
                    try (ResultSet rs = oidStmt.executeQuery())
                    {
                        if (!rs.next())
                        {
                            throw new SQLException("Created schema not found in catalog");
                        }
                        namespaceOid = rs.getLong(1);
                        ownerOid = rs.getLong(2);
                    }
                }

                String qualifiedPlatformSchema = '"' + platformSchema + '"';
                try (PreparedStatement allocStmt = lockConn.prepareStatement("""
                    INSERT INTO %s.cabinet_schema_allocations
                        (reservation_id, cabinet_id, allocation_kind, schema_number, schema_name, allocated_at)
                    VALUES (?, ?, 'SEQUENTIAL', ?, ?, CURRENT_TIMESTAMP)
                    """.formatted(qualifiedPlatformSchema)))
                {
                    allocStmt.setObject(1, reservationId);
                    allocStmt.setObject(2, cabinetId);
                    allocStmt.setLong(3, schemaNumber);
                    allocStmt.setString(4, schemaName);
                    allocStmt.execute();
                }

                try (PreparedStatement cabinetStmt = lockConn.prepareStatement("""
                    INSERT INTO %s.cabinets (id, owner_id, name, schema_name,
                        provisioning_status, created_at, provisioned_at)
                    VALUES (?, ?, 'Fictional cabinet', ?, 'ACTIVE', now(), now())
                    """.formatted(qualifiedPlatformSchema)))
                {
                    cabinetStmt.setObject(1, cabinetId);
                    cabinetStmt.setObject(2, ownerId);
                    cabinetStmt.setString(3, schemaName);
                    cabinetStmt.execute();
                }

                try (PreparedStatement receiptStmt = lockConn.prepareStatement("""
                    INSERT INTO %s.cabinet_schema_creation_receipts
                        (reservation_id, cabinet_id, schema_name, namespace_oid, owner_oid, created_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """.formatted(qualifiedPlatformSchema)))
                {
                    receiptStmt.setObject(1, reservationId);
                    receiptStmt.setObject(2, cabinetId);
                    receiptStmt.setString(3, schemaName);
                    receiptStmt.setLong(4, namespaceOid);
                    receiptStmt.setLong(5, ownerOid);
                    receiptStmt.execute();
                }

                // Registration validates the committed receipt on another connection; session lock stays held.
                lockConn.commit();
                Runnable hook = ON_REGISTER_EXISTING_HOOK;
                if (tracked && hook != null)
                {
                    hook.run();
                }
                if (resources != null)
                {
                    var identity = new OwnedTestDatabase.NamespaceIdentity(schemaName, namespaceOid, ownerOid);
                    resources.registerExisting(platformSchema, schemaName, identity, reservationId);
                }
                allocation = new AllocationResult(cabinetId, schemaName, schemaNumber, reservationId,
                    namespaceOid, ownerOid);
            } catch (SQLException | RuntimeException | Error e)
            {
                failure = e;
                try
                {
                    lockConn.rollback();
                } catch (SQLException rollbackFailure)
                {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally
            {
                if (lockAcquired)
                {
                    try (PreparedStatement unlockStmt = lockConn.prepareStatement(
                            "SELECT pg_catalog.pg_advisory_unlock(pg_catalog.hashtextextended(?, 0))"))
                    {
                        unlockStmt.setString(1, ALLOCATOR_LOCK_KEY);
                        unlockStmt.execute();
                    } catch (SQLException unlockFailure)
                    {
                        if (failure == null)
                        {
                            throw unlockFailure;
                        }
                        failure.addSuppressed(unlockFailure);
                    }
                }
                // Try-with-resources closes the dedicated session even if rollback/unlock fails.
            }
        } catch (SQLException e)
        {
            throw new IllegalStateException(tracked ? "Coordinated schema allocation failed"
                : "Coordinated schema allocation without tracking failed", e);
        }

        // Migrations have their own connection lifecycle and cannot roll back the committed allocation.
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(OwnedTestDatabase.jdbcUrl(), OwnedTestDatabase.username(), OwnedTestDatabase.password())
                .schemas(allocation.schemaName())
                .defaultSchema(allocation.schemaName())
                .locations("classpath:db/migration/tenant")
                .table("flyway_schema_history_tenant")
                .createSchemas(false)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
        flyway.migrate();
        return allocation;
    }

    /**
     * Allocates a schema without physical creation or OwnedTestDatabase registration.
     * Used for metadata-only tests that don't need physical schema.
     *
     * @param platformJdbc   JdbcTemplate connected to platform schema
     * @param platformSchema the platform schema name
     * @param ownerId        the cabinet owner UUID
     * @return a record containing cabinetId, schemaName, and schemaNumber
     */
    public static AllocationResult allocateMetadataOnly(
            JdbcTemplate platformJdbc, String platformSchema, UUID ownerId)
    {
        UUID cabinetId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        // Get next sequential schema number
        Long schemaNumber = platformJdbc.queryForObject(
                "SELECT pg_catalog.nextval(CAST(? AS pg_catalog.regclass))", Long.class,
                platformSchema + ".cabinet_schema_number_seq");
        String schemaName = "cabinet" + String.format("%05d", schemaNumber);

        // Insert permanent allocation record
        String qualifiedPlatformSchema = '"' + platformSchema + '"';
        platformJdbc.update("""
            INSERT INTO %s.cabinet_schema_allocations
                (reservation_id, cabinet_id, allocation_kind, schema_number, schema_name, allocated_at)
            VALUES (?, ?, 'SEQUENTIAL', ?, ?, CURRENT_TIMESTAMP)
            """.formatted(qualifiedPlatformSchema), reservationId, cabinetId, schemaNumber, schemaName);

        // Insert cabinet record
        platformJdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name,
                provisioning_status, created_at, provisioned_at)
            VALUES (?, ?, 'Fictional cabinet', ?, 'ACTIVE', now(), now())
            """.formatted(qualifiedPlatformSchema),
            cabinetId, ownerId, schemaName);

        // Note: No physical schema creation, no migrations, no receipt
        return new AllocationResult(cabinetId, schemaName, schemaNumber, reservationId, -1, -1);
    }

    /**
     * Result of a coordinated schema allocation.
     *
     * @param cabinetId     the allocated cabinet identifier
     * @param schemaName    the allocated tenant schema name
     * @param schemaNumber  the permanent schema sequence number
     * @param reservationId the platform reservation identifier
     * @param namespaceOid  the PostgreSQL namespace OID
     * @param ownerOid      the namespace owner OID
     */
    public record AllocationResult(
            UUID cabinetId,
            String schemaName,
            Long schemaNumber,
            UUID reservationId,
            long namespaceOid,
            long ownerOid
    )
    {
        /**
         * Converts to a CabinetFixture with the given ownerId.
         *
         * @param ownerId the cabinet owner identifier
         * @return a CabinetFixture for the allocation
         */
        public TestFixtures.CabinetFixture toCabinetFixture(UUID ownerId)
        {
            return new TestFixtures.CabinetFixture(cabinetId, schemaName, ownerId, reservationId, schemaNumber);
        }
    }
}
