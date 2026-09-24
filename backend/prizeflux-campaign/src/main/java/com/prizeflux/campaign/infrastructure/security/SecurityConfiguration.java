package com.prizeflux.campaign.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.application.DrawRateLimiter;
import com.prizeflux.campaign.infrastructure.traffic.DrawRateLimitFilter;
import com.prizeflux.campaign.infrastructure.traffic.DrawRateLimitProperties;
import com.prizeflux.contract.api.ApiResponse;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.nio.charset.StandardCharsets;

/**
 * 安全过滤链与授权装配。与持久化开关联动：
 * 持久化启用时提供 JWT 资源服务器过滤链；禁用（健康检查/轻量启动）时提供全放行链，
 * 避免默认"全部需认证"过滤链覆盖健康接口。签名密钥与 JwtDecoder 仍由 {@link JwtSecurityConfiguration} 提供。
 *
 * <p>I4 落地认证（登录、/me、服务端 Actor）；I5 把活动与配置写入口收紧为仅 {@code OPERATOR}，
 * 抽奖收紧为仅 {@code USER}；匿名缺令牌得 401，角色不足得 403。健康接口保持公开。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    @ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain resourceServerFilterChain(HttpSecurity http,
            ActorJwtAuthenticationConverter jwtConverter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            DrawRateLimiter drawRateLimiter,
            DrawRateLimitProperties drawRateLimitProperties,
            ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/health").permitAll()
                        // I5：运营写入口仅 OPERATOR；USER 越权得 403，匿名缺令牌得 401。
                        // PathPattern 下 "/**" 不匹配基路径，故创建用精确路径。
                        .requestMatchers(HttpMethod.POST, "/api/campaigns").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/campaigns/*/publish").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/campaigns/*/close").hasRole("OPERATOR")
                        // I6：奖品配置写仅 OPERATOR，抽奖仅 USER。
                        .requestMatchers(HttpMethod.POST, "/api/campaigns/*/configuration").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/draws").hasRole("USER")
                        // 活动查询对已认证用户开放；角色越权的具体数据过滤留到后续个人查询任务。
                        .requestMatchers(HttpMethod.GET, "/api/campaigns/**").authenticated()
                        // D9：个人抽奖历史仅本人可查；身份过滤在用例层取安全上下文，非路径/参数。
                        .requestMatchers(HttpMethod.GET, "/api/me/draws", "/api/me/draws/*").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .addFilterAfter(new DrawRateLimitFilter(drawRateLimiter,
                        drawRateLimitProperties, objectMapper), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "false")
    SecurityFilterChain healthFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
    ActorJwtAuthenticationConverter actorJwtAuthenticationConverter() {
        return new ActorJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.failure(ErrorCode.UNAUTHORIZED));
        };
    }

    @Bean
    @ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
    AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.failure(ErrorCode.FORBIDDEN));
        };
    }
}
