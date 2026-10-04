from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal


@dataclass(frozen=True, slots=True)
class CampaignSummary:
    campaign_id: int
    name: str
    status: str

    def __post_init__(self) -> None:
        if self.campaign_id <= 0 or not self.name.strip() or not self.status.strip():
            raise ValueError("campaign summary fields are invalid")


@dataclass(frozen=True, slots=True)
class PrizeDraft:
    prize_id: int
    name: str
    total_stock: int
    probability: Decimal


@dataclass(frozen=True, slots=True)
class CampaignDraft:
    name: str
    starts_at: datetime
    ends_at: datetime
    daily_limit: int
    prizes: tuple[PrizeDraft, ...]


@dataclass(frozen=True, slots=True)
class ValidationFinding:
    code: str
    message: str
    field: str | None = None


@dataclass(frozen=True, slots=True)
class DraftValidationReport:
    valid: bool
    findings: tuple[ValidationFinding, ...]
