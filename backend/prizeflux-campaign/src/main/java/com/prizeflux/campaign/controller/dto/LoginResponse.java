package com.prizeflux.campaign.controller.dto;

import java.time.Instant;

/** 登录成功返回的短期访问令牌及过期时间；令牌值不得写入日志。 */
public record LoginResponse(String token, Instant expiresAt) {
}
