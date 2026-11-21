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

/** Public setup and authenticated account endpoints with payload-free diagnostic events. */
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
        var status = new SetupStatus(setup.available(), deployment.mode());
        if (status.available())
        {
            LOG.debug("AUTH_SETUP_AVAILABLE deployment={}", status.deploymentMode());
        } else
        {
            LOG.debug("AUTH_SETUP_UNAVAILABLE deployment={}", status.deploymentMode());
        }
        return status;
    }

    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    void initialize(@RequestBody LocalSetupService.SetupRequest request)
    {
        LOG.debug("AUTH_SETUP_STARTED deployment={}", deployment.mode());
        setup.initialize(request);
        LOG.info("AUTH_SETUP_COMPLETED deployment={}", deployment.mode());
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
        LOG.debug("AUTH_CURRENT_ACCOUNT");
        return new AccountResponse(account.id(), account.username(), account.fullName(), account.email());
    }

    /**
     * Public setup availability without installation details.
     *
     * @param available whether initial owner creation is available
     * @param deploymentMode the allowlisted deployment category
     */
    record SetupStatus(boolean available, DeploymentProperties.Mode deploymentMode)
    {
    }

    /**
     * Authenticated account response, never included in diagnostic events.
     *
     * @param id the account identity
     * @param username the account login name
     * @param fullName the account display name
     * @param email the account email address
     */
    record AccountResponse(UUID id, String username, String fullName, String email)
    {
    }
}