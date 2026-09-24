package com.prizeflux.campaign.application;

import java.util.Optional;

/** 账号持久化端口；口令验证由后续认证用例负责。 */
public interface AppUserRepository {
    Optional<AppUser> findByUsername(String username);

    long create(String username, String passwordHash, AppUser.Role role);
}
