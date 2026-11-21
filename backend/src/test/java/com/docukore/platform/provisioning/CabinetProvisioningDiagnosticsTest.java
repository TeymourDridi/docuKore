package com.docukore.platform.provisioning;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.docukore.platform.PlatformProperties;
import com.docukore.platform.cabinet.CabinetRegistrationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Database-free provisioning diagnostics checks preserving rejection, connection and lock outcomes. */
class CabinetProvisioningDiagnosticsTest
{
    /** Synthetic identities and configuration never permitted in class-local diagnostic events. */
    private static final String[] SENSITIVE = {
        "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222",
        "33333333-3333-3333-3333-333333333333", "44444444-4444-4444-4444-444444444444",
        "cabinet54321", "jdbc:postgresql://synthetic.invalid/synthetic_provisioning_database",
        "synthetic-provisioning-user", "synthetic-provisioning-password",
        "synthetic-provider-error", "synthetic-provider-cause", "SELECT synthetic_private_column",
        "SYNTHETIC_UNKNOWN_FAILURE_CODE", "SELECT_PRIVATE_DATA", "synthetic_platform_schema"
    };
    /** Fictional original registration identity. */
    private static final UUID REGISTRATION_ID = UUID.fromString(SENSITIVE[0]);
    /** Fictional account identity. */
    private static final UUID ACCOUNT_ID = UUID.fromString(SENSITIVE[1]);
    /** Fictional allocated cabinet identity. */
    private static final UUID CABINET_ID = UUID.fromString(SENSITIVE[2]);
    /** Fictional allocation reservation identity. */
    private static final UUID RESERVATION_ID = UUID.fromString(SENSITIVE[3]);
    /** Backend correlation value allowed only in MDC. */
    private static final String REQUEST_ID = "55555555-5555-5555-5555-555555555555";
    /** Unrelated MDC value that provisioning must preserve. */
    private static final String UNRELATED_CONTEXT = "synthetic-unrelated-context";
    /** Allocation mock preventing database access. */
    private final CabinetRegistrationRepository registrations = mock(CabinetRegistrationRepository.class);
    /** Ownership query mock preventing database access. */
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    /** Real valid properties with fictional connection values. */
    private final CabinetProvisioningProperties properties = properties(Duration.ofSeconds(10));
    /** Service instantiated through its ordinary constructor. */
    private final CabinetProvisioningService service = service(properties);
    /** Only the provisioning service logger is captured. */
    private final Logger logger = (Logger) LoggerFactory.getLogger(CabinetProvisioningService.class);
    /** Eager capture snapshots formatting and MDC on the logging thread. */
    private final SnapshotAppender appender = new SnapshotAppender();
    /** Previous explicit logger level, possibly inherited. */
    private Level previousLevel;
    /** Previous propagation to parent appenders. */
    private boolean previousAdditive;
    /** Previous correlation context restored after each test. */
    private String previousRequestId;
    /** Previous unrelated context restored after each test. */
    private String previousUnrelatedContext;

    @BeforeEach
    void captureDiagnostics()
    {
        previousRequestId = MDC.get("requestId");
        previousUnrelatedContext = MDC.get("diagnosticsTestContext");
        MDC.put("requestId", REQUEST_ID);
        MDC.put("diagnosticsTestContext", UNRELATED_CONTEXT);
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
    }

