package com.docukore.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController
{

    /**
     * Logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationController.class);

    /** Local setup service for initial owner creation. */
    private final LocalSetupService setup;

    /** Account repository for lookups. */
    private final AccountRepository accounts;

    /** Deployment metadata for public registration fields. */
    private final DeploymentProperties deployment;

    public AuthenticationController(LocalSetupService setup, AccountRepository accounts,
                                    DeploymentProperties deployment)
    {
        this.setup = setup;
        this.accounts = accounts;
        this.deployment = deployment;
    }

    @GetMapping("/setup")
    SetupStatus setupStatus()
    {
        LOG.info("Setup status requested - available: {}, mode: {}",
            setup.available(), deployment.mode());
        return new SetupStatus(setup.available(), deployment.mode());
    }

    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    void initialize(@RequestBody LocalSetupService.SetupRequest request)
    {
        LOG.info("Local setup initialization requested for user: {}",
            request.username() != null ? request.username() : "[redacted]");

        setup.initialize(request);
        LOG.info("Local setup completed successfully for user: {}",
            request.username() != null ? request.username() : "[redacted]");
    }

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void csrf()
    {
        // Security's SPA handler materializes the cookie.
    }

    @GetMapping("/me")
    AccountResponse current(Authentication authentication)
    {
        var account = accounts.find(authentication.getName()).orElseThrow();
        LOG.debug("Current user requested - username: {}", account.username());
        return new AccountResponse(account.id(), account.username(), account.fullName(), account.email());
    }

    record SetupStatus(boolean available, DeploymentProperties.Mode deploymentMode)
    {
    }

    record AccountResponse(UUID id, String username, String fullName, String email)
    {
    }
}