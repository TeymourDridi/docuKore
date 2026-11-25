package com.docukore.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Centralized test fixture helper for creating V10/V11-compliant cabinet fixtures.
 * Ensures all sequential cabinet allocations satisfy the actual database contract:
 * - Permanent allocation record in cabinet_schema_allocations
 * - Correct cabinet/schema relationship in cabinets table
 * - Correct schema name format: cabinet00000, cabinet00001, etc.
 * - Physical PostgreSQL schema creation
 * - Tenant Flyway migrations executed
 * - Valid provisioning_status = 'ACTIVE'
 * - Valid administrative availability
 * - Appropriate owner and membership records where required
 */
public final class TestFixtures
{

    private TestFixtures()
    {
    }

    /**
     * Creates a V10-compliant cabinet with sequential allocation using coordinated test allocator.
     * Returns a CabinetFixture with cabinetId, schemaName, and ownerId.
     *
     * @param platformJdbc JdbcTemplate connected to platform schema (an explicit isolated test schema)
     * @param platformSchema the platform schema name (e.g., "test_platform_xxx")
     * @param ownerId      Owner user UUID
     * @param resources    OwnedTestDatabase.Scope for tracking created schemas (null for metadata-only)
     * @return CabinetFixture containing all allocation details
     */
    public static CabinetFixture createCabinet(JdbcTemplate platformJdbc, String platformSchema, UUID ownerId,
                                                OwnedTestDatabase.Scope resources)
    {
        OwnedTestDatabase.identifier(platformSchema);

        if (resources != null)
        {
            // Use coordinated allocator for physical schema creation with OwnedTestDatabase tracking
            TestSchemaAllocator.AllocationResult result = TestSchemaAllocator.allocateSchema(
                platformJdbc, platformSchema, resources, ownerId);
            return new CabinetFixture(result.cabinetId(), result.schemaName(), ownerId,
                result.reservationId(), result.schemaNumber());
        } else
        {
            // Metadata-only path: allocate without physical schema creation
            TestSchemaAllocator.AllocationResult result = TestSchemaAllocator.allocateMetadataOnly(
                platformJdbc, platformSchema, ownerId);
            return new CabinetFixture(result.cabinetId(), result.schemaName(), ownerId,
                result.reservationId(), result.schemaNumber());
        }
    }

    /**
     * Creates a V10-compliant cabinet with sequential allocation (legacy, no cleanup tracking).
     * Uses coordinated metadata-only allocation.
     *
     * @param platformJdbc  JdbcTemplate connected to platform schema
     * @param platformSchema the platform schema name
     * @param ownerId        the cabinet owner UUID
     * @return a CabinetFixture with allocation details
     * @deprecated Use createCabinet with OwnedTestDatabase.Scope parameter for proper cleanup
     */
    public static CabinetFixture createCabinet(JdbcTemplate platformJdbc, String platformSchema, UUID ownerId)
    {
        return createCabinet(platformJdbc, platformSchema, ownerId, null);
    }

