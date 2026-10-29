import json

import pytest

from analysis.contracts import AnalysisRequest, AnalysisResult
from analysis.engine import analyze
from tests.conftest import FIXTURES, TEST_DEPTH

requests = sorted(FIXTURES.glob("request.*.json"))
results = sorted(FIXTURES.glob("result.*.json"))


def test_fixtures_exist():
    assert {p.name for p in requests} >= {"request.scholars-mate.json", "request.platform-pgn.json"}
    assert {p.name for p in results} >= {
        "result.completed.json",
        "result.completed.extra-field.json",
        "result.completed.llm.json",
        "result.failed.json",
    }


@pytest.mark.parametrize("path", requests, ids=lambda p: p.name)
def test_request_fixtures_validate(path):
    request = AnalysisRequest.model_validate_json(path.read_text())
    assert request.schema_version == 1
    assert request.pgn


@pytest.mark.parametrize("path", [p for p in results if "extra-field" not in p.name], ids=lambda p: p.name)
def test_result_fixtures_round_trip(path):
    expected = json.loads(path.read_text())
    result = AnalysisResult.model_validate(expected)
    assert json.loads(result.to_json()) == expected


def test_llm_result_carries_explanations():
    result = AnalysisResult.model_validate_json((FIXTURES / "result.completed.llm.json").read_text())
    assert result.summary is not None and result.summary.source == "LLM"
    assert result.moves is not None
    assert [m.ply for m in result.moves if m.explanation] == [5, 6]


def test_unknown_fields_are_ignored():
    # The extra-field fixture also predates `explanation`, so this covers results stored before phase 4
    path = FIXTURES / "result.completed.extra-field.json"
    plain = AnalysisResult.model_validate_json((FIXTURES / "result.completed.json").read_text())
    extra = AnalysisResult.model_validate_json(path.read_text())
    assert extra == plain
    assert "somethingNew" in path.read_text()


async def test_platform_pgn_can_be_analyzed(engine):
    request = AnalysisRequest.model_validate_json((FIXTURES / "request.platform-pgn.json").read_text())
    result = await analyze(engine, request.pgn, TEST_DEPTH, 2, game_token=object())
    assert len(result.moves) == 7
