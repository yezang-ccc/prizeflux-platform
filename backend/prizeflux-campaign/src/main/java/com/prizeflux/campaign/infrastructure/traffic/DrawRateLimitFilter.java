package com.prizeflux.campaign.infrastructure.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AuthenticatedActor;
import com.prizeflux.campaign.application.DrawRateLimitDecision;
import com.prizeflux.campaign.application.DrawRateLimiter;
import com.prizeflux.contract.api.ApiResponse;
import com.prizeflux.contract.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 在 JWT 校验后执行限流；客户端不能选择或伪造用户限流键。 */
public final class DrawRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(DrawRateLimitFilter.class);

    private final DrawRateLimiter limiter;
    private final DrawRateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public DrawRateLimitFilter(DrawRateLimiter limiter,
            DrawRateLimitProperties properties, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/draws".equals(requestPath(request));
    }

    private static String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return requestUri.substring(Math.min(contextPath.length(), requestUri.length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        AuthenticatedActor actor = authenticatedActor();
        if (actor == null || actor.role() != AppUser.Role.USER) {
            chain.doFilter(request, response);
            return;
        }

        DrawRateLimitDecision decision;
        try {
            decision = limiter.consume(actor.userId());
        } catch (DrawRateLimitUnavailableException exception) {
            log.warn("draw_rate_limiter_unavailable");
            writeFailure(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ErrorCode.SERVICE_UNAVAILABLE);
            return;
        }

        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            writeFailure(response, 429, ErrorCode.RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }

    private static AuthenticatedActor authenticatedActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            return null;
        }
        return actor;
    }

    private void writeFailure(HttpServletResponse response, int status, ErrorCode errorCode)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorCode));
    }
}
