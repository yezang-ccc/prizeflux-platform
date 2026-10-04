package com.prizeflux.worker.infrastructure.security;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.context.annotation.Profile;

/** The isolated Worker management listener serves no application API. */
@Configuration
@Profile("local-full")
public class WorkerManagementSecurityConfiguration {

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "pfx.observability.metrics.scrape-auth-enabled", havingValue = "true")
    SecurityFilterChain authenticatedManagementEndpoints(HttpSecurity http,
            @Value("${pfx.observability.metrics.scrape-username:}") String username,
            @Value("${pfx.observability.metrics.scrape-password:}") String password) throws Exception {
        http.authenticationProvider(scrapeAuthenticationProvider(username, password));
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("prometheus")).hasRole("METRICS_SCRAPER")
                        .anyRequest().denyAll())
                .httpBasic(basic -> { });
        return http.build();
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "pfx.observability.metrics.scrape-auth-enabled", havingValue = "false",
            matchIfMissing = true)
    SecurityFilterChain internalOnlyManagementEndpoints(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("prometheus")).permitAll()
                        .anyRequest().denyAll());
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
