package com.docukore.platform.migration;

import com.docukore.support.OwnedTestDatabase;
import com.docukore.support.SharedPostgresTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual PostgreSQL ACL, ownership and runtime-write checks against invocation-owned migrated schemas. */
class RuntimeDatabasePrivilegesIntegrationTest extends SharedPostgresTest
{
    /** Explicit ordinary table privileges, including privileges runtime must never receive. */
    private static final List<String> TABLE_PRIVILEGES = List.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN");
    /** General future table/session DML, not ownership or DDL privileges. */
    private static final List<String> DML = List.of("SELECT", "INSERT", "UPDATE", "DELETE");
    /** Save/find persistence and mutable registration state. */
    private static final List<String> READ_INSERT_UPDATE = List.of("SELECT", "INSERT", "UPDATE");

    @Test
    void restrictedLoginsRetainMigratorOwnershipAndRuntimeCannotPerformDdl() throws SQLException
    {
        String tenantSchema = tenant("latest");
        try (Connection migrator = OwnedTestDatabase.connection();
             Connection runtime = OwnedTestDatabase.runtimeConnection())
        {
            assertRole(migrator, "docukore_test_runner");
            assertRole(runtime, "docukore_app");
            assertEquals(0L, queryLong(runtime, "SELECT count(*) FROM pg_catalog.pg_auth_members "
                + "WHERE member = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = current_user)"));
            assertFalse(queryBoolean(runtime,
                "SELECT pg_catalog.pg_has_role(current_user, 'docukore_test_runner', 'MEMBER')"));
            assertFalse(queryBoolean(runtime,
                "SELECT pg_catalog.has_database_privilege(current_user, current_database(), 'CREATE')"));
            assertFalse(queryBoolean(runtime,
                "SELECT pg_catalog.has_schema_privilege(current_user, 'public', 'CREATE')"));
            for (String schema : List.of(platformSchema, tenantSchema))
            {
                assertSchemaPrivileges(runtime, schema);
                assertMigratorOwnership(schema);
            }
            runtime.setAutoCommit(false);
            try
            {
                deny(runtime, "SET ROLE docukore_test_runner");
                deny(runtime, "CREATE SCHEMA runtime_denied_" + UUID.randomUUID().toString().replace("-", ""));
                deny(runtime, "CREATE TABLE " + table(platformSchema, "runtime_denied_table") + " (id integer)");
                deny(runtime, "CREATE SEQUENCE " + table(tenantSchema, "runtime_denied_sequence"));
                deny(runtime, "ALTER TABLE " + table(platformSchema, "users") + " ADD COLUMN denied integer");
                deny(runtime, "DROP TABLE " + table(tenantSchema, "audit_logs"));
                deny(runtime, "ALTER SCHEMA " + OwnedTestDatabase.identifier(tenantSchema)
                    + " RENAME TO runtime_denied_rename");
                deny(runtime, "ALTER SEQUENCE " + table(platformSchema, "cabinet_schema_number_seq")
                    + " RESTART WITH 0");
            } finally
            {
                runtime.rollback();
            }
        }
    }

    @Test
    void platformMinimumGrantsSupportRegistrationAllocationAndFullSessionDml() throws SQLException
    {
        Map<String, List<String>> grants = Map.ofEntries(
            Map.entry("users", READ_INSERT_UPDATE),
            Map.entry("installation_setup", List.of("SELECT", "UPDATE")),
            Map.entry("initial_cabinet_registrations", READ_INSERT_UPDATE),
            Map.entry("email_verification_tokens", READ_INSERT_UPDATE),
            Map.entry("cabinets", List.of("SELECT", "INSERT")),
            Map.entry("cabinet_schema_allocations", List.of("SELECT", "INSERT")),
            Map.entry("cabinet_memberships", List.of("SELECT")),
            Map.entry("membership_permissions", List.of("SELECT")),
            Map.entry("cabinet_subscriptions", List.of("SELECT")),
            Map.entry("cabinet_legacy_schema_observations", List.of("SELECT")),
            Map.entry("cabinet_schema_creation_receipts", List.of("SELECT")),
            Map.entry("spring_session", DML),
            Map.entry("spring_session_attributes", DML));
        try (Connection runtime = OwnedTestDatabase.runtimeConnection())
        {
            assertTableMatrix(runtime, platformSchema, grants);
            assertSequencePrivileges(runtime, platformSchema);
            assertEquals(12L, jdbc.queryForObject("SELECT max(version::integer) FROM "
                + table(platformSchema, "flyway_schema_history") + " WHERE success", Long.class));
            runtime.setAutoCommit(false);
            try
            {
                exerciseRuntimeRegistration(runtime);
                exerciseSessionDml(runtime);
                deny(runtime, "UPDATE " + table(platformSchema, "cabinet_schema_allocations")
                    + " SET allocated_at = CURRENT_TIMESTAMP WHERE false");
                deny(runtime, "INSERT INTO " + table(platformSchema, "cabinet_schema_creation_receipts")
                    + " SELECT * FROM " + table(platformSchema, "cabinet_schema_creation_receipts") + " WHERE false");
                deny(runtime, "DELETE FROM " + table(platformSchema, "cabinet_schema_creation_receipts")
                    + " WHERE false");
                deny(runtime, "TRUNCATE " + table(platformSchema, "cabinet_schema_allocations"));
                assertHistoryDenied(runtime, platformSchema, "flyway_schema_history");
            } finally
            {
                runtime.rollback();
            }
        }
    }

