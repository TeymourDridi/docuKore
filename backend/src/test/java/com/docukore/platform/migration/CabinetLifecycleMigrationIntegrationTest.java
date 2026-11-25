package com.docukore.platform.migration;

import com.docukore.platform.cabinet.CabinetPlatformMigrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CabinetLifecycleMigrationIntegrationTest extends CabinetPlatformMigrationTest
{

    @ParameterizedTest
    @ValueSource(strings = {"public", "portable"})
    void freshPlatformHasTwelveMigrationsWithoutAllocatingCabinets(String kind)
    {
        String schema = platform(kind, "latest");
        assertTwelveVersions(schema);
        requireSequence(schema);
        requireTable(schema, "cabinet_schema_allocations");
        requireTable(schema, "cabinet_schema_creation_receipts");
        requireTable(schema, "cabinet_legacy_schema_observations");
        requireTable(schema, "initial_cabinet_registrations");
        requireTable(schema, "email_verification_tokens");
        requireTable(schema, "cabinet_subscriptions");
        assertEquals(0, count(schema, "cabinets"));
        assertEquals(0, count(schema, "cabinet_schema_allocations"));
        assertEquals(0, count(schema, "cabinet_schema_creation_receipts"));
        assertEquals(0, count(schema, "cabinet_subscriptions"));
        assertEquals(Map.of("last_value", 0L, "is_called", false),
            jdbc.queryForMap("SELECT last_value, is_called FROM " + schema + ".cabinet_schema_number_seq"));
        platformFlyway(schema, "latest").validate();
    }

    @ParameterizedTest
    @ValueSource(strings = "portable")
    void populatedV9UpgradePreservesAllHistoriesDataAndLegacyCatalogIdentity(String kind)
    {
        String schema = platform(kind, "9");
        UUID owner = localUser(schema, "legacy-owner");
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        for (UUID cabinet : List.of(first, second))
        {
            String tenant = legacyName(cabinet);
            String cabinetName = "Fictional cabinet " + cabinet.toString();
            // At V9, only cabinets table exists. Insert legacy cabinet data without allocations table.
            jdbc.update("""
                INSERT INTO %s.cabinets (id, owner_id, name, schema_name,
                    provisioning_status, created_at, provisioned_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                """.formatted(schema), cabinet, owner, cabinetName, tenant);
            // Ensure schema exists for legacy test - use fresh UUID per scenario
            // Physical schema creation required for legacy migration evidence
            jdbc.execute("DROP SCHEMA IF EXISTS " + tenant + " CASCADE");
            jdbc.execute("CREATE SCHEMA " + tenant);
            tenantFlyway(tenant).migrate();
            jdbc.update("INSERT INTO " + tenant + ".patients "
                + "(id, cabinet_id, patient_number, legal_given_name, legal_family_name, "
                + "birth_precision, created_at, updated_at) "
                + "VALUES (?, ?, 'P001', 'Fictional', 'Patient', 'UNKNOWN', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.fromString("10000000-0000-0000-0000-000000000001"), cabinet);
            jdbc.update("INSERT INTO " + schema + ".cabinet_memberships "
                + "(id, cabinet_id, user_id, membership_role, staff_type, status) "
                + "VALUES (?, ?, ?, 'OWNER', 'DOCTOR', 'ACTIVE')",
                UUID.randomUUID(), cabinet, owner);
        }
        List<Map<String, Object>> platformHistory = history(schema, "flyway_schema_history");
        List<Map<String, Object>> tenantHistory = history(legacyName(first), "flyway_schema_history_tenant");
        List<Map<String, Object>> secondHistory = history(legacyName(second), "flyway_schema_history_tenant");
        List<Map<String, Object>> cabinets = jdbc.queryForList(
            "SELECT id, owner_id, name, schema_name, provisioning_status, "
                + "created_at, provisioned_at FROM " + schema + ".cabinets ORDER BY id");
        List<Map<String, Object>> members = jdbc.queryForList(
            "SELECT * FROM " + schema + ".cabinet_memberships ORDER BY id");
        Map<String, Object> firstIdentity = namespace(legacyName(first));
        Map<String, Object> secondIdentity = namespace(legacyName(second));
        List<Map<String, Object>> patients = jdbc.queryForList(
            "SELECT * FROM " + legacyName(first) + ".patients");
        List<Map<String, Object>> secondPatients = jdbc.queryForList(
            "SELECT * FROM " + legacyName(second) + ".patients");

        upgrade(schema);

        assertTwelveVersions(schema);
        assertEquals(platformHistory, history(schema, "flyway_schema_history").subList(0, 9));
        assertEquals(tenantHistory, history(legacyName(first),
            "flyway_schema_history_tenant"));
        assertEquals(secondHistory, history(legacyName(second),
            "flyway_schema_history_tenant"));
        assertEquals(cabinets, jdbc.queryForList(
            "SELECT id, owner_id, name, schema_name, provisioning_status, "
                + "created_at, provisioned_at FROM " + schema + ".cabinets ORDER BY id"));
        assertEquals(members, jdbc.queryForList("SELECT * FROM " + schema + ".cabinet_memberships ORDER BY id"));
        assertEquals(patients, jdbc.queryForList(
            "SELECT * FROM " + legacyName(first) + ".patients"));
        assertEquals(secondPatients, jdbc.queryForList(
            "SELECT * FROM " + legacyName(second) + ".patients"));
        assertEquals(firstIdentity, namespace(legacyName(first)));
        assertEquals(secondIdentity, namespace(legacyName(second)));
        assertEquals(2, count(schema, "cabinet_schema_allocations"));
        assertEquals(2, count(schema, "cabinet_legacy_schema_observations"));
        assertEquals(0, count(schema, "cabinet_schema_creation_receipts"));
        assertEquals(0, count(schema, "cabinet_subscriptions"));
        assertEquals(0, count(schema, "initial_cabinet_registrations"));
        assertNull(jdbc.queryForObject(
            "SELECT email_verified_at FROM " + schema + ".users WHERE id = ?",
            Object.class, owner));
        for (UUID cabinet : List.of(first, second))
        {
            Map<String, Object> allocation = jdbc.queryForMap(
                "SELECT cabinet_id, allocation_kind, schema_number, schema_name "
                    + "FROM " + schema + ".cabinet_schema_allocations WHERE cabinet_id = ?", cabinet);
            assertEquals(cabinet, allocation.get("cabinet_id"));
            assertEquals("LEGACY", allocation.get("allocation_kind"));
            assertNull(allocation.get("schema_number"));
            assertEquals(legacyName(cabinet), allocation.get("schema_name"));
            assertEquals(namespace(legacyName(cabinet)),
                jdbc.queryForMap(
                    "SELECT namespace_oid::bigint, owner_oid::bigint FROM "
                        + schema + ".cabinet_legacy_schema_observations WHERE cabinet_id = ?",
                    cabinet));
        }
        platformFlyway(schema, "latest").validate();
        tenantFlyway(legacyName(first)).validate();
        tenantFlyway(legacyName(second)).validate();
    }

    private Flyway tenantFlyway(String schema)
    {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration/tenant").table("flyway_schema_history_tenant")
            .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).load();
    }

    @ParameterizedTest
    @ValueSource(strings = "portable")
    void missingLegacyNamespaceIsNotRecreatedOrGivenCreationEvidence(String kind)
    {
        String schema = platform(kind, "9");
        UUID owner = localUser(schema, "legacy-owner");
        UUID cabinet = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // Ensure the legacy schema does NOT exist (test scenario: missing schema)
        // Use DROP SCHEMA IF EXISTS to ensure clean state (permanent allocation semantics)
        jdbc.execute("DROP SCHEMA IF EXISTS " + legacyName(cabinet) + " CASCADE");
        // At V9, only cabinets table exists. Insert legacy cabinet data without allocations table.
        jdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name, provisioning_status, created_at, provisioned_at)
            VALUES (?, ?, 'Fictional cabinet', ?, 'ACTIVE', now(), now())
            """.formatted(schema), cabinet, owner, legacyName(cabinet));
        // Verify schema does not exist (fresh UUID per test run)
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname = ?",
            Integer.class, legacyName(cabinet)));
        upgrade(schema);
        requireTable(schema, "cabinet_schema_allocations");
        assertEquals("LEGACY", jdbc.queryForObject(
            "SELECT allocation_kind FROM " + schema
                + ".cabinet_schema_allocations WHERE cabinet_id = ?",
            String.class, cabinet));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname = ?",
            Integer.class, legacyName(cabinet)));
        assertEquals(0, count(schema, "cabinet_legacy_schema_observations"));
        assertEquals(0, count(schema, "cabinet_schema_creation_receipts"));
        assertEquals(0, count(schema, "cabinet_subscriptions"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "cabinet00000", "public",
        "cabinet_00000000000000000000000000000002",
        "cabinet_0000000000000000000000000000000A"
    })
    void invalidLegacyMappingStopsV10WithoutChangingOldData(String namespace)
    {
        String schema = platform("portable", "9");
        UUID cabinet = UUID.fromString("00000000-0000-0000-0000-000000000001");
        // At V9, only cabinets table exists. Insert legacy cabinet data without allocations table.
        jdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name, provisioning_status, created_at, provisioned_at)
            VALUES (?, ?, 'Fictional cabinet', ?, 'ACTIVE', now(), now())
            """.formatted(schema), cabinet, localUser(schema, "owner"), namespace);
        List<Map<String, Object>> before = history(schema, "flyway_schema_history");
        assertThrows(FlywayException.class, () -> upgrade(schema));
        assertEquals(before, history(schema, "flyway_schema_history"));
        assertEquals(namespace, jdbc.queryForObject(
            "SELECT schema_name FROM " + schema + ".cabinets WHERE id = ?",
            String.class, cabinet));
    }

    @ParameterizedTest
    @ValueSource(strings = "portable")
    void canonicalEmailCollisionStopsV11WithoutChoosingWinner(String kind)
    {
        String schema = platform(kind, "10");
        UUID first = user(schema, "legacy-one", " Fictional@Example.invalid ", "old-one", "hosted", "ACTIVE");
        UUID second = user(schema, "legacy-two", "fictional@example.invalid", "old-two", "hosted", "LOCKED");
        List<Map<String, Object>> users = jdbc.queryForList("SELECT * FROM " + schema + ".users ORDER BY id");
        assertThrows(FlywayException.class, () -> upgrade(schema));
        assertEquals(users, jdbc.queryForList(
            "SELECT id, username, username_normalized, email, email_normalized, "
                + "password_hash, full_name, deployment_mode, created_at, activated_at, "
                + "last_login_at, account_status FROM "
                + schema + ".users ORDER BY id"));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version = '11' AND success",
            Integer.class));
        assertEquals("old-one", jdbc.queryForObject(
            "SELECT email_normalized FROM " + schema + ".users WHERE id = ?",
            String.class, first));
        assertEquals("old-two", jdbc.queryForObject(
            "SELECT email_normalized FROM " + schema + ".users WHERE id = ?",
            String.class, second));
    }

    @Test
    void emailRealignmentPreservesLegacyLoginStatusAndDoesNotFabricateProof()
    {
        String schema = platform("portable", "9");
        UUID hosted = user(schema, "legacy-not-an-email",
            " Fictional@Example.invalid ", "wrong-key", "hosted", "ACTIVE");
        UUID local = localUser(schema, "local-owner");
        UUID suspended = user(schema, "legacy-suspended",
            "Other@Example.invalid", null, "hosted", "SUSPENDED");
        List<Map<String, Object>> identity = jdbc.queryForList(
            "SELECT id, username, username_normalized, password_hash, "
                + "account_status, activated_at FROM " + schema + ".users ORDER BY id");
        upgrade(schema);
        assertEquals("fictional@example.invalid", jdbc.queryForObject(
            "SELECT email_normalized FROM " + schema + ".users WHERE id = ?",
            String.class, hosted));
        assertEquals("other@example.invalid", jdbc.queryForObject(
            "SELECT email_normalized FROM " + schema + ".users WHERE id = ?",
            String.class, suspended));
        assertNull(jdbc.queryForObject(
            "SELECT email_normalized FROM " + schema + ".users WHERE id = ?",
            String.class, local));
        assertEquals(identity, jdbc.queryForList(
            "SELECT id, username, username_normalized, password_hash, "
                + "account_status, activated_at FROM "
                + schema + ".users ORDER BY id"));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM " + schema + ".users WHERE email_verified_at IS NOT NULL",
            Integer.class));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
            () -> user(schema, "another-login", "fictional@example.invalid",
                "fictional@example.invalid", "hosted", "ACTIVE"));
    }

    @Test
    void emailKeySwapIsRealignedWithoutTransientUniqueIndexCollision()
    {
        String schema = platform("portable", "9");
        UUID first = user(schema, "first",
            "first@example.invalid", "second@example.invalid", "hosted", "ACTIVE");
        UUID second = user(schema, "second",
            "second@example.invalid", "first@example.invalid", "hosted", "ACTIVE");
        upgrade(schema);
        assertEquals("first@example.invalid", jdbc.queryForObject(
            "SELECT email_normalized FROM " + schema + ".users WHERE id = ?",
            String.class, first));
        assertEquals("second@example.invalid", jdbc.queryForObject(
            "SELECT email_normalized FROM " + schema + ".users WHERE id = ?",
            String.class, second));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
            () -> user(schema, "duplicate", "first@example.invalid",
                "first@example.invalid", "hosted", "ACTIVE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "portable"})
    void futureUuidNamedCabinetCannotBypassSequentialReservationRequirement(String kind)
    {
        String schema = platform(kind, "latest");
        UUID owner = localUser(schema, "owner");
        UUID cabinet = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> legacyCabinet(schema, owner, cabinet, legacyName(cabinet)));
        assertEquals(0, count(schema, "cabinets"));
    }

    @Test
    void futureLegacyReservationCannotBeForgedAfterUpgradeBoundary()
    {
        String schema = platform("portable", "latest");
        requireTable(schema, "cabinet_schema_allocations");
        UUID cabinet = UUID.randomUUID();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
            "INSERT INTO " + schema + ".cabinet_schema_allocations "
                + "(reservation_id, cabinet_id, allocation_kind, schema_name, allocated_at) "
                + "VALUES (?, ?, 'LEGACY', ?, CURRENT_TIMESTAMP)",
            UUID.randomUUID(), cabinet, legacyName(cabinet)));
        assertEquals(0, count(schema, "cabinet_schema_allocations"));
    }

    @Test
    void legacyMappingCannotBeChangedAfterSnapshotButDisplayNameCan()
    {
        String schema = platform("portable", "9");
        UUID cabinet = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String tenant = legacyName(cabinet);
        // At V9, only cabinets table exists. Insert legacy cabinet data without allocations table.
        jdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name, provisioning_status, created_at, provisioned_at)
            VALUES (?, ?, 'Fictional cabinet', ?, 'ACTIVE', now(), now())
            """.formatted(schema), cabinet, localUser(schema, "owner"), tenant);
        upgrade(schema);
        assertThrows(org.springframework.dao.DataAccessException.class,
            () -> jdbc.update("UPDATE " + schema + ".cabinets SET schema_name = 'cabinet99999' WHERE id = ?", cabinet));
        assertThrows(org.springframework.dao.DataAccessException.class,
            () -> jdbc.update("UPDATE " + schema + ".cabinets SET id = ? WHERE id = ?",
                UUID.randomUUID(), cabinet));
        jdbc.update("UPDATE " + schema + ".cabinets SET name = 'Fictional renamed cabinet' WHERE id = ?", cabinet);
        assertEquals(legacyName(cabinet), jdbc.queryForObject(
            "SELECT schema_name FROM " + schema + ".cabinets WHERE id = ?",
            String.class, cabinet));
    }

    @Test
    void legacyLedgerAndCatalogEvidenceSurviveCabinetDeletionAndRejectMutation()
    {
        String schema = platform("portable", "9");
        UUID cabinet = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String tenant = legacyName(cabinet);
        // Ensure fresh schema per test scenario
        jdbc.execute("DROP SCHEMA IF EXISTS " + tenant + " CASCADE");
        // At V9, only cabinets table exists. Insert legacy cabinet data without allocations table.
        jdbc.update("""
            INSERT INTO %s.cabinets (id, owner_id, name, schema_name, provisioning_status, created_at, provisioned_at)
            VALUES (?, ?, 'Fictional cabinet', ?, 'ACTIVE', now(), now())
            """.formatted(schema), cabinet, localUser(schema, "owner"), tenant);
        // Create schema after cabinet insert for legacy scenario
        jdbc.execute("CREATE SCHEMA " + tenant);
        upgrade(schema);
        requireTable(schema, "cabinet_schema_allocations");
        requireTable(schema, "cabinet_legacy_schema_observations");
        Map<String, Object> allocation = jdbc.queryForMap(
            "SELECT * FROM " + schema + ".cabinet_schema_allocations WHERE cabinet_id = ?", cabinet);
        Map<String, Object> observation = jdbc.queryForMap(
            "SELECT * FROM " + schema + ".cabinet_legacy_schema_observations WHERE cabinet_id = ?", cabinet);
        assertAll(
            () -> assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update(
                    "UPDATE " + schema
                    + ".cabinet_schema_allocations SET schema_name = 'cabinet99999' WHERE cabinet_id = ?",
                    cabinet)),
            () -> assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update(
                    "DELETE FROM " + schema
                    + ".cabinet_schema_allocations WHERE cabinet_id = ?", cabinet)),
            () -> assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.execute(
                    "TRUNCATE " + schema + ".cabinet_schema_allocations CASCADE")),
            () -> assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update(
                    "UPDATE " + schema
                    + ".cabinet_legacy_schema_observations SET namespace_oid = 1 WHERE cabinet_id = ?",
                    cabinet)),
            () -> assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update(
                    "DELETE FROM " + schema
                    + ".cabinet_legacy_schema_observations WHERE cabinet_id = ?", cabinet)),
            () -> assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.execute(
                    "TRUNCATE " + schema + ".cabinet_legacy_schema_observations")));
        jdbc.update("DELETE FROM " + schema + ".cabinets WHERE id = ?", cabinet);
        assertEquals(allocation, jdbc.queryForMap(
            "SELECT * FROM " + schema
            + ".cabinet_schema_allocations WHERE cabinet_id = ?", cabinet));
        assertEquals(observation, jdbc.queryForMap(
            "SELECT * FROM " + schema
            + ".cabinet_legacy_schema_observations WHERE cabinet_id = ?", cabinet));
        assertEquals(namespace(tenant).get("namespace_oid"),
            observation.get("namespace_oid"));
    }

    @Test
    void trialMetadataRequiresExactly720ElapsedHoursAndOnlyOneInitialTrial()
    {
        String schema = platform("public", "latest");
        requireTable(schema, "cabinet_subscriptions");
        UUID owner = localUser(schema, "owner");
        UUID cabinet = reserveCabinet(schema, owner);
        Instant start = Instant.parse("2026-03-28T12:00:00Z");
        Instant end = Instant.parse("2026-04-27T12:00:00Z");
        insertTrial(schema, cabinet, start, end);
        assertEquals(2592000L, jdbc.queryForObject(
            "SELECT extract(epoch FROM (trial_ends_at - trial_started_at))::bigint FROM "
                + schema + ".cabinet_subscriptions WHERE cabinet_id = ?",
            Long.class, cabinet));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
            () -> insertTrial(schema, cabinet, start, end));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> jdbc.update(
                "UPDATE " + schema
                + ".cabinet_subscriptions SET trial_ends_at = trial_started_at WHERE cabinet_id = ?",
                cabinet));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> jdbc.update(
                "UPDATE " + schema
                + ".cabinet_subscriptions SET trial_ends_at = NULL WHERE cabinet_id = ?",
                cabinet));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> jdbc.update(
                "UPDATE " + schema
                + ".cabinet_subscriptions SET trial_ends_at = "
                + "trial_ends_at + interval '1 hour' WHERE cabinet_id = ?",
                cabinet));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> jdbc.update(
                "UPDATE " + schema
                + ".cabinet_subscriptions SET trial_started_at = "
                + "trial_started_at + interval '1 day', "
                + "trial_ends_at = trial_ends_at + interval '1 day' WHERE cabinet_id = ?",
                cabinet));
        jdbc.update("UPDATE " + schema + ".cabinets SET administrative_status = 'SUSPENDED' WHERE id = ?", cabinet);
        assertEquals("TRIAL", jdbc.queryForObject(
            "SELECT subscription_status FROM " + schema
                + ".cabinet_subscriptions WHERE cabinet_id = ?", String.class, cabinet));
        assertEquals("cabinet00000", jdbc.queryForObject(
            "SELECT schema_name FROM " + schema
                + ".cabinets WHERE id = ?", String.class, cabinet));
        jdbc.update("UPDATE " + schema + ".cabinets SET administrative_status = 'AVAILABLE', "
            + "provisioning_attempt_id = ? WHERE id = ?",
            UUID.randomUUID(), cabinet);
        assertEquals(1, count(schema, "cabinet_subscriptions"));
    }

    private UUID reserveCabinet(String schema, UUID owner)
    {
        UUID cabinet = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".cabinet_schema_allocations "
            + "(reservation_id, cabinet_id, allocation_kind, schema_number, schema_name, allocated_at) "
            + "VALUES (?, ?, 'SEQUENTIAL', 0, 'cabinet00000', CURRENT_TIMESTAMP)", UUID.randomUUID(), cabinet);
        jdbc.update("INSERT INTO " + schema + ".cabinets (id, owner_id, name, schema_name, created_at) "
            + "VALUES (?, ?, 'Fictional cabinet', 'cabinet00000', CURRENT_TIMESTAMP)", cabinet, owner);
        return cabinet;
    }

    private void insertTrial(String schema, UUID cabinet, Instant start, Instant end)
    {
        jdbc.update("INSERT INTO " + schema + ".cabinet_subscriptions "
            + "(id, cabinet_id, subscription_status, trial_started_at, trial_ends_at) VALUES (?, ?, 'TRIAL', ?, ?)",
            UUID.randomUUID(), cabinet, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
    }

    @Test
    void allSixteenHistoricalSqlResourcesKeepTheirOriginalByteChecksums() throws Exception
    {
        String manifest = """
            platform/V1__initial_platform_schema.sql
                4064d3c80344f076706ee299878cbcb51e62ba2ac739a27cbd1837dd5a43337e
            platform/V2__create_users_table.sql
                a79660f2958cd18f642e3c75a17a2610973d4bcb3be707fd223ca8b6fb77d828
            platform/V3__create_cabinets_table.sql
                4bb5abb19bf1436b023da22a800a562b4d1f72ab959f0f1e198b41a314273731
            platform/V4__create_cabinet_memberships_table.sql
                3bebd0d00fb237f03f06008f12bc1e4756190eb95fbd7dda3d046b5eb656328f
            platform/V5__create_membership_permissions_table.sql
                c107f83a310cdb151e60a2dac62b96fbef98de093b9703d1b89f2c251882e9dd
            platform/V6__create_invitations_table.sql
                7cd6ae13706b7dd3d5050467ec053db974459e53bd86295fabadcf8992f907e5
            platform/V7__create_platform_indexes.sql
                04573234810bc3b335a1d68cc8bd4389b7d9217f9eb8762d1f963485e9684302
            platform/V8__record_installation_setup.sql
                809cb052862ba07194d9b4382afd2f9fa2cb9dee1c9cedfde977c81c24801141
            platform/V9__create_spring_session_tables.sql
                0ab6604f45020ba5f4c5a0191cf87d066ce3cc4d0057fae4c6454178bff1950c
            tenant/V1__create_patients_table.sql
                e1d6c63390fd1e8d6fed62b68e8f462bf1cc7272f18a7e2ba0a2a21bce02b147
            tenant/V2__create_patient_identity_tables.sql
                7dac64067191282b8c4a49eca357c8860af122db928ed154568f16561c407044
            tenant/V3__create_staff_and_medical_safety_tables.sql
                1d05aa504b5323a4773bce5afb4f96cd02ff30e43ba409f7140136c7fe1f3289
            tenant/V4__create_audit_logs_table.sql
                9ea752e683eb529d5450be0172ceba7ba79cbc80a1962a277fa507cd4f1ee3cf
            tenant/V5__create_import_infrastructure_tables.sql
                605dae23aa3fc0c652bc60ee0fcf620cf57a7d332684bca043ea707ec60cce8d
            tenant/V6__enforce_unique_patient_phone_numbers.sql
                efb4bfa0625389f25accf23a95cde12bf7fcb4fc44684e8293118e3c5ce884bf
            tenant/V7__harden_patient_integrity.sql
                a73a969d2418af61baf40a2a0c605831e6ce8c026d57eaf7544af6fe056bcb86
            """;
        List<String> lines = manifest.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (int i = 0; i < lines.size(); i += 2)
        {
            String path = lines.get(i);
            String expectedHash = lines.get(i + 1);
            try (var resource = getClass().getResourceAsStream("/db/migration/" + path))
            {
                assertNotNull(resource, path);
                assertEquals(expectedHash,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource.readAllBytes())),
                    path);
            }
        }
    }
}