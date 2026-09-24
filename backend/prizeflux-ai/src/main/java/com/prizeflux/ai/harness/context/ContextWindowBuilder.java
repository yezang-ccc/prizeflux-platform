package com.prizeflux.ai.harness.context;

import com.prizeflux.ai.harness.model.AgentMessage;
import com.prizeflux.ai.harness.session.SessionTextSize;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Selects the newest contiguous suffix of whole user interactions within explicit budgets. */
public final class ContextWindowBuilder {
    private final int maxMessages;
    private final int maxCharacters;

    public ContextWindowBuilder(int maxMessages, int maxCharacters) {
        if (maxMessages < 1 || maxCharacters < 1) {
            throw new IllegalArgumentException("context budgets must be positive");
        }
        this.maxMessages = maxMessages;
        this.maxCharacters = maxCharacters;
    }

    public ContextWindow build(List<AgentMessage> history, String newQuestion) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(newQuestion, "newQuestion");
        AgentMessage currentUser = AgentMessage.user(newQuestion);
        int currentSize = SessionTextSize.of(currentUser);
        if (currentSize > maxCharacters || maxMessages < 1) {
            throw new ContextLimitExceededException();
        }

        List<List<AgentMessage>> turns = groupTurns(history);
        List<AgentMessage> selected = new ArrayList<>();
        int characters = currentSize;
        int omitted = history.size();
        for (int index = turns.size() - 1; index >= 0; index--) {
            List<AgentMessage> turn = turns.get(index);
            int turnCharacters = turn.stream().mapToInt(SessionTextSize::of).sum();
            if (selected.size() + turn.size() + 1 > maxMessages
                    || characters + turnCharacters > maxCharacters) {
                break; // Keep a contiguous recent suffix, never skip a recent large turn to keep an older one.
            }
            selected.addAll(0, turn);
            characters += turnCharacters;
            omitted -= turn.size();
        }
        selected.add(currentUser);
        return new ContextWindow(selected, omitted, characters, selected.size() - 1);
    }

    private List<List<AgentMessage>> groupTurns(List<AgentMessage> history) {
        List<List<AgentMessage>> turns = new ArrayList<>();
        List<AgentMessage> current = null;
        for (AgentMessage message : history) {
            Objects.requireNonNull(message, "history message");
            if (message.kind() == AgentMessage.Kind.USER) {
                current = new ArrayList<>();
                turns.add(current);
            }
            if (current == null) {
                throw new IllegalArgumentException("conversation history must start with a user message");
            }
            current.add(message);
        }
        return turns;
    }
}
