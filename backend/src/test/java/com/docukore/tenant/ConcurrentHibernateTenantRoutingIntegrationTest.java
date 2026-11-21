package com.docukore.tenant;

import com.docukore.support.SharedSpringBootTest;
import com.docukore.support.TestFixtures;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestConstructor;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConcurrentHibernateTenantRoutingIntegrationTest extends SharedSpringBootTest
{

    /** Creates tenant-scoped entity managers for concurrent routing tests. */
    private final EntityManagerFactory entityManagers;
    /** Connection pool used to verify concurrent tenant isolation. */
    private final HikariDataSource dataSource;
    /** Establishes trusted tenant context for simulated requests. */
    private final TenantRequestFilter filter;
    /** JDBC access for tenant fixtures and persisted marker assertions. */
    private final JdbcTemplate jdbc;
    /** First cabinet fixture used for overlapping tenant requests. */
    private TestFixtures.CabinetFixture first;
    /** Second cabinet fixture used for overlapping tenant requests. */
    private TestFixtures.CabinetFixture second;

    ConcurrentHibernateTenantRoutingIntegrationTest(EntityManagerFactory entityManagers,
            HikariDataSource dataSource, TenantRequestFilter filter)
    {
        this.entityManagers = entityManagers;
        this.dataSource = dataSource;
        this.filter = filter;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void provisionTestSchemas()
    {
        first = TestFixtures.createCabinetWithOwner(jdbc);
        second = TestFixtures.createCabinetWithOwner(jdbc);
        TestFixtures.createTenantProbeTable(jdbc, first.schemaName());
        TestFixtures.createTenantProbeTable(jdbc, second.schemaName());
    }

    @Test
    void overlappingRequestsAndReusedWorkerThreadsStayInTheirOwnSchemas() throws Exception
    {
        UUID entityId = UUID.randomUUID();
        CyclicBarrier overlappingTransactions = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2))
        {
            var firstWrite = executor.submit(write(first.cabinetId(), entityId, "FIRST", overlappingTransactions));
            var secondWrite = executor.submit(write(second.cabinetId(), entityId, "SECOND", overlappingTransactions));
            assertNotEquals(
                firstWrite.get(30, TimeUnit.SECONDS),
                secondWrite.get(30, TimeUnit.SECONDS),
                "Transactions must overlap on different physical connections");
            var reusedFirst = executor.submit(read(second.cabinetId(), entityId, "SECOND"));
            var reusedSecond = executor.submit(read(first.cabinetId(), entityId, "FIRST"));
            assertTrue(reusedFirst.get(30, TimeUnit.SECONDS));
            assertTrue(reusedSecond.get(30, TimeUnit.SECONDS));
        }
        String sql1 = "SELECT marker FROM " + first.schemaName() + ".tenant_probe WHERE id = ?";
        String sql2 = "SELECT marker FROM " + second.schemaName() + ".tenant_probe WHERE id = ?";
        assertEquals("FIRST", jdbc.queryForObject(sql1, String.class, entityId));
        assertEquals("SECOND", jdbc.queryForObject(sql2, String.class, entityId));
        try (var firstConnection = dataSource.getConnection(); var secondConnection = dataSource.getConnection())
        {
            assertEquals("public", firstConnection.getSchema());
            assertEquals("public", secondConnection.getSchema());
        }
    }

    private Callable<Integer> write(UUID cabinet, UUID entityId, String marker, CyclicBarrier barrier)
    {
        return () ->
        {
            assertFalse(TenantContext.isPresent());
            java.util.concurrent.atomic.AtomicInteger pid = new java.util.concurrent.atomic.AtomicInteger();
            var request = request(cabinet);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            {
                try (var em = entityManagers.createEntityManager())
                {
                    em.getTransaction().begin();
                    try
                    {
                        assertEquals(cabinet, TenantContext.requireCabinetId());
                        String expectedSchema = cabinet.equals(first.cabinetId())
                                ? first.schemaName() : second.schemaName();
                        String schemaQuery = "SELECT current_schema()";
                        assertEquals(expectedSchema, em.createNativeQuery(schemaQuery, String.class).getSingleResult());
                        String pidQuery = "SELECT pg_backend_pid()";
                        pid.set((Integer) em.createNativeQuery(pidQuery, Integer.class).getSingleResult());
                        em.persist(new TenantProbe(entityId, marker));
                        em.flush();
                        try
                        {
                            barrier.await(15, TimeUnit.SECONDS);
                        } catch (Exception failure)
                        {
                            throw new IllegalStateException("Concurrent test synchronization failed", failure);
                        }
                        em.getTransaction().commit();
                    } finally
                    {
                        if (em.getTransaction().isActive())
                        {
                            em.getTransaction().rollback();
                        }
                    }
                }
            });
            assertFalse(TenantContext.isPresent());
            return pid.get();
        };
    }

    private Callable<Boolean> read(UUID cabinet, UUID entityId, String expected)
    {
        return () ->
        {
            assertFalse(TenantContext.isPresent());
            filter.doFilter(request(cabinet), new MockHttpServletResponse(), (req, res) ->
            {
                try (var em = entityManagers.createEntityManager())
                {
                    assertEquals(expected, em.find(TenantProbe.class, entityId).marker());
                }
            });
            return !TenantContext.isPresent();
        };
    }

    private MockHttpServletRequest request(UUID cabinet)
    {
        var request = new MockHttpServletRequest();
        request.setAttribute(TenantRequestFilter.TRUSTED_CABINET_ATTRIBUTE, cabinet);
        return request;
    }
}