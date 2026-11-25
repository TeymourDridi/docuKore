package com.docukore.infrastructure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/** Focused process-boundary and catalog guards without PostgreSQL, Spring or infrastructure startup. */
class DatabaseBootstrapTest
{
    @Test
    void acceptsOnlyThreeExplicitModes()
    {
        assertEquals(BootstrapMode.DEVELOPMENT, BootstrapMode.parse(new String[] {"development"}));
        assertEquals(BootstrapMode.TEST, BootstrapMode.parse(new String[] {"test"}));
        assertEquals(BootstrapMode.RESET_DEVELOPMENT, BootstrapMode.parse(new String[] {"reset-development"}));
        assertThrows(BootstrapFailure.class, () -> BootstrapMode.parse(new String[] {"production"}));
        assertThrows(BootstrapFailure.class,
            () -> BootstrapMode.parse(new String[] {"development", "fixture-password"}));
        assertThrows(BootstrapFailure.class, () -> BootstrapMode.parse(new String[0]));
    }

    @Test
    void sanitizedFailureNeverEchoesArgumentsOrPaths()
    {
        ByteArrayOutputStream success = new ByteArrayOutputStream();
        ByteArrayOutputStream failure = new ByteArrayOutputStream();
        int status = DatabaseBootstrap.run(new String[] {"fixture-sensitive-argument"}, java.util.Map.of(),
            new PrintStream(success), new PrintStream(failure));
        assertEquals(1, status);
        assertEquals("", success.toString(StandardCharsets.UTF_8));
        assertEquals("BOOTSTRAP_MODE_INVALID", failure.toString(StandardCharsets.UTF_8).strip());
        assertFalse(failure.toString(StandardCharsets.UTF_8).contains("fixture"));
    }

    @Test
    void rejectsMissingProtectedConfigWithoutConnecting()
    {
        ByteArrayOutputStream failure = new ByteArrayOutputStream();
        int status = DatabaseBootstrap.run(new String[] {"development"}, null,
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(failure));
        assertEquals(1, status);
        assertEquals("BOOTSTRAP_CONFIG_REQUIRED", failure.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void safeFailureHasNoCauseOrStackTrace()
    {
        BootstrapFailure failure = new BootstrapFailure("BOOTSTRAP_DATABASE_FAILED");
        assertNull(failure.getCause());
        assertEquals(0, failure.getStackTrace().length);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void escapesPasswordLiteralAndNeverAltersExistingRoles()
    {
        String sql = DatabaseBootstrap.createRoleSql("docukore_app", "fixture'\\password");
        assertTrue(sql.startsWith("CREATE ROLE docukore_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"));
        assertTrue(sql.contains("NOREPLICATION NOBYPASSRLS"));
        assertTrue(sql.endsWith("PASSWORD E'fixture''\\\\password'"));
        assertFalse(sql.contains("ALTER ROLE"));
        assertThrows(BootstrapFailure.class,
            () -> DatabaseBootstrap.createRoleSql("docukore_app; DROP ROLE fixture", "fixture"));
        assertThrows(BootstrapFailure.class, () -> DatabaseBootstrap.createRoleSql("docukore", "fixture"));
        assertThrows(BootstrapFailure.class,
            () -> DatabaseBootstrap.createRoleSql("docukore_app", "fixture\npassword"));
    }

    @Test
    void requiresConfiguredSuperuserIdentityAndActualMaintenanceDatabase() throws SQLException
    {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet row = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(row);
        when(row.next()).thenReturn(true);
        when(row.getLong(1)).thenReturn(10L);
        when(row.getBoolean(2)).thenReturn(true);
        when(row.getString(3)).thenReturn("docukore");
        when(row.getString(4)).thenReturn("docukore");
        when(row.getString(5)).thenReturn("postgres");
        DatabaseBootstrap.verifyAuthority(connection, "docukore");
        when(row.getLong(1)).thenReturn(42L);
        DatabaseBootstrap.verifyAuthority(connection, "docukore");
        when(row.getBoolean(2)).thenReturn(false);
        assertThrows(BootstrapFailure.class, () -> DatabaseBootstrap.verifyAuthority(connection, "docukore"));
        when(row.getBoolean(2)).thenReturn(true);
        when(row.getString(4)).thenReturn("other_admin");
        assertThrows(BootstrapFailure.class, () -> DatabaseBootstrap.verifyAuthority(connection, "docukore"));
        when(row.getString(4)).thenReturn("docukore");
        when(row.getString(5)).thenReturn("docukore");
        assertThrows(BootstrapFailure.class, () -> DatabaseBootstrap.verifyAuthority(connection, "docukore"));
    }

    @Test
    void rejectsElevatedExistingRoleMembershipAndDatabaseOwnership() throws SQLException
    {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet row = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(row);
        when(row.next()).thenReturn(true);
        when(row.getBoolean(1)).thenReturn(true);
        DatabaseBootstrap.verifyRestrictedRole(connection, "docukore_test_runner");
        for (int flag = 2; flag <= 8; flag++)
        {
            when(row.getBoolean(flag)).thenReturn(true);
            assertThrows(BootstrapFailure.class,
                () -> DatabaseBootstrap.verifyRestrictedRole(connection, "docukore_test_runner"));
            when(row.getBoolean(flag)).thenReturn(false);
        }
        when(row.getBoolean(1)).thenReturn(false);
        assertThrows(BootstrapFailure.class,
            () -> DatabaseBootstrap.verifyRestrictedRole(connection, "docukore_test_runner"));
    }
}
