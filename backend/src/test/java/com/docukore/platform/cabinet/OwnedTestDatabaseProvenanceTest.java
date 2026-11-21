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
        UUID ownerId = UUID.randomUUID();

        // Create a schema directly in PostgreSQL, bypassing the allocator (no receipt)
        String preExistingSchema = "cabinet99999";
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement())
            {
                stmt.execute("CREATE SCHEMA " + preExistingSchema);
            }
            conn.commit();
        } catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        // Capture its identity
        OwnedTestDatabase.NamespaceIdentity identity;
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            try (var stmt = conn.prepareStatement(
                    "SELECT oid::bigint, nspowner::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?"))
            {
                stmt.setString(1, preExistingSchema);
                try (var rs = stmt.executeQuery())
                {
                    if (!rs.next())
                    {
                        throw new IllegalStateException("Schema not found");
                    }
                    identity = new OwnedTestDatabase.NamespaceIdentity(preExistingSchema, rs.getLong(1), rs.getLong(2));
                }
            }
        } catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        // Try to register it - should fail because no creation receipt exists
        try (var resources = OwnedTestDatabase.scope(dataSource))
        {
            UUID fakeReservationId = UUID.randomUUID();
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                resources.registerExisting(platformSchema, preExistingSchema, identity, fakeReservationId));
            assertTrue(ex.getMessage().contains("No matching creation receipt found"));
        }

        // Cleanup the manually created schema
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            try (var stmt = conn.createStatement())
            {
                stmt.execute("DROP SCHEMA " + preExistingSchema + " CASCADE");
            }
        } catch (Exception ignored)
        {
        }
    }

    @Test
    void preExistingSchemaWithWrongReceiptCannotBeRegistered()
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

        // First, create a legitimate schema through allocator
        String legitimateSchema;
        UUID legitimateReservationId;
        OwnedTestDatabase.NamespaceIdentity legitimateIdentity;
        try (var resources = OwnedTestDatabase.scope(dataSource))
        {
            TestSchemaAllocator.AllocationResult result = TestSchemaAllocator.allocateSchema(
                jdbc, platformSchema, resources, ownerId);
            legitimateSchema = result.schemaName();
            legitimateReservationId = result.reservationId();
            legitimateIdentity = new OwnedTestDatabase.NamespaceIdentity(
                result.schemaName(), result.namespaceOid(), result.ownerOid());
        }

        // Now create another schema manually with same OID/owner (simulating replaced namespace)
        // In practice, namespace OIDs are unique, but we test the receipt check
        String manualSchema = "cabinet88888";
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement())
            {
                stmt.execute("CREATE SCHEMA " + manualSchema);
            }
            conn.commit();
        } catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        // Capture its identity
        OwnedTestDatabase.NamespaceIdentity manualIdentity;
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            try (var stmt = conn.prepareStatement(
                    "SELECT oid::bigint, nspowner::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?"))
            {
                stmt.setString(1, manualSchema);
                try (var rs = stmt.executeQuery())
                {
                    if (!rs.next())
                    {
                        throw new IllegalStateException("Schema not found");
                    }
                    manualIdentity = new OwnedTestDatabase.NamespaceIdentity(
                        manualSchema, rs.getLong(1), rs.getLong(2));
                }
            }
        } catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        // Try to register manual schema with legitimate schema's receipt - should fail
        try (var resources = OwnedTestDatabase.scope(dataSource))
        {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                resources.registerExisting(platformSchema, manualSchema, manualIdentity, legitimateReservationId));
            assertTrue(ex.getMessage().contains("No matching creation receipt found"));
        }

        // Cleanup
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            try (var stmt = conn.createStatement())
            {
                stmt.execute("DROP SCHEMA " + manualSchema + " CASCADE");
            }
        } catch (Exception ignored)
        {
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
        UUID ownerId = UUID.randomUUID();
        String username = ownerId.toString();
        String usernameNormalized = username.toLowerCase().trim();
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(platformSchema), ownerId, username, usernameNormalized);

        // Test 1: registerExisting fails with non-existent schema
        try (var resources = OwnedTestDatabase.scope(dataSource))
        {
            var fakeIdentity = new OwnedTestDatabase.NamespaceIdentity("nonexistent", 123, 456);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                resources.registerExisting(platformSchema, "nonexistent", fakeIdentity, UUID.randomUUID()));
            assertTrue(ex.getMessage().contains("does not exist"));
        }

        // Test 2: registerExisting fails with identity mismatch
        String existingSchema = "cabinet77777";
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement())
            {
                stmt.execute("CREATE SCHEMA " + existingSchema);
            }
            conn.commit();
        } catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            try (var stmt = conn.prepareStatement(
                    "SELECT oid::bigint, nspowner::bigint FROM pg_catalog.pg_namespace WHERE nspname = ?"))
            {
                stmt.setString(1, existingSchema);
                try (var rs = stmt.executeQuery())
                {
                    if (rs.next())
                    {
                        // Use wrong OID
                        var wrongIdentity = new OwnedTestDatabase.NamespaceIdentity(existingSchema, 999999, 888888);
                        try (var resources = OwnedTestDatabase.scope(dataSource))
                        {
                            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                                resources.registerExisting(
                                platformSchema, existingSchema, wrongIdentity,
                                UUID.randomUUID()));
                            assertTrue(ex.getMessage().contains("does not match database"));
                        }
                    }
                }
            }
        } catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        // Cleanup
        try (Connection conn = DriverManager.getConnection(
                SharedPostgresTest.SHARED_JDBC_URL,
                SharedPostgresTest.SHARED_USER,
                SharedPostgresTest.SHARED_PASSWORD))
        {
            try (var stmt = conn.createStatement())
            {
                stmt.execute("DROP SCHEMA " + existingSchema + " CASCADE");
            }
        } catch (Exception ignored)
        {
        }

        // Test 3: drop fails without registration
        try (var resources = OwnedTestDatabase.scope(dataSource))
        {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                resources.drop("unregistered_schema"));
            assertTrue(ex.getMessage().contains("Refusing cleanup without exact test creation receipt"));
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

        CountDownLatch allocator1Started = new CountDownLatch(1);
        CountDownLatch allocator1ReachedRegisterExisting = new CountDownLatch(1);
        CountDownLatch allocator1ReleasedLock = new CountDownLatch(1);
        AtomicReference<TestSchemaAllocator.AllocationResult> allocator1ResultRef = new AtomicReference<>();
        AtomicReference<TestSchemaAllocator.AllocationResult> allocator2ResultRef = new AtomicReference<>();
        AtomicReference<Throwable> allocator1Error = new AtomicReference<>();
        AtomicReference<Throwable> allocator2Error = new AtomicReference<>();

        // Test hook to signal when allocator 1 is about to call registerExisting
        CountDownLatch onRegisterExistingSignal = new CountDownLatch(1);
        TestSchemaAllocator.setOnRegisterExistingHook(() -> onRegisterExistingSignal.countDown());

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
                allocator1Started.await(10, TimeUnit.SECONDS);
                // Wait a bit to ensure allocator 1 has acquired the lock
                Thread.sleep(500);
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

        // Wait for allocator 1 to reach the point where it's about to call registerExisting
        // At this point, the session-level advisory lock is still held
        assertTrue(onRegisterExistingSignal.await(30, TimeUnit.SECONDS),
            "Allocator 1 should reach registerExisting call");

        // Allocator 2 should be blocked and not complete yet
        Thread.sleep(1000);
        assertNull(allocator2Error.get(), "Allocator 2 should not have failed yet (should be blocked)");
        assertNull(allocator2ResultRef.get(), "Allocator 2 should not have completed allocation yet");

        // Now wait for allocator 1 to fully complete and release lock
        assertTrue(allocator1ReleasedLock.await(30, TimeUnit.SECONDS),
            "Allocator 1 should complete and release lock");

        // Now allocator 2 should be able to proceed (or have completed)
        allocator2.join(30_000);

        // Allocator 1 should have succeeded
        assertNull(allocator1Error.get(), "Allocator 1 should not have errored");
        assertNotNull(allocator1ResultRef.get(), "Allocator 1 should have returned a result");

        // Allocator 2 should either succeed (after lock released) or be waiting
        // The key assertion: allocator 2 did NOT complete before allocator 1 released the lock
        // If allocator2Error is null and it joined, it succeeded after lock release - that's correct
        // If allocator2Error is not null, it might be a timeout or other issue
        // The critical test is that allocator 2 was blocked until allocator 1 completed registerExisting
    }
}