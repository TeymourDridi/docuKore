package com.docukore.cabinet.patient;

import com.docukore.support.OwnedTestDatabase;
import com.docukore.support.SharedSpringBootTest;
import com.docukore.support.TestFixtures;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP patient creation contract against the existing external PostgreSQL test database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PatientCreationHttpIntegrationTest extends SharedSpringBootTest
{
    /** Latest-migrated platform owned by this test class only. */
    private static final TestFixtures.OwnedPlatform PLATFORM = TestFixtures.createPlatform("patient_http_platform");
    /** JSON codec used only for fictional requests and boundary assertions. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Fictional account credential, never an installation credential. */
    private static final String PASSWORD = "Fictional-patient-test-passphrase!";
    /** Runtime pool retired before exact owned-schema cleanup. */
    private final HikariDataSource dataSource;
    /** Exact tenant ownership receipts retained until cleanup. */
    private final OwnedTestDatabase.Scope resources;
    /** Fixture access through the class-owned migrator pool. */
    private final JdbcTemplate jdbc;
    /** Encoder used to create real login fixtures. */
    private final PasswordEncoder passwords;
    /** Random port of the actual application HTTP server. */
    private final int port;
    /** First cabinet owner used for authenticated writes. */
    private UUID owner;
    /** Independent second cabinet owner used for isolation assertions. */
    private UUID otherOwner;
    /** Secretary with explicitly managed grants, never implicit owner permissions. */
    private UUID member;
    /** Account without cabinet membership. */
    private UUID outsider;
    /** First tenant, reused without resets across test methods. */
    private TestFixtures.CabinetFixture first;
    /** Independently provisioned tenant, reused without resets. */
    private TestFixtures.CabinetFixture second;
    /** Active secretary membership whose grants are changed in scoped tests. */
    private UUID memberMembership;

    PatientCreationHttpIntegrationTest(@LocalServerPort int port, HikariDataSource dataSource,
                                      PasswordEncoder passwords)
    {
        this.port = port;
        this.dataSource = dataSource;
        this.passwords = passwords;
        this.jdbc = PLATFORM.jdbc();
        this.resources = OwnedTestDatabase.scope(PLATFORM.migratorDataSource());
    }

    @DynamicPropertySource
    static void isolatedPlatform(DynamicPropertyRegistry registry)
    {
        registerPlatform(registry, PLATFORM);
    }

    @BeforeAll
    void fixtures()
    {
        String hash = passwords.encode(PASSWORD);
        owner = account(hash);
        otherOwner = account(hash);
        member = account(hash);
        outsider = account(hash);
        first = TestFixtures.createCabinet(jdbc, PLATFORM.schema(), owner, resources);
        second = TestFixtures.createCabinet(jdbc, PLATFORM.schema(), otherOwner, resources);
        membership(first.cabinetId(), owner, "OWNER");
        membership(second.cabinetId(), otherOwner, "OWNER");
        memberMembership = membership(first.cabinetId(), member, "MEMBER");
    }

    @Test
    void createsPatientWithContactAndAuditOnly() throws Exception
    {
        Browser browser = login(owner);
        Map<String, Integer> before = rowCounts(first);
        Map<String, Integer> untouched = rowCounts(second);
        Map<String, Object> request = patient("  20 123-456  ");
        request.put("firstName", "  Fictional  ");
        request.put("familyName", "  Patient  ");
        request.put("dateOfBirth", "1990-02-28");
        request.put("initialNote", "Fictional initial note.");
        Instant started = Instant.now().minusSeconds(1);

        HttpResponse<String> response = browser.post(request);

        assertEquals(201, response.statusCode());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode body = JSON.readTree(response.body());
        assertEquals(8, body.size());
        UUID patientId = UUID.fromString(body.get("id").asString());
        assertTrue(body.get("patientNumber").asString().matches("PAT-[0-9]{6,}"));
        assertEquals("Fictional", body.get("firstName").asString());
        assertEquals("Patient", body.get("familyName").asString());
        assertEquals("+21620123456", body.get("phone").asString());
        assertEquals("1990-02-28", body.get("dateOfBirth").asString());
        assertEquals("Fictional initial note.", body.get("initialNote").asString());
        Instant created = Instant.parse(body.get("createdAt").asString());
        assertFalse(created.isBefore(started));
        assertFalse(created.isAfter(Instant.now().plusSeconds(1)));

        Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM " + table(first, "patients")
            + " WHERE id = ?", patientId);
        assertEquals(first.cabinetId(), stored.get("cabinet_id"));
        assertEquals(body.get("patientNumber").asString(), stored.get("patient_number"));
        assertEquals("Fictional", stored.get("legal_given_name"));
        assertEquals("Patient", stored.get("legal_family_name"));
        assertEquals(1990, stored.get("birth_year"));
        assertEquals(2, stored.get("birth_month"));
        assertEquals(28, stored.get("birth_day"));
        assertEquals("COMPLETE_DATE", stored.get("birth_precision"));
        assertEquals("Fictional initial note.", stored.get("initial_note"));
        assertEquals(owner, stored.get("created_by"));
        assertEquals(owner, stored.get("updated_by"));
        assertNotNull(stored.get("created_at"));
        assertNotNull(stored.get("updated_at"));
        Map<String, Object> contact = jdbc.queryForMap("SELECT * FROM " + table(first, "patient_contacts")
            + " WHERE patient_id = ?", patientId);
        assertEquals("PHONE", contact.get("contact_type"));
        assertEquals("20 123-456", contact.get("value"));
        assertEquals("+21620123456", contact.get("value_normalized"));
        assertEquals(Boolean.TRUE, contact.get("is_current"));
        assertEquals(Boolean.TRUE, contact.get("is_primary_for_type"));
        assertAudit(first, patientId, owner);
        assertCreationCounts(before, first, 1);
        assertEquals(untouched, rowCounts(second));
    }

    @Test
    void omittedOptionalFieldsRemainNullWithoutCreatingClinicalRecords() throws Exception
    {
        Map<String, Integer> before = rowCounts(first);
        var response = login(owner).post(patient("21 123 456"));
        assertEquals(201, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.has("dateOfBirth"));
        assertTrue(body.has("initialNote"));
        assertTrue(body.get("dateOfBirth").isNull());
        assertTrue(body.get("initialNote").isNull());
        UUID id = UUID.fromString(body.get("id").asString());
        var stored = jdbc.queryForMap("SELECT birth_year, birth_month, birth_day, birth_precision, initial_note FROM "
            + table(first, "patients") + " WHERE id = ?", id);
        assertEquals("UNKNOWN", stored.get("birth_precision"));
        assertEquals(null, stored.get("birth_year"));
        assertEquals(null, stored.get("birth_month"));
        assertEquals(null, stored.get("birth_day"));
        assertEquals(null, stored.get("initial_note"));
        assertCreationCounts(before, first, 1);
    }

    @ParameterizedTest(name = "rejects invalid {0}")
    @MethodSource("invalidFields")
    void rejectsInvalidFieldsWithoutEchoingValuesOrWritingRows(InvalidField invalid) throws Exception
    {
        Map<String, Integer> before = rowCounts(first);
        Map<String, Object> request = patient("+21622123456");
        if (invalid.omitted())
        {
            request.remove(invalid.field());
        } else
        {
            request.put(invalid.field(), invalid.value());
        }
        var response = login(owner).post(request);
        assertEquals(400, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("INVALID_INPUT", body.get("code").asString());
        JsonNode errors = body.get("fieldErrors");
        assertNotNull(errors);
        assertTrue(errors.isObject());
        assertTrue(errors.has(invalid.field()));
        assertFalse(response.body().contains("rejectedValue"));
        assertFalse(response.body().contains("Fictional"));
        assertFalse(response.body().contains("Patient"));
        assertFalse(response.body().contains("+21622123456"));
        if (invalid.value() instanceof String value && !value.isBlank())
        {
            assertFalse(response.body().contains(value));
        }
        assertEquals(before, rowCounts(first));
    }

    static Stream<InvalidField> invalidFields()
    {
        return Stream.of(
            new InvalidField("firstName", null, true),
            new InvalidField("firstName", null, false),
            new InvalidField("firstName", "   ", false),
            new InvalidField("firstName", "X".repeat(256), false),
            new InvalidField("familyName", null, true),
            new InvalidField("familyName", "\t ", false),
            new InvalidField("familyName", "Y".repeat(256), false),
            new InvalidField("phone", null, true),
            new InvalidField("phone", null, false),
            new InvalidField("phone", " ", false),
            new InvalidField("phone", "1234567", false),
            new InvalidField("phone", "21620123456", false),
            new InvalidField("phone", "+01234567", false),
            new InvalidField("phone", "+123456", false),
            new InvalidField("phone", "+1234567890123456", false),
            new InvalidField("phone", "20CALLME", false),
            new InvalidField("phone", "+21620/123456", false),
            new InvalidField("phone", "+21620++123456", false),
            new InvalidField("phone", "٢١٦٢٠١٢٣٤٥٦", false),
            new InvalidField("phone", "2".repeat(256), false),
            new InvalidField("dateOfBirth", LocalDate.now().plusDays(1).toString(), false),
            new InvalidField("dateOfBirth", "2023-02-29", false),
            new InvalidField("dateOfBirth", "not-a-date", false),
            new InvalidField("initialNote", "N".repeat(10001), false));
    }

    @ParameterizedTest(name = "rejects non-textual {0}")
    @MethodSource("nonTextualFields")
    void rejectsNonTextualInputWithoutWritingRows(InvalidField invalid) throws Exception
    {
        Map<String, Integer> before = rowCounts(first);
        Map<String, Object> request = patient("+21633123456");
        request.put(invalid.field(), invalid.value());
        var response = login(owner).post(request);
        assertError(response, 400, "INVALID_INPUT");
        assertEquals(before, rowCounts(first));
    }

    static Stream<InvalidField> nonTextualFields()
    {
        return Stream.of(
            new InvalidField("firstName", true, false),
            new InvalidField("familyName", 123, false),
            new InvalidField("phone", 20123456, false),
            new InvalidField("initialNote", 123, false));
    }

    @ParameterizedTest(name = "normalizes {0}")
    @MethodSource("validPhones")
    void normalizesSupportedPhoneForms(String raw, String canonical) throws Exception
    {
        Map<String, Integer> before = rowCounts(second);
        var response = login(otherOwner).post(patient(raw));
        assertEquals(201, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(canonical, body.get("phone").asString());
        UUID id = UUID.fromString(body.get("id").asString());
        assertEquals(raw.strip(), jdbc.queryForObject("SELECT value FROM " + table(second, "patient_contacts")
            + " WHERE patient_id = ?", String.class, id));
        assertCreationCounts(before, second, 1);
    }

    static Stream<Object[]> validPhones()
    {
        return Stream.of(
            new Object[]{"  22.345.678  ", "+21622345678"},
            new Object[]{"00 33 (1) 23-45-67-89", "+33123456789"},
            new Object[]{"+1 (202) 555-0147", "+12025550147"},
            new Object[]{"+1234567", "+1234567"},
            new Object[]{"+123456789012345", "+123456789012345"});
    }

    @Test
    void acceptsBoundaryLengthsAndExplicitNullOptionalFields() throws Exception
    {
        var request = patient("+21623123456");
        request.put("firstName", "F".repeat(255));
        request.put("familyName", "P".repeat(255));
        request.put("initialNote", "N".repeat(10000));
        request.put("dateOfBirth", null);
        var response = login(owner).post(request);
        assertEquals(201, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(255, body.get("firstName").asString().length());
        assertEquals(255, body.get("familyName").asString().length());
        assertEquals(10000, body.get("initialNote").asString().length());
        assertTrue(body.get("dateOfBirth").isNull());
    }

    @Test
    void duplicateNormalizedPhoneConflictsAndRollsBackAllRows() throws Exception
    {
        Browser browser = login(owner);
        assertEquals(201, browser.post(patient("24 123 456")).statusCode());
        Map<String, Integer> before = rowCounts(first);
        var response = browser.post(patient("00216 (24) 123-456"));
        assertError(response, 409, "PHONE_CONFLICT");
        assertFalse(response.body().contains("24123456"));
        assertEquals(before, rowCounts(first));
        assertEquals(201, browser.post(patient("24 123 457")).statusCode());
        assertCreationCounts(before, first, 1);
    }

    @Test
    void concurrentDistinctCreatesAllocateUniqueTenantLocalNumbers() throws Exception
    {
        Map<String, Integer> before = rowCounts(first);
        List<Browser> browsers = new ArrayList<>();
        List<Map<String, Object>> requests = new ArrayList<>();
        for (int index = 0; index < 4; index++)
        {
            browsers.add(login(owner));
            requests.add(patient("+2162512345" + index));
        }
        Set<String> numbers = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (var response : concurrentPosts(browsers, requests))
        {
            assertEquals(201, response.statusCode());
            JsonNode body = JSON.readTree(response.body());
            assertTrue(body.get("patientNumber").asString().matches("PAT-[0-9]{6,}"));
            numbers.add(body.get("patientNumber").asString());
            ids.add(body.get("id").asString());
        }
        assertEquals(4, numbers.size());
        assertEquals(4, ids.size());
        assertCreationCounts(before, first, 4);
    }

    @Test
    void concurrentSamePhoneHasExactlyOneWinnerAndNoOrphans() throws Exception
    {
        Map<String, Integer> before = rowCounts(first);
        List<Browser> browsers = new ArrayList<>();
        List<Map<String, Object>> requests = new ArrayList<>();
        for (int index = 0; index < 4; index++)
        {
            browsers.add(login(owner));
            requests.add(patient(index % 2 == 0 ? "26 123-456" : "+216 (26) 123456"));
        }
        int created = 0;
        int conflicts = 0;
        for (var response : concurrentPosts(browsers, requests))
        {
            if (response.statusCode() == 201)
            {
                created++;
            } else
            {
                assertError(response, 409, "PHONE_CONFLICT");
                conflicts++;
            }
        }
        assertEquals(1, created);
        assertEquals(3, conflicts);
        assertCreationCounts(before, first, 1);
    }

    @Test
    void separateCabinetsAcceptSamePhoneAndIgnoreSpoofedTenantAndActor() throws Exception
    {
        Map<String, Integer> beforeFirst = rowCounts(first);
        Map<String, Integer> beforeSecond = rowCounts(second);
        Map<String, Object> spoofed = patient("27 123 456");
        spoofed.put("cabinetId", second.cabinetId().toString());
        spoofed.put("schemaName", second.schemaName());
        spoofed.put("tenantId", second.cabinetId().toString());
        spoofed.put("userId", otherOwner.toString());
        spoofed.put("createdBy", otherOwner.toString());
        var response = login(owner).post(spoofed, true, Map.of(
            "X-Cabinet-Id", second.cabinetId().toString(),
            "X-Tenant-Schema", second.schemaName(),
            "X-User-Id", otherOwner.toString()));
        assertEquals(201, response.statusCode());
        UUID id = UUID.fromString(JSON.readTree(response.body()).get("id").asString());
        assertEquals(first.cabinetId(), jdbc.queryForObject("SELECT cabinet_id FROM " + table(first, "patients")
            + " WHERE id = ?", UUID.class, id));
        assertAudit(first, id, owner);
        assertCreationCounts(beforeFirst, first, 1);
        assertEquals(beforeSecond, rowCounts(second));
        var otherResponse = login(otherOwner).post(patient("+21627123456"));
        assertEquals(201, otherResponse.statusCode());
        UUID otherId = UUID.fromString(JSON.readTree(otherResponse.body()).get("id").asString());
        assertFalse(id.equals(otherId));
        assertAudit(second, otherId, otherOwner);
        assertCreationCounts(beforeSecond, second, 1);
        assertCreationCounts(beforeFirst, first, 1);
    }

    @Test
    void secretaryRequiresExplicitReadWritePermission() throws Exception
    {
        Browser browser = login(member);
        Map<String, Integer> before = rowCounts(first);
        assertEquals(403, browser.post(patient("28 123 456")).statusCode());
        UUID grant = grant(memberMembership, "READ");
        try
        {
            assertEquals(403, browser.post(patient("28 123 456")).statusCode());
            assertEquals(before, rowCounts(first));
            jdbc.update("UPDATE " + platformTable("membership_permissions")
                + " SET permission_code = 'READ_WRITE' WHERE id = ?", grant);
            var response = browser.post(patient("28 123 456"));
            assertEquals(201, response.statusCode());
            UUID id = UUID.fromString(JSON.readTree(response.body()).get("id").asString());
            assertAudit(first, id, member);
            assertCreationCounts(before, first, 1);
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("membership_permissions") + " WHERE id = ?", grant);
        }
    }

    @Test
    void inactiveMembershipAndUnavailableCabinetDenyWritesWithoutRelogin() throws Exception
    {
        Browser browser = login(owner);
        Map<String, Integer> before = rowCounts(first);
        String memberships = platformTable("cabinet_memberships");
        String cabinets = platformTable("cabinets");
        try
        {
            jdbc.update("UPDATE " + memberships + " SET status = 'INACTIVE' WHERE cabinet_id = ? AND user_id = ?",
                first.cabinetId(), owner);
            assertEquals(403, browser.post(patient("29 123 456")).statusCode());
            jdbc.update("UPDATE " + memberships + " SET status = 'ACTIVE', revoked_at = now()"
                + " WHERE cabinet_id = ? AND user_id = ?", first.cabinetId(), owner);
            assertEquals(403, browser.post(patient("29 123 456")).statusCode());
            jdbc.update("UPDATE " + memberships + " SET revoked_at = NULL WHERE cabinet_id = ? AND user_id = ?",
                first.cabinetId(), owner);
            jdbc.update("UPDATE " + cabinets + " SET administrative_status = 'SUSPENDED' WHERE id = ?",
                first.cabinetId());
            assertEquals(403, browser.post(patient("29 123 456")).statusCode());
            jdbc.update("UPDATE " + cabinets + " SET administrative_status = 'AVAILABLE',"
                + " provisioning_status = 'PROVISIONING_FAILED' WHERE id = ?", first.cabinetId());
            assertEquals(403, browser.post(patient("29 123 456")).statusCode());
            assertEquals(before, rowCounts(first));
        } finally
        {
            jdbc.update("UPDATE " + memberships + " SET status = 'ACTIVE', revoked_at = NULL"
                + " WHERE cabinet_id = ? AND user_id = ?", first.cabinetId(), owner);
            jdbc.update("UPDATE " + cabinets + " SET administrative_status = 'AVAILABLE',"
                + " provisioning_status = 'ACTIVE' WHERE id = ?", first.cabinetId());
        }
    }

    @Test
    void noMembershipAndMultipleMembershipsNeverSelectSpoofedCabinet() throws Exception
    {
        Map<String, Integer> beforeFirst = rowCounts(first);
        Map<String, Integer> beforeSecond = rowCounts(second);
        Browser browser = login(outsider);
        assertEquals(403, browser.post(patient("30 123 456")).statusCode());
        UUID firstMembership = membership(first.cabinetId(), outsider, "MEMBER");
        UUID secondMembership = membership(second.cabinetId(), outsider, "MEMBER");
        try
        {
            var request = patient("30 123 456");
            request.put("cabinetId", first.cabinetId().toString());
            assertError(browser.post(request, true, Map.of("X-Cabinet-Id", first.cabinetId().toString())),
                409, "CABINET_SELECTION_REQUIRED");
            assertEquals(beforeFirst, rowCounts(first));
            assertEquals(beforeSecond, rowCounts(second));
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("cabinet_memberships") + " WHERE id IN (?, ?)",
                firstMembership, secondMembership);
        }
    }

    @Test
    void missingCsrfAndAnonymousRequestsCannotCreatePatient() throws Exception
    {
        Map<String, Integer> before = rowCounts(first);
        Browser browser = login(owner);
        assertEquals(403, browser.post(patient("31 123 456"), false, Map.of()).statusCode());
        Browser anonymous = new Browser(port);
        assertEquals(204, anonymous.get("/api/auth/csrf").statusCode());
        assertEquals(401, anonymous.post(patient("31 123 456")).statusCode());
        assertEquals(before, rowCounts(first));
    }

    @Test
    void auditInsertFailureRollsBackPatientAndContact() throws Exception
    {
        Browser browser = login(owner);
        Map<String, Integer> before = rowCounts(first);
        String audit = table(first, "audit_logs");
        jdbc.execute("ALTER TABLE " + audit
            + " ADD CONSTRAINT task951_reject_create CHECK (operation <> 'CREATE') NOT VALID");
        try
        {
            assertEquals(500, browser.post(patient("32 123 456")).statusCode());
            assertEquals(before, rowCounts(first));
        } finally
        {
            jdbc.execute("ALTER TABLE " + audit + " DROP CONSTRAINT task951_reject_create");
        }
        assertEquals(201, browser.post(patient("32 123 456")).statusCode());
        assertCreationCounts(before, first, 1);
    }

    @AfterAll
    void removeOnlyOwnedFixtures()
    {
        dataSource.close();
        resources.close();
        PLATFORM.close();
    }

    private void assertAudit(TestFixtures.CabinetFixture cabinet, UUID patientId, UUID actor)
    {
        var audit = jdbc.queryForMap("SELECT cabinet_id, entity_type, entity_id, operation, user_id, timestamp FROM "
            + table(cabinet, "audit_logs") + " WHERE entity_id = ?", patientId);
        assertEquals(cabinet.cabinetId(), audit.get("cabinet_id"));
        assertEquals("patient", audit.get("entity_type"));
        assertEquals(patientId, audit.get("entity_id"));
        assertEquals("CREATE", audit.get("operation"));
        assertEquals(actor, audit.get("user_id"));
        assertNotNull(audit.get("timestamp"));
    }

    private void assertCreationCounts(Map<String, Integer> before, TestFixtures.CabinetFixture cabinet, int created)
    {
        Map<String, Integer> expected = new HashMap<>(before);
        for (String table : List.of("patients", "patient_contacts", "audit_logs"))
        {
            expected.compute(table, (key, count) -> count + created);
        }
        // Includes all existing identity, staff, medical-safety and import tables, not future modules.
        assertEquals(expected, rowCounts(cabinet));
    }

    private Map<String, Integer> rowCounts(TestFixtures.CabinetFixture cabinet)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables"
            + " WHERE table_schema = ? AND table_type = 'BASE TABLE'"
            + " AND table_name <> 'flyway_schema_history_tenant' ORDER BY table_name", String.class,
            cabinet.schemaName());
        for (String name : tables)
        {
            counts.put(name, jdbc.queryForObject("SELECT count(*) FROM " + table(cabinet, name), Integer.class));
        }
        return counts;
    }

    private static String table(TestFixtures.CabinetFixture cabinet, String name)
    {
        return OwnedTestDatabase.identifier(cabinet.schemaName()) + "." + OwnedTestDatabase.identifier(name);
    }

    private static String platformTable(String name)
    {
        return OwnedTestDatabase.identifier(PLATFORM.schema()) + "." + OwnedTestDatabase.identifier(name);
    }

    private static Map<String, Object> patient(String phone)
    {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("firstName", "Fictional");
        request.put("familyName", "Patient");
        request.put("phone", phone);
        return request;
    }

    private static void assertError(HttpResponse<String> response, int status, String code)
    {
        assertEquals(status, response.statusCode());
        assertEquals(code, JSON.readTree(response.body()).get("code").asString());
    }

    private UUID account(String hash)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + platformTable("users")
            + " (id, username, username_normalized, password_hash, deployment_mode, account_status,"
            + " activated_at, created_at) VALUES (?, ?, ?, ?, 'local', 'ACTIVE', now(), now())",
            id, id.toString(), id.toString(), hash);
        return id;
    }

    private UUID membership(UUID cabinet, UUID account, String role)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + platformTable("cabinet_memberships")
            + " (id, cabinet_id, user_id, membership_role, staff_type, status)"
            + " VALUES (?, ?, ?, ?, 'SECRETARY', 'ACTIVE')", id, cabinet, account, role);
        return id;
    }

    private UUID grant(UUID membership, String permission)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + platformTable("membership_permissions")
            + " (id, membership_id, permission_code, granted_at) VALUES (?, ?, ?, now())",
            id, membership, permission);
        return id;
    }

    private Browser login(UUID account) throws Exception
    {
        Browser browser = new Browser(port);
        assertEquals(204, browser.get("/api/auth/csrf").statusCode());
        assertEquals(200, browser.login(account.toString()).statusCode());
        assertEquals(204, browser.get("/api/auth/csrf").statusCode());
        return browser;
    }

    private static List<HttpResponse<String>> concurrentPosts(List<Browser> browsers,
                                                             List<Map<String, Object>> requests) throws Exception
    {
        CountDownLatch ready = new CountDownLatch(browsers.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(browsers.size()))
        {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int index = 0; index < browsers.size(); index++)
            {
                Browser browser = browsers.get(index);
                Map<String, Object> request = requests.get(index);
                futures.add(executor.submit(() ->
                {
                    ready.countDown();
                    assertTrue(start.await(15, TimeUnit.SECONDS));
                    return browser.post(request);
                }));
            }
            try
            {
                assertTrue(ready.await(15, TimeUnit.SECONDS));
            } finally
            {
                start.countDown();
            }
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (var future : futures)
            {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    /**
     * One independently specified invalid input without relying on production validation helpers.
     *
     * @param field public DTO field expected in fieldErrors
     * @param value fictional invalid value, possibly null
     * @param omitted whether the property is absent rather than explicitly null
     */
    record InvalidField(String field, Object value, boolean omitted)
    {
        @Override
        public String toString()
        {
            return field + (omitted ? " missing" : " invalid");
        }
    }

    /** Cookie-backed real browser with fresh CSRF tokens after login. */
    private static final class Browser
    {
        /** Random local server port. */
        private final int port;
        /** Cookie store preserving actual session and CSRF state. */
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        /** Real HTTP client, never a mock controller invocation. */
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        private Browser(int port)
        {
            this.port = port;
        }

        HttpResponse<String> get(String path) throws Exception
        {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> login(String username) throws Exception
        {
            String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            return client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(Map<String, Object> request) throws Exception
        {
            return post(request, true, Map.of());
        }

        HttpResponse<String> post(Map<String, Object> request, boolean withCsrf,
                                  Map<String, String> headers) throws Exception
        {
            var builder = HttpRequest.newBuilder(uri("/api/patients")).header("Content-Type", "application/json");
            if (withCsrf)
            {
                builder.header("X-XSRF-TOKEN", csrf());
            }
            headers.forEach(builder::header);
            return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)))
                .build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path)
        {
            return URI.create("http://localhost:" + port + path);
        }

        private String csrf()
        {
            return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(java.net.HttpCookie::getValue).findFirst().orElseThrow();
        }
    }
}