    @Test
    void tenantUpgradeNarrowsEarlierAuditGrantsAndSupportsPatientWritesWithoutAuditMutation() throws SQLException
    {
        String schema = tenant("7");
        jdbc.execute("GRANT UPDATE, DELETE, TRUNCATE ON " + table(schema, "audit_logs") + " TO docukore_app");
        tenantFlyway(schema, "latest").migrate();
        assertEquals(8L, jdbc.queryForObject("SELECT max(version::integer) FROM "
            + table(schema, "flyway_schema_history_tenant") + " WHERE success", Long.class));
        try (Connection runtime = OwnedTestDatabase.runtimeConnection())
        {
            assertTableMatrix(runtime, schema, Map.of(
                "patients", READ_INSERT_UPDATE,
                "audit_logs", List.of("SELECT", "INSERT")));
            assertSchemaPrivileges(runtime, schema);
            runtime.setAutoCommit(false);
            try
            {
                UUID patient = UUID.randomUUID();
                UUID cabinet = UUID.randomUUID();
                UUID actor = UUID.randomUUID();
                UUID event = UUID.randomUUID();
                assertEquals(1, update(runtime, "INSERT INTO " + table(schema, "patients")
                    + " (id, cabinet_id, patient_number, legal_given_name, legal_family_name, created_at, updated_at)"
                    + " VALUES (?, ?, 'GRANT-PROBE', 'Fictional', 'GrantProbe', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    patient, cabinet));
                assertEquals(1, update(runtime, "UPDATE " + table(schema, "patients")
                    + " SET archived_at = CURRENT_TIMESTAMP WHERE id = ?", patient));
                assertEquals(1L, queryLong(runtime, "SELECT count(*) FROM " + table(schema, "patients")
                    + " WHERE id = ? AND archived_at IS NOT NULL", patient));
                assertEquals(1, update(runtime, "INSERT INTO " + table(schema, "audit_logs")
                    + " (id, cabinet_id, entity_type, entity_id, operation, user_id, timestamp)"
                    + " VALUES (?, ?, 'patient', ?, 'UPDATE', ?, CURRENT_TIMESTAMP)", event, cabinet, patient, actor));
                assertEquals(1L, queryLong(runtime, "SELECT count(*) FROM " + table(schema, "audit_logs")
                    + " WHERE id = ?", event));
                deny(runtime, "UPDATE " + table(schema, "audit_logs") + " SET operation = 'DELETE'");
                deny(runtime, "DELETE FROM " + table(schema, "audit_logs"));
                deny(runtime, "TRUNCATE " + table(schema, "audit_logs"));
                deny(runtime, "DELETE FROM " + table(schema, "patients") + " WHERE false");
                assertHistoryDenied(runtime, schema, "flyway_schema_history_tenant");
                assertEquals(1L, queryLong(runtime, "SELECT count(*) FROM " + table(schema, "audit_logs")
                    + " WHERE id = ? AND operation = 'UPDATE'", event));
            } finally
            {
                runtime.rollback();
            }
        }
    }

    @Test
    void futureMigratorTablesAndSequencesInBothSchemasReceiveOnlyExplicitDmlDefaults() throws SQLException
    {
        String tenantSchema = tenant("latest");
        for (String schema : List.of(platformSchema, tenantSchema))
        {
            jdbc.execute("CREATE SEQUENCE " + table(schema, "future_runtime_seq") + " START WITH 1");
            jdbc.execute("CREATE TABLE " + table(schema, "future_runtime_table")
                + " (id bigint PRIMARY KEY DEFAULT nextval('" + table(schema, "future_runtime_seq")
                + "'::regclass), probe_value integer NOT NULL)");
            assertMigratorOwnership(schema);
            try (Connection runtime = OwnedTestDatabase.runtimeConnection())
            {
                for (String privilege : TABLE_PRIVILEGES)
                {
                    assertEquals(DML.contains(privilege), queryBoolean(runtime,
                        "SELECT pg_catalog.has_table_privilege(current_user, ?, ?)",
                        table(schema, "future_runtime_table"), privilege), privilege);
                }
                assertSequencePrivileges(runtime, schema);
                runtime.setAutoCommit(false);
                try
                {
                    assertEquals(1, update(runtime, "INSERT INTO " + table(schema, "future_runtime_table")
                        + " (probe_value) VALUES (1)"));
                    assertEquals(1L, queryLong(runtime, "SELECT count(*) FROM "
                        + table(schema, "future_runtime_table") + " WHERE probe_value = 1"));
                    assertEquals(1, update(runtime, "UPDATE " + table(schema, "future_runtime_table")
                        + " SET probe_value = 2 WHERE probe_value = 1"));
                    assertTrue(queryLong(runtime, "SELECT nextval(?::regclass)",
                        table(schema, "future_runtime_seq")) > 0);
                    assertTrue(queryLong(runtime, "SELECT currval(?::regclass)",
                        table(schema, "future_runtime_seq")) > 0);
                    assertEquals(1L, queryLong(runtime, "SELECT count(*) FROM "
                        + table(schema, "future_runtime_seq")));
                    assertEquals(1, update(runtime, "DELETE FROM " + table(schema, "future_runtime_table")
                        + " WHERE probe_value = 2"));
                    deny(runtime, "SELECT setval('" + table(schema, "future_runtime_seq") + "'::regclass, 1)");
                    deny(runtime, "TRUNCATE " + table(schema, "future_runtime_table"));
                    deny(runtime, "ALTER TABLE " + table(schema, "future_runtime_table")
                        + " ADD COLUMN denied integer");
                } finally
                {
                    runtime.rollback();
                }
            }
        }
    }

    @Test
    void defaultsDoNotGrantAccessToAnUnmigratedSchema() throws SQLException
    {
        String schema = newSchema("unmigrated");
        jdbc.execute("CREATE TABLE " + table(schema, "ungranted_table") + " (id integer)");
        jdbc.execute("CREATE SEQUENCE " + table(schema, "ungranted_sequence"));
        try (Connection runtime = OwnedTestDatabase.runtimeConnection())
        {
            assertFalse(queryBoolean(runtime,
                "SELECT pg_catalog.has_schema_privilege(current_user, ?, 'USAGE')", schema));
            Long tableOid = jdbc.queryForObject("SELECT ?::regclass::oid::bigint", Long.class,
                table(schema, "ungranted_table"));
            Long sequenceOid = jdbc.queryForObject("SELECT ?::regclass::oid::bigint", Long.class,
                table(schema, "ungranted_sequence"));
            for (String privilege : TABLE_PRIVILEGES)
            {
                assertFalse(queryBoolean(runtime,
                    "SELECT pg_catalog.has_table_privilege(current_user, ?::oid, ?)", tableOid, privilege));
            }
            assertFalse(queryBoolean(runtime,
                "SELECT pg_catalog.has_sequence_privilege(current_user, ?::oid, 'USAGE')", sequenceOid));
            runtime.setAutoCommit(false);
            try
            {
                deny(runtime, "SELECT * FROM " + table(schema, "ungranted_table"));
            } finally
            {
                runtime.rollback();
            }
        }
    }

    private void assertMigratorOwnership(String schema)
    {
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_namespace n "
            + "JOIN pg_catalog.pg_roles r ON r.oid = n.nspowner "
            + "WHERE n.nspname = ? AND r.rolname = 'docukore_test_runner'", Long.class, schema));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_catalog.pg_roles r ON r.oid = c.relowner "
            + "WHERE n.nspname = ? AND r.rolname <> 'docukore_test_runner'", Long.class, schema));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_proc p "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace "
            + "JOIN pg_catalog.pg_roles r ON r.oid = p.proowner "
            + "WHERE n.nspname = ? AND r.rolname <> 'docukore_test_runner'", Long.class, schema));
    }

    private void assertTableMatrix(Connection runtime, String schema, Map<String, List<String>> grants)
        throws SQLException
    {
        List<String> tables = jdbc.queryForList(
            "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = ?", String.class, schema);
        assertTrue(tables.containsAll(grants.keySet()));
        for (String name : tables)
        {
            List<String> allowed = grants.getOrDefault(name, List.of());
            for (String privilege : TABLE_PRIVILEGES)
            {
                assertEquals(allowed.contains(privilege), queryBoolean(runtime,
                    "SELECT pg_catalog.has_table_privilege(current_user, ?, ?)", table(schema, name), privilege),
                    name + " " + privilege);
            }
        }
    }

    private void assertSequencePrivileges(Connection runtime, String schema) throws SQLException
    {
        List<String> sequences = jdbc.queryForList(
            "SELECT sequencename FROM pg_catalog.pg_sequences WHERE schemaname = ?", String.class, schema);
        for (String sequence : sequences)
        {
            String qualified = table(schema, sequence);
            assertTrue(queryBoolean(runtime,
                "SELECT pg_catalog.has_sequence_privilege(current_user, ?, 'USAGE')", qualified));
            assertTrue(queryBoolean(runtime,
                "SELECT pg_catalog.has_sequence_privilege(current_user, ?, 'SELECT')", qualified));
            assertFalse(queryBoolean(runtime,
                "SELECT pg_catalog.has_sequence_privilege(current_user, ?, 'UPDATE')", qualified));
        }
    }

    private void exerciseRuntimeRegistration(Connection runtime) throws SQLException
    {
        UUID account = UUID.randomUUID();
        UUID registration = UUID.randomUUID();
        UUID cabinet = UUID.randomUUID();
        assertEquals(1, update(runtime, "INSERT INTO " + table(platformSchema, "users")
            + " (id, username, username_normalized, password_hash, deployment_mode, created_at,"
            + " activated_at, account_status) VALUES (?, 'grant-probe', 'grant-probe', 'fictional-hash',"
            + " 'local', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE')", account));
        assertEquals(1, update(runtime, "UPDATE " + table(platformSchema, "users")
            + " SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", account));
        assertEquals(1L, queryLong(runtime, "SELECT id FROM "
            + table(platformSchema, "installation_setup") + " WHERE id = 1 FOR UPDATE"));
        assertEquals(1, update(runtime, "UPDATE " + table(platformSchema, "installation_setup")
            + " SET first_owner_id = ?, completed_at = CURRENT_TIMESTAMP WHERE id = 1", account));
        assertEquals(1, update(runtime, "INSERT INTO " + table(platformSchema, "initial_cabinet_registrations")
            + " (id, account_id, idempotency_key, cabinet_name) VALUES (?, ?, ?, 'Fictional grant cabinet')",
            registration, account, UUID.randomUUID()));
        assertEquals(1L, queryLong(runtime, "SELECT 1 FROM "
            + table(platformSchema, "initial_cabinet_registrations") + " r JOIN "
            + table(platformSchema, "users") + " u ON u.id = r.account_id WHERE r.id = ? FOR UPDATE OF r, u",
            registration));
        long number = queryLong(runtime, "SELECT pg_catalog.nextval(?::regclass)",
            table(platformSchema, "cabinet_schema_number_seq"));
        String cabinetSchema = "cabinet" + String.format(java.util.Locale.ROOT, "%05d", number);
        assertEquals(1, update(runtime, "INSERT INTO " + table(platformSchema, "cabinet_schema_allocations")
            + " (reservation_id, cabinet_id, allocation_kind, schema_number, schema_name)"
            + " VALUES (?, ?, 'SEQUENTIAL', ?, ?)", UUID.randomUUID(), cabinet, number, cabinetSchema));
        assertEquals(1, update(runtime, "INSERT INTO " + table(platformSchema, "cabinets")
            + " (id, owner_id, name, schema_name, created_at)"
            + " VALUES (?, ?, 'Fictional grant cabinet', ?, CURRENT_TIMESTAMP)", cabinet, account, cabinetSchema));
        assertEquals(1, update(runtime, "UPDATE " + table(platformSchema, "initial_cabinet_registrations")
            + " SET cabinet_id = ? WHERE id = ?", cabinet, registration));
        UUID token = UUID.randomUUID();
        assertEquals(1, update(runtime, "INSERT INTO " + table(platformSchema, "email_verification_tokens")
            + " (id, registration_id, token_hash, expires_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP + interval '1 day')",
            token, registration, "a".repeat(64)));
        assertEquals(1, update(runtime, "UPDATE " + table(platformSchema, "email_verification_tokens")
            + " SET consumed_at = CURRENT_TIMESTAMP WHERE id = ?", token));
        assertEquals(1L, queryLong(runtime, "SELECT count(*) FROM "
            + table(platformSchema, "cabinet_schema_allocations") + " WHERE cabinet_id = ?", cabinet));
    }

    private void exerciseSessionDml(Connection runtime) throws SQLException
    {
        String session = UUID.randomUUID().toString();
        assertEquals(1, update(runtime, "INSERT INTO " + table(platformSchema, "spring_session")
            + " (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time)"
            + " VALUES (?, ?, 1, 1, 60, 60001)", session, UUID.randomUUID().toString()));
        assertEquals(1, update(runtime, "INSERT INTO " + table(platformSchema, "spring_session_attributes")
            + " (session_primary_id, attribute_name, attribute_bytes) VALUES (?, 'probe', ?)",
            session, new byte[] {1}));
        assertEquals(1L, queryLong(runtime, "SELECT count(*) FROM " + table(platformSchema, "spring_session")
            + " WHERE primary_id = ?", session));
        assertEquals(1L, queryLong(runtime, "SELECT count(*) FROM "
            + table(platformSchema, "spring_session_attributes") + " WHERE session_primary_id = ?", session));
        assertEquals(1, update(runtime, "UPDATE " + table(platformSchema, "spring_session")
            + " SET last_access_time = 2 WHERE primary_id = ?", session));
        assertEquals(1, update(runtime, "UPDATE " + table(platformSchema, "spring_session_attributes")
            + " SET attribute_bytes = ? WHERE session_primary_id = ?", new byte[] {2}, session));
        assertEquals(1, update(runtime, "DELETE FROM " + table(platformSchema, "spring_session_attributes")
            + " WHERE session_primary_id = ?", session));
        assertEquals(1, update(runtime, "DELETE FROM " + table(platformSchema, "spring_session")
            + " WHERE primary_id = ?", session));
    }

    private static void assertRole(Connection connection, String role) throws SQLException
    {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT current_user, session_user, current_database(), "
                 + "rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolreplication "
                 + "FROM pg_catalog.pg_roles WHERE rolname = current_user"))
        {
            assertTrue(rows.next());
            assertEquals(role, rows.getString(1));
            assertEquals(role, rows.getString(2));
            assertEquals("docukore_test", rows.getString(3));
            assertTrue(rows.getBoolean(4));
            for (int index = 5; index <= 9; index++)
            {
                assertFalse(rows.getBoolean(index));
            }
        }
    }

    private static void assertSchemaPrivileges(Connection runtime, String schema) throws SQLException
    {
        assertTrue(queryBoolean(runtime,
            "SELECT pg_catalog.has_schema_privilege(current_user, ?, 'USAGE')", schema));
        assertFalse(queryBoolean(runtime,
            "SELECT pg_catalog.has_schema_privilege(current_user, ?, 'CREATE')", schema));
    }

    private static void assertHistoryDenied(Connection runtime, String schema, String history) throws SQLException
    {
        String qualified = table(schema, history);
        deny(runtime, "SELECT count(*) FROM " + qualified);
        deny(runtime, "INSERT INTO " + qualified + " SELECT * FROM " + qualified + " WHERE false");
        deny(runtime, "UPDATE " + qualified + " SET success = false WHERE false");
        deny(runtime, "DELETE FROM " + qualified + " WHERE false");
        deny(runtime, "TRUNCATE " + qualified);
    }

    private static void deny(Connection runtime, String sql) throws SQLException
    {
        var savepoint = runtime.setSavepoint();
        try
        {
            SQLException failure = assertThrows(SQLException.class, () ->
            {
                try (var statement = runtime.createStatement())
                {
                    statement.execute(sql);
                }
            });
            assertEquals("42501", failure.getSQLState(), "Expected insufficient_privilege, not a trigger rejection");
        } finally
        {
            runtime.rollback(savepoint);
            runtime.releaseSavepoint(savepoint);
        }
    }

    private static String table(String schema, String name)
    {
        if (!name.matches("[a-z][a-z0-9_]{0,62}"))
        {
            throw new IllegalArgumentException("Invalid test object identifier");
        }
        return OwnedTestDatabase.identifier(schema) + ".\"" + name + "\"";
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters)
        throws SQLException
    {
        PreparedStatement statement = connection.prepareStatement(sql);
        try
        {
            for (int index = 0; index < parameters.length; index++)
            {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement;
        } catch (SQLException failure)
        {
            statement.close();
            throw failure;
        }
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException
    {
        try (var statement = prepare(connection, sql, parameters))
        {
            return statement.executeUpdate();
        }
    }

    private static long queryLong(Connection connection, String sql, Object... parameters) throws SQLException
    {
        try (var statement = prepare(connection, sql, parameters);
             var rows = statement.executeQuery())
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql, Object... parameters) throws SQLException
    {
        try (var statement = prepare(connection, sql, parameters);
             var rows = statement.executeQuery())
        {
            assertTrue(rows.next());
            return rows.getBoolean(1);
        }
    }
}
