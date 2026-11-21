package com.docukore.identity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Database-free session revocation and logging privacy checks with real servlet and security context state. */
class AccountSessionFilterTest
{
    /** Synthetic request, account and failure values forbidden in diagnostic events. */
    private static final String[] SENSITIVE = {
        "synthetic-session@example.invalid", "Synthetic Session Name", "synthetic-session-email@example.invalid",
        "synthetic-session-password", "/synthetic-session-uri", "synthetic-session-query",
        "synthetic-session-exception", "synthetic-session-cause", "55555555-5555-5555-5555-555555555555"
    };
    /** Safe correlation value owned by the request boundary, not by this filter. */
    private static final String REQUEST_ID = "66666666-6666-6666-6666-666666666666";
    /** Mocked repository prevents all database access. */
    private final AccountRepository accounts = mock(AccountRepository.class);
    /** Filter with the real allowlisted deployment configuration. */
    private final AccountSessionFilter filter = new AccountSessionFilter(accounts,
            new DeploymentProperties(DeploymentProperties.Mode.LOCAL));
    /** Real servlet request with fictional sensitive path and query values. */
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", SENSITIVE[4]);
    /** Real servlet response for business contract assertions. */
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    /** Class-local logger under capture. */
    private final Logger logger = (Logger) LoggerFactory.getLogger(AccountSessionFilter.class);
    /** Eager in-memory diagnostic capture. */
    private final SnapshotAppender appender = new SnapshotAppender();
    /** Prior explicit level, possibly inherited. */
    private Level previousLevel;
    /** Prior propagation to parent appenders. */
    private boolean previousAdditive;
    /** Security context to restore after each test. */
    private SecurityContext previousContext;
    /** Correlation value to restore after each test. */
    private String previousRequestId;

    @BeforeEach
    void captureDiagnostics()
    {
        request.setQueryString(SENSITIVE[5]);
        previousContext = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
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
    void restoreDiagnosticsAndContext()
    {
        try
        {
            assertPrivateEvents();
        } finally
        {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            SecurityContextHolder.setContext(previousContext);
            if (previousRequestId == null)
            {
                MDC.remove("requestId");
            } else
            {
                MDC.put("requestId", previousRequestId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revokedOrMissingAccountLogsSafeWarningAndKeepsUnauthenticatedResponse(boolean missing) throws Exception
    {
        Authentication authentication = authentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var session = request.getSession();
        var account = account("INACTIVE");
        when(accounts.find(SENSITIVE[0])).thenReturn(missing ? Optional.empty() : Optional.of(account));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"Authentication failed.\"}");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(request.getSession(false)).isNull();
        assertThat(session).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(accounts).find(SENSITIVE[0]);
        verifyNoMoreInteractions(accounts);
        verifyNoInteractions(chain);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getMessage()).isEqualTo("AUTH_SESSION_REVOKED deployment={}");
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.getFirst().getArgumentArray()).containsExactly(DeploymentProperties.Mode.LOCAL);
    }

    @Test
    void activeAccountPassesUnchangedWithoutRoutineInfo() throws Exception
    {
        Authentication authentication = authentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(accounts.find(SENSITIVE[0])).thenReturn(Optional.of(account("ACTIVE")));
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            assertSame(request, chainRequest);
            assertSame(response, chainResponse);
            assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
            response.setStatus(204);
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(204);
        assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
        verify(accounts).find(SENSITIVE[0]);
        verifyNoMoreInteractions(accounts);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void downstreamFailureIsPropagatedExactlyWithoutLoggingThrowable()
    {
        Authentication authentication = authentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(accounts.find(SENSITIVE[0])).thenReturn(Optional.of(account("ACTIVE")));
        var failure = new IllegalStateException(SENSITIVE[6], new IllegalArgumentException(SENSITIVE[7]));
        FilterChain chain = (chainRequest, chainResponse) ->
        {
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, chain)));

        assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
        verify(accounts).find(SENSITIVE[0]);
        verifyNoMoreInteractions(accounts);
        assertThat(appender.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrAnonymousAuthenticationDoesNotReadAccounts(boolean anonymous) throws Exception
    {
        Authentication authentication = anonymous ? new AnonymousAuthenticationToken("synthetic-anonymous-key",
                SENSITIVE[0], List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))) : null;
        SecurityContextHolder.getContext().setAuthentication(authentication);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(accounts);
        assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
        assertThat(appender.list).isEmpty();
    }

    private static Authentication authentication()
    {
        return UsernamePasswordAuthenticationToken.authenticated(SENSITIVE[0], SENSITIVE[3], List.of());
    }

    private static AccountRepository.Account account(String status)
    {
        return new AccountRepository.Account(UUID.fromString(SENSITIVE[8]), SENSITIVE[0], SENSITIVE[1], SENSITIVE[2],
                SENSITIVE[3], status, "local", Instant.EPOCH);
    }

    private void assertPrivateEvents()
    {
        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
        for (ILoggingEvent event : appender.list)
        {
            assertThat(event.getLoggerName()).isEqualTo(AccountSessionFilter.class.getName());
            assertThat(event.getMessage()).doesNotContain(SENSITIVE);
            assertThat(event.getFormattedMessage()).doesNotContain(SENSITIVE);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).isNullOrEmpty();
            if (event.getArgumentArray() != null)
            {
                assertThat(event.getArgumentArray()).allSatisfy(argument ->
                {
                    assertThat(argument).isIn(DeploymentProperties.Mode.LOCAL, DeploymentProperties.Mode.HOSTED);
                    assertThat(String.valueOf(argument)).doesNotContain(SENSITIVE);
                });
            }
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", REQUEST_ID);
            event.getMDCPropertyMap().forEach((key, value) ->
            {
                assertThat(key).doesNotContain(SENSITIVE);
                assertThat(value).doesNotContain(SENSITIVE);
            });
        }
    }

    /** Snapshots MDC and formatting while the event is still owned by the request thread. */
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
