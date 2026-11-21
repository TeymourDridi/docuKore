package com.docukore.identity;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class CurrentAccount
{

    /** Account repository for lookups. */
    private final AccountRepository accounts;

    /** Deployment configuration (local vs hosted). */
    private final DeploymentProperties deployment;

    public CurrentAccount(AccountRepository accounts, DeploymentProperties deployment)
    {
        this.accounts = accounts;
        this.deployment = deployment;
    }

    public UUID requireId()
    {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof UserDetails principal)
                || principal.getUsername() == null || principal.getUsername().isBlank())
        {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        return accounts.find(principal.getUsername())
                .filter(account -> account.loginAllowed(deployment))
                .map(AccountRepository.Account::id)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authentication is required"));
    }
}