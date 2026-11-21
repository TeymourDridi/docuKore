package com.docukore.identity.registration;

import com.docukore.identity.DeploymentProperties;
import com.docukore.platform.provisioning.CabinetProvisioningService;
import com.docukore.platform.provisioning.ProvisioningResult;
import com.docukore.platform.provisioning.ProvisioningStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
