package com.prizeflux.campaign.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 接入层（Controller）。
 *
 * <p>职责：把外部 HTTP 请求转换为 Java 方法调用，再把 Java 返回对象交给 Spring MVC
 * 序列化为 JSON 响应。它不负责活动、抽奖、数据库或 Redis 等业务和基础设施工作。</p>
 *
 * <p>谁会调用它：浏览器、负载均衡器、Docker 健康检查或未来的 Gateway 请求
 * {@code GET /api/health} 时，Spring MVC 会定位并调用 {@link #health()}。</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * 返回 Campaign 服务自身的最小存活状态。
     *
     * <p>这里返回 {@code UP} 只表示 Spring 应用已启动且 Controller 可处理请求，
     * 不表示数据库、Redis、Kafka 或外部 AI 服务可用。本任务尚未连接这些组件，
     * 因此不能伪造它们的健康状态。</p>
     *
     * 返回的对象会被 Spring MVC 自动序列化为 JSON，HTTP 默认状态码为 200 OK。
     */
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "prizeflux-campaign");
    }
}
