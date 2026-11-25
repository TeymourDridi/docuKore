package com.docukore.tenant;

import com.docukore.audit.AuditEvent;
import com.docukore.audit.AuditService;
import com.docukore.support.OwnedTestDatabase;
import com.docukore.support.SharedSpringBootTest;
import com.docukore.support.TestFixtures;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real tenant PostgreSQL coverage for audit mapping, mandatory writes and after-commit VIEW recording. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(AuditPersistenceIntegrationTest.ReadOnlyConfiguration.class)
class AuditPersistenceIntegrationTest extends SharedSpringBootTest
{
    /** Fully migrated invocation-owned platform and migrator-only ownership receipts. */
    private static final TestFixtures.OwnedPlatform PLATFORM = TestFixtures.createPlatform("audit_platform");
    /** Offset and sub-microsecond input exercises instant preservation and PostgreSQL precision. */
    private static final OffsetDateTime TIMESTAMP = OffsetDateTime.parse("2026-03-04T10:11:12.123456789+05:30");
    /** Only opaque correlation and a fixed outcome are permitted through the production service. */
    private static final String REQUEST_ID = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
    /** Expected metadata uses server request context, never a caller's correlation claim. */
    private static final String METADATA = "{\"requestId\":\"" + REQUEST_ID + "\",\"outcome\":\"SUCCESS\"}";
    /** Factory for the actual transaction-bound tenant persistence context. */
    private final EntityManagerFactory entityManagers;
    /** Existing validated PostgreSQL pool, never a separately created database or container. */
    private final HikariDataSource dataSource;
    /** Production audit service, including its real repository and account/membership lookups. */
    private final AuditService auditService;
    /** Actual application tenant transaction manager. */
    private final TransactionTemplate transactions;
    /** Spring-proxied read-only boundary, not a mocked transaction flag. */
    private final ReadOnlyActions readOnly;
    /** Qualified platform fixture and independent committed-row assertions. */
    private final JdbcTemplate jdbc;
    /** One invocation-owned schema receipt scope for the entire class. */
    private final OwnedTestDatabase.Scope resources;
    /** First independently migrated fictional cabinet. */
    private TestFixtures.CabinetFixture first;
    /** Second independently migrated fictional cabinet. */
    private TestFixtures.CabinetFixture second;

    AuditPersistenceIntegrationTest(EntityManagerFactory entityManagers, HikariDataSource dataSource,
            AuditService auditService, @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            ReadOnlyActions readOnly)
    {
        this.entityManagers = entityManagers;
        this.dataSource = dataSource;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = readOnly;
        this.jdbc = PLATFORM.jdbc();
        this.resources = OwnedTestDatabase.scope(PLATFORM.migratorDataSource());
    }

    @DynamicPropertySource
    static void isolatedPlatform(DynamicPropertyRegistry registry)
    {
        registerPlatform(registry, PLATFORM);
    }

    @BeforeAll
    void createOwnedFixtures()
    {
        first = createFixture();
        second = createFixture();
    }

