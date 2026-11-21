package com.docukore.support;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Existing PostgreSQL service, externally configured test database, pooled connections and exact cleanup. */
public abstract class SharedPostgresTest
{
    /** Shared JDBC URL for test database. */
    public static final String SHARED_JDBC_URL = OwnedTestDatabase.jdbcUrl();
    /** Shared username for test database. */
    public static final String SHARED_USER = OwnedTestDatabase.username();
    /** Shared password for test database. */
    public static final String SHARED_PASSWORD = OwnedTestDatabase.password();

    /** Shared HikariDataSource pool. */
    private static HikariDataSource SHARED_DATA_SOURCE;
    /** Per-test OwnedTestDatabase scope. */
    private OwnedTestDatabase.Scope resources;

    /** Test data source. */
    protected HikariDataSource dataSource;
    /** Test JdbcTemplate. */
    protected JdbcTemplate jdbc;
    /** Test transaction template. */
    protected TransactionTemplate transactions;
    /** Test platform schema name. */
    protected String platformSchema;

    @BeforeAll
    static void validateSharedPostgres()
    {
        SHARED_DATA_SOURCE = OwnedTestDatabase.acquirePool();
    }

    @BeforeEach
    void openDatabase()
    {
        dataSource = SHARED_DATA_SOURCE;
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        resources = OwnedTestDatabase.scope(dataSource);
        if (migratePlatformAtStart())
        {
            platformSchema = newPlatform("latest");
        }
    }

    /**
     * Migration contract tests choose their own upgrade boundary instead of migrating twice.
     *
     * @return true if platform migration should run at start
     */
    protected boolean migratePlatformAtStart()
    {
        return true;
    }

    @AfterEach
    void closeDatabase()
    {
        try
        {
            if (resources != null)
            {
                resources.close();
            }
        } finally
        {
            resources = null;
            platformSchema = null;
            jdbc = null;
            transactions = null;
            dataSource = null;
        }
    }

    @AfterAll
    static void cleanupSharedSchemas()
    {
        OwnedTestDatabase.releasePool();
    }

    protected String newSchema(String kind)
    {
        return resources.create(kind);
    }

    protected OwnedTestDatabase.NamespaceIdentity createOwnedSchema(String exactName)
    {
        return resources.createExact(exactName);
    }

    protected void dropOwnedSchema(String exactName)
    {
        resources.drop(exactName);
    }

    protected String newPlatform(String target)
    {
        String schema = newSchema("platform");
        platformFlyway(schema, target).migrate();
        return schema;
    }

    protected Flyway platformFlyway(String schema, String target)
    {
        OwnedTestDatabase.requireOwned(schema);
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/platform").target(target)
                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).load();
    }

    protected Flyway tenantFlyway(String schema, String target)
    {
        OwnedTestDatabase.requireOwned(schema);
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/tenant").table("flyway_schema_history_tenant")
                .target(target).createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).load();
    }

    protected String tenant(String target)
    {
        String schema = newSchema("tenant");
        tenantFlyway(schema, target).migrate();
        return schema;
    }

    protected static PSQLException databaseFailure(Executable action)
    {
        Throwable failure = assertThrows(org.springframework.dao.DataAccessException.class, action);
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
        {
            if (cause instanceof PSQLException sql)
            {
                return sql;
            }
        }
        throw new AssertionError("Expected PostgreSQL JDBC cause", failure);
    }

    protected static void assertDatabaseError(String state, String constraint, Executable action)
    {
        PSQLException sql = databaseFailure(action);
        assertEquals(state, sql.getSQLState());
        assertNotNull(sql.getServerErrorMessage());
        assertEquals(constraint, sql.getServerErrorMessage().getConstraint());
    }

    protected static void assertDatabaseMessage(String state, String message, Executable action)
    {
        PSQLException sql = databaseFailure(action);
        assertEquals(state, sql.getSQLState());
        assertTrue(sql.getMessage().contains(message), "Expected PostgreSQL error message: " + message);
    }

    protected UUID createCabinetWithAllocation(UUID ownerId)
    {
        if (platformSchema == null)
        {
            String msg = "Platform schema not initialized; "
                    + "call migratePlatformAtStart() or set platformSchema";
            throw new IllegalStateException(msg);
        }
        return TestFixtures.createCabinet(jdbc, platformSchema, ownerId, resources).cabinetId();
    }

    protected CabinetWithSchema createCabinetWithAllocationAndSchema(UUID ownerId)
    {
        if (platformSchema == null)
        {
            String msg = "Platform schema not initialized; "
                    + "call migratePlatformAtStart() or set platformSchema";
            throw new IllegalStateException(msg);
        }
        var cabinet = TestFixtures.createCabinet(jdbc, platformSchema, ownerId, resources);
        return new CabinetWithSchema(cabinet.cabinetId(), cabinet.schemaName());
    }

    public record CabinetWithSchema(UUID cabinetId, String schemaName)
    {
    }
}
