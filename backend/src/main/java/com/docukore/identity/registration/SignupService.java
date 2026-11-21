package com.docukore.identity.registration;

import com.docukore.identity.DeploymentProperties;
import com.docukore.platform.provisioning.CabinetProvisioningException;
import com.docukore.platform.provisioning.CabinetProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static com.docukore.identity.registration.SignupResponse.Status;

@Service
public class SignupService
{
    /**
     * Logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SignupService.class);

    /** Repository for signup registration data. */
    private final SignupRepository registrations;
    /** Password encoder for secure password storage. */
    private final PasswordEncoder passwords;
    /** Deployment properties (local/hosted mode). */
    private final DeploymentProperties deployment;
    /** Signup workflow properties. */
    private final SignupProperties properties;
    /** Verification token generator. */
    private final VerificationTokens tokens;
    /** Email transport for verification mails. */
    private final VerificationMailTransport mail;
    /** Cabinet provisioning service. */
    private final CabinetProvisioningService provisioner;
    /** Transaction template for platform operations. */
    private final TransactionTemplate transactions;
    /** Clock for timestamp generation. */
    private final Clock clock;

    public SignupService(SignupRepository registrations, PasswordEncoder passwords,
            DeploymentProperties deployment, SignupProperties properties, VerificationTokens tokens,
            VerificationMailTransport mail, CabinetProvisioningService provisioner,
            @Qualifier("platformTransactionManager") PlatformTransactionManager transactionManager, Clock clock)
    {
        this.registrations = registrations;
        this.passwords = passwords;
        this.deployment = deployment;
        this.properties = properties;
        this.tokens = tokens;
        this.mail = mail;
        this.provisioner = provisioner;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public SignupResponse register(SignupRequest request)
    {
        // Preserve the null-request failure previously triggered by the initial logging argument.
        Objects.requireNonNull(request);
        LOG.debug("event=SIGNUP_REGISTRATION_ATTEMPT deployment={}", deployment.mode());
        SignupRepository.CanonicalSignup canonical = canonical(request);
        SignupRepository.Registration registration;
        try
        {
            registration = transactions.execute(status ->
            {
                registrations.lockRequest(canonical.key());
                var previous = registrations.findByKey(canonical.key());
                if (previous.isPresent())
                {
                    LOG.debug("event=SIGNUP_REGISTRATION_REUSE_SELECTED deployment={}", deployment.mode());
                    var existing = registrations.lockRegistration(previous.get().id()).orElseThrow(Conflict::new);
                    if (!matches(existing, canonical)
                        || !passwords.matches(request.password(), existing.passwordHash()))
                    {
                        LOG.warn("event=SIGNUP_REGISTRATION_CONFLICT deployment={} outcome=DATA_MISMATCH",
                                deployment.mode());
                        throw new Conflict();
                    }
                    if (!existing.pendingHosted() && !existing.eligible(canonical.mode()))
                    {
                        LOG.warn("event=SIGNUP_REGISTRATION_CONFLICT deployment={} outcome=INELIGIBLE",
                                deployment.mode());
                        throw new Conflict();
                    }
                    return existing;
                }
                if (registrations.identityExists(canonical.username(), canonical.email()))
                {
                    LOG.warn("event=SIGNUP_REGISTRATION_CONFLICT deployment={} outcome=IDENTITY_EXISTS",
                            deployment.mode());
                    throw new Conflict();
                }
                LOG.debug("event=SIGNUP_REGISTRATION_INSERT deployment={}", deployment.mode());
                return registrations.insert(canonical, passwords.encode(request.password()), clock.instant());
            });
        } catch (DuplicateKeyException error)
        {
            // Concurrent different-key identity collisions never attach to another registration.
            LOG.warn("event=SIGNUP_REGISTRATION_CONFLICT deployment={} outcome=IDENTITY_COLLISION",
                    deployment.mode());
            throw new Conflict();
        }
        LOG.info("event=SIGNUP_REGISTRATION_COMMITTED deployment={}", deployment.mode());
        if (registration.pendingHosted())
        {
            LOG.debug("event=SIGNUP_VERIFICATION_PENDING deployment={}", deployment.mode());
            deliverIfAllowed(registration.id());
            return pendingResponse(registration);
        }
        return provision(registration);
    }

    public SignupResponse verifyEmail(String proof)
    {
        if (deployment.local())
        {
            throw new InvalidProof();
        }
        String hash;
        try
        {
            hash = tokens.hash(proof);
        } catch (IllegalArgumentException error)
        {
            throw new InvalidProof();
        }
        var registration = transactions.execute(status ->
        {
            UUID id = registrations.registrationForToken(hash).orElseThrow(InvalidProof::new);
            var pending = registrations.lockRegistration(id).orElseThrow(InvalidProof::new);
            Instant now = clock.instant();
            if (!pending.pendingHosted() || !registrations.consumeToken(id, hash, now))
            {
                throw new InvalidProof();
            }
            registrations.invalidateTokens(id, now);
            registrations.activate(pending.accountId(), now);
            return registrations.lockRegistration(id).orElseThrow(InvalidProof::new);
        });
        // Proof/account activation is committed before permanent allocation or external provisioning.
        LOG.info("event=SIGNUP_ACTIVATION_COMMITTED deployment={} outcome=PROOF_ACCEPTED", deployment.mode());
        return provision(registration);
    }

    public void resendVerification(String email)
    {
        if (deployment.local())
        {
            return;
        }
        String normalized;
        try
        {
            normalized = email(email, true);
        } catch (IllegalArgumentException error)
        {
            return;
        }
        UUID id = transactions.execute(status -> registrations.findPendingByEmail(normalized)
                .filter(SignupRepository.Registration::pendingHosted)
                .map(SignupRepository.Registration::id).orElse(null));
        if (id != null)
        {
            deliverIfAllowed(id);
        }
        // Email alone is a delivery request, never authority to retry allocation/provisioning.
    }

    public SignupResponse retry(UUID registrationId, UUID authenticatedAccountId)
    {
        if (registrationId == null || authenticatedAccountId == null)
        {
            throw new RetryDenied();
        }
        var registration = transactions.execute(status ->
        {
            var original = registrations.lockRegistration(registrationId).orElseThrow(RetryDenied::new);
            if (!authenticatedAccountId.equals(original.accountId()) || !original.eligible(mode()))
            {
                throw new RetryDenied();
            }
            return original;
        });
        return provision(registration);
    }

    private SignupResponse provision(SignupRepository.Registration registration)
    {
        try
        {
            var result = provisioner.provision(registration.id(), registration.accountId());
            if ("ACTIVE".equals(result.status().name()))
            {
                LOG.info("event=SIGNUP_PROVISIONING_OUTCOME deployment={} outcome=ACTIVE reason=COMPLETED",
                        deployment.mode());
            } else
            {
                LOG.debug("event=SIGNUP_PROVISIONING_OUTCOME deployment={} outcome=PENDING reason=IN_PROGRESS",
                        deployment.mode());
            }
            return new SignupResponse(registration.id(), result.cabinetId(),
                    "ACTIVE".equals(result.status().name()) ? Status.ACTIVE : Status.PROVISIONING_PENDING);
        } catch (CabinetProvisioningException error)
        {
            // Missing configuration and bounded failures preserve the original committed workflow.
            var current = transactions.execute(status -> registrations.lockRegistration(registration.id())
                    .orElseThrow(RetryDenied::new));
            // Provisioning owns the actionable failure; this is only the signup workflow summary.
            LOG.debug("event=SIGNUP_PROVISIONING_OUTCOME deployment={} outcome=PENDING reason=PROVISIONING_DEFERRED",
                    deployment.mode());
            return new SignupResponse(current.id(), current.cabinetId(), Status.PROVISIONING_PENDING);
        }
    }

    private void deliverIfAllowed(UUID registrationId)
    {
        DeliveryAttempt attempt = transactions.execute(status ->
        {
            var registration = registrations.lockRegistration(registrationId).orElseThrow(InvalidProof::new);
            if (!registration.pendingHosted())
            {
                return null;
            }
            Instant now = clock.instant();
            var previous = registrations.lastDelivery(registrationId);
            if (previous.isPresent() && previous.get().lastAttemptAt() != null
                    && now.isBefore(previous.get().lastAttemptAt().plus(properties.resendCooldown())))
            {
                return null;
            }
            // Rotation and the durable attempt precede transport, even if transport is disabled/fails.
            registrations.invalidateTokens(registrationId, now);
            var issued = tokens.issue();
            UUID tokenId = registrations.createToken(registrationId, issued.hash(), now,
                    now.plus(properties.verificationTtl()));
            return new DeliveryAttempt(tokenId, registration.email(), issued.value());
        });
        if (attempt == null)
        {
            LOG.debug("event=SIGNUP_VERIFICATION_DELIVERY deployment={} outcome=SKIPPED", deployment.mode());
            return;
        }
        VerificationMailTransport.DeliveryResult result;
        if (properties.appBaseUri() == null)
        {
            result = VerificationMailTransport.DeliveryResult.NOT_CONFIGURED;
        } else
        {
            try
            {
                result = mail.send(new VerificationMailTransport.VerificationMail(attempt.tokenId(),
                        attempt.email(), properties.verificationLink(attempt.token())));
                if (result == null)
                {
                    result = VerificationMailTransport.DeliveryResult.DELIVERY_UNAVAILABLE;
                }
            } catch (RuntimeException error)
            {
                result = VerificationMailTransport.DeliveryResult.DELIVERY_UNAVAILABLE;
            }
        }
        var outcome = result;
        transactions.executeWithoutResult(status -> registrations.recordDelivery(attempt.tokenId(), outcome));
        if (outcome == VerificationMailTransport.DeliveryResult.DELIVERED)
        {
            LOG.info("event=SIGNUP_VERIFICATION_DELIVERY deployment={} outcome={}", deployment.mode(), outcome);
        } else
        {
            LOG.warn("event=SIGNUP_VERIFICATION_DELIVERY deployment={} outcome={}", deployment.mode(), outcome);
        }
    }

    private SignupResponse pendingResponse(SignupRepository.Registration registration)
    {
        var delivery = transactions.execute(status -> registrations.lastDelivery(registration.id()));
        boolean failed = delivery.isPresent() && delivery.get().error() != null;
        return new SignupResponse(registration.id(), null,
                failed ? Status.DELIVERY_FAILED : Status.PENDING_EMAIL_VERIFICATION);
    }

    private SignupRepository.CanonicalSignup canonical(SignupRequest request)
    {
        if (request == null || request.idempotencyKey() == null)
        {
            throw new IllegalArgumentException("Invalid signup input");
        }
        String email = email(request.email(), !deployment.local());
        String username = deployment.local() ? normalize(request.username()) : email;
        if (deployment.local() && !username.matches("[a-z0-9][a-z0-9._-]{2,63}"))
        {
            throw new IllegalArgumentException("Invalid signup input");
        }
        if (!deployment.local() && request.username() != null && !request.username().isBlank()
                && !username.equals(normalize(request.username())))
        {
            throw new IllegalArgumentException("Invalid signup input");
        }
        String password = request.password();
        if (password == null || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
        {
            throw new IllegalArgumentException("Invalid signup input");
        }
        return new SignupRepository.CanonicalSignup(username, email, name(request.fullName()),
                name(request.cabinetName()), mode(), request.idempotencyKey());
    }

    private static boolean matches(SignupRepository.Registration registration, SignupRepository.CanonicalSignup signup)
    {
        return registration.username().equals(signup.username())
                && Objects.equals(registration.email(), signup.email())
                && registration.fullName().equals(signup.fullName())
                && registration.cabinetName().equals(signup.cabinetName())
                && registration.mode().equals(signup.mode());
    }

    private String mode()
    {
        return deployment.local() ? "local" : "hosted";
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String email(String value, boolean required)
    {
        String normalized = normalize(value);
        if (normalized.isEmpty() && !required)
        {
            return null;
        }
        if (normalized.length() > 255 || !normalized.matches(
                "[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}"))
        {
            throw new IllegalArgumentException("Invalid signup input");
        }
        return normalized;
    }

    private static String name(String value)
    {
        String result = value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (result.isBlank() || result.length() > 255 || result.codePoints().anyMatch(Character::isISOControl))
        {
            throw new IllegalArgumentException("Invalid signup input");
        }
        return result;
    }

    private record DeliveryAttempt(UUID tokenId, String email, String token)
    {
        @Override
        public String toString()
        {
            return "DeliveryAttempt[redacted]";
        }
    }

    public static final class Conflict extends RuntimeException
    {
        public Conflict()
        {
            super("Registration request conflicts with an existing request");
        }
    }

    public static final class InvalidProof extends RuntimeException
    {
        public InvalidProof()
        {
            super("Verification proof is invalid or expired");
        }
    }

    public static final class RetryDenied extends RuntimeException
    {
        public RetryDenied()
        {
            super("Registration retry is not permitted");
        }
    }
}