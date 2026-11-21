package com.docukore.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public final class TenantRequestFilter extends OncePerRequestFilter
{

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
        try
        {
            if (TenantContext.isPresent())
            {
                throw new IllegalStateException("Stale tenant context at request entry");
            }
            Object selection = request.getAttribute(TRUSTED_CABINET_ATTRIBUTE);
            if (selection == null)
            {
                chain.doFilter(request, response);
            } else if (selection instanceof UUID cabinetId)
            {
                try (var ignored = tenantAccess.open(cabinetId))
                {
                    chain.doFilter(request, response);
                }
            } else
            {
                throw new IllegalArgumentException("Trusted cabinet selection must be a UUID");
            }
        } finally
        {
            TenantContext.clear();
        }
    }
}