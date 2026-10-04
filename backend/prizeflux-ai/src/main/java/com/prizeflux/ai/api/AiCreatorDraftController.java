package com.prizeflux.ai.api;

import com.prizeflux.ai.application.AiCreatorDraftService;
import com.prizeflux.contract.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@RestController
@RequestMapping("/api/ai/creator")
public class AiCreatorDraftController {
    private final AiCreatorDraftService drafts;

    public AiCreatorDraftController(AiCreatorDraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping("/validate")
    public ApiResponse<AiCreatorDraftResponse> validate(
            @RequestHeader(value = AUTHORIZATION, required = false) String authorization,
            @RequestBody AiCreatorDraftRequest request) {
        return ApiResponse.success(drafts.advise(request, AiBearerToken.parse(authorization)));
    }
}
