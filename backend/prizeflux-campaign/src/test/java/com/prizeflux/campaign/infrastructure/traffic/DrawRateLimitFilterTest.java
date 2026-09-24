package com.prizeflux.campaign.infrastructure.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AuthenticatedActor;
import com.prizeflux.campaign.application.DrawRateLimitDecision;
import com.prizeflux.campaign.application.DrawRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DrawRateLimitFilterTest {
    private final DrawRateLimiter limiter = mock(DrawRateLimiter.class);
    private final DrawRateLimitProperties properties = enabledProperties();
    private final DrawRateLimitFilter filter = new DrawRateLimitFilter(
            limiter, properties, new ObjectMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deniedAuthenticatedUserDoesNotReachDrawEndpointAndGetsRetryHeaders() throws Exception {
        authenticate(42, AppUser.Role.USER);
        when(limiter.consume(42)).thenReturn(new DrawRateLimitDecision(false, 30, 0, 17));
        MockHttpServletRequest request = request("POST", "/api/draws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("Retry-After")).isEqualTo("17");
        assertThat(response.getContentAsString()).contains("PFX-3001", "请求过于频繁");
        assertThat(chain.getRequest()).isNull();
        verify(limiter).consume(42);
    }

    @Test
    void redisFailureFailsClosedWithSafeServiceUnavailableResponse() throws Exception {
        authenticate(42, AppUser.Role.USER);
        when(limiter.consume(42)).thenThrow(new DrawRateLimitUnavailableException());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("POST", "/api/draws"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("PFX-9001", "服务暂不可用")
                .doesNotContain("Redis", "password", "localhost");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void onlyAuthenticatedUserDrawPostConsumesLimit() throws Exception {
        authenticate(77, AppUser.Role.OPERATOR);
        MockFilterChain operatorChain = new MockFilterChain();
        filter.doFilter(request("POST", "/api/draws"), new MockHttpServletResponse(), operatorChain);
        assertThat(operatorChain.getRequest()).isNotNull();
        verify(limiter, never()).consume(77);

        authenticate(42, AppUser.Role.USER);
        MockFilterChain otherPathChain = new MockFilterChain();
        filter.doFilter(request("GET", "/api/draws"), new MockHttpServletResponse(), otherPathChain);
        assertThat(otherPathChain.getRequest()).isNotNull();
        verify(limiter, never()).consume(42);
    }

    @Test
    void disabledLimiterDoesNotCallRedisAdapter() throws Exception {
        authenticate(42, AppUser.Role.USER);
        properties.setEnabled(false);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("POST", "/api/draws"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(limiter, never()).consume(42);
    }

    private static DrawRateLimitProperties enabledProperties() {
        DrawRateLimitProperties properties = new DrawRateLimitProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static void authenticate(long userId, AppUser.Role role) {
        AuthenticatedActor actor = new AuthenticatedActor(userId, "user." + userId, role);
        String authority = "ROLE_" + role.name();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null,
                        List.of(new SimpleGrantedAuthority(authority))));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
