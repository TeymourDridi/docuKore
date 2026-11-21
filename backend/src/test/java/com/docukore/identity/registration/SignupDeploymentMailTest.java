package com.docukore.identity.registration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.docukore.identity.DeploymentProperties;
import com.docukore.platform.provisioning.CabinetProvisioningException;
import com.docukore.platform.provisioning.CabinetProvisioningService;
import com.docukore.platform.provisioning.ProvisioningResult;
import com.docukore.platform.provisioning.ProvisioningStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SignupDeploymentMailTest
{
    /** Mock registration persistence for deployment-specific signup scenarios. */
    private final SignupRepository repository = mock(SignupRepository.class);
    /** Mock encoder returning the fictional registration password hash. */
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    /** Mock transport used to verify delivery and LOCAL network isolation. */
    private final VerificationMailTransport mail = mock(VerificationMailTransport.class);
    /** Mock provisioner used to verify activation boundaries. */
    private final CabinetProvisioningService provisioner = mock(CabinetProvisioningService.class);
    /** Mock transaction manager for signup operations without a database. */
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    /** Fixed instant for registration and verification-token timestamps. */
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    /** Synthetic registration identifier. */
    private final UUID id = UUID.randomUUID();
    /** Synthetic account identifier, distinct from the registration identifier. */
    private final UUID accountId = UUID.randomUUID();
    /** Synthetic idempotency key supplied by the signup request. */
    private final UUID key = UUID.randomUUID();
    /** Class-local logger isolated from ancestor appenders during each test. */
    private final Logger logger = (Logger) LoggerFactory.getLogger(SignupService.class);
    /** Safe synthetic request correlation value, unrelated to registration identifiers. */
    private final String requestId = UUID.randomUUID().toString();
    /** Captured events with formatted messages and MDC frozen at emission time. */
    private final ListAppender<ILoggingEvent> events = new ListAppender<>()
    {
        @Override
        protected void append(ILoggingEvent event)
        {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    /** Logger level restored after capture, including an inherited null level. */
    private Level previousLevel;
    /** Logger additivity restored after capture. */
    private boolean previousAdditivity;
    /** Previous request correlation restored without changing unrelated MDC keys. */
    private String previousRequestId;

    @BeforeEach
    void captureSignupEvents()
    {
        previousLevel = logger.getLevel();
        previousAdditivity = logger.isAdditive();
        previousRequestId = MDC.get("requestId");
        MDC.put("requestId", requestId);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        events.setContext(logger.getLoggerContext());
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void restoreLoggingState()
    {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditivity);
        if (previousRequestId == null)
        {
            MDC.remove("requestId");
        } else
        {
            MDC.put("requestId", previousRequestId);
        }
    }

    @Test
    void localUsernameRegistrationProvisionsWithoutEmailOrNetwork()
    {
        var registration = registration("local", "ACTIVE", now);
        prepare(registration);
        UUID cabinetId = UUID.randomUUID();
        when(provisioner.provision(id, accountId))
                .thenReturn(new ProvisioningResult(cabinetId, ProvisioningStatus.ACTIVE));

        var response = service(DeploymentProperties.Mode.LOCAL, null).register(
                new SignupRequest("fictional.doctor", null, "Fictional Doctor", "Fictional Practice",
                        "fictional-password", key));

        assertThat(response.status()).isEqualTo(SignupResponse.Status.ACTIVE);
        assertThat(response.cabinetId()).isEqualTo(cabinetId);
        verifyNoInteractions(mail);
        verify(repository, never()).createToken(any(), any(), any(), any());
        assertThatThrownBy(() -> service(DeploymentProperties.Mode.LOCAL, null).verifyEmail("not-used"))
                .isInstanceOf(SignupService.InvalidProof.class);
        assertEvent(Level.DEBUG, "event=SIGNUP_REGISTRATION_ATTEMPT deployment=LOCAL");
        assertEvent(Level.INFO, "event=SIGNUP_REGISTRATION_COMMITTED deployment=LOCAL");
        assertEvent(Level.INFO,
                "event=SIGNUP_PROVISIONING_OUTCOME deployment=LOCAL outcome=ACTIVE reason=COMPLETED");
        assertThat(events.list).noneMatch(event -> event.getFormattedMessage()
                .contains("event=SIGNUP_VERIFICATION_DELIVERY"));
        assertSafeEvents(cabinetId.toString(), "not-used");
    }

    @Test
    void hostedRequiresVerificationAndSendsActualRecipientWithTrustedUrl()
    {
        var registration = registration("hosted", "PENDING_ACTIVATION", null);
        prepare(registration);
        when(repository.lockRegistration(id)).thenReturn(Optional.of(registration));
        when(repository.lastDelivery(id)).thenReturn(Optional.empty());
        UUID tokenId = UUID.randomUUID();
        when(repository.createToken(eq(id), any(), eq(now), any())).thenReturn(tokenId);
        when(mail.send(any())).thenReturn(VerificationMailTransport.DeliveryResult.DELIVERED);

        var response = service(DeploymentProperties.Mode.HOSTED, URI.create("https://app.example.test"))
                .register(new SignupRequest(null, "recipient@example.test", "Fictional Doctor", "Fictional Practice",
                        "fictional-password", key));

        assertThat(response.status()).isEqualTo(SignupResponse.Status.PENDING_EMAIL_VERIFICATION);
        assertThat(response.cabinetId()).isNull();
        verifyNoInteractions(provisioner);
        var captured = ArgumentCaptor.forClass(VerificationMailTransport.VerificationMail.class);
        verify(mail).send(captured.capture());
        assertThat(captured.getValue().recipient()).isEqualTo("recipient@example.test");
        assertThat(captured.getValue().verificationLink().getHost()).isEqualTo("app.example.test");
        assertThat(captured.getValue().verificationLink().getPath()).isEqualTo("/verify-email");
        assertThat(captured.getValue().verificationLink().getFragment()).matches("token=[A-Za-z0-9_-]{43}");
        verify(repository).recordDelivery(tokenId, VerificationMailTransport.DeliveryResult.DELIVERED);
        assertEvent(Level.DEBUG, "event=SIGNUP_REGISTRATION_ATTEMPT deployment=HOSTED");
        assertEvent(Level.INFO, "event=SIGNUP_REGISTRATION_COMMITTED deployment=HOSTED");
        assertEvent(Level.INFO, "event=SIGNUP_VERIFICATION_DELIVERY deployment=HOSTED outcome=DELIVERED");
        String emittedToken = captured.getValue().verificationLink().getFragment().substring("token=".length());
        assertSafeEvents(tokenId.toString(), emittedToken, captured.getValue().verificationLink().toString());
    }

    @Test
    void hostedMissingApplicationBaseRecordsDeliveryFailureWithoutMailOrProvisioning()
    {
        var registration = registration("hosted", "PENDING_ACTIVATION", null);
        prepare(registration);
        when(repository.lockRegistration(id)).thenReturn(Optional.of(registration));
        when(repository.lastDelivery(id)).thenReturn(Optional.empty(),
                Optional.of(new SignupRepository.DeliveryState(now, "NOT_CONFIGURED")));
        UUID tokenId = UUID.randomUUID();
        when(repository.createToken(eq(id), any(), eq(now), any())).thenReturn(tokenId);
        var response = service(DeploymentProperties.Mode.HOSTED, null).register(
                new SignupRequest(null, "recipient@example.test", "Fictional Doctor", "Fictional Practice",
                        "fictional-password", key));
        assertThat(response.status()).isEqualTo(SignupResponse.Status.DELIVERY_FAILED);
        verify(repository).recordDelivery(tokenId, VerificationMailTransport.DeliveryResult.NOT_CONFIGURED);
        verifyNoInteractions(mail, provisioner);
        assertEvent(Level.DEBUG, "event=SIGNUP_REGISTRATION_ATTEMPT deployment=HOSTED");
        assertEvent(Level.INFO, "event=SIGNUP_REGISTRATION_COMMITTED deployment=HOSTED");
        assertEvent(Level.WARN, "event=SIGNUP_VERIFICATION_DELIVERY deployment=HOSTED outcome=NOT_CONFIGURED");
        assertSafeEvents(tokenId.toString());
    }

    @Test
    void duplicateIdentityPreservesConflictWithoutLoggingPersistenceDetails()
    {
        var registration = registration("local", "ACTIVE", now);
        prepare(registration);
        String sensitiveMessage = "fictional-provider duplicate recipient@example.test fictional-hash "
                + "fictional-password fictional-token " + id + " " + accountId + " " + key;
        when(repository.insert(any(), eq("fictional-hash"), eq(now)))
                .thenThrow(new DuplicateKeyException(sensitiveMessage));

        assertThatThrownBy(() -> service(DeploymentProperties.Mode.LOCAL, null).register(
                new SignupRequest("fictional.doctor", null, "Fictional Doctor", "Fictional Practice",
                        "fictional-password", key)))
                .isInstanceOf(SignupService.Conflict.class);

        verifyNoInteractions(mail, provisioner);
        verify(repository, never()).createToken(any(), any(), any(), any());
        assertEvent(Level.DEBUG, "event=SIGNUP_REGISTRATION_ATTEMPT deployment=LOCAL");
        assertEvent(Level.WARN,
                "event=SIGNUP_REGISTRATION_CONFLICT deployment=LOCAL outcome=IDENTITY_COLLISION");
        assertThat(events.list).noneMatch(event -> event.getFormattedMessage()
                .contains("event=SIGNUP_REGISTRATION_COMMITTED"));
        assertSafeEvents(sensitiveMessage);
    }

    @Test
    void provisioningExceptionPreservesPendingResponseWithoutLoggingArbitraryCode()
    {
        var registration = registration("local", "PENDING_ACTIVATION", now);
        prepare(registration);
        when(repository.lockRegistration(id)).thenReturn(Optional.of(registration));
        String sensitiveCode = "fictional-provider-token";
        when(provisioner.provision(id, accountId)).thenThrow(new CabinetProvisioningException(sensitiveCode));

        var response = service(DeploymentProperties.Mode.LOCAL, null).register(
                new SignupRequest("fictional.doctor", null, "Fictional Doctor", "Fictional Practice",
                        "fictional-password", key));

        assertThat(response).isEqualTo(new SignupResponse(id, null, SignupResponse.Status.PROVISIONING_PENDING));
        verify(provisioner).provision(id, accountId);
        verify(repository).lockRegistration(id);
        verifyNoInteractions(mail);
        verify(repository, never()).createToken(any(), any(), any(), any());
        assertEvent(Level.DEBUG, "event=SIGNUP_REGISTRATION_ATTEMPT deployment=LOCAL");
        assertEvent(Level.INFO, "event=SIGNUP_REGISTRATION_COMMITTED deployment=LOCAL");
        assertEvent(Level.DEBUG,
                "event=SIGNUP_PROVISIONING_OUTCOME deployment=LOCAL outcome=PENDING reason=PROVISIONING_DEFERRED");
        assertSafeEvents(sensitiveCode);
    }

    @Test
    void nullRequestStillThrowsNullPointerExceptionWithoutDependencyInteractions()
    {
        assertThatThrownBy(() -> service(DeploymentProperties.Mode.LOCAL, null).register(null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(repository, passwords, mail, provisioner, transactionManager);
        assertThat(events.list).isEmpty();
        assertSafeEvents();
    }

    private void assertEvent(Level level, String message)
    {
        assertThat(events.list).anySatisfy(event ->
        {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).isEqualTo(message);
        });
    }

    private void assertSafeEvents(String... additionalSensitiveValues)
    {
        List<String> sensitiveValues = new ArrayList<>(List.of(
                "fictional.doctor", "recipient@example.test", "Fictional Doctor", "Fictional Practice",
                "fictional-password", "fictional-hash", id.toString(), accountId.toString(), key.toString(),
                "fictional-token", "fictional-provider", "app.example.test", "https://app.example.test",
                "DuplicateKeyException", "CabinetProvisioningException", "InvalidProof", "token="));
        sensitiveValues.addAll(Arrays.asList(additionalSensitiveValues));
        String[] forbidden = sensitiveValues.toArray(String[]::new);
        assertThat(MDC.get("requestId")).isEqualTo(requestId);
        for (ILoggingEvent event : events.list)
        {
            assertThat(event.getLoggerName()).isEqualTo(SignupService.class.getName());
            assertThat(event.getMessage()).doesNotContain(forbidden);
            assertThat(event.getFormattedMessage()).doesNotContain(forbidden);
            if (event.getArgumentArray() != null)
            {
                for (Object argument : event.getArgumentArray())
                {
                    assertThat(String.valueOf(argument)).doesNotContain(forbidden);
                }
            }
            assertThat(event.getMDCPropertyMap()).containsExactlyEntriesOf(Map.of("requestId", requestId));
            assertThat(event.getMDCPropertyMap().toString()).doesNotContain(forbidden);
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    private void prepare(SignupRepository.Registration registration)
    {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findByKey(key)).thenReturn(Optional.empty());
        when(passwords.encode("fictional-password")).thenReturn("fictional-hash");
        when(repository.insert(any(), eq("fictional-hash"), eq(now))).thenReturn(registration);
    }

    private SignupRepository.Registration registration(String mode, String status, Instant activatedAt)
    {
        return new SignupRepository.Registration(id, accountId, key, "fictional.doctor", "recipient@example.test",
                "Fictional Doctor", "Fictional Practice", "fictional-hash", status, mode, activatedAt, null, null);
    }

    private SignupService service(DeploymentProperties.Mode mode, URI base)
    {
        return new SignupService(repository, passwords, new DeploymentProperties(mode),
                new SignupProperties(base, null, null), new VerificationTokens(), mail, provisioner,
                transactionManager, Clock.fixed(now, ZoneOffset.UTC));
    }
}
