package com.prizeflux.ai.api;

import com.prizeflux.ai.application.AiCampaignService;
import com.prizeflux.contract.api.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiCampaignController {
    private final AiCampaignService service;

    public AiCampaignController(AiCampaignService service) {
        this.service = service;
    }

    @PostMapping("/campaign-summary")
    public ApiResponse<AiCampaignResponse> summarize(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AiCampaignRequest request) {
        String token = AiBearerToken.parse(authorization);
        return ApiResponse.success(service.summarize(request, token));
    }
}
