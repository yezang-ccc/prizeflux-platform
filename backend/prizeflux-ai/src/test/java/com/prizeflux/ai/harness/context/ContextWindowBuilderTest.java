package com.prizeflux.ai.harness.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.model.ToolCall;
import com.prizeflux.ai.harness.model.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextWindowBuilderTest {
    @Test
    void keepsRecentCompleteTurnsAndDoesNotMutateArchivedTranscript() {
        List<AgentMessage> history = List.of(
                AgentMessage.user("old-question"), AgentMessage.answer("old-answer"),
                AgentMessage.user("latest-question"),
                AgentMessage.toolCall(new ToolCall("campaign.summary", Map.of("campaignId", "1"))),
                AgentMessage.toolObservation(new ToolCall("campaign.summary", Map.of("campaignId", "1")),
                        ToolResult.ok("recent-observation")), AgentMessage.answer("latest-answer"));
        ContextWindowBuilder builder = new ContextWindowBuilder(5, 500);

        ContextWindow window = builder.build(history, "follow up");

        assertThat(window.messages())
                .extracting(AgentMessage::kind)
                .containsExactly(AgentMessage.Kind.USER, AgentMessage.Kind.ASSISTANT_TOOL_CALL,
                        AgentMessage.Kind.TOOL_OBSERVATION, AgentMessage.Kind.ASSISTANT_ANSWER, AgentMessage.Kind.USER);
        assertThat(window.messages().get(window.messages().size() - 1)).isEqualTo(AgentMessage.user("follow up"));
        assertThat(window.omittedMessageCount()).isEqualTo(2);
        assertThat(history).hasSize(6);
    }

    @Test
    void includesCurrentQuestionAndPreservesToolCallObservationPair() {
        ToolCall call = new ToolCall("campaign.summary", Map.of("campaignId", "1"));
        List<AgentMessage> history = List.of(AgentMessage.user("first"), AgentMessage.toolCall(call),
                AgentMessage.toolObservation(call, ToolResult.ok("ok")), AgentMessage.answer("done"));

        ContextWindow window = new ContextWindowBuilder(5, 100).build(history, "second");

        assertThat(window.messages())
                .extracting(AgentMessage::kind)
                .containsExactly(AgentMessage.Kind.USER, AgentMessage.Kind.ASSISTANT_TOOL_CALL,
                        AgentMessage.Kind.TOOL_OBSERVATION, AgentMessage.Kind.ASSISTANT_ANSWER, AgentMessage.Kind.USER);
        assertThat(window.messages().get(window.messages().size() - 1)).isEqualTo(AgentMessage.user("second"));
    }

    @Test
    void rejectsQuestionThatCannotFitWithoutTruncation() {
        ContextWindowBuilder builder = new ContextWindowBuilder(4, 8);
        assertThatThrownBy(() -> builder.build(List.of(), "too-long-question"))
                .isInstanceOf(ContextLimitExceededException.class);
    }

    @Test
    void includesNewUserMessageInContextForRunner() {
        ContextWindow window = new ContextWindowBuilder(4, 100).build(List.of(AgentMessage.user("old")), "new");
        assertThat(window.messages()).extracting(AgentMessage::content).containsExactly("old", "new");
        assertThat(window.currentMessageIndex()).isEqualTo(1);
    }
}
