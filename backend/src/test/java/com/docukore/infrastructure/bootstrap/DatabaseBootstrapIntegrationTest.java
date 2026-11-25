package com.docukore.infrastructure.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in first-install verification on an explicitly empty, loopback development application database. */
@EnabledIfEnvironmentVariable(named = "DOCUKORE_BOOTSTRAP_INTEGRATION_TESTS", matches = "true")
class DatabaseBootstrapIntegrationTest
{
    /** Preserve existing restricted role OIDs and their grants on the independent test database. */
    private static final String SAVED_APP = "docukore_bootstrap_saved_app";
    /** Preserve the existing migration role while testing genuinely missing application identities. */
    private static final String SAVED_MIGRATOR = "docukore_bootstrap_saved_migrator";

    @Test
    void createsMissingDatabaseAndRolesThenRetainsDatabaseDataAndIndependentTestDatabase() throws Exception
    {
        Map<String, String> values = System.getenv();
        URI endpoint = URI.create(values.get("DOCUKORE_BOOTSTRAP_URL").substring(5));
        assertTrue(endpoint.getHost().equals("localhost") || endpoint.getHost().equals("127.0.0.1")
            || endpoint.getHost().equals("[::1]") || endpoint.getHost().equals("::1"),
            "Opt-in verification requires a literal loopback development endpoint");
        assertEquals("/postgres", endpoint.getPath());
        assertEquals("docukore", values.get("DOCUKORE_BOOTSTRAP_USERNAME"));
        try (Connection admin = connect(values.get("DOCUKORE_BOOTSTRAP_URL"), "docukore",
                values.get("DOCUKORE_BOOTSTRAP_PASSWORD")))
        {
            assertEquals("180007", scalar(admin, "SHOW server_version_num"));
            assertEquals("", scalar(admin, "SELECT oid::text FROM pg_database WHERE datname = 'docukore'"),
                "Requires an absent development database; never deletes a pre-existing database");
            assertEquals("", roleOid(admin, SAVED_APP));
            assertEquals("", roleOid(admin, SAVED_MIGRATOR));
            String appOid = roleOid(admin, "docukore_app");
            String migratorOid = roleOid(admin, "docukore_migrator");
            String testOid = scalar(admin, "SELECT oid::text FROM pg_database WHERE datname = 'docukore_test'");
            String testRoleOid = roleOid(admin, "docukore_test_runner");
            boolean appSaved = false;
            boolean migratorSaved = false;
            try
            {
                if (!appOid.isEmpty())
                {
                    sql(admin, "ALTER ROLE docukore_app RENAME TO " + SAVED_APP);
                    appSaved = true;
                }
                if (!migratorOid.isEmpty())
                {
                    sql(admin, "ALTER ROLE docukore_migrator RENAME TO " + SAVED_MIGRATOR);
                    migratorSaved = true;
                }
                DatabaseBootstrap.initialize(values);
                String createdOid = scalar(admin, "SELECT oid::text FROM pg_database WHERE datname = 'docukore'");
                assertFalse(createdOid.isEmpty());
                assertRestrictedRole(admin, "docukore_app");
                assertRestrictedRole(admin, "docukore_migrator");
                try (Connection writer = connect(values.get("DOCUKORE_MIGRATION_URL"), "docukore_migrator",
                        values.get("DOCUKORE_MIGRATION_PASSWORD")))
                {
                    sql(writer, "CREATE TABLE public.bootstrap_retained_marker (value integer NOT NULL)");
                    sql(writer, "INSERT INTO public.bootstrap_retained_marker VALUES (42)");
                }
                DatabaseBootstrap.initialize(values);
                DatabaseBootstrap.initialize(values);
                assertEquals(createdOid,
                    scalar(admin, "SELECT oid::text FROM pg_database WHERE datname = 'docukore'"));
                try (Connection writer = connect(values.get("DOCUKORE_MIGRATION_URL"), "docukore_migrator",
                        values.get("DOCUKORE_MIGRATION_PASSWORD")))
                {
                    assertEquals("42", scalar(writer, "SELECT value::text FROM public.bootstrap_retained_marker"));
                }
                assertEquals(testOid,
                    scalar(admin, "SELECT oid::text FROM pg_database WHERE datname = 'docukore_test'"));
                assertEquals(testRoleOid, roleOid(admin, "docukore_test_runner"));
            } finally
            {
                // Only the database created by this opt-in test is disposable. Existing test data is untouched.
                sql(admin, "DROP DATABASE IF EXISTS docukore WITH (FORCE)");
                sql(admin, "DROP ROLE IF EXISTS docukore_app");
                sql(admin, "DROP ROLE IF EXISTS docukore_migrator");
                if (appSaved)
                {
                    sql(admin, "ALTER ROLE " + SAVED_APP + " RENAME TO docukore_app");
                    assertEquals(appOid, roleOid(admin, "docukore_app"));
                }
                if (migratorSaved)
                {
                    sql(admin, "ALTER ROLE " + SAVED_MIGRATOR + " RENAME TO docukore_migrator");
                    assertEquals(migratorOid, roleOid(admin, "docukore_migrator"));
                }
            }
        }
    }

    private static Connection connect(String url, String username, String password) throws Exception
    {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        return DriverManager.getConnection(url, properties);
    }

    private static String roleOid(Connection connection, String role) throws Exception
    {
        return scalar(connection, "SELECT oid::text FROM pg_roles WHERE rolname = '" + role + "'");
    }

    private static void assertRestrictedRole(Connection connection, String role) throws Exception
    {
        assertEquals("false", scalar(connection, "SELECT (rolsuper OR rolcreatedb OR rolcreaterole "
            + "OR rolbypassrls OR rolreplication)::text FROM pg_roles WHERE rolname = '" + role + "'"));
    }

    private static String scalar(Connection connection, String statement) throws Exception
    {
        try (Statement query = connection.createStatement(); ResultSet row = query.executeQuery(statement))
        {
            return row.next() ? row.getString(1) : "";
        }
    }

    private static void sql(Connection connection, String statement) throws Exception
    {
        try (Statement query = connection.createStatement())
        {
            query.execute(statement);
        }
    }
}
