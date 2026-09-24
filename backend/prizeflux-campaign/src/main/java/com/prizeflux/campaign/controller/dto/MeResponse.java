package com.prizeflux.campaign.controller.dto;

/** 服务端验证后的当前用户投影，不含任何凭据或内部哈希。 */
public record MeResponse(long userId, String username, String role) {
}
