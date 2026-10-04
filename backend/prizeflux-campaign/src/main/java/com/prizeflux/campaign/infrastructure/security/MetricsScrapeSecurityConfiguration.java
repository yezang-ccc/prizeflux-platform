package com.prizeflux.campaign.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Optional least-privilege Basic identity for Prometheus; it is accepted only on this endpoint chain. */
@Configuration
@ConditionalOnProperty(name = "pfx.observability.metrics.scrape-auth-enabled", havingValue = "true")
public class MetricsScrapeSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain prometheusScrapeFilterChain(HttpSecurity http,
            ActorJwtAuthenticationConverter jwtConverter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            @Value("${pfx.observability.metrics.scrape-username:}") String username,
            @Value("${pfx.observability.metrics.scrape-password:}") String password) throws Exception {
        http.authenticationProvider(scrapeAuthenticationProvider(username, password));
        http
                .securityMatcher(EndpointRequest.to("prometheus"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAnyRole("OPERATOR", "METRICS_SCRAPER"))
                .httpBasic(basic -> { })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    private static DaoAuthenticationProvider scrapeAuthenticationProvider(String username, String password) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Metrics scrape username and password must both be configured");
        }
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        UserDetailsService users = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("METRICS_SCRAPER")
                .build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }
}
