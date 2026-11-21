package com.docukore.platform.cabinet;

import com.docukore.support.SharedPostgresTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests platform cabinet migrations using the shared PostgreSQL instance.
 * Creates isolated platform schemas per test method for migration verification.
 */
public abstract class CabinetPlatformMigrationTest extends SharedPostgresTest
{

    @BeforeEach
    final void setupPlatformSchema() throws Exception
    {
        // Use the isolated platform schema created by SharedPostgresTest
        // The platformSchema field is already set up with migrations applied in SharedPostgresTest.openDatabase()
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    @AfterEach
    final void cleanup()
    {
        // SharedPostgresTest handles dataSource cleanup at class level
        // Individual test methods using platform() create additional schemas that remain for debugging
    }

    protected String platform(String kind, String target)
    {
        String schema = "public".equals(kind) ? platformSchema : newSchema("platform");
        if (!"public".equals(schema))
        {
            // Per-test cabinet schemas tracked via OwnedTestDatabase; no global scan cleanup
            try (Connection conn = DriverManager.getConnection(SHARED_JDBC_URL, SHARED_USER, SHARED_PASSWORD))
            {
                // Reset sequence for this platform schema using the shared data source
                jdbc.execute("ALTER SEQUENCE IF EXISTS " + schema + ".cabinet_schema_number_seq RESTART WITH 0");
            } catch (Exception e)
            {
                System.err.println("Warning: Failed to reset sequence for " + schema + ": " + e.getMessage());
            }
            platformFlyway(schema, target).migrate();
        }
        return schema;
    }

    protected Flyway platformFlyway(String schema, String target)
    {
        return Flyway.configure().dataSource(SHARED_JDBC_URL, SHARED_USER, SHARED_PASSWORD)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration/platform")
            .target(target)
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .load();
    }

    protected void upgrade(String schema)
    {
        platformFlyway(schema, "latest").migrate();
    }

    protected void requireTable(String schema, String table)
    {
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'r'
            """, Integer.class, schema, table), "Stage B migration must create " + schema + "." + table);
    }

    protected void requireSequence(String schema)
    {
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = 'cabinet_schema_number_seq' AND c.relkind = 'S'
            """, Integer.class, schema), "Stage B permanent allocation sequence is missing");
    }

    protected UUID user(String schema, String username, String email,
                    String emailNormalized, String mode, String status)
    {
        UUID id = UUID.randomUUID();
        String usernameNormalized = username.toLowerCase().trim();
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, email, email_normalized,
                                  password_hash, deployment_mode, created_at, account_status)
            VALUES (?, ?, ?, ?, ?, 'fictional-hash', ?, now(), ?)
            """.formatted(schema), id, username, usernameNormalized, email, emailNormalized, mode, status);
        return id;
    }

    protected UUID localUser(String schema, String username)
    {
        String usernameNormalized = username.toLowerCase().trim();
        return user(schema, username,
            usernameNormalized + "@example.invalid",
            usernameNormalized + "@example.invalid",
            "local", "ACTIVE");
    }

    protected UUID cabinet(String schema, UUID ownerId, String name, String schemaName, String status)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name,
                                     provisioning_status, created_at, provisioned_at)
            VALUES (?, ?, ?, ?, ?, now(), now())
            """.formatted(schema), id, ownerId, name, schemaName, status);
        return id;
    }

    protected Map<String, Object> allocation(String schema, UUID cabinetId, String kind,
                                          Long number, String schemaName, String status)
    {
        UUID reservationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO %s.cabinet_schema_allocations
                (reservation_id, cabinet_id, allocation_kind, schema_number, schema_name, allocated_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """.formatted(schema), reservationId, cabinetId, kind, number, schemaName);
        return Map.of("reservation_id", reservationId, "cabinet_id", cabinetId);
    }

    protected String legacyName(UUID cabinetId)
    {
        return "cabinet_" + cabinetId.toString().replace("-", "");
    }

    protected int count(String schema, String table)
    {
        return jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + table, Integer.class);
    }

    protected void assertRejected(Executable action)
    {
        assertThrows(DataAccessException.class, action);
    }

    protected Map<String, Object> namespace(String schemaName)
    {
        return jdbc.queryForMap(
            "SELECT oid::bigint AS namespace_oid, nspowner::bigint AS owner_oid "
                + "FROM pg_catalog.pg_namespace WHERE nspname = ?",
            schemaName);
    }

    protected List<Map<String, Object>> history(String schema, String historyTable)
    {
        return jdbc.queryForList(
            "SELECT * FROM " + schema + "." + historyTable + " ORDER BY installed_rank");
    }

    protected void assertElevenVersions(String schema)
    {
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"),
            jdbc.queryForList(
                "SELECT version FROM " + schema
                    + ".flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class));
    }

    protected void legacyCabinet(String schema, UUID owner, UUID cabinetId, String schemaName)
    {
        UUID reservationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO %s.cabinet_schema_allocations
                (reservation_id, cabinet_id, allocation_kind, schema_name, allocated_at)
            VALUES (?, ?, 'LEGACY', ?, CURRENT_TIMESTAMP)
            """.formatted(schema), reservationId, cabinetId, schemaName);
        jdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name, provisioning_status, created_at, provisioned_at)
            VALUES (?, ?, 'Fictional cabinet', ?, 'ACTIVE', now(), now())
            """.formatted(schema), cabinetId, owner, schemaName);
    }
}
