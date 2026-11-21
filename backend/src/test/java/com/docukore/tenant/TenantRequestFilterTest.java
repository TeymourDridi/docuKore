package com.docukore.tenant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
    /** Synthetic request and throwable values forbidden in diagnostics. */
    private static final String[] SENSITIVE = {
        "/synthetic-tenant-uri", "synthetic-tenant-query", "synthetic-tenant@example.invalid",
        "synthetic-tenant-failure", "synthetic-tenant-cause", FIRST.toString(), SECOND.toString(),
        FIRST_SCHEMA, SECOND_SCHEMA, SensitiveSelectionCanary.class.getName()
    };
    /** Safe correlation value supplied by the owning request boundary. */
    private static final String REQUEST_ID = "77777777-7777-7777-7777-777777777777";
    /** External dependency required only to construct the filter; no tenant resolution is exercised. */
    private final CabinetTenantAccess tenantAccess = mock(CabinetTenantAccess.class);
    /** Actual request filter under test. */
    private final TenantRequestFilter filter = new TenantRequestFilter(tenantAccess);
    /** Real Spring servlet request with no trusted cabinet attribute by default. */
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    /** Real Spring servlet response passed to the downstream chain. */
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    /** Class-local logger captured without changing root logging. */
    private final Logger logger = (Logger) LoggerFactory.getLogger(TenantRequestFilter.class);
    /** In-memory capture that freezes MDC immediately. */
    private final SnapshotAppender appender = new SnapshotAppender();
    /** Prior explicit logging level, possibly inherited. */
    private Level previousLevel;
    /** Prior parent-appender delegation. */
    private boolean previousAdditive;
    /** Prior request correlation restored after each test. */
    private String previousRequestId;

    @BeforeEach
    void captureDiagnostics()
    {
        request.setRequestURI(SENSITIVE[0]);
        request.setQueryString(SENSITIVE[1]);
        request.setRemoteUser(SENSITIVE[2]);
        previousRequestId = MDC.get("requestId");
        MDC.put("requestId", REQUEST_ID);
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
    }

    @AfterEach
    void clearThreadAndVerifyNoTenantResolution()
    {
        try
        {
            assertPrivateEvents();
            verifyNoInteractions(tenantAccess);
        } finally
        {
            TenantContext.clear();
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            if (previousRequestId == null)
            {
                MDC.remove("requestId");
            } else
            {
                MDC.put("requestId", previousRequestId);
            }
        }
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
        assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                .containsExactly("TENANT_REQUEST_ENTER", "TENANT_SELECTION_ABSENT", "TENANT_REQUEST_EXIT");
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }

    @Test
    void servletExceptionClearsBindingAndPreservesExactException()
    {
        ServletException failure = new ServletException(SENSITIVE[3], new IllegalStateException(SENSITIVE[4]));
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
        IllegalStateException failure = new IllegalStateException(SENSITIVE[3],
                new IllegalArgumentException(SENSITIVE[4]));
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
        assertPropagation("ILLEGAL_STATE", Level.ERROR);
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
        assertPropagation("ILLEGAL_STATE", Level.ERROR);
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
        assertPropagation("ILLEGAL_ARGUMENT", Level.WARN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detachedScopeCannotClearReplacementButFilterFinallyDoes(boolean sameTenant)
    {
        UUID replacementCabinet = sameTenant ? FIRST : SECOND;
        String replacementSchema = sameTenant ? FIRST_SCHEMA : SECOND_SCHEMA;
        IllegalStateException failure = new IllegalStateException(SENSITIVE[3],
                new IllegalArgumentException(SENSITIVE[4]));
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

    @Test
    void ioExceptionClearsBindingAndPreservesExactException()
    {
        IOException failure = new IOException(SENSITIVE[3], new IllegalStateException(SENSITIVE[4]));
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            TenantContext.open(FIRST, FIRST_SCHEMA);
            throw failure;
        };

        assertSame(failure, assertThrows(IOException.class, () -> runFilter(chain)));

        assertAbsent();
        assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                .containsExactly("TENANT_REQUEST_ENTER", "TENANT_SELECTION_ABSENT", "TENANT_REQUEST_EXIT");
    }

    @Test
    void downstreamArgumentFailureIsPropagationNotATenantOriginDiagnosis()
    {
        IllegalArgumentException failure = new IllegalArgumentException(SENSITIVE[3],
                new IllegalStateException(SENSITIVE[4]));
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            TenantContext.open(FIRST, FIRST_SCHEMA);
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> runFilter(chain)));

        assertAbsent();
        assertPropagation("ILLEGAL_ARGUMENT", Level.WARN);
    }

    @Test
    void invalidSelectionDoesNotLogArbitraryClassOrItsStringValue()
    {
        request.setAttribute(TenantRequestFilter.TRUSTED_CABINET_ATTRIBUTE, new SensitiveSelectionCanary());
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            fail("An invalid trusted attribute must not reach the chain");
        };

        var failure = assertThrows(IllegalArgumentException.class, () -> runFilter(chain));

        assertEquals("Trusted cabinet selection must be a UUID", failure.getMessage());
        assertAbsent();
        assertPropagation("ILLEGAL_ARGUMENT", Level.WARN);
    }

    @Test
    void trustedScopeCompletesWithDebugEventsWithoutCabinetOrSchema() throws ServletException, IOException
    {
        var access = mock(CabinetTenantAccess.class);
        var scopedFilter = new TenantRequestFilter(access);
        request.setAttribute(TenantRequestFilter.TRUSTED_CABINET_ATTRIBUTE, FIRST);
        when(access.open(FIRST)).thenAnswer(invocation -> TenantContext.open(FIRST, FIRST_SCHEMA));
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            assertBinding(FIRST, FIRST_SCHEMA);
            response.setStatus(204);
        };

        scopedFilter.doFilter(request, response, chain);

        assertEquals(204, response.getStatus());
        assertAbsent();
        verify(access).open(FIRST);
        verifyNoMoreInteractions(access);
        assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                .containsExactly("TENANT_REQUEST_ENTER", "TENANT_SCOPE_OPENING", "TENANT_SCOPE_CLOSED",
                        "TENANT_REQUEST_EXIT");
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }

    private void runFilter(FilterChain chain) throws ServletException, IOException
    {
        filter.doFilter(request, response, chain);
    }

    private void assertPropagation(String category, Level level)
    {
        String message = "TENANT_REQUEST_EXCEPTION_PROPAGATED category=" + category;
        assertThat(appender.list).filteredOn(event -> event.getMessage().equals(message))
                .singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(level));
        assertThat(appender.list.getLast().getMessage()).isEqualTo("TENANT_REQUEST_EXIT");
    }

    private void assertPrivateEvents()
    {
        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
        for (ILoggingEvent event : appender.list)
        {
            assertThat(event.getLoggerName()).isEqualTo(TenantRequestFilter.class.getName());
            assertThat(event.getMessage()).isIn("TENANT_REQUEST_ENTER", "TENANT_SELECTION_ABSENT",
                    "TENANT_SCOPE_OPENING", "TENANT_SCOPE_CLOSED", "TENANT_REQUEST_EXIT",
                    "TENANT_REQUEST_EXCEPTION_PROPAGATED category=ILLEGAL_ARGUMENT",
                    "TENANT_REQUEST_EXCEPTION_PROPAGATED category=ILLEGAL_STATE");
            assertThat(event.getMessage()).doesNotContain(SENSITIVE);
            assertThat(event.getFormattedMessage()).doesNotContain(SENSITIVE);
            assertThat(event.getArgumentArray()).isNullOrEmpty();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).isNullOrEmpty();
            assertThat(event.getLevel()).isNotEqualTo(Level.INFO);
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", REQUEST_ID);
            event.getMDCPropertyMap().forEach((key, value) ->
            {
                assertThat(key).doesNotContain(SENSITIVE);
                assertThat(value).doesNotContain(SENSITIVE);
            });
        }
    }

    /** Invalid selection whose class name and string representation both contain forbidden synthetic data. */
    private static final class SensitiveSelectionCanary
    {
        @Override
        public String toString()
        {
            return SENSITIVE[2];
        }
    }

    /** Eagerly snapshots formatted messages and MDC before request cleanup or test teardown. */
    private static final class SnapshotAppender extends ListAppender<ILoggingEvent>
    {
        @Override
        protected void append(ILoggingEvent event)
        {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
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
