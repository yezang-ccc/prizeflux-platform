from dataclasses import dataclass
from enum import StrEnum


class Role(StrEnum):
    USER = "USER"
    OPERATOR = "OPERATOR"


@dataclass(frozen=True, slots=True)
class Actor:
    user_id: int
    role: Role

    def __post_init__(self) -> None:
        if self.user_id <= 0:
            raise ValueError("user_id must be positive")
