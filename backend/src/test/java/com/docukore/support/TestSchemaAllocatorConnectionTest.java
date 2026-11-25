package com.docukore.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** JDBC-boundary lifecycle regressions; PostgreSQL lock contention is tested separately. */
class TestSchemaAllocatorConnectionTest
{
    /** Fictional connection configuration, intercepted before any network access. */
    private static final String URL = "jdbc:postgresql://example.invalid/docukore_test";
    /** Fictional cabinet owner. */
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void allocationCommitsThenUnlocksAndClosesBeforeMigration(boolean tracked) throws SQLException
    {
        Fixture fixture = new Fixture();
        fixture.run(() -> fixture.allocate(tracked));
        assertEquals(List.of("lock", "candidate", "exists", "create", "identity", "allocation", "cabinet",
            "receipt", "commit", "unlock", "close", "migrate"), fixture.events);
        assertEquals(1, fixture.opened);
        assertTrue(fixture.sessions.getFirst().closed);
    }

    @Test
    void ownershipRegistrationCompletesAfterCommitButBeforeUnlockAndClose() throws SQLException
    {
        Fixture fixture = new Fixture();
        doAnswer(invocation ->
        {
            Session session = fixture.sessions.getFirst();
            assertTrue(session.committed);
            assertFalse(session.closed);
            assertEquals("commit", fixture.events.getLast());
            fixture.events.add("registration-start");
            var identity = invocation.getArgument(2, OwnedTestDatabase.NamespaceIdentity.class);
            assertEquals("cabinet00001", identity.schemaName());
            assertEquals(11L, identity.namespaceOid());
            assertEquals(12L, identity.ownerOid());
            fixture.events.add("registration-complete");
            return null;
        }).when(fixture.resources).registerExisting(anyString(), anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        fixture.run(() -> fixture.allocate(true));
        assertTrue(fixture.events.indexOf("registration-complete") < fixture.events.indexOf("unlock"));
        assertTrue(fixture.events.indexOf("unlock") < fixture.events.indexOf("close"));
    }

    @Test
    void overlappingOperationsUseDifferentConnectionsEvenDuringRegistration() throws SQLException
    {
        Fixture fixture = new Fixture();
        doAnswer(invocation ->
        {
            assertFalse(fixture.sessions.getFirst().closed);
            fixture.allocate(false);
            assertEquals(2, fixture.opened, "Independent allocation must open its own JDBC session");
            assertNotEquals(fixture.sessions.get(0).connection, fixture.sessions.get(1).connection);
            assertFalse(fixture.sessions.getFirst().closed, "Nested operation must not close the first session");
            return null;
        }).when(fixture.resources).registerExisting(anyString(), anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        fixture.run(() -> fixture.allocate(true));
        assertTrue(fixture.sessions.stream().allMatch(session -> session.closed));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sqlFailureRollsBackThenUnlocksAndCloses(boolean tracked) throws SQLException
    {
        Fixture fixture = new Fixture();
        SQLException failure = new SQLException("fictional allocation failure");
        fixture.allocationFailure = failure;
        fixture.run(() ->
        {
            var thrown = assertThrows(IllegalStateException.class, () -> fixture.allocate(tracked));
            assertSame(failure, thrown.getCause());
        });
        assertEquals(List.of("lock", "candidate", "exists", "create", "identity", "allocation", "rollback",
            "unlock", "close"), fixture.events);
    }

    @Test
    void registrationFailureRollsBackThenUnlocksAndCloses() throws SQLException
    {
        Fixture fixture = new Fixture();
        RuntimeException failure = new IllegalStateException("fictional registration failure");
        doAnswer(invocation ->
        {
            assertTrue(fixture.sessions.getFirst().committed);
            throw failure;
        }).when(fixture.resources).registerExisting(anyString(), anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        fixture.run(() -> assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.allocate(true))));
        assertEquals(List.of("rollback", "unlock", "close"), fixture.events.subList(9, 12));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void migrationFailureDoesNotRollbackClosedAllocationConnection(boolean tracked) throws SQLException
    {
        Fixture fixture = new Fixture();
        RuntimeException failure = new IllegalStateException("fictional migration failure");
        fixture.migrationFailure = failure;
        fixture.run(() -> assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.allocate(tracked))));
        assertFalse(fixture.events.contains("rollback"));
        assertTrue(fixture.sessions.getFirst().closed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lockFailureRollsBackAndClosesWithoutUnlockingUnacquiredLock(boolean tracked) throws SQLException
    {
        Fixture fixture = new Fixture();
        fixture.lockFailure = new SQLException("fictional lock failure");
        fixture.run(() -> assertThrows(IllegalStateException.class, () -> fixture.allocate(tracked)));
        assertEquals(List.of("lock", "rollback", "close"), fixture.events);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unlockFailureStillClosesConnectionAndIsReported(boolean tracked) throws SQLException
    {
        Fixture fixture = new Fixture();
        fixture.unlockFailure = new SQLException("fictional unlock failure");
        fixture.run(() ->
        {
            var failure = assertThrows(IllegalStateException.class, () -> fixture.allocate(tracked));
            assertSame(fixture.unlockFailure, failure.getCause());
        });
        assertTrue(fixture.sessions.getFirst().closed);
        assertFalse(fixture.events.contains("migrate"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cleanupFailuresDoNotReplaceAllocationFailure(boolean tracked) throws SQLException
    {
        Fixture fixture = new Fixture();
        fixture.allocationFailure = new SQLException("fictional allocation failure");
        fixture.rollbackFailure = new SQLException("fictional rollback failure");
        fixture.unlockFailure = new SQLException("fictional unlock failure");
        fixture.run(() ->
        {
            var failure = assertThrows(IllegalStateException.class, () -> fixture.allocate(tracked));
            assertSame(fixture.allocationFailure, failure.getCause());
            assertEquals(2, failure.getCause().getSuppressed().length);
        });
        assertTrue(fixture.sessions.getFirst().closed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void repeatedAllocationUsesSequenceValuesAndSkipsExistingPhysicalName(boolean tracked) throws SQLException
    {
        Fixture fixture = new Fixture();
        fixture.existingCandidate = true;
        fixture.run(() ->
        {
            var first = fixture.allocate(tracked);
            var second = fixture.allocate(tracked);
            assertEquals("cabinet00002", first.schemaName());
            assertEquals("cabinet00003", second.schemaName());
            assertTrue(first.schemaNumber() < second.schemaNumber());
            assertNotEquals(first.cabinetId(), second.cabinetId());
        });
        assertEquals(2, fixture.opened);
        assertTrue(fixture.sessions.stream().allMatch(session -> session.closed));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void postgresSessionsSerializeThroughCommittedRegistrationAndReleaseAfterSuccessOrFailure(boolean failRegistration)
        throws Exception
    {
        Assumptions.assumeTrue(OwnedTestDatabase.hasExternalConfiguration(),
            "Live PostgreSQL methods require an explicit test launch; mock-only methods need no database arguments");

        // Real PostgreSQL sessions/locks/commit/rollback only; allocation DDL and migrations remain intercepted.
        try (Connection first = OwnedTestDatabase.connection();
             Connection second = OwnedTestDatabase.connection();
             Connection observer = OwnedTestDatabase.connection();
             var executor = Executors.newFixedThreadPool(2))
        {
            int firstPid = backendPid(first);
            int secondPid = backendPid(second);
            assertNotEquals(firstPid, secondPid);
            Fixture firstFixture = new Fixture();
            firstFixture.lockConnection = first;
            Fixture secondFixture = new Fixture();
            secondFixture.lockConnection = second;
            CountDownLatch registration = new CountDownLatch(1);
            CountDownLatch finishRegistration = new CountDownLatch(1);
            doAnswer(invocation ->
            {
                assertTrue(firstFixture.sessions.getFirst().committed);
                assertFalse(first.isClosed());
                registration.countDown();
                assertTrue(finishRegistration.await(10, TimeUnit.SECONDS), "Registration gate timed out");
                if (failRegistration)
                {
                    throw new IllegalStateException("fictional registration failure");
                }
                return null;
            }).when(firstFixture.resources).registerExisting(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

            var firstAllocation = executor.submit(() ->
            {
                firstFixture.run(() -> firstFixture.allocate(true));
                return null;
            });
            try
            {
                assertTrue(registration.await(10, TimeUnit.SECONDS), "First allocation did not reach registration");
                assertFalse(tryAllocatorLock(observer), "Commit must not release the session-level lock");
                var secondAllocation = executor.submit(() ->
                {
                    secondFixture.run(() -> secondFixture.allocate(false));
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked;
                do
                {
                    try (PreparedStatement statement = observer.prepareStatement(
                            "SELECT ? = ANY(pg_catalog.pg_blocking_pids(?))"))
                    {
                        statement.setInt(1, firstPid);
                        statement.setInt(2, secondPid);
                        try (ResultSet result = statement.executeQuery())
                        {
                            assertTrue(result.next());
                            blocked = result.getBoolean(1);
                        }
                    }
                    if (!blocked && System.nanoTime() < deadline)
                    {
                        Thread.sleep(10);
                    }
                } while (!blocked && System.nanoTime() < deadline);
                assertTrue(blocked, "Second allocator must be blocked by the first PostgreSQL session");
                assertFalse(secondFixture.events.contains("candidate"), "Candidate selection must wait for the lock");
                finishRegistration.countDown();
                if (failRegistration)
                {
                    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> firstAllocation.get(10, TimeUnit.SECONDS));
                    assertEquals("fictional registration failure", failure.getCause().getMessage());
                } else
                {
                    firstAllocation.get(10, TimeUnit.SECONDS);
                }
                secondAllocation.get(10, TimeUnit.SECONDS);
                assertTrue(first.isClosed());
                assertTrue(second.isClosed());
                assertTrue(tryAllocatorLock(observer), "Both allocator sessions must release the lock");
                try (PreparedStatement unlock = observer.prepareStatement(
                        "SELECT pg_catalog.pg_advisory_unlock(pg_catalog.hashtextextended(?, 0))"))
                {
                    unlock.setString(1, "docukore-test-allocator");
                    try (ResultSet result = unlock.executeQuery())
                    {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1));
                    }
                }
            } finally
            {
                finishRegistration.countDown();
                executor.shutdownNow();
            }
        }
    }

    private static int backendPid(Connection connection) throws SQLException
    {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_catalog.pg_backend_pid()"))
        {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static boolean tryAllocatorLock(Connection connection) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_try_advisory_lock(pg_catalog.hashtextextended(?, 0))"))
        {
            statement.setString(1, "docukore-test-allocator");
            try (ResultSet result = statement.executeQuery())
            {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    /** Replaces only external JDBC, ownership and migration boundaries; runs real allocator code. */
    private static final class Fixture
    {
        /** Events emitted by JDBC calls and external callbacks. */
        private final List<String> events = new ArrayList<>();
        /** Dedicated sessions offered to the allocator. */
        private final List<Session> sessions = new ArrayList<>();
        /** Unused legacy caller argument in physical allocation. */
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        /** Registration boundary; real provenance checks are outside this unit test. */
        private final OwnedTestDatabase.Scope resources = mock(OwnedTestDatabase.Scope.class);
        /** Number of opened connections. */
        private int opened;
        /** Sequential nextval results supplied by JDBC. */
        private long sequence;
        /** Existing physical candidate to skip once. */
        private boolean existingCandidate;
        /** Optional SQL failures at defined external boundaries. */
        private SQLException allocationFailure;
        /** Optional lock failure. */
        private SQLException lockFailure;
        /** Optional rollback failure. */
        private SQLException rollbackFailure;
        /** Optional unlock failure. */
        private SQLException unlockFailure;
        /** Optional migration failure after connection cleanup. */
        private RuntimeException migrationFailure;
        /** Optional real session for lock-only PostgreSQL verification; allocation SQL stays intercepted. */
        private Connection lockConnection;

        private Connection open() throws SQLException
        {
            Session session = new Session(this);
            sessions.add(session);
            opened++;
            return session.connection;
        }

        private TestSchemaAllocator.AllocationResult allocate(boolean tracked)
        {
            return tracked ? TestSchemaAllocator.allocateSchema(jdbc, "test_platform", resources, OWNER)
                : TestSchemaAllocator.allocateSchemaWithoutTracking(jdbc, "test_platform", OWNER);
        }

        private void run(Runnable action) throws SQLException
        {
            // Intercept both old and corrected connection boundaries, without environment/property changes.
            try (var owned = mockStatic(OwnedTestDatabase.class);
                 var driver = mockStatic(DriverManager.class);
                 var migrations = mockStatic(Flyway.class))
            {
                owned.when(OwnedTestDatabase::jdbcUrl).thenReturn(URL);
                owned.when(OwnedTestDatabase::username).thenReturn("fictional");
                owned.when(OwnedTestDatabase::password).thenReturn("fictional");
                owned.when(OwnedTestDatabase::connection).thenAnswer(invocation -> open());
                driver.when(() -> DriverManager.getConnection(URL, "fictional", "fictional"))
                    .thenAnswer(invocation -> open());
                FluentConfiguration configuration = mock(FluentConfiguration.class, RETURNS_SELF);
                Flyway flyway = mock(Flyway.class);
                migrations.when(Flyway::configure).thenReturn(configuration);
                when(configuration.load()).thenReturn(flyway);
                when(flyway.migrate()).thenAnswer(invocation ->
                {
                    assertTrue(sessions.getLast().closed, "Allocation connection must close before migrations");
                    events.add("migrate");
                    if (migrationFailure != null)
                    {
                        throw migrationFailure;
                    }
                    return null;
                });
                action.run();
            }
        }
    }

    /** A JDBC session double, without simulated PostgreSQL lock contention. */
    private static final class Session
    {
        /** Connection boundary for this session. */
        private final Connection connection = mock(Connection.class);
        /** Whether the transaction committed. */
        private boolean committed;
        /** Whether the allocator closed this connection. */
        private boolean closed;

        private Session(Fixture fixture) throws SQLException
        {
            doAnswer(invocation ->
            {
                if (fixture.lockConnection != null)
                {
                    fixture.lockConnection.setAutoCommit(invocation.getArgument(0));
                }
                return null;
            }).when(connection).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
            when(connection.isClosed()).thenAnswer(invocation -> closed);
            Statement validation = mock(Statement.class);
            ResultSet database = mock(ResultSet.class);
            when(database.next()).thenReturn(true);
            when(database.getString(1)).thenReturn("docukore_test");
            when(validation.executeQuery("SELECT current_database()")).thenReturn(database);
            ResultSet roles = mock(ResultSet.class);
            when(roles.next()).thenReturn(true);
            when(roles.getString(1)).thenReturn("docukore_test_runner");
            when(roles.getString(2)).thenReturn("docukore_test_runner");
            when(validation.executeQuery("SELECT current_user, session_user")).thenReturn(roles);
            when(connection.createStatement()).thenReturn(validation);
            doAnswer(invocation ->
            {
                assertFalse(closed, "Must not commit a closed session");
                if (fixture.lockConnection != null)
                {
                    fixture.lockConnection.commit();
                }
                committed = true;
                fixture.events.add("commit");
                return null;
            }).when(connection).commit();
            doAnswer(invocation ->
            {
                assertFalse(closed, "Must not rollback a closed session");
                fixture.events.add("rollback");
                if (fixture.lockConnection != null)
                {
                    fixture.lockConnection.rollback();
                }
                if (fixture.rollbackFailure != null)
                {
                    throw fixture.rollbackFailure;
                }
                return null;
            }).when(connection).rollback();
            doAnswer(invocation ->
            {
                assertFalse(closed, "Must close a session only once");
                if (fixture.lockConnection != null)
                {
                    fixture.lockConnection.close();
                }
                closed = true;
                fixture.events.add("close");
                return null;
            }).when(connection).close();
            when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
            {
                assertFalse(closed, "Must not execute SQL on a closed session");
                String sql = invocation.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                ResultSet result = mock(ResultSet.class);
                when(result.next()).thenReturn(true, false);
                when(statement.executeQuery()).thenReturn(result);
                if (sql.contains("pg_advisory_lock(") || sql.contains("pg_advisory_unlock("))
                {
                    String operation = sql.contains("pg_advisory_unlock(") ? "unlock" : "lock";
                    assertEquals("SELECT pg_catalog.pg_advisory_" + operation
                        + "(pg_catalog.hashtextextended(?, 0))", sql);
                    if (fixture.lockConnection != null)
                    {
                        PreparedStatement realStatement = fixture.lockConnection.prepareStatement(sql);
                        when(statement.execute()).thenAnswer(call ->
                        {
                            fixture.events.add(operation);
                            return realStatement.execute();
                        });
                        doAnswer(binding ->
                        {
                            realStatement.setString(binding.getArgument(0), binding.getArgument(1));
                            return null;
                        }).when(statement).setString(org.mockito.ArgumentMatchers.anyInt(), anyString());
                        doAnswer(call ->
                        {
                            realStatement.close();
                            return null;
                        }).when(statement).close();
                        return statement;
                    }
                    doAnswer(binding ->
                    {
                        assertEquals("docukore-test-allocator", binding.getArgument(1));
                        return null;
                    }).when(statement).setString(org.mockito.ArgumentMatchers.eq(1), anyString());
                    when(statement.execute()).thenAnswer(call ->
                    {
                        fixture.events.add(operation);
                        SQLException failure = operation.equals("lock") ? fixture.lockFailure : fixture.unlockFailure;
                        if (failure != null)
                        {
                            throw failure;
                        }
                        return true;
                    });
                } else if (sql.contains("pg_catalog.nextval"))
                {
                    fixture.events.add("candidate");
                    when(result.getLong(1)).thenReturn(++fixture.sequence);
                } else if (sql.startsWith("SELECT EXISTS"))
                {
                    fixture.events.add("exists");
                    when(result.getBoolean(1)).thenReturn(fixture.existingCandidate);
                    fixture.existingCandidate = false;
                } else if (sql.startsWith("SELECT oid"))
                {
                    fixture.events.add("identity");
                    when(result.getLong(1)).thenReturn(11L);
                    when(result.getLong(2)).thenReturn(12L);
                } else
                {
                    String operation;
                    if (sql.startsWith("CREATE SCHEMA"))
                    {
                        operation = "create";
                    } else if (sql.contains(".cabinet_schema_allocations"))
                    {
                        operation = "allocation";
                    } else if (sql.contains(".cabinet_schema_creation_receipts"))
                    {
                        operation = "receipt";
                    } else if (sql.contains(".cabinet"))
                    {
                        operation = "cabinet";
                    } else
                    {
                        throw new AssertionError("Unexpected SQL: " + sql);
                    }
                    when(statement.execute()).thenAnswer(call ->
                    {
                        fixture.events.add(operation);
                        if (operation.equals("allocation") && fixture.allocationFailure != null)
                        {
                            throw fixture.allocationFailure;
                        }
                        return true;
                    });
                }
                return statement;
            });
        }
    }
}
