package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.CampaignUseCase;
import com.prizeflux.campaign.application.CampaignReadService;
import com.prizeflux.campaign.controller.dto.CampaignResponse;
import com.prizeflux.campaign.controller.dto.CreateCampaignRequest;
import com.prizeflux.contract.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 活动管理HTTP适配器，只接收请求、调用用例并包装公开响应。 */
@RestController
@RequestMapping("/api/campaigns")
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignController {
    private final CampaignUseCase campaignUseCase;
    private final CampaignReadService campaignReadService;

    public CampaignController(CampaignUseCase campaignUseCase, CampaignReadService campaignReadService) {
        this.campaignUseCase = campaignUseCase;
        this.campaignReadService = campaignReadService;
    }

    /** 创建草稿活动。 */
    @PostMapping
    public ResponseEntity<ApiResponse<CampaignResponse>> create(
            @Valid @RequestBody CreateCampaignRequest request) {
        CampaignResponse response = CampaignResponse.from(campaignUseCase.create(
                request.id(), request.name(), request.startsAt(), request.endsAt()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /** 按业务id查询活动。 */
    @GetMapping("/{id}")
    public ApiResponse<CampaignResponse> get(@PathVariable long id) {
        return ApiResponse.success(CampaignResponse.from(campaignReadService.campaign(id)));
    }

    /** 发布草稿活动。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<CampaignResponse> publish(@PathVariable long id) {
        return ApiResponse.success(CampaignResponse.from(campaignUseCase.publish(id)));
    }

    /** 关闭草稿或已发布活动。 */
    @PostMapping("/{id}/close")
    public ApiResponse<CampaignResponse> close(@PathVariable long id) {
        return ApiResponse.success(CampaignResponse.from(campaignUseCase.close(id)));
    }
}
