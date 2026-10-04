package com.prizeflux.campaign.application;

import java.util.Optional;

/** 账号持久化端口；口令验证由后续认证用例负责。 */
public interface AppUserRepository {
    Optional<AppUser> findByUsername(String username);

    long create(String username, String passwordHash, AppUser.Role role);

    /** 原子地创建 USER；实现必须将用户名唯一键冲突转换为 UsernameAlreadyRegisteredException。 */
    long registerUser(String username, String passwordHash);
}
