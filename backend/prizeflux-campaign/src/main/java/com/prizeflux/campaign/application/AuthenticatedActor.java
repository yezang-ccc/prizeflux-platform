package com.prizeflux.campaign.application;

/** 令牌校验通过后得到的服务端身份；与 {@link AppUser} 不同，它不携带密码哈希。 */
public record AuthenticatedActor(long userId, String username, AppUser.Role role) {
    public AuthenticatedActor {
        if (userId <= 0 || username == null || !username.matches("[a-z0-9._-]{3,64}") || role == null) {
            throw new IllegalArgumentException("Invalid authenticated actor");
        }
    }
}
