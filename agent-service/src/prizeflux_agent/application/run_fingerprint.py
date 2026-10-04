import hashlib
import json
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation

from prizeflux_agent.domain.campaign import CampaignDraft, PrizeDraft
from prizeflux_agent.domain.conversation import CreatorDraftContext


def creator_context_payload(context: CreatorDraftContext) -> dict[str, object]:
    if type(context.schema_version) is not int or context.schema_version != 1:
        raise ValueError("creator_context_schema_unsupported")
    draft = context.draft
    return {
        "schemaVersion": context.schema_version,
        "draft": {
            "name": draft.name,
            "startsAt": _utc_timestamp(draft.starts_at),
            "endsAt": _utc_timestamp(draft.ends_at),
            "dailyLimit": draft.daily_limit,
            "prizes": [
                {
                    "id": prize.prize_id,
                    "name": prize.name,
                    "totalStock": prize.total_stock,
                    "probability": _decimal_string(prize.probability),
                }
                for prize in draft.prizes
            ],
        },
    }


def creator_context_from_payload(raw: dict[str, object]) -> CreatorDraftContext:
    if (
        set(raw) != {"schemaVersion", "draft"}
        or type(raw.get("schemaVersion")) is not int
        or raw.get("schemaVersion") != 1
    ):
        raise ValueError("persisted_creator_context_invalid")
    draft = raw.get("draft")
    if not isinstance(draft, dict) or set(draft) != {
        "name",
        "startsAt",
        "endsAt",
        "dailyLimit",
        "prizes",
    }:
        raise ValueError("persisted_creator_context_invalid")
    prizes = draft.get("prizes")
    if not isinstance(prizes, list) or not 1 <= len(prizes) <= 50:
        raise ValueError("persisted_creator_context_invalid")
    if any(
        not isinstance(item, dict)
        or set(item) != {"id", "name", "totalStock", "probability"}
        for item in prizes
    ):
        raise ValueError("persisted_creator_context_invalid")
    try:
        domain_prizes = tuple(
            PrizeDraft(
                prize_id=_stored_int(item, "id", 1, 9_223_372_036_854_775_807),
                name=_stored_text(item, "name", 100),
                total_stock=_stored_int(item, "totalStock", 0, 2_147_483_647),
                probability=_stored_decimal(item, "probability"),
            )
            for item in prizes
        )
        starts_at = _stored_timestamp(draft.get("startsAt"))
        ends_at = _stored_timestamp(draft.get("endsAt"))
        return CreatorDraftContext(
            schema_version=1,
            draft=CampaignDraft(
                name=_stored_text(draft, "name", 100),
                starts_at=starts_at,
                ends_at=ends_at,
                daily_limit=_stored_int(draft, "dailyLimit", 1, 2_147_483_647),
                prizes=domain_prizes,
            ),
        )
    except (KeyError, TypeError, InvalidOperation, ValueError) as exc:
        raise ValueError("persisted_creator_context_invalid") from exc


def _stored_int(raw: object, key: str, minimum: int, maximum: int) -> int:
    if not isinstance(raw, dict):
        raise ValueError("persisted_creator_context_invalid")
    value = raw.get(key)
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("persisted_creator_context_invalid")
    return value


def _stored_text(raw: object, key: str, maximum: int) -> str:
    if not isinstance(raw, dict):
        raise ValueError("persisted_creator_context_invalid")
    value = raw.get(key)
    if (
        not isinstance(value, str)
        or not value.strip()
        or value != value.strip()
        or len(value) > maximum
    ):
        raise ValueError("persisted_creator_context_invalid")
    return value


def _stored_decimal(raw: object, key: str) -> Decimal:
    if not isinstance(raw, dict):
        raise ValueError("persisted_creator_context_invalid")
    value = raw.get(key)
    if not isinstance(value, str):
        raise ValueError("persisted_creator_context_invalid")
    parsed = Decimal(value)
    if not parsed.is_finite() or not Decimal("0") <= parsed <= Decimal("1"):
        raise ValueError("persisted_creator_context_invalid")
    if _decimal_string(parsed) != value:
        raise ValueError("persisted_creator_context_invalid")
    return parsed


def _stored_timestamp(value: object) -> datetime:
    if not isinstance(value, str):
        raise ValueError("persisted_creator_context_invalid")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if (
        parsed.tzinfo is None
        or parsed.utcoffset() is None
        or _utc_timestamp(parsed) != value
    ):
        raise ValueError("persisted_creator_context_invalid")
    return parsed


def creator_draft_fingerprint(context: CreatorDraftContext) -> str:
    payload = creator_context_payload(context)["draft"]
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(b"prizeflux-agent-creator-draft:v1\x00" + canonical).hexdigest()


def creator_run_fingerprint(
    *,
    expected_version: int,
    normalized_input: str,
    context: CreatorDraftContext,
) -> str:
    canonical = json.dumps(
        {
            "context": creator_context_payload(context),
            "expectedVersion": expected_version,
            "input": normalized_input,
            "mode": "CREATOR_DRAFT_REVIEW",
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    if len(canonical) > 64 * 1024:
        raise ValueError("creator_run_request_too_large")
    return hashlib.sha256(b"prizeflux-agent-run:v2\x00" + canonical).hexdigest()


def _utc_timestamp(value: object) -> str:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("creator_context_timestamp_invalid")
    normalized = value.astimezone(UTC).isoformat(timespec="microseconds")
    date, fraction = normalized[:-6].split(".")
    fraction = fraction.rstrip("0")
    return f"{date}.{fraction}Z" if fraction else f"{date}Z"


def _decimal_string(value: Decimal) -> str:
    if not value.is_finite():
        raise ValueError("creator_context_probability_invalid")
    if value == 0:
        return "0"
    sign, raw_digits, raw_exponent = value.as_tuple()
    if not isinstance(raw_exponent, int):
        raise ValueError("creator_context_probability_invalid")
    exponent = raw_exponent
    digits = list(raw_digits)
    while digits and digits[-1] == 0:
        digits.pop()
        exponent += 1
    if sign or not digits:
        raise ValueError("creator_context_probability_invalid")

    digit_text = "".join(str(digit) for digit in digits)
    decimal_point = len(digit_text) + exponent
    if decimal_point <= 0:
        output_length = 2 - decimal_point + len(digit_text)
        if output_length > 64 * 1024:
            raise ValueError("creator_context_probability_scale_too_large")
        return f"0.{('0' * -decimal_point)}{digit_text}"
    if decimal_point >= len(digit_text):
        output_length = decimal_point
        if output_length > 64 * 1024:
            raise ValueError("creator_context_probability_scale_too_large")
        return digit_text + ("0" * (decimal_point - len(digit_text)))
    return f"{digit_text[:decimal_point]}.{digit_text[decimal_point:]}"
