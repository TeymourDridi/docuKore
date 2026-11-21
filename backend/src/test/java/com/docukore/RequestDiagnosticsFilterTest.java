package com.docukore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Database-free correlation, privacy and servlet-dispatch regressions. */
class RequestDiagnosticsFilterTest
{
    /** Synthetic sensitive values that must never enter request diagnostic events. */
    private static final String[] SENSITIVE = {
        "sensitive-request-path", "sensitive-request-query", "sensitive-request-cookie",
        "sensitive-request-body", "sensitive-request-exception", "sensitive-request-header"
    };
    /** Actual request boundary under test. */
    private final RequestDiagnosticsFilter filter = new RequestDiagnosticsFilter();
    /** Real mock request with a fictional sensitive path. */
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/" + SENSITIVE[0]);
    /** Real mock response exposing the generated header. */
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    /** Class-local logger, isolated from other appenders during each test. */
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestDiagnosticsFilter.class);
    /** Eager log capture so MDC is recorded before the request finally block cleans it. */
    private final SnapshotAppender appender = new SnapshotAppender();
    /** Original explicit logger level. */
    private Level previousLevel;
    /** Original logger propagation setting. */
    private boolean previousAdditive;
    /** Original thread context restored after each test. */
    private Map<String, String> previousMdc;

    @BeforeEach
    void capture()
    {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        request.setQueryString(SENSITIVE[1]);
        request.addHeader("Cookie", SENSITIVE[2]);
        request.addHeader("Authorization", SENSITIVE[5]);
        request.setContent(SENSITIVE[3].getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore()
    {
        try
        {
            for (ILoggingEvent event : appender.list)
            {
                assertThat(event.getMessage()).doesNotContain(SENSITIVE);
                assertThat(event.getFormattedMessage()).doesNotContain(SENSITIVE);
                assertThat(event.getThrowableProxy()).isNull();
                if (event.getArgumentArray() != null)
                {
                    for (Object argument : event.getArgumentArray())
                    {
                        assertThat(String.valueOf(argument)).doesNotContain(SENSITIVE);
                    }
                }
                event.getMDCPropertyMap().forEach((key, value) ->
                {
                    assertThat(key).doesNotContain(SENSITIVE);
                    assertThat(value).doesNotContain(SENSITIVE);
                });
            }
        } finally
        {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            MDC.clear();
            if (previousMdc != null)
            {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    @Test
    void generatesHeaderBeforeChainAndIgnoresIncomingId() throws Exception
    {
        String forged = UUID.randomUUID().toString();
        request.addHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER, forged);
        MDC.put("unrelated", "preserved");
        filter.doFilter(request, response, (input, output) ->
        {
            assertSame(request, input);
            assertSame(response, output);
            assertCorrelated();
            assertThat(response.getHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER)).isNotEqualTo(forged);
            assertThat(MDC.get("unrelated")).isEqualTo("preserved");
            response.setStatus(204);
        });

        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.DEBUG);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("operation=API", "status=204");
        assertEventId();
    }

    @Test
    void restoresExistingMdcAndUsesDifferentIdsForConsecutiveRequests() throws Exception
    {
        MDC.put(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY, "outer-context");
        filter.doFilter(request, response, (input, output) -> assertCorrelated());
        String first = response.getHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER);
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isEqualTo("outer-context");

        var secondRequest = new MockHttpServletRequest("GET", "/api/auth/me");
        var secondResponse = new MockHttpServletResponse();
        filter.doFilter(secondRequest, secondResponse, (input, output) ->
            assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isNotEqualTo(first));

        assertThat(secondResponse.getHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER)).isNotEqualTo(first);
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isEqualTo("outer-context");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 503})
    void recordsDenialOrServerFailureWithoutChangingResponse(int status) throws Exception
    {
        request.setRequestURI("/api/auth/login");
        filter.doFilter(request, response, (input, output) -> response.setStatus(status));

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(status >= 500 ? Level.ERROR : Level.WARN);
        assertThat(event.getFormattedMessage()).contains("operation=LOGIN", "status=" + status);
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertEventId();
    }

    @Test
    void propagatedExceptionIsNotReportedAsHttpSuccess()
    {
        var failure = new IllegalStateException(SENSITIVE[4]);
        FilterChain chain = (input, output) ->
        {
            assertCorrelated();
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> filter.doFilter(request, response, chain)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
            .contains("event=HTTP_REQUEST_FAILED", "status=UNRESOLVED").doesNotContain("status=200");
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertEventId();
    }

    @Test
    void checkedServletAndIoExceptionsKeepExactIdentity()
    {
        var servletFailure = new ServletException(SENSITIVE[4]);
        assertSame(servletFailure, assertThrows(ServletException.class, () ->
            filter.doFilter(request, response, (input, output) ->
            {
                throw servletFailure;
            })));
        var ioFailure = new IOException(SENSITIVE[4]);
        assertSame(ioFailure, assertThrows(IOException.class, () ->
            filter.doFilter(request, response, (input, output) ->
            {
                throw ioFailure;
            })));
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void errorRedispatchReusesServerIdAndRestoresContext() throws Exception
    {
        filter.doFilter(request, response, (input, output) -> response.setStatus(503));
        String original = response.getHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER);
        response.reset();
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, SENSITIVE[0]);
        request.setRequestURI("/error");
        filter.doFilter(request, response, (input, output) ->
        {
            assertCorrelated();
            response.setStatus(503);
        });

        assertThat(response.getHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER)).isEqualTo(original);
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.getLast().getFormattedMessage()).contains("dispatch=ERROR", "status=503");
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertEventId();
    }

    @Test
    void nestedErrorDispatchDoesNotLeakOrReplaceOuterRequestId() throws Exception
    {
        filter.doFilter(request, response, (input, output) ->
        {
            String outerId = MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY);
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, SENSITIVE[0]);
            filter.doFilter(request, response, (nestedInput, nestedOutput) -> assertCorrelated());
            assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isEqualTo(outerId);
        });

        assertThat(appender.list).hasSize(2);
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertEventId();
    }

    @Test
    void unknownMethodAndAttributeCannotInjectLogContent() throws Exception
    {
        request.setMethod(SENSITIVE[5]);
        request.setAttribute(RequestDiagnosticsFilter.REQUEST_ID_ATTRIBUTE, SENSITIVE[5]);
        filter.doFilter(request, response, (input, output) -> assertCorrelated());

        assertThat(appender.list.getFirst().getFormattedMessage()).contains("method=OTHER");
        assertEventId();
    }

    @Test
    void asyncInitiationDoesNotClaimFinalStatusOrPropagation() throws Exception
    {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (input, output) -> request.startAsync());

        assertThat(appender.list.getFirst().getFormattedMessage())
            .contains("event=HTTP_ASYNC_STARTED", "status=UNRESOLVED");
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void registrationIsOuterServletFilterBeforeSecurityAndHandlesErrors() throws ServletException
    {
        var registration = new RequestDiagnosticsConfiguration().requestDiagnosticsFilterRegistration();
        assertThat(registration.getFilter()).isInstanceOf(RequestDiagnosticsFilter.class);
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE).isLessThan(-100);
        assertThat(registration.isAsyncSupported()).isTrue();
        var servletContext = new org.springframework.mock.web.MockServletContext();
        // Dispatcher mapping is asserted via the registration's servlet API contract, not a running server.
        var dynamic = org.mockito.Mockito.mock(jakarta.servlet.FilterRegistration.Dynamic.class);
        var context = org.mockito.Mockito.spy(servletContext);
        org.mockito.Mockito.doReturn(dynamic).when(context).addFilter(
            org.mockito.ArgumentMatchers.eq("requestDiagnosticsFilter"),
            org.mockito.ArgumentMatchers.any(jakarta.servlet.Filter.class));
        registration.onStartup(context);
        org.mockito.Mockito.verify(dynamic).addMappingForUrlPatterns(
            org.mockito.ArgumentMatchers.eq(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR)),
            org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq("/*"));
    }

    private void assertCorrelated()
    {
        Object id = request.getAttribute(RequestDiagnosticsFilter.REQUEST_ID_ATTRIBUTE);
        assertThat(id).isInstanceOf(UUID.class);
        assertThat(response.getHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER)).isEqualTo(id.toString());
        assertThat(MDC.get(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY)).isEqualTo(id.toString());
    }

    private void assertEventId()
    {
        for (ILoggingEvent event : appender.list)
        {
            assertThat(event.getMDCPropertyMap()).containsEntry(RequestDiagnosticsFilter.REQUEST_ID_MDC_KEY,
                response.getHeader(RequestDiagnosticsFilter.REQUEST_ID_HEADER));
        }
    }

    /** Snapshots MDC while the event remains within the correlated request. */
    private static final class SnapshotAppender extends ListAppender<ILoggingEvent>
    {
        @Override
        protected void append(ILoggingEvent event)
        {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
