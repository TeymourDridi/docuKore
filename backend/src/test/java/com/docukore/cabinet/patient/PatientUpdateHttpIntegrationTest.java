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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP PATCH contracts with independent PostgreSQL assertions and class-owned, never reset fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PatientUpdateHttpIntegrationTest extends SharedSpringBootTest
{
    /** Latest-migrated platform belonging only to this class. */
    private static final TestFixtures.OwnedPlatform PLATFORM = TestFixtures.createPlatform("patient_update_platform");
    /** Codec for actual HTTP request and response boundaries. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Fictional login credential unrelated to installation secrets. */
    private static final String PASSWORD = "Fictional-patient-update-passphrase!";
    /** Precisely persisted creation time which PATCH must never replace. */
    private static final Instant CREATED = Instant.parse("2024-06-15T12:00:00.123456Z");
    /** Omitted notes must retain even legacy whitespace verbatim. */
    private static final String LEGACY_NOTE = "  Fictional legacy update note.\r\nSecond line.\n  ";
    /** Exact detail and PATCH contract, without writable persistence metadata. */
    private static final List<String> DETAIL_FIELDS = List.of("id", "patientNumber", "firstName", "familyName",
        "phone", "dateOfBirth", "initialNote", "createdAt");
    /** Restricted runtime pool retired before owned schema cleanup. */
    private final HikariDataSource dataSource;
    /** Exact tenant ownership receipts, not a wildcard cleanup scope. */
    private final OwnedTestDatabase.Scope resources;
    /** Independent fixture queries use migration credentials; HTTP uses restricted runtime credentials. */
    private final JdbcTemplate jdbc;
    /** Encoder for real cookie-backed login accounts. */
    private final PasswordEncoder passwords;
    /** Actual random HTTP server port. */
    private final int port;
    /** Trusted owner of the first cabinet. */
    private UUID owner;
    /** Trusted owner of the independent second cabinet. */
    private UUID otherOwner;
    /** Secretary who needs an explicit READ_WRITE grant. */
    private UUID secretary;
    /** Authenticated account initially lacking any cabinet. */
    private UUID outsider;
    /** Class-owned tenant reused without resets. */
    private TestFixtures.CabinetFixture first;
    /** Second independently provisioned isolation tenant. */
    private TestFixtures.CabinetFixture second;
    /** Owner membership changed only in finally-protected live authorization tests. */
    private UUID ownerMembership;
    /** Secretary membership used for finally-protected grants. */
    private UUID secretaryMembership;
    /** Unique fictional phone suffixes across all cases in this class. */
    private int nextPhone = 20000000;

    PatientUpdateHttpIntegrationTest(@LocalServerPort int port, HikariDataSource dataSource,
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
    }

    @Test
    void updatesAllFiveFieldsWithCreationNormalizationAndExactPersistedDetailContract() throws Exception
    {
        UUID id = patient();
        Map<String, Object> original = stored(first, id);
        List<Map<String, Object>> oldContacts = contacts(first, id);
        Map<String, Integer> before = rowCounts(first);
        Map<String, Integer> otherCounts = rowCounts(second);
        Browser browser = login(owner);
        String rawPhone = "  00 33 (1) 23-45-67-89  ";
        String note = "  Fictional replacement note.\r\nSecond line.  ";

        JsonNode body = detail(browser.patch(path(id), Map.of("firstName", "  FictionalChanged  ",
            "familyName", "  UpdatedFamily  ", "phone", rawPhone, "dateOfBirth", "  1996-02-29  ",
            "initialNote", note)));

        Map<String, Object> persisted = stored(first, id);
        assertEquals("FictionalChanged", persisted.get("legal_given_name"));
        assertEquals("UpdatedFamily", persisted.get("legal_family_name"));
        assertBirth(persisted, "COMPLETE_DATE", 1996, 2, 29);
        assertEquals(note.strip(), persisted.get("initial_note"));
        assertImmutable(original, persisted, body);
        assertEquals(owner, persisted.get("updated_by"));
        assertTrue(((Timestamp) persisted.get("updated_at")).toInstant().isAfter(CREATED));
        assertEquals(persisted.get("legal_given_name"), body.get("firstName").asString());
        assertEquals(persisted.get("legal_family_name"), body.get("familyName").asString());
        assertEquals("1996-02-29", body.get("dateOfBirth").asString());
        assertEquals(persisted.get("initial_note"), body.get("initialNote").asString());
        assertEquals("+33123456789", body.get("phone").asString());
        assertRetired(oldContacts, contacts(first, id));
        Map<String, Object> current = currentPhone(id);
        assertEquals("PHONE", current.get("contact_type"));
        assertEquals(rawPhone.strip(), current.get("value"));
        assertEquals("+33123456789", current.get("value_normalized"));
        assertEquals(Boolean.TRUE, current.get("is_primary_for_type"));
        assertUpdateCounts(before, first, 1, 1);
        assertEquals(otherCounts, rowCounts(second));
        assertAudit(first, id, owner, "OWNER");
        assertEquals(body, detail(browser.get(path(id))), "GET must read the same committed eight persisted fields");
        assertListedPhone(browser, id, "+33123456789");
    }

    @ParameterizedTest(name = "independent name update: {0}")
    @ValueSource(strings = {"firstName", "familyName"})
    void updatesOneNameWithoutReplacingOmittedFields(String field) throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = stored(first, id);
        List<Map<String, Object>> oldContacts = contacts(first, id);
        JsonNode body = detail(login(owner).patch(path(id), Map.of(field, "  FictionalRenamed  ")));
        Map<String, Object> after = stored(first, id);
        assertEquals("FictionalRenamed", after.get("firstName".equals(field)
            ? "legal_given_name" : "legal_family_name"));
        assertEquals(before.get("firstName".equals(field) ? "legal_family_name" : "legal_given_name"),
            after.get("firstName".equals(field) ? "legal_family_name" : "legal_given_name"));
        assertBirth(after, "COMPLETE_DATE", 1988, 2, 29);
        assertEquals(LEGACY_NOTE, after.get("initial_note"));
        assertEquals(oldContacts, contacts(first, id));
        assertImmutable(before, after, body);
        assertAudit(first, id, owner, "OWNER");
    }

    @ParameterizedTest(name = "phone normalization: {0}")
    @MethodSource("supportedPhones")
    void normalizesSupportedPhoneFormsLikeCreation(String raw, String canonical) throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = stored(first, id);
        JsonNode body = detail(login(owner).patch(path(id), Map.of("phone", raw)));
        assertEquals(canonical, body.get("phone").asString());
        Map<String, Object> current = currentPhone(id);
        assertEquals(raw.strip(), current.get("value"));
        assertEquals(canonical, current.get("value_normalized"));
        Map<String, Object> after = stored(first, id);
        assertEquals(before.get("legal_given_name"), after.get("legal_given_name"));
        assertEquals(before.get("legal_family_name"), after.get("legal_family_name"));
        assertBirth(after, "COMPLETE_DATE", 1988, 2, 29);
        assertEquals(LEGACY_NOTE, after.get("initial_note"));
        assertAudit(first, id, owner, "OWNER");
    }

    static Stream<Arguments> supportedPhones()
    {
        return Stream.of(Arguments.of("  22.345.678  ", "+21622345678"),
            Arguments.of("00 44 (20) 7946-0958", "+442079460958"),
            Arguments.of("+1 (202) 555-0147", "+12025550147"),
            Arguments.of("+1234567", "+1234567"),
            Arguments.of("+123456789012345", "+123456789012345"));
    }

    @Test
    void changingPhoneRetiresEveryOldCurrentPhoneAndMobileButPreservesHistoryAndOtherContactTypes()
        throws Exception
    {
        UUID id = patient();
        String oldPhone = currentPhone(id).get("value_normalized").toString();
        contact(first, id, "PHONE", phone(), true, false, CREATED.plusSeconds(1));
        contact(first, id, "MOBILE", phone(), true, true, CREATED.plusSeconds(2));
        contact(first, id, "MOBILE", phone(), false, true, CREATED.minusSeconds(1));
        excludedContacts(id);
        List<Map<String, Object>> before = contacts(first, id);
        String replacement = phone();
        Browser browser = login(owner);

        JsonNode body = detail(browser.patch(path(id), Map.of("phone", replacement)));

        List<Map<String, Object>> after = contacts(first, id);
        assertRetired(before, after);
        assertEquals(before.size() + 1, after.size());
        assertEquals(replacement, currentPhone(id).get("value_normalized"));
        assertEquals(replacement, body.get("phone").asString());
        assertListedPhone(browser, id, replacement);
        assertEquals(replacement, detail(browser.get(path(id))).get("phone").asString());
        assertEquals(0, listing(browser, oldPhone).get("content").size(), "Ended phone must not remain searchable");
        assertAudit(first, id, owner, "OWNER");
    }

    @Test
    void nullPhoneClearsAllCurrentPhoneAndMobileWithoutFallbackOrDeletingAnyHistory() throws Exception
    {
        UUID id = patient();
        String oldPhone = currentPhone(id).get("value_normalized").toString();
        contact(first, id, "PHONE", phone(), true, false, CREATED.plusSeconds(1));
        contact(first, id, "MOBILE", phone(), true, true, CREATED.plusSeconds(2));
        contact(first, id, "PHONE", phone(), false, true, CREATED.minusSeconds(1));
        excludedContacts(id);
        List<Map<String, Object>> before = contacts(first, id);
        Map<String, Integer> counts = rowCounts(first);
        Browser browser = login(owner);

        JsonNode body = detail(browser.patch(path(id), patchField("phone", null)));

        assertTrue(body.get("phone").isNull());
        List<Map<String, Object>> after = contacts(first, id);
        assertEquals(before.size(), after.size());
        assertRetired(before, after);
        assertEquals(0, currentPhoneCount(id));
        assertTrue(detail(browser.get(path(id))).get("phone").isNull());
        assertListedPhone(browser, id, null);
        assertEquals(0, listing(browser, oldPhone).get("content").size());
        assertUpdateCounts(counts, first, 0, 1);
        assertAudit(first, id, owner, "OWNER");
    }

    @ParameterizedTest(name = "canonical selected contact no-op: {0}")
    @ValueSource(strings = {"PRIMARY_MOBILE", "PHONE_TYPE", "NEWEST_PHONE", "UUID_TIE", "NULL_PRIMARY"})
    void normalizedSelectedPhoneIsNoOpWithoutDuplicateContactsIncludingSelectedMobile(String ordering)
        throws Exception
    {
        UUID id = patient(first, first.cabinetId(), UUID.randomUUID(), "UNKNOWN");
        String selected = phone();
        String losing = phone();
        if ("PRIMARY_MOBILE".equals(ordering))
        {
            contact(first, id, "PHONE", losing, true, false, CREATED.plusSeconds(1));
            contact(first, id, "MOBILE", selected, true, true, CREATED);
        } else if ("PHONE_TYPE".equals(ordering))
        {
            contact(first, id, "PHONE", selected, true, true, CREATED);
            contact(first, id, "MOBILE", losing, true, true, CREATED.plusSeconds(1));
        } else if ("NEWEST_PHONE".equals(ordering))
        {
            contact(first, id, "PHONE", losing, true, true, CREATED);
            contact(first, id, "PHONE", selected, true, true, CREATED.plusSeconds(1));
        } else if ("UUID_TIE".equals(ordering))
        {
            contact(first, id, new UUID(5, nextPhone++), "PHONE", selected, true, true, CREATED);
            contact(first, id, new UUID(4, nextPhone++), "PHONE", losing, true, true, CREATED);
        } else
        {
            contact(first, id, "PHONE", selected, true, false, CREATED);
            contact(first, id, "PHONE", losing, true, null, CREATED.plusSeconds(1));
        }
        excludedContacts(id);
        Map<String, Object> before = snapshot(first, id);
        Browser browser = login(owner);
        String formatted = "  00 " + selected.substring(1, 4) + " " + selected.substring(4, 6)
            + "-" + selected.substring(6) + "  ";

        JsonNode body = detail(browser.patch(path(id), Map.of("phone", formatted)));

        assertEquals(selected, body.get("phone").asString());
        assertEquals(before, snapshot(first, id), "Canonical equality must not mutate raw contact/history/timestamps");
        assertListedPhone(browser, id, selected);
        assertEquals(selected, detail(browser.get(path(id))).get("phone").asString());
    }

    @ParameterizedTest(name = "reuse own phone: {0}")
    @ValueSource(strings = {"NONSELECTED_CURRENT", "HISTORICAL"})
    void ownNonselectedOrHistoricalPhoneCanBecomeTheSingleCurrentPhone(String source) throws Exception
    {
        UUID id = patient();
        String replacement = phone();
        contact(first, id, "MOBILE", replacement, "NONSELECTED_CURRENT".equals(source), false,
            CREATED.minusSeconds(1));
        List<Map<String, Object>> before = contacts(first, id);

        JsonNode body = detail(login(owner).patch(path(id), Map.of("phone", replacement)));

        assertEquals(replacement, body.get("phone").asString());
        assertEquals(before.size() + 1, contacts(first, id).size());
        assertRetired(before, contacts(first, id));
        assertEquals("PHONE", currentPhone(id).get("contact_type"));
        assertEquals(replacement, currentPhone(id).get("value_normalized"));
        assertAudit(first, id, owner, "OWNER");
    }

    @ParameterizedTest(name = "supported complete birth date: {0}")
    @ValueSource(strings = {"0001-01-01", "1900-02-28", "2000-02-29", "1996-04-30"})
    void replacesBirthPrecisionAndAllComponentsWithSupportedCompleteDate(String raw) throws Exception
    {
        UUID id = patient(first, first.cabinetId(), UUID.randomUUID(), "YEAR_ONLY");
        LocalDate expected = LocalDate.parse(raw);
        JsonNode body = detail(login(owner).patch(path(id), Map.of("dateOfBirth", raw)));
        assertBirth(stored(first, id), "COMPLETE_DATE", expected.getYear(), expected.getMonthValue(),
            expected.getDayOfMonth());
        assertEquals(raw, body.get("dateOfBirth").asString());
        assertAudit(first, id, owner, "OWNER");
    }

    @ParameterizedTest(name = "omitted birth precision: {0}")
    @ValueSource(strings = {"COMPLETE_DATE", "YEAR_MONTH", "YEAR_ONLY", "UNKNOWN"})
    void omittedBirthDatePreservesExistingPrecisionAndEveryComponent(String precision) throws Exception
    {
        UUID id = patient(first, first.cabinetId(), UUID.randomUUID(), precision);
        Map<String, Object> before = stored(first, id);
        JsonNode body = detail(login(owner).patch(path(id), Map.of("firstName", "FictionalNewName")));
        Map<String, Object> after = stored(first, id);
        assertBirth(after, precision, (Integer) before.get("birth_year"), (Integer) before.get("birth_month"),
            (Integer) before.get("birth_day"));
        if ("COMPLETE_DATE".equals(precision))
        {
            assertEquals("1988-02-29", body.get("dateOfBirth").asString());
        } else
        {
            assertTrue(body.get("dateOfBirth").isNull(), "PATCH must not invent missing calendar components");
        }
        assertEquals(LEGACY_NOTE, after.get("initial_note"));
        assertAudit(first, id, owner, "OWNER");
    }

    @ParameterizedTest(name = "explicit null birth from: {0}")
    @ValueSource(strings = {"COMPLETE_DATE", "YEAR_MONTH", "YEAR_ONLY"})
    void nullBirthDateClearsAllComponentsAndSetsUnknownPrecision(String precision) throws Exception
    {
        UUID id = patient(first, first.cabinetId(), UUID.randomUUID(), precision);
        JsonNode body = detail(login(owner).patch(path(id), patchField("dateOfBirth", null)));
        assertBirth(stored(first, id), "UNKNOWN", null, null, null);
        assertTrue(body.get("dateOfBirth").isNull());
        assertAudit(first, id, owner, "OWNER");
    }

    @ParameterizedTest(name = "blank optional clears: {0}")
    @ValueSource(strings = {"dateOfBirth", "initialNote"})
    void blankBirthDateOrNoteClearsLikeCreationNormalization(String field) throws Exception
    {
        UUID id = patient();
        JsonNode body = detail(login(owner).patch(path(id), Map.of(field, " \t\r\n ")));
        assertTrue(body.get(field).isNull());
        Map<String, Object> after = stored(first, id);
        if ("dateOfBirth".equals(field))
        {
            assertBirth(after, "UNKNOWN", null, null, null);
            assertEquals(LEGACY_NOTE, after.get("initial_note"));
        } else
        {
            assertNull(after.get("initial_note"));
            assertBirth(after, "COMPLETE_DATE", 1988, 2, 29);
        }
        assertAudit(first, id, owner, "OWNER");
    }

    @Test
    void explicitNullOptionalsClearTogetherWhileOmittedNamesAndImmutableValuesRemain() throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = stored(first, id);
        Map<String, Object> request = patchField("phone", null);
        request.put("dateOfBirth", null);
        request.put("initialNote", null);
        JsonNode body = detail(login(owner).patch(path(id), request));
        Map<String, Object> after = stored(first, id);
        assertBirth(after, "UNKNOWN", null, null, null);
        assertNull(after.get("initial_note"));
        assertEquals(0, currentPhoneCount(id));
        assertEquals(before.get("legal_given_name"), after.get("legal_given_name"));
        assertEquals(before.get("legal_family_name"), after.get("legal_family_name"));
        assertImmutable(before, after, body);
        for (String field : List.of("phone", "dateOfBirth", "initialNote"))
        {
            assertTrue(body.get(field).isNull());
        }
        assertAudit(first, id, owner, "OWNER");
    }

    @Test
    void acceptsCreationBoundaryLengthsForNamesAndNote() throws Exception
    {
        UUID id = patient();
        JsonNode body = detail(login(owner).patch(path(id), Map.of("firstName", "F".repeat(255),
            "familyName", "P".repeat(255), "initialNote", "N".repeat(10000))));
        Map<String, Object> after = stored(first, id);
        assertEquals("F".repeat(255), after.get("legal_given_name"));
        assertEquals("P".repeat(255), after.get("legal_family_name"));
        assertEquals("N".repeat(10000), after.get("initial_note"));
        assertEquals(255, body.get("firstName").asString().length());
        assertEquals(255, body.get("familyName").asString().length());
        assertEquals(10000, body.get("initialNote").asString().length());
        assertAudit(first, id, owner, "OWNER");
    }

    @ParameterizedTest(name = "invalid PATCH field: {0}")
    @MethodSource("invalidFields")
    void rejectsInvalidSuppliedFieldsAtomicallyWithoutEchoingTheirValues(String field, Object value)
        throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = snapshot(first, id);
        Map<String, Object> request = patchField(field, value);
        request.put("initialNote".equals(field) ? "familyName" : "initialNote", "FictionalRejectedCanary");

        HttpResponse<String> response = login(owner).patch(path(id), request);

        assertError(response, 400, "INVALID_INPUT");
        JsonNode errors = JSON.readTree(response.body()).get("fieldErrors");
        assertNotNull(errors);
        assertTrue(errors.isObject());
        assertTrue(errors.has(field));
        assertFalse(response.body().contains("FictionalRejectedCanary"));
        if (value instanceof String text && !text.isBlank())
        {
            assertFalse(response.body().contains(text), "Validation must not echo rejected values");
        }
        assertEquals(before, snapshot(first, id));
    }

    static Stream<Arguments> invalidFields()
    {
        return Stream.of(Arguments.of("firstName", null), Arguments.of("familyName", null),
            Arguments.of("firstName", " \t "), Arguments.of("familyName", " \r\n "),
            Arguments.of("firstName", "F".repeat(256)), Arguments.of("familyName", "P".repeat(256)),
            Arguments.of("phone", " "), Arguments.of("phone", "1234567"),
            Arguments.of("phone", "21620123456"), Arguments.of("phone", "+01234567"),
            Arguments.of("phone", "+123456"), Arguments.of("phone", "+1234567890123456"),
            Arguments.of("phone", "20CALLME"), Arguments.of("phone", "+21620/123456"),
            Arguments.of("phone", "+21620++123456"), Arguments.of("phone", "٢١٦٢٠١٢٣٤٥٦"),
            Arguments.of("phone", "2".repeat(256)),
            Arguments.of("dateOfBirth", LocalDate.now().plusDays(1).toString()),
            Arguments.of("dateOfBirth", "1900-02-29"), Arguments.of("dateOfBirth", "2023-02-29"),
            Arguments.of("dateOfBirth", "1996-04-31"), Arguments.of("dateOfBirth", "not-a-date"),
            Arguments.of("dateOfBirth", "1988"), Arguments.of("dateOfBirth", "1988-02"),
            Arguments.of("dateOfBirth", "0000-01-01"), Arguments.of("dateOfBirth", "-0001-01-01"),
            Arguments.of("dateOfBirth", "+10000-01-01"), Arguments.of("dateOfBirth", "1988-02-29T00:00:00Z"),
            Arguments.of("initialNote", "N".repeat(10001)));
    }

    @ParameterizedTest(name = "strict text PATCH field: {0}, value: {1}")
    @MethodSource("nonTextualFields")
    void rejectsNumbersBooleansArraysAndObjectsForEveryTextField(String field, Object value) throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = snapshot(first, id);
        assertError(login(owner).patch(path(id), patchField(field, value)), 400, "INVALID_INPUT");
        assertEquals(before, snapshot(first, id));
    }

    static Stream<Arguments> nonTextualFields()
    {
        return Stream.of("firstName", "familyName", "phone", "dateOfBirth", "initialNote")
            .flatMap(field -> Stream.of(123, true, List.of("FictionalArrayCanary"),
                Map.of("value", "FictionalObjectCanary")).map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest(name = "unknown or immutable PATCH field: {0}")
    @ValueSource(strings = {
        "id", "patientNumber", "createdAt", "cabinetId", "tenantId", "schemaName", "schema",
        "tenantSchema", "userId", "createdBy", "updatedBy", "updatedAt", "archivedAt",
        "legacySourceSystemId", "legacyRecordId", "importBatchId", "birthPrecision", "birthYear",
        "birthMonth", "birthDay", "legalGivenName", "legalFamilyName", "contacts", "unknownField"
    })
    void rejectsUnknownAndImmutableFieldsRatherThanSilentlyIgnoringOrWritingThem(String field) throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = snapshot(first, id);
        Map<String, Object> request = patchField(field, "FictionalImmutableCanary");
        request.put("firstName", "FictionalRejectedRename");
        HttpResponse<String> response = login(owner).patch(path(id), request);
        assertError(response, 400, "INVALID_INPUT");
        assertFalse(response.body().contains("FictionalImmutableCanary"));
        assertFalse(response.body().contains("FictionalRejectedRename"));
        assertEquals(before, snapshot(first, id));
    }

    @ParameterizedTest(name = "invalid root PATCH JSON: {0}")
    @ValueSource(strings = {"null", "[]", "\"FictionalRootCanary\"", "{\"firstName\":"})
    void rejectsNonobjectOrMalformedPatchDocumentsWithoutMutation(String document) throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = snapshot(first, id);
        assertError(login(owner).patchJson(path(id), document, true, Map.of()), 400, "INVALID_INPUT");
        assertEquals(before, snapshot(first, id));
    }

    @ParameterizedTest(name = "PATCH no-op: {0}")
    @ValueSource(strings = {"EMPTY", "NORMALIZED_IDENTICAL", "ALREADY_NULL"})
    void noOpReturnsPersistedDetailWithoutAuditOrTimestampMutation(String mode) throws Exception
    {
        UUID id = "ALREADY_NULL".equals(mode)
            ? patient(first, first.cabinetId(), UUID.randomUUID(), "UNKNOWN") : patient();
        Map<String, Object> request = new LinkedHashMap<>();
        if ("NORMALIZED_IDENTICAL".equals(mode))
        {
            String phone = currentPhone(id).get("value_normalized").toString();
            request.put("firstName", "  FictionalGiven  ");
            request.put("familyName", "  FictionalFamily  ");
            request.put("phone", "00 " + phone.substring(1));
            request.put("dateOfBirth", " 1988-02-29 ");
            // Compare normalized request to a normalized persisted note; legacy whitespace is not a no-op.
            jdbc.update("UPDATE " + table(first, "patients") + " SET initial_note = ? WHERE id = ?",
                "Fictional normalized note.", id);
            request.put("initialNote", "  Fictional normalized note.  ");
        } else if ("ALREADY_NULL".equals(mode))
        {
            jdbc.update("UPDATE " + table(first, "patients") + " SET initial_note = NULL WHERE id = ?", id);
            request.put("phone", null);
            request.put("dateOfBirth", null);
            request.put("initialNote", null);
        }
        Map<String, Object> before = snapshot(first, id);
        Browser browser = login(owner);
        JsonNode body = detail(browser.patch(path(id), request));
        assertEquals(before, snapshot(first, id));
        assertEquals(body, detail(browser.get(path(id))));
        assertImmutable(stored(first, id), stored(first, id), body);
    }

    @Test
    void foreignUnknownAndWrongCabinetRowsReturnIndistinguishableNotFoundWithoutMutation() throws Exception
    {
        UUID foreign = patient(second, second.cabinetId(), UUID.randomUUID(), "UNKNOWN");
        UUID mismatched = patient(first, second.cabinetId(), UUID.randomUUID(), "UNKNOWN");
        Map<String, Object> foreignBefore = snapshot(second, foreign);
        Map<String, Object> mismatchBefore = snapshot(first, mismatched);
        Map<String, Integer> counts = rowCounts(first);
        Browser browser = login(owner);
        Map<String, Object> request = Map.of("firstName", "FictionalForeignCanary");

        JsonNode foreignError = assertError(browser.patch(path(foreign), request), 404, "PATIENT_NOT_FOUND");
        JsonNode unknownError = assertError(browser.patch(path(UUID.randomUUID()), request), 404, "PATIENT_NOT_FOUND");
        JsonNode mismatchError = assertError(browser.patch(path(mismatched), request), 404, "PATIENT_NOT_FOUND");

        assertEquals(foreignError, unknownError);
        assertEquals(foreignError, mismatchError);
        assertEquals(foreignBefore, snapshot(second, foreign));
        assertEquals(mismatchBefore, snapshot(first, mismatched));
        assertEquals(counts, rowCounts(first));
    }

    @ParameterizedTest(name = "malformed PATCH UUID: {0}")
    @ValueSource(strings = {"not-a-uuid", "00000000-0000-0000-0000-00000000000g"})
    void malformedPatientUuidReturnsSafeInvalidInput(String value) throws Exception
    {
        Map<String, Integer> before = rowCounts(first);
        HttpResponse<String> response = login(owner).patch("/api/patients/" + value,
            Map.of("firstName", "FictionalMalformedCanary"));
        assertError(response, 400, "INVALID_INPUT");
        assertFalse(response.body().contains(value));
        assertEquals(before, rowCounts(first));
    }

    @Test
    void identicalUuidsAndSpoofedTenantActorInputsNeverCrossCabinetsAcrossReusedConnections() throws Exception
    {
        UUID id = UUID.randomUUID();
        patient(first, first.cabinetId(), id, "COMPLETE_DATE");
        patient(second, second.cabinetId(), id, "YEAR_ONLY");
        Map<String, Object> otherBefore = snapshot(second, id);
        Browser browser = login(owner);
        Browser otherBrowser = login(otherOwner);

        JsonNode body = detail(browser.patch(spoofedPath(id, second), Map.of("firstName", "FirstCabinetOnly"),
            true, spoofedHeaders(second, otherOwner)));

        assertEquals("FirstCabinetOnly", body.get("firstName").asString());
        assertEquals(otherBefore, snapshot(second, id));
        assertEquals(first.cabinetId(), stored(first, id).get("cabinet_id"));
        assertEquals(owner, stored(first, id).get("updated_by"));
        assertAudit(first, id, owner, "OWNER");
        Map<String, Object> firstAfter = snapshot(first, id);
        detail(otherBrowser.patch(path(id), Map.of("familyName", "SecondCabinetOnly")));
        assertEquals(firstAfter, snapshot(first, id));
        assertEquals("SecondCabinetOnly", stored(second, id).get("legal_family_name"));
        assertAudit(second, id, otherOwner, "OWNER");
        detail(browser.patch(path(id), Map.of("familyName", "FirstCabinetAgain")));
        assertEquals("FirstCabinetAgain", stored(first, id).get("legal_family_name"));
        assertEquals("SecondCabinetOnly", stored(second, id).get("legal_family_name"));
        assertEquals(2, auditRows(first, id).size());
    }

    @ParameterizedTest(name = "secretary PATCH permission: {0}")
    @ValueSource(strings = {"NONE", "READ", "READ_WRITE"})
    void secretaryNeedsExistingReadWriteCapabilityAndNeverInheritsOwnerPermissions(String permission)
        throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = snapshot(first, id);
        UUID grant = grant(secretaryMembership, permission);
        try
        {
            Browser browser = login(secretary);
            if ("READ_WRITE".equals(permission))
            {
                detail(browser.patch(path(id), Map.of("firstName", "SecretaryWriteAllowed")));
                assertEquals("SecretaryWriteAllowed", stored(first, id).get("legal_given_name"));
                assertEquals(secretary, stored(first, id).get("updated_by"));
                assertAudit(first, id, secretary, "SECRETARY");
            } else
            {
                assertError(browser.patch(path(id), Map.of("firstName", "SecretaryWriteDenied")),
                    403, "ACCESS_DENIED");
                assertEquals(before, snapshot(first, id));
            }
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("membership_permissions") + " WHERE id = ?", grant);
        }
    }

    @Test
    void ungrantedSecretaryCannotWriteEvenWithSpoofedOwnerAndTenant() throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = snapshot(first, id);
        Browser browser = login(secretary);
        assertError(browser.patch(path(id), Map.of("firstName", "UngrantDenied")), 403, "ACCESS_DENIED");
        assertError(browser.patch(spoofedPath(id, first), Map.of("firstName", "SpoofDenied"), true,
            spoofedHeaders(first, owner)), 403, "ACCESS_DENIED");
        assertEquals(before, snapshot(first, id));
    }

    @Test
    void permissionRevocationIsEnforcedOnTheSameAuthenticatedSession() throws Exception
    {
        UUID id = patient();
        UUID grant = grant(secretaryMembership, "READ_WRITE");
        Browser browser = login(secretary);
        try
        {
            detail(browser.patch(path(id), Map.of("firstName", "BeforeGrantRevocation")));
            Map<String, Object> beforeDenied = snapshot(first, id);
            jdbc.update("UPDATE " + platformTable("membership_permissions") + " SET revoked_at = now() WHERE id = ?",
                grant);
            assertError(browser.patch(path(id), Map.of("familyName", "AfterGrantRevocationDenied")),
                403, "ACCESS_DENIED");
            assertEquals(beforeDenied, snapshot(first, id));
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("membership_permissions") + " WHERE id = ?", grant);
        }
    }

    @ParameterizedTest(name = "live PATCH availability change: {0}")
    @ValueSource(strings = {"INACTIVE", "REVOKED", "SUSPENDED", "PROVISIONING_FAILED"})
    void revalidatesMembershipAndCabinetAvailabilityWithoutRelogin(String change) throws Exception
    {
        UUID id = patient();
        Browser browser = login(owner);
        detail(browser.patch(path(id), Map.of("firstName", "BeforeAvailabilityChange")));
        Map<String, Object> beforeDenied = snapshot(first, id);
        String memberships = platformTable("cabinet_memberships");
        String cabinets = platformTable("cabinets");
        try
        {
            if ("INACTIVE".equals(change))
            {
                jdbc.update("UPDATE " + memberships + " SET status = 'INACTIVE' WHERE id = ?", ownerMembership);
            } else if ("REVOKED".equals(change))
            {
                jdbc.update("UPDATE " + memberships + " SET revoked_at = now() WHERE id = ?", ownerMembership);
            } else if ("SUSPENDED".equals(change))
            {
                jdbc.update("UPDATE " + cabinets + " SET administrative_status = 'SUSPENDED' WHERE id = ?",
                    first.cabinetId());
            } else
            {
                jdbc.update("UPDATE " + cabinets + " SET provisioning_status = 'PROVISIONING_FAILED' WHERE id = ?",
                    first.cabinetId());
            }
            assertError(browser.patch(path(id), Map.of("familyName", "AfterAvailabilityChangeDenied")),
                403, "ACCESS_DENIED");
            assertEquals(beforeDenied, snapshot(first, id));
        } finally
        {
            jdbc.update("UPDATE " + memberships + " SET status = 'ACTIVE', revoked_at = NULL WHERE id = ?",
                ownerMembership);
            jdbc.update("UPDATE " + cabinets + " SET administrative_status = 'AVAILABLE',"
                + " provisioning_status = 'ACTIVE' WHERE id = ?", first.cabinetId());
        }
        detail(browser.patch(path(id), Map.of("familyName", "AfterAvailabilityRestored")));
        assertEquals("AfterAvailabilityRestored", stored(first, id).get("legal_family_name"));
    }

    @Test
    void absentOrAmbiguousMembershipCannotSelectASpoofedCabinet() throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = snapshot(first, id);
        Browser browser = login(outsider);
        assertError(browser.patch(spoofedPath(id, first), Map.of("firstName", "OutsiderDenied"), true,
            spoofedHeaders(first, owner)), 403, "ACCESS_DENIED");
        UUID firstMembership = membership(first.cabinetId(), outsider, "OWNER");
        UUID secondMembership = membership(second.cabinetId(), outsider, "OWNER");
        try
        {
            assertError(browser.patch(spoofedPath(id, first), Map.of("firstName", "AmbiguousDenied"), true,
                spoofedHeaders(first, owner)), 409, "CABINET_SELECTION_REQUIRED");
            assertEquals(before, snapshot(first, id));
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("cabinet_memberships") + " WHERE id IN (?, ?)",
                firstMembership, secondMembership);
        }
    }

    @Test
    void unauthenticatedAndMissingOrInvalidCsrfRequestsCannotUpdate() throws Exception
    {
        UUID id = patient();
        Map<String, Object> before = snapshot(first, id);
        Map<String, Object> request = Map.of("firstName", "SecurityRejectedCanary");
        Browser browser = login(owner);
        assertError(browser.patch(path(id), request, false, Map.of()), 403, "ACCESS_DENIED");
        assertError(browser.patch(path(id), request, false, Map.of("X-XSRF-TOKEN", "fictional-invalid-token")),
            403, "ACCESS_DENIED");
        Browser anonymous = new Browser(port);
        assertEquals(204, anonymous.get("/api/auth/csrf").statusCode());
        assertError(anonymous.patch(spoofedPath(id, first), request, true, spoofedHeaders(first, owner)),
            401, "AUTHENTICATION_FAILED");
        assertEquals(before, snapshot(first, id));
    }

    @Test
    void auditInsertFailureRollsBackPatientContactRetirementAndReplacementAtomically() throws Exception
    {
        UUID id = patient();
        contact(first, id, "MOBILE", phone(), true, false, CREATED);
        Map<String, Object> before = snapshot(first, id);
        Map<String, Integer> counts = rowCounts(first);
        Browser browser = login(owner);
        Map<String, Object> request = Map.of("firstName", "AuditRollbackCanary", "familyName", "AuditRollbackFamily",
            "phone", phone(), "dateOfBirth", "1996-02-29", "initialNote", "Fictional audit rollback note.");
        String audit = table(first, "audit_logs");
        jdbc.execute("ALTER TABLE " + audit
            + " ADD CONSTRAINT task954_reject_update CHECK (operation <> 'UPDATE') NOT VALID");
        try
        {
            assertError(browser.patch(path(id), request), 500, "UNAVAILABLE");
            assertEquals(before, snapshot(first, id));
            assertEquals(counts, rowCounts(first));
        } finally
        {
            jdbc.execute("ALTER TABLE " + audit + " DROP CONSTRAINT task954_reject_update");
        }
        detail(browser.patch(path(id), request));
        assertEquals("AuditRollbackCanary", stored(first, id).get("legal_given_name"));
        assertEquals(request.get("phone"), currentPhone(id).get("value_normalized"));
        assertAudit(first, id, owner, "OWNER");
        assertUpdateCounts(counts, first, 1, 1);
    }

    @ParameterizedTest(name = "conflicting current contact type: {0}")
    @ValueSource(strings = {"PHONE", "MOBILE"})
    void anotherPatientsCurrentNormalizedPhoneConflictsAndRollsBackAllChanges(String type) throws Exception
    {
        UUID id = patient();
        contact(first, id, "MOBILE", phone(), true, false, CREATED);
        UUID holder = patient(first, first.cabinetId(), UUID.randomUUID(), "UNKNOWN");
        String occupied = phone();
        contact(first, holder, type, occupied, true, true, CREATED);
        Map<String, Object> before = snapshot(first, id);
        Map<String, Object> holderBefore = snapshot(first, holder);
        Map<String, Integer> counts = rowCounts(first);
        Map<String, Object> request = new LinkedHashMap<>(Map.of("firstName", "PhoneRollbackCanary",
            "familyName", "PhoneRollbackFamily", "dateOfBirth", "1996-02-29",
            "initialNote", "Fictional phone conflict note.", "phone", "00 " + occupied.substring(1)));
        Browser browser = login(owner);

        HttpResponse<String> response = browser.patch(path(id), request);

        assertError(response, 409, "PHONE_CONFLICT");
        assertFalse(response.body().contains(occupied));
        assertFalse(response.body().contains("PhoneRollbackCanary"));
        assertEquals(before, snapshot(first, id));
        assertEquals(holderBefore, snapshot(first, holder));
        assertEquals(counts, rowCounts(first));
        request.put("phone", phone());
        detail(browser.patch(path(id), request));
        assertEquals(request.get("phone"), currentPhone(id).get("value_normalized"));
        assertAudit(first, id, owner, "OWNER");
    }

    @Test
    void normalizedPhoneUniquenessIsTenantLocalAndDoesNotReserveEndedContacts() throws Exception
    {
        UUID holder = patient(first, first.cabinetId(), UUID.randomUUID(), "UNKNOWN");
        String ended = phone();
        contact(first, holder, "PHONE", ended, false, true, CREATED);
        UUID id = patient();
        detail(login(owner).patch(path(id), Map.of("phone", ended)));
        UUID otherId = patient(second, second.cabinetId(), UUID.randomUUID(), "UNKNOWN");
        detail(login(otherOwner).patch(path(otherId), Map.of("phone", ended)));
        assertEquals(ended, currentPhone(id).get("value_normalized"));
        assertEquals(ended, contacts(second, otherId).getFirst().get("value_normalized"));
        assertEquals(Boolean.FALSE, contacts(first, holder).getFirst().get("is_current"));
        assertAudit(first, id, owner, "OWNER");
        assertAudit(second, otherId, otherOwner, "OWNER");
    }

    @AfterAll
    void removeOnlyOwnedFixtures()
    {
        dataSource.close();
        resources.close();
        PLATFORM.close();
    }

    private UUID patient()
    {
        UUID id = patient(first, first.cabinetId(), UUID.randomUUID(), "COMPLETE_DATE");
        contact(first, id, "PHONE", phone(), true, true, CREATED);
        return id;
    }

    private UUID patient(TestFixtures.CabinetFixture tenant, UUID cabinetId, UUID id, String precision)
    {
        Integer year = "UNKNOWN".equals(precision) ? null : 1988;
        Integer month = List.of("COMPLETE_DATE", "YEAR_MONTH").contains(precision) ? 2 : null;
        Integer day = "COMPLETE_DATE".equals(precision) ? 29 : null;
        jdbc.update("INSERT INTO " + table(tenant, "patients")
            + " (id, cabinet_id, patient_number, legal_given_name, legal_family_name, birth_precision,"
            + " birth_year, birth_month, birth_day, initial_note, created_at, updated_at, created_by, updated_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, cabinetId, "UPDATE-" + id,
            "FictionalGiven", "FictionalFamily", precision, year, month, day, LEGACY_NOTE,
            Timestamp.from(CREATED), Timestamp.from(CREATED), tenant.ownerId(), tenant.ownerId());
        return id;
    }

    private String phone()
    {
        return "+216" + nextPhone++;
    }

    private void contact(TestFixtures.CabinetFixture tenant, UUID patientId, String type, String value,
                         boolean current, Boolean primary, Instant createdAt)
    {
        contact(tenant, patientId, UUID.randomUUID(), type, value, current, primary, createdAt);
    }

    private void contact(TestFixtures.CabinetFixture tenant, UUID patientId, UUID id, String type, String value,
                         boolean current, Boolean primary, Instant createdAt)
    {
        String normalized = List.of("PHONE", "MOBILE").contains(type) ? value : null;
        jdbc.update("INSERT INTO " + table(tenant, "patient_contacts")
            + " (id, patient_id, contact_type, value, value_normalized, is_current, is_primary_for_type, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, patientId, type, value, normalized, current, primary,
            Timestamp.from(createdAt));
    }

    private void excludedContacts(UUID id)
    {
        contact(first, id, "EMAIL", "fictional-contact@example.invalid", true, true, CREATED);
        contact(first, id, "FAX", "+21629999888", true, true, CREATED);
        contact(first, id, "EMERGENCY_PHONE", "+21629999777", true, true, CREATED);
    }

    private Map<String, Object> stored(TestFixtures.CabinetFixture tenant, UUID id)
    {
        return jdbc.queryForMap("SELECT * FROM " + table(tenant, "patients") + " WHERE id = ?", id);
    }

    private List<Map<String, Object>> contacts(TestFixtures.CabinetFixture tenant, UUID id)
    {
        return jdbc.queryForList("SELECT * FROM " + table(tenant, "patient_contacts")
            + " WHERE patient_id = ? ORDER BY id", id);
    }

    private List<Map<String, Object>> auditRows(TestFixtures.CabinetFixture tenant, UUID id)
    {
        return jdbc.queryForList("SELECT * FROM " + table(tenant, "audit_logs")
            + " WHERE entity_id = ? ORDER BY id", id);
    }

    private Map<String, Object> snapshot(TestFixtures.CabinetFixture tenant, UUID id)
    {
        return Map.of("patient", stored(tenant, id), "contacts", contacts(tenant, id), "audit", auditRows(tenant, id));
    }

    private int currentPhoneCount(UUID id)
    {
        return jdbc.queryForObject("SELECT count(*) FROM " + table(first, "patient_contacts")
            + " WHERE patient_id = ? AND contact_type IN ('PHONE', 'MOBILE') AND is_current = true", Integer.class, id);
    }

    private Map<String, Object> currentPhone(UUID id)
    {
        assertEquals(1, currentPhoneCount(id), "Changed phone must leave exactly one current PHONE/MOBILE");
        return jdbc.queryForMap("SELECT * FROM " + table(first, "patient_contacts")
            + " WHERE patient_id = ? AND contact_type IN ('PHONE', 'MOBILE') AND is_current = true", id);
    }

    private static void assertBirth(Map<String, Object> row, String precision, Integer year, Integer month, Integer day)
    {
        assertEquals(precision, row.get("birth_precision"));
        assertEquals(year, row.get("birth_year"));
        assertEquals(month, row.get("birth_month"));
        assertEquals(day, row.get("birth_day"));
    }

    private static void assertImmutable(Map<String, Object> before, Map<String, Object> after, JsonNode body)
    {
        for (String field : List.of("id", "cabinet_id", "patient_number", "created_at", "created_by",
            "legacy_source_system_id", "legacy_record_id", "import_batch_id", "archived_at"))
        {
            assertEquals(before.get(field), after.get(field), "PATCH must preserve immutable column: " + field);
        }
        assertEquals(after.get("id").toString(), body.get("id").asString());
        assertEquals(after.get("patient_number"), body.get("patientNumber").asString());
        assertEquals(((Timestamp) after.get("created_at")).toInstant(),
            Instant.parse(body.get("createdAt").asString()));
    }

    private static void assertRetired(List<Map<String, Object>> before, List<Map<String, Object>> after)
    {
        for (Map<String, Object> original : before)
        {
            Map<String, Object> expected = new HashMap<>(original);
            if (List.of("PHONE", "MOBILE").contains(original.get("contact_type")))
            {
                expected.put("is_current", Boolean.FALSE);
            }
            Map<String, Object> retained = after.stream().filter(row -> row.get("id").equals(original.get("id")))
                .findFirst().orElseThrow();
            assertEquals(expected, retained, "Only PHONE/MOBILE lifecycle may change; never history or other types");
        }
    }

    private void assertAudit(TestFixtures.CabinetFixture tenant, UUID id, UUID actor, String role)
    {
        List<Map<String, Object>> events = auditRows(tenant, id);
        assertEquals(1, events.size(), "One effective PATCH must record exactly one mandatory event");
        Map<String, Object> event = events.getFirst();
        assertEquals(tenant.cabinetId(), event.get("cabinet_id"));
        assertEquals("patient", event.get("entity_type"));
        assertEquals(id, event.get("entity_id"));
        assertEquals("UPDATE", event.get("operation"));
        assertEquals(actor, event.get("user_id"));
        assertEquals(role, event.get("user_role"));
        assertNotNull(event.get("timestamp"));
        assertNull(event.get("previous_values"), "Medical values must never be copied into audit payloads");
        assertNull(event.get("new_values"));
        assertNull(event.get("user_agent"));
        Map<String, Object> row = stored(tenant, id);
        String serialized = event.toString();
        for (String field : List.of("legal_given_name", "legal_family_name", "initial_note"))
        {
            Object value = row.get(field);
            if (value != null && !value.toString().isBlank())
            {
                assertFalse(serialized.contains(value.toString()), "Audit payload leaked medical values");
            }
        }
        for (Map<String, Object> contact : contacts(tenant, id))
        {
            assertFalse(serialized.contains(contact.get("value").toString()));
            if (contact.get("value_normalized") != null)
            {
                assertFalse(serialized.contains(contact.get("value_normalized").toString()));
            }
        }
        if ("COMPLETE_DATE".equals(row.get("birth_precision")))
        {
            String birth = LocalDate.of((Integer) row.get("birth_year"), (Integer) row.get("birth_month"),
                (Integer) row.get("birth_day")).toString();
            assertFalse(serialized.contains(birth));
        }
    }

    private Map<String, Integer> rowCounts(TestFixtures.CabinetFixture tenant)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables"
            + " WHERE table_schema = ? AND table_type = 'BASE TABLE'"
            + " AND table_name <> 'flyway_schema_history_tenant' ORDER BY table_name", String.class,
            tenant.schemaName());
        for (String name : tables)
        {
            counts.put(name, jdbc.queryForObject("SELECT count(*) FROM " + table(tenant, name), Integer.class));
        }
        return counts;
    }

    private void assertUpdateCounts(Map<String, Integer> before, TestFixtures.CabinetFixture tenant,
                                    int insertedContacts, int insertedAudit)
    {
        Map<String, Integer> expected = new HashMap<>(before);
        expected.compute("patient_contacts", (key, count) -> count + insertedContacts);
        expected.compute("audit_logs", (key, count) -> count + insertedAudit);
        assertEquals(expected, rowCounts(tenant),
            "PATCH must not create patients, identity side tables or clinical rows");
    }

    private static JsonNode detail(HttpResponse<String> response)
    {
        assertEquals(200, response.statusCode(), "Authorized valid PATCH/detail must succeed");
        assertNoStore(response);
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.isObject());
        assertEquals(DETAIL_FIELDS.size(), body.size());
        for (String field : DETAIL_FIELDS)
        {
            assertTrue(body.has(field), "Missing exact public detail field: " + field);
        }
        return body;
    }

    private JsonNode listing(Browser browser, String search) throws Exception
    {
        HttpResponse<String> response = browser.get("/api/patients?search=" + encode(search));
        assertEquals(200, response.statusCode());
        assertNoStore(response);
        return JSON.readTree(response.body());
    }

    private void assertListedPhone(Browser browser, UUID id, String expected) throws Exception
    {
        JsonNode rows = listing(browser, stored(first, id).get("patient_number").toString()).get("content");
        assertEquals(1, rows.size());
        assertEquals(id.toString(), rows.get(0).get("id").asString());
        if (expected == null)
        {
            assertTrue(rows.get(0).get("phone").isNull());
        } else
        {
            assertEquals(expected, rows.get(0).get("phone").asString());
        }
    }

    private JsonNode assertError(HttpResponse<String> response, int status, String code)
    {
        assertEquals(status, response.statusCode(), "Expected safe patient PATCH failure");
        assertNoStore(response);
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.isObject());
        assertEquals(code, body.get("code").asString());
        assertTrue(body.get("message").isString());
        assertFalse(body.get("message").asString().isBlank());
        assertEquals(body.has("fieldErrors") ? 3 : 2, body.size());
        for (String privateDetail : List.of("FictionalGiven", "FictionalFamily", "Fictional legacy update note",
            PLATFORM.schema(), first.schemaName(), second.schemaName(), "rejectedValue", "stackTrace",
            "SQLException", "org.postgresql", "java.lang", "com.docukore", "password_hash"))
        {
            assertFalse(response.body().contains(privateDetail), "Error leaked patient or implementation details");
        }
        return body;
    }

    private static void assertNoStore(HttpResponse<String> response)
    {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
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

    private static Map<String, Object> patchField(String field, Object value)
    {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put(field, value);
        return request;
    }

    private static String path(UUID id)
    {
        return "/api/patients/" + id;
    }

    private static String spoofedPath(UUID id, TestFixtures.CabinetFixture tenant)
    {
        return path(id) + "?cabinetId=" + tenant.cabinetId() + "&tenantId=" + tenant.cabinetId()
            + "&schema=" + encode(tenant.schemaName()) + "&tenantSchema=" + encode(tenant.schemaName())
            + "&userId=" + tenant.ownerId() + "&updatedBy=" + tenant.ownerId();
    }

    private static Map<String, String> spoofedHeaders(TestFixtures.CabinetFixture tenant, UUID actor)
    {
        return Map.of("X-Cabinet-Id", tenant.cabinetId().toString(), "X-Tenant-Schema", tenant.schemaName(),
            "X-User-Id", actor.toString());
    }

    private static String table(TestFixtures.CabinetFixture tenant, String name)
    {
        return OwnedTestDatabase.identifier(tenant.schemaName()) + "." + OwnedTestDatabase.identifier(name);
    }

    private static String platformTable(String name)
    {
        return OwnedTestDatabase.identifier(PLATFORM.schema()) + "." + OwnedTestDatabase.identifier(name);
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Actual HTTP client retaining session and CSRF cookies, never a mocked controller. */
    private static final class Browser
    {
        /** Random port of this class's application context. */
        private final int port;
        /** Cookie store representing the browser's authenticated session. */
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        /** Real network transport for every request. */
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
            String body = "username=" + encode(username) + "&password=" + encode(PASSWORD);
            return client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> patch(String path, Map<String, Object> request) throws Exception
        {
            return patch(path, request, true, Map.of());
        }

        HttpResponse<String> patch(String path, Map<String, Object> request, boolean withCsrf,
                                   Map<String, String> headers) throws Exception
        {
            return patchJson(path, JSON.writeValueAsString(request), withCsrf, headers);
        }

        HttpResponse<String> patchJson(String path, String document, boolean withCsrf, Map<String, String> headers)
            throws Exception
        {
            var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(document));
            if (withCsrf)
            {
                builder.header("X-XSRF-TOKEN", csrf());
            }
            headers.forEach(builder::header);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
