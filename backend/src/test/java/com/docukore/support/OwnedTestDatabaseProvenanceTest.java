package com.docukore.support;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real scope/registry regressions at the JDBC boundary; no live schema creation or cleanup. */
class OwnedTestDatabaseProvenanceTest
{
    @Test
    void anotherScopeCannotRegisterAnOwnedNamespace() throws Exception
    {
        Fixture fixture = new Fixture();
        var first = OwnedTestDatabase.scope(fixture.dataSource);
        var second = OwnedTestDatabase.scope(fixture.dataSource);
        first.createExact(fixture.schema);
        try
        {
            assertThrows(IllegalStateException.class, () -> second.registerExisting(
                "platform", fixture.schema, fixture.original, fixture.reservation));
            assertSame(first, OwnedTestDatabase.owner(fixture.schema));
            first.requireIdentity(fixture.schema);
            second.close();
            assertTrue(fixture.present);
        } finally
        {
            first.drop(fixture.schema);
        }
    }

    @Test
    void rejectedSecondRegistrationCannotGiveAnotherScopeCleanupRights() throws Exception
    {
        Fixture fixture = new Fixture();
        var first = OwnedTestDatabase.scope(fixture.dataSource);
        var second = OwnedTestDatabase.scope(fixture.dataSource);
        first.createExact(fixture.schema);
        try
        {
            try
            {
                second.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            } catch (IllegalStateException expected)
            {
                assertSame(first, OwnedTestDatabase.owner(fixture.schema));
            }
            assertThrows(IllegalStateException.class, () -> second.drop(fixture.schema));
            assertSame(first, OwnedTestDatabase.owner(fixture.schema));
            assertTrue(fixture.present);
        } finally
        {
            first.drop(fixture.schema);
        }
        assertFalse(fixture.present);
        assertThrows(IllegalStateException.class, () -> OwnedTestDatabase.owner(fixture.schema));
    }

