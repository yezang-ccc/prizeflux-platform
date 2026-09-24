package com.prizeflux.campaign.application;

import java.time.Instant;

/** 已签发的访问令牌及其过期时间；令牌内容不得写入日志。 */
public record IssuedToken(String value, Instant expiresAt) {
    public IssuedToken {
        if (value == null || value.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("Invalid issued token");
        }
    }
}
