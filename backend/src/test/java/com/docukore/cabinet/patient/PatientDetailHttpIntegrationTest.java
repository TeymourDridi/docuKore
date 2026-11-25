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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP patient detail contracts on class-owned schemas in the existing external test database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PatientDetailHttpIntegrationTest extends SharedSpringBootTest
{
    /** Latest-migrated platform owned exclusively by this class. */
    private static final TestFixtures.OwnedPlatform PLATFORM = TestFixtures.createPlatform("patient_detail_platform");
    /** Public JSON response codec. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Fictional account credential unrelated to installation credentials. */
    private static final String PASSWORD = "Fictional-detail-test-passphrase!";
    /** Fixed persisted timestamp, not a request-time approximation. */
    private static final Instant CREATED = Instant.parse("2024-06-15T12:00:00.123456Z");
    /** Legacy content must be returned exactly, without trimming or newline normalization. */
    private static final String LEGACY_NOTE = "  Fictional legacy note.\r\nSecond line.\n  ";
    /** Independent tenant content must never leak through a foreign detail request. */
    private static final String FOREIGN_NOTE = "Fictional other cabinet private note.";
    /** Exact public contract excludes fabricated clinical, financial and richer demographic fields. */
    private static final List<String> DETAIL_FIELDS = List.of("id", "patientNumber", "firstName", "familyName",
        "phone", "dateOfBirth", "initialNote", "createdAt");
    /** Shared UUID deliberately identifies different records in the two independent tenants. */
    private static final UUID PATIENT = new UUID(0, 101);
    /** Historical patient with all optional public values absent. */
    private static final UUID NULL_PATIENT = new UUID(0, 102);
    /** Patient present only in the second tenant. */
    private static final UUID FOREIGN_PATIENT = new UUID(0, 201);
    /** Row in the first tenant whose cabinet identifier belongs to the second cabinet. */
    private static final UUID WRONG_CABINET_PATIENT = new UUID(0, 202);
    /** Runtime pool retired before exact owned-schema cleanup. */
    private final HikariDataSource dataSource;
    /** Exact tenant ownership receipts retained until class cleanup. */
    private final OwnedTestDatabase.Scope resources;
    /** Fixture access uses migration credentials, while HTTP uses the restricted runtime pool. */
    private final JdbcTemplate jdbc;
    /** Encoder for actual session-login accounts. */
    private final PasswordEncoder passwords;
    /** Actual randomly assigned application server port. */
    private final int port;
    /** Owner of the first tenant. */
    private UUID owner;
    /** Owner of the second tenant. */
    private UUID otherOwner;
    /** Secretary with no implicit patient permission. */
    private UUID secretary;
    /** Account with no initial cabinet membership. */
    private UUID outsider;
    /** First independently provisioned tenant, reused throughout the class. */
    private TestFixtures.CabinetFixture first;
    /** Second independently provisioned isolation tenant. */
    private TestFixtures.CabinetFixture second;
    /** Membership mutated only within finally-protected live authorization tests. */
    private UUID ownerMembership;
    /** Membership used for explicit secretary grants. */
    private UUID secretaryMembership;

    PatientDetailHttpIntegrationTest(@LocalServerPort int port, HikariDataSource dataSource,
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
        secretary = account(hash);
        outsider = account(hash);
        first = TestFixtures.createCabinet(jdbc, PLATFORM.schema(), owner, resources);
        second = TestFixtures.createCabinet(jdbc, PLATFORM.schema(), otherOwner, resources);
        ownerMembership = membership(first.cabinetId(), owner, "OWNER");
        membership(second.cabinetId(), otherOwner, "OWNER");
        secretaryMembership = membership(first.cabinetId(), secretary, "MEMBER");

        patient(first, first.cabinetId(), PATIENT, "LEGACY-DETAIL-101", "Marielle", "Vellmont",
            "COMPLETE_DATE", 1988, 2, 29, LEGACY_NOTE, CREATED);
        contact(PATIENT, new UUID(1, 1), "PHONE", "20 444-001", "+21620444001", true, true, CREATED);
        patient(second, second.cabinetId(), PATIENT, "OTHER-DETAIL-101", "Independent", "Boundary",
            "COMPLETE_DATE", 1991, 7, 9, FOREIGN_NOTE, CREATED.minusSeconds(7));
        patient(second, second.cabinetId(), FOREIGN_PATIENT, "OTHER-ONLY-201", "ForeignOnly", "PrivateFamily",
            "UNKNOWN", null, null, null, FOREIGN_NOTE, CREATED);
        // Both predicates are required: a trusted schema alone must not authorize this mismatched row.
        patient(first, second.cabinetId(), WRONG_CABINET_PATIENT, "WRONG-CABINET-202", "Mismatched", "Boundary",
            "UNKNOWN", null, null, null, FOREIGN_NOTE, CREATED);
        patient(first, first.cabinetId(), NULL_PATIENT, "LEGACY-NULL-102", "Absent", "Optionals",
            "UNKNOWN", null, null, null, null, CREATED);
        contact(NULL_PATIENT, new UUID(1, 2), "PHONE", "old phone", "+21620999888", false, true,
            CREATED.plusSeconds(20));
        contact(NULL_PATIENT, new UUID(1, 3), "MOBILE", "old mobile", "+21620999777", false, true,
            CREATED.plusSeconds(21));
        contact(NULL_PATIENT, new UUID(1, 4), "EMAIL", "fictional@example.invalid", null, true, true, CREATED);
        contact(NULL_PATIENT, new UUID(1, 5), "FAX", "+21620999666", null, true, true, CREATED);
        contact(NULL_PATIENT, new UUID(1, 6), "EMERGENCY_PHONE", "+21620999555", null, true, true, CREATED);
        patient(first, first.cabinetId(), new UUID(0, 103), "LEGACY-YEAR-103", "YearOnly", "PartialBirth",
            "YEAR_ONLY", 1988, null, null, null, CREATED);
        patient(first, first.cabinetId(), new UUID(0, 104), "LEGACY-MONTH-104", "YearMonth", "PartialBirth",
            "YEAR_MONTH", 1988, 2, null, null, CREATED);
        phoneFixtures();
    }

    @Test
    void postThenGetReturnsIndependentPersistedValuesRatherThanAnEcho() throws Exception
    {
        Browser browser = login(owner);
        Map<String, Object> request = Map.of("firstName", "  Fictional  ", "familyName", "  Created  ",
            "phone", "  25 123-456  ", "dateOfBirth", "1990-02-28", "initialNote", "Fictional creation note.");
        HttpResponse<String> created = browser.post(request);
        assertEquals(201, created.statusCode(), "Creation must succeed before exercising detail");
        UUID id = UUID.fromString(JSON.readTree(created.body()).get("id").asString());
        Map<String, Object> stored = jdbc.queryForMap("SELECT id, cabinet_id, patient_number, legal_given_name,"
            + " legal_family_name, birth_precision, birth_year, birth_month, birth_day, initial_note, created_at"
            + " FROM " + table(first, "patients") + " WHERE id = ?", id);
        String storedPhone = jdbc.queryForObject("SELECT value_normalized FROM " + table(first, "patient_contacts")
            + " WHERE patient_id = ? AND contact_type = 'PHONE' AND is_current = true", String.class, id);
        assertEquals(first.cabinetId(), stored.get("cabinet_id"));
        assertEquals("Fictional", stored.get("legal_given_name"));
        assertEquals("Created", stored.get("legal_family_name"));
        assertEquals("COMPLETE_DATE", stored.get("birth_precision"));
        assertEquals(1990, stored.get("birth_year"));
        assertEquals(2, stored.get("birth_month"));
        assertEquals(28, stored.get("birth_day"));
        assertEquals("Fictional creation note.", stored.get("initial_note"));
        assertEquals("+21625123456", storedPhone);

        JsonNode body = detail(browser.get(path(id)));
        assertEquals(stored.get("id").toString(), body.get("id").asString());
        assertEquals(stored.get("patient_number"), body.get("patientNumber").asString());
        assertEquals(stored.get("legal_given_name"), body.get("firstName").asString());
        assertEquals(stored.get("legal_family_name"), body.get("familyName").asString());
        assertEquals(storedPhone, body.get("phone").asString());
        assertEquals("1990-02-28", body.get("dateOfBirth").asString());
        assertEquals(stored.get("initial_note"), body.get("initialNote").asString());
        assertEquals(((Timestamp) stored.get("created_at")).toInstant(),
            Instant.parse(body.get("createdAt").asString()));
    }

    @Test
    void ownerReadsExactLegacyNoteAndOnlyEightPersistedPublicFields() throws Exception
    {
        assertFirstPatient(detail(login(owner).get(path(PATIENT))));
    }

    @Test
    void absentOptionalsRemainExplicitNullAndExcludedContactsNeverBecomePhone() throws Exception
    {
        Map<String, Object> stored = jdbc.queryForMap("SELECT birth_precision, initial_note FROM "
            + table(first, "patients") + " WHERE id = ?", NULL_PATIENT);
        assertEquals("UNKNOWN", stored.get("birth_precision"));
        assertNull(stored.get("initial_note"));
        JsonNode body = detail(login(owner).get(path(NULL_PATIENT)));
        assertEquals(NULL_PATIENT.toString(), body.get("id").asString());
        assertEquals("LEGACY-NULL-102", body.get("patientNumber").asString());
        assertEquals("Absent", body.get("firstName").asString());
        assertEquals("Optionals", body.get("familyName").asString());
        assertEquals(CREATED, Instant.parse(body.get("createdAt").asString()));
        assertTrue(body.get("phone").isNull());
        assertTrue(body.get("dateOfBirth").isNull());
        assertTrue(body.get("initialNote").isNull());
    }

    @ParameterizedTest(name = "partial birth precision: {0}")
    @MethodSource("partialBirths")
    void partialBirthDatesNeverInventMonthOrDay(String precision, UUID id) throws Exception
    {
        assertEquals(precision, jdbc.queryForObject("SELECT birth_precision FROM " + table(first, "patients")
            + " WHERE id = ?", String.class, id));
        JsonNode body = detail(login(owner).get(path(id)));
        assertEquals(id.toString(), body.get("id").asString());
        assertTrue(body.get("dateOfBirth").isNull());
    }

    static Stream<Arguments> partialBirths()
    {
        return Stream.of(Arguments.of("YEAR_ONLY", new UUID(0, 103)),
            Arguments.of("YEAR_MONTH", new UUID(0, 104)));
    }

    @ParameterizedTest(name = "phone ordering: {0}")
    @MethodSource("phoneSelections")
    void phoneMatchesCurrentPrimaryTypeNewestAndIdListingOrder(String ordering, UUID id, String expected)
        throws Exception
    {
        Browser browser = login(owner);
        JsonNode body = detail(browser.get(path(id)));
        assertEquals(id.toString(), body.get("id").asString());
        assertEquals(expected, body.get("phone").asString(), ordering);
        HttpResponse<String> listing = browser.get("/api/patients?search="
            + encode("PHONE-DETAIL-" + id.getLeastSignificantBits()));
        assertEquals(200, listing.statusCode());
        assertNoStore(listing);
        JsonNode rows = JSON.readTree(listing.body()).get("content");
        assertEquals(1, rows.size());
        assertEquals(id.toString(), rows.get(0).get("id").asString());
        assertEquals(expected, rows.get(0).get("phone").asString(), "Both contracts use the literal expected winner");
    }

    static Stream<Arguments> phoneSelections()
    {
        return Stream.of(Arguments.of("primary mobile beats nonprimary phone", new UUID(0, 105), "+21621111002"),
            Arguments.of("phone beats newer mobile when primary ties", new UUID(0, 106), "+21622222001"),
            Arguments.of("newest phone wins when primary and type tie", new UUID(0, 107), "+21623333002"),
            Arguments.of("descending UUID breaks equal timestamps", new UUID(0, 108), "+21624444002"),
            Arguments.of("false primary sorts before null", new UUID(0, 109), "+21625555001"));
    }

    @Test
    void foreignAndUnknownUuidsReturnIndistinguishableSafeNotFoundErrors() throws Exception
    {
        Browser browser = login(owner);
        JsonNode foreign = assertError(browser.get(path(FOREIGN_PATIENT)), 404, "PATIENT_NOT_FOUND");
        JsonNode unknown = assertError(browser.get(path(new UUID(0, 999999))), 404, "PATIENT_NOT_FOUND");
        assertEquals(foreign, unknown, "Not-found errors must not reveal whether a foreign patient exists");
    }

    @Test
    void cabinetPredicateRejectsForeignCabinetRowEvenInsideResolvedTenantSchema() throws Exception
    {
        assertError(login(owner).get(path(WRONG_CABINET_PATIENT)), 404, "PATIENT_NOT_FOUND");
    }

    @ParameterizedTest(name = "malformed patient UUID: {0}")
    @ValueSource(strings = {"not-a-uuid", "00000000-0000-0000-0000-00000000000g"})
    void malformedUuidReturnsSafeInvalidInput(String value) throws Exception
    {
        HttpResponse<String> response = login(owner).get("/api/patients/" + value);
        assertError(response, 400, "INVALID_INPUT");
        assertFalse(response.body().contains(value), "Rejected input must not be echoed");
    }

    @Test
    void identicalUuidsResolveOnlyWithinAuthenticatedTenantAcrossReusedConnections() throws Exception
    {
        Browser firstBrowser = login(owner);
        Browser secondBrowser = login(otherOwner);
        assertFirstPatient(detail(firstBrowser.get(path(PATIENT))));
        JsonNode other = detail(secondBrowser.get(path(PATIENT)));
        assertEquals(PATIENT.toString(), other.get("id").asString());
        assertEquals("OTHER-DETAIL-101", other.get("patientNumber").asString());
        assertEquals("Independent", other.get("firstName").asString());
        assertEquals("Boundary", other.get("familyName").asString());
        assertEquals("1991-07-09", other.get("dateOfBirth").asString());
        assertEquals(FOREIGN_NOTE, other.get("initialNote").asString());
        assertEquals(CREATED.minusSeconds(7), Instant.parse(other.get("createdAt").asString()));
        assertTrue(other.get("phone").isNull());
        assertFirstPatient(detail(firstBrowser.get(path(PATIENT))));
    }

    @Test
    void spoofedHeadersAndQueryParametersCannotSelectCabinetOrActor() throws Exception
    {
        Browser browser = login(owner);
        assertFirstPatient(detail(browser.get(spoofedPath(PATIENT, second), spoofedHeaders(second, otherOwner))));
        assertError(browser.get(spoofedPath(FOREIGN_PATIENT, second), spoofedHeaders(second, otherOwner)),
            404, "PATIENT_NOT_FOUND");
    }

    @Test
    void secretaryWithoutExplicitPermissionIsDeniedEvenWithSpoofedOwner() throws Exception
    {
        Browser browser = login(secretary);
        assertError(browser.get(path(PATIENT)), 403, "ACCESS_DENIED");
        assertError(browser.get(spoofedPath(PATIENT, first), spoofedHeaders(first, owner)), 403, "ACCESS_DENIED");
    }

    @ParameterizedTest(name = "secretary permission: {0}")
    @ValueSource(strings = {"NONE", "READ", "READ_WRITE"})
    void secretaryRequiresReadCapabilityRatherThanInheritingDoctorPermissions(String permission) throws Exception
    {
        UUID grant = grant(secretaryMembership, permission);
        try
        {
            Browser browser = login(secretary);
            if ("NONE".equals(permission))
            {
                assertError(browser.get(path(PATIENT)), 403, "ACCESS_DENIED");
            } else
            {
                assertFirstPatient(detail(browser.get(path(PATIENT))));
            }
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("membership_permissions") + " WHERE id = ?", grant);
        }
    }

    @Test
    void permissionRevocationTakesEffectOnExistingSession() throws Exception
    {
        UUID grant = grant(secretaryMembership, "READ");
        Browser browser = login(secretary);
        try
        {
            assertFirstPatient(detail(browser.get(path(PATIENT))));
            jdbc.update("UPDATE " + platformTable("membership_permissions") + " SET revoked_at = now() WHERE id = ?",
                grant);
            assertError(browser.get(path(PATIENT)), 403, "ACCESS_DENIED");
        } finally
        {
            jdbc.update("UPDATE " + platformTable("membership_permissions") + " SET revoked_at = NULL WHERE id = ?",
                grant);
            jdbc.update("DELETE FROM " + platformTable("membership_permissions") + " WHERE id = ?", grant);
        }
    }

    @ParameterizedTest(name = "live membership change: {0}")
    @ValueSource(strings = {"INACTIVE", "REVOKED"})
    void membershipIsRevalidatedWithoutReloginAndRestoredInFinally(String change) throws Exception
    {
        Browser browser = login(owner);
        String memberships = platformTable("cabinet_memberships");
        try
        {
            assertFirstPatient(detail(browser.get(path(PATIENT))));
            if ("INACTIVE".equals(change))
            {
                jdbc.update("UPDATE " + memberships + " SET status = 'INACTIVE' WHERE id = ?", ownerMembership);
            } else
            {
                jdbc.update("UPDATE " + memberships + " SET revoked_at = now() WHERE id = ?", ownerMembership);
            }
            assertError(browser.get(path(PATIENT)), 403, "ACCESS_DENIED");
        } finally
        {
            jdbc.update("UPDATE " + memberships + " SET status = 'ACTIVE', revoked_at = NULL WHERE id = ?",
                ownerMembership);
        }
        assertFirstPatient(detail(browser.get(path(PATIENT))));
    }

    @Test
    void noMembershipCannotSelectCabinetUsingUntrustedInputs() throws Exception
    {
        Browser browser = login(outsider);
        assertError(browser.get(path(PATIENT)), 403, "ACCESS_DENIED");
        assertError(browser.get(spoofedPath(PATIENT, first), spoofedHeaders(first, owner)), 403, "ACCESS_DENIED");
    }

    @Test
    void ambiguousLiveMembershipsRequireSelectionDespiteSpoofedCabinet() throws Exception
    {
        Browser browser = login(outsider);
        UUID firstMembership = membership(first.cabinetId(), outsider, "OWNER");
        UUID secondMembership = membership(second.cabinetId(), outsider, "OWNER");
        try
        {
            assertError(browser.get(path(PATIENT)), 409, "CABINET_SELECTION_REQUIRED");
            assertError(browser.get(spoofedPath(PATIENT, first), spoofedHeaders(first, owner)),
                409, "CABINET_SELECTION_REQUIRED");
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("cabinet_memberships") + " WHERE id IN (?, ?)",
                firstMembership, secondMembership);
        }
    }

    @Test
    void anonymousDetailIsUnauthenticatedEvenWithSpoofedTenantAndOwner() throws Exception
    {
        Browser browser = new Browser(port);
        assertError(browser.get(path(PATIENT)), 401, "AUTHENTICATION_FAILED");
        assertError(browser.get(spoofedPath(PATIENT, first), spoofedHeaders(first, owner)),
            401, "AUTHENTICATION_FAILED");
    }

    @AfterAll
    void removeOnlyOwnedFixtures()
    {
        dataSource.close();
        resources.close();
        PLATFORM.close();
    }

    private void phoneFixtures()
    {
        for (int suffix = 105; suffix <= 109; suffix++)
        {
            patient(first, first.cabinetId(), new UUID(0, suffix), "PHONE-DETAIL-" + suffix, "PhoneFixture" + suffix,
                "Ordering", "UNKNOWN", null, null, null, null, CREATED);
        }
        contact(new UUID(0, 105), new UUID(2, 1), "PHONE", "21 111-001", "+21621111001", true, false, CREATED);
        contact(new UUID(0, 105), new UUID(2, 2), "MOBILE", "21 111-002", "+21621111002", true, true,
            CREATED.minusSeconds(1));
        contact(new UUID(0, 106), new UUID(2, 3), "PHONE", "22 222-001", "+21622222001", true, true, CREATED);
        contact(new UUID(0, 106), new UUID(2, 4), "MOBILE", "22 222-002", "+21622222002", true, true,
            CREATED.plusSeconds(1));
        contact(new UUID(0, 107), new UUID(2, 5), "PHONE", "23 333-001", "+21623333001", true, true, CREATED);
        contact(new UUID(0, 107), new UUID(2, 6), "PHONE", "23 333-002", "+21623333002", true, true,
            CREATED.plusSeconds(1));
        // Insert the larger UUID first so insertion order cannot accidentally satisfy the tie-breaker.
        contact(new UUID(0, 108), new UUID(2, 8), "PHONE", "24 444-002", "+21624444002", true, true, CREATED);
        contact(new UUID(0, 108), new UUID(2, 7), "PHONE", "24 444-001", "+21624444001", true, true, CREATED);
        contact(new UUID(0, 109), new UUID(2, 9), "PHONE", "25 555-001", "+21625555001", true, false, CREATED);
        contact(new UUID(0, 109), new UUID(2, 10), "PHONE", "25 555-002", "+21625555002", true, null,
            CREATED.plusSeconds(1));
    }

    private void patient(TestFixtures.CabinetFixture tenant, UUID cabinetId, UUID id, String number, String given,
                         String family, String precision, Integer year, Integer month, Integer day, String note,
                         Instant createdAt)
    {
        jdbc.update("INSERT INTO " + table(tenant, "patients")
            + " (id, cabinet_id, patient_number, legal_given_name, legal_family_name, birth_precision,"
            + " birth_year, birth_month, birth_day, initial_note, created_at, updated_at, created_by, updated_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, cabinetId, number, given, family,
            precision, year, month, day, note, Timestamp.from(createdAt), Timestamp.from(createdAt),
            tenant.ownerId(), tenant.ownerId());
    }

    private void contact(UUID patientId, UUID id, String type, String raw, String normalized, boolean current,
                         Boolean primary, Instant createdAt)
    {
        jdbc.update("INSERT INTO " + table(first, "patient_contacts")
            + " (id, patient_id, contact_type, value, value_normalized, is_current, is_primary_for_type, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, patientId, type, raw, normalized, current, primary,
            Timestamp.from(createdAt));
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

    private UUID membership(UUID cabinetId, UUID userId, String role)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + platformTable("cabinet_memberships")
            + " (id, cabinet_id, user_id, membership_role, staff_type, status)"
            + " VALUES (?, ?, ?, ?, 'SECRETARY', 'ACTIVE')", id, cabinetId, userId, role);
        return id;
    }

    private UUID grant(UUID membershipId, String permission)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + platformTable("membership_permissions")
            + " (id, membership_id, permission_code, granted_at) VALUES (?, ?, ?, now())",
            id, membershipId, permission);
        return id;
    }

    private Browser login(UUID userId) throws Exception
    {
        Browser browser = new Browser(port);
        assertEquals(204, browser.get("/api/auth/csrf").statusCode());
        assertEquals(200, browser.login(userId.toString()).statusCode());
        assertEquals(204, browser.get("/api/auth/csrf").statusCode());
        return browser;
    }

    private static JsonNode detail(HttpResponse<String> response)
    {
        assertEquals(200, response.statusCode(), "Authenticated patient detail must succeed");
        assertNoStore(response);
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.isObject());
        assertEquals(DETAIL_FIELDS.size(), body.size(), "No entities, clinical/financial placeholders or extra fields");
        for (String field : DETAIL_FIELDS)
        {
            assertTrue(body.has(field), "Missing public detail field: " + field);
        }
        UUID.fromString(body.get("id").asString());
        Instant.parse(body.get("createdAt").asString());
        return body;
    }

    private static void assertFirstPatient(JsonNode body)
    {
        assertEquals(PATIENT.toString(), body.get("id").asString());
        assertEquals("LEGACY-DETAIL-101", body.get("patientNumber").asString());
        assertEquals("Marielle", body.get("firstName").asString());
        assertEquals("Vellmont", body.get("familyName").asString());
        assertEquals("+21620444001", body.get("phone").asString());
        assertEquals("1988-02-29", body.get("dateOfBirth").asString());
        assertEquals(LEGACY_NOTE, body.get("initialNote").asString());
        assertEquals(CREATED, Instant.parse(body.get("createdAt").asString()));
    }

    private JsonNode assertError(HttpResponse<String> response, int status, String code)
    {
        assertEquals(status, response.statusCode(), "Expected safe patient detail failure");
        assertNoStore(response);
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.isObject());
        assertEquals(code, body.get("code").asString());
        assertTrue(body.get("message").isString());
        assertFalse(body.get("message").asString().isBlank());
        assertEquals(body.has("fieldErrors") ? 3 : 2, body.size(), "Only safe error fields may be serialized");
        for (String privateDetail : List.of("Marielle", "Vellmont", "Independent", "ForeignOnly", "PrivateFamily",
            "Fictional legacy note", FOREIGN_NOTE, "+21620444001", PLATFORM.schema(), first.schemaName(),
            second.schemaName(), "initialNote", "patientNumber", "stackTrace", "SQLException", "org.postgresql",
            "java.lang", "com.docukore", "password_hash"))
        {
            assertFalse(response.body().contains(privateDetail), "Error leaked patient or implementation details");
        }
        return body;
    }

    private static void assertNoStore(HttpResponse<String> response)
    {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    private static String path(UUID patientId)
    {
        return "/api/patients/" + patientId;
    }

    private static String spoofedPath(UUID patientId, TestFixtures.CabinetFixture cabinet)
    {
        return path(patientId) + "?cabinetId=" + cabinet.cabinetId() + "&tenantId=" + cabinet.cabinetId()
            + "&schema=" + encode(cabinet.schemaName()) + "&tenantSchema=" + encode(cabinet.schemaName())
            + "&userId=" + cabinet.ownerId();
    }

    private static Map<String, String> spoofedHeaders(TestFixtures.CabinetFixture cabinet, UUID userId)
    {
        return Map.of("X-Cabinet-Id", cabinet.cabinetId().toString(), "X-Tenant-Schema", cabinet.schemaName(),
            "X-User-Id", userId.toString());
    }

    private static String table(TestFixtures.CabinetFixture cabinet, String name)
    {
        return OwnedTestDatabase.identifier(cabinet.schemaName()) + "." + OwnedTestDatabase.identifier(name);
    }

    private static String platformTable(String name)
    {
        return OwnedTestDatabase.identifier(PLATFORM.schema()) + "." + OwnedTestDatabase.identifier(name);
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Cookie-backed real HTTP browser, matching creation and listing integration conventions. */
    private static final class Browser
    {
        /** Random application server port. */
        private final int port;
        /** Actual session and CSRF cookie store. */
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        /** Real HTTP client, not a controller mock. */
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        private Browser(int port)
        {
            this.port = port;
        }

        HttpResponse<String> get(String path) throws Exception
        {
            return get(path, Map.of());
        }

        HttpResponse<String> get(String path, Map<String, String> headers) throws Exception
        {
            var builder = HttpRequest.newBuilder(uri(path)).GET();
            headers.forEach(builder::header);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> login(String username) throws Exception
        {
            String body = "username=" + encode(username) + "&password=" + encode(PASSWORD);
            return client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(Map<String, Object> request) throws Exception
        {
            return client.send(HttpRequest.newBuilder(uri("/api/patients"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request))).build(),
                HttpResponse.BodyHandlers.ofString());
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
