package com.prizeflux.campaign.application;

/** 登录失败统一异常；Web 层统一映射为 401，不区分账号不存在、禁用或口令错误。 */
public final class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
