package com.prizeflux.campaign.infrastructure.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.RegistrationRateLimitDecision;
import com.prizeflux.campaign.application.RegistrationRateLimitUnavailableException;
import com.prizeflux.campaign.application.RegistrationRateLimiter;
import com.prizeflux.contract.api.ApiResponse;
import com.prizeflux.contract.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 在 Controller/BCrypt 之前限制匿名注册；仅信任 Servlet 提供的 remoteAddr。 */
public final class RegistrationRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RegistrationRateLimitFilter.class);
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final RegistrationRateLimiter limiter;
    private final RegistrationRateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RegistrationRateLimitFilter(RegistrationRateLimiter limiter,
            RegistrationRateLimitProperties properties, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/auth/register".equals(requestPath(request));
    }

    private static String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return requestUri.substring(Math.min(contextPath.length(), requestUri.length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        RegistrationRateLimitDecision decision;
        try {
            decision = limiter.consume(request.getRemoteAddr());
        } catch (RegistrationRateLimitUnavailableException exception) {
            log.warn("registration_rate_limiter_unavailable");
            writeFailure(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
            return;
        }

        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            writeFailure(response, HTTP_TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeFailure(HttpServletResponse response, int status, ErrorCode errorCode)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorCode));
    }
}
