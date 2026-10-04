package com.prizeflux.campaign.application;

import java.util.ArrayList;
import java.util.List;

/** Deterministic findings for a user-provided, unsaved campaign proposal. */
public record CampaignDraftValidation(List<Finding> findings) {
    private static final String LEGACY_CAMPAIGN_ISSUE =
            "请检查活动名称，以及开始和结束时间。结束时间必须晚于开始时间。";
    private static final String LEGACY_CONFIGURATION_ISSUE =
            "请检查每日上限、奖品编号/名称/库存和概率；概率总和不能超过 1。";

    public CampaignDraftValidation {
        findings = List.copyOf(findings);
    }

    public boolean valid() {
        return findings.isEmpty();
    }

    /** Kept for callers deployed before the structured findings were added. */
    public List<String> legacyIssues() {
        boolean campaignDetailsInvalid = findings.stream().anyMatch(finding ->
                finding.code().startsWith("CAMPAIGN_NAME_")
                        || finding.code().startsWith("CAMPAIGN_START_")
                        || finding.code().startsWith("CAMPAIGN_END_")
                        || finding.code().startsWith("CAMPAIGN_TIME_"));
        boolean configurationInvalid = findings.stream().anyMatch(finding -> !
                (finding.code().startsWith("CAMPAIGN_NAME_")
                        || finding.code().startsWith("CAMPAIGN_START_")
                        || finding.code().startsWith("CAMPAIGN_END_")
                        || finding.code().startsWith("CAMPAIGN_TIME_")));
        List<String> issues = new ArrayList<>(2);
        if (campaignDetailsInvalid) {
            issues.add(LEGACY_CAMPAIGN_ISSUE);
        }
        if (configurationInvalid) {
            issues.add(LEGACY_CONFIGURATION_ISSUE);
        }
        return List.copyOf(issues);
    }

    public record Finding(String code, String field, String message) {
    }
}
