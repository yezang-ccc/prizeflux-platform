package com.prizeflux.campaign.infrastructure.security;

import com.prizeflux.campaign.application.ActorProvider;
import com.prizeflux.campaign.application.AuthenticatedActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 从 Spring Security 上下文读取服务端确认的身份，是 {@link ActorProvider} 的生产实现。
 * 绝不读取任何请求头、请求体或路径参数；上下文中没有可用的 {@link AuthenticatedActor} 时直接失败。
 */
@Component
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityContextActorProvider implements ActorProvider {

    @Override
    public AuthenticatedActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            throw new IllegalStateException("No authenticated actor in security context");
        }
        return actor;
    }
}
