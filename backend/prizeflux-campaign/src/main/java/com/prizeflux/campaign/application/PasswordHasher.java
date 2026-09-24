package com.prizeflux.campaign.application;

/** 密码编码端口；业务层不依赖具体 BCrypt 类。 */
public interface PasswordHasher {
    String hash(String rawPassword);

    boolean matches(String rawPassword, String storedHash);
}
