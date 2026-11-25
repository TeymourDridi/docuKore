package com.docukore.platform.cabinet;

import com.docukore.support.OwnedTestDatabase;
import com.docukore.support.SharedPostgresTest;
import com.docukore.support.TestSchemaAllocator;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused tests for OwnedTestDatabase ownership provenance mechanism.
 * Verifies that registerExisting() requires a valid creation receipt from the authorized allocator,
 * and that arbitrary pre-existing schemas cannot be adopted.
 */
class OwnedTestDatabaseProvenanceTest extends CabinetPlatformMigrationTest
{

    @Test
    void authorizedAllocatorCreatedSchemaCanBeRegistered()
    {
        String platformSchema = platform("isolated", "latest");
        UUID ownerId = UUID.randomUUID();
        String username = ownerId.toString();
        String usernameNormalized = username.toLowerCase().trim();
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(platformSchema), ownerId, username, usernameNormalized);

        try (var resources = OwnedTestDatabase.scope(dataSource))
        {
            // Create schema through authorized allocator (holds DB-wide lock, inserts receipt)
            TestSchemaAllocator.AllocationResult result = TestSchemaAllocator.allocateSchema(
                jdbc, platformSchema, resources, ownerId);

            // Verify schema is registered in resources (implicitly tested by allocation success)
            // If registration failed, allocateSchema would have thrown

            // Verify receipt exists and matches
            String qualifiedPlatformSchema = '"' + platformSchema + '"';
            long receiptCount = jdbc.queryForObject(
                "SELECT count(*) FROM " + qualifiedPlatformSchema
                + ".cabinet_schema_creation_receipts WHERE reservation_id = ?",
                Long.class, result.reservationId());
            assertEquals(1, receiptCount);

            // Verify identity matches by calling requireIdentity (which validates against DB)
            resources.requireIdentity(result.schemaName());
        }
    }

    @Test
    void preExistingSchemaWithoutReceiptCannotBeRegistered()
    {
        String platformSchema = platform("isolated", "latest");
        try (var creator = OwnedTestDatabase.scope(dataSource))
        {
            String schema = creator.create("unreceipted");
            var identity = namespaceIdentity(schema);
            assertAdoptionDenied(platformSchema, identity, UUID.randomUUID());
            creator.requireIdentity(schema);
        }
    }

    @Test
    void preExistingSchemaWithWrongReceiptCannotBeRegistered()
    {
        String platformSchema = platform("isolated", "latest");
        UUID ownerId = localUser(platformSchema, "fixture_owner");
        try (var creator = OwnedTestDatabase.scope(dataSource))
        {
            var allocated = TestSchemaAllocator.allocateSchema(jdbc, platformSchema, creator, ownerId);
            String schema = creator.create("wrong_receipt");
            var identity = namespaceIdentity(schema);
            assertAdoptionDenied(platformSchema, identity, allocated.reservationId());
            creator.requireIdentity(schema);
            // Even the exact receipt and live identity cannot authorize a different scope to adopt the tenant.
            var allocatedIdentity = namespaceIdentity(allocated.schemaName());
            assertAdoptionDenied(platformSchema, allocatedIdentity, allocated.reservationId());
            creator.requireIdentity(allocated.schemaName());
        }
    }

    private OwnedTestDatabase.NamespaceIdentity namespaceIdentity(String schema)
    {
        var namespace = namespace(schema);
        return new OwnedTestDatabase.NamespaceIdentity(schema, ((Number) namespace.get("namespace_oid")).longValue(),
            ((Number) namespace.get("owner_oid")).longValue());
    }

    private void assertAdoptionDenied(String platformSchema, OwnedTestDatabase.NamespaceIdentity identity,
                                      UUID reservationId)
    {
        String schema = identity.schemaName();
        try (var other = OwnedTestDatabase.scope(dataSource))
        {
            var failure = assertThrows(IllegalStateException.class,
                () -> other.registerExisting(platformSchema, schema, identity, reservationId));
            assertTrue(failure.getMessage().contains("No scope-authorized allocation"));
            assertThrows(IllegalStateException.class, () -> other.requireIdentity(schema));
            assertThrows(IllegalStateException.class, () -> other.drop(schema));
        }
    }

    @Test
    void replacedNamespaceCannotBeDeletedDuringCleanup()
    {
        String platformSchema = platform("isolated", "latest");
        UUID ownerId = UUID.randomUUID();
        String username = ownerId.toString();
        String usernameNormalized = username.toLowerCase().trim();
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(platformSchema), ownerId, username, usernameNormalized);

        String schemaName;
        var resources = OwnedTestDatabase.scope(dataSource);
        try
        {
            TestSchemaAllocator.AllocationResult result = TestSchemaAllocator.allocateSchema(
                jdbc, platformSchema, resources, ownerId);
            schemaName = result.schemaName();
        } finally
        {
            // Don't close resources yet - we need the schema to still be tracked
        }

        // Simulate namespace replacement: drop and recreate (different OID)
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement())
            {
                stmt.execute("DROP SCHEMA " + schemaName + " CASCADE");
                stmt.execute("CREATE SCHEMA " + schemaName);
            }
            conn.commit();
        } catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        // Try cleanup - should fail because OID/owner changed
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            resources.drop(schemaName));
        assertTrue(ex.getMessage().contains("namespace OID/owner changed"));

        // Cleanup the replaced schema manually (since drop failed)
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            try (var stmt = conn.createStatement())
            {
                stmt.execute("DROP SCHEMA " + schemaName + " CASCADE");
            }
        } catch (Exception ignored)
        {
        }

        // Close resources (won't try to drop already-dropped schema)
        resources.close();
    }

    @Test
    void ownershipRegistrationAndCleanupSafeOnFailure()
    {
        String platformSchema = platform("isolated", "latest");
        String missing = "missing_" + UUID.randomUUID().toString().replace("-", "");
        assertAdoptionDenied(platformSchema, new OwnedTestDatabase.NamespaceIdentity(missing, 123, 456),
            UUID.randomUUID());

        try (var creator = OwnedTestDatabase.scope(dataSource))
        {
            String schema = creator.create("ownership_failure");
            var identity = namespaceIdentity(schema);
            var wrongIdentity = new OwnedTestDatabase.NamespaceIdentity(
                schema, identity.namespaceOid() + 1, identity.ownerOid());
            assertAdoptionDenied(platformSchema, wrongIdentity, UUID.randomUUID());
            creator.requireIdentity(schema);
        }
    }

    @Test
    void sessionLockHeldUntilOwnershipRegistrationCompletes() throws InterruptedException
    {
        String platformSchema = platform("isolated", "latest");
        UUID ownerId1 = UUID.randomUUID();
        String username1 = ownerId1.toString();
        String usernameNormalized1 = username1.toLowerCase().trim();
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(platformSchema), ownerId1, username1, usernameNormalized1);

        UUID ownerId2 = UUID.randomUUID();
        String username2 = ownerId2.toString();
        String usernameNormalized2 = username2.toLowerCase().trim();
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(platformSchema), ownerId2, username2, usernameNormalized2);

        CountDownLatch allowRegistration = new CountDownLatch(1);
        CountDownLatch allocator2Started = new CountDownLatch(1);
        CountDownLatch allocator1Started = new CountDownLatch(1);
        CountDownLatch allocator1ReachedRegisterExisting = new CountDownLatch(1);
        CountDownLatch allocator1ReleasedLock = new CountDownLatch(1);
        AtomicReference<TestSchemaAllocator.AllocationResult> allocator1ResultRef = new AtomicReference<>();
        AtomicReference<TestSchemaAllocator.AllocationResult> allocator2ResultRef = new AtomicReference<>();
        AtomicReference<Throwable> allocator1Error = new AtomicReference<>();
        AtomicReference<Throwable> allocator2Error = new AtomicReference<>();

        // Test hook to signal when allocator 1 is about to call registerExisting
        CountDownLatch onRegisterExistingSignal = new CountDownLatch(1);
        TestSchemaAllocator.setOnRegisterExistingHook(() ->
        {
            onRegisterExistingSignal.countDown();
            try
            {
                assertTrue(allowRegistration.await(30, TimeUnit.SECONDS), "Registration barrier must be released");
            } catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError("Registration barrier interrupted");
            }
        });

        // Allocator 1: starts first, will hold session lock through registerExisting
        Thread allocator1 = new Thread(() ->
        {
            try (var resources = OwnedTestDatabase.scope(dataSource))
            {
                allocator1Started.countDown();
                TestSchemaAllocator.AllocationResult result = TestSchemaAllocator.allocateSchema(
                    jdbc, platformSchema, resources, ownerId1);
                allocator1ReachedRegisterExisting.countDown();
                // Lock should still be held here until allocateSchema returns
                allocator1ResultRef.set(result);
            } catch (Throwable t)
            {
                allocator1Error.set(t);
            } finally
            {
                allocator1ReleasedLock.countDown();
            }
        });

        // Allocator 2: tries to run while allocator 1 holds the lock
        Thread allocator2 = new Thread(() ->
        {
            try
            {
                assertTrue(onRegisterExistingSignal.await(30, TimeUnit.SECONDS),
                    "First allocator must hold the registration lock");
                allocator2Started.countDown();
                try (var resources = OwnedTestDatabase.scope(dataSource))
                {
                    TestSchemaAllocator.AllocationResult result = TestSchemaAllocator.allocateSchema(
                        jdbc, platformSchema, resources, ownerId2);
                    allocator2ResultRef.set(result);
                }
            } catch (Throwable t)
            {
                allocator2Error.set(t);
            }
        });

        allocator1.start();
        allocator2.start();
        try
        {
            assertTrue(onRegisterExistingSignal.await(30, TimeUnit.SECONDS),
                "Allocator 1 should reach registerExisting call");
            assertTrue(allocator2Started.await(30, TimeUnit.SECONDS), "Allocator 2 should attempt allocation");
            Thread.sleep(1000);
            assertNull(allocator2Error.get(), "Allocator 2 should not have failed yet (should be blocked)");
            assertNull(allocator2ResultRef.get(), "Allocator 2 should not have completed allocation yet");
            allowRegistration.countDown();
            assertTrue(allocator1ReleasedLock.await(30, TimeUnit.SECONDS),
                "Allocator 1 should complete and release lock");
            allocator2.join(30_000);
            assertNull(allocator1Error.get(), "Allocator 1 should not have errored");
            assertNotNull(allocator1ResultRef.get(), "Allocator 1 should have returned a result");
            assertNull(allocator2Error.get(), "Allocator 2 should succeed after lock release");
            assertNotNull(allocator2ResultRef.get(), "Allocator 2 should complete after lock release");
        } finally
        {
            allowRegistration.countDown();
            TestSchemaAllocator.setOnRegisterExistingHook(null);
            allocator1.join(30_000);
            allocator2.join(30_000);
        }
    }
}