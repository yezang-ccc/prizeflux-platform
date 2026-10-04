package com.prizeflux.campaign.infrastructure.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.RegistrationRateLimitDecision;
import com.prizeflux.campaign.application.RegistrationRateLimiter;
import com.prizeflux.campaign.application.RegistrationRateLimitUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationRateLimitFilterTest {
    private final RegistrationRateLimiter limiter = mock(RegistrationRateLimiter.class);
    private final RegistrationRateLimitProperties properties = enabledProperties();
    private final RegistrationRateLimitFilter filter = new RegistrationRateLimitFilter(
            limiter, properties, new ObjectMapper());

    @Test
    void usesServletRemoteAddressAndRejectsOverLimitRequest() throws Exception {
        when(limiter.consume("192.0.2.25"))
                .thenReturn(new RegistrationRateLimitDecision(false, 5, 0, 52));
        MockHttpServletRequest request = request("POST", "/api/auth/register");
        request.setRemoteAddr("192.0.2.25");
        request.addHeader("X-Forwarded-For", "198.51.100.90");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("5");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("Retry-After")).isEqualTo("52");
        assertThat(response.getContentAsString()).contains("PFX-3001", "请求过于频繁");
        assertThat(chain.getRequest()).isNull();
        verify(limiter).consume("192.0.2.25");
        verify(limiter, never()).consume("198.51.100.90");
    }

    @Test
    void redisFailureFailsClosed() throws Exception {
        when(limiter.consume("127.0.0.1")).thenThrow(new RegistrationRateLimitUnavailableException());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("POST", "/api/auth/register"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("PFX-9001", "服务暂不可用")
                .doesNotContain("Redis", "password", "localhost");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void filtersOnlyPostRegistrationRequestsAndRespectsDisableSwitch() throws Exception {
        MockFilterChain otherPath = new MockFilterChain();
        filter.doFilter(request("POST", "/api/auth/login"), new MockHttpServletResponse(), otherPath);
        assertThat(otherPath.getRequest()).isNotNull();

        properties.setEnabled(false);
        MockFilterChain disabled = new MockFilterChain();
        filter.doFilter(request("POST", "/api/auth/register"), new MockHttpServletResponse(), disabled);
        assertThat(disabled.getRequest()).isNotNull();
        verify(limiter, never()).consume("127.0.0.1");
    }

    private static RegistrationRateLimitProperties enabledProperties() {
        RegistrationRateLimitProperties properties = new RegistrationRateLimitProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
