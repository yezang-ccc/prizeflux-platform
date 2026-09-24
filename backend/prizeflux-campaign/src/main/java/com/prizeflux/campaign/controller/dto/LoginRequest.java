package com.prizeflux.campaign.controller.dto;

/**
 * 登录请求。故意不加字段级校验：用户名格式、口令错误、账号缺失或禁用都在认证用例里
 * 统一映射为 401，避免用不同的 400/401 区分账号是否存在。只有 JSON 无法解析才由框架返回 400。
 */
public record LoginRequest(String username, String password) {
}
