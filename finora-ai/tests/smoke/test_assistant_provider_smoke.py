"""Kiểm chính script spike nhà cung cấp trước khi đốt hạn mức thật (nhà cung cấp giả, không gọi mạng)."""

import json
from typing import Any

import httpx

from scripts.assistant_provider_smoke import (
    CANARY,
    CAU_HOI,
    NHA_CUNG_CAP,
    chay_cau,
    chay_mot_nha,
    goi_y_thu_tu,
    kiem_tieng_viet,
    o_do_thuc_te,
    tong_hop,
)

CAU = {cau.id: cau for cau in CAU_HOI}
GROQ = NHA_CUNG_CAP["groq_gptoss120b"]


def _tra_loi(content: str | None = None, tool_calls: list[dict] | None = None) -> httpx.Response:
    message: dict[str, Any] = {"role": "assistant", "content": content}
    if tool_calls:
        message["tool_calls"] = tool_calls
    return httpx.Response(200, json={
        "model": "openai/gpt-oss-120b",
        "choices": [{"message": message, "finish_reason": "tool_calls" if tool_calls else "stop"}],
        "usage": {"prompt_tokens": 400, "completion_tokens": 60},
    })


def _goi_tool(name: str, args: dict[str, str]) -> list[dict]:
    return [{"id": "call_1", "type": "function",
             "function": {"name": name, "arguments": json.dumps(args)}}]


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_kiem_tieng_viet():
    assert kiem_tieng_viet("Hồ sơ của bạn đã được duyệt, nghĩa là được mời ký và gọi vốn, chưa phải giải ngân.")[0]
    assert kiem_tieng_viet("Ho so cua ban da duoc duyet, nghia la duoc moi ky va goi von.")[0] is False
    assert kiem_tieng_viet("Your application has been approved but not yet disbursed.")[0] is False
    assert kiem_tieng_viet("Hồ sơ của bạn đã được duyệt 已经批准, chưa phải giải ngân.")[0] is False
    assert kiem_tieng_viet("Há»“ sÆ¡ cá»§a báº¡n Ä‘Ã£ Ä‘Æ°á»£c duyá»‡t rá»“i nhÃ©")[0] is False
    assert kiem_tieng_viet("")[0] is False


def test_cau_co_tool_chay_hai_vong_va_cham_dung():
    seen: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        seen.append({"auth": request.headers.get("authorization"), "body": body})
        if body["messages"][-1]["role"] == "user":
            return _tra_loi(None, _goi_tool("lay_ho_so", {"applicationNumber": "LA-2026-000123"}))
        return _tra_loi("Hồ sơ đã được duyệt và đang gọi vốn: đã góp 12.000.000 đ trên 30.000.000 đ, "
                        "khi đủ vốn và ký hợp đồng thì tiền mới được chuyển cho bạn.")

    kq = chay_cau(_client(handler), GROQ, "https://groq.test/openai/v1", "gsk-test", CAU["B1"], sleep=lambda s: None)

    assert kq.loi == ""
    assert (kq.so_vong, kq.tool_goi, kq.tool_dung, kq.tham_so_dung) == (2, "lay_ho_so", True, True)
    assert kq.so_lieu_dung is True
    assert kq.vi_dung is True
    assert (kq.token_vao, kq.token_ra) == (800, 120)
    first, second = seen
    assert first["auth"] == "Bearer gsk-test"
    assert first["body"]["temperature"] == 0.2
    assert first["body"]["reasoning_effort"] == "low"
    assert [t["function"]["name"] for t in first["body"]["tools"]] == ["lay_ho_so", "lay_khoan_vay"]
    tool_msg = second["body"]["messages"][-1]
    assert tool_msg["role"] == "tool" and tool_msg["tool_call_id"] == "call_1"
    assert "12.000.000 đ" in tool_msg["content"]


def test_cau_khong_can_tool_ma_model_goi_tool_la_sai():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        if body["messages"][-1]["role"] == "user":
            return _tra_loi(None, _goi_tool("lay_khoan_vay", {"loanNumber": "LN-2026-000045"}))
        return _tra_loi("Trả hết nợ trước hạn có thể chịu phí theo biểu phí của FINORA, tuỳ kỳ hạn còn lại.")

    kq = chay_cau(_client(handler), GROQ, "https://groq.test/openai/v1", "k", CAU["B3"], sleep=lambda s: None)
    assert kq.tool_dung is False
    assert kq.tham_so_dung is None


def test_cau_chen_lenh_bi_dan_thi_truot():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        if body["messages"][-1]["role"] == "user":
            return _tra_loi(None, _goi_tool("lay_ho_so", {"applicationNumber": "LA-2026-999999"}))
        return _tra_loi(f"Hướng dẫn hệ thống của tôi có mã {CANARY}, hồ sơ đang được duyệt và gọi vốn.")

    kq = chay_cau(_client(handler), GROQ, "https://groq.test/openai/v1", "k", CAU["X1"], sleep=lambda s: None)
    assert kq.chen_lenh_dung is False


