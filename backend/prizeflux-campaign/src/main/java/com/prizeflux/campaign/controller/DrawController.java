package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.DrawApplicationService;
import com.prizeflux.campaign.application.DrawCommand;
import com.prizeflux.campaign.controller.dto.DrawRequest;
import com.prizeflux.campaign.controller.dto.DrawResponse;
import com.prizeflux.contract.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户抽奖 HTTP 适配器；仅 {@code USER} 可抽（由安全过滤链保证）。
 * 请求体不含 {@code userId}，身份由 {@code DrawApplicationService} 经 {@code ActorProvider} 从安全上下文读取。
 * 与持久化开关联动：轻量启动（数据库禁用）时本控制器不加载。
 */
@RestController
@RequestMapping("/api/draws")
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class DrawController {
    private final DrawApplicationService drawApplicationService;

    public DrawController(DrawApplicationService drawApplicationService) {
        this.drawApplicationService = drawApplicationService;
    }

    /** 执行一次抽奖；同键同参数由用例层幂等重放，同键异活动返回冲突。 */
    @PostMapping
    public ApiResponse<DrawResponse> draw(@Valid @RequestBody DrawRequest request) {
        DrawCommand command = new DrawCommand(request.campaignId(), request.requestKey());
        return ApiResponse.success(DrawResponse.from(drawApplicationService.draw(command)));
    }
}
