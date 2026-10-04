package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.CampaignOwnershipService;
import com.prizeflux.campaign.controller.dto.CampaignConfigurationResponse;
import com.prizeflux.campaign.controller.dto.CampaignDraftValidationResponse;
import com.prizeflux.campaign.controller.dto.ConfigureCampaignRequest;
import com.prizeflux.campaign.controller.dto.CursorPageResponse;
import com.prizeflux.campaign.controller.dto.OwnedCampaignRequest;
import com.prizeflux.campaign.controller.dto.OwnedCampaignResponse;
import com.prizeflux.campaign.controller.dto.ValidateOwnedCampaignRequest;
import com.prizeflux.campaign.domain.model.Prize;
import com.prizeflux.contract.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前 USER 的活动创建、草稿配置和提审入口。 */
@RestController
@RequestMapping("/api/me/campaigns")
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CreatorCampaignController {
    private final CampaignOwnershipService ownershipService;

    public CreatorCampaignController(CampaignOwnershipService ownershipService) {
        this.ownershipService = ownershipService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OwnedCampaignResponse>> create(
            @Valid @RequestBody OwnedCampaignRequest request) {
        var campaign = ownershipService.create(request.name(), request.startsAt(), request.endsAt());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(OwnedCampaignResponse.from(campaign)));
    }

    @PostMapping("/validate")
    public ApiResponse<CampaignDraftValidationResponse> validate(
            @Valid @RequestBody ValidateOwnedCampaignRequest request) {
        List<CampaignOwnershipService.PrizeInput> prizes = request.prizes().stream()
                .map(item -> item == null ? null : new CampaignOwnershipService.PrizeInput(
                        item.id(), item.name(), item.totalStock(), item.probability())).toList();
        var validation = ownershipService.validateProposal(request.name(), request.startsAt(),
                request.endsAt(), request.dailyLimit(), prizes);
        List<CampaignDraftValidationResponse.Finding> findings = validation.findings().stream()
                .map(finding -> new CampaignDraftValidationResponse.Finding(
                        finding.code(), finding.field(), finding.message()))
                .toList();
        return ApiResponse.success(new CampaignDraftValidationResponse(
                validation.valid(), validation.legacyIssues(), findings));
    }

    @GetMapping
    public ApiResponse<CursorPageResponse<OwnedCampaignResponse>> list(
            @RequestParam(required = false) Long before, @RequestParam(required = false) Integer limit) {
        var page = ownershipService.list(before, limit);
        return ApiResponse.success(new CursorPageResponse<>(
                page.items().stream().map(OwnedCampaignResponse::from).toList(), page.nextCursor()));
    }

    @GetMapping("/{id}")
    public ApiResponse<OwnedCampaignResponse> get(@PathVariable long id) {
        return ApiResponse.success(OwnedCampaignResponse.from(ownershipService.get(id)));
    }

    @PutMapping("/{id}")
    public ApiResponse<OwnedCampaignResponse> update(@PathVariable long id,
            @Valid @RequestBody OwnedCampaignRequest request) {
        return ApiResponse.success(OwnedCampaignResponse.from(
                ownershipService.update(id, request.name(), request.startsAt(), request.endsAt())));
    }

    @GetMapping("/{id}/configuration")
    public ApiResponse<CampaignConfigurationResponse> configuration(@PathVariable long id) {
        var configuration = ownershipService.configuration(id);
        return ApiResponse.success(new CampaignConfigurationResponse(configuration.dailyLimit(),
                configuration.prizes().stream().map(prize -> new CampaignConfigurationResponse
                        .PrizeConfigurationResponse(prize.id(), prize.name(), prize.totalStock(), prize.probability()))
                        .toList()));
    }

    @PutMapping("/{id}/configuration")
    public ApiResponse<Void> configure(@PathVariable long id,
            @Valid @RequestBody ConfigureCampaignRequest request) {
        List<Prize> prizes = request.prizes().stream()
                .map(item -> new Prize(item.id(), id, item.name(), item.totalStock(), item.probability()))
                .toList();
        ownershipService.configure(id, request.dailyLimit(), prizes);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<OwnedCampaignResponse> submit(@PathVariable long id) {
        ownershipService.submit(id);
        return ApiResponse.success(OwnedCampaignResponse.from(ownershipService.get(id)));
    }
}
