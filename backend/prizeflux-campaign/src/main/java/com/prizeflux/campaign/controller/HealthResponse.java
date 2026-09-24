package com.prizeflux.campaign.controller;

/**
 * 健康接口专用的响应 DTO（数据传输对象），属于 Web 接入层的输出模型。
 *
 * <p>使用 Java 17 的 {@code record}，因为这个对象只承载两个固定字段，创建后不应修改。
 * Spring Boot 内置的 Jackson 会把 {@code status} 和 {@code application} 自动写成 JSON 字段。</p>
 *
 * <p>第一个字段 status 表示服务自身状态，第二个字段 application 是固定应用标识。
 * 它不是数据库实体、不是 Spring Bean，也不是以后统一 API 响应模型；D2-01 会专门建立跨接口复用的统一响应契约。</p>
 *
 */
public record HealthResponse(String status, String application) {
}
