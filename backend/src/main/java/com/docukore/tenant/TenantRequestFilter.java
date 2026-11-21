package com.docukore.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public final class TenantRequestFilter extends OncePerRequestFilter
{

    private static final Logger log = LoggerFactory.getLogger(TenantRequestFilter.class);

    // Internal server attribute, never populated from a header or request parameter.
    /** Request attribute name for trusted cabinet ID. */
    public static final String TRUSTED_CABINET_ATTRIBUTE = TenantRequestFilter.class.getName() + ".authorizedCabinetId";

    /** Tenant access manager. */
    private final CabinetTenantAccess tenantAccess;

    public TenantRequestFilter(CabinetTenantAccess tenantAccess)
    {
        this.tenantAccess = tenantAccess;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        String path = request.getRequestURI();
        log.debug("Tenant request filter entering - path: {}", path);

        try
        {
            if (TenantContext.isPresent())
            {
                log.error("Stale tenant context detected - refusing request");
                throw new IllegalStateException("Stale tenant context at request entry");
            }

            Object selection = request.getAttribute(TRUSTED_CABINET_ATTRIBUTE);
            if (selection == null)
            {
                log.debug("No cabinet context provided - allowing request to proceed");
                chain.doFilter(request, response);
            } else if (selection instanceof UUID cabinetId)
            {
                log.debug("Opening tenant context for cabinet: {}", cabinetId);
                try (var ignored = tenantAccess.open(cabinetId))
                {
                    chain.doFilter(request, response);
                }
                log.debug("Tenant context closed successfully");
            } else
            {
                log.error("Invalid cabinet selection type: {}, expected UUID", selection.getClass());
                throw new IllegalArgumentException("Trusted cabinet selection must be a UUID");
            }
        } catch (IllegalArgumentException e)
        {
            log.warn("Invalid tenant context - path: {}, error: {}", path, e.getMessage());
            throw e;
        } catch (IllegalStateException e)
        {
            log.error("Tenant context error - path: {}, error: {}", path, e.getMessage());
            throw e;
        } finally
        {
            TenantContext.clear();
            log.debug("Tenant request filter exiting - path: {}", path);
        }
    }
}