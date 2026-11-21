package com.docukore.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fail-closed test connection configuration and exact, invocation-owned namespace receipts. */
public final class OwnedTestDatabase
{
    /** Target test database name. */
    private static final String TEST_DATABASE = "docukore_test";
    /** Map of owned schema names to their owning Scope. */
    private static final Map<String, Scope> OWNERS = new LinkedHashMap<>();
    /** Scope authorized for each pending physical schema allocation; never reassigned between scopes. */
    private static final Map<String, Scope> ALLOCATION_OWNERS = new LinkedHashMap<>();
    /** Shared HikariDataSource pool for test database connections. */
    private static HikariDataSource POOL;
    /** Current number of active leases on the shared pool. */
    private static int LEASES;

    private OwnedTestDatabase()
    {
    }

    private static final class Configuration
    {
        /** Test JDBC URL. */
        private static final String URL = required("docukore.test.jdbc-url", "DOCUKORE_TEST_JDBC_URL");
        /** Test database username. */
        private static final String USER = required("docukore.test.username", "DOCUKORE_TEST_USERNAME");
        /** Test database password. */
        private static final String PASSWORD = required("docukore.test.password", "DOCUKORE_TEST_PASSWORD");

        static
        {
            try
            {
                if (!URL.startsWith("jdbc:postgresql://"))
                {
                    throw new IllegalArgumentException();
                }
                URI uri = URI.create(URL.substring("jdbc:".length()));
                // Reject driver URL overrides (service, credentials, options, currentSchema, etc.).
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null || !("/" + TEST_DATABASE).equals(uri.getRawPath()))
                {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException failure)
            {
                throw new IllegalStateException("Test JDBC URL must explicitly name docukore_test, "
                        + "with one host and no URL credentials, query options or fragment");
            }
        }
    }

    private static String required(String property, String environment)
    {
        String value = System.getProperty(property);
        if (value == null || value.isBlank())
        {
            value = System.getenv(environment);
        }
        if (value == null || value.isBlank())
        {
            throw new IllegalStateException("Supply " + property + " or " + environment
                    + " externally; integration tests never use application connection defaults");
        }
        return value;
    }

    public static String jdbcUrl()
    {
        return Configuration.URL;
    }
    public static String username()
    {
        return Configuration.USER;
    }
    public static String password()
    {
        return Configuration.PASSWORD;
    }

    public static void validate(Connection connection) throws SQLException
    {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database()"))
        {
            if (!result.next() || !TEST_DATABASE.equals(result.getString(1)))
            {
                throw new IllegalStateException("Refusing connection: server database is not docukore_test");
            }
        }
    }

