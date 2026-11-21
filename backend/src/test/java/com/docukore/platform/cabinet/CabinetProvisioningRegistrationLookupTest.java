package com.docukore.platform.cabinet;

import com.docukore.platform.PlatformProperties;
import com.docukore.platform.provisioning.CabinetProvisioningException;
import com.docukore.platform.provisioning.CabinetProvisioningProperties;
import com.docukore.platform.provisioning.CabinetProvisioningService;
import com.docukore.platform.provisioning.ProvisioningResult;
import com.docukore.platform.provisioning.ProvisioningStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JDBC-boundary regressions; no live database, migrations, schema creation or cleanup. */
class CabinetProvisioningRegistrationLookupTest
{
    /** Original request identity, distinct from both account and cabinet identities. */
    private static final UUID REGISTRATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Cabinet identity stored in the V11 registration cabinet_id relationship. */
    private static final UUID CABINET = UUID.fromString("00000000-0000-0000-0000-000000000002");
    /** Original registration owner. */
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    /** Unrelated fictional account. */
    private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000004");
    /** Original allocation receipt identity. */
    private static final UUID RESERVATION = UUID.fromString("00000000-0000-0000-0000-000000000005");
    /** Exact attempt authorized to finalize the fictional cabinet. */
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000006");
    /** Fictional allocated namespace, never created by these tests. */
    private static final String SCHEMA = "cabinet00000";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cabinetLookupAcceptsOriginalOwnerWhenRegistrationAndCabinetIdsDiffer(boolean completed) throws Exception
    {
        assertNotEquals(REGISTRATION, CABINET);
        assertNotEquals(REGISTRATION, ACCOUNT);
        Fixture fixture = new Fixture();

        fixture.invoke(completed, ACCOUNT);
        fixture.invoke(completed, ACCOUNT);

        // These are SQL/binding contract assertions, not a claim of PostgreSQL execution.
        assertEquals("SELECT r.id, r.account_id, r.cabinet_id" + (completed ? " " : ", r.cabinet_name ")
            + "FROM \"platform\".initial_cabinet_registrations r WHERE r.cabinet_id = ? FOR UPDATE",
            fixture.registrationSql);
        verify(fixture.registrationStatement).setObject(1, CABINET);
        if (completed)
        {
            verify(fixture.connection, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("INSERT"));
            verify(fixture.connection, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("UPDATE"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cabinetLookupRejectsDifferentRegistrationOwner(boolean completed) throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.registrationAccount = OTHER_ACCOUNT;
        assertRejected(fixture, completed, "ACCOUNT_DOES_NOT_OWN_REGISTRATION", "Account does not own registration");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cabinetLookupRejectsMissingRegistration(boolean completed) throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.registrationPresent = false;
        assertRejected(fixture, completed, "ORIGINAL_REGISTRATION_NOT_FOUND", "Original registration not found");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cabinetLookupRejectsMissingRegistrationOwner(boolean completed) throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.registrationAccount = null;
        assertRejected(fixture, completed, "ACCOUNT_DOES_NOT_OWN_REGISTRATION", "Account does not own registration");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cabinetLookupRejectsMissingCabinetLink(boolean completed) throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.registrationCabinet = null;
        assertRejected(fixture, completed, "REGISTRATION_CABINET_MISMATCH", "Registration cabinet mismatch");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cabinetLookupRejectsInconsistentCabinetLink(boolean completed) throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.registrationCabinet = REGISTRATION;
        assertRejected(fixture, completed, "REGISTRATION_CABINET_MISMATCH", "Registration cabinet mismatch");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cabinetLookupRejectsMultipleRegistrations(boolean completed) throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.duplicateRegistration = true;
        assertRejected(fixture, completed, "REGISTRATION_CABINET_MISMATCH", "Registration cabinet mismatch");
    }

    @Test
    void finalizationRejectsAnotherAttemptBeforeWritingMembershipOrTrial() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.cabinetAttempt = RESERVATION;
        assertRejected(fixture, false, "PROVISIONING_ATTEMPT_MISMATCH", "Cabinet provisioning attempt mismatch");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "PROVISIONING_FAILED", "PENDING"})
    void finalizationRequiresProvisioningStateEvenWithMatchingAttempt(String status) throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.cabinetStatus = status;
        assertRejected(fixture, false, null, "Cabinet not in PROVISIONING state");
    }

