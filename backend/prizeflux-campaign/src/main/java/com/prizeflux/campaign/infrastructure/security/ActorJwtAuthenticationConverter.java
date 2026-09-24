package com.prizeflux.campaign.infrastructure.security;

import com.prizeflux.campaign.application.AuthenticatedActor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * 把资源服务器校验通过后的 {@link Jwt} 转成以 {@link AuthenticatedActor} 为主体的认证，
 * 角色映射为单个 {@code ROLE_USER} / {@code ROLE_OPERATOR} 权限，供后续授权规则使用。
 * 声明提取复用 {@link JwtTokenService#toActor(Jwt)}，与签发/校验走同一映射，避免两套口径。
 * 声明不合法时包装为 {@link BadCredentialsException}，让过滤链统一映射为 401 而不是 500。
 */
public class ActorJwtAuthenticationConverter
        implements Converter<Jwt, UsernamePasswordAuthenticationToken> {

    @Override
    public UsernamePasswordAuthenticationToken convert(Jwt jwt) {
        try {
            AuthenticatedActor actor = JwtTokenService.toActor(jwt);
            GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + actor.role().name());
            return new UsernamePasswordAuthenticationToken(actor, null, List.of(authority));
        } catch (RuntimeException e) {
            throw new BadCredentialsException("Invalid token claims", e);
        }
    }
}
