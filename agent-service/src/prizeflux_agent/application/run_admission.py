import hashlib
import json
import re
from decimal import Decimal

from prizeflux_agent.application.run_fingerprint import creator_run_fingerprint
from prizeflux_agent.application.run_store import (
    IdempotencyConflict,
    RunStorePort,
)
from prizeflux_agent.domain.conversation import (
    AcceptedRun,
    AgentRunMode,
    CreatorDraftContext,
)

_CLIENT_MESSAGE_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class RunAdmissionService:
    """Validates and fingerprints a turn before the store reserves its session version."""

    def __init__(self, store: RunStorePort, max_input_chars: int = 6000) -> None:
        if max_input_chars < 1:
            raise ValueError("max_input_chars must be positive")
        self._store = store
        self._max_input_chars = max_input_chars

    async def accept(
        self,
        *,
        session_id: str,
        owner_id: int,
        client_message_id: str,
        expected_version: int,
        user_input: str,
        mode: AgentRunMode = AgentRunMode.CHAT,
        creator_context: CreatorDraftContext | None = None,
    ) -> AcceptedRun:
        normalized_input, fingerprint = self._prepare(
            owner_id=owner_id,
            client_message_id=client_message_id,
            expected_version=expected_version,
            user_input=user_input,
            mode=mode,
            creator_context=creator_context,
        )
        return await self._store.accept_run(
            session_id=session_id,
            owner_id=owner_id,
            client_message_id=client_message_id,
            expected_version=expected_version,
            user_input=normalized_input,
            request_fingerprint=fingerprint,
            creator_context=creator_context,
        )

    async def find_existing(
        self,
        *,
        session_id: str,
        owner_id: int,
        client_message_id: str,
        expected_version: int,
        user_input: str,
        mode: AgentRunMode = AgentRunMode.CHAT,
        creator_context: CreatorDraftContext | None = None,
    ) -> AcceptedRun | None:
        normalized_input, fingerprint = self._prepare(
            owner_id=owner_id,
            client_message_id=client_message_id,
            expected_version=expected_version,
            user_input=user_input,
            mode=mode,
            creator_context=creator_context,
        )
        existing = await self._store.find_run_by_idempotency_key(
            session_id, owner_id, client_message_id
        )
        if existing is None:
            return None
        if existing.request_fingerprint != fingerprint:
            raise IdempotencyConflict
        return AcceptedRun(existing, replayed=True)

    def _prepare(
        self,
        *,
        owner_id: int,
        client_message_id: str,
        expected_version: int,
        user_input: str,
        mode: AgentRunMode,
        creator_context: CreatorDraftContext | None,
    ) -> tuple[str, str]:
        normalized_input = user_input.strip() if isinstance(user_input, str) else ""
        if not normalized_input:
            raise ValueError("input_required")
        if len(normalized_input) > self._max_input_chars:
            raise ValueError("input_too_large")
        if not isinstance(client_message_id, str) or not _CLIENT_MESSAGE_ID.fullmatch(
            client_message_id
        ):
            raise ValueError("invalid_client_message_id")
        if type(expected_version) is not int or expected_version < 0:
            raise ValueError("invalid_expected_version")
        if type(owner_id) is not int or owner_id < 1:
            raise ValueError("invalid_owner_id")
        if not isinstance(mode, AgentRunMode):
            raise ValueError("invalid_run_mode")
        if mode is AgentRunMode.CHAT and creator_context is not None:
            raise ValueError("chat_context_not_allowed")
        if mode is AgentRunMode.CREATOR_DRAFT_REVIEW:
            if (
                creator_context is None
                or type(creator_context.schema_version) is not int
                or creator_context.schema_version != 1
            ):
                raise ValueError("creator_context_required")
            draft = creator_context.draft
            if (
                not draft.name
                or draft.name != draft.name.strip()
                or len(draft.name) > 100
                or type(draft.daily_limit) is not int
                or not 1 <= draft.daily_limit <= 2_147_483_647
                or not 1 <= len(draft.prizes) <= 50
                or draft.starts_at.tzinfo is None
                or draft.starts_at.utcoffset() is None
                or draft.ends_at.tzinfo is None
                or draft.ends_at.utcoffset() is None
            ):
                raise ValueError("creator_context_invalid")
            for prize in draft.prizes:
                if (
                    type(prize.prize_id) is not int
                    or not 1 <= prize.prize_id <= 9_223_372_036_854_775_807
                    or not prize.name
                    or prize.name != prize.name.strip()
                    or len(prize.name) > 100
                    or type(prize.total_stock) is not int
                    or not 0 <= prize.total_stock <= 2_147_483_647
                    or not isinstance(prize.probability, Decimal)
                    or not prize.probability.is_finite()
                    or not Decimal("0") <= prize.probability <= Decimal("1")
                ):
                    raise ValueError("creator_context_invalid")

        if mode is AgentRunMode.CREATOR_DRAFT_REVIEW:
            assert creator_context is not None
            fingerprint = creator_run_fingerprint(
                expected_version=expected_version,
                normalized_input=normalized_input,
                context=creator_context,
            )
            return normalized_input, fingerprint

        canonical = json.dumps(
            {"expectedVersion": expected_version, "input": normalized_input},
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        fingerprint = hashlib.sha256(b"prizeflux-agent-run:v1\x00" + canonical).hexdigest()
        return normalized_input, fingerprint