    @Test
    void differentAccountIsRejectedBeforeAllocation() throws Exception
    {
        Fixture fixture = new Fixture();
        CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
            () -> fixture.service.provision(REGISTRATION, OTHER_ACCOUNT));
        assertEquals("ACCOUNT_DOES_NOT_OWN_REGISTRATION", failure.code());
        verify(fixture.repository, never()).allocate(REGISTRATION);
    }

    @Test
    void createSchemaWithReceiptRejectsMissingNamespaceBeforeCreateOrInsert() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.namespaceAbsent = true;
        fixture.receiptPresent = true;
        Exception failure = assertThrows(Exception.class, fixture::invokeSchemaCreation);
        assertEquals("Receipted schema missing from catalog",
            assertInstanceOf(SQLException.class, failure).getMessage());
        verify(fixture.connection, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("CREATE"));
        verify(fixture.connection, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("INSERT"));
    }

    @Test
    void finalizationRejectsLostAdvisoryLockBeforeWritingMembershipOrTrial() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.advisoryLockAcquired = false;
        Exception failure = assertThrows(CabinetProvisioningException.class,
            () -> fixture.invoke(false, ACCOUNT));
        assertEquals("PROVISIONING_LOCK_LOST", assertInstanceOf(CabinetProvisioningException.class, failure).code());
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("INSERT"));
        verify(fixture.connection, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("UPDATE"));
    }

    private static void assertRejected(Fixture fixture, boolean completed, String code, String sqlMessage)
        throws SQLException
    {
        Exception failure = assertThrows(Exception.class, () -> fixture.invoke(completed, ACCOUNT));
        if (completed || "PROVISIONING_ATTEMPT_MISMATCH".equals(code))
        {
            assertEquals(code, assertInstanceOf(CabinetProvisioningException.class, failure).code());
            if (completed)
            {
                verify(fixture.connection).rollback();
            }
        } else
        {
            assertEquals(sqlMessage, assertInstanceOf(SQLException.class, failure).getMessage());
        }
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("INSERT"));
        verify(fixture.connection, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("UPDATE"));
    }

    /** Explicit JDBC doubles exercise service mapping/validation without enabling database infrastructure. */
    private static final class Fixture
    {
        /** Idempotent allocation boundary, not executed against a database. */
        private final CabinetRegistrationRepository repository = mock(CabinetRegistrationRepository.class);
        /** Platform read boundary. */
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        /** Dedicated provisioning connection boundary. */
        private final Connection connection = mock(Connection.class);
        /** Fictional connection settings intercepted before DriverManager can connect. */
        private final CabinetProvisioningProperties properties = new CabinetProvisioningProperties(
            "jdbc:postgresql://example.invalid/docukore_test", "fictional", "fictional", null, null, null, null);
        /** Real service under test. */
        private final CabinetProvisioningService service = new CabinetProvisioningService(
            repository, properties, jdbc, new PlatformProperties("platform"));
        /** Account stored on the registration row. */
        private UUID registrationAccount = ACCOUNT;
        /** Cabinet link returned by the JDBC boundary. */
        private UUID registrationCabinet = CABINET;
        /** Whether the requested cabinet has a registration row. */
        private boolean registrationPresent = true;
        /** Simulates corrupted linkage despite V11's unique constraint. */
        private boolean duplicateRegistration;
        /** State observed by either the completed retry or exact-attempt finalization path. */
        private boolean completedPath;
        /** Receipt exists but tenant namespace absent (catalog missing). */
        private boolean namespaceAbsent;
        /** Receipt row present for the create-schema-with-receipt path. */
        private boolean receiptPresent;
        /** Advisory lock acquisition result (true = acquired, false = lost). */
        private boolean advisoryLockAcquired = true;
        /** Recorded attempt; independent of registration/cabinet/account identities. */
        private UUID cabinetAttempt = ATTEMPT;
        /** Default eligible lifecycle state for finalization. */
        private String cabinetStatus = "PROVISIONING";
        /** Captured SQL emitted by the real service. */
        private String registrationSql;
        /** Last registration statement, used to check the bound cabinet identity. */
        private PreparedStatement registrationStatement;

        private Fixture() throws SQLException
        {
            when(jdbc.queryForObject("SELECT account_id FROM \"platform\".initial_cabinet_registrations WHERE id = ?",
                UUID.class, REGISTRATION)).thenReturn(ACCOUNT);
            when(repository.allocate(REGISTRATION)).thenReturn(
                new CabinetRegistrationRepository.Allocation(CABINET, RESERVATION, 0L, SCHEMA));
            // Existing completed/finalization fixtures have matching immutable receipts.
            receiptPresent = true;
            when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
            {
                String sql = invocation.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                ResultSet row = mock(ResultSet.class);
                when(row.next()).thenReturn(true, false);
                if (sql.startsWith("SELECT r.id"))
                {
                    registrationSql = sql;
                    registrationStatement = statement;
                    when(row.next()).thenReturn(registrationPresent, duplicateRegistration, false);
                    when(row.getObject("id")).thenReturn(REGISTRATION);
                    when(row.getObject("account_id")).thenReturn(registrationAccount);
                    when(row.getObject("cabinet_id")).thenReturn(registrationCabinet);
                    when(row.getString("cabinet_name")).thenReturn("Fictional cabinet");
                } else if (sql.contains(".users WHERE id"))
                {
                    when(row.getObject("id")).thenReturn(ACCOUNT);
                    when(row.getString("account_status")).thenReturn("ACTIVE");
                } else if (sql.startsWith("SELECT provisioning_status FROM"))
                {
                    when(row.getString(1)).thenReturn(completedPath ? "ACTIVE" : cabinetStatus);
                } else if (sql.contains(".cabinets WHERE id"))
                {
                    when(row.getObject("id")).thenReturn(CABINET);
                    when(row.getObject("owner_id")).thenReturn(ACCOUNT);
                    when(row.getString("schema_name")).thenReturn(SCHEMA);
                    when(row.getString("provisioning_status")).thenReturn(completedPath ? "ACTIVE" : cabinetStatus);
                    when(row.getObject("provisioning_attempt_id")).thenReturn(cabinetAttempt);
                    when(row.getString("administrative_status")).thenReturn("AVAILABLE");
                } else if (sql.contains("pg_try_advisory_lock"))
                {
                    when(row.getBoolean(1)).thenReturn(true);
                } else if (sql.startsWith("SELECT EXISTS") && sql.contains(".cabinet_schema_creation_receipts"))
                {
                    when(row.getBoolean(1)).thenReturn(receiptPresent);
                } else if (sql.contains(".cabinet_schema_creation_receipts"))
                {
                    if (receiptPresent)
                    {
                        when(row.next()).thenReturn(true, false);
                        when(row.getObject("reservation_id")).thenReturn(RESERVATION);
                        when(row.getLong("namespace_oid")).thenReturn(11L);
                        when(row.getLong("owner_oid")).thenReturn(12L);
                    }
                    else
                    {
                        when(row.next()).thenReturn(false);
                    }
                } else if (sql.contains("pg_catalog.pg_namespace"))
                {
                    if (sql.startsWith("SELECT EXISTS"))
                    {
                        when(row.getBoolean(1)).thenReturn(!namespaceAbsent);
                    } else
                    {
                        when(row.next()).thenReturn(!namespaceAbsent, false);
                        when(row.getLong(1)).thenReturn(11L);
                        when(row.getLong(2)).thenReturn(12L);
                    }
                } else if (sql.contains("flyway_schema_history_tenant WHERE success = true"))
                {
                    when(row.getInt(1)).thenReturn(1);
                } else if (sql.contains("cabinet_memberships WHERE cabinet_id") && sql.contains("status = 'ACTIVE'"))
                {
                    when(row.getInt(1)).thenReturn(1);
                } else if (sql.contains("cabinet_subscriptions WHERE cabinet_id")
                    && sql.contains("subscription_status = 'TRIAL'"))
                {
                    when(row.getInt(1)).thenReturn(1);
                } else if (sql.contains("pg_catalog.pg_locks") && sql.contains("EXISTS"))
                {
                    when(row.getBoolean(1)).thenReturn(advisoryLockAcquired);
                } else if (sql.startsWith("INSERT INTO") || sql.startsWith("UPDATE"))
                {
                    when(statement.executeUpdate()).thenReturn(1);
                } else if (!sql.startsWith("SELECT pg_catalog.set_config"))
                {
                    throw new AssertionError("Unexpected database operation: " + sql);
                }
                when(statement.executeQuery()).thenReturn(row);
                return statement;
            });
        }

        private void invokeSchemaCreation() throws Exception
        {
            var method = CabinetProvisioningService.class.getDeclaredMethod("createSchemaWithReceipt",
                Connection.class, UUID.class, String.class, UUID.class);
            method.setAccessible(true);
            try
            {
                method.invoke(service, connection, CABINET, SCHEMA, RESERVATION);
            } catch (InvocationTargetException failure)
            {
                if (failure.getCause() instanceof Exception cause)
                {
                    throw cause;
                }
                throw failure;
            }
        }

        private void invoke(boolean completed, UUID account) throws Exception
        {
            completedPath = completed;
            if (completed)
            {
                try (var driver = mockStatic(DriverManager.class))
                {
                    driver.when(() -> DriverManager.getConnection(properties.url(), properties.username(),
                        properties.password())).thenReturn(connection);
                    assertEquals(new ProvisioningResult(CABINET, ProvisioningStatus.ACTIVE),
                        service.provision(REGISTRATION, account));
                }
            } else
            {
                // Direct invocation avoids unrelated DDL/Flyway paths; transaction ownership stays with the caller.
                var method = CabinetProvisioningService.class.getDeclaredMethod("finalizeProvisioning",
                    Connection.class, UUID.class, UUID.class, String.class, UUID.class, UUID.class);
                method.setAccessible(true);
                try
                {
                    method.invoke(service, connection, CABINET, account, SCHEMA, RESERVATION, ATTEMPT);
                } catch (InvocationTargetException failure)
                {
                    if (failure.getCause() instanceof Exception cause)
                    {
                        throw cause;
                    }
                    throw failure;
                }
            }
        }
    }
}
