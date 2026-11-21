package com.docukore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Correlates servlet diagnostics without recording request contents or account identifiers. */
public final class RequestDiagnosticsFilter extends OncePerRequestFilter
{
    /** Logger for bounded request outcomes. */
    private static final Logger LOG = LoggerFactory.getLogger(RequestDiagnosticsFilter.class);
    /** Internal server attribute retained across error redispatches. */
    static final String REQUEST_ID_ATTRIBUTE = RequestDiagnosticsFilter.class.getName() + ".requestId";
    /** Additive response header that lets a caller locate the matching diagnostic events. */
    static final String REQUEST_ID_HEADER = "X-Request-ID";
    /** MDC key owned by this filter, not an authentication or tenant identifier. */
    static final String REQUEST_ID_MDC_KEY = "requestId";

    @Override
    protected boolean shouldNotFilterErrorDispatch()
    {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException
    {
        correlate(request, response, chain);
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException
    {
        correlate(request, response, chain);
    }

    private void correlate(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException
    {
        Object existing = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        UUID requestId = existing instanceof UUID id ? id : UUID.randomUUID();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId.toString());
        String previous = MDC.get(REQUEST_ID_MDC_KEY);
        MDC.put(REQUEST_ID_MDC_KEY, requestId.toString());
        long started = System.nanoTime();
        boolean completed = false;
        try
        {
            chain.doFilter(request, response);
            completed = true;
        } finally
        {
            try
            {
                recordOutcome(request, response, completed, (System.nanoTime() - started) / 1_000_000);
            } finally
            {
                if (previous == null)
                {
                    MDC.remove(REQUEST_ID_MDC_KEY);
                } else
                {
                    MDC.put(REQUEST_ID_MDC_KEY, previous);
                }
            }
        }
    }

    private void recordOutcome(HttpServletRequest request, HttpServletResponse response, boolean completed,
        long elapsedMs)
    {
        String operation = operation(request.getRequestURI());
        String method = method(request.getMethod());
        String dispatch = request.getDispatcherType().name();
        if (!completed)
        {
            // A downstream exception has not necessarily been mapped to its final HTTP status yet.
            LOG.error("event=HTTP_REQUEST_FAILED operation={} method={} dispatch={} status=UNRESOLVED elapsedMs={}",
                operation, method, dispatch, elapsedMs);
            return;
        }
        if (request.isAsyncStarted())
        {
            LOG.debug("event=HTTP_ASYNC_STARTED operation={} method={} dispatch={} status=UNRESOLVED elapsedMs={}",
                operation, method, dispatch, elapsedMs);
            return;
        }
        int status = response.getStatus();
        if (status < 100 || status > 599)
        {
            LOG.error("event=HTTP_REQUEST_COMPLETED operation={} method={} dispatch={} status=UNKNOWN elapsedMs={}",
                operation, method, dispatch, elapsedMs);
        } else if (status >= 500)
        {
            LOG.error("event=HTTP_REQUEST_COMPLETED operation={} method={} dispatch={} status={} elapsedMs={}",
                operation, method, dispatch, status, elapsedMs);
        } else if (status >= 400)
        {
            LOG.warn("event=HTTP_REQUEST_COMPLETED operation={} method={} dispatch={} status={} elapsedMs={}",
                operation, method, dispatch, status, elapsedMs);
        } else
        {
            LOG.debug("event=HTTP_REQUEST_COMPLETED operation={} method={} dispatch={} status={} elapsedMs={}",
                operation, method, dispatch, status, elapsedMs);
        }
    }

    private String method(String supplied)
    {
        if (supplied == null)
        {
            return "OTHER";
        }
        return switch (supplied)
        {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> supplied;
            default -> "OTHER";
        };
    }

    private String operation(String path)
    {
        if (path == null)
        {
            return "OTHER";
        }
        return switch (path)
        {
            case "/api/auth/login" -> "LOGIN";
            case "/api/auth/logout" -> "LOGOUT";
            case "/api/auth/setup" -> "SETUP";
            case "/api/auth/signup" -> "SIGNUP";
            case "/api/auth/verify-email" -> "VERIFY_EMAIL";
            case "/api/auth/resend-verification" -> "RESEND_VERIFICATION";
            case "/api/auth/me", "/api/auth/csrf" -> "AUTH_SESSION";
            case "/actuator/health" -> "HEALTH";
            case "/error" -> "ERROR_DISPATCH";
            default -> path.startsWith("/api/") ? "API" : "OTHER";
        };
    }
}
