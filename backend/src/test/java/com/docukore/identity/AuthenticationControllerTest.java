package com.docukore.identity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Constructor-only endpoint and logging privacy checks without a Spring context or database. */
class AuthenticationControllerTest
{
    /** Fictional account and request data forbidden in diagnostics. */
    private static final String[] SENSITIVE = {
        "synthetic.login@example.invalid", "Synthetic Display Name", "synthetic-password-canary",
        "synthetic-email@example.invalid", "synthetic-exception-canary", "synthetic-cause-canary",
        "/synthetic-request-uri", "synthetic-query-canary", "33333333-3333-3333-3333-333333333333"
    };
    /** Safe opaque correlation value supplied by the owning request boundary in production. */
    private static final String REQUEST_ID = "44444444-4444-4444-4444-444444444444";
    /** Class-local logger captured without changing root logging. */
    private final Logger logger = (Logger) LoggerFactory.getLogger(AuthenticationController.class);
    /** Capture snapshots MDC before it can change on the test thread. */
    private final SnapshotAppender appender = new SnapshotAppender();
    /** Original explicit logger level, including inherited null. */
    private Level previousLevel;
    /** Original parent-appender delegation. */
    private boolean previousAdditive;
    /** Correlation value to restore after each test. */
    private String previousRequestId;

    @BeforeEach
    void captureDiagnostics()
    {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        previousRequestId = MDC.get("requestId");
        MDC.put("requestId", REQUEST_ID);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
    }

    @AfterEach
    void restoreDiagnostics()
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
    void publicSetupStatusDistinguishesHostedFromInitializedLocalRegistration()
    {
        var setup = mock(LocalSetupService.class);
        var accounts = mock(AccountRepository.class);
        when(setup.available()).thenReturn(false);

        var local = new AuthenticationController(setup, accounts,
                new DeploymentProperties(DeploymentProperties.Mode.LOCAL));
        var hosted = new AuthenticationController(setup, accounts,
                new DeploymentProperties(DeploymentProperties.Mode.HOSTED));

        assertThat(local.setupStatus().available()).isFalse();
        assertThat(local.setupStatus().deploymentMode()).isEqualTo(DeploymentProperties.Mode.LOCAL);
        assertThat(hosted.setupStatus().available()).isFalse();
        assertThat(hosted.setupStatus().deploymentMode()).isEqualTo(DeploymentProperties.Mode.HOSTED);
        assertThat(appender.list).allSatisfy(event ->
        {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getMessage()).isEqualTo("AUTH_SETUP_UNAVAILABLE deployment={}");
        });
    }

    @Test
    void availablePollingAndCurrentAccountStayDebugAndPreserveResponses()
    {
        var setup = mock(LocalSetupService.class);
        var accounts = mock(AccountRepository.class);
        var controller = new AuthenticationController(setup, accounts,
                new DeploymentProperties(DeploymentProperties.Mode.LOCAL));
        var account = new AccountRepository.Account(UUID.fromString(SENSITIVE[8]), SENSITIVE[0], SENSITIVE[1],
                SENSITIVE[3], SENSITIVE[2], "ACTIVE", "local", Instant.EPOCH);
        when(setup.available()).thenReturn(true);
        when(accounts.find(SENSITIVE[0])).thenReturn(Optional.of(account));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(SENSITIVE[0], SENSITIVE[2],
                java.util.List.of());

        assertThat(controller.setupStatus()).isEqualTo(
                new AuthenticationController.SetupStatus(true, DeploymentProperties.Mode.LOCAL));
        assertThat(controller.current(authentication)).isEqualTo(
                new AuthenticationController.AccountResponse(account.id(), SENSITIVE[0], SENSITIVE[1], SENSITIVE[3]));

        verify(setup).available();
        verify(accounts).find(SENSITIVE[0]);
        verifyNoMoreInteractions(setup, accounts);
        assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                .containsExactly("AUTH_SETUP_AVAILABLE deployment={}", "AUTH_CURRENT_ACCOUNT");
        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG, Level.DEBUG);
    }

    @Test
    void successfulSetupLogsCompletedTransitionWithoutRequestPayload()
    {
        var setup = mock(LocalSetupService.class);
        var accounts = mock(AccountRepository.class);
        var controller = new AuthenticationController(setup, accounts,
                new DeploymentProperties(DeploymentProperties.Mode.LOCAL));
        var request = new LocalSetupService.SetupRequest(SENSITIVE[0], SENSITIVE[1], SENSITIVE[2]);

        controller.initialize(request);

        verify(setup).initialize(request);
        verifyNoMoreInteractions(setup, accounts);
        assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                .containsExactly("AUTH_SETUP_STARTED deployment={}", "AUTH_SETUP_COMPLETED deployment={}");
        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG, Level.INFO);
    }

    @Test
    void failedSetupPreservesExactExceptionAndDoesNotLogCompletionOrThrowable()
    {
        var setup = mock(LocalSetupService.class);
        var accounts = mock(AccountRepository.class);
        var controller = new AuthenticationController(setup, accounts,
                new DeploymentProperties(DeploymentProperties.Mode.LOCAL));
        var request = new LocalSetupService.SetupRequest(SENSITIVE[0], SENSITIVE[1], SENSITIVE[2]);
        var failure = new IllegalArgumentException(SENSITIVE[4], new IllegalStateException(SENSITIVE[5]));
        doThrow(failure).when(setup).initialize(request);

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> controller.initialize(request)));

        verify(setup).initialize(request);
        verifyNoMoreInteractions(setup, accounts);
        assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                .containsExactly("AUTH_SETUP_STARTED deployment={}");
        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG);
    }

    private void assertPrivateEvents()
    {
        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
        for (ILoggingEvent event : appender.list)
        {
            assertThat(event.getLoggerName()).isEqualTo(AuthenticationController.class.getName());
            assertThat(event.getMessage()).doesNotContain(SENSITIVE);
            assertThat(event.getFormattedMessage()).doesNotContain(SENSITIVE);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).isNullOrEmpty();
            Object[] arguments = event.getArgumentArray();
            if (arguments != null)
            {
                assertThat(arguments).allSatisfy(argument ->
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

    /** In-memory event capture that eagerly freezes formatted messages and MDC, including failure paths. */
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
