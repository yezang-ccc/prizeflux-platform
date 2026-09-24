package com.prizeflux.ai.application;

import com.prizeflux.ai.api.AiCampaignRequest;
import com.prizeflux.ai.api.AiCampaignResponse;
import com.prizeflux.ai.api.AiRequestException;
import com.prizeflux.ai.api.AiTraceStep;
import com.prizeflux.ai.harness.application.AgentRunner;
import com.prizeflux.ai.harness.application.RunLimits;
import com.prizeflux.ai.harness.application.RunResult;
import com.prizeflux.ai.harness.application.RunStatus;
import com.prizeflux.ai.harness.campaign.CampaignReadClient;
import com.prizeflux.ai.harness.campaign.CampaignSummaryTool;
import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.Role;
import com.prizeflux.ai.harness.safety.AuthorizedToolExecutor;
import com.prizeflux.ai.infrastructure.campaign.CampaignIdentityClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Coordinates server-side identity, the read-only tool and a deterministic demo model. */
@Service
public class AiCampaignService {
    private static final RunLimits LIMITS = new RunLimits(3, 1, Duration.ofSeconds(8));
    private final CampaignIdentityClient identities;
    private final CampaignReadClient campaigns;
    private final Clock clock;

    public AiCampaignService(CampaignIdentityClient identities, CampaignReadClient campaigns, Clock clock) {
        this.identities = identities;
        this.campaigns = campaigns;
        this.clock = clock;
    }

    public AiCampaignResponse summarize(AiCampaignRequest request, String bearerToken) {
        Actor actor = identities.verify(bearerToken);
        if (!actor.role().allows(Role.OPERATOR)) {
            throw new AiRequestException(HttpStatus.FORBIDDEN);
        }
        RunResult result = runSummary(request, bearerToken, actor, List.of(AgentMessage.user(request.question())));
        if (result.status() != RunStatus.COMPLETED) {
            throw new AiRequestException(HttpStatus.BAD_GATEWAY);
        }
        return response(result);
    }

    public RunResult runSummary(AiCampaignRequest request, String bearerToken, Actor actor,
                                List<AgentMessage> initialTranscript) {
        AuthorizedToolExecutor tools = CampaignSummaryTool.forSession(actor, bearerToken, campaigns);
        AgentRunner runner = new AgentRunner((messages, remaining) -> nextAction(messages, request), tools, clock);
        return runner.runWithTranscript(initialTranscript, LIMITS);
    }

    public AiCampaignResponse response(RunResult result) {
        if (result.status() != RunStatus.COMPLETED) {
            throw new AiRequestException(HttpStatus.BAD_GATEWAY);
        }
        List<AiTraceStep> trace = result.transcript().stream()
                .map(message -> new AiTraceStep(message.kind().name(),
                        message.call() == null ? null : message.call().name(), message.toolSuccess()))
                .toList();
        return new AiCampaignResponse(result.answer(), true, result.status().name(), trace);
    }

    private ModelAction nextAction(List<AgentMessage> messages, AiCampaignRequest request) {
        int currentUser = -1;
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).kind() == AgentMessage.Kind.USER) {
                currentUser = index;
            }
        }
        boolean currentTurnHasObservation = currentUser >= 0
                && messages.subList(currentUser + 1, messages.size()).stream()
                .anyMatch(message -> message.kind() == AgentMessage.Kind.TOOL_OBSERVATION);
        if (!currentTurnHasObservation) {
            return new ToolCall("campaign.summary", Map.of("campaignId", Long.toString(request.campaignId())));
        }
        String observation = messages.subList(currentUser + 1, messages.size()).stream()
                .filter(message -> message.kind() == AgentMessage.Kind.TOOL_OBSERVATION)
                .reduce((first, second) -> second).orElseThrow().content();
        return new ModelAction.FinalAnswer("SIMULATED campaign summary: " + observation);
    }
}
