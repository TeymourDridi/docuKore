package com.docukore.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Scope lifecycle regressions using real thread-local state; no database or application context. */
class TenantContextTest
{
    /** First fictional cabinet identity. */
    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    /** Second fictional cabinet identity. */
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    /** Sequential allocation names are independent of cabinet UUIDs. */
    private static final String FIRST_SCHEMA = "cabinet00000";
    /** Separate fictional allocated namespace. */
    private static final String SECOND_SCHEMA = "cabinet00001";

    @AfterEach
    void clearThread()
    {
        TenantContext.clear();
    }

    @Test
    void absentContextFailsClosed()
    {
        assertAbsent();
        assertThrows(IllegalStateException.class, () -> TenantContext.requireMatches(FIRST));
        assertThrows(IllegalStateException.class,
            () -> new CabinetTenantIdentifierResolver().resolveCurrentTenantIdentifier());
        DataSource dataSource = mock(DataSource.class);
        assertThrows(IllegalStateException.class, () -> new CabinetTenantSchemaMapper(dataSource).schemaName(FIRST));
        verifyNoInteractions(dataSource);
    }

    @Test
    void mapperAcceptsOnlyMatchingTrustedUuidWithoutResolvingAnotherSchema()
    {
        DataSource dataSource = mock(DataSource.class);
        var mapper = new CabinetTenantSchemaMapper(dataSource);
        var resolver = new CabinetTenantIdentifierResolver();
        try (var scope = TenantContext.open(FIRST, FIRST_SCHEMA))
        {
            assertEquals(FIRST, resolver.resolveCurrentTenantIdentifier());
            assertTrue(resolver.validateExistingCurrentSessions());
            assertEquals(FIRST_SCHEMA, mapper.schemaName(FIRST));
            assertThrows(IllegalStateException.class, () -> mapper.schemaName(SECOND));
            assertThrows(IllegalStateException.class, () -> mapper.schemaName(null));
            assertBinding(FIRST, FIRST_SCHEMA);
        }
        verifyNoInteractions(dataSource);
        assertAbsent();
    }

    @Test
    void validScopeBindsCabinetAndAllocatedSchemaTogether()
    {
        try (var scope = TenantContext.open(FIRST, FIRST_SCHEMA))
        {
            assertBinding(FIRST, FIRST_SCHEMA);
            TenantContext.requireMatches(FIRST);
            assertThrows(IllegalStateException.class, () -> TenantContext.requireMatches(SECOND));
            assertThrows(IllegalStateException.class, () -> TenantContext.requireMatches(null));
            assertBinding(FIRST, FIRST_SCHEMA);
        }
        assertAbsent();
    }

