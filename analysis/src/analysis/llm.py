"""LLM explanations (spec §10): engine facts in, a summary and short explanations of the worst mistakes out.

The prompt holds only engine facts, never PGN headers or usernames. Any failure (an error, a timeout, output
that is still invalid after one repair call) falls back to the template summary: the job never fails here.
"""

import asyncio
import json
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass

import httpx2
from openai import AsyncOpenAI
from openai.types.chat import ChatCompletionMessageParam
from pydantic import BaseModel, Field

from analysis.contracts import MoveAnalysis, Summary
from analysis.engine import Analyzed, fmt_eval, numbered

log = logging.getLogger(__name__)

# The worst mistakes get an explanation. Blunders lose the most, so every blunder is among them unless there
# are more than this.
MAX_EXPLAINED = 5
CALL_TIMEOUT_SECONDS = 20
TOTAL_TIMEOUT_SECONDS = 30
# After this many failed jobs in a row, the LLM is skipped for BREAKER_SECONDS
BREAKER_FAILURES = 3
BREAKER_SECONDS = 60

ERRORS = ("INACCURACY", "MISTAKE", "BLUNDER")

SYSTEM_PROMPT = """\
You explain a chess game to the players, using only the engine facts in the user's JSON.
Reply with one JSON object: {"summary": string, "explanations": [{"move": string, "text": string}]}.
- "summary": at most 80 words. How the game went, its turning point and the result.
- "explanations": exactly one for each move listed in "explain", with "move" written as it is there, each \
text at most 40 words. Say why the move was bad and what the best move does instead.
- Write a move only as the facts write it, with its number ("12.Nf3", "12...Nf6"), and only a move that was \
played or that the facts give as the best move instead of it. Use "bestLine" to explain, but do not write \
its later moves.
- Plain text only: no markdown, no player names.
"""


class Explanation(BaseModel):
    move: str
    text: str = Field(min_length=1)


class LlmOutput(BaseModel):
    summary: str = Field(min_length=1)
    explanations: list[Explanation]


def to_explain(moves: list[MoveAnalysis]) -> dict[str, int]:
    """The moves to explain, as numbered SAN, with their plies: the MAX_EXPLAINED largest win-chance losses
    among the mistakes, in game order.

    The model only sees the labels. Given a bare ply next to "8...c4", a small model wrote "16...c4".
    """
    mistakes = [m for m in moves if m.classification in ERRORS]
    worst = sorted(mistakes, key=lambda m: m.win_chance_loss, reverse=True)[:MAX_EXPLAINED]
    return {numbered(m.ply, m.san): m.ply for m in sorted(worst, key=lambda m: m.ply)}


def facts(analyzed: Analyzed) -> dict[str, object]:
    """The prompt's only input. The template summary is engine facts too: counts, turning point, result."""
    moves = analyzed.moves
    mistakes = []
    for m in moves:
        if m.classification not in ERRORS:
            continue
        before = moves[m.ply - 2].eval if m.ply > 1 else analyzed.initial_eval
        mistakes.append(
            {
                "side": "White" if m.ply % 2 == 1 else "Black",
                "played": numbered(m.ply, m.san),
                "best": numbered(m.ply, m.best_san),
                "evalBefore": fmt_eval(before),
                "evalAfter": fmt_eval(m.eval),
                "classification": m.classification,
                "bestLine": analyzed.best_lines[m.ply - 1],
            }
        )
    return {
        "evalsAreFrom": "White's point of view, in pawns",
        "engineSummary": analyzed.summary.text,
        "mistakes": mistakes,
        "explain": list(to_explain(moves)),
    }


def _key(label: str) -> str:
    """`8... c4?` and `8...c4` name the same move."""
    return re.sub(r"\s+", "", label).rstrip("?!+#")


