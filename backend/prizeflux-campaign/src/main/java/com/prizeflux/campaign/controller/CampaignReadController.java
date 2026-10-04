package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.CampaignReadService;
import com.prizeflux.campaign.controller.dto.CampaignConfigurationResponse;
import com.prizeflux.campaign.controller.dto.CampaignResponse;
import com.prizeflux.campaign.controller.dto.CursorPageResponse;
import com.prizeflux.campaign.controller.dto.PrizeViewResponse;
import com.prizeflux.contract.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户目录和运营只读查询 HTTP 适配器。 */
@RestController
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignReadController {
    private final CampaignReadService readService;

    public CampaignReadController(CampaignReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/api/available-campaigns")
    public ApiResponse<CursorPageResponse<CampaignResponse>> availableCampaigns(
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Integer limit) {
        var page = readService.availableCampaigns(before, limit);
        return ApiResponse.success(new CursorPageResponse<>(
                page.items().stream().map(CampaignResponse::from).toList(), page.nextCursor()));
    }

    @GetMapping("/api/ops/campaigns")
    public ApiResponse<CursorPageResponse<CampaignResponse>> operatorCampaigns(
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Integer limit) {
        var page = readService.operatorCampaigns(before, limit);
        return ApiResponse.success(new CursorPageResponse<>(
                page.items().stream().map(CampaignResponse::from).toList(), page.nextCursor()));
    }

    @GetMapping("/api/campaigns/{id}/prizes")
    public ApiResponse<java.util.List<PrizeViewResponse>> prizes(@PathVariable long id) {
        return ApiResponse.success(readService.prizes(id).stream()
                .map(prize -> new PrizeViewResponse(prize.id(), prize.name(), prize.probability()))
                .toList());
    }

    @GetMapping("/api/ops/campaigns/{id}/configuration")
    public ApiResponse<CampaignConfigurationResponse> configuration(@PathVariable long id) {
        var configuration = readService.operatorConfiguration(id);
        return ApiResponse.success(new CampaignConfigurationResponse(configuration.dailyLimit(),
                configuration.prizes().stream()
                        .map(prize -> new CampaignConfigurationResponse.PrizeConfigurationResponse(
                                prize.id(), prize.name(), prize.totalStock(), prize.probability()))
                        .toList()));
    }
}