    @Test
    void legacyAllocatedSchemaRemainsSupported()
    {
        String schema = "cabinet_11111111111111111111111111111111";
        try (var scope = TenantContext.open(FIRST, schema))
        {
            assertBinding(FIRST, schema);
        }
        assertAbsent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedScopesAreRejectedWithoutReplacingOuterBinding(boolean sameTenant)
    {
        UUID cabinet = sameTenant ? FIRST : SECOND;
        String schema = sameTenant ? FIRST_SCHEMA : SECOND_SCHEMA;
        try (var outer = TenantContext.open(FIRST, FIRST_SCHEMA))
        {
            assertThrows(IllegalStateException.class, () -> TenantContext.open(cabinet, schema));
            assertBinding(FIRST, FIRST_SCHEMA);
        }
        assertAbsent();
    }

    @Test
    void closingOnlyScopeClearsBothCabinetAndSchema()
    {
        var scope = TenantContext.open(FIRST, FIRST_SCHEMA);
        scope.close();
        assertAbsent();
    }

    @Test
    void doubleCloseWithoutReplacementIsSafe()
    {
        var scope = TenantContext.open(FIRST, FIRST_SCHEMA);
        scope.close();
        scope.close();
        assertAbsent();
    }

    @Test
    void closingOldScopeTwiceCannotClearNewContext()
    {
        var old = TenantContext.open(FIRST, FIRST_SCHEMA);
        old.close();
        try (var current = TenantContext.open(SECOND, SECOND_SCHEMA))
        {
            old.close();
            assertBinding(SECOND, SECOND_SCHEMA);
        }
        assertAbsent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingDetachedOuterScopeCannotClearInnerReplacement(boolean sameTenant)
    {
        var outer = TenantContext.open(FIRST, FIRST_SCHEMA);
        TenantContext.clear();
        UUID cabinet = sameTenant ? FIRST : SECOND;
        String schema = sameTenant ? FIRST_SCHEMA : SECOND_SCHEMA;
        try (var inner = TenantContext.open(cabinet, schema))
        {
            outer.close();
            assertBinding(cabinet, schema);
            outer.close();
            assertBinding(cabinet, schema);
        }
        assertAbsent();
    }

    @Test
    void closingInnerReplacementNeverRestoresDetachedOuterScope()
    {
        var outer = TenantContext.open(FIRST, FIRST_SCHEMA);
        TenantContext.clear();
        var inner = TenantContext.open(SECOND, SECOND_SCHEMA);
        inner.close();
        assertAbsent();
        outer.close();
        assertAbsent();
    }

    @Test
    void detachedScopeCloseAfterCleanupCannotCorruptLaterBinding()
    {
        var old = TenantContext.open(FIRST, FIRST_SCHEMA);
        TenantContext.clear();
        old.close();
        try (var current = TenantContext.open(SECOND, SECOND_SCHEMA))
        {
            old.close();
            assertBinding(SECOND, SECOND_SCHEMA);
        }
        assertAbsent();
    }

    @Test
    void explicitCleanupIsIdempotentAndDoesNotRestoreScopes()
    {
        var scope = TenantContext.open(FIRST, FIRST_SCHEMA);
        TenantContext.clear();
        TenantContext.clear();
        assertAbsent();
        scope.close();
        assertAbsent();
    }

    @Test
    void scopeClearsAfterException()
    {
        var failure = assertThrows(IllegalArgumentException.class, () ->
        {
            try (var scope = TenantContext.open(FIRST, FIRST_SCHEMA))
            {
                assertBinding(FIRST, FIRST_SCHEMA);
                throw new IllegalArgumentException("Fictional failure");
            }
        });
        assertEquals("Fictional failure", failure.getMessage());
        assertAbsent();
    }

    @Test
    void exceptionUnwindingDetachedOuterScopePreservesReplacement()
    {
        var outer = TenantContext.open(FIRST, FIRST_SCHEMA);
        TenantContext.clear();
        try (var inner = TenantContext.open(SECOND, SECOND_SCHEMA))
        {
            assertThrows(IllegalArgumentException.class, () ->
            {
                try (outer)
                {
                    throw new IllegalArgumentException("Fictional failure");
                }
            });
            assertBinding(SECOND, SECOND_SCHEMA);
        }
        assertAbsent();
    }

    @Test
    void nullAndNilCabinetIdsAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> TenantContext.open(null, FIRST_SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> TenantContext.open(new UUID(0, 0), FIRST_SCHEMA));
        assertAbsent();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", " cabinet00000", "cabinet00000 ", "cabinet 00000",
        "cabinet-00000", "platform.cabinets", "\"cabinet00000\"", "cabinet00000;SELECT", "Cabinet00000",
        "0cabinet", "cabinet/00000", "cabinet00000\u0000"})
    void invalidSchemaIsRejectedWithoutEstablishingContext(String schema)
    {
        assertThrows(IllegalArgumentException.class, () -> TenantContext.open(FIRST, schema));
        assertAbsent();
    }