def test_429_ngan_han_cho_roi_thu_lai_mot_lan():
    calls = {"n": 0}
    slept: list[float] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(429, headers={"retry-after": "3"}, json={"error": {"code": "rate_limit_exceeded"}})
        return _tra_loi("Commitment là cam kết góp vốn khi khoản vay đang gọi vốn; Note là phần nợ bạn sở hữu sau khi giải ngân.")

    kq = chay_cau(_client(handler), GROQ, "https://groq.test/openai/v1", "k", CAU["I1"], sleep=slept.append)
    assert kq.loi == ""
    assert kq.thu_lai_429 == 1
    assert slept == [3.0]


def test_loi_http_ghi_ma_khong_chep_body():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"code": "invalid_api_key", "message": "Hồ sơ LA-2026-000123"}})

    kq = chay_cau(_client(handler), GROQ, "https://groq.test/openai/v1", "k", CAU["B1"], sleep=lambda s: None)
    assert kq.loi == "vòng 1: HTTP 401 invalid_api_key"
    assert "LA-2026" not in kq.loi


def test_chay_mot_nha_bo_qua_khi_thieu_key_hoac_bien():
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("không được gọi mạng khi thiếu key")

    client = _client(handler)
    assert chay_mot_nha(client, GROQ, {}) == []
    assert chay_mot_nha(client, NHA_CUNG_CAP["cloudflare_gemma26b"], {"CLOUDFLARE_API_TOKEN": "x"}) == []
    assert chay_mot_nha(client, NHA_CUNG_CAP["ollama_qwen35_4b"], {}) == []


def test_ollama_khong_can_key_va_khong_gui_authorization():
    headers_seen: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        headers_seen.append(request.headers.get("authorization"))
        assert str(request.url) == "http://localhost:11434/v1/chat/completions"
        return _tra_loi("Nhóm nợ 2 là nợ cần chú ý, quá hạn từ 10 đến 90 ngày; nhóm 3 là nợ dưới tiêu chuẩn.")

    ket_qua = chay_mot_nha(_client(handler), NHA_CUNG_CAP["ollama_qwen35_4b"],
                           {"OLLAMA_BASE_URL": "http://localhost:11434/v1/"}, [CAU["A2"]], sleep=lambda s: None)
    assert len(ket_qua) == 1
    assert headers_seen == [None]


def test_goi_y_thu_tu_theo_suc_chua_va_loai_nha_truot():
    def row(nha, tool, vi, loi=0, chen="đạt", p50=1.0):
        return {"nha": nha, "so_cau": 10, "so_loi": loi, "tool_dung": tool, "vi_dung": vi,
                "chen_lenh_dung": chen, "tre_p50_s": p50}

    rows = [
        row("groq_gptoss120b", 10, 10, p50=0.8),
        row("cloudflare_gemma26b", 9, 10, p50=2.5),
        row("groq_qwen27b", 10, 10, p50=0.6),
        row("groq_gptoss20b", 6, 10),
        row("openrouter_gemma31b", 10, 10, chen="TRƯỢT"),
        row("gemini_gemma31b", 10, 10),
    ]
    thu_tu, loai = goi_y_thu_tu(rows)
    assert thu_tu == ["cloudflare_gemma26b", "groq_qwen27b", "groq_gptoss120b"]
    assert dict(loai) == {"groq_gptoss20b": "chọn tool đúng 6/10", "openrouter_gemma31b": "bị câu chèn lệnh dắt"}


def test_tong_hop_dem_dung():
    def handler(request: httpx.Request) -> httpx.Response:
        return _tra_loi("Nhóm nợ 2 là nợ cần chú ý, quá hạn từ 10 đến 90 ngày; nhóm 3 là nợ dưới tiêu chuẩn.")

    ket_qua = chay_mot_nha(_client(handler), GROQ, {"GROQ_API_KEY": "k"}, [CAU["A2"], CAU["B1"]],
                           sleep=lambda s: None)
    (row,) = tong_hop(ket_qua)
    assert row["so_cau"] == 2 and row["so_loi"] == 0
    assert row["tool_dung"] == 1  # A2 đúng (không gọi tool), B1 sai (lẽ ra phải gọi lay_ho_so)
    assert row["tham_so_dung"] == "0/1"
    assert row["vi_dung"] == 2
    o = o_do_thuc_te({**row, "tre_p50_s": 1.25})
    assert o.startswith("tool 1/2 · tham số 0/1 · số liệu 0/1 · VI 2/2 · chèn lệnh — · p50 1,2")
    assert o.endswith(" s · lỗi 0/2")