    @AfterEach
    void restoreDiagnostics()
    {
        try
        {
            assertPrivateEvents();
            assertThat(MDC.get("diagnosticsTestContext")).isEqualTo(UNRELATED_CONTEXT);
        } finally
        {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            restoreMdc("requestId", previousRequestId);
            restoreMdc("diagnosticsTestContext", previousUnrelatedContext);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configurationRejectionLogsSafeWarningBeforeOwnershipOrAllocation(boolean disabled)
    {
        CabinetProvisioningProperties rejected = disabled
            ? new CabinetProvisioningProperties(null, null, null, null, null, null, null)
            : properties(Duration.ofSeconds(61));
        String expectedCode = disabled ? "PROVISIONING_DISABLED" : "PROVISIONING_CONFIGURATION";
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class))
        {
            CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
                () -> service(rejected).provision(REGISTRATION_ID, ACCOUNT_ID));

            assertThat(failure.code()).isEqualTo(expectedCode);
            verifyNoInteractions(jdbc, registrations);
            driver.verifyNoInteractions();
        }
        assertEvents("event=CABINET_PROVISIONING_BEGIN phase=CONFIGURATION",
            "event=CABINET_PROVISIONING_FAILED phase=CONFIGURATION code=" + expectedCode);
        assertWarning("CONFIGURATION", expectedCode);
        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
    }

    @Test
    void ownershipRejectionLogsSafeWarningWithoutAllocationOrConnection()
    {
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(REGISTRATION_ID))).thenReturn(RESERVATION_ID);
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class))
        {
            CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
                () -> service.provision(REGISTRATION_ID, ACCOUNT_ID));

            assertThat(failure.code()).isEqualTo("ACCOUNT_DOES_NOT_OWN_REGISTRATION");
            verifyNoInteractions(registrations);
            driver.verifyNoInteractions();
        }
        assertEvents("event=CABINET_PROVISIONING_BEGIN phase=CONFIGURATION",
            "event=CABINET_PROVISIONING_FAILED phase=OWNERSHIP code=ACCOUNT_DOES_NOT_OWN_REGISTRATION");
        assertWarning("OWNERSHIP", "ACCOUNT_DOES_NOT_OWN_REGISTRATION");
        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
    }

    @Test
    void connectionFailureLogsFixedWarningWithoutProviderDetails() throws Exception
    {
        allowAllocation();
        var cause = new IllegalStateException(SENSITIVE[9]);
        var connectionFailure = new SQLException(SENSITIVE[8] + " " + SENSITIVE[10], cause);
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class))
        {
            driver.when(() -> DriverManager.getConnection(properties.url(), properties.username(),
                properties.password())).thenThrow(connectionFailure);

            CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
                () -> service.provision(REGISTRATION_ID, ACCOUNT_ID));

            assertThat(failure.code()).isEqualTo("PROVISIONING_CONNECTION_FAILED");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            verify(registrations).allocate(REGISTRATION_ID);
            driver.verify(() -> DriverManager.getConnection(properties.url(), properties.username(),
                properties.password()));
            driver.verifyNoMoreInteractions();
        }
        assertEvents("event=CABINET_PROVISIONING_BEGIN phase=CONFIGURATION",
            "event=CABINET_PROVISIONING_PHASE phase=ALLOCATION_RETURNED",
            "event=CABINET_PROVISIONING_FAILED phase=CONNECTION code=PROVISIONING_CONNECTION_FAILED");
        assertWarning("CONNECTION", "PROVISIONING_CONNECTION_FAILED");
        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
    }

    @Test
    void lockContentionLogsDebugAndReturnsInProgressWithoutCommitOrRollback() throws Exception
    {
        allowAllocation();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet lockResult = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(false);
        MDC.remove("requestId");
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class))
        {
            driver.when(() -> DriverManager.getConnection(properties.url(), properties.username(),
                properties.password())).thenReturn(connection);

            ProvisioningResult result = service.provision(REGISTRATION_ID, ACCOUNT_ID);

            assertThat(result).isEqualTo(new ProvisioningResult(CABINET_ID, ProvisioningStatus.IN_PROGRESS));
            verify(connection).setAutoCommit(false);
            verify(connection).close();
            verify(connection, never()).commit();
            verify(connection, never()).rollback();
            driver.verify(() -> DriverManager.getConnection(properties.url(), properties.username(),
                properties.password()));
            driver.verifyNoMoreInteractions();
        }
        assertEvents("event=CABINET_PROVISIONING_BEGIN phase=CONFIGURATION",
            "event=CABINET_PROVISIONING_PHASE phase=ALLOCATION_RETURNED",
            "event=CABINET_PROVISIONING_LOCK_CONTENDED outcome=IN_PROGRESS");
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
        assertThat(MDC.get("requestId")).isNull();
        assertThat(appender.list).allSatisfy(event ->
            assertThat(event.getMDCPropertyMap()).doesNotContainKey("requestId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNTHETIC_UNKNOWN_FAILURE_CODE", "SELECT_PRIVATE_DATA"})
    void arbitraryUppercaseCodeUsesFixedDiagnosticFallbackAndPreservesOriginalFailure(String unknownCode)
    {
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(REGISTRATION_ID))).thenReturn(ACCOUNT_ID);
        var original = new CabinetProvisioningException(unknownCode);
        when(registrations.allocate(REGISTRATION_ID)).thenThrow(original);
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class))
        {
            assertSame(original, assertThrows(CabinetProvisioningException.class,
                () -> service.provision(REGISTRATION_ID, ACCOUNT_ID)));
            assertThat(original.code()).isEqualTo(unknownCode);
            driver.verifyNoInteractions();
        }
        assertEvents("event=CABINET_PROVISIONING_BEGIN phase=CONFIGURATION",
            "event=CABINET_PROVISIONING_FAILED phase=ALLOCATION code=PROVISIONING_FAILED");
        assertWarning("ALLOCATION", "PROVISIONING_FAILED");
        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void claimedAttemptFailureLogsOnlyCommittedMilestonesAndSafeRecoveryOutcome(boolean recoveryFails)
        throws Exception
    {
        allowAllocation();
        Connection lockConnection = mock(Connection.class);
        Connection claimConnection = mock(Connection.class);
        Connection failureConnection = mock(Connection.class);
        ResultSet heldLock = mock(ResultSet.class);
        when(heldLock.next()).thenReturn(true);
        when(heldLock.getBoolean(1)).thenReturn(true);
        ResultSet pending = mock(ResultSet.class);
        when(pending.next()).thenReturn(true);
        when(pending.getString(1)).thenReturn("PENDING");
        when(lockConnection.prepareStatement(anyString())).thenAnswer(invocation ->
        {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT provisioning_status, provisioning_attempt_id"))
            {
                throw new CabinetProvisioningException(SENSITIVE[11]);
            }
            return queryStatement(sql.contains("SELECT provisioning_status FROM") ? pending : heldLock);
        });
        ResultSet account = mock(ResultSet.class);
        when(account.next()).thenReturn(true);
        when(account.getString("account_status")).thenReturn("ACTIVE");
        when(account.getString("deployment_mode")).thenReturn("local");
        ResultSet registration = mock(ResultSet.class);
        when(registration.next()).thenReturn(true, false);
        when(registration.getObject("account_id")).thenReturn(ACCOUNT_ID);
        when(registration.getObject("cabinet_id")).thenReturn(CABINET_ID);
        ResultSet cabinet = mock(ResultSet.class);
        when(cabinet.next()).thenReturn(true);
        when(cabinet.getObject("owner_id")).thenReturn(ACCOUNT_ID);
        when(cabinet.getString("schema_name")).thenReturn(SENSITIVE[4]);
        when(cabinet.getString("provisioning_status")).thenReturn("PENDING");
        when(claimConnection.prepareStatement(anyString())).thenAnswer(invocation ->
        {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("UPDATE"))
            {
                PreparedStatement update = mock(PreparedStatement.class);
                when(update.executeUpdate()).thenReturn(1);
                return update;
            }
            return queryStatement(sql.contains(".users") ? account
                : sql.contains(".initial_cabinet_registrations") ? registration : cabinet);
        });
        when(failureConnection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        if (recoveryFails)
        {
            org.mockito.Mockito.doThrow(new SQLException(SENSITIVE[8])).when(failureConnection).commit();
        }
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class))
        {
            driver.when(() -> DriverManager.getConnection(properties.url(), properties.username(),
                properties.password())).thenReturn(lockConnection, claimConnection, failureConnection);

            CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
                () -> service.provision(REGISTRATION_ID, ACCOUNT_ID));

            assertThat(failure.code()).isEqualTo(SENSITIVE[11]);
            verify(claimConnection).commit();
            verify(lockConnection).rollback();
            verify(lockConnection, never()).commit();
            verify(failureConnection).commit();
            if (recoveryFails)
            {
                verify(failureConnection).rollback();
            }
            verify(lockConnection).close();
            verify(claimConnection).close();
            verify(failureConnection).close();
        }
        assertEvents("event=CABINET_PROVISIONING_BEGIN phase=CONFIGURATION",
            "event=CABINET_PROVISIONING_PHASE phase=ALLOCATION_RETURNED",
            "event=CABINET_PROVISIONING_PHASE phase=CLAIM_COMMITTED",
            "event=CABINET_PROVISIONING_FAILED phase=SCHEMA_RECEIPT code=PROVISIONING_FAILED",
            recoveryFails
                ? "event=CABINET_PROVISIONING_FAILURE_RECORD_FAILED phase=FAILURE_RECORD "
                    + "code=PROVISIONING_DATABASE_ERROR"
                : "event=CABINET_PROVISIONING_FAILURE_RECORD_COMMITTED phase=FAILURE_RECORD");
        assertThat(appender.list.get(2).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(appender.list.get(3).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.getLast().getLevel()).isEqualTo(recoveryFails ? Level.WARN : Level.DEBUG);
        assertThat(appender.list).noneMatch(event -> event.getLevel().equals(Level.INFO));
    }

    private static PreparedStatement queryStatement(ResultSet result) throws SQLException
    {
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.executeQuery()).thenReturn(result);
        return statement;
    }

    private void allowAllocation()
    {
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(REGISTRATION_ID))).thenReturn(ACCOUNT_ID);
        when(registrations.allocate(REGISTRATION_ID)).thenReturn(
            new CabinetRegistrationRepository.Allocation(CABINET_ID, RESERVATION_ID, 54321L, SENSITIVE[4]));
    }

    private CabinetProvisioningService service(CabinetProvisioningProperties configuration)
    {
        return new CabinetProvisioningService(registrations, configuration, jdbc,
            new PlatformProperties("synthetic_platform_schema"));
    }

    private static CabinetProvisioningProperties properties(Duration lockTimeout)
    {
        return new CabinetProvisioningProperties(SENSITIVE[5], SENSITIVE[6], SENSITIVE[7], lockTimeout,
            Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(90));
    }

    private void assertEvents(String... expected)
    {
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(expected);
    }

    private void assertWarning(String phase, String code)
    {
        ILoggingEvent warning = appender.list.getLast();
        assertThat(warning.getLevel()).isEqualTo(Level.WARN);
        assertThat(warning.getFormattedMessage())
            .isEqualTo("event=CABINET_PROVISIONING_FAILED phase=" + phase + " code=" + code);
        assertThat(appender.list.stream().filter(event -> event.getLevel().equals(Level.WARN)).count()).isEqualTo(1);
        assertThat(appender.list.stream().filter(event -> event.getLevel().equals(Level.INFO)).count()).isZero();
    }

    private void assertPrivateEvents()
    {
        for (ILoggingEvent event : appender.list)
        {
            assertThat(event.getLoggerName()).isEqualTo(CabinetProvisioningService.class.getName());
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getMessage()).doesNotContain(SENSITIVE);
            assertThat(event.getFormattedMessage()).doesNotContain(SENSITIVE);
            if (event.getArgumentArray() != null)
            {
                for (Object argument : event.getArgumentArray())
                {
                    assertThat(argument).isInstanceOf(String.class);
                    assertThat((String) argument).doesNotContain(SENSITIVE);
                }
            }
            assertThat(event.getMDCPropertyMap()).containsEntry("diagnosticsTestContext", UNRELATED_CONTEXT);
            event.getMDCPropertyMap().forEach((key, value) ->
            {
                assertThat(key).doesNotContain(SENSITIVE);
                assertThat(value).doesNotContain(SENSITIVE);
            });
            if (event.getMDCPropertyMap().containsKey("requestId"))
            {
                assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo(REQUEST_ID);
            }
        }
    }

    private static void restoreMdc(String key, String previousValue)
    {
        if (previousValue == null)
        {
            MDC.remove(key);
        } else
        {
            MDC.put(key, previousValue);
        }
    }

    /** Snapshots events before request-thread MDC can change. */
    private static final class SnapshotAppender extends ListAppender<ILoggingEvent>
    {
        @Override
        protected void append(ILoggingEvent event)
        {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
