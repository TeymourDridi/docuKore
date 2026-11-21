package com.docukore.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Request-boundary cleanup regressions using the real filter and real thread-local scopes. */
class TenantRequestFilterTest
{
    /** First fictional cabinet identity. */
    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    /** Second fictional cabinet identity. */
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    /** First allocated tenant namespace. */
    private static final String FIRST_SCHEMA = "cabinet00000";
    /** Second allocated tenant namespace. */
    private static final String SECOND_SCHEMA = "cabinet00001";
    /** External dependency required only to construct the filter; no tenant resolution is exercised. */
    private final CabinetTenantAccess tenantAccess = mock(CabinetTenantAccess.class);
    /** Actual request filter under test. */
    private final TenantRequestFilter filter = new TenantRequestFilter(tenantAccess);
    /** Real Spring servlet request with no trusted cabinet attribute by default. */
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    /** Real Spring servlet response passed to the downstream chain. */
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearThreadAndVerifyNoTenantResolution()
    {
        TenantContext.clear();
        verifyNoInteractions(tenantAccess);
    }

    @Test
    void normalRequestClearsBindingLeftOpenByChain() throws ServletException, IOException
    {
        assertAbsent();
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            assertSame(request, chainRequest);
            assertSame(response, chainResponse);
            assertAbsent();
            TenantContext.open(FIRST, FIRST_SCHEMA);
            assertBinding(FIRST, FIRST_SCHEMA);
            response.setStatus(204);
        };

        runFilter(chain);

        assertEquals(204, response.getStatus());
        assertAbsent();
    }

    @Test
    void servletExceptionClearsBindingAndPreservesExactException()
    {
        ServletException failure = new ServletException("Synthetic downstream failure");
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            assertAbsent();
            TenantContext.open(FIRST, FIRST_SCHEMA);
            assertBinding(FIRST, FIRST_SCHEMA);
            throw failure;
        };

        ServletException actual = assertThrows(ServletException.class, () -> runFilter(chain));

        assertSame(failure, actual);
        assertAbsent();
    }

    @Test
    void runtimeExceptionClearsBindingAndPreservesExactException()
    {
        IllegalStateException failure = new IllegalStateException("Synthetic downstream failure");
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            assertAbsent();
            TenantContext.open(FIRST, FIRST_SCHEMA);
            assertBinding(FIRST, FIRST_SCHEMA);
            throw failure;
        };

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> runFilter(chain));

        assertSame(failure, actual);
        assertAbsent();
    }

    @Test
    void staleContextIsRejectedBeforeChainAndCleared()
    {
        TenantContext.open(FIRST, FIRST_SCHEMA);
        assertBinding(FIRST, FIRST_SCHEMA);
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            fail("A request entering with stale context must not reach the chain");
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runFilter(chain));

        assertEquals("Stale tenant context at request entry", failure.getMessage());
        assertAbsent();
    }

    @Test
    void invalidTrustedAttributeIsRejectedBeforeChain()
    {
        assertAbsent();
        request.setAttribute(TenantRequestFilter.TRUSTED_CABINET_ATTRIBUTE, FIRST.toString());
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            fail("An invalid trusted attribute must not reach the chain");
        };

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> runFilter(chain));

        assertEquals("Trusted cabinet selection must be a UUID", failure.getMessage());
        assertAbsent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detachedScopeCannotClearReplacementButFilterFinallyDoes(boolean sameTenant)
    {
        UUID replacementCabinet = sameTenant ? FIRST : SECOND;
        String replacementSchema = sameTenant ? FIRST_SCHEMA : SECOND_SCHEMA;
        IllegalStateException failure = new IllegalStateException("Synthetic failure after replacement");
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            assertAbsent();
            var detached = TenantContext.open(FIRST, FIRST_SCHEMA);
            assertBinding(FIRST, FIRST_SCHEMA);
            TenantContext.clear();
            assertAbsent();
            TenantContext.open(replacementCabinet, replacementSchema);
            assertBinding(replacementCabinet, replacementSchema);

            detached.close();

            assertBinding(replacementCabinet, replacementSchema);
            throw failure;
        };

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> runFilter(chain));

        assertSame(failure, actual);
        assertAbsent();
    }

    private void runFilter(FilterChain chain) throws ServletException, IOException
    {
        filter.doFilter(request, response, chain);
    }

    private static void assertBinding(UUID cabinetId, String schemaName)
    {
        assertTrue(TenantContext.isPresent());
        assertEquals(cabinetId, TenantContext.requireCabinetId());
        assertEquals(schemaName, TenantContext.getSchemaName());
    }

    private static void assertAbsent()
    {
        assertFalse(TenantContext.isPresent());
        assertThrows(IllegalStateException.class, TenantContext::requireCabinetId);
        assertThrows(IllegalStateException.class, TenantContext::getSchemaName);
    }
}
