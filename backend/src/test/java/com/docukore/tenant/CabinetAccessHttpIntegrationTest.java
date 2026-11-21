package com.docukore.tenant;

import com.docukore.identity.CurrentAccount;
import com.docukore.support.SharedSpringBootTest;
import com.docukore.support.TestFixtures;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(CabinetAccessHttpIntegrationTest.ProbeController.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CabinetAccessHttpIntegrationTest extends SharedSpringBootTest
{

    /** Fictional password shared by test accounts. */
    private static final String PASSWORD = "Fictional-task6-passphrase!";
    /** Shared probe identifier used to compare isolated tenant data. */
    private static final UUID PROBE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    /** Random HTTP port assigned to the test server. */
    private final int port;
    /** JDBC access for account, membership and tenant fixtures. */
    private final JdbcTemplate jdbc;
    /** Encoder used to create fictional account password hashes. */
    private final PasswordEncoder passwords;
    /** Test-only controller used to observe authorized business callbacks. */
    private final ProbeController probe;
    /** Account identifier for the first cabinet's owner. */
    private UUID owner;
    /** Account identifier for the first cabinet's member. */
    private UUID member;
    /** Account identifier for the second cabinet's owner. */
    private UUID otherOwner;
    /** First cabinet fixture used for access and revocation scenarios. */
    private TestFixtures.CabinetFixture first;
    /** Second cabinet fixture used for cross-tenant access scenarios. */
    private TestFixtures.CabinetFixture second;

    CabinetAccessHttpIntegrationTest(@LocalServerPort int port, DataSource dataSource,
                                     PasswordEncoder passwords, ProbeController probe)
    {
        this.port = port;
        this.jdbc = new JdbcTemplate(dataSource);
        this.passwords = passwords;
        this.probe = probe;
    }

    @BeforeEach
    void fixtures()
    {
        String hash = passwords.encode(PASSWORD);
        owner = account(hash);
        member = account(hash);
        otherOwner = account(hash);
        first = TestFixtures.createCabinetWithOwner(jdbc);
        second = TestFixtures.createCabinetWithOwner(jdbc);
        TestFixtures.createTenantProbeTable(jdbc, first.schemaName());
        TestFixtures.createTenantProbeTable(jdbc, second.schemaName());
        String sql1 = "INSERT INTO " + first.schemaName() + ".tenant_probe (id, marker) VALUES (?, ?)";
        jdbc.update(sql1, PROBE_ID, "FIRST");
        String sql2 = "INSERT INTO " + second.schemaName() + ".tenant_probe (id, marker) VALUES (?, ?)";
        jdbc.update(sql2, PROBE_ID, "SECOND");
        membership(first.cabinetId(), owner, "OWNER");
        membership(first.cabinetId(), member, "MEMBER");
        membership(second.cabinetId(), otherOwner, "OWNER");
        probe.calls.set(0);
    }

    @Test
    void realSessionOwnerAndMemberAccessCabinetUsingServerIdentityNotSpoofedInputs() throws Exception
    {
        Browser ownerBrowser = login(owner);
        Browser memberBrowser = login(member);
        var ownerResponse = ownerBrowser.get(path(first.cabinetId()) + "?userId=" + otherOwner + "&schemaName=public");
        assertEquals(200, ownerResponse.statusCode());
        assertTrue(ownerResponse.body().contains("FIRST"));
        assertTrue(ownerResponse.body().contains(owner.toString()));
        assertFalse(ownerResponse.body().contains(otherOwner.toString()));
        var memberResponse = memberBrowser.get(path(first.cabinetId()));
        assertEquals(200, memberResponse.statusCode());
        assertTrue(memberResponse.body().contains(member.toString()));
        assertEquals(2, probe.calls.get());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.membership_permissions", Integer.class));
    }

    @Test
    void crossCabinetUnknownAndAnonymousRequestsNeverRunBusinessCallback() throws Exception
    {
        Browser browser = login(owner);
        String unrelatedPath = path(second.cabinetId()) + "?userId=" + otherOwner
                + "&schemaName=" + schema(second.cabinetId());
        var unrelated = browser.get(unrelatedPath);
        var unknown = browser.get(path(UUID.randomUUID()));
        assertEquals(403, unrelated.statusCode());
        assertEquals(403, unknown.statusCode());
        assertEquals(unrelated.body(), unknown.body());
        assertEquals(401, new Browser(port).get(path(first.cabinetId()) + "?userId=" + owner).statusCode());
        assertEquals(0, probe.calls.get());
        assertFalse(unrelated.body().contains("cabinet_"));
    }

    @Test
    void membershipRevocationAndCabinetDeactivationTakeEffectWithoutRelogin() throws Exception
    {
        Browser browser = login(member);
        assertEquals(200, browser.get(path(first.cabinetId())).statusCode());
        String revokeSql = "UPDATE public.cabinet_memberships SET revoked_at = now() "
                + "WHERE cabinet_id = ? AND user_id = ?";
        jdbc.update(revokeSql, first.cabinetId(), member);
        assertEquals(403, browser.get(path(first.cabinetId())).statusCode());
        String unrevokeSql = "UPDATE public.cabinet_memberships SET revoked_at = NULL "
                + "WHERE cabinet_id = ? AND user_id = ?";
        jdbc.update(unrevokeSql, first.cabinetId(), member);
        String failSql = "UPDATE public.cabinets SET provisioning_status = 'PROVISIONING_FAILED' "
                + "WHERE id = ?";
        jdbc.update(failSql, first.cabinetId());
        assertEquals(403, browser.get(path(first.cabinetId())).statusCode());
        assertEquals(1, probe.calls.get());
    }

    @Test
    void businessExceptionCleansContextAndNextTenantRequestUsesOwnSchema() throws Exception
    {
        Browser firstBrowser = login(owner);
        Browser secondBrowser = login(otherOwner);
        assertEquals(500, firstBrowser.get(path(first.cabinetId()) + "/failure").statusCode());
        var next = secondBrowser.get(path(second.cabinetId()));
        assertEquals(200, next.statusCode());
        assertTrue(next.body().contains("SECOND"));
        var platform = firstBrowser.get("/task6-test/platform");
        assertEquals(200, platform.statusCode());
        assertEquals("false", platform.body());
    }

    @Test
    void suspendedAccountSessionCannotReachGatewayBusinessOperations() throws Exception
    {
        Browser browser = login(owner);
        jdbc.update("UPDATE public.users SET account_status = 'SUSPENDED' WHERE id = ?", owner);
        assertEquals(401, browser.get(path(first.cabinetId())).statusCode());
        assertEquals(0, probe.calls.get());
    }

    private Browser login(UUID account) throws Exception
    {
        Browser browser = new Browser(port);
        assertEquals(204, browser.get("/api/auth/csrf").statusCode());
        assertEquals(200, browser.login(account.toString()).statusCode());
        return browser;
    }

    private UUID account(String hash)
    {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO public.users (id, username, username_normalized, password_hash, deployment_mode,
                account_status, activated_at, created_at)
            VALUES (?, ?, ?, ?, 'local', 'ACTIVE', now(), now())
            """, id, id.toString(), id.toString(), hash);
        return id;
    }

    private void membership(UUID cabinet, UUID account, String role)
    {
        String sql = "INSERT INTO public.cabinet_memberships "
                + "(id, cabinet_id, user_id, membership_role, staff_type, status) "
                + "VALUES (?, ?, ?, ?, 'SECRETARY', 'ACTIVE')";
        jdbc.update(sql, UUID.randomUUID(), cabinet, account, role);
    }

    private static String path(UUID cabinet)
    {
        return "/task6-test/cabinets/" + cabinet;
    }

    private String schema(UUID cabinet)
    {
        return jdbc.queryForObject("SELECT schema_name FROM public.cabinets WHERE id = ?", String.class, cabinet);
    }

    // Excluded from normal component scanning; imported only by this test.
    @TestComponent
    @RestController
    static class ProbeController
    {
        /** Gateway enforcing cabinet access before running callbacks. */
        private final AuthorizedCabinetAccess access;
        /** Resolves the authenticated account for probe responses. */
        private final CurrentAccount currentAccount;
        /** Provides entity managers bound to tenant transactions. */
        private final EntityManagerFactory entityManagers;
        /** Transaction boundary for tenant-scoped probe reads. */
        private final TransactionTemplate transactions;
        /** Counts business callbacks reached by HTTP requests. */
        private final AtomicInteger calls = new AtomicInteger();

        ProbeController(AuthorizedCabinetAccess access, CurrentAccount currentAccount,
                        EntityManagerFactory entityManagers,
                        @Qualifier("transactionManager") PlatformTransactionManager transactionManager)
        {
            this.access = access;
            this.currentAccount = currentAccount;
            this.entityManagers = entityManagers;
            this.transactions = new TransactionTemplate(transactionManager);
        }

        @GetMapping("/task6-test/cabinets/{cabinetId}")
        ProbeResult read(@PathVariable UUID cabinetId)
        {
            return access.execute(cabinetId, () -> transactions.execute(status ->
            {
                calls.incrementAndGet();
                var em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
                assertNotNull(em);
                return new ProbeResult(currentAccount.requireId(), cabinetId,
                    em.find(TenantProbe.class, PROBE_ID).marker());
            }));
        }

        @GetMapping("/task6-test/cabinets/{cabinetId}/failure")
        void failure(@PathVariable UUID cabinetId)
        {
            access.execute(cabinetId, () ->
            {
                calls.incrementAndGet();
                throw new IllegalStateException("Fictional callback failure");
            });
        }

        @GetMapping("/task6-test/platform")
        boolean platform()
        {
            currentAccount.requireId();
            return TenantContext.isPresent();
        }
    }

    record ProbeResult(UUID accountId, UUID cabinetId, String marker)
    {
    }

    private static final class Browser
    {
        /** HTTP port of the test server. */
        private final int port;
        /** Cookie store preserving session and CSRF state between requests. */
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        /** HTTP client sharing the browser's cookie store. */
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        private Browser(int port)
        {
            this.port = port;
        }

        HttpResponse<String> get(String path) throws Exception
        {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", "ffffffff-ffff-ffff-ffff-ffffffffffff")
                .header("X-Tenant-Schema", "public").GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> login(String username) throws Exception
        {
            String csrf = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(java.net.HttpCookie::getValue).findFirst().orElseThrow();
            String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}