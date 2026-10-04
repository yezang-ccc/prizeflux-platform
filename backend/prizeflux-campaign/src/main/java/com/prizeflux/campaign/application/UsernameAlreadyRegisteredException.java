package com.prizeflux.campaign.application;

/** 注册用户名已被占用，包括并发注册时数据库唯一键最终裁决的情况。 */
public final class UsernameAlreadyRegisteredException extends RuntimeException {
    public UsernameAlreadyRegisteredException() {
        super("Username is unavailable");
    }
}
