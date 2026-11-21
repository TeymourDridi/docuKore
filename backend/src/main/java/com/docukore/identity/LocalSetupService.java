package com.docukore.identity;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class LocalSetupService
{

    /** Deployment configuration (local vs hosted). */
    private final DeploymentProperties deployment;

    /** Account repository for setup operations. */
    private final AccountRepository accounts;

    /** Password encoder for hashing. */
    private final PasswordEncoder passwords;

    public LocalSetupService(DeploymentProperties deployment, AccountRepository accounts, PasswordEncoder passwords)
    {
        this.deployment = deployment;
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public boolean available()
    {
        return deployment.local() && accounts.setupAvailable();
    }

    @Transactional(transactionManager = "platformTransactionManager")
    public void initialize(SetupRequest request)
    {
        if (!deployment.local())
        {
            throw new SetupUnavailable(false);
        }
        if (!accounts.lockSetupAvailable())
        {
            throw new SetupUnavailable(true);
        }
        String username = request.username() == null ? "" : request.username().strip();
        String fullName = request.fullName() == null ? "" : request.fullName().strip();
        String password = request.password();
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}") || fullName.isBlank() || fullName.length() > 255
                || password == null || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
        {
            throw new IllegalArgumentException("Invalid setup input");
        }
        accounts.createFirstOwner(username, fullName, passwords.encode(password));
    }

    public record SetupRequest(String username, String fullName, String password)
    {
        @Override
        public String toString()
        {
            return "SetupRequest[redacted]";
        }
    }

    static final class SetupUnavailable extends RuntimeException
    {

        /** Whether setup was already completed. */
        private final boolean initialized;

        SetupUnavailable(boolean initialized)
        {
            this.initialized = initialized;
        }

        boolean initialized()
        {
            return initialized;
        }
    }
}