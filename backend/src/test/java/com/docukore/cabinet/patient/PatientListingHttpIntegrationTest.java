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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP patient listing contracts using owned schemas on the existing external PostgreSQL database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PatientListingHttpIntegrationTest extends SharedSpringBootTest
{
    /** Latest-migrated platform owned exclusively by this class. */
    private static final TestFixtures.OwnedPlatform PLATFORM = TestFixtures.createPlatform("patient_listing_platform");
    /** JSON codec for public response assertions. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Fictional account credential, unrelated to installation credentials. */
    private static final String PASSWORD = "Fictional-listing-test-passphrase!";
    /** Deliberately sensitive fixture content that must never appear in a listing or error. */
    private static final String PRIVATE_NOTE = "Fictional confidential listing note must remain hidden.";
    /** Fixed timestamps make ordering assertions independent of wall-clock precision. */
    private static final Instant CREATED = Instant.parse("2024-06-15T12:00:00Z");
    /** The only public patient row fields. */
    private static final List<String> ROW_FIELDS = List.of("id", "patientNumber", "firstName", "familyName",
        "phone", "dateOfBirth", "createdAt");
    /** Runtime pool retired before exact owned-schema cleanup. */
    private final HikariDataSource dataSource;
    /** Exact tenant ownership receipts retained until class cleanup. */
    private final OwnedTestDatabase.Scope resources;
    /** Fixture writes through the class-owned migrator pool, not runtime credentials. */
    private final JdbcTemplate jdbc;
    /** Encoder used to seed real HTTP login accounts. */
    private final PasswordEncoder passwords;
    /** Actual application server port. */
    private final int port;
    /** Owner of the stable pagination dataset. */
    private UUID owner;
    /** Owner of an independent tenant. */
    private UUID otherOwner;
    /** Owner of the stable search and legacy dataset. */
    private UUID searchOwner;
    /** Owner of a permanently empty tenant. */
    private UUID emptyOwner;
    /** Secretary whose grants are explicitly managed by one test. */
    private UUID secretary;
    /** Account with no initial membership. */
    private UUID outsider;
    /** Stable pagination tenant, never reset between tests. */
    private TestFixtures.CabinetFixture first;
    /** Independently provisioned isolation tenant. */
    private TestFixtures.CabinetFixture second;
    /** Search fixtures isolated from pagination counts. */
    private TestFixtures.CabinetFixture searchable;
    /** Empty fixtures isolated from all patient inserts. */
    private TestFixtures.CabinetFixture empty;
    /** Secretary membership used for permission revalidation. */
    private UUID secretaryMembership;
    /** Expected pagination order specified independently of production sorting. */
    private final List<UUID> firstIds = new ArrayList<>();
    /** Expected independent tenant order. */
    private final List<UUID> secondIds = new ArrayList<>();
    /** Primary search target with several current and historical contacts. */
    private UUID searchTarget;
    /** Patient whose literal percent sign must not become a SQL wildcard. */
    private UUID percentPatient;
    /** Patient whose literal underscore must not become a SQL wildcard. */
    private UUID underscorePatient;
    /** Patient whose literal exclamation mark must be escaped. */
    private UUID exclamationPatient;
    /** Primary mobile must win over a nonprimary phone. */
    private UUID primaryMobilePatient;
    /** Phone must win over mobile when primary preference ties. */
    private UUID phoneBeforeMobilePatient;
    /** Newest contact must win when primary preference and type tie. */
    private UUID newestPhonePatient;
    /** Highest contact UUID must win when timestamps also tie. */
    private UUID contactTiePatient;
    /** Historical patient without a current phone or birth date. */
    private UUID unknownBirthPatient;
    /** Historical patient with only a birth year. */
    private UUID yearOnlyPatient;
    /** Historical patient with birth year and month. */
    private UUID yearMonthPatient;

    PatientListingHttpIntegrationTest(@LocalServerPort int port, HikariDataSource dataSource,
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
        searchOwner = account(hash);
        emptyOwner = account(hash);
        secretary = account(hash);
        outsider = account(hash);
        first = cabinet(owner);
        second = cabinet(otherOwner);
        searchable = cabinet(searchOwner);
        empty = cabinet(emptyOwner);
        secretaryMembership = membership(first.cabinetId(), secretary, "MEMBER");
        paginationFixtures();
        searchFixtures();
        UUID older = patient(second, 1001, "LEGACY-REF-ALPHA-701", "Marielle", "Vellmont",
            "COMPLETE_DATE", 1990, 2, 28, CREATED.minusSeconds(1));
        UUID newer = patient(second, 1002, "OTHER-ONLY-702", "Independent", "Boundary",
            "UNKNOWN", null, null, null, CREATED);
        contact(second, older, "PHONE", "20 444-001", "+21620444001", true, true, CREATED);
        secondIds.add(newer);
        secondIds.add(older);
    }

    @Test
    void ownerListsDefaultFirstPageWithOnlySevenPublicRowFields() throws Exception
    {
        JsonNode body = page(login(owner).get("/api/patients"), 0, 20, 25, 2);
        assertEquals(firstIds.subList(0, 20), ids(body));
        JsonNode newest = body.get("content").get(0);
        assertEquals("PAT-LIST-002", newest.get("patientNumber").asString());
        assertEquals("Fictional1", newest.get("firstName").asString());
        assertEquals("Pagination", newest.get("familyName").asString());
        assertEquals("1990-02-28", newest.get("dateOfBirth").asString());
        assertEquals(CREATED, Instant.parse(newest.get("createdAt").asString()));
        assertTrue(newest.get("phone").isNull());
        assertEquals("+21620123456", body.get("content").get(1).get("phone").asString());
    }

    @Test
    void emptyCabinetReturnsEmptyDefaultPage() throws Exception
    {
        assertTrue(ids(page(login(emptyOwner).get("/api/patients"), 0, 20, 0, 0)).isEmpty());
    }

    @Test
    void outOfRangePagePreservesRequestedPaginationAndTotals() throws Exception
    {
        assertTrue(ids(page(login(owner).get("/api/patients?page=9&size=7"), 9, 7, 25, 4)).isEmpty());
    }

    @Test
    void paginationHasNoGapsAndEqualTimestampRowsUseDescendingId() throws Exception
    {
        Browser browser = login(owner);
        List<UUID> collected = new ArrayList<>();
        for (int index = 0; index < 4; index++)
        {
            JsonNode body = page(browser.get("/api/patients?page=" + index + "&size=7"), index, 7, 25, 4);
            assertEquals(firstIds.subList(index * 7, Math.min((index + 1) * 7, 25)), ids(body));
            collected.addAll(ids(body));
        }
        assertEquals(firstIds, collected);
        JsonNode firstPage = page(browser.get("/api/patients?size=1"), 0, 1, 25, 25);
        JsonNode secondPage = page(browser.get("/api/patients?page=1&size=1"), 1, 1, 25, 25);
        assertEquals(List.of(new UUID(0, 2)), ids(firstPage));
        assertEquals(List.of(new UUID(0, 1)), ids(secondPage));
        assertEquals(firstPage.get("content").get(0).get("createdAt").asString(),
            secondPage.get("content").get(0).get("createdAt").asString());
        assertEquals(ids(firstPage), ids(page(browser.get("/api/patients?size=1"), 0, 1, 25, 25)));
    }

    @Test
    void maximumPageSizeOneHundredIsAccepted() throws Exception
    {
        assertEquals(firstIds, ids(page(login(owner).get("/api/patients?size=100"), 0, 100, 25, 1)));
    }

    @ParameterizedTest(name = "valid large offset page={0}, size={1}")
    @MethodSource("validLargePages")
    void acceptsLargestNonoverflowingOffsets(int requestedPage, int requestedSize) throws Exception
    {
        String path = "/api/patients?page=" + requestedPage + "&size=" + requestedSize;
        int totalPages = requestedSize == 1 ? 25 : 1;
        assertTrue(ids(page(login(owner).get(path), requestedPage, requestedSize, 25, totalPages)).isEmpty());
    }

    static Stream<Arguments> validLargePages()
    {
        return Stream.of(Arguments.of(Integer.MAX_VALUE, 1), Arguments.of(21474836, 100));
    }

    @ParameterizedTest(name = "invalid listing input: {0}")
    @MethodSource("invalidQueries")
    void invalidPaginationAndSearchReturnSafeNoStoreErrors(String query, String rejectedValue) throws Exception
    {
        HttpResponse<String> response = login(owner).get("/api/patients?" + query);
        assertError(response, 400, "INVALID_INPUT");
        assertEquals("Check the entered information.", JSON.readTree(response.body()).get("message").asString());
        if (rejectedValue.length() > 4)
        {
            assertFalse(response.body().contains(rejectedValue), "Rejected input must not be echoed in errors");
        }
    }

    static Stream<Arguments> invalidQueries()
    {
        String excessiveSearch = "private-search-needle-" + "z".repeat(491);
        return Stream.of(
            Arguments.of("page=-1", "-1"),
            Arguments.of("size=0", "0"),
            Arguments.of("size=-1", "-1"),
            Arguments.of("size=101", "101"),
            Arguments.of("page=invalid-page-needle", "invalid-page-needle"),
            Arguments.of("size=invalid-size-needle", "invalid-size-needle"),
            Arguments.of("page=1.5", "1.5"),
            Arguments.of("page=2147483648", "2147483648"),
            Arguments.of("size=2147483648", "2147483648"),
            Arguments.of("page=21474837&size=100", "21474837"),
            Arguments.of("page=2147483647&size=20", "2147483647"),
            Arguments.of("search=" + encode(excessiveSearch), excessiveSearch));
    }

    @ParameterizedTest(name = "literal case-insensitive search: {0}")
    @MethodSource("targetSearches")
    void searchesFirstFamilyFullNameNumberAndEveryCurrentPhone(String search) throws Exception
    {
        JsonNode body = page(login(searchOwner).get("/api/patients?search=" + encode(search)), 0, 20, 1, 1);
        assertEquals(List.of(searchTarget), ids(body));
        assertEquals("+21620444001", body.get("content").get(0).get("phone").asString());
        assertEquals("1990-02-28", body.get("content").get(0).get("dateOfBirth").asString());
    }

    static Stream<String> targetSearches()
    {
        return Stream.of("aRIe", "llMON", "ELLE VELL", "ref-aLPHa", "20444001", "20 444-001",
            "20444002", "20444003", "+216 (20) 444-001", "00 216 (20) 444-001", "20-444.001",
            "  arIE  ", "  arIE  ");
    }

    @ParameterizedTest(name = "blank search is unfiltered: [{0}]")
    @MethodSource("blankSearches")
    void blankSearchIsNormalListing(String search) throws Exception
    {
        JsonNode body = page(login(owner).get("/api/patients?search=" + encode(search)), 0, 20, 25, 2);
        assertEquals(firstIds.subList(0, 20), ids(body));
    }

    static Stream<String> blankSearches()
    {
        return Stream.of("", "   ", "\t\n", " ");
    }

    @Test
    void strippedSearchAtFiveHundredTwelveCharactersIsAccepted() throws Exception
    {
        String maximumSearch = "z".repeat(512);
        Browser browser = login(searchOwner);
        assertTrue(ids(page(browser.get("/api/patients?search=" + encode(maximumSearch)), 0, 20, 0, 0)).isEmpty());
        assertTrue(ids(page(browser.get("/api/patients?search=" + encode("  " + maximumSearch + "  ")),
            0, 20, 0, 0)).isEmpty());
    }

    @ParameterizedTest(name = "excluded contact or no match: {0}")
    @MethodSource("excludedSearches")
    void noMatchesAndNoncurrentEmailFaxEmergencyContactsAreNotSearchable(String search) throws Exception
    {
        assertTrue(ids(page(login(searchOwner).get("/api/patients?search=" + encode(search)),
            0, 20, 0, 0)).isEmpty());
    }

    static Stream<String> excludedSearches()
    {
        return Stream.of("no-such-fictional-patient", "obsoletephone-needle", "20999888",
            "emailonly-needle", "20999777", "20999666", "' OR 1=1 --");
    }

    @ParameterizedTest(name = "SQL wildcard character is literal: {0}")
    @MethodSource("literalSearches")
    void percentUnderscoreAndEscapeCharacterAreLiteral(String search, UUID expected) throws Exception
    {
        assertEquals(List.of(expected), ids(page(login(searchOwner).get("/api/patients?search=" + encode(search)),
            0, 20, 1, 1)));
    }

    Stream<Arguments> literalSearches()
    {
        return Stream.of(Arguments.of("%", percentPatient), Arguments.of("_", underscorePatient),
            Arguments.of("!", exclamationPatient), Arguments.of("Literal%Token", percentPatient),
            Arguments.of("Literal_Token", underscorePatient), Arguments.of("Literal!Token", exclamationPatient));
    }

    @Test
    void multipleMatchingContactsNeverDuplicatePatientsOrInflateTotals() throws Exception
    {
        Browser browser = login(searchOwner);
        assertEquals(List.of(searchTarget), ids(page(browser.get("/api/patients?search=20444&size=1"),
            0, 1, 1, 1)));
        assertTrue(ids(page(browser.get("/api/patients?search=20444&page=1&size=1"), 1, 1, 1, 1)).isEmpty());
        assertEquals(12, page(browser.get("/api/patients?size=100"), 0, 100, 12, 1).get("content").size());
    }

    @ParameterizedTest(name = "deterministic phone selection: {0}")
    @MethodSource("phoneSelections")
    void phoneUsesCurrentPrimaryThenTypeThenNewestThenDescendingContactId(String search, UUID expected,
                                                                        String phone) throws Exception
    {
        JsonNode body = page(login(searchOwner).get("/api/patients?search=" + encode(search)), 0, 20, 1, 1);
        assertEquals(List.of(expected), ids(body));
        assertEquals(phone, body.get("content").get(0).get("phone").asString());
    }

    Stream<Arguments> phoneSelections()
    {
        return Stream.of(Arguments.of("Marielle", searchTarget, "+21620444001"),
            Arguments.of("PrimaryMobile", primaryMobilePatient, "+21621111002"),
            Arguments.of("PhoneBeforeMobile", phoneBeforeMobilePatient, "+21622222001"),
            Arguments.of("NewestPhone", newestPhonePatient, "+21623333002"),
            Arguments.of("ContactIdTie", contactTiePatient, "+21624444002"));
    }

    @ParameterizedTest(name = "legacy birth and phone remain absent: {0}")
    @MethodSource("incompleteBirths")
    void missingPhoneAndUnknownOrPartialBirthNeverInventValues(String search, UUID expected) throws Exception
    {
        JsonNode body = page(login(searchOwner).get("/api/patients?search=" + encode(search)), 0, 20, 1, 1);
        assertEquals(List.of(expected), ids(body));
        JsonNode row = body.get("content").get(0);
        assertTrue(row.get("dateOfBirth").isNull());
        assertTrue(row.get("phone").isNull());
    }

    Stream<Arguments> incompleteBirths()
    {
        return Stream.of(Arguments.of("UnknownBirth", unknownBirthPatient),
            Arguments.of("YearOnlyBirth", yearOnlyPatient), Arguments.of("YearMonthBirth", yearMonthPatient));
    }

    @Test
    void secretaryNeedsExplicitReadAndRevocationTakesEffectWithoutRelogin() throws Exception
    {
        Browser browser = login(secretary);
        assertError(browser.get("/api/patients"), 403, "ACCESS_DENIED");
        UUID permission = grant(secretaryMembership);
        try
        {
            assertEquals(firstIds.subList(0, 20), ids(page(browser.get("/api/patients"), 0, 20, 25, 2)));
            jdbc.update("UPDATE " + platformTable("membership_permissions") + " SET revoked_at = now() WHERE id = ?",
                permission);
            assertError(browser.get("/api/patients"), 403, "ACCESS_DENIED");
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("membership_permissions") + " WHERE id = ?", permission);
        }
    }

    @Test
    void noMembershipAndMultipleMembershipsCannotSelectSpoofedCabinet() throws Exception
    {
        Browser browser = login(outsider);
        assertError(browser.get("/api/patients"), 403, "ACCESS_DENIED");
        assertError(browser.get(spoofedPath(first), spoofedHeaders(first, owner)), 403, "ACCESS_DENIED");
        UUID firstMembership = membership(first.cabinetId(), outsider, "OWNER");
        UUID secondMembership = membership(second.cabinetId(), outsider, "OWNER");
        try
        {
            assertError(browser.get("/api/patients"), 409, "CABINET_SELECTION_REQUIRED");
            assertError(browser.get(spoofedPath(first), spoofedHeaders(first, owner)),
                409, "CABINET_SELECTION_REQUIRED");
        } finally
        {
            jdbc.update("DELETE FROM " + platformTable("cabinet_memberships") + " WHERE id IN (?, ?)",
                firstMembership, secondMembership);
        }
    }

    @Test
    void anonymousListingIsUnauthenticatedEvenWithSpoofedTenant() throws Exception
    {
        Browser browser = new Browser(port);
        assertError(browser.get(spoofedPath(first), spoofedHeaders(first, owner)), 401, "AUTHENTICATION_FAILED");
    }

    @Test
    void tenantHeadersAndQueryParametersCannotChooseAnotherCabinet() throws Exception
    {
        Browser browser = login(owner);
        assertEquals(firstIds.subList(0, 20), ids(page(browser.get(spoofedPath(second),
            spoofedHeaders(second, otherOwner)), 0, 20, 25, 2)));
        assertTrue(ids(page(browser.get(spoofedPath(second) + "&search=Independent",
            spoofedHeaders(second, otherOwner)), 0, 20, 0, 0)).isEmpty());
        assertEquals(secondIds, ids(page(login(otherOwner).get(spoofedPath(first),
            spoofedHeaders(first, owner)), 0, 20, 2, 1)));
    }

    @Test
    void sameNamesNumbersAndPhoneInIndependentTenantsRemainIsolated() throws Exception
    {
        Browser firstBrowser = login(searchOwner);
        Browser secondBrowser = login(otherOwner);
        for (String search : List.of("Marielle", "LEGACY-REF-ALPHA-701", "+21620444001"))
        {
            String path = "/api/patients?search=" + encode(search);
            assertEquals(List.of(searchTarget), ids(page(firstBrowser.get(path), 0, 20, 1, 1)));
            assertEquals(List.of(new UUID(0, 1001)), ids(page(secondBrowser.get(path), 0, 20, 1, 1)));
        }
        assertEquals(List.of(searchTarget), ids(page(firstBrowser.get("/api/patients?search=Marielle"),
            0, 20, 1, 1)));
    }

    @Test
    void inactiveRevokedMembershipAndUnavailableCabinetDenyListingWithoutRelogin() throws Exception
    {
        Browser browser = login(owner);
        String memberships = platformTable("cabinet_memberships");
        String cabinets = platformTable("cabinets");
        try
        {
            jdbc.update("UPDATE " + memberships + " SET status = 'INACTIVE' WHERE cabinet_id = ? AND user_id = ?",
                first.cabinetId(), owner);
            assertError(browser.get("/api/patients"), 403, "ACCESS_DENIED");
            jdbc.update("UPDATE " + memberships + " SET status = 'ACTIVE', revoked_at = now()"
                + " WHERE cabinet_id = ? AND user_id = ?", first.cabinetId(), owner);
            assertError(browser.get("/api/patients"), 403, "ACCESS_DENIED");
            jdbc.update("UPDATE " + memberships + " SET revoked_at = NULL WHERE cabinet_id = ? AND user_id = ?",
                first.cabinetId(), owner);
            jdbc.update("UPDATE " + cabinets + " SET administrative_status = 'SUSPENDED' WHERE id = ?",
                first.cabinetId());
            assertError(browser.get("/api/patients"), 403, "ACCESS_DENIED");
            jdbc.update("UPDATE " + cabinets + " SET administrative_status = 'AVAILABLE',"
                + " provisioning_status = 'PROVISIONING_FAILED' WHERE id = ?", first.cabinetId());
            assertError(browser.get("/api/patients"), 403, "ACCESS_DENIED");
        } finally
        {
            jdbc.update("UPDATE " + memberships + " SET status = 'ACTIVE', revoked_at = NULL"
                + " WHERE cabinet_id = ? AND user_id = ?", first.cabinetId(), owner);
            jdbc.update("UPDATE " + cabinets + " SET administrative_status = 'AVAILABLE',"
                + " provisioning_status = 'ACTIVE' WHERE id = ?", first.cabinetId());
        }
        assertEquals(firstIds.subList(0, 20), ids(page(browser.get("/api/patients"), 0, 20, 25, 2)));
    }

    @AfterAll
    void removeOnlyOwnedFixtures()
    {
        dataSource.close();
        resources.close();
        PLATFORM.close();
    }

    private void paginationFixtures()
    {
        List<UUID> insertionOrder = new ArrayList<>();
        for (int index = 0; index < 25; index++)
        {
            Instant timestamp = index < 2 ? CREATED : CREATED.minusSeconds(index);
            insertionOrder.add(patient(first, index + 1, "PAT-LIST-" + String.format("%03d", index + 1),
                "Fictional" + index, "Pagination", "COMPLETE_DATE", 1990, 2, 28, timestamp));
        }
        firstIds.add(insertionOrder.get(1));
        firstIds.add(insertionOrder.get(0));
        firstIds.addAll(insertionOrder.subList(2, insertionOrder.size()));
        contact(first, insertionOrder.get(0), "PHONE", "20 123-456", "+21620123456", true, true, CREATED);
    }

    private void searchFixtures()
    {
        searchTarget = searchPatient(101, "LEGACY-REF-ALPHA-701", "Marielle", "Vellmont");
        contact(searchable, searchTarget, "PHONE", "20 444-001", "+21620444001", true, true, CREATED);
        contact(searchable, searchTarget, "MOBILE", "20 444-002", "+21620444002", true, true,
            CREATED.plusSeconds(2));
        contact(searchable, searchTarget, "PHONE", "20 444-003", "+21620444003", true, false,
            CREATED.plusSeconds(3));
        contact(searchable, searchTarget, "PHONE", "obsoletephone-needle", "+21620999888", false, true,
            CREATED.plusSeconds(4));
        contact(searchable, searchTarget, "EMAIL", "emailonly-needle@example.invalid", null, true, true,
            CREATED.plusSeconds(5));
        contact(searchable, searchTarget, "EMERGENCY_PHONE", "+21620999777", null, true, true,
            CREATED.plusSeconds(6));
        contact(searchable, searchTarget, "FAX", "+21620999666", null, true, true, CREATED.plusSeconds(7));
        percentPatient = searchPatient(102, "LEGACY-PERCENT-102", "Literal%Token", "PercentFamily");
        underscorePatient = searchPatient(103, "LEGACY-UNDERSCORE-103", "Literal_Token", "UnderscoreFamily");
        exclamationPatient = searchPatient(104, "LEGACY-ESCAPE-104", "Literal!Token", "EscapeFamily");
        searchPatient(105, "LEGACY-DECOY-105", "LiteralXToken", "DecoyFamily");
        primaryMobilePatient = searchPatient(106, "LEGACY-PRIMARY-106", "PrimaryMobile", "Preference");
        contact(searchable, primaryMobilePatient, "PHONE", "21 111-001", "+21621111001", true, false,
            CREATED.plusSeconds(1));
        contact(searchable, primaryMobilePatient, "MOBILE", "21 111-002", "+21621111002", true, true, CREATED);
        phoneBeforeMobilePatient = searchPatient(107, "LEGACY-TYPE-107", "PhoneBeforeMobile", "Preference");
        contact(searchable, phoneBeforeMobilePatient, "PHONE", "22 222-001", "+21622222001", true, false, CREATED);
        contact(searchable, phoneBeforeMobilePatient, "MOBILE", "22 222-002", "+21622222002", true, false,
            CREATED.plusSeconds(1));
        newestPhonePatient = searchPatient(108, "LEGACY-NEWEST-108", "NewestPhone", "Preference");
        contact(searchable, newestPhonePatient, "PHONE", "23 333-001", "+21623333001", true, true, CREATED);
        contact(searchable, newestPhonePatient, "PHONE", "23 333-002", "+21623333002", true, true,
            CREATED.plusSeconds(1));
        contactTiePatient = searchPatient(109, "LEGACY-TIE-109", "ContactIdTie", "Preference");
        contact(searchable, contactTiePatient, new UUID(0, 9001), "PHONE", "24 444-001", "+21624444001",
            true, true, CREATED);
        contact(searchable, contactTiePatient, new UUID(0, 9002), "PHONE", "24 444-002", "+21624444002",
            true, true, CREATED);
        unknownBirthPatient = patient(searchable, 110, "LEGACY-UNKNOWN-110", "UnknownBirth", "Historical",
            "UNKNOWN", null, null, null, CREATED);
        contact(searchable, unknownBirthPatient, "PHONE", "old-raw-phone", null, false, true, CREATED);
        contact(searchable, unknownBirthPatient, "EMAIL", "historical@example.invalid", null, true, true, CREATED);
        yearOnlyPatient = patient(searchable, 111, "LEGACY-YEAR-111", "YearOnlyBirth", "Historical",
            "YEAR_ONLY", 1985, null, null, CREATED);
        yearMonthPatient = patient(searchable, 112, "LEGACY-MONTH-112", "YearMonthBirth", "Historical",
            "YEAR_MONTH", 1986, 7, null, CREATED);
    }

    private UUID searchPatient(int id, String number, String firstName, String familyName)
    {
        return patient(searchable, id, number, firstName, familyName, "COMPLETE_DATE", 1990, 2, 28, CREATED);
    }

    private UUID patient(TestFixtures.CabinetFixture cabinet, int suffix, String number, String firstName,
                         String familyName, String precision, Integer year, Integer month, Integer day,
                         Instant createdAt)
    {
        UUID id = new UUID(0, suffix);
        jdbc.update("INSERT INTO " + table(cabinet, "patients")
            + " (id, cabinet_id, patient_number, legal_given_name, legal_family_name, birth_precision,"
            + " birth_year, birth_month, birth_day, initial_note, created_at, updated_at, created_by, updated_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, cabinet.cabinetId(), number, firstName,
            familyName, precision, year, month, day, PRIVATE_NOTE, Timestamp.from(createdAt),
            Timestamp.from(createdAt), cabinet.ownerId(), cabinet.ownerId());
        return id;
    }

    private void contact(TestFixtures.CabinetFixture cabinet, UUID patientId, String type, String raw,
                         String normalized, boolean current, boolean primary, Instant createdAt)
    {
        contact(cabinet, patientId, UUID.randomUUID(), type, raw, normalized, current, primary, createdAt);
    }

    private void contact(TestFixtures.CabinetFixture cabinet, UUID patientId, UUID id, String type, String raw,
                         String normalized, boolean current, boolean primary, Instant createdAt)
    {
        jdbc.update("INSERT INTO " + table(cabinet, "patient_contacts")
            + " (id, patient_id, contact_type, value, value_normalized, is_current, is_primary_for_type, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, patientId, type, raw, normalized, current, primary,
            Timestamp.from(createdAt));
    }

    private TestFixtures.CabinetFixture cabinet(UUID cabinetOwner)
    {
        TestFixtures.CabinetFixture result = TestFixtures.createCabinet(jdbc, PLATFORM.schema(),
            cabinetOwner, resources);
        membership(result.cabinetId(), cabinetOwner, "OWNER");
        return result;
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

    private UUID grant(UUID membershipId)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + platformTable("membership_permissions")
            + " (id, membership_id, permission_code, granted_at) VALUES (?, ?, 'READ', now())", id, membershipId);
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

    private static JsonNode page(HttpResponse<String> response, int requestedPage, int requestedSize,
                                 int totalElements, int totalPages)
    {
        assertEquals(200, response.statusCode(), response.body());
        assertNoStore(response);
        JsonNode body = JSON.readTree(response.body());
        assertEquals(5, body.size());
        assertNotNull(body.get("content"));
        assertTrue(body.get("content").isArray());
        assertEquals(requestedPage, body.get("page").asInt());
        assertEquals(requestedSize, body.get("size").asInt());
        assertEquals(totalElements, body.get("totalElements").asLong());
        assertEquals(totalPages, body.get("totalPages").asInt());
        for (JsonNode row : body.get("content"))
        {
            assertEquals(ROW_FIELDS.size(), row.size(), "Listing must not serialize entities or hidden fields");
            for (String field : ROW_FIELDS)
            {
                assertTrue(row.has(field), "Missing public row field: " + field);
            }
            UUID.fromString(row.get("id").asString());
            Instant.parse(row.get("createdAt").asString());
        }
        assertFalse(response.body().contains(PRIVATE_NOTE));
        return body;
    }

    private static List<UUID> ids(JsonNode page)
    {
        List<UUID> result = new ArrayList<>();
        for (JsonNode row : page.get("content"))
        {
            result.add(UUID.fromString(row.get("id").asString()));
        }
        return result;
    }

    private static void assertError(HttpResponse<String> response, int status, String code)
    {
        assertEquals(status, response.statusCode(), response.body());
        assertNoStore(response);
        JsonNode body = JSON.readTree(response.body());
        assertEquals(code, body.get("code").asString());
        assertTrue(body.get("message").isString());
        assertFalse(body.get("message").asString().isBlank());
        assertEquals(body.has("fieldErrors") ? 3 : 2, body.size(), "Only safe error fields may be serialized");
        for (String privateDetail : List.of(PRIVATE_NOTE, PLATFORM.schema(), "stackTrace", "SQLException",
            "org.postgresql", "java.lang", "com.docukore", "initialNote", "password_hash"))
        {
            assertFalse(response.body().contains(privateDetail), "Error leaked a private implementation detail");
        }
    }

    private static void assertNoStore(HttpResponse<String> response)
    {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    private static String spoofedPath(TestFixtures.CabinetFixture cabinet)
    {
        return "/api/patients?cabinetId=" + cabinet.cabinetId() + "&tenantId=" + cabinet.cabinetId()
            + "&schema=" + encode(cabinet.schemaName()) + "&tenantSchema=" + encode(cabinet.schemaName());
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

    /** Cookie-backed real HTTP browser, matching patient creation integration conventions. */
    private static final class Browser
    {
        /** Random application HTTP port. */
        private final int port;
        /** Actual session and CSRF cookie store. */
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        /** Real HTTP client, never a controller mock. */
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
            String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(java.net.HttpCookie::getValue).findFirst().orElseThrow();
            return client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path)
        {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
