package com.prizeflux.ai.harness.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.ai.harness.model.ToolResult;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.ArgumentRule;
import com.prizeflux.ai.harness.safety.AuthorizedToolExecutor;
import com.prizeflux.ai.harness.safety.Role;
import com.prizeflux.ai.harness.safety.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Binds one authenticated request's actor and token to an operator-only GET tool. */
public final class CampaignSummaryTool {
    private CampaignSummaryTool() { }

    public static AuthorizedToolExecutor forSession(Actor actor, String verifiedBearerToken,
                                                     CampaignReadClient client) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(verifiedBearerToken, "verifiedBearerToken");
        Objects.requireNonNull(client, "client");
        if (verifiedBearerToken.isBlank()) {
            throw new IllegalArgumentException("missing verified token");
        }
        ObjectMapper json = new ObjectMapper();
        ToolDefinition definition = new ToolDefinition("campaign.summary", Role.OPERATOR,
                Map.of("campaignId", new ArgumentRule(true, Pattern.compile("[1-9][0-9]*"), 18)),
                (trustedActor, arguments, remaining) -> {
                    long id;
                    try {
                        id = Long.parseLong(arguments.get("campaignId"));
                    } catch (NumberFormatException exception) {
                        return ToolResult.error("INVALID_ARGUMENTS");
                    }
                    CampaignSummary summary = client.read(id, verifiedBearerToken, remaining);
                    try {
                        return ToolResult.ok(json.writeValueAsString(summary));
                    } catch (JsonProcessingException exception) {
                        return ToolResult.error("TOOL_UNAVAILABLE");
                    }
                });
        return new AuthorizedToolExecutor(actor, List.of(definition));
    }
}