    public static Connection connection() throws SQLException
    {
        Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password());
        try
        {
            validate(connection);
            connection.setSchema("pg_catalog");
            return connection;
        } catch (SQLException | RuntimeException failure)
        {
            connection.close();
            throw failure;
        }
    }

    static synchronized HikariDataSource acquirePool()
    {
        if (POOL == null)
        {
            // Validate server identity before constructing a pool or executing any DDL/Flyway.
            try (Connection ignored = connection())
            {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(jdbcUrl());
                config.setUsername(username());
                config.setPassword(password());
                config.setMaximumPoolSize(4);
                config.setMinimumIdle(1);
                config.setSchema("pg_catalog");
                config.setPoolName("DocukoreOwnedTestPool");
                POOL = new HikariDataSource(config);
            } catch (SQLException failure)
            {
                throw new IllegalStateException("Test PostgreSQL is unavailable; reuse the existing service", failure);
            }
        }
        LEASES++;
        return POOL;
    }

    static synchronized void releasePool()
    {
        if (--LEASES == 0 && POOL != null)
        {
            POOL.close();
            POOL = null;
        }
    }

    public static Scope scope(DataSource dataSource)
    {
        return new Scope(dataSource);
    }

    public static String identifier(String schema)
    {
        if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}")
                || schema.equals("public") || schema.startsWith("pg_") || schema.equals("information_schema"))
        {
            throw new IllegalArgumentException("Not an isolated test schema identifier");
        }
        return '"' + schema + '"';
    }

    static synchronized Scope owner(String schema)
    {
        Scope owner = OWNERS.get(schema);
        if (owner == null)
        {
            throw new IllegalStateException("Schema was not created by this test run: " + schema);
        }
        return owner;
    }

    public static void requireOwned(String schema)
    {
        owner(schema).requireIdentity(schema);
    }

    public record NamespaceIdentity(String schemaName, long namespaceOid, long ownerOid)
    {
    }

    public static final class Scope implements AutoCloseable
    {
        /** Data source for test schema operations. */
        private final DataSource dataSource;
        /** Map of created schema names to their namespace identity. */
        private final Map<String, NamespaceIdentity> created = new LinkedHashMap<>();
        /** Exact allocator provenance authorized before physical creation, retained until safe cleanup. */
        private final Map<String, AllocationProvenance> allocations = new LinkedHashMap<>();

        private record AllocationProvenance(String platformSchema, UUID reservationId, UUID cabinetId)
        {
        }

        private Scope(DataSource dataSource)
        {
            this.dataSource = dataSource;
        }

        String create(String kind)
        {
            String name = "test_" + kind + "_" + UUID.randomUUID().toString().replace("-", "");
            createExact(name);
            return name;
        }

        NamespaceIdentity createExact(String schema)
        {
            String quoted = identifier(schema);
            try (Connection connection = dataSource.getConnection())
            {
                validate(connection);
                connection.setAutoCommit(false);
                try
                {
                    lock(connection, schema);
                    synchronized (OwnedTestDatabase.class)
                    {
                        if (OWNERS.containsKey(schema) || ALLOCATION_OWNERS.containsKey(schema))
                        {
                            throw new IllegalStateException("Test schema already has an owner: " + schema);
                        }
                    }
                    try (var statement = connection.createStatement())
                    {
                        // Never adopt, replace, or pre-drop an existing namespace.
                        statement.execute("CREATE SCHEMA " + quoted);
                    }
                    NamespaceIdentity identity = identity(connection, schema);
                    synchronized (OwnedTestDatabase.class)
                    {
                        if (OWNERS.containsKey(schema) || ALLOCATION_OWNERS.containsKey(schema))
                        {
                            throw new IllegalStateException("Test schema already has an owner: " + schema);
                        }
                        connection.commit();
                        created.put(schema, identity);
                        OWNERS.put(schema, this);
                    }
                    return identity;
                } catch (SQLException | RuntimeException failure)
                {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure)
            {
                throw new IllegalStateException("Cannot create exact owned test schema " + schema, failure);
            }
        }

        /**
         * Validates that the given schema was created by this test scope and its identity matches.
         * Public for test access.
         *
         * @param schema the schema name to validate
         */
        public void requireIdentity(String schema)
        {
            NamespaceIdentity expected;
            synchronized (OwnedTestDatabase.class)
            {
                expected = ownedIdentity(schema);
            }
            if (expected == null)
            {
                throw new IllegalStateException("No creation receipt for test schema " + schema);
            }
            try (Connection connection = dataSource.getConnection())
            {
                validate(connection);
                connection.setAutoCommit(false);
                try
                {
                    lock(connection, schema);
                    synchronized (OwnedTestDatabase.class)
                    {
                        if (!expected.equals(ownedIdentity(schema)))
                        {
                            throw new IllegalStateException("Test schema ownership changed: " + schema);
                        }
                    }
                    if (!expected.equals(identity(connection, schema)))
                    {
                        throw new IllegalStateException(
                            "Owned test schema identity changed; refusing access: " + schema);
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure)
                {
                    rollback(connection, failure);
                    throw failure;
                }
            } catch (SQLException failure)
            {
                throw new IllegalStateException("Cannot validate owned test namespace", failure);
            }
        }

        /**
         * Binds a physical allocation to this scope before creation, while the allocator session lock is held.
         * Package-private: registration cannot authorize itself by presenting a readable database receipt.
         *
         * @param platformSchema the receipt namespace
         * @param schema the physical tenant namespace
         * @param reservationId the exact allocator reservation
         * @param cabinetId the cabinet bound to the reservation
         */
        void authorizeAllocation(String platformSchema, String schema, UUID reservationId, UUID cabinetId)
        {
            platformIdentifier(platformSchema);
            identifier(schema);
            if (reservationId == null || cabinetId == null)
            {
                throw new IllegalArgumentException("Allocation requires reservation and cabinet identities");
            }
            var provenance = new AllocationProvenance(platformSchema, reservationId, cabinetId);
            synchronized (OwnedTestDatabase.class)
            {
                Scope owner = OWNERS.get(schema);
                Scope allocationOwner = ALLOCATION_OWNERS.get(schema);
                AllocationProvenance existing = allocations.get(schema);
                if ((owner != null && owner != this) || (allocationOwner != null && allocationOwner != this)
                        || (existing != null && !existing.equals(provenance))
                        || (created.containsKey(schema) && existing == null))
                {
                    throw new IllegalStateException(
                        "Test schema allocation already belongs to another scope: " + schema);
                }
                allocations.put(schema, provenance);
                ALLOCATION_OWNERS.put(schema, this);
            }
        }

        /**
         * Registers an allocator-created schema using this scope's exact authorized reservation and cabinet.
         * Validates the live namespace and every creation receipt field under the existing per-schema lock.
         * The allocator's dedicated session lock remains held by its caller throughout registration.
         *
         * @param platformSchema the platform schema name where receipts are stored
         * @param schema the tenant schema name
         * @param identity the captured namespace identity
         * @param reservationId the reservation UUID from the allocator
         */
        public void registerExisting(String platformSchema, String schema,
            NamespaceIdentity identity, UUID reservationId)
        {
            String qualifiedPlatformSchema = platformIdentifier(platformSchema);
            identifier(schema);
            if (identity == null || !schema.equals(identity.schemaName()))
            {
                throw new IllegalArgumentException("Registration requires the exact namespace identity");
            }
            AllocationProvenance provenance;
            synchronized (OwnedTestDatabase.class)
            {
                provenance = authorizedAllocation(platformSchema, schema, reservationId);
            }
            try (Connection connection = dataSource.getConnection())
            {
                validate(connection);
                connection.setAutoCommit(false);
                try
                {
                    lock(connection, schema);
                    NamespaceIdentity actual = identity(connection, schema);
                    if (actual == null)
                    {
                        throw new IllegalStateException("Cannot register schema that does not exist: " + schema);
                    }
                    if (!identity.equals(actual))
                    {
                        throw new IllegalStateException("Provided identity does not match database: " + schema);
                    }
                    String receiptQuery = """
                        SELECT reservation_id, cabinet_id, schema_name, namespace_oid, owner_oid
                        FROM %s.cabinet_schema_creation_receipts WHERE reservation_id = ?
                        """.formatted(qualifiedPlatformSchema);
                    try (PreparedStatement stmt = connection.prepareStatement(receiptQuery))
                    {
                        stmt.setObject(1, reservationId);
                        try (var rs = stmt.executeQuery())
                        {
                            if (!rs.next() || !reservationId.equals(rs.getObject("reservation_id", UUID.class))
                                    || !provenance.cabinetId().equals(rs.getObject("cabinet_id", UUID.class))
                                    || !schema.equals(rs.getString("schema_name"))
                                    || identity.namespaceOid() != rs.getLong("namespace_oid")
                                    || identity.ownerOid() != rs.getLong("owner_oid") || rs.next())
                            {
                                throw new IllegalStateException(
                                    "No matching creation receipt found for schema " + schema);
                            }
                        }
                    }
                    synchronized (OwnedTestDatabase.class)
                    {
                        if (!provenance.equals(authorizedAllocation(platformSchema, schema, reservationId)))
                        {
                            throw new IllegalStateException("Test allocation authorization changed: " + schema);
                        }
                        NamespaceIdentity existing = created.get(schema);
                        if (existing != null && !existing.equals(identity))
                        {
                            throw new IllegalStateException("Cannot replace an owned namespace identity: " + schema);
                        }
                        connection.commit();
                        created.putIfAbsent(schema, identity);
                        OWNERS.putIfAbsent(schema, this);
                    }
                } catch (SQLException | RuntimeException failure)
                {
                    rollback(connection, failure);
                    throw failure;
                }
            } catch (SQLException failure)
            {
                throw new IllegalStateException("Cannot validate schema identity and receipt for registration: "
                    + schema, failure);
            }
        }

        private AllocationProvenance authorizedAllocation(String platformSchema, String schema, UUID reservationId)
        {
            Scope owner = OWNERS.get(schema);
            AllocationProvenance provenance = allocations.get(schema);
            if ((owner != null && owner != this) || ALLOCATION_OWNERS.get(schema) != this || provenance == null
                    || !provenance.platformSchema().equals(platformSchema)
                    || !provenance.reservationId().equals(reservationId))
            {
                throw new IllegalStateException("No scope-authorized allocation for test schema " + schema);
            }
            return provenance;
        }

        private NamespaceIdentity ownedIdentity(String schema)
        {
            if (OWNERS.get(schema) != this || !created.containsKey(schema))
            {
                throw new IllegalStateException("Refusing access or cleanup: scope does not own test schema " + schema);
            }
            return created.get(schema);
        }

        /**
         * Drops a schema created by this test scope, validating identity matches.
         * Package-private for test access.
         *
         * @param schema the schema name to drop
         */
        public void drop(String schema)
        {
            NamespaceIdentity expected;
            synchronized (OwnedTestDatabase.class)
            {
                expected = ownedIdentity(schema);
            }
            if (expected == null)
            {
                throw new IllegalStateException("Refusing cleanup without exact test creation receipt: " + schema);
            }
            try (Connection connection = dataSource.getConnection())
            {
                validate(connection);
                connection.setAutoCommit(false);
                try
                {
                    lock(connection, schema);
                    synchronized (OwnedTestDatabase.class)
                    {
                        if (!expected.equals(ownedIdentity(schema)))
                        {
                            throw new IllegalStateException("Test schema ownership changed: " + schema);
                        }
                    }
                    NamespaceIdentity actual = identity(connection, schema);
                    if (actual != null && !expected.equals(actual))
                    {
                        throw new IllegalStateException("Refusing cleanup: namespace OID/owner changed: " + schema);
                    }
                    if (actual != null)
                    {
                        try (var statement = connection.createStatement())
                        {
                            statement.execute("DROP SCHEMA " + identifier(schema) + " CASCADE");
                        }
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure)
                {
                    rollback(connection, failure);
                    throw failure;
                }
            } catch (SQLException failure)
            {
                throw new IllegalStateException("Exact owned test schema cleanup failed: " + schema, failure);
            }
            // Retain receipts on every failure, including connection close after a committed drop.
            synchronized (OwnedTestDatabase.class)
            {
                if (!expected.equals(ownedIdentity(schema)))
                {
                    throw new IllegalStateException("Test schema ownership changed during cleanup: " + schema);
                }
                created.remove(schema);
                OWNERS.remove(schema, this);
                allocations.remove(schema);
                ALLOCATION_OWNERS.remove(schema, this);
            }
        }

        @Override
        public void close()
        {
            List<String> schemas;
            synchronized (OwnedTestDatabase.class)
            {
                schemas = new ArrayList<>(created.keySet());
            }
            RuntimeException failure = null;
            for (String schema : schemas.reversed())
            {
                try
                {
                    drop(schema);
                } catch (RuntimeException cleanupFailure)
                {
                    if (failure == null)
                    {
                        failure = cleanupFailure;
                    } else
                    {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            List<String> pending;
            synchronized (OwnedTestDatabase.class)
            {
                pending = allocations.keySet().stream().filter(schema -> !created.containsKey(schema)).toList();
            }
            for (String schema : pending)
            {
                try
                {
                    cancelAbsentAllocation(schema);
                } catch (RuntimeException pendingFailure)
                {
                    if (failure == null)
                    {
                        failure = pendingFailure;
                    } else
                    {
                        failure.addSuppressed(pendingFailure);
                    }
                }
            }
            if (failure != null)
            {
                throw failure;
            }
        }

        private void cancelAbsentAllocation(String schema)
        {
            AllocationProvenance expected;
            synchronized (OwnedTestDatabase.class)
            {
                expected = allocations.get(schema);
                if (expected == null || ALLOCATION_OWNERS.get(schema) != this || OWNERS.containsKey(schema))
                {
                    throw new IllegalStateException("Cannot cancel test allocation owned by another scope: " + schema);
                }
            }
            try (Connection connection = dataSource.getConnection())
            {
                validate(connection);
                connection.setAutoCommit(false);
                try
                {
                    // Wait for any in-flight allocator before declaring its pending namespace absent.
                    try (var statement = connection.prepareStatement(
                            "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))"))
                    {
                        statement.setString(1, "docukore-test-allocator");
                        statement.execute();
                    }
                    lock(connection, schema);
                    if (identity(connection, schema) != null)
                    {
                        throw new IllegalStateException(
                            "Unregistered physical test allocation requires provenance recovery: " + schema);
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure)
                {
                    rollback(connection, failure);
                    throw failure;
                }
            } catch (SQLException failure)
            {
                throw new IllegalStateException("Cannot cancel absent test allocation: " + schema, failure);
            }
            synchronized (OwnedTestDatabase.class)
            {
                if (!expected.equals(allocations.get(schema)) || ALLOCATION_OWNERS.get(schema) != this
                        || OWNERS.containsKey(schema))
                {
                    throw new IllegalStateException("Test allocation changed during cancellation: " + schema);
                }
                allocations.remove(schema);
                ALLOCATION_OWNERS.remove(schema, this);
            }
        }
    }

    private static NamespaceIdentity identity(Connection connection, String schema) throws SQLException
    {
        try (var statement = connection.prepareStatement(
                "SELECT oid::bigint, nspowner::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?"))
        {
            statement.setString(1, schema);
            try (var result = statement.executeQuery())
            {
                return result.next() ? new NamespaceIdentity(schema, result.getLong(1), result.getLong(2)) : null;
            }
        }
    }

    private static String platformIdentifier(String schema)
    {
        if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}") || schema.startsWith("pg_")
                || schema.equals("information_schema"))
        {
            throw new IllegalArgumentException("Invalid platform schema identifier: " + schema);
        }
        return '"' + schema + '"';
    }

    private static void rollback(Connection connection, Throwable failure)
    {
        try
        {
            connection.rollback();
        } catch (SQLException rollbackFailure)
        {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void lock(Connection connection, String schema) throws SQLException
    {
        try (var statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 0))"))
        {
            statement.setString(1, "docukore-test-schema:" + schema);
            statement.execute();
        }
    }
}
