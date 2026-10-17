"""Test log siêu dữ liệu: đủ trường để đo, không lộ user id/câu hỏi."""

import json
import logging

from app.services.assistant.audit import hash_user, log_feedback, log_turn


def _ban_ghi(caplog, su_kien):
    dong = [r.getMessage() for r in caplog.records if r.name == "finora.assistant.audit"]
    assert dong, "không có dòng log nào"
    ten, json_text = dong[-1].split(" ", 1)
    assert ten == su_kien
    return json.loads(json_text)


def test_hash_user_on_dinh_va_phu_thuoc_muoi():
    assert hash_user("27", "muoi-a") == hash_user("27", "muoi-a")
    assert hash_user("27", "muoi-a") != hash_user("27", "muoi-b")
    assert hash_user("27", "muoi-a") != hash_user("28", "muoi-a")
    assert len(hash_user("27", "muoi-a")) == 64  # SHA-256 dạng hex


def test_log_turn_ghi_du_truong(caplog):
    caplog.set_level(logging.INFO, logger="finora.assistant.audit")
    log_turn(
        turn_id="0f9c3c1e-1111-2222-3333-444455556666",
        user_hash="abc",
        toolsets=["INVESTOR", "BORROWER"],
        surface="mobile",
        screen="ApplicationDetail",
        mode="llm",
        tools=["chi_tiet_ho_so"],
        provider="groq_gptoss120b",
        model="openai/gpt-oss-120b",
        prompt_tokens=1200,
        completion_tokens=150,
        latency_ms=2300,
        error_code=None,
    )
    ban_ghi = _ban_ghi(caplog, "assistant_turn")
    assert ban_ghi == {
        "event": "assistant_turn",
        "turnId": "0f9c3c1e-1111-2222-3333-444455556666",
        "user": "abc",
        "toolsets": ["BORROWER", "INVESTOR"],
        "surface": "mobile",
        "screen": "ApplicationDetail",
        "mode": "llm",
        "tools": ["chi_tiet_ho_so"],
        "provider": "groq_gptoss120b",
        "model": "openai/gpt-oss-120b",
        "promptTokens": 1200,
        "completionTokens": 150,
        "latencyMs": 2300,
        "errorCode": None,
    }


def test_man_hinh_la_thi_khong_ghi(caplog):
    caplog.set_level(logging.INFO, logger="finora.assistant.audit")
    log_turn(
        turn_id="t", user_hash="abc", toolsets=[], surface="mobile", screen="0912345678 xin chao", mode="template",
        tools=[], provider=None, model=None, prompt_tokens=0, completion_tokens=0, latency_ms=1, error_code=None,
    )
    assert _ban_ghi(caplog, "assistant_turn")["screen"] is None


def test_log_feedback_chi_ghi_danh_gia(caplog):
    caplog.set_level(logging.INFO, logger="finora.assistant.audit")
    log_feedback(turn_id="0f9c3c1e-1111-2222-3333-444455556666", user_hash="abc", rating="down")
    assert _ban_ghi(caplog, "assistant_feedback") == {
        "event": "assistant_feedback",
        "turnId": "0f9c3c1e-1111-2222-3333-444455556666",
        "user": "abc",
        "rating": "down",
    }
    log_feedback(turn_id="cau hoi bi nhet vao day", user_hash="abc", rating="up")
    assert _ban_ghi(caplog, "assistant_feedback")["turnId"] is None