    @ParameterizedTest
    @ValueSource(strings = {"drop", "commit", "close"})
    void failedCleanupPreservesOwnershipForSafeRetry(String failingOperation) throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = OwnedTestDatabase.scope(fixture.dataSource);
        owner.createExact(fixture.schema);
        fixture.failure = failingOperation;
        assertThrows(IllegalStateException.class, () -> owner.drop(fixture.schema));
        try
        {
            assertSame(owner, OwnedTestDatabase.owner(fixture.schema));
        } finally
        {
            fixture.failure = null;
            owner.drop(fixture.schema);
        }
        assertFalse(fixture.present);
        assertThrows(IllegalStateException.class, () -> OwnedTestDatabase.owner(fixture.schema));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replacedNamespaceOrOwnerIsRejectedAndOwnershipIsRetained(boolean replaceNamespace) throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = OwnedTestDatabase.scope(fixture.dataSource);
        owner.createExact(fixture.schema);
        fixture.actual = new OwnedTestDatabase.NamespaceIdentity(fixture.schema,
            replaceNamespace ? 99 : 11, replaceNamespace ? 12 : 99);
        int drops = fixture.dropCount;
        try
        {
            assertThrows(IllegalStateException.class, () -> owner.requireIdentity(fixture.schema));
            assertThrows(IllegalStateException.class, () -> owner.drop(fixture.schema));
            assertSame(owner, OwnedTestDatabase.owner(fixture.schema));
            assertTrue(fixture.present);
            assertEquals(drops, fixture.dropCount);
        } finally
        {
            fixture.actual = fixture.original;
            owner.drop(fixture.schema);
        }
    }

    @Test
    void authorizedScopeCanRegisterIdempotentlyAndCleanUp() throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = fixture.authorizedScope();
        owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
        try
        {
            owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            owner.requireIdentity(fixture.schema);
            assertSame(owner, OwnedTestDatabase.owner(fixture.schema));
            assertTrue(fixture.events.indexOf("lock") < fixture.events.indexOf("receipt"));
            assertTrue(fixture.events.indexOf("receipt") < fixture.events.indexOf("commit"));
        } finally
        {
            owner.drop(fixture.schema);
        }
        assertFalse(fixture.present);
    }

    @Test
    void readableReceiptAndIdentityDoNotAuthorizeFirstRegistration() throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = fixture.authorizedScope();
        var stranger = OwnedTestDatabase.scope(fixture.dataSource);
        try
        {
            assertThrows(IllegalStateException.class, () -> stranger.registerExisting(
                "platform", fixture.schema, fixture.original, fixture.reservation));
            assertThrows(IllegalStateException.class, () -> stranger.authorizeAllocation(
                "platform", fixture.schema, fixture.reservation, fixture.cabinet));
            owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            assertThrows(IllegalStateException.class, () -> stranger.registerExisting(
                "platform", fixture.schema, fixture.original, fixture.reservation));
            assertThrows(IllegalStateException.class, () -> stranger.drop(fixture.schema));
            assertSame(owner, OwnedTestDatabase.owner(fixture.schema));
        } finally
        {
            owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            owner.drop(fixture.schema);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reservation", "cabinet", "schema", "namespace", "owner", "missing", "duplicate"})
    void everyReceiptFieldAndUniquenessMustMatchAuthorizedAllocation(String mismatch) throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = fixture.authorizedScope();
        fixture.receiptMismatch = mismatch;
        assertThrows(IllegalStateException.class, () -> owner.registerExisting(
            "platform", fixture.schema, fixture.original, fixture.reservation));
        try
        {
            assertThrows(IllegalStateException.class, () -> OwnedTestDatabase.owner(fixture.schema));
            assertThrows(IllegalStateException.class, () -> owner.drop(fixture.schema));
        } finally
        {
            fixture.receiptMismatch = null;
            owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            owner.drop(fixture.schema);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void authorizationCannotBeReusedForAnotherPlatformOrReservation(boolean wrongPlatform) throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = fixture.authorizedScope();
        try
        {
            assertThrows(IllegalStateException.class, () -> owner.registerExisting(
                wrongPlatform ? "another_platform" : "platform", fixture.schema, fixture.original,
                wrongPlatform ? fixture.reservation : UUID.randomUUID()));
        } finally
        {
            owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            owner.drop(fixture.schema);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "namespace", "owner"})
    void authorizedRegistrationRejectsMissingOrReplacedPhysicalIdentity(String mismatch) throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = fixture.authorizedScope();
        fixture.present = !mismatch.equals("missing");
        fixture.actual = new OwnedTestDatabase.NamespaceIdentity(fixture.schema,
            mismatch.equals("namespace") ? 99 : 11, mismatch.equals("owner") ? 99 : 12);
        try
        {
            assertThrows(IllegalStateException.class, () -> owner.registerExisting(
                "platform", fixture.schema, fixture.original, fixture.reservation));
            assertThrows(IllegalStateException.class, () -> OwnedTestDatabase.owner(fixture.schema));
        } finally
        {
            fixture.present = true;
            fixture.actual = fixture.original;
            owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            owner.drop(fixture.schema);
        }
    }

    @Test
    void failedRegistrationCannotMakeCloseSilentlyDiscardPendingProvenance() throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = fixture.authorizedScope();
        fixture.receiptMismatch = "cabinet";
        assertThrows(IllegalStateException.class, () -> owner.registerExisting(
            "platform", fixture.schema, fixture.original, fixture.reservation));
        try
        {
            assertThrows(IllegalStateException.class, owner::close);
            assertThrows(IllegalStateException.class, () -> OwnedTestDatabase.scope(fixture.dataSource)
                .authorizeAllocation("platform", fixture.schema, fixture.reservation, fixture.cabinet));
        } finally
        {
            fixture.receiptMismatch = null;
            owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            owner.close();
        }
        assertFalse(fixture.present);
    }

    @ParameterizedTest
    @ValueSource(strings = {"drop", "commit", "close"})
    void allocatorCleanupFailureRetainsExactProvenanceUntilSafeRetry(String failingOperation) throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = fixture.authorizedScope();
        owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
        fixture.failure = failingOperation;
        assertThrows(IllegalStateException.class, () -> owner.drop(fixture.schema));
        try
        {
            assertSame(owner, OwnedTestDatabase.owner(fixture.schema));
            assertThrows(IllegalStateException.class, () -> OwnedTestDatabase.scope(fixture.dataSource)
                .authorizeAllocation("platform", fixture.schema, fixture.reservation, fixture.cabinet));
            fixture.failure = null;
            if (fixture.present)
            {
                owner.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
            }
        } finally
        {
            fixture.failure = null;
            owner.drop(fixture.schema);
        }
        var next = fixture.authorizedScope();
        next.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
        next.close();
        assertFalse(fixture.present);
    }

    @Test
    void closeCancelsOnlyPhysicallyAbsentPendingAllocations() throws Exception
    {
        Fixture fixture = new Fixture();
        var owner = fixture.authorizedScope();
        fixture.present = false;
        owner.close();
        var next = fixture.authorizedScope();
        next.registerExisting("platform", fixture.schema, fixture.original, fixture.reservation);
        next.close();
        assertFalse(fixture.present);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void postgresOwnershipCannotTransferAndFailedCleanupCanBeRetried(boolean failCleanup) throws Exception
    {
        Assumptions.assumeTrue(configured("docukore.test.jdbc-url", "DOCUKORE_TEST_JDBC_URL")
            && configured("docukore.test.username", "DOCUKORE_TEST_USERNAME")
            && configured("docukore.test.password", "DOCUKORE_TEST_PASSWORD"),
            "Dedicated PostgreSQL configuration unavailable; no application database fallback");
        DataSource dataSource = mock(DataSource.class);
        boolean[] readOnly = {false};
        when(dataSource.getConnection()).thenAnswer(invocation ->
        {
            Connection connection = OwnedTestDatabase.connection();
            connection.setReadOnly(readOnly[0]);
            return connection;
        });
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String platform = "test_owned_platform_" + suffix;
        String schema = "test_owned_tenant_" + suffix;
        UUID reservation = UUID.randomUUID();
        UUID cabinet = UUID.randomUUID();
        try (var platformOwner = OwnedTestDatabase.scope(dataSource);
             var owner = OwnedTestDatabase.scope(dataSource);
             var stranger = OwnedTestDatabase.scope(dataSource))
        {
            platformOwner.createExact(platform);
            OwnedTestDatabase.NamespaceIdentity identity;
            try (Connection connection = OwnedTestDatabase.connection())
            {
                connection.setAutoCommit(false);
                try (var lock = connection.prepareStatement(
                        "SELECT pg_catalog.pg_advisory_lock(pg_catalog.hashtextextended(?, 0))"))
                {
                    lock.setString(1, "docukore-test-allocator");
                    lock.execute();
                }
                owner.authorizeAllocation(platform, schema, reservation, cabinet);
                try (var statement = connection.createStatement())
                {
                    // Isolated receipt fixture, not a replacement for migration/constraint integration coverage.
                    statement.execute("CREATE TABLE " + OwnedTestDatabase.identifier(platform)
                        + ".cabinet_schema_creation_receipts (reservation_id uuid PRIMARY KEY, cabinet_id uuid, "
                        + "schema_name text, namespace_oid bigint, owner_oid bigint)");
                    statement.execute("CREATE SCHEMA " + OwnedTestDatabase.identifier(schema));
                }
                try (var statement = connection.prepareStatement(
                        "SELECT oid::bigint, nspowner::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?"))
                {
                    statement.setString(1, schema);
                    try (var row = statement.executeQuery())
                    {
                        assertTrue(row.next());
                        identity = new OwnedTestDatabase.NamespaceIdentity(schema, row.getLong(1), row.getLong(2));
                    }
                }
                try (var statement = connection.prepareStatement("INSERT INTO "
                        + OwnedTestDatabase.identifier(platform)
                        + ".cabinet_schema_creation_receipts VALUES (?,?,?,?,?)"))
                {
                    statement.setObject(1, reservation);
                    statement.setObject(2, cabinet);
                    statement.setString(3, schema);
                    statement.setLong(4, identity.namespaceOid());
                    statement.setLong(5, identity.ownerOid());
                    statement.executeUpdate();
                }
                connection.commit();
                owner.registerExisting(platform, schema, identity, reservation);
                // Dedicated session close releases the allocator lock only after ownership registration.
            }
            assertThrows(IllegalStateException.class,
                () -> stranger.registerExisting(platform, schema, identity, reservation));
            assertThrows(IllegalStateException.class, () -> stranger.drop(schema));
            assertSame(owner, OwnedTestDatabase.owner(schema));
            owner.requireIdentity(schema);
            if (failCleanup)
            {
                readOnly[0] = true;
                try
                {
                    assertThrows(IllegalStateException.class, () -> owner.drop(schema));
                    assertSame(owner, OwnedTestDatabase.owner(schema));
                    owner.requireIdentity(schema);
                } finally
                {
                    readOnly[0] = false;
                }
            }
            owner.drop(schema);
            assertThrows(IllegalStateException.class, () -> OwnedTestDatabase.owner(schema));
            try (Connection connection = OwnedTestDatabase.connection();
                 var statement = connection.prepareStatement(
                     "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?)"))
            {
                statement.setString(1, schema);
                try (var row = statement.executeQuery())
                {
                    assertTrue(row.next());
                    assertFalse(row.getBoolean(1));
                }
            }
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

    /** JDBC doubles expose catalog/receipt rows; all authorization and ownership logic remains real. */
    private static final class Fixture
    {
        /** Unique fictional namespace; never created in PostgreSQL by boundary tests. */
        private final String schema = "test_owned_" + UUID.randomUUID().toString().replace("-", "");
        /** Allocation reservation. */
        private final UUID reservation = UUID.randomUUID();
        /** Cabinet bound to the reservation. */
        private final UUID cabinet = UUID.randomUUID();
        /** Captured namespace receipt. */
        private final OwnedTestDatabase.NamespaceIdentity original =
            new OwnedTestDatabase.NamespaceIdentity(schema, 11, 12);
        /** Catalog response, optionally replaced for negative tests. */
        private OwnedTestDatabase.NamespaceIdentity actual = original;
        /** External JDBC boundary. */
        private final DataSource dataSource = mock(DataSource.class);
        /** Ordered transaction and DDL events. */
        private final List<String> events = new ArrayList<>();
        /** Whether the catalog contains the namespace. */
        private boolean present;
        /** Optional injected cleanup failure. */
        private String failure;
        /** Number of attempted schema drops. */
        private int dropCount;
        /** One field of the external creation receipt to corrupt. */
        private String receiptMismatch;

        private Fixture() throws SQLException
        {
            when(dataSource.getConnection()).thenAnswer(invocation -> connection());
        }

        private OwnedTestDatabase.Scope authorizedScope()
        {
            var scope = OwnedTestDatabase.scope(dataSource);
            scope.authorizeAllocation("platform", schema, reservation, cabinet);
            present = true;
            return scope;
        }

        private Connection connection() throws SQLException
        {
            Connection connection = mock(Connection.class);
            boolean[] dropping = {false};
            when(connection.createStatement()).thenAnswer(invocation ->
            {
                Statement statement = mock(Statement.class);
                ResultSet database = mock(ResultSet.class);
                when(database.next()).thenReturn(true, false);
                when(database.getString(1)).thenReturn("docukore_test");
                when(statement.executeQuery("SELECT current_database()")).thenReturn(database);
                when(statement.execute(anyString())).thenAnswer(execution ->
                {
                    String sql = execution.getArgument(0);
                    if (sql.startsWith("CREATE SCHEMA "))
                    {
                        if (present)
                        {
                            throw new SQLException("Namespace already exists");
                        }
                        present = true;
                        events.add("create");
                    } else if (sql.equals("DROP SCHEMA " + OwnedTestDatabase.identifier(schema) + " CASCADE"))
                    {
                        events.add("drop");
                        dropCount++;
                        fail("drop");
                        dropping[0] = true;
                    } else
                    {
                        throw new AssertionError("Unexpected statement: " + sql);
                    }
                    return false;
                });
                return statement;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
            {
                String sql = invocation.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                if (sql.contains("pg_advisory_xact_lock"))
                {
                    when(statement.execute()).thenAnswer(execution ->
                    {
                        events.add("lock");
                        return false;
                    });
                } else if (sql.contains("pg_catalog.pg_namespace"))
                {
                    ResultSet row = mock(ResultSet.class);
                    when(row.next()).thenReturn(present, false);
                    when(row.getLong(1)).thenReturn(actual.namespaceOid());
                    when(row.getLong(2)).thenReturn(actual.ownerOid());
                    when(statement.executeQuery()).thenReturn(row);
                } else if (sql.contains("cabinet_schema_creation_receipts"))
                {
                    ResultSet row = mock(ResultSet.class);
                    when(row.next()).thenReturn(!"missing".equals(receiptMismatch),
                        "duplicate".equals(receiptMismatch), false);
                    when(row.getObject("reservation_id", UUID.class)).thenReturn(
                        "reservation".equals(receiptMismatch) ? UUID.randomUUID() : reservation);
                    when(row.getObject("cabinet_id", UUID.class)).thenReturn(
                        "cabinet".equals(receiptMismatch) ? UUID.randomUUID() : cabinet);
                    when(row.getString("schema_name")).thenReturn(
                        "schema".equals(receiptMismatch) ? "another_schema" : schema);
                    when(row.getLong("namespace_oid")).thenReturn(
                        "namespace".equals(receiptMismatch) ? 99 : original.namespaceOid());
                    when(row.getLong("owner_oid")).thenReturn(
                        "owner".equals(receiptMismatch) ? 99 : original.ownerOid());
                    when(statement.executeQuery()).thenAnswer(execution ->
                    {
                        events.add("receipt");
                        return row;
                    });
                } else
                {
                    throw new AssertionError("Unexpected prepared statement: " + sql);
                }
                return statement;
            });
            doAnswer(invocation ->
            {
                events.add("commit");
                fail("commit");
                if (dropping[0])
                {
                    present = false;
                }
                return null;
            }).when(connection).commit();
            doAnswer(invocation ->
            {
                events.add("rollback");
                dropping[0] = false;
                return null;
            }).when(connection).rollback();
            doAnswer(invocation ->
            {
                events.add("close");
                fail("close");
                return null;
            }).when(connection).close();
            return connection;
        }

        private void fail(String operation) throws SQLException
        {
            if (operation.equals(failure))
            {
                throw new SQLException("Fictional " + operation + " failure");
            }
        }
    }
}