def parse(content: str, wanted: dict[str, int]) -> tuple[str, dict[int, str]]:
    """The summary, and each explanation by ply. Raises ValueError (as a pydantic ValidationError does) if the
    output does not fit, including when it does not explain exactly the `wanted` moves."""
    output = LlmOutput.model_validate_json(content)
    plies = {_key(label): ply for label, ply in wanted.items()}
    explanations = {}
    for e in output.explanations:
        if _key(e.move) not in plies:
            raise ValueError(f"{e.move!r} is not one of the moves to explain: {', '.join(wanted)}")
        explanations[plies[_key(e.move)]] = e.text.strip()
    if len(explanations) != len(wanted):
        raise ValueError(f"explain each of these moves once: {', '.join(wanted)}")
    return output.summary.strip(), explanations


@dataclass
class Attempt:
    """What one job's LLM step did, for the eval (L1, L5)."""

    first_call_valid: bool = False
    seconds: float = 0.0
    tokens: int = 0


@dataclass(frozen=True)
class Explained:
    summary: Summary
    explanations: dict[int, str]


class Explainer:
    def __init__(
        self,
        base_url: str,
        model: str,
        api_key: str,
        http_client: httpx2.AsyncClient | None = None,
        clock: Callable[[], float] = time.monotonic,
    ):
        # The SDK retries once on a connection error, a timeout, 429 or 5xx
        self._client = AsyncOpenAI(
            base_url=base_url,
            api_key=api_key or "unused",  # Ollama and Docker Model Runner need none
            timeout=CALL_TIMEOUT_SECONDS,
            max_retries=1,
            http_client=http_client,
        )
        self._model = model
        self._clock = clock
        self._failures = 0
        self._skip_until = float("-inf")
        self.last: Attempt | None = None

    async def explain(
        self, analyzed: Analyzed, seconds_left: float = TOTAL_TIMEOUT_SECONDS
    ) -> Explained | None:
        """The LLM's summary and explanations, or None to keep the template summary.

        `seconds_left` is how long the job has until its deadline; the step stops then at the latest.
        """
        self.last = None
        if self._clock() < self._skip_until:
            return None
        attempt = self.last = Attempt()
        start = self._clock()
        try:
            async with asyncio.timeout(min(seconds_left, TOTAL_TIMEOUT_SECONDS)):
                explained = await self._explain(analyzed, attempt)
        except Exception as e:  # noqa: BLE001 - whatever the LLM does, the job completes
            log.warning("LLM step failed, using the template summary: %r", e)
            self._failures += 1
            if self._failures >= BREAKER_FAILURES:
                # Stays at the threshold, so a failed call after the pause skips again at once
                self._skip_until = self._clock() + BREAKER_SECONDS
            return None
        finally:
            attempt.seconds = self._clock() - start
        self._failures = 0
        return explained

    async def _explain(self, analyzed: Analyzed, attempt: Attempt) -> Explained:
        wanted = to_explain(analyzed.moves)
        messages: list[ChatCompletionMessageParam] = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps(facts(analyzed))},
        ]
        content = await self._complete(messages, attempt)
        try:
            summary, explanations = parse(content, wanted)
            attempt.first_call_valid = True
        except ValueError as e:
            log.info("Invalid LLM output, asking for a repair: %s", e)
            messages.append({"role": "assistant", "content": content})
            messages.append(
                {"role": "user", "content": f"That reply is invalid: {e}\nReply with only the JSON."}
            )
            summary, explanations = parse(await self._complete(messages, attempt), wanted)
        return Explained(summary=Summary(text=summary, source="LLM"), explanations=explanations)

    async def _complete(self, messages: list[ChatCompletionMessageParam], attempt: Attempt) -> str:
        response = await self._client.chat.completions.create(
            model=self._model,
            messages=messages,
            response_format={"type": "json_object"},
            temperature=0,
        )
        if response.usage is not None:
            attempt.tokens += response.usage.total_tokens
        return response.choices[0].message.content or ""
