"""Wire contracts shared with the Spring backend (schemaVersion 1). JSON is camelCase on the wire."""

from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

SCHEMA_VERSION = 1

Classification = Literal["BEST", "GOOD", "INACCURACY", "MISTAKE", "BLUNDER"]
ErrorCode = Literal[
    "INVALID_PGN",
    "TOO_LONG",
    "UNSUPPORTED_SCHEMA_VERSION",
    "ENGINE_ERROR",
    "BUDGET_EXCEEDED",
]


class Contract(BaseModel):
    # Unknown fields are ignored: additive changes keep the schema version
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


class AnalysisRequest(Contract):
    schema_version: int
    job_id: UUID
    match_id: UUID
    pgn: str
    deadline: datetime


class Eval(Contract):
    """From White's point of view; exactly one of cp and mate is set."""

    cp: int | None = None
    mate: int | None = None


class EngineInfo(Contract):
    name: str
    depth: int


class MoveAnalysis(Contract):
    ply: int
    san: str
    uci: str
    eval: Eval
    best_san: str
    best_uci: str
    win_chance_loss: float
    classification: Classification
    # The LLM's plain-text explanation of a mistake; added in phase 4, so older results lack it
    explanation: str | None = None


class Summary(Contract):
    text: str
    source: Literal["TEMPLATE", "LLM"]


class ErrorInfo(Contract):
    code: ErrorCode
    message: str


class AnalysisResult(Contract):
    schema_version: int = SCHEMA_VERSION
    job_id: UUID
    match_id: UUID
    status: Literal["COMPLETED", "FAILED"]
    engine: EngineInfo | None = None
    initial_eval: Eval | None = None
    moves: list[MoveAnalysis] | None = None
    summary: Summary | None = None
    error: ErrorInfo | None = None

    def to_json(self) -> bytes:
        return self.model_dump_json(by_alias=True).encode()