    private TestFixtures.CabinetFixture createFixture()
    {
        UUID owner = TestFixtures.createUser(jdbc, PLATFORM.schema());
        var fixture = TestFixtures.createCabinet(jdbc, PLATFORM.schema(), owner, resources);
        Integer memberships = jdbc.queryForObject("SELECT count(*) FROM " + PLATFORM.schema()
                + ".cabinet_memberships "
                + "WHERE cabinet_id = ? AND user_id = ?", Integer.class, fixture.cabinetId(), owner);
        if (Integer.valueOf(0).equals(memberships))
        {
            TestFixtures.createMembership(jdbc, PLATFORM.schema(), fixture.cabinetId(), owner, "OWNER", "DOCTOR");
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + PLATFORM.schema() + ".cabinet_memberships "
                + "WHERE cabinet_id = ? AND user_id = ? AND membership_role = 'OWNER' "
                + "AND status = 'ACTIVE' AND revoked_at IS NULL", Integer.class, fixture.cabinetId(), owner));
        // The allocator runs tenant Flyway: this assertion proves V4 executed, rather than hand-built audit DDL.
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + table(fixture, "flyway_schema_history_tenant")
                + " WHERE version = '4' AND script = 'V4__create_audit_logs_table.sql' AND success", Integer.class));
        TestFixtures.createTenantProbeTable(jdbc, fixture.schemaName());
        return fixture;
    }

    @Test
    void mapsEveryUuidAndJsonbColumnWithPostgresqlTypesAndSemanticEquality()
    {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), first.cabinetId(), "document", UUID.randomUUID(),
                "UPDATE", first.ownerId(), "SECRETARY", TIMESTAMP.truncatedTo(ChronoUnit.MICROS));
        event.setIpAddress("192.0.2.17");
        event.setUserAgent("Synthetic mapping client");
        event.setChangedFields("[\"synthetic_field\",\"synthetic_flag\"]");
        event.setPreviousValues("{\"synthetic_flag\":false,\"synthetic_number\":1}");
        event.setNewValues("{\"synthetic_number\":2,\"synthetic_flag\":true}");
        event.setMetadata("{\"synthetic_context\":{\"flags\":[true,null],\"number\":3}}");
        // Direct entity construction/persistence is mapping-only; these generic values are forbidden by the service.
        write(first, em ->
        {
            em.persist(event);
            em.flush();
        });
        AuditEvent stored = find(first, event.id());
        assertIdentity(event, stored);
        assertEquals(event.userRole(), stored.userRole());
        assertEquals(event.userAgent(), stored.userAgent());
        assertEquals(TIMESTAMP.toInstant().truncatedTo(ChronoUnit.MICROS), stored.timestamp().toInstant());
        assertTrue(jdbc.queryForObject("""
            SELECT pg_typeof(id)::text = 'uuid' AND pg_typeof(cabinet_id)::text = 'uuid'
                AND pg_typeof(entity_id)::text = 'uuid' AND pg_typeof(user_id)::text = 'uuid'
                AND pg_typeof(timestamp)::text = 'timestamp with time zone'
                AND pg_typeof(ip_address)::text = 'inet'
                AND pg_typeof(changed_fields)::text = 'jsonb' AND pg_typeof(previous_values)::text = 'jsonb'
                AND pg_typeof(new_values)::text = 'jsonb' AND pg_typeof(metadata)::text = 'jsonb'
            FROM %s WHERE id = ?
            """.formatted(table(first, "audit_logs")), Boolean.class, event.id()));
        assertJsonColumns(first, stored, event.changedFields(), event.previousValues(), event.newValues(),
                event.metadata());
    }

    @Test
    void roundTripsIpv4AndIpv6WithAndWithoutNetworkMasks()
    {
        for (String address : List.of("192.0.2.17", "192.0.2.17/24", "2001:db8::17", "2001:db8::17/64"))
        {
            AuditEvent event = event(first, UUID.randomUUID(), "CREATE");
            event.setIpAddress(address);
            write(first, em ->
            {
                em.persist(event);
                em.flush();
            });
            // Compare against the driver's INET representation, not PostgreSQL's explicit text cast.
            String canonical = jdbc.queryForObject("SELECT cast(? as inet)", String.class, address);
            assertEquals(canonical, find(first, event.id()).ipAddress());
            assertTrue(jdbc.queryForObject("SELECT ip_address = cast(? as inet) FROM " + table(first, "audit_logs")
                    + " WHERE id = ?", Boolean.class, address, event.id()));
        }
    }

    @Test
    void roundTripsNullableOptionalFields()
    {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), first.cabinetId(), "document", UUID.randomUUID(),
                "VIEW", first.ownerId(), null, TIMESTAMP.truncatedTo(ChronoUnit.MICROS));
        write(first, em -> em.persist(event));
        AuditEvent stored = find(first, event.id());
        assertIdentity(event, stored);
        assertNull(stored.userRole());
        assertNull(stored.ipAddress());
        assertNull(stored.userAgent());
        assertNull(stored.changedFields());
        assertNull(stored.previousValues());
        assertNull(stored.newValues());
        assertNull(stored.metadata());
    }

    @Test
    void commitsBusinessWriteAndAuditTogetherWithTrustedRoleAndSafeMetadata()
    {
        UUID probe = UUID.randomUUID();
        AuditEvent event = event(first, UUID.randomUUID(), "UPDATE");
        event.setChangedFields("[\"status\"]");
        event.setMetadata("{\"requestId\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\",\"outcome\":\"SUCCESS\"}");
        event.setUserAgent("Synthetic untrusted header");
        event.setIpAddress("192.0.2.18");
        write(first, em ->
        {
            em.persist(new TenantProbe(probe, "COMMITTED"));
            auditService.recordEvent(event);
        });
        assertEquals(1, count(first, "tenant_probe", probe));
        AuditEvent stored = find(first, event.id());
        assertIdentity(event, stored);
        assertEquals("OWNER", stored.userRole());
        assertNull(stored.userAgent());
        assertNull(stored.previousValues());
        assertNull(stored.newValues());
        assertEquals(TIMESTAMP.toInstant().truncatedTo(ChronoUnit.MICROS), stored.timestamp().toInstant());
        assertJsonColumns(first, stored, "[\"status\"]", null, null, METADATA);
    }

    @Test
    void caughtAuditDatabaseFailureStillMarksBusinessTransactionRollbackOnly()
    {
        UUID probe = UUID.randomUUID();
        AuditEvent event = event(first, UUID.randomUUID(), "CREATE");
        event.setIpAddress("synthetic-invalid-inet");
        assertThrows(UnexpectedRollbackException.class, () -> inTenant(first, () ->
            transactions.executeWithoutResult(status ->
            {
                entityManager().persist(new TenantProbe(probe, "MUST_ROLL_BACK"));
                RuntimeException failure = assertThrows(RuntimeException.class, () -> auditService.recordEvent(event));
                assertSqlState(failure, "22P02");
                assertTrue(status.isRollbackOnly());
            })));
        assertEquals(0, count(first, "tenant_probe", probe));
        assertEquals(0, count(first, "audit_logs", event.id()));
    }

    @Test
    void laterBusinessFailureRollsBackPreviouslyFlushedAudit()
    {
        UUID probe = UUID.randomUUID();
        AuditEvent event = event(first, UUID.randomUUID(), "CREATE");
        assertThrows(IllegalStateException.class, () -> write(first, em ->
        {
            em.persist(new TenantProbe(probe, "MUST_ROLL_BACK"));
            auditService.recordEvent(event);
            assertNotNull(em.find(AuditEvent.class, event.id()));
            throw new IllegalStateException("Synthetic business failure");
        }));
        assertEquals(0, count(first, "tenant_probe", probe));
        assertEquals(0, count(first, "audit_logs", event.id()));
    }

    @Test
    void viewIsAbsentInsideRealReadOnlySpringProxyAndCommittedAfterCompletion()
    {
        AuditEvent event = event(first, UUID.randomUUID(), "VIEW");
        inTenant(first, () -> readOnly.execute(em ->
        {
            auditService.recordEvent(event);
            assertEquals(0, count(first, "audit_logs", event.id()));
            assertNull(em.find(AuditEvent.class, event.id()));
        }));
        assertIdentity(event, find(first, event.id()));
        assertEquals(1, count(first, "audit_logs", event.id()));
    }

    @Test
    void queuedViewIsDiscardedWhenReadOnlyBusinessTransactionRollsBack()
    {
        AuditEvent event = event(first, UUID.randomUUID(), "VIEW");
        assertThrows(IllegalStateException.class, () -> inTenant(first, () -> readOnly.execute(em ->
        {
            auditService.recordEvent(event);
            assertEquals(0, count(first, "audit_logs", event.id()));
            throw new IllegalStateException("Synthetic read failure");
        })));
        assertEquals(0, count(first, "audit_logs", event.id()));
    }

    @Test
    void queuedViewSnapshotsMutableInputBeforeAfterCommit()
    {
        AuditEvent event = event(first, UUID.randomUUID(), "VIEW");
        event.setIpAddress("2001:db8::19");
        event.setChangedFields("[\"status\"]");
        event.setMetadata(METADATA);
        inTenant(first, () -> readOnly.execute(em ->
        {
            auditService.recordEvent(event);
            event.setIpAddress("synthetic-invalid-inet");
            event.setChangedFields("[\"synthetic_unapproved_field\"]");
            event.setMetadata("{\"synthetic_unapproved_context\":\"synthetic-canary\"}");
            event.setPreviousValues("{\"synthetic_flag\":false}");
            event.setNewValues("{\"synthetic_flag\":true}");
        }));
        AuditEvent stored = find(first, event.id());
        assertEquals("2001:db8::19", stored.ipAddress());
        assertJsonColumns(first, stored, "[\"status\"]", null, null, METADATA);
    }

    @Test
    void changedTenantBeforeViewCompletionFailsClosedWithoutCrossTenantInsert()
    {
        AuditEvent event = event(first, UUID.randomUUID(), "VIEW");
        TenantContext.Scope[] replacement = new TenantContext.Scope[1];
        try
        {
            assertThrows(IllegalStateException.class, () -> inTenant(first, () -> readOnly.execute(em ->
            {
                auditService.recordEvent(event);
                TenantContext.clear();
                replacement[0] = TenantContext.open(second.cabinetId(), second.schemaName());
            })));
        } finally
        {
            if (replacement[0] != null)
            {
                replacement[0].close();
            }
        }
        assertFalse(TenantContext.isPresent());
        assertEquals(0, count(first, "audit_logs", event.id()));
        assertEquals(0, count(second, "audit_logs", event.id()));
    }

    @Test
    void rejectsCrossCabinetAndSpoofedActorClaimsBeforePersistence()
    {
        AuditEvent foreignCabinet = event(second, UUID.randomUUID(), "CREATE");
        assertThrows(IllegalArgumentException.class,
                () -> write(first, em -> auditService.recordEvent(foreignCabinet)));
        AuditEvent foreignActor = new AuditEvent(UUID.randomUUID(), first.cabinetId(), "document", UUID.randomUUID(),
                "CREATE", second.ownerId(), "OWNER", TIMESTAMP);
        assertThrows(IllegalArgumentException.class, () -> write(first, em -> auditService.recordEvent(foreignActor)));
        assertEquals(0, count(first, "audit_logs", foreignCabinet.id()));
        assertEquals(0, count(second, "audit_logs", foreignCabinet.id()));
        assertEquals(0, count(first, "audit_logs", foreignActor.id()));
    }

    @Test
    void sameAuditIdIsIsolatedAcrossTwoTenantsAndEveryBorrowedConnectionReturnsToBaseline() throws Exception
    {
        UUID id = UUID.randomUUID();
        AuditEvent firstEvent = event(first, id, "CREATE");
        AuditEvent secondEvent = event(second, id, "EXPORT");
        write(first, em -> auditService.recordEvent(firstEvent));
        write(second, em -> auditService.recordEvent(secondEvent));
        assertIdentity(firstEvent, find(first, id));
        assertIdentity(secondEvent, find(second, id));
        assertEquals("CREATE", find(first, id).operation());
        assertEquals("EXPORT", find(second, id).operation());
        List<Connection> borrowed = new ArrayList<>();
        try
        {
            for (int index = 0; index < dataSource.getMaximumPoolSize(); index++)
            {
                Connection connection = dataSource.getConnection();
                borrowed.add(connection);
                assertEquals(PLATFORM.schema(), connection.getSchema());
                assertFalse(connection.isReadOnly());
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery(
                         "SELECT current_schema(), current_setting('transaction_read_only')"))
                {
                    assertTrue(result.next());
                    assertEquals(PLATFORM.schema(), result.getString(1));
                    assertEquals("off", result.getString(2));
                }
            }
        } finally
        {
            for (Connection connection : borrowed)
            {
                connection.close();
            }
        }
        assertFalse(TenantContext.isPresent());
    }

    @Test
    void readOnlyPersistWithoutFlushReproducesSuppressedLegacyAuditWrite()
    {
        AuditEvent event = event(first, UUID.randomUUID(), "VIEW");
        inTenant(first, () -> readOnly.execute(em -> em.persist(event)));
        assertEquals(0, count(first, "audit_logs", event.id()));
    }

    @Test
    void forcingFlushInRealReadOnlyTransactionIsRejectedByPostgresql()
    {
        AuditEvent event = event(first, UUID.randomUUID(), "VIEW");
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> inTenant(first, () -> readOnly.execute(em ->
            {
                em.persist(event);
                em.flush();
            })));
        assertSqlState(failure, "25006");
        assertEquals(0, count(first, "audit_logs", event.id()));
    }

    @Test
    void sensitivePayloadAndDirectSetterBypassesAreRejectedWithoutWrites()
    {
        List<Consumer<AuditEvent>> forbidden = List.of(
                event -> event.setPreviousValues("{\"synthetic_flag\":false}"),
                event -> event.setNewValues("{\"synthetic_flag\":true}"),
                event -> event.setMetadata("{\"clinical_notes\":\"synthetic-canary\"}"),
                event -> event.setChangedFields("[\"synthetic-canary\"]"));
        for (Consumer<AuditEvent> mutate : forbidden)
        {
            AuditEvent event = event(first, UUID.randomUUID(), "UPDATE");
            mutate.accept(event);
            UUID probe = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class, () -> write(first, em ->
            {
                em.persist(new TenantProbe(probe, "POLICY_MUST_ROLL_BACK"));
                auditService.recordEvent(event);
            }));
            assertEquals(0, count(first, "audit_logs", event.id()));
            assertEquals(0, count(first, "tenant_probe", probe));
        }
    }

    @Test
    void duplicateAuditIdIsRejectedInsteadOfMergingAndOriginalRemainsUnchanged()
    {
        UUID id = UUID.randomUUID();
        AuditEvent original = event(first, id, "CREATE");
        original.setMetadata(METADATA);
        write(first, em -> auditService.recordEvent(original));
        AuditEvent replacement = event(first, id, "DELETE");
        replacement.setMetadata("{\"outcome\":\"SUCCESS\"}");
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> write(first, em -> auditService.recordEvent(replacement)));
        assertSqlState(failure, "23505");
        AuditEvent stored = find(first, id);
        assertIdentity(original, stored);
        assertJsonColumns(first, stored, null, null, null, METADATA);
        assertEquals(1, count(first, "audit_logs", id));
    }

    @Test
    void immutableManagedAuditCannotBeChangedByDirtyChecking()
    {
        AuditEvent event = event(first, UUID.randomUUID(), "CREATE");
        event.setMetadata(METADATA);
        write(first, em -> auditService.recordEvent(event));
        write(first, em ->
        {
            AuditEvent managed = em.find(AuditEvent.class, event.id());
            managed.setMetadata("{\"outcome\":\"SUCCESS\"}");
            managed.setIpAddress("192.0.2.99");
            em.flush();
        });
        AuditEvent stored = find(first, event.id());
        assertNull(stored.ipAddress());
        assertJsonColumns(first, stored, null, null, null, METADATA);
    }

    @Test
    void restrictedRuntimeCanInsertAndReadAuditButCannotUpdateOrDeleteCommittedHistory()
    {
        AuditEvent event = event(first, UUID.randomUUID(), "CREATE");
        event.setMetadata(METADATA);
        write(first, em ->
        {
            assertEquals("docukore_app", em.createNativeQuery("SELECT current_user", String.class).getSingleResult());
            assertEquals("docukore_app", em.createNativeQuery("SELECT session_user", String.class).getSingleResult());
            auditService.recordEvent(event);
        });
        assertIdentity(event, find(first, event.id()));
        RuntimeException updateFailure = assertThrows(RuntimeException.class, () -> write(first, em ->
            em.createNativeQuery("UPDATE audit_logs SET operation = 'DELETE' WHERE id = :id")
                .setParameter("id", event.id()).executeUpdate()));
        assertSqlState(updateFailure, "42501");
        RuntimeException deleteFailure = assertThrows(RuntimeException.class, () -> write(first, em ->
            em.createNativeQuery("DELETE FROM audit_logs WHERE id = :id")
                .setParameter("id", event.id()).executeUpdate()));
        assertSqlState(deleteFailure, "42501");
        AuditEvent stored = find(first, event.id());
        assertIdentity(event, stored);
        assertJsonColumns(first, stored, null, null, null, METADATA);
        assertEquals(1, count(first, "audit_logs", event.id()));
    }

    @Test
    void restrictedRuntimeCannotCreateSchemas() throws Exception
    {
        String attemptedSchema = "audit_runtime_denied_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection())
        {
            assertEquals(PLATFORM.schema(), connection.getSchema());
            connection.setAutoCommit(false);
            try
            {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT current_user, session_user"))
                {
                    assertTrue(result.next());
                    assertEquals("docukore_app", result.getString(1));
                    assertEquals("docukore_app", result.getString(2));
                }
                try (var statement = connection.createStatement())
                {
                    String createSql = "CREATE SCHEMA " + OwnedTestDatabase.identifier(attemptedSchema);
                    SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(createSql));
                    assertEquals("42501", failure.getSQLState());
                }
            } finally
            {
                // Roll back even an unexpectedly permitted CREATE, so this negative test never leaves a namespace.
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname = ?",
            Integer.class, attemptedSchema));
        assertFalse(TenantContext.isPresent());
    }

    private AuditEvent event(TestFixtures.CabinetFixture fixture, UUID id, String operation)
    {
        return new AuditEvent(id, fixture.cabinetId(), "document", UUID.randomUUID(), operation,
                fixture.ownerId(), "SECRETARY", TIMESTAMP);
    }

    private void write(TestFixtures.CabinetFixture fixture, Consumer<EntityManager> action)
    {
        inTenant(fixture, () -> transactions.executeWithoutResult(status -> action.accept(entityManager())));
    }

    private AuditEvent find(TestFixtures.CabinetFixture fixture, UUID id)
    {
        // A new Spring transaction/entity manager avoids first-level-cache round-trip false positives.
        return asTenant(fixture, () -> transactions.execute(status -> entityManager().find(AuditEvent.class, id)));
    }

    private EntityManager entityManager()
    {
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
        assertNotNull(em);
        return em;
    }

    private void inTenant(TestFixtures.CabinetFixture fixture, Runnable action)
    {
        asTenant(fixture, () ->
        {
            action.run();
            return null;
        });
    }

    private <T> T asTenant(TestFixtures.CabinetFixture fixture, Supplier<T> action)
    {
        var previous = SecurityContextHolder.getContext();
        String previousRequestId = MDC.get("requestId");
        var security = SecurityContextHolder.createEmptyContext();
        // AuthenticationConfiguration uses this same UserDetails shape; CurrentAccount reloads the real fixture row.
        var principal = User.withUsername(fixture.ownerId().toString()).password("synthetic-unused")
                .authorities("ACCOUNT").build();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities()));
        SecurityContextHolder.setContext(security);
        MDC.put("requestId", REQUEST_ID);
        try (var ignored = TenantContext.open(fixture.cabinetId(), fixture.schemaName()))
        {
            // Tenant scope deliberately surrounds transaction completion, including synchronous afterCommit callbacks.
            return action.get();
        } finally
        {
            SecurityContextHolder.setContext(previous);
            if (previousRequestId == null)
            {
                MDC.remove("requestId");
            } else
            {
                MDC.put("requestId", previousRequestId);
            }
        }
    }

    private static String table(TestFixtures.CabinetFixture fixture, String name)
    {
        return OwnedTestDatabase.identifier(fixture.schemaName()) + "." + name;
    }

    private int count(TestFixtures.CabinetFixture fixture, String name, UUID id)
    {
        return jdbc.queryForObject("SELECT count(*) FROM " + table(fixture, name) + " WHERE id = ?", Integer.class, id);
    }

    private static void assertIdentity(AuditEvent expected, AuditEvent actual)
    {
        assertNotNull(actual);
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.cabinetId(), actual.cabinetId());
        assertEquals(expected.entityId(), actual.entityId());
        assertEquals(expected.userId(), actual.userId());
        assertEquals(expected.entityType(), actual.entityType());
        assertEquals(expected.operation(), actual.operation());
    }

    private void assertJsonColumns(TestFixtures.CabinetFixture fixture, AuditEvent stored, String changed,
            String previous, String next, String metadata)
    {
        assertTrue(jdbc.queryForObject("""
            SELECT changed_fields IS NOT DISTINCT FROM cast(? as jsonb)
                AND previous_values IS NOT DISTINCT FROM cast(? as jsonb)
                AND new_values IS NOT DISTINCT FROM cast(? as jsonb)
                AND metadata IS NOT DISTINCT FROM cast(? as jsonb)
            FROM %s WHERE id = ?
            """.formatted(table(fixture, "audit_logs")), Boolean.class,
                changed, previous, next, metadata, stored.id()));
        // PostgreSQL also compares the JPA-loaded JSON strings semantically, not by formatting/key order.
        assertTrue(jdbc.queryForObject("SELECT cast(? as jsonb) IS NOT DISTINCT FROM cast(? as jsonb) "
                + "AND cast(? as jsonb) IS NOT DISTINCT FROM cast(? as jsonb) "
                + "AND cast(? as jsonb) IS NOT DISTINCT FROM cast(? as jsonb) "
                + "AND cast(? as jsonb) IS NOT DISTINCT FROM cast(? as jsonb)", Boolean.class,
                stored.changedFields(), changed, stored.previousValues(), previous, stored.newValues(), next,
                stored.metadata(), metadata));
    }

    private static void assertSqlState(Throwable failure, String expected)
    {
        Throwable cause = failure;
        while (cause != null)
        {
            if (cause instanceof SQLException sql && expected.equals(sql.getSQLState()))
            {
                return;
            }
            cause = cause.getCause();
        }
        throw new AssertionError("Expected PostgreSQL SQLSTATE " + expected);
    }

    @AfterAll
    void removeOnlyOwnedFixtures()
    {
        // Retire routed pooled sessions before removing namespaces; DirtiesContext closes remaining Spring beans.
        dataSource.close();
        // Keep platform allocator evidence until every ownership-checked tenant schema drop has succeeded.
        resources.close();
        PLATFORM.close();
    }

    /** Imports only a test business boundary; the audit service and persistence remain production beans. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ReadOnlyConfiguration
    {
        @Bean
        ReadOnlyActions auditReadOnlyActions(EntityManagerFactory entityManagers)
        {
            return new ReadOnlyActions(entityManagers);
        }
    }

    /** A real Spring transaction proxy reproducing read-only business entry points. */
    static class ReadOnlyActions
    {
        /** Production tenant entity manager factory. */
        private final EntityManagerFactory entityManagers;

        ReadOnlyActions(EntityManagerFactory entityManagers)
        {
            this.entityManagers = entityManagers;
        }

        @Transactional(transactionManager = "transactionManager", readOnly = true)
        public void execute(Consumer<EntityManager> action)
        {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
            assertNotNull(em);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals("on", em.createNativeQuery("SHOW transaction_read_only", String.class).getSingleResult());
            action.accept(em);
        }
    }
}