    @Test
    void maximumLengthLowercaseIdentifierIsPreservedExactly()
    {
        String schema = "a".repeat(63);
        try (var scope = TenantContext.open(FIRST, schema))
        {
            assertBinding(FIRST, schema);
        }
        assertAbsent();
    }

    @Test
    void oversizedSchemaIsRejectedWithoutPostgresqlIdentifierTruncation()
    {
        assertThrows(IllegalArgumentException.class, () -> TenantContext.open(FIRST, "a".repeat(64)));
        assertAbsent();
    }

    @Test
    void rejectedInvalidOpenLeavesExistingBindingIntact()
    {
        try (var scope = TenantContext.open(FIRST, FIRST_SCHEMA))
        {
            assertThrows(IllegalArgumentException.class, () -> TenantContext.open(null, SECOND_SCHEMA));
            assertThrows(IllegalArgumentException.class, () -> TenantContext.open(SECOND, null));
            assertBinding(FIRST, FIRST_SCHEMA);
        }
        assertAbsent();
    }

    @Test
    void contextIsNotInheritedAndWorkerBindingDoesNotAffectParent() throws Exception
    {
        try (var parent = TenantContext.open(FIRST, FIRST_SCHEMA))
        {
            try (var executor = Executors.newSingleThreadExecutor())
            {
                executor.submit(() ->
                {
                    assertAbsent();
                    try (var worker = TenantContext.open(SECOND, SECOND_SCHEMA))
                    {
                        assertBinding(SECOND, SECOND_SCHEMA);
                    }
                    assertAbsent();
                }).get(5, TimeUnit.SECONDS);
            }
            assertBinding(FIRST, FIRST_SCHEMA);
        }
        assertAbsent();
    }

    @Test
    void wrongThreadCloseCannotClearEitherThreadsBindingOrConsumeScope() throws Exception
    {
        var parent = TenantContext.open(FIRST, FIRST_SCHEMA);
        try (parent; var executor = Executors.newSingleThreadExecutor())
        {
            executor.submit(() ->
            {
                assertAbsent();
                try (var worker = TenantContext.open(SECOND, SECOND_SCHEMA))
                {
                    assertThrows(IllegalStateException.class, parent::close);
                    assertBinding(SECOND, SECOND_SCHEMA);
                }
                assertAbsent();
            }).get(5, TimeUnit.SECONDS);
            assertBinding(FIRST, FIRST_SCHEMA);
        }
        assertAbsent();
    }

    @Test
    void exceptionCleanupPreventsLeakIntoNextTaskOnSameWorker() throws Exception
    {
        try (var executor = Executors.newSingleThreadExecutor())
        {
            var firstThread = executor.submit(() ->
            {
                assertAbsent();
                assertThrows(IllegalArgumentException.class, () ->
                {
                    try (var scope = TenantContext.open(FIRST, FIRST_SCHEMA))
                    {
                        throw new IllegalArgumentException("Fictional worker failure");
                    }
                });
                assertAbsent();
                return Thread.currentThread();
            }).get(5, TimeUnit.SECONDS);
            executor.submit(() ->
            {
                assertEquals(firstThread, Thread.currentThread());
                assertAbsent();
                try (var scope = TenantContext.open(SECOND, SECOND_SCHEMA))
                {
                    assertBinding(SECOND, SECOND_SCHEMA);
                }
                assertAbsent();
            }).get(5, TimeUnit.SECONDS);
        }
        assertAbsent();
    }

    private static void assertBinding(UUID cabinet, String schema)
    {
        assertTrue(TenantContext.isPresent());
        assertEquals(cabinet, TenantContext.requireCabinetId());
        assertEquals(schema, TenantContext.getSchemaName());
    }

    private static void assertAbsent()
    {
        assertFalse(TenantContext.isPresent());
        assertThrows(IllegalStateException.class, TenantContext::requireCabinetId);
        assertThrows(IllegalStateException.class, TenantContext::getSchemaName);
    }
}
