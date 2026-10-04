from prizeflux_agent.application.conversation_context import ConversationContextBuilder
from prizeflux_agent.domain.conversation import ConversationTurn


def _turn(sequence: int, user_input: str, answer: str) -> ConversationTurn:
    return ConversationTurn(sequence, user_input, answer)


def test_context_keeps_recent_whole_turns_and_current_input() -> None:
    builder = ConversationContextBuilder(max_context_chars=5000, max_history_turns=2)
    messages = builder.build(
        (
            _turn(1, "first question", "first answer"),
            _turn(2, "second question", "second answer"),
            _turn(3, "third question", "third answer"),
        ),
        "current question",
    )

    assert [(message.role, message.content) for message in messages[1:]] == [
        ("user", "second question"),
        ("assistant", "second answer"),
        ("user", "third question"),
        ("assistant", "third answer"),
        ("user", "current question"),
    ]


def test_context_drops_older_turns_at_whole_turn_boundaries() -> None:
    builder = ConversationContextBuilder(max_context_chars=370, max_history_turns=8)
    messages = builder.build(
        (
            _turn(1, "old" * 100, "old answer" * 20),
            _turn(2, "recent question", "recent answer"),
        ),
        "current question",
    )

    contents = [message.content for message in messages]
    assert "old" * 100 not in contents
    assert "recent question" in contents
    assert "recent answer" in contents
    assert contents[-1] == "current question"


def test_context_with_no_history_retains_harness_system_prompt() -> None:
    messages = ConversationContextBuilder(5000).build((), "help")

    assert messages[0].role == "system"
    assert "read-only PrizeFlux Copilot" in messages[0].content
    assert messages[-1].role == "user"