    /**
     * Creates a cabinet with an owner user in one call.
     * Uses coordinated physical allocation and the exact owning platform scope for tenant cleanup.
     *
     * @param platformJdbc JdbcTemplate connected to platform schema (an explicit isolated test schema)
     * @return CabinetFixture containing all allocation details
     */
    public static CabinetFixture createCabinetWithOwner(JdbcTemplate platformJdbc)
    {
        String platformSchema = getPlatformSchema(platformJdbc);
        UUID ownerId = UUID.randomUUID();
        String username = ownerId.toString();
        String usernameNormalized = username.toLowerCase().trim();
        platformJdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(platformSchema),
            ownerId, username, usernameNormalized);
        // Reuse the exact invocation owner rather than leaking a physical tenant from Spring fixtures.
        return createCabinet(platformJdbc, platformSchema, ownerId, OwnedTestDatabase.owner(platformSchema));
    }

    /**
     * Creates a cabinet with owner and membership for a member user.
     *
     * @param platformJdbc JdbcTemplate connected to platform schema (an explicit isolated test schema)
     * @param memberRole   the membership role for the member user
     * @param staffType    the staff type for the member user
     * @return CabinetWithMembership containing the cabinet fixture and member ID
     */
    public static CabinetWithMembership createCabinetWithMembership(
        JdbcTemplate platformJdbc, String memberRole, String staffType)
    {
        String platformSchema = getPlatformSchema(platformJdbc);
        CabinetFixture cabinet = createCabinetWithOwner(platformJdbc);
        UUID memberId = UUID.randomUUID();
        String username = memberId.toString();
        String usernameNormalized = username.toLowerCase().trim();
        platformJdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(platformSchema), memberId, username, usernameNormalized);

        platformJdbc.update("""
            INSERT INTO %s.cabinet_memberships (id, cabinet_id, user_id, membership_role, staff_type, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.formatted(platformSchema),
            UUID.randomUUID(), cabinet.cabinetId(), memberId, memberRole, staffType);

        return new CabinetWithMembership(cabinet, memberId);
    }

    /**
     * Creates a user account for testing.
     *
     * @param platformJdbc JdbcTemplate connected to platform schema (an explicit isolated test schema)
     * @param platformSchema the platform schema name (e.g., "test_platform_xxx")
     * @return the created user UUID
     */
    public static UUID createUser(JdbcTemplate platformJdbc, String platformSchema)
    {
        OwnedTestDatabase.identifier(platformSchema);
        UUID id = UUID.randomUUID();
        String username = id.toString();
        String usernameNormalized = username.toLowerCase().trim();
        platformJdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(platformSchema), id, username, usernameNormalized);
        return id;
    }

    /**
     * Creates a membership for an existing cabinet and user.
     *
     * @param platformJdbc JdbcTemplate connected to platform schema (an explicit isolated test schema)
     * @param platformSchema the platform schema name (e.g., "test_platform_xxx")
     * @param cabinetId    the cabinet UUID
     * @param userId       the user UUID
     * @param role         the membership role
     * @param staffType    the staff type
     */
    public static void createMembership(
        JdbcTemplate platformJdbc, String platformSchema, UUID cabinetId, UUID userId, String role, String staffType)
    {
        OwnedTestDatabase.identifier(platformSchema);
        platformJdbc.update("""
            INSERT INTO %s.cabinet_memberships (id, cabinet_id, user_id, membership_role, staff_type, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.formatted(platformSchema),
            UUID.randomUUID(), cabinetId, userId, role, staffType);
    }

    /**
     * Creates the tenant_probe table in a tenant schema (for routing tests).
     *
     * @param platformJdbc JdbcTemplate connected to platform schema (public)
     * @param schemaName   the tenant schema name
     */
    public static void createTenantProbeTable(JdbcTemplate platformJdbc, String schemaName)
    {
        String schema = OwnedTestDatabase.identifier(schemaName);
        platformJdbc.execute("CREATE TABLE " + schema
            + ".tenant_probe (id uuid primary key, marker varchar(255) not null)");
        platformJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE " + schema
            + ".tenant_probe TO docukore_app");
    }

    /**
     * Record holding all cabinet allocation details for test assertions.
     *
     * @param cabinetId     the cabinet UUID
     * @param schemaName    the tenant schema name
     * @param ownerId       the owner user UUID
     * @param reservationId the reservation UUID
     * @param schemaNumber  the sequential schema number
     */
    public record CabinetFixture(
        UUID cabinetId,
        String schemaName,
        UUID ownerId,
        UUID reservationId,
        Long schemaNumber
    )
    {
    }

    /**
     * Record for cabinet with membership fixture.
     *
     * @param cabinet  the cabinet fixture
     * @param memberId the member user UUID
     */
    public record CabinetWithMembership(
        CabinetFixture cabinet,
        UUID memberId
    )
    {
    }

    /**
     * Opens a migration pool at the neutral catalog schema; callers must select an explicit fixture schema.
     *
     * @return a caller-owned migration pool
     */
    public static HikariDataSource createSharedDataSource()
    {
        return createMigratorDataSource("pg_catalog");
    }

    /**
     * Opens a migration/provisioning pool scoped to a trusted test schema.
     *
     * @param schema the explicit test schema or neutral pg_catalog
     * @return a caller-owned migration pool
     */
    public static HikariDataSource createMigratorDataSource(String schema)
    {
        return createDataSource(schema, false);
    }

    /**
     * Opens a restricted application pool scoped to a trusted test schema.
     *
     * @param schema the explicit test schema or neutral pg_catalog
     * @return a caller-owned runtime pool
     */
    public static HikariDataSource createRuntimeDataSource(String schema)
    {
        return createDataSource(schema, true);
    }

    private static HikariDataSource createDataSource(String schema, boolean runtime)
    {
        if (!"pg_catalog".equals(schema))
        {
            OwnedTestDatabase.identifier(schema);
        }
        // Check database and login identity before any pool or migration is initialized.
        try (Connection ignored = runtime ? OwnedTestDatabase.runtimeConnection() : OwnedTestDatabase.connection())
        {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(OwnedTestDatabase.jdbcUrl());
            config.setUsername(runtime ? OwnedTestDatabase.runtimeUsername() : OwnedTestDatabase.username());
            config.setPassword(runtime ? OwnedTestDatabase.runtimePassword() : OwnedTestDatabase.password());
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(1);
            config.setSchema(schema);
            return new HikariDataSource(config);
        } catch (SQLException failure)
        {
            throw new IllegalStateException("Test PostgreSQL is unavailable; reuse the existing service", failure);
        }
    }

    /**
     * Creates a fully migrated invocation-owned platform with a schema-scoped migrator JDBC fixture.
     *
     * @return the caller-owned platform and exact cleanup scope
     */
    public static OwnedPlatform createPlatform()
    {
        return createPlatform("platform");
    }

    /**
     * Creates a fully migrated invocation-owned platform; no existing schema is adopted or replaced.
     *
     * @param kind the isolated namespace category
     * @return the caller-owned platform and exact cleanup scope
     */
    public static OwnedPlatform createPlatform(String kind)
    {
        HikariDataSource ownershipPool = OwnedTestDatabase.acquirePool();
        OwnedTestDatabase.Scope scope = OwnedTestDatabase.scope(ownershipPool);
        HikariDataSource migrator = null;
        try
        {
            String schema = scope.create(kind);
            migrator = createMigratorDataSource(schema);
            Flyway.configure().dataSource(migrator).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/platform").createSchemas(false)
                .cleanDisabled(true).baselineOnMigrate(false).load().migrate();
            return new OwnedPlatform(schema, migrator, new JdbcTemplate(migrator), scope);
        } catch (RuntimeException | Error failure)
        {
            try
            {
                scope.close();
            } catch (RuntimeException | Error cleanupFailure)
            {
                failure.addSuppressed(cleanupFailure);
                // Keep the ownership connection usable so refused cleanup can be investigated or retried.
                throw failure;
            }
            if (migrator != null)
            {
                migrator.close();
            }
            OwnedTestDatabase.releasePool();
            throw failure;
        }
    }

    /** Exact platform and migrator resources, retaining ownership receipts until cleanup succeeds. */
    public static final class OwnedPlatform implements AutoCloseable
    {
        /** Invocation-owned isolated platform name. */
        private final String schema;
        /** Migrator pool used for fixture setup, receipts and cleanup. */
        private final HikariDataSource migratorDataSource;
        /** Schema-scoped fixture JDBC operations; never the runtime application identity. */
        private final JdbcTemplate jdbc;
        /** Exact namespace and allocator provenance receipts. */
        private final OwnedTestDatabase.Scope scope;
        /** Whether the scope and pool were successfully closed. */
        private boolean closed;

        private OwnedPlatform(String schema, HikariDataSource migratorDataSource, JdbcTemplate jdbc,
            OwnedTestDatabase.Scope scope)
        {
            this.schema = schema;
            this.migratorDataSource = migratorDataSource;
            this.jdbc = jdbc;
            this.scope = scope;
        }

        public String schema()
        {
            return schema;
        }

        public HikariDataSource migratorDataSource()
        {
            return migratorDataSource;
        }

        public JdbcTemplate jdbc()
        {
            return jdbc;
        }

        public OwnedTestDatabase.Scope scope()
        {
            return scope;
        }

        @Override
        public synchronized void close()
        {
            if (!closed)
            {
                // A refused OID/provenance cleanup must retain its scope and usable migrator pool for retry.
                scope.close();
                migratorDataSource.close();
                OwnedTestDatabase.releasePool();
                closed = true;
            }
        }
    }

    /**
     * Validates the exact external test PostgreSQL database using migration credentials.
     *
     * @throws IllegalStateException if the existing service is unavailable
     */
    public static void validateSharedPostgres()
    {
        try (Connection connection = OwnedTestDatabase.connection())
        {
            if (!connection.isValid(5))
            {
                throw new IllegalStateException("The existing test PostgreSQL service is unavailable");
            }
        } catch (SQLException failure)
        {
            throw new IllegalStateException("Test PostgreSQL is unavailable; reuse the existing service", failure);
        }
    }

    /**
     * Runs a database action on a fresh validated migration connection at a neutral search path.
     *
     * @param action the database action to execute
     */
    public static void withConnection(Consumer<Connection> action)
    {
        try (Connection connection = OwnedTestDatabase.connection())
        {
            action.accept(connection);
        } catch (SQLException failure)
        {
            throw new IllegalStateException("Test database operation failed", failure);
        }
    }

    /**
     * Resolves only an explicit trusted schema on the same connection; never falls back to public.
     *
     * @param jdbc the schema-scoped fixture JDBC operations
     * @return the validated isolated schema name
     */
    public static String getPlatformSchema(JdbcTemplate jdbc)
    {
        if (jdbc.getDataSource() == null)
        {
            throw new IllegalStateException("Fixture JDBC requires an explicit schema-scoped datasource");
        }
        try (Connection connection = jdbc.getDataSource().getConnection())
        {
            OwnedTestDatabase.validate(connection);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT current_schema()"))
            {
                if (!result.next())
                {
                    throw new IllegalStateException("Fixture datasource has no current schema");
                }
                String schema = result.getString(1);
                OwnedTestDatabase.identifier(schema);
                return schema;
            }
        } catch (SQLException failure)
        {
            throw new IllegalStateException("Cannot resolve explicit fixture schema", failure);
        }
    }
}