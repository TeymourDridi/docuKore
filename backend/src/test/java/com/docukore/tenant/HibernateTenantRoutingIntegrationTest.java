package com.docukore.tenant;

import com.docukore.support.OwnedTestDatabase;
import com.docukore.support.SharedSpringBootTest;
import com.docukore.support.TestFixtures;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class HibernateTenantRoutingIntegrationTest extends SharedSpringBootTest
{

    /** Latest-migrated class-owned platform, independent from public and other test classes. */
    private static final TestFixtures.OwnedPlatform PLATFORM = TestFixtures.createPlatform("hibernate_platform");
    /** Migrator-only exact creation receipts for every tenant used by this class. */
    private final OwnedTestDatabase.Scope resources;
    /** Creates tenant-scoped entity managers for routing tests. */
    private final EntityManagerFactory entityManagers;
    /** Connection pool used to verify schema restoration and connection reuse. */
    private final HikariDataSource dataSource;
    /** Establishes trusted tenant context for simulated requests. */
    private final TenantRequestFilter filter;
    /** JDBC access for shared platform fixtures and schema provisioning. */
    private final JdbcTemplate platformJdbc;
    /** First cabinet fixture used for tenant isolation assertions. */
    private TestFixtures.CabinetFixture first;
    /** Second cabinet fixture used for tenant isolation assertions. */
    private TestFixtures.CabinetFixture second;
    /** Fixed entity ID for tenant isolation tests. */
    private static final UUID ENTITY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    HibernateTenantRoutingIntegrationTest(EntityManagerFactory entityManagers,
            HikariDataSource dataSource, TenantRequestFilter filter)
    {
        this.entityManagers = entityManagers;
        this.dataSource = dataSource;
        this.filter = filter;
        this.platformJdbc = PLATFORM.jdbc();
        this.resources = OwnedTestDatabase.scope(PLATFORM.migratorDataSource());
    }

    @DynamicPropertySource
    static void isolatedPlatform(DynamicPropertyRegistry registry)
    {
        registerPlatform(registry, PLATFORM);
    }

    @BeforeEach
    void provisionTestSchemas()
    {
        first = createFixture();
        second = createFixture();
        TestFixtures.createTenantProbeTable(platformJdbc, first.schemaName());
        TestFixtures.createTenantProbeTable(platformJdbc, second.schemaName());
    }

    @Test
    void jpaWritesReadsUpdatesAndSameIdsAreIsolatedWithPhysicalConnectionReuse() throws Exception
    {
        int pid = request(first.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "FIRST"));
            return backend(em);
        });
        assertEquals(Integer.valueOf(pid), request(second.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "SECOND"));
            return backend(em);
        }));
        assertEquals("FIRST", request(first.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID).marker()));
        String q = "select p from TenantProbe p";
        assertEquals("SECOND", request(second.cabinetId(), em ->
                em.createQuery(q, TenantProbe.class).getSingleResult().marker()));
        request(first.cabinetId(), em ->
        {
            em.find(TenantProbe.class, ENTITY_ID).marker("UPDATED");
            return null;
        });
        assertEquals("UPDATED", request(first.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID).marker()));
        assertEquals("SECOND", request(second.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID).marker()));
        assertBaselineConnection(pid);
    }

    @Test
    void applicationAndSqlFailuresRollbackAndClearBeforeOtherTenantSucceeds() throws Exception
    {
        int pid = request(first.cabinetId(), HibernateTenantRoutingIntegrationTest::backend);
        assertThrows(IllegalArgumentException.class, () -> request(first.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "ROLLBACK"));
            em.flush();
            throw new IllegalArgumentException("Fictional failure");
        }));
        assertFalse(TenantContext.isPresent());
        assertThrows(RuntimeException.class, () -> request(first.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "SQL ROLLBACK"));
            em.flush();
            em.createNativeQuery("SELECT 1 / 0", Integer.class).getSingleResult();
            return null;
        }));
        assertFalse(TenantContext.isPresent());
        assertEquals(Integer.valueOf(pid), request(second.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "SECOND"));
            return backend(em);
        }));
        assertNull(request(first.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID)));
        assertEquals("SECOND", request(second.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID).marker()));
        assertBaselineConnection(pid);
    }

    @Test
    void missingContextCannotOpenEntityManagerButPlatformJdbcWorks()
    {
        assertThrows(RuntimeException.class, entityManagers::createEntityManager);
        String countSql = "SELECT count(*) FROM " + OwnedTestDatabase.identifier(PLATFORM.schema()) + ".cabinets";
        assertTrue(platformJdbc.queryForObject(countSql, Integer.class) >= 2);
    }

    @Test
    void explicitDifferentSessionTenantIsRejectedBeforeQuery() throws Exception
    {
        request(first.cabinetId(), em ->
        {
            try (Session other = entityManagers.unwrap(SessionFactory.class)
                    .withOptions().tenantIdentifier(second.cabinetId()).openSession())
            {
                String q = "from TenantProbe";
                assertThrows(RuntimeException.class, () -> other.createQuery(q, TenantProbe.class).getResultList());
            }
            return null;
        });
    }

    @Test
    void springManagedJpaTransactionUsesTrustedTenantAndRestoresConnection() throws Exception
    {
        var transactions = new TransactionTemplate(
            new org.springframework.orm.jpa.JpaTransactionManager(entityManagers));
        var request = new MockHttpServletRequest();
        request.setAttribute(TenantRequestFilter.TRUSTED_CABINET_ATTRIBUTE, first.cabinetId());
        filter.doFilter(request, new MockHttpServletResponse(),
            (req, res) -> transactions.executeWithoutResult(status ->
            {
                EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
                assertNotNull(em);
                em.persist(new TenantProbe(ENTITY_ID, "SPRING"));
                String schemaQuery = "SELECT current_schema()";
                assertEquals(first.schemaName(), em.createNativeQuery(schemaQuery, String.class).getSingleResult());
            }));
        assertFalse(TenantContext.isPresent());
        assertEquals("SPRING", request(first.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID).marker()));
        assertNull(request(second.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID)));
        try (Connection connection = dataSource.getConnection())
        {
            assertEquals(PLATFORM.schema(), connection.getSchema());
        }
    }

    @Test
    void matchingTenantCanMergeRefreshAndRemoveWithoutAffectingOtherSchema() throws Exception
    {
        request(first.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "FIRST"));
            return null;
        });
        request(second.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "SECOND"));
            return null;
        });
        request(first.cabinetId(), em ->
        {
            TenantProbe changed = em.merge(new TenantProbe(ENTITY_ID, "MERGED"));
            em.flush();
            em.refresh(changed);
            assertEquals("MERGED", changed.marker());
            em.remove(changed);
            return null;
        });
        assertNull(request(first.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID)));
        assertEquals("SECOND", request(second.cabinetId(), em -> em.find(TenantProbe.class, ENTITY_ID).marker()));
    }

    @Test
    void explicitlySuppliedEntityManagerUuidCannotOverrideTrustedContext() throws Exception
    {
        // Add data to second schema
        request(second.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "SECOND"));
            return null;
        });

        request(first.cabinetId(), em ->
        {
            // Using EntityManager.unwrap to get Session ensures guard is active
            Session session = em.unwrap(Session.class);
            SessionFactory sf = session.getSessionFactory();
            try (Session other = sf.withOptions()
                    .tenantIdentifier(second.cabinetId()).openSession())
            {
                // Should throw because session tenant (second) != context (first)
                String q = "from TenantProbe";
                assertThrows(RuntimeException.class, () -> other.createQuery(q, TenantProbe.class).getResultList());
            }
            return null;
        });
    }

    @Test
    void platformQualifiedJdbcAccessDuringTenantTransactionDoesNotChangeRouting() throws Exception
    {
        request(first.cabinetId(), em ->
        {
            em.unwrap(Session.class).doWork(connection ->
            {
                String countSql = "SELECT count(*) FROM "
                        + OwnedTestDatabase.identifier(PLATFORM.schema()) + ".cabinets";
                try (var statement = connection.prepareStatement(countSql); var rows = statement.executeQuery())
                {
                    assertTrue(rows.next());
                    assertTrue(rows.getInt(1) >= 2);
                }
            });
            String schemaQuery = "SELECT current_schema()";
            assertEquals(first.schemaName(), em.createNativeQuery(schemaQuery, String.class).getSingleResult());
            return null;
        });
    }

    @Test
    void existingEntityManagerCannotReadCachedEntityAfterContextIsClearedOrChanged() throws Exception
    {
        request(first.cabinetId(), em ->
        {
            em.persist(new TenantProbe(ENTITY_ID, "FIRST"));
            em.flush();
            TenantProbe loaded = em.find(TenantProbe.class, ENTITY_ID);
            assertEquals("FIRST", loaded.marker());
            TenantContext.clear();
            try
            {
                assertThrows(IllegalStateException.class, () -> em.find(TenantProbe.class, ENTITY_ID));
                try (var ignored = TenantContext.open(second.cabinetId(), second.schemaName()))
                {
                    assertThrows(IllegalStateException.class, () -> em.find(TenantProbe.class, ENTITY_ID));
                    String q = "from TenantProbe";
                    assertThrows(IllegalStateException.class,
                            () -> em.createQuery(q, TenantProbe.class).getResultList());
                    String countSql = "SELECT count(*) FROM tenant_probe";
                    assertThrows(IllegalStateException.class,
                            () -> em.createNativeQuery(countSql, Long.class).getSingleResult());
                    TenantProbe p1 = new TenantProbe(UUID.randomUUID(), "DENIED");
                    TenantProbe p2 = new TenantProbe(ENTITY_ID, "DENIED");
                    assertThrows(IllegalStateException.class, () -> em.getReference(TenantProbe.class, ENTITY_ID));
                    assertThrows(IllegalStateException.class, () -> em.persist(p1));
                    assertThrows(IllegalStateException.class, () -> em.merge(p2));
                    assertThrows(IllegalStateException.class, () -> em.remove(loaded));
                    assertThrows(IllegalStateException.class, () -> em.refresh(p2));
                    assertThrows(IllegalStateException.class, em::flush);
                }
            } finally
            {
                TenantContext.open(first.cabinetId(), first.schemaName());
            }
            return null;
        });
    }

    private TestFixtures.CabinetFixture createFixture()
    {
        UUID owner = TestFixtures.createUser(platformJdbc, PLATFORM.schema());
        return TestFixtures.createCabinet(platformJdbc, PLATFORM.schema(), owner, resources);
    }

    @AfterAll
    void removeOnlyOwnedFixtures()
    {
        // Prevent cached runtime sessions outliving their tenant/platform namespaces.
        dataSource.close();
        resources.close();
        PLATFORM.close();
    }

    private <T> T request(UUID cabinet, Function<EntityManager, T> action) throws Exception
    {
        var request = new MockHttpServletRequest();
        request.setAttribute(TenantRequestFilter.TRUSTED_CABINET_ATTRIBUTE, cabinet);
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
        {
            try (EntityManager em = entityManagers.createEntityManager())
            {
                em.getTransaction().begin();
                try
                {
                    // Verify current schema matches the requested cabinet
                    String expectedSchema = cabinet.equals(first.cabinetId())
                            ? first.schemaName() : second.schemaName();
                    String schemaQuery = "SELECT current_schema()";
                    assertEquals(expectedSchema,
                            em.createNativeQuery(schemaQuery, String.class).getSingleResult());
                    result.set(action.apply(em));
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
        return result.get();
    }

    private static int backend(EntityManager em)
    {
        return (Integer) em.createNativeQuery("SELECT pg_backend_pid()", Integer.class).getSingleResult();
    }

    private void assertBaselineConnection(int expectedPid) throws Exception
    {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement())
        {
            assertEquals(PLATFORM.schema(), connection.getSchema());
            try (var rows = statement.executeQuery("SELECT pg_backend_pid()"))
            {
                assertTrue(rows.next());
                assertEquals(expectedPid, rows.getInt(1));
            }
        }
    }
}