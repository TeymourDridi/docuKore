package com.docukore.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Revalidates global account activation on each request.
 *
 * <p>Cabinet permissions are not session authorities; they are evaluated per-request
 * via {@link TenantRequestFilter}. This filter only ensures the account itself
 * remains active and allowed to log in for the current deployment mode.</p>
 */
final class AccountSessionFilter extends OncePerRequestFilter
{

    /**
     * Logger for this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountSessionFilter.class);

    /** Repository for account lookups. */
    private final AccountRepository accounts;

    /** Deployment configuration (local vs hosted). */
    private final DeploymentProperties deployment;

    AccountSessionFilter(AccountRepository accounts, DeploymentProperties deployment)
    {
        this.accounts = accounts;
        this.deployment = deployment;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && !(authentication instanceof AnonymousAuthenticationToken)
                && !accounts.find(authentication.getName())
                        .map(a -> a.loginAllowed(deployment))
                        .orElse(false))
        {
            String username = authentication.getName();
            LOG.warn("Account {} login not allowed for deployment mode {}",
                username, deployment.mode());

            new SecurityContextLogoutHandler().logout(request, response, authentication);
            AuthenticationErrors.unauthenticated(response);
            return;
        }
        chain.doFilter(request, response);
    }
}