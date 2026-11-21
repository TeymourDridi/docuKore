package com.docukore.support;

import com.docukore.platform.PlatformProperties;
import com.docukore.platform.cabinet.CabinetRegistrationRepository;
import com.docukore.platform.provisioning.CabinetProvisioningException;
import com.docukore.platform.provisioning.CabinetProvisioningProperties;
import com.docukore.platform.provisioning.CabinetProvisioningService;
import com.docukore.platform.provisioning.ProvisioningStatus;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL commit/rollback and session-lock regressions; all namespace evidence is retained. */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("java.sql.DriverManager")
class CabinetProvisioningAttemptTransactionTest
{
    /** Only the calling provisioning thread is observed; pool/Flyway connections remain real and independent. */
    private static final ThreadLocal<Observation> OBSERVATION = new ThreadLocal<>();
    /** Driver registration order restored after this class. */
    private static final List<Driver> ORIGINAL_DRIVERS = new ArrayList<>();
    /** Shared, fail-closed test pool lease. */
    private static HikariDataSource DATA_SOURCE;
    /** Qualified access to the invocation-owned platform. */
    private static JdbcTemplate JDBC;
    /** Actual Spring JDBC transaction boundaries. */
    private static TransactionTemplate TRANSACTIONS;
    /** Actual durable registration allocation repository. */
    private static CabinetRegistrationRepository REPOSITORY;
    /** Public service under test. */
    private static CabinetProvisioningService SERVICE;
    /** Unique, fully migrated platform name. */
    private static String PLATFORM;
    /** Quoted platform identifier. */
    private static String QUOTED_PLATFORM;
    /** Retained ownership receipt; deliberately never closed because close drops schemas. */
    private static OwnedTestDatabase.Scope RESOURCES;
    /** Test-only delegating driver. */
    private static ObservingDriver OBSERVING_DRIVER;

    @BeforeAll
    static void openPlatform() throws Exception
    {
        Assumptions.assumeTrue(configured("docukore.test.jdbc-url", "DOCUKORE_TEST_JDBC_URL")
            && configured("docukore.test.username", "DOCUKORE_TEST_USERNAME")
            && configured("docukore.test.password", "DOCUKORE_TEST_PASSWORD"),
            "Dedicated PostgreSQL test configuration unavailable; no application database fallback");
        DATA_SOURCE = OwnedTestDatabase.acquirePool();
        JDBC = new JdbcTemplate(DATA_SOURCE);
        var manager = new DataSourceTransactionManager(DATA_SOURCE);
        TRANSACTIONS = new TransactionTemplate(manager);
        RESOURCES = OwnedTestDatabase.scope(DATA_SOURCE);
        PLATFORM = RESOURCES.create("attempt_platform");
        QUOTED_PLATFORM = OwnedTestDatabase.identifier(PLATFORM);
        OwnedTestDatabase.requireOwned(PLATFORM);
        Flyway.configure().dataSource(DATA_SOURCE).schemas(PLATFORM).defaultSchema(PLATFORM)
            .locations("classpath:db/migration/platform").target("11").createSchemas(false)
            .cleanDisabled(true).baselineOnMigrate(false).load().migrate();
        assertEquals(11, JDBC.queryForObject("SELECT count(*) FROM " + QUOTED_PLATFORM
            + ".flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class));
        initializeFreshSequence();
        REPOSITORY = new CabinetRegistrationRepository(DATA_SOURCE, manager, PLATFORM);
        SERVICE = new CabinetProvisioningService(REPOSITORY, new CabinetProvisioningProperties(
            OwnedTestDatabase.jdbcUrl(), OwnedTestDatabase.username(), OwnedTestDatabase.password(),
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(45)),
            JDBC, new PlatformProperties(PLATFORM));

        // DriverManager tries drivers in registration order. Never replace PostgreSQL with a JDBC mock.
        ORIGINAL_DRIVERS.addAll(Collections.list(DriverManager.getDrivers()));
        OBSERVING_DRIVER = new ObservingDriver();
        for (Driver driver : ORIGINAL_DRIVERS)
        {
            DriverManager.deregisterDriver(driver);
        }
        DriverManager.registerDriver(OBSERVING_DRIVER);
        for (Driver driver : ORIGINAL_DRIVERS)
        {
            DriverManager.registerDriver(driver);
        }
    }

    @AfterAll
    static void closeConnectionsOnly() throws SQLException
    {
        OBSERVATION.remove();
        try
        {
            if (OBSERVING_DRIVER != null)
            {
                DriverManager.deregisterDriver(OBSERVING_DRIVER);
                for (Driver driver : ORIGINAL_DRIVERS)
                {
                    DriverManager.deregisterDriver(driver);
                }
                for (Driver driver : ORIGINAL_DRIVERS)
                {
                    DriverManager.registerDriver(driver);
                }
            }
        } finally
        {
            if (DATA_SOURCE != null)
            {
                OwnedTestDatabase.releasePool();
                DATA_SOURCE = null;
            }
            // No Scope.close(), schema drops, sequence reuse/reset or permanent evidence mutation.
        }
    }

    @Test
    void committedClaimSurvivesActualSchemaReceiptFailureAndAmbientRollback() throws Exception
    {
        Fixture fixture = new Fixture("local");
        fixture.rejectReceipt();
        try (Observation observation = new Observation(fixture))
        {
            TRANSACTIONS.executeWithoutResult(status ->
            {
                // Allocation is seeded beforehand; the ambient transaction holds no fixture row locks.
                observation.ambientPid = JDBC.queryForObject("SELECT pg_backend_pid()", Integer.class);
                CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
                    () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
                assertEquals("PROVISIONING_DATABASE_ERROR", failure.code());
                assertEquals(new CabinetState("PROVISIONING_FAILED", observation.attemptId,
                    "PROVISIONING_DATABASE_ERROR", "AVAILABLE"), fixture.externalState());
                status.setRollbackOnly();
            });
            assertTrue(observation.ddlStarted, "The failure must follow actual schema DDL, not bypass it");
            assertTrue(observation.receiptStarted,
                "The actual schema must exist on L before the receipt trigger fails");
            assertFalse(observation.ddlCommitted, "Receipt failure must roll back the schema transaction");
            assertEquals(new CabinetState("PROVISIONING_FAILED", observation.attemptId,
                "PROVISIONING_DATABASE_ERROR", "AVAILABLE"), fixture.externalState());
            assertFalse(fixture.namespaceExists());
            assertEquals(0, fixture.count("cabinet_schema_creation_receipts"));
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @Test
    void successfulPublicProvisionKeepsLockAcrossClaimAndRealFlywayAndPreservesSuspension() throws Exception
    {
        Fixture fixture = new Fixture("hosted");
        fixture.setState("PENDING", null, null, "SUSPENDED");
        try (Observation observation = new Observation(fixture))
        {
            var result = SERVICE.provision(fixture.registrationId, fixture.accountId);
            assertEquals(fixture.allocation.cabinetId(), result.cabinetId());
            assertEquals(ProvisioningStatus.ACTIVE, result.status());
            assertTrue(observation.ddlCommitted);
            assertTrue(observation.migrationsObserved,
                "Real tenant history must be committed before finalization while L still holds its lock");
            assertEquals(new CabinetState("ACTIVE", observation.attemptId, null, "SUSPENDED"),
                fixture.externalState());
            assertEquals(1, fixture.count("cabinet_schema_creation_receipts"));
            assertEquals(1, JDBC.queryForObject("SELECT count(*) FROM " + QUOTED_PLATFORM
                + ".cabinet_memberships WHERE cabinet_id = ? AND user_id = ? AND membership_role = 'OWNER'"
                + " AND staff_type = 'DOCTOR' AND status = 'ACTIVE'", Integer.class,
                fixture.allocation.cabinetId(), fixture.accountId));
            assertEquals(1, JDBC.queryForObject("SELECT count(*) FROM " + QUOTED_PLATFORM
                + ".cabinet_subscriptions WHERE cabinet_id = ? AND subscription_status = 'TRIAL'"
                + " AND trial_ends_at - trial_started_at = interval '720 hours'", Integer.class,
                fixture.allocation.cabinetId()));
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCKED", "UNVERIFIED_HOSTED", "INELIGIBLE_DEPLOYMENT"})
    void claimRechecksAccountEligibilityAfterCommittedAllocation(String changedEligibility) throws Exception
    {
        Fixture fixture = new Fixture("hosted");
        CabinetState before = fixture.externalState();
        try (Observation observation = new Observation(fixture))
        {
            observation.changedEligibility = changedEligibility;
            assertThrows(CabinetProvisioningException.class,
                () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
            assertTrue(observation.eligibilityChanged, "The account must change after allocation and before claim C");
            assertFalse(observation.ddlStarted, "Ineligible accounts must not reach schema creation");
            assertEquals(0, observation.claimPid, "An ineligible account must not commit a provisioning claim");
            assertEquals(before, fixture.externalState());
            assertFalse(fixture.namespaceExists());
            assertEquals(0, fixture.count("cabinet_schema_creation_receipts"));
            fixture.assertNoCompletion();
            // Repository replay itself now rejects the account; inspect original immutable evidence directly.
            assertEquals(fixture.evidence, fixture.evidence());
        }
        fixture.assertLockReleased();
    }

    @Test
    void matchingFailureCommitsIndependentlyOfAmbientSpringTransaction() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID attempt = UUID.randomUUID();
        fixture.setState("PROVISIONING", attempt, null, "SUSPENDED");
        TRANSACTIONS.executeWithoutResult(status ->
        {
            JDBC.queryForObject("SELECT pg_backend_pid()", Integer.class);
            recordFailure(fixture, attempt);
            assertEquals(new CabinetState("PROVISIONING_FAILED", attempt, "PROVISIONING_DATABASE_ERROR",
                "SUSPENDED"), fixture.externalState());
            status.setRollbackOnly();
        });
        assertEquals(new CabinetState("PROVISIONING_FAILED", attempt, "PROVISIONING_DATABASE_ERROR", "SUSPENDED"),
            fixture.externalState());
        fixture.assertEvidenceUnchanged();
        fixture.assertNoCompletion();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "ACTIVE", "PROVISIONING_FAILED"})
    void matchingAttemptCannotRecordFailureOutsideCurrentProvisioningStatus(String status) throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID attempt = UUID.randomUUID();
        fixture.setState(status, attempt, "EXISTING_EVIDENCE", "SUSPENDED");
        CabinetState before = fixture.externalState();
        TRANSACTIONS.executeWithoutResult(transaction -> recordFailure(fixture, attempt));
        assertEquals(before, fixture.externalState());
        fixture.assertEvidenceUnchanged();
    }

    @Test
    void staleOlderFailureCannotOverwriteNewerAttempt() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        assertNotEquals(older, newer);
        fixture.setState("PROVISIONING", newer, "NEWER_ATTEMPT_EVIDENCE", "SUSPENDED");
        CabinetState before = fixture.externalState();
        TRANSACTIONS.executeWithoutResult(transaction -> recordFailure(fixture, older));
        assertEquals(before, fixture.externalState());
        fixture.assertNoCompletion();
        fixture.assertEvidenceUnchanged();
    }

    @Test
    void stalePublicFinalizationCannotCreateMembershipOrTrialOrOverwriteNewerAttempt() throws Exception
    {
        Fixture fixture = new Fixture("local");
        fixture.setState("PENDING", null, null, "SUSPENDED");
        UUID newer = UUID.randomUUID();
        try (Observation observation = new Observation(fixture))
        {
            observation.newerAttempt = newer;
            assertThrows(CabinetProvisioningException.class,
                () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
            assertTrue(observation.migrationsObserved);
            assertNotEquals(observation.attemptId, newer);
            assertEquals(new CabinetState("PROVISIONING", newer, "NEWER_ATTEMPT_EVIDENCE", "SUSPENDED"),
                fixture.externalState());
            assertTrue(fixture.namespaceExists());
            assertEquals(1, fixture.count("cabinet_schema_creation_receipts"));
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @Test
    void abandonedProvisioningAttemptIsReplacedOnlyAfterAcquiringItsReleasedLock() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID abandoned = UUID.randomUUID();
        fixture.setState("PROVISIONING", abandoned, "ABANDONED_ATTEMPT_EVIDENCE", "SUSPENDED");
        try (Observation observation = new Observation(fixture))
        {
            var result = SERVICE.provision(fixture.registrationId, fixture.accountId);
            assertEquals(ProvisioningStatus.ACTIVE, result.status());
            assertNotEquals(abandoned, observation.attemptId);
            assertEquals(new CabinetState("ACTIVE", observation.attemptId, null, "SUSPENDED"),
                fixture.externalState());
            assertTrue(observation.ddlCommitted);
            assertTrue(observation.migrationsObserved);
            fixture.assertSingleCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @Test
    void liveWorkerLockPreventsAnyAttemptOrEvidenceModificationUntilItCloses() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID liveAttempt = UUID.randomUUID();
        fixture.setState("PROVISIONING", liveAttempt, "LIVE_ATTEMPT_EVIDENCE", "SUSPENDED");
        CabinetState before = fixture.externalState();
        try (Connection holder = fixture.holdAdvisoryLock(); Observation observation = new Observation(fixture))
        {
            var result = SERVICE.provision(fixture.registrationId, fixture.accountId);
            assertEquals(ProvisioningStatus.IN_PROGRESS, result.status());
            assertEquals(before, fixture.externalState());
            assertFalse(observation.ddlStarted);
            assertEquals(0, observation.claimPid);
            assertFalse(fixture.namespaceExists());
            assertEquals(0, fixture.count("cabinet_schema_creation_receipts"));
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
            fixture.assertAdvisoryLock(true);
        }
        fixture.assertLockReleased();
        var recovered = SERVICE.provision(fixture.registrationId, fixture.accountId);
        assertEquals(ProvisioningStatus.ACTIVE, recovered.status());
        assertNotEquals(liveAttempt, fixture.externalState().attempt());
        fixture.assertSingleCompletion();
        fixture.assertEvidenceUnchanged();
        fixture.assertLockReleased();
    }

    @Test
    void physicalLockLostBeforeClaimCannotOverwriteTheSuccessorAttempt() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID successor = UUID.randomUUID();
        fixture.setState("PROVISIONING", UUID.randomUUID(), "ABANDONED_ATTEMPT_EVIDENCE", "SUSPENDED");
        Connection[] successorHolder = new Connection[1];
        try (Observation observation = new Observation(fixture))
        {
            observation.at(Phase.BEFORE_CLAIM_LOCKS, session ->
            {
                Session lockSession = observation.sessions.stream()
                    .filter(candidate -> candidate.pid == observation.lockPid)
                    .findFirst().orElseThrow(() -> new AssertionError("Real L session was not observed"));
                fixture.unlock(lockSession.connection);
                fixture.setState("PROVISIONING", successor, "SUCCESSOR_ATTEMPT_EVIDENCE", "SUSPENDED");
                successorHolder[0] = fixture.holdAdvisoryLock();
            });
            CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
                () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
            assertEquals("PROVISIONING_LOCK_LOST", failure.code());
            assertTrue(observation.reached(Phase.BEFORE_CLAIM_LOCKS));
            assertEquals(0, observation.claimPid, "L must remain physically owned before C writes an attempt claim");
            assertFalse(observation.ddlStarted);
            assertFalse(fixture.namespaceExists());
            assertEquals(0, fixture.count("cabinet_schema_creation_receipts"));
            assertEquals(new CabinetState("PROVISIONING", successor, "SUCCESSOR_ATTEMPT_EVIDENCE", "SUSPENDED"),
                fixture.externalState());
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
            fixture.assertAdvisoryLock(true);
        } finally
        {
            if (successorHolder[0] != null)
            {
                successorHolder[0].close();
            }
        }
        fixture.assertLockReleased();
    }

    @Test
    void actualReceiptFailureCanRetryWithoutReallocatingOrResettingEvidence() throws Exception
    {
        Fixture fixture = new Fixture("local");
        fixture.rejectReceipt();
        CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
            () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
        assertEquals("PROVISIONING_DATABASE_ERROR", failure.code());
        CabinetState failed = fixture.externalState();
        assertEquals("PROVISIONING_FAILED", failed.status());
        assertFalse(fixture.namespaceExists());
        assertEquals(0, fixture.count("cabinet_schema_creation_receipts"));
        fixture.assertNoCompletion();
        fixture.assertLockReleased();
        fixture.allowReceipt();
        try (Observation observation = new Observation(fixture))
        {
            var recovered = SERVICE.provision(fixture.registrationId, fixture.accountId);
            assertEquals(ProvisioningStatus.ACTIVE, recovered.status());
            assertNotEquals(failed.attempt(), observation.attemptId);
            assertTrue(observation.ddlCommitted);
            assertTrue(observation.migrationsObserved);
            assertEquals(1, fixture.count("cabinet_schema_creation_receipts"));
            fixture.assertSingleCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @Test
    void abandonedAttemptReusesCommittedSchemaReceiptAndTenantHistory() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID interrupted = UUID.randomUUID();
        try (Observation observation = new Observation(fixture))
        {
            observation.at(Phase.BEFORE_FINALIZER,
                session -> fixture.setState("PROVISIONING", interrupted, "INTERRUPTED_FINALIZER", "SUSPENDED"));
            assertThrows(CabinetProvisioningException.class,
                () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
            assertTrue(observation.migrationsObserved);
        }
        fixture.assertLockReleased();
        var receipt = fixture.rows("cabinet_schema_creation_receipts");
        var history = fixture.tenantHistory();
        long namespace = fixture.namespaceOid();
        fixture.assertNoCompletion();
        try (Observation observation = new Observation(fixture))
        {
            var recovered = SERVICE.provision(fixture.registrationId, fixture.accountId);
            assertEquals(ProvisioningStatus.ACTIVE, recovered.status());
            assertNotEquals(interrupted, observation.attemptId);
            assertFalse(observation.ddlStarted, "A receipted schema must never be recreated");
            assertEquals(namespace, fixture.namespaceOid());
            assertEquals(receipt, fixture.rows("cabinet_schema_creation_receipts"));
            assertEquals(history, fixture.tenantHistory());
            assertEquals(new CabinetState("ACTIVE", observation.attemptId, null, "SUSPENDED"),
                fixture.externalState());
            fixture.assertSingleCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @Test
    void activeReplayPreservesSuspensionAndEveryCompletionRow() throws Exception
    {
        Fixture fixture = new Fixture("local");
        SERVICE.provision(fixture.registrationId, fixture.accountId);
        CabinetState active = fixture.externalState();
        fixture.setState("ACTIVE", active.attempt(), active.error(), "SUSPENDED");
        active = fixture.externalState();
        var receipt = fixture.rows("cabinet_schema_creation_receipts");
        var memberships = fixture.rows("cabinet_memberships");
        var subscriptions = fixture.rows("cabinet_subscriptions");
        var history = fixture.tenantHistory();
        long namespace = fixture.namespaceOid();
        try (Observation observation = new Observation(fixture))
        {
            var replay = SERVICE.provision(fixture.registrationId, fixture.accountId);
            assertEquals(ProvisioningStatus.ACTIVE, replay.status());
            assertEquals(active, fixture.externalState());
            assertEquals(0, observation.claimPid);
            assertFalse(observation.ddlStarted);
            assertEquals(namespace, fixture.namespaceOid());
            assertEquals(receipt, fixture.rows("cabinet_schema_creation_receipts"));
            assertEquals(memberships, fixture.rows("cabinet_memberships"));
            assertEquals(subscriptions, fixture.rows("cabinet_subscriptions"));
            assertEquals(history, fixture.tenantHistory());
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_SCHEMA", "MISSING_RECEIPT"})
    void activeReplayFailsClosedOnMissingSchemaOrOwnershipReceipt(String inconsistency) throws Exception
    {
        Fixture fixture = new Fixture("local");
        if ("MISSING_RECEIPT".equals(inconsistency))
        {
            fixture.createUnreceiptedSchema();
        }
        UUID attempt = UUID.randomUUID();
        fixture.setState("ACTIVE", attempt, "INCONSISTENT_ACTIVE_EVIDENCE", "SUSPENDED");
        CabinetState before = fixture.externalState();
        try (Observation observation = new Observation(fixture))
        {
            assertThrows(CabinetProvisioningException.class,
                () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
            assertEquals(before, fixture.externalState());
            assertFalse(observation.ddlStarted);
            assertEquals(0, observation.claimPid);
            assertEquals("MISSING_RECEIPT".equals(inconsistency), fixture.namespaceExists());
            assertEquals(0, fixture.count("cabinet_schema_creation_receipts"));
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @Test
    void workerCompletedBetweenAllocationAndLockAcquisitionReturnsActiveWithoutAnotherClaim() throws Exception
    {
        Fixture fixture = new Fixture("local");
        try (Observation observation = new Observation(fixture))
        {
            observation.at(Phase.BEFORE_LOCK, session ->
            {
                SERVICE.provision(fixture.registrationId, fixture.accountId);
                fixture.setState("ACTIVE", fixture.externalState().attempt(), null, "SUSPENDED");
            });
            var result = SERVICE.provision(fixture.registrationId, fixture.accountId);
            assertEquals(ProvisioningStatus.ACTIVE, result.status());
            assertTrue(observation.reached(Phase.BEFORE_LOCK));
            assertEquals(0, observation.claimPid, "The waiting caller must not claim a completed cabinet again");
            assertFalse(observation.ddlStarted);
            assertEquals("SUSPENDED", fixture.externalState().administrativeStatus());
            fixture.assertSingleCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"AFTER_CLAIM", "AFTER_RECEIPT", "AFTER_DDL_COMMIT",
        "AFTER_FLYWAY", "BEFORE_FINALIZER"})
    void stalePublicWorkerIsFencedAtEveryDurableBoundary(Phase phase) throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID newer = UUID.randomUUID();
        try (Observation observation = new Observation(fixture))
        {
            observation.at(phase,
                session -> fixture.setState("PROVISIONING", newer, "NEWER_ATTEMPT_EVIDENCE", "SUSPENDED"));
            CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
                () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
            assertEquals("PROVISIONING_ATTEMPT_MISMATCH", failure.code());
            assertTrue(observation.reached(phase), "The intended real database boundary must execute");
            assertNotEquals(newer, observation.attemptId);
            assertEquals(new CabinetState("PROVISIONING", newer, "NEWER_ATTEMPT_EVIDENCE", "SUSPENDED"),
                fixture.externalState());
            boolean schemaCommitted = phase == Phase.AFTER_DDL_COMMIT || phase == Phase.AFTER_FLYWAY
                || phase == Phase.BEFORE_FINALIZER;
            assertEquals(schemaCommitted, fixture.namespaceExists());
            assertEquals(schemaCommitted ? 1 : 0, fixture.count("cabinet_schema_creation_receipts"));
            assertEquals(phase == Phase.AFTER_FLYWAY || phase == Phase.BEFORE_FINALIZER, fixture.hasTenantHistory());
            if (phase == Phase.AFTER_CLAIM)
            {
                assertFalse(observation.ddlStarted);
            }
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @Test
    void physicalLockLossAfterRealFlywayCannotFinalizeOrOverwriteReplacementFailureEvidence() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID newer = UUID.randomUUID();
        try (Observation observation = new Observation(fixture))
        {
            observation.at(Phase.AFTER_FLYWAY, session ->
            {
                fixture.unlock(session.connection);
                fixture.setState("PROVISIONING", newer, "NEWER_ATTEMPT_EVIDENCE", "SUSPENDED");
            });
            CabinetProvisioningException failure = assertThrows(CabinetProvisioningException.class,
                () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
            assertEquals("PROVISIONING_LOCK_LOST", failure.code());
            assertTrue(observation.reached(Phase.AFTER_FLYWAY));
            assertTrue(fixture.hasTenantHistory());
            assertEquals(new CabinetState("PROVISIONING", newer, "NEWER_ATTEMPT_EVIDENCE", "SUSPENDED"),
                fixture.externalState());
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    @Test
    void ownershipGuardRequiresExactSessionLockAndAttemptWithoutReentrantAcquisition() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID attempt = UUID.randomUUID();
        fixture.setState("PROVISIONING", attempt, null, "SUSPENDED");
        try (Connection holder = fixture.holdAdvisoryLock(); Connection stranger = observerConnection())
        {
            requireAttemptOwnership(holder, fixture, attempt);
            requireAttemptOwnership(holder, fixture, attempt);
            assertEquals("PROVISIONING_LOCK_LOST", assertThrows(CabinetProvisioningException.class,
                () -> requireAttemptOwnership(stranger, fixture, attempt)).code());
            assertEquals("PROVISIONING_ATTEMPT_MISMATCH", assertThrows(CabinetProvisioningException.class,
                () -> requireAttemptOwnership(holder, fixture, UUID.randomUUID())).code());
            fixture.unlock(holder);
            fixture.assertLockReleased();
            assertEquals("PROVISIONING_LOCK_LOST", assertThrows(CabinetProvisioningException.class,
                () -> requireAttemptOwnership(holder, fixture, attempt)).code());
            assertEquals(new CabinetState("PROVISIONING", attempt, null, "SUSPENDED"), fixture.externalState());
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
        }
    }

    @Test
    void finalizerWithCurrentTokenButNoPhysicalLockCannotCreateCompletionRows() throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID current = UUID.randomUUID();
        try (Observation observation = new Observation(fixture))
        {
            observation.at(Phase.BEFORE_FINALIZER,
                session -> fixture.setState("PROVISIONING", current, "CURRENT_ATTEMPT_EVIDENCE", "SUSPENDED"));
            assertThrows(CabinetProvisioningException.class,
                () -> SERVICE.provision(fixture.registrationId, fixture.accountId));
            assertTrue(observation.migrationsObserved);
        }
        fixture.assertLockReleased();
        CabinetState before = fixture.externalState();
        try (Connection connection = observerConnection())
        {
            connection.setAutoCommit(false);
            try
            {
                assertEquals("PROVISIONING_LOCK_LOST", assertThrows(CabinetProvisioningException.class,
                    () -> finalizeProvisioning(connection, fixture, current)).code());
            } finally
            {
                connection.rollback();
            }
        }
        assertEquals(before, fixture.externalState());
        fixture.assertNoCompletion();
        fixture.assertEvidenceUnchanged();
        fixture.assertLockReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "ACTIVE", "PROVISIONING_FAILED"})
    void ownershipGuardRejectsMatchingTokenOutsideProvisioningStatus(String status) throws Exception
    {
        Fixture fixture = new Fixture("local");
        UUID attempt = UUID.randomUUID();
        fixture.setState(status, attempt, "UNCHANGED_EVIDENCE", "SUSPENDED");
        CabinetState before = fixture.externalState();
        try (Connection holder = fixture.holdAdvisoryLock())
        {
            assertEquals("PROVISIONING_ATTEMPT_MISMATCH", assertThrows(CabinetProvisioningException.class,
                () -> requireAttemptOwnership(holder, fixture, attempt)).code());
            assertEquals(before, fixture.externalState());
            fixture.assertNoCompletion();
            fixture.assertEvidenceUnchanged();
        }
        fixture.assertLockReleased();
    }

    private static void finalizeProvisioning(Connection connection, Fixture fixture, UUID attempt) throws Exception
    {
        try
        {
            Method method = CabinetProvisioningService.class.getDeclaredMethod("finalizeProvisioning",
                Connection.class, UUID.class, UUID.class, String.class, UUID.class, UUID.class);
            method.setAccessible(true);
            method.invoke(SERVICE, connection, fixture.allocation.cabinetId(), fixture.accountId,
                fixture.allocation.schemaName(), fixture.allocation.reservationId(), attempt);
        } catch (InvocationTargetException failure)
        {
            if (failure.getCause() instanceof Exception cause)
            {
                throw cause;
            }
            throw new AssertionError("Provisioning finalizer failed", failure.getCause());
        } catch (ReflectiveOperationException failure)
        {
            throw new AssertionError("Cannot invoke provisioning finalizer", failure);
        }
    }

    private static void requireAttemptOwnership(Connection connection, Fixture fixture, UUID attempt) throws Exception
    {
        try
        {
            Method method = CabinetProvisioningService.class.getDeclaredMethod(
                "requireAttemptOwnership", Connection.class, UUID.class, UUID.class);
            method.setAccessible(true);
            method.invoke(SERVICE, connection, fixture.allocation.cabinetId(), attempt);
        } catch (InvocationTargetException failure)
        {
            if (failure.getCause() instanceof Exception cause)
            {
                throw cause;
            }
            throw new AssertionError("Attempt ownership guard failed", failure.getCause());
        } catch (ReflectiveOperationException failure)
        {
            throw new AssertionError("Cannot invoke attempt ownership guard", failure);
        }
    }

    private static void recordFailure(Fixture fixture, UUID attempt)
    {
        // Keep this test-first batch compilable against the old service, without depending on a new claim helper.
        try
        {
            Method method = CabinetProvisioningService.class.getDeclaredMethod(
                "recordProvisioningFailure", UUID.class, UUID.class, Exception.class);
            method.setAccessible(true);
            method.invoke(SERVICE, fixture.allocation.cabinetId(), attempt,
                new SQLException("Fictional private fixture detail must never become persisted error text", "P0001"));
        } catch (ReflectiveOperationException failure)
        {
            throw new AssertionError("Cannot invoke existing failure-recording boundary", failure);
        }
    }

    private static boolean configured(String property, String environment)
    {
        String value = System.getProperty(property);
        if (value == null || value.isBlank())
        {
            value = System.getenv(environment);
        }
        return value != null && !value.isBlank();
    }

    private static void initializeFreshSequence()
    {
        assertEquals(0, JDBC.queryForObject("SELECT count(*) FROM " + QUOTED_PLATFORM
            + ".cabinet_schema_allocations", Integer.class));
        assertEquals(Boolean.FALSE, JDBC.queryForObject("SELECT is_called FROM " + QUOTED_PLATFORM
            + ".cabinet_schema_number_seq", Boolean.class));
        long first;
        do
        {
            first = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 8_000_000_000_000L);
        } while (JDBC.queryForObject("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname IN "
            + "(SELECT 'cabinet' || number::text FROM generate_series(?, ?) AS number)",
            Integer.class, first, first + 1_000) != 0);
        // Only this newly created platform's never-used sequence is initialized, never an existing sequence.
        JDBC.queryForObject("SELECT pg_catalog.setval(CAST(? AS pg_catalog.regclass), ?, false)", Long.class,
            QUOTED_PLATFORM + ".cabinet_schema_number_seq", first);
    }

    private static Connection observerConnection() throws SQLException
    {
        Observation observation = OBSERVATION.get();
        OBSERVATION.remove();
        try
        {
            return OwnedTestDatabase.connection();
        } finally
        {
            if (observation != null)
            {
                OBSERVATION.set(observation);
            }
        }
    }

    private static int backendPid(Connection connection) throws SQLException
    {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_backend_pid()"))
        {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable
    {
        try
        {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure)
        {
            throw failure.getCause();
        }
    }

    /**
     * Complete externally visible attempt state, including fields stale operations must preserve.
     *
     * @param status current provisioning state
     * @param attempt recorded attempt identity
     * @param error normalized failure evidence
     * @param administrativeStatus independent administrative availability
     */
    private record CabinetState(String status, UUID attempt, String error, String administrativeStatus)
    {
    }

    /** Fictional active account and its original registration linked by the real allocation repository. */
    private static final class Fixture
    {
        /** Original account identity. */
        private final UUID accountId = UUID.randomUUID();
        /** Original registration identity. */
        private final UUID registrationId = UUID.randomUUID();
        /** Durable allocation, committed before any ambient provisioning transaction. */
        private final CabinetRegistrationRepository.Allocation allocation;
        /** Immutable registration/allocation rows captured before provisioning. */
        private final List<java.util.Map<String, Object>> evidence;
        /** Permanent fixture-owned gate used to retry receipt errors without dropping the trigger. */
        private String receiptGate;

        private Fixture(String deploymentMode)
        {
            String username = "attempt_" + accountId.toString().replace("-", "");
            String email = "hosted".equals(deploymentMode) ? username + "@example.invalid" : null;
            JDBC.update("INSERT INTO " + QUOTED_PLATFORM + ".users "
                + "(id, username, username_normalized, email, email_normalized, password_hash, full_name,"
                + " deployment_mode, created_at, activated_at, account_status, email_verified_at)"
                + " VALUES (?, ?, ?, ?, ?, 'fictional-not-a-login-hash', 'Fictional Doctor', ?,"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE', CURRENT_TIMESTAMP)",
                accountId, username, username, email, email, deploymentMode);
            JDBC.update("INSERT INTO " + QUOTED_PLATFORM
                + ".initial_cabinet_registrations (id, account_id, idempotency_key, cabinet_name) VALUES (?, ?, ?, ?)",
                registrationId, accountId, UUID.randomUUID(), "Fictional Attempt Cabinet " + registrationId);
            allocation = REPOSITORY.allocate(registrationId);
            assertNotEquals(registrationId, allocation.cabinetId());
            assertFalse(namespaceExists());
            RESOURCES.authorizeAllocation(PLATFORM, allocation.schemaName(), allocation.reservationId(),
                allocation.cabinetId());
            evidence = evidence();
        }

        private List<java.util.Map<String, Object>> evidence()
        {
            var rows = new ArrayList<>(JDBC.queryForList("SELECT * FROM " + QUOTED_PLATFORM
                + ".initial_cabinet_registrations WHERE id = ?", registrationId));
            rows.addAll(JDBC.queryForList("SELECT * FROM " + QUOTED_PLATFORM
                + ".cabinet_schema_allocations WHERE cabinet_id = ?", allocation.cabinetId()));
            return rows;
        }

        private void assertEvidenceUnchanged()
        {
            assertEquals(evidence, evidence());
            assertEquals(allocation, REPOSITORY.allocate(registrationId), "Retry must reuse the original reservation");
        }

        private void setState(String status, UUID attempt, String error, String administrativeStatus)
        {
            assertEquals(1, JDBC.update("UPDATE " + QUOTED_PLATFORM
                + ".cabinets SET provisioning_status = ?, provisioning_attempt_id = ?, last_provisioning_error = ?,"
                + " administrative_status = ? WHERE id = ?", status, attempt, error, administrativeStatus,
                allocation.cabinetId()));
        }

        private CabinetState externalState()
        {
            // DriverManager observer callbacks disable observation while these independent real sessions run.
            try (Connection connection = observerConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT provisioning_status,"
                     + " provisioning_attempt_id, last_provisioning_error, administrative_status FROM "
                     + QUOTED_PLATFORM + ".cabinets WHERE id = ?"))
            {
                statement.setObject(1, allocation.cabinetId());
                try (ResultSet result = statement.executeQuery())
                {
                    assertTrue(result.next());
                    return new CabinetState(result.getString(1), result.getObject(2, UUID.class),
                        result.getString(3), result.getString(4));
                }
            } catch (SQLException failure)
            {
                throw new AssertionError("External cabinet observation failed", failure);
            }
        }

        private boolean namespaceExists()
        {
            return Boolean.TRUE.equals(JDBC.queryForObject("SELECT EXISTS "
                + "(SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)", Boolean.class,
                allocation.schemaName()));
        }

        private int count(String table)
        {
            return JDBC.queryForObject("SELECT count(*) FROM " + QUOTED_PLATFORM + "." + table
                + " WHERE cabinet_id = ?", Integer.class, allocation.cabinetId());
        }

        private void assertNoCompletion()
        {
            assertEquals(0, count("cabinet_memberships"));
            assertEquals(0, count("cabinet_subscriptions"));
        }

        private List<Map<String, Object>> rows(String table)
        {
            return JDBC.queryForList("SELECT * FROM " + QUOTED_PLATFORM + "." + table
                + " WHERE cabinet_id = ?", allocation.cabinetId());
        }

        private long namespaceOid()
        {
            return JDBC.queryForObject("SELECT oid::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?",
                Long.class, allocation.schemaName());
        }

        private boolean hasTenantHistory()
        {
            return Boolean.TRUE.equals(JDBC.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                OwnedTestDatabase.identifier(allocation.schemaName()) + ".flyway_schema_history_tenant"));
        }

        private List<Map<String, Object>> tenantHistory()
        {
            return JDBC.queryForList("SELECT * FROM " + OwnedTestDatabase.identifier(allocation.schemaName())
                + ".flyway_schema_history_tenant ORDER BY installed_rank");
        }

        private void assertSingleCompletion()
        {
            assertEquals(1, count("cabinet_memberships"));
            assertEquals(1, count("cabinet_subscriptions"));
        }

        private void createUnreceiptedSchema()
        {
            assertFalse(namespaceExists(), "Only this fixture's authorized, absent allocation may be created");
            JDBC.execute("CREATE SCHEMA " + OwnedTestDatabase.identifier(allocation.schemaName()));
        }

        private Connection holdAdvisoryLock() throws SQLException
        {
            Connection connection = observerConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)"))
            {
                statement.setLong(1, allocation.cabinetId().getMostSignificantBits());
                try (ResultSet result = statement.executeQuery())
                {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1), "Fresh fixture lock must be available");
                }
                return connection;
            } catch (SQLException | RuntimeException | Error failure)
            {
                connection.close();
                throw failure;
            }
        }

        private void unlock(Connection connection) throws SQLException
        {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)"))
            {
                statement.setLong(1, allocation.cabinetId().getMostSignificantBits());
                try (ResultSet result = statement.executeQuery())
                {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1), "The observed real L session must own the lock being released");
                }
            }
        }

        private void rejectReceipt()
        {
            String suffix = allocation.cabinetId().toString().replace("-", "");
            receiptGate = QUOTED_PLATFORM + ".attempt_receipt_gate_" + suffix;
            JDBC.execute("CREATE TABLE " + receiptGate + " (reject_receipt boolean NOT NULL)");
            JDBC.update("INSERT INTO " + receiptGate + " (reject_receipt) VALUES (true)");
            String function = QUOTED_PLATFORM + ".reject_attempt_receipt_" + suffix;
            JDBC.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql "
                + "SET search_path = pg_catalog AS $$ BEGIN IF (SELECT reject_receipt FROM " + receiptGate
                + ") THEN RAISE EXCEPTION 'Fictional receipt failure' USING ERRCODE = 'P0001'; END IF;"
                + " RETURN NEW; END; $$");
            JDBC.execute("CREATE TRIGGER reject_attempt_receipt_" + suffix
                + " BEFORE INSERT ON " + QUOTED_PLATFORM + ".cabinet_schema_creation_receipts FOR EACH ROW"
                + " WHEN (NEW.cabinet_id = '" + allocation.cabinetId()
                + "'::uuid) EXECUTE FUNCTION " + function + "()");
        }

        private void allowReceipt()
        {
            assertNotNull(receiptGate);
            assertEquals(1, JDBC.update("UPDATE " + receiptGate + " SET reject_receipt = false"));
        }

        private void assertAdvisoryLock(boolean expected) throws SQLException
        {
            try (Connection connection = observerConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)"))
            {
                statement.setLong(1, allocation.cabinetId().getMostSignificantBits());
                try (ResultSet result = statement.executeQuery())
                {
                    assertTrue(result.next());
                    // The observer owns no lock on false; a successful probe is released by session close.
                    assertEquals(!expected, result.getBoolean(1), "Original L must retain its session advisory lock");
                }
            }
        }

        private void assertLockReleased() throws SQLException
        {
            assertAdvisoryLock(false);
        }
    }

    /** Deterministic boundaries around real SQL execution, never mocked PostgreSQL behavior. */
    private enum Phase
    {
        /** Before the original session attempts to acquire the advisory lock. */
        BEFORE_LOCK,
        /** Before the independent claim transaction locks the account row. */
        BEFORE_CLAIM_LOCKS,
        /** After the provisioning claim commits independently of schema creation. */
        AFTER_CLAIM,
        /** After the creation receipt is inserted, before the schema transaction commits. */
        AFTER_RECEIPT,
        /** After schema creation and its receipt commit together. */
        AFTER_DDL_COMMIT,
        /** After committed tenant migration history is observed. */
        AFTER_FLYWAY,
        /** Before the finalization transaction locks the account row. */
        BEFORE_FINALIZER
    }

    @FunctionalInterface
    private interface BoundaryAction
    {
        void run(Session session) throws SQLException;
    }

    /** External observations and one-shot fixture mutations; no production hooks or synthetic SQL results. */
    private static final class Observation implements AutoCloseable
    {
        /** Current synthetic registration/allocation. */
        private final Fixture fixture;
        /** All observed production direct connections, still backed by org.postgresql.Driver. */
        private final List<Session> sessions = new ArrayList<>();
        /** Suppresses wrapping observer connections opened on this same thread. */
        private boolean inCallback;
        /** Original advisory-lock connection backend PID. */
        private int lockPid;
        /** Separately committed claim connection backend PID. */
        private int claimPid;
        /** Optional ambient Spring-bound connection backend PID. */
        private int ambientPid;
        /** Attempt visible from outside the claim transaction before DDL. */
        private UUID attemptId;
        /** Whether actual CREATE SCHEMA execution has begun. */
        private boolean ddlStarted;
        /** Whether actual schema creation succeeded on L before receipt INSERT execution. */
        private boolean receiptStarted;
        /** Whether schema and receipt committed together. */
        private boolean ddlCommitted;
        /** Whether committed real Flyway history was observed before finalization. */
        private boolean migrationsObserved;
        /** Optional independently committed replacement attempt at the finalization boundary. */
        private UUID newerAttempt;
        /** Optional account eligibility change after allocation and before the claim transaction. */
        private String changedEligibility;
        /** Whether the independent eligibility change was committed. */
        private boolean eligibilityChanged;
        /** Optional fixture changes executed at most once at a selected real boundary. */
        private final Map<Phase, BoundaryAction> actions = new EnumMap<>(Phase.class);
        /** Boundaries already observed, ensuring each callback executes at most once. */
        private final EnumSet<Phase> reached = EnumSet.noneOf(Phase.class);
        /** Reused schemas have no DDL boundary and may already have tenant history before this invocation. */
        private final boolean schemaPresentAtStart;
        /** Whether committed tenant migration history existed before this provisioning invocation. */
        private final boolean historyPresentAtStart;

        private Observation(Fixture fixture)
        {
            this.fixture = fixture;
            assertEquals(null, OBSERVATION.get());
            schemaPresentAtStart = fixture.namespaceExists();
            historyPresentAtStart = fixture.hasTenantHistory();
            OBSERVATION.set(this);
        }

        private void at(Phase phase, BoundaryAction action)
        {
            assertEquals(null, actions.put(phase, action), "Each fixture boundary has one owner");
        }

        private boolean reached(Phase phase)
        {
            return reached.contains(phase);
        }

        private void fire(Phase phase, Session session) throws SQLException
        {
            if (reached.add(phase))
            {
                BoundaryAction action = actions.get(phase);
                if (action != null)
                {
                    action.run(session);
                }
            }
        }

        private void beforeSql(Session session, String sql) throws SQLException
        {
            String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
            if (normalized.contains("PG_TRY_ADVISORY_LOCK("))
            {
                lockPid = session.pid;
                fire(Phase.BEFORE_LOCK, session);
                if (changedEligibility != null && !eligibilityChanged)
                {
                    String assignment = switch (changedEligibility)
                    {
                        case "LOCKED" -> "account_status = 'LOCKED'";
                        case "UNVERIFIED_HOSTED" -> "email_verified_at = NULL";
                        case "INELIGIBLE_DEPLOYMENT" -> "deployment_mode = NULL";
                        default -> throw new AssertionError("Unknown fixture eligibility change");
                    };
                    assertEquals(1, JDBC.update("UPDATE " + QUOTED_PLATFORM + ".users SET " + assignment
                        + " WHERE id = ?", fixture.accountId));
                    eligibilityChanged = true;
                }
            }
            if (lockPid != 0 && session.pid != lockPid && claimPid == 0
                && normalized.contains(".USERS") && normalized.contains("FOR UPDATE"))
            {
                fire(Phase.BEFORE_CLAIM_LOCKS, session);
            }
            if (normalized.startsWith("CREATE SCHEMA "))
            {
                assertFalse(ddlStarted);
                CabinetState state = fixture.externalState();
                assertEquals("PROVISIONING", state.status(), "Claim must be visible before schema creation begins");
                assertNotNull(state.attempt());
                assertEquals(attemptId, state.attempt());
                assertTrue(claimPid > 0, "Claim must already have committed on independently observed connection C");
                assertEquals(lockPid, session.pid);
                assertNotEquals(lockPid, claimPid, "C must not be the lock-owning L connection");
                if (ambientPid != 0)
                {
                    assertNotEquals(ambientPid, claimPid, "C must not be the ambient Spring connection");
                    assertNotEquals(ambientPid, lockPid);
                }
                assertFalse(fixture.namespaceExists(), "Observer runs before actual CREATE SCHEMA execution");
                fixture.assertAdvisoryLock(true);
                ddlStarted = true;
            }
            if (ddlStarted && session.pid == lockPid && normalized.startsWith("INSERT INTO ")
                && normalized.contains(".CABINET_SCHEMA_CREATION_RECEIPTS"))
            {
                try (PreparedStatement statement = session.connection.prepareStatement("SELECT EXISTS "
                    + "(SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)"))
                {
                    statement.setString(1, fixture.allocation.schemaName());
                    try (ResultSet result = statement.executeQuery())
                    {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1), "Real CREATE SCHEMA must precede actual receipt INSERT");
                    }
                }
                receiptStarted = true;
            }
            if (attemptId != null && session.pid == lockPid && !migrationsObserved
                && (ddlCommitted || schemaPresentAtStart) && fixture.hasTenantHistory()
                && (!historyPresentAtStart || (normalized.contains(".USERS") && normalized.contains("FOR UPDATE"))))
            {
                // A fresh tenant's history can only become visible here after real Flyway has returned.
                assertTrue(JDBC.queryForObject("SELECT count(*) FROM "
                    + OwnedTestDatabase.identifier(fixture.allocation.schemaName())
                    + ".flyway_schema_history_tenant WHERE success AND version IS NOT NULL", Integer.class) > 0);
                fixture.assertAdvisoryLock(true);
                assertEquals(lockPid, backendPid(session.connection));
                migrationsObserved = true;
                fire(Phase.AFTER_FLYWAY, session);
            }
            if (attemptId != null && session.pid == lockPid
                && normalized.contains(".USERS") && normalized.contains("FOR UPDATE"))
            {
                fire(Phase.BEFORE_FINALIZER, session);
                if (newerAttempt != null)
                {
                    fixture.setState("PROVISIONING", newerAttempt, "NEWER_ATTEMPT_EVIDENCE", "SUSPENDED");
                }
            }
        }

        private void afterSql(Session session, String sql) throws SQLException
        {
            String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
            if (session.pid == lockPid && ddlStarted && normalized.startsWith("INSERT INTO ")
                && normalized.contains(".CABINET_SCHEMA_CREATION_RECEIPTS"))
            {
                fire(Phase.AFTER_RECEIPT, session);
            }
        }

        private void afterCommit(Session session) throws SQLException
        {
            if (lockPid != 0 && session.pid != lockPid && claimPid == 0)
            {
                CabinetState state = fixture.externalState();
                if ("PROVISIONING".equals(state.status()))
                {
                    claimPid = session.pid;
                    attemptId = state.attempt();
                    fixture.assertAdvisoryLock(true);
                    fire(Phase.AFTER_CLAIM, session);
                }
            } else if (ddlStarted && session.pid == lockPid && !ddlCommitted)
            {
                assertTrue(fixture.namespaceExists());
                assertEquals(1, fixture.count("cabinet_schema_creation_receipts"));
                fixture.assertAdvisoryLock(true);
                ddlCommitted = true;
                fire(Phase.AFTER_DDL_COMMIT, session);
            }
        }

        @Override
        public void close() throws SQLException
        {
            OBSERVATION.remove();
            for (Session session : sessions)
            {
                assertTrue(session.connection.isClosed(), "Every direct provisioning connection must close");
            }
        }
    }

    /** One real JDBC session; proxies only observe execution and never emulate transactions or locks. */
    private static final class Session
    {
        /** Real PostgreSQL connection. */
        private final Connection connection;
        /** Real server-assigned PID. */
        private final int pid;
        /** Current observer. */
        private final Observation observation;

        private Session(Connection connection, Observation observation) throws SQLException
        {
            this.connection = connection;
            this.observation = observation;
            pid = backendPid(connection);
            observation.sessions.add(this);
        }

        private Connection proxy()
        {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) ->
                {
                    Object result = invoke(connection, method, arguments);
                    if ("commit".equals(method.getName()))
                    {
                        observation.inCallback = true;
                        try
                        {
                            observation.afterCommit(this);
                        } finally
                        {
                            observation.inCallback = false;
                        }
                    }
                    if (result instanceof Statement statement)
                    {
                        String sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String
                            ? (String) arguments[0] : null;
                        return statementProxy(statement, sql);
                    }
                    return result;
                });
        }

        private Statement statementProxy(Statement statement, String preparedSql)
        {
            Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) ->
                {
                    if (method.getName().startsWith("execute"))
                    {
                        String sql = preparedSql != null ? preparedSql
                            : arguments != null && arguments.length > 0 && arguments[0] instanceof String
                                ? (String) arguments[0] : "";
                        observation.inCallback = true;
                        try
                        {
                            observation.beforeSql(this, sql);
                        } finally
                        {
                            observation.inCallback = false;
                        }
                        Object result = invoke(statement, method, arguments);
                        observation.inCallback = true;
                        try
                        {
                            observation.afterSql(this, sql);
                        } finally
                        {
                            observation.inCallback = false;
                        }
                        return result;
                    }
                    return invoke(statement, method, arguments);
                });
        }
    }

    /** Registered first only during this class; all connections delegate to the actual PostgreSQL driver. */
    private static final class ObservingDriver implements Driver
    {
        /** Real PostgreSQL driver, not a mocked DriverManager/Connection. */
        private final org.postgresql.Driver delegate = new org.postgresql.Driver();

        @Override
        public Connection connect(String url, Properties properties) throws SQLException
        {
            Connection connection = delegate.connect(url, properties);
            Observation observation = OBSERVATION.get();
            if (connection == null || observation == null || observation.inCallback)
            {
                return connection;
            }
            try
            {
                OwnedTestDatabase.validate(connection);
                return new Session(connection, observation).proxy();
            } catch (SQLException | RuntimeException failure)
            {
                connection.close();
                throw failure;
            }
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException
        {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) throws SQLException
        {
            return delegate.getPropertyInfo(url, properties);
        }

        @Override
        public int getMajorVersion()
        {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion()
        {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant()
        {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger()
        {
            return Logger.getLogger("com.docukore.support.provisioning-observer");
        }
    }
}
