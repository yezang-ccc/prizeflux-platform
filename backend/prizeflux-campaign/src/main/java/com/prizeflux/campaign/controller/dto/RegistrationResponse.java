package com.prizeflux.campaign.controller.dto;

/** 注册成功的安全投影；不包含密码、密码哈希或访问令牌。 */
public record RegistrationResponse(long userId, String username, String role) {
}
