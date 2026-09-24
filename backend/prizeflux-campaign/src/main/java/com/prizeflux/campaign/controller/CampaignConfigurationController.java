package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.CampaignConfigurationService;
import com.prizeflux.campaign.controller.dto.ConfigureCampaignRequest;
import com.prizeflux.campaign.domain.model.Prize;
import com.prizeflux.contract.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 草稿活动奖品配置 HTTP 适配器；仅 {@code OPERATOR} 可写（由安全过滤链保证）。
 * 只把公开 DTO 转换成领域 {@link Prize} 并委托配置用例，不涉及 SQL、库存或权限判断。
 * 与持久化开关联动：轻量启动（数据库禁用）时本控制器不加载。
 */
@RestController
@RequestMapping("/api/campaigns")
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignConfigurationController {
    private final CampaignConfigurationService configurationService;

    public CampaignConfigurationController(CampaignConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /** 替换草稿奖品配置；活动归属取路径 ID，客户端不能自行声明归属。 */
    @PostMapping("/{id}/configuration")
    public ApiResponse<Void> configure(@PathVariable long id,
            @Valid @RequestBody ConfigureCampaignRequest request) {
        List<Prize> prizes = request.prizes().stream()
                .map(item -> new Prize(item.id(), id, item.name(), item.totalStock(), item.probability()))
                .toList();
        configurationService.configureDraft(id, request.dailyLimit(), prizes);
        return ApiResponse.<Void>success(null);
    }
}
