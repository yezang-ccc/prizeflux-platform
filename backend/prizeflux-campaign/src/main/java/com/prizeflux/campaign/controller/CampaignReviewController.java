package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.application.CampaignReviewService;
import com.prizeflux.campaign.controller.dto.CampaignResponse;
import com.prizeflux.campaign.controller.dto.CursorPageResponse;
import com.prizeflux.campaign.controller.dto.PendingCampaignReviewResponse;
import com.prizeflux.campaign.controller.dto.ReviewDecisionRequest;
import com.prizeflux.contract.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** OPERATOR 审核队列、通过与驳回入口。 */
@RestController
@RequestMapping("/api/ops/reviews/campaigns")
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignReviewController {
    private final CampaignReviewService reviewService;

    public CampaignReviewController(CampaignReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ApiResponse<CursorPageResponse<PendingCampaignReviewResponse>> pending(
            @RequestParam(required = false) Long before, @RequestParam(required = false) Integer limit) {
        var page = reviewService.pending(before, limit);
        return ApiResponse.success(new CursorPageResponse<>(page.items().stream()
                .map(item -> PendingCampaignReviewResponse.from(item.campaign())).toList(), page.nextCursor()));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<CampaignResponse> approve(@PathVariable long id,
            @Valid @RequestBody(required = false) ReviewDecisionRequest request) {
        String comment = request == null ? null : request.comment();
        return ApiResponse.success(CampaignResponse.from(reviewService.approve(id, comment)));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<CampaignResponse> reject(@PathVariable long id,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return ApiResponse.success(CampaignResponse.from(reviewService.reject(id, request.comment())));
    }
}
