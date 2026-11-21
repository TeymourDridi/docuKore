package com.docukore.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
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
     * @param platformJdbc JdbcTemplate connected to platform schema (e.g., public or test_platform_xxx)
     * @param platformSchema the platform schema name (e.g., "public", "test_platform_xxx")
     * @param ownerId      Owner user UUID
     * @param resources    OwnedTestDatabase.Scope for tracking created schemas (null for metadata-only)
     * @return CabinetFixture containing all allocation details
     */
    public static CabinetFixture createCabinet(JdbcTemplate platformJdbc, String platformSchema, UUID ownerId,
                                                OwnedTestDatabase.Scope resources)
    {
        // Validate platform schema identifier
        if (platformSchema == null || !platformSchema.matches("[a-z][a-z0-9_]{0,62}"))
        {
            throw new IllegalArgumentException("Invalid platform schema identifier: " + platformSchema);
        }

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
     * Uses coordinated allocator with physical schema creation (no cleanup tracking for Spring Boot tests).
     *
     * @param platformJdbc JdbcTemplate connected to platform schema (public or test_platform_xxx)
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
        // Use coordinated allocator with physical schema creation but without OwnedTestDatabase tracking
        return TestSchemaAllocator.allocateSchemaWithoutTracking(
            platformJdbc, platformSchema, ownerId).toCabinetFixture(ownerId);
    }

    /**
     * Creates a cabinet with owner and membership for a member user.
     *
     * @param platformJdbc JdbcTemplate connected to platform schema (public or test_platform_xxx)
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
     * @param platformJdbc JdbcTemplate connected to platform schema (public or test_platform_xxx)
     * @param platformSchema the platform schema name (e.g., "public", "test_platform_xxx")
     * @return the created user UUID
     */
    public static UUID createUser(JdbcTemplate platformJdbc, String platformSchema)
    {
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
     * @param platformJdbc JdbcTemplate connected to platform schema (public or test_platform_xxx)
     * @param platformSchema the platform schema name (e.g., "public", "test_platform_xxx")
     * @param cabinetId    the cabinet UUID
     * @param userId       the user UUID
     * @param role         the membership role
     * @param staffType    the staff type
     */
    public static void createMembership(
        JdbcTemplate platformJdbc, String platformSchema, UUID cabinetId, UUID userId, String role, String staffType)
    {
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
        platformJdbc.execute("CREATE TABLE " + schemaName
            + ".tenant_probe (id uuid primary key, marker varchar(255) not null)");
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
     * Creates a shared HikariDataSource for test classes that need a connection pool.
     * Caller is responsible for closing.
     *
     * @return a configured HikariDataSource connected to the shared PostgreSQL
     */
    public static HikariDataSource createSharedDataSource()
    {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(SharedPostgresTest.SHARED_JDBC_URL);
        config.setUsername(SharedPostgresTest.SHARED_USER);
        config.setPassword(SharedPostgresTest.SHARED_PASSWORD);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setSchema("public");
        return new HikariDataSource(config);
    }

    /**
     * Validates that the shared PostgreSQL is available.
     * Throws IllegalStateException if unavailable.
     *
     * @throws IllegalStateException if the shared PostgreSQL is not available
     */
    public static void validateSharedPostgres()
    {
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            if (!conn.isValid(5))
            {
                throw new IllegalStateException("Shared PostgreSQL at "
                    + SharedPostgresTest.SHARED_JDBC_URL + " is not available");
            }
        } catch (Exception e)
        {
            throw new IllegalStateException(
                "Cannot connect to shared PostgreSQL. Ensure docukore-postgres container is running. "
                + "Start with: docker compose -f infrastructure/docker/docker-compose.yml up -d", e);
        }
    }

    /**
     * Runs a database action with a fresh connection from the shared pool.
     * Useful for test setup/teardown that needs direct JDBC access.
     *
     * @param action the database action to execute with the connection
     */
    public static void withConnection(Consumer<Connection> action)
    {
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            action.accept(conn);
        } catch (Exception e)
        {
            throw new RuntimeException("Database operation failed", e);
        }
    }

    /**
     * Extracts the platform schema name from the JdbcTemplate's datasource.
     * Works with both direct connections and Hikari pooled connections.
     *
     * @param jdbc JdbcTemplate to inspect
     * @return the schema name (e.g., "public" or "test_platform_xxx")
     */
    public static String getPlatformSchema(JdbcTemplate jdbc)
    {
        try (var conn = jdbc.getDataSource().getConnection())
        {
            // For HikariCP, get the schema from the connection's metadata
            var metaData = conn.getMetaData();
            String url = metaData.getURL();
            // Extract schema from current_schema setting or default to public
            String schema = jdbc.queryForObject("SELECT current_schema()", String.class);
            return schema != null ? schema : "public";
        } catch (Exception e)
        {
            // Fallback to public for compatibility
            return "public";
        }
    }
}