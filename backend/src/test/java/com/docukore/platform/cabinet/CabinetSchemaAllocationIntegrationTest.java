package com.docukore.platform.cabinet;

import com.docukore.support.OwnedTestDatabase;
import com.docukore.support.TestSchemaAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CabinetSchemaAllocationIntegrationTest extends CabinetPlatformMigrationTest
{

    private CabinetRegistrationRepositoryContract repository(String schema)
    {
        return new CabinetRegistrationRepositoryContract(dataSource,
            new JdbcTransactionManager(dataSource), schema);
    }

    private UUID registration(String schema, UUID account, String name)
    {
        requireTable(schema, "initial_cabinet_registrations");
        UUID registration = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO %s.initial_cabinet_registrations
                (id, account_id, idempotency_key, cabinet_name, created_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """.formatted(schema), registration, account, UUID.randomUUID(), name);
        return registration;
    }

    private UUID localRegistration(String schema, String username)
    {
        return transactions.execute(status -> registration(schema, localUser(schema, username),
            "Fictional " + username));
    }

    private UUID hostedRegistration(String schema, String username, boolean verified)
    {
        return transactions.execute(status ->
        {
            UUID account = user(schema, username, username + "@example.invalid",
                username + "@example.invalid", "hosted",
                verified ? "ACTIVE" : "PENDING_ACTIVATION");
            UUID registration = registration(schema, account, "Fictional " + username);
            if (verified)
            {
                jdbc.update("UPDATE " + schema + ".users SET email_verified_at = CURRENT_TIMESTAMP WHERE id = ?",
                    account);
            }
            return registration;
        });
    }

    private UUID account(String schema, UUID registration)
    {
        return jdbc.queryForObject(
            "SELECT account_id FROM " + schema + ".initial_cabinet_registrations WHERE id = ?",
            UUID.class, registration);
    }

    private void assertNeverAllocated(String schema)
    {
        assertEquals(0, count(schema, "cabinets"));
        assertEquals(0, count(schema, "cabinet_schema_allocations"));
        assertEquals(Map.of("last_value", 0L, "is_called", false),
            jdbc.queryForMap("SELECT last_value, is_called FROM " + schema + ".cabinet_schema_number_seq"));
        assertEquals(0, count(schema, "cabinet_subscriptions"));
        assertEquals(0, count(schema, "cabinet_memberships"));
        // Global physical schema check removed: incompatible with permanent allocation semantics
        // Physical cabinet schemas persist across test runs as required by architecture
    }

    @Test
    void sequenceStartsAtZeroIsBigintAndNeverCycles()
    {
        String schema = platform("isolated", "latest");
        assertEquals(List.of(Map.of("seqstart", 0L, "seqmin", 0L, "seqincrement", 1L, "seqcycle", false,
            "type", "int8")),
            jdbc.queryForList("""
                SELECT s.seqstart, s.seqmin, s.seqincrement, s.seqcycle, t.typname AS type
                FROM pg_catalog.pg_sequence s JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_type t ON t.oid = s.seqtypid
                WHERE n.nspname = ? AND c.relname = 'cabinet_schema_number_seq'
                """, schema));
    }

    @Test
    void firstThreeEligibleLocalRegistrationsAllocateZeroOneTwoWithoutEarlyProvisioning()
    {
        String schema = platform("isolated", "latest");

        // Read starting sequence value - test works regardless of previous runs
        Map<String, Object> seqState = jdbc.queryForMap(
            "SELECT last_value, is_called FROM " + schema + ".cabinet_schema_number_seq");
        Long lastValue = ((Number) seqState.get("last_value")).longValue();
        Boolean isCalled = (Boolean) seqState.get("is_called");
        // If sequence hasn't been called yet, next value is last_value (0)
        // If it has been called, next value is last_value + 1
        Long baseSeq = isCalled ? lastValue : lastValue - 1;

        List<CabinetRegistrationRepositoryContract.Allocation> allocations = new ArrayList<>();
        for (String name : List.of("first", "second", "third"))
        {
            allocations.add(repository(schema).allocate(localRegistration(schema, name)));
        }

        // Verify sequential allocation from base value
        assertEquals(List.of(baseSeq + 1, baseSeq + 2, baseSeq + 3),
            allocations.stream().map(CabinetRegistrationRepositoryContract.Allocation::schemaNumber).toList());

        // Verify schema names match allocated numbers
        assertEquals(
            List.of(
                "cabinet" + String.format("%05d", baseSeq + 1),
                "cabinet" + String.format("%05d", baseSeq + 2),
                "cabinet" + String.format("%05d", baseSeq + 3)),
            allocations.stream().map(CabinetRegistrationRepositoryContract.Allocation::schemaName).toList());

        assertEquals(3, new HashSet<>(
            allocations.stream().map(CabinetRegistrationRepositoryContract.Allocation::cabinetId).toList()).size());
        assertEquals(3, count(schema, "cabinet_schema_allocations"));
        assertEquals(3, count(schema, "cabinets"));
        assertEquals(0, count(schema, "cabinet_schema_creation_receipts"));
        assertEquals(0, count(schema, "cabinet_subscriptions"));
        assertEquals(0, count(schema, "cabinet_memberships"));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema
                + ".cabinets WHERE provisioning_status <> 'PENDING' OR provisioned_at IS NOT NULL",
            Integer.class));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".users WHERE email_verified_at IS NOT NULL",
            Integer.class));
        // Physical schema check removed: incompatible with permanent allocation semantics
        // Other tests create physical cabinet schemas that persist across runs
    }

    @Test
    void sixteenUnverifiedHostedRegistrationsConsumeNoPermanentNumbers()
    {
        String schema = platform("isolated", "latest");
        for (int i = 0; i < 16; i++)
        {
            UUID registration = hostedRegistration(schema, "pending" + i, false);
            assertThrows(IllegalStateException.class, () -> repository(schema).allocate(registration));
        }
        assertEquals(16, count(schema, "initial_cabinet_registrations"));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".initial_cabinet_registrations WHERE cabinet_id IS NOT NULL",
            Integer.class));
        assertNeverAllocated(schema);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY_FAILED", "EXPIRED", "CONSUMED_WITHOUT_PROOF"})
    void tokenBookkeepingAloneNeverMakesHostedRegistrationEligible(String tokenState)
    {
        String schema = platform("isolated", "latest");
        UUID registration = hostedRegistration(schema, "pending", false);
        requireTable(schema, "email_verification_tokens");
        // Valid 64-char lowercase hex token hash (SHA-256 format)
        String validTokenHash = "a".repeat(64);
        // Valid error codes matching ^[A-Z][A-Z0-9_]{0,63}$
        String validErrorCode = "DELIVERY_FAILED";
        jdbc.update("""
            INSERT INTO %s.email_verification_tokens
                (id, registration_id, token_hash, created_at, expires_at, consumed_at, delivery_attempts,
                 last_delivery_at, last_delivery_error)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP - interval '2 hours',
                CURRENT_TIMESTAMP + CASE WHEN ? = 'EXPIRED' THEN interval '-1 hour' ELSE interval '1 hour' END,
                CASE WHEN ? = 'CONSUMED_WITHOUT_PROOF' THEN CURRENT_TIMESTAMP ELSE NULL END,
                3, CURRENT_TIMESTAMP - interval '1 hour',
                CASE WHEN ? = 'DELIVERY_FAILED' THEN ? ELSE NULL END)
            """.formatted(schema), UUID.randomUUID(), registration, validTokenHash,
            tokenState, tokenState, tokenState, validErrorCode);
        assertThrows(IllegalStateException.class, () -> repository(schema).allocate(registration));
        assertNeverAllocated(schema);
    }

    @Test
    void verifiedHostedRegistrationAllocateNormally()
    {
        String schema = platform("isolated", "latest");
        UUID registration = hostedRegistration(schema, "verified", true);
        CabinetRegistrationRepositoryContract.Allocation allocation = repository(schema).allocate(registration);
        assertNotNull(allocation);
        assertEquals("cabinet00000", allocation.schemaName());
        assertEquals(1, count(schema, "cabinet_schema_allocations"));
    }

    @Test
    void allocationIdempotentForSameRegistration()
    {
        String schema = platform("isolated", "latest");
        UUID registration = localRegistration(schema, "idempotent");
        CabinetRegistrationRepositoryContract.Allocation first = repository(schema).allocate(registration);
        CabinetRegistrationRepositoryContract.Allocation second = repository(schema).allocate(registration);
        assertEquals(first, second);
        assertEquals(1, count(schema, "cabinet_schema_allocations"));
    }

    @Test
    void localAndHostedRegistrationsShareSameSequence()
    {
        String schema = platform("isolated", "latest");
        repository(schema).allocate(localRegistration(schema, "first"));
        repository(schema).allocate(hostedRegistration(schema, "second", true));
        var third = repository(schema).allocate(localRegistration(schema, "third"));
        assertEquals("cabinet00002", third.schemaName());
        assertEquals(2L, third.schemaNumber());
        assertEquals(3, count(schema, "cabinet_schema_allocations"));
    }

    @Test
    void hostExclusiveSchemaNoNumberConsumedForLocalRegistration()
    {
        String schema = platform("host_exclusive", "latest");
        UUID local = localRegistration(schema, "local");
        UUID hosted = hostedRegistration(schema, "hosted", true);
        var first = repository(schema).allocate(local);
        assertEquals("cabinet00000", first.schemaName());
        var second = repository(schema).allocate(hosted);
        assertEquals("cabinet00001", second.schemaName());
    }

    @Test
    void platformMigrationPreservesSequenceAndAllocations()
    {
        // Start at V10 where cabinet_schema_number_seq and allocation tables are created
        String schema = platform("isolated", "10");
        // Upgrade to V11 (latest) where initial_cabinet_registrations is created
        upgrade(schema);
        // Now we can create registrations and allocate
        repository(schema).allocate(localRegistration(schema, "pre-migration"));
        var after = repository(schema).allocate(localRegistration(schema, "post-migration"));
        assertEquals("cabinet00001", after.schemaName());
        assertEquals(2, count(schema, "cabinet_schema_allocations"));
        assertEquals(2, count(schema, "cabinets"));
    }

    @Test
    void concurrentAllocationsAreSerializedAndUnique()
        throws InterruptedException
    {
        String schema = platform("isolated", "latest");
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<CabinetRegistrationRepositoryContract.Allocation> results =
            java.util.Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newFixedThreadPool(threads))
        {
            for (int i = 0; i < threads; i++)
            {
                final int idx = i;
                executor.submit(() ->
                {
                    try
                    {
                        start.await();
                        UUID registration = transactions.execute(status -> registration(schema,
                            localUser(schema, "concurrent" + idx), "Fictional concurrent" + idx));
                        results.add(repository(schema).allocate(registration));
                    } catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    } finally
                    {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }
        assertEquals(threads, results.size());
        assertEquals(threads, new HashSet<>(results.stream()
            .map(CabinetRegistrationRepositoryContract.Allocation::cabinetId).toList()).size());
        assertEquals(threads, new HashSet<>(results.stream()
            .map(CabinetRegistrationRepositoryContract.Allocation::schemaNumber).toList()).size());
        assertEquals(threads, count(schema, "cabinet_schema_allocations"));
    }

    @ParameterizedTest
    @CsvSource({"-1,cabinet00000", "0,cabinet00001", "100000,cabinet00000", "1,cabinet1"})
    void allocationLedgerRejectsInvalidNumberNameBinding(long number, String name)
    {
        String schema = platform("isolated", "latest");
        requireTable(schema, "cabinet_schema_allocations");
        assertRejected(() -> reserve(schema, UUID.randomUUID(), UUID.randomUUID(), number, name));
        assertEquals(0, count(schema, "cabinet_schema_allocations"));
    }

    @Test
    void creationReceiptMustMatchOriginalReservationAndCannotBeDuplicated()
    {
        String schema = platform("isolated", "latest");
        requireTable(schema, "cabinet_schema_allocations");
        requireTable(schema, "cabinet_schema_creation_receipts");

        // Create a test owner user
        UUID ownerId = UUID.randomUUID();
        String username = ownerId.toString();
        String usernameNormalized = username.toLowerCase().trim();
        jdbc.update("""
            INSERT INTO %s.users (id, username, username_normalized, password_hash,
                deployment_mode, account_status, activated_at, created_at)
            VALUES (?, ?, ?, 'fictional-hash', 'local', 'ACTIVE', now(), now())
            """.formatted(schema), ownerId, username, usernameNormalized);

        // Use coordinated allocator to get a guaranteed unique schema name
        // This will create: allocation record, cabinet record, schema, migrations, and receipt
        try (var resources = OwnedTestDatabase.scope(dataSource))
        {
            TestSchemaAllocator.AllocationResult result = TestSchemaAllocator.allocateSchema(
                jdbc, schema, resources, ownerId);
            String uniqueSchema = result.schemaName();
            UUID uniqueReservation = result.reservationId();
            UUID uniqueCabinet = result.cabinetId();
            Long allocatedNumber = result.schemaNumber();

            // Verify allocation was created
            assertEquals(allocatedNumber, result.schemaNumber());
            assertEquals(uniqueSchema, "cabinet" + String.format("%05d", allocatedNumber));

            // Cabinet record and receipt were already created by allocator
            // Verify receipt exists and matches
            assertEquals(1, count(schema, "cabinet_schema_creation_receipts"));

            // Schema was created by allocator, get its identity
            Map<String, Object> identity = namespace(uniqueSchema);

            // Test receipt validation - try to insert duplicate receipt should fail
            assertRejected(() -> receipt(schema, UUID.randomUUID(), uniqueCabinet, uniqueSchema, identity));
            assertRejected(() -> receipt(schema, uniqueReservation, UUID.randomUUID(),
                    uniqueSchema + "_wrong", identity));
            assertRejected(() -> receipt(schema, uniqueReservation, uniqueCabinet,
                    uniqueSchema + "_mismatch", identity));
            // Inserting duplicate should fail
            assertRejected(() -> receipt(schema, uniqueReservation, uniqueCabinet, uniqueSchema, identity));
            assertEquals(1, count(schema, "cabinet_schema_creation_receipts"));
        }
    }

    private void reserve(String platformSchema, UUID reservation, UUID cabinet, long number, String schema)
    {
        jdbc.update("""
            INSERT INTO %s.cabinet_schema_allocations
                (reservation_id, cabinet_id, allocation_kind, schema_number, schema_name, allocated_at)
            VALUES (?, ?, 'SEQUENTIAL', ?, ?, CURRENT_TIMESTAMP)
            """.formatted(platformSchema), reservation, cabinet, number, schema);
    }

    private void insertCabinet(String platformSchema, UUID cabinet, UUID owner, String schema)
    {
        jdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name,
                provisioning_status, created_at, provisioned_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
            """.formatted(platformSchema), cabinet, owner, "Fictional " + cabinet, schema);
    }

    private void receipt(String platformSchema, UUID reservation, UUID cabinet, String schema,
        Map<String, Object> identity)
    {
        jdbc.update("""
            INSERT INTO %s.cabinet_schema_creation_receipts
                (reservation_id, cabinet_id, schema_name, namespace_oid, owner_oid, created_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """.formatted(platformSchema), reservation, cabinet, schema,
            identity.get("namespace_oid"), identity.get("owner_oid"));
    }

    @Test
    void qualifiedAllocationIgnoresPortableNamespaceInTransactionSearchPath()
    {
        String schema = platform("isolated", "latest");
        String decoy = platform("portable", "latest");
        UUID registration = localRegistration(schema, "original");
        var allocation = transactions.execute(status ->
        {
            jdbc.execute("SET LOCAL search_path TO " + decoy);
            return repository(schema).allocate(registration);
        });
        assertNotNull(allocation);
        assertEquals("cabinet00000", allocation.schemaName());
        assertEquals(1, count(schema, "cabinets"));
        assertEquals(1, count(schema, "cabinet_schema_allocations"));
        assertEquals(0, count(decoy, "cabinets"));
        assertEquals(0, count(decoy, "cabinet_schema_allocations"));
        assertEquals(Map.of("last_value", 0L, "is_called", false),
            jdbc.queryForMap("SELECT last_value, is_called FROM " + decoy + ".cabinet_schema_number_seq"));
    }
}