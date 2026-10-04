package com.prizeflux.campaign.controller.dto;

import java.util.List;

public record CampaignDraftValidationResponse(boolean valid, List<String> issues, List<Finding> findings) {
    public CampaignDraftValidationResponse {
        issues = List.copyOf(issues);
        findings = List.copyOf(findings);
    }

    public record Finding(String code, String field, String message) {
    }
}
