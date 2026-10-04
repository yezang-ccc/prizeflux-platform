package com.prizeflux.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.ai.api.AiCreatorDraftRequest;
import com.prizeflux.ai.api.AiCreatorDraftResponse;
import com.prizeflux.ai.api.AiRequestException;
import com.prizeflux.ai.api.AiTraceStep;
import com.prizeflux.ai.harness.application.AgentRunner;
import com.prizeflux.ai.harness.application.ModelPort;
import com.prizeflux.ai.harness.application.RunLimits;
import com.prizeflux.ai.harness.application.RunResult;
import com.prizeflux.ai.harness.application.RunStatus;
import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ModelAction;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.ArgumentRule;
import com.prizeflux.ai.harness.safety.AuthorizedToolExecutor;
import com.prizeflux.ai.harness.safety.Role;
import com.prizeflux.ai.harness.safety.ToolDefinition;
import com.prizeflux.ai.infrastructure.campaign.CampaignDraftValidationClient;
import com.prizeflux.ai.infrastructure.campaign.CampaignIdentityClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** One bounded Fake Model → authorized validation tool → observation → advice turn. */
@Service
public class AiCreatorDraftService {
    private static final String TOOL = "campaign.draft.validate";
    private static final RunLimits LIMITS = new RunLimits(2, 1, Duration.ofSeconds(7));
    private static final Pattern CURRENT_DRAFT = Pattern.compile("CURRENT_DRAFT");

    private final CampaignIdentityClient identities;
    private final CampaignDraftValidationClient validationClient;
    private final Clock clock;
    private final ObjectMapper json;

    public AiCreatorDraftService(CampaignIdentityClient identities,
            CampaignDraftValidationClient validationClient, Clock clock, ObjectMapper json) {
        this.identities = identities;
        this.validationClient = validationClient;
        this.clock = clock;
        this.json = json;
    }

    public AiCreatorDraftResponse advise(AiCreatorDraftRequest request, String bearerToken) {
        Actor actor = identities.verify(bearerToken);
        if (actor.role() != Role.USER) {
            throw new AiRequestException(HttpStatus.FORBIDDEN);
        }
        AtomicReference<CampaignDraftValidationClient.Result> validation = new AtomicReference<>();
        ToolDefinition validateDraft = new ToolDefinition(TOOL, Role.USER,
                Map.of("scope", new ArgumentRule(true, CURRENT_DRAFT, 20)),
                (trustedActor, arguments, remaining) -> {
                    if (trustedActor.userId() != actor.userId()) return ToolResult.error("TOOL_FORBIDDEN");
                    CampaignDraftValidationClient.Result result =
                            validationClient.validate(request, bearerToken, remaining);
                    validation.set(result);
                    try {
                        return ToolResult.ok(json.writeValueAsString(result));
                    } catch (Exception exception) {
                        return ToolResult.error("TOOL_UNAVAILABLE");
                    }
                });
        AuthorizedToolExecutor tools = new AuthorizedToolExecutor(actor, List.of(validateDraft));
        ModelPort fakeModel = (messages, remaining) -> {
            boolean observed = messages.stream().anyMatch(
                    message -> message.kind() == AgentMessage.Kind.TOOL_OBSERVATION);
            if (!observed) {
                return new ToolCall(TOOL, Map.of("scope", "CURRENT_DRAFT"));
            }
            CampaignDraftValidationClient.Result result = validation.get();
            if (result == null) {
                return new ModelAction.FinalAnswer("模拟校验服务暂时不可用，请稍后再试；草案不会被保存或提交。");
            }
            if (result.valid()) {
                return new ModelAction.FinalAnswer("模拟检查通过：活动时间、奖品库存与概率配置符合当前规则。请再人工检查活动文案与运营要求；我不会替你保存或提交。");
            }
            return new ModelAction.FinalAnswer("模拟检查发现需要处理的地方："
                    + String.join("；", result.issues()) + "。修改后可再次检查；我不会替你保存或提交。");
        };
        RunResult result = new AgentRunner(fakeModel, tools, clock)
                .run("检查当前活动草案，只校验并提出建议，不执行保存或审核。", LIMITS);
        if (result.status() != RunStatus.COMPLETED) {
            throw new AiRequestException(HttpStatus.BAD_GATEWAY);
        }
        CampaignDraftValidationClient.Result checked = validation.get();
        List<AiTraceStep> trace = result.transcript().stream()
                .map(message -> new AiTraceStep(message.kind().name(),
                        message.call() == null ? null : message.call().name(), message.toolSuccess()))
                .toList();
        return new AiCreatorDraftResponse(result.answer(), checked != null && checked.valid(),
                checked == null ? List.of("校验服务暂时不可用。") : checked.issues(), true,
                result.status().name(), trace);
    }
}
