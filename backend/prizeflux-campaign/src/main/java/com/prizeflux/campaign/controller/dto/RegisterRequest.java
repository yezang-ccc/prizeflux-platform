package com.prizeflux.campaign.controller.dto;

/** 自助注册仅接受用户名与密码；角色和用户 ID 不属于客户端可控字段。 */
public record RegisterRequest(String username, String password) {
}
