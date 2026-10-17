"""Bộ điều phối của Trợ lý FINORA: một lượt hỏi đáp đi từ câu hỏi tới phản hồi (spec mục 4).

Thứ tự xử lý cố ý đi từ rẻ tới đắt, vì hạn mức LLM miễn phí rất ít (vài trăm lượt/ngày):

A. Nút gợi ý (`suggestionId`) → chạy đúng công cụ của gợi ý, dựng câu bằng mẫu. Không gọi LLM.
   (Nhà đầu tư xin khuyến nghị → câu từ chối mẫu, kiểm ngay sau A để áp cho mọi đường còn lại.)
B. Câu hỏi gắn màn hình → lấy dữ liệu màn đó + tối đa 3 đoạn kiến thức, gọi LLM MỘT lần, không kèm công cụ.
C. Câu tự do → khớp từ khóa với gợi ý (như A) → câu chính sách thuần thì gọi LLM làn PUBLIC một lần
   → còn lại cho LLM gọi công cụ của vai trò, tối đa 3 vòng.

Mọi câu trả lời của LLM đi qua `guard.check_reply`; sai thì thay bằng câu dựng từ dữ kiện, vì nghĩa vụ
"thông tin chính xác" (LAW-P2P-2025) quan trọng hơn câu văn hay. Nút điều hướng, thẻ số liệu và căn cứ
luôn do code dựng; LLM chỉ được chọn khóa hành động trong bảng cố định.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import time
import traceback
import unicodedata
import uuid
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Any

import httpx
from pydantic import BaseModel, ValidationError

from app.api.errors import ApiError
from app.schemas.assistant import (
    Action,
    Card,
    CardItem,
    ChatRequest,
    ChatResponse,
    Citation,
    SuggestionItem,
)
from app.services.assistant import guard
from app.services.assistant.audit import hash_user, log_turn
from app.services.assistant.deeplinks import ACTIONS, allowed_action_keys, build_action
from app.services.assistant.glossary import CHUA_CO_DIEN_GIAI, dich_ma_loi
from app.services.assistant.knowledge.bm25 import Bm25Index, get_index
from app.services.assistant.knowledge.store import KnowledgeChunk
from app.services.assistant.legal import (
    LegalSource,
    citations_for_chunk,
    load_legal_sources,
)
from app.services.assistant.llm.providers import Lane
from app.services.assistant.llm.router import (
    LlmResult,
    LlmRouter,
    LlmUnavailable,
    get_llm_router,
)
from app.services.assistant.principal import Toolset
from app.services.assistant.prompts import (
    build_fact_block,
    build_system_prompt,
    parse_model_output,
)
from app.services.assistant.relay import FinoraApiClient
from app.services.assistant.settings import AssistantSettings, get_settings
from app.services.assistant.thao_tac import (
    ThaoTac,
    cau_tu_choi_thao_tac,
    detect_action_request,
)
from app.services.assistant.tinh_toan import (
    CAU_MAU_TINH_VAY,
    doc_yeu_cau_tinh_vay,
    ke_hoach_tinh_vay,
)
from app.services.assistant.tools.admin import ADMIN_SUGGESTIONS
from app.services.assistant.tools.base import (
    SuggestionDef,
    ToolError,
    ToolResult,
    ToolSpec,
)
from app.services.assistant.tools.borrower import BORROWER_SUGGESTIONS
from app.services.assistant.tools.investor import INVESTOR_SUGGESTIONS
from app.services.assistant.tools.registry import ToolRegistry, default_registry
from app.services.auth.keycloak_jwt import Principal

logger = logging.getLogger(__name__)

THOI_GIAN_CHONG_TRUNG_S = 60.0
SO_MUC_CHONG_TRUNG_TOI_DA = 500
SO_VONG_CONG_CU_TOI_DA = 3
# Một vòng model có thể đòi nhiều công cụ cùng lúc; giới hạn để một lượt không bắn hàng chục request.
SO_GOI_MOI_VONG_TOI_DA = 4
SO_DOAN_KIEN_THUC_MAN_HINH = 3
SO_DOAN_KIEN_THUC_CHINH_SACH = 4
SO_DOAN_KIEN_THUC_CONG_CU = 2
# Đo trên kho thật của Task 8 (70 đoạn): câu đúng chủ đề có đoạn đầu 13–90 điểm ("eKYC là gì?" 13,3;
# "Sao vay tối đa chỉ 24 tháng?" 75,1), câu lạc đề ("Chào bạn", "Thời tiết hôm nay") không có đoạn nào.
# Đoạn đầu dưới ngưỡng thì coi như kho không có câu trả lời. Task 19 đo lại trên bộ đánh giá.
NGUONG_BM25_CHINH_SACH = 12.0
# Chỉ giữ đoạn có điểm ≥ 50% đoạn đầu: đoạn xếp sau thường chỉ khớp vài chữ chung ("tối đa", "phí")
# và nếu giữ sẽ kéo theo căn cứ pháp lý không liên quan vào câu trả lời.
TY_LE_GIU_DOAN = 0.5
# Câu hỏi pháp lý (fix-1, CHAT-014): đoạn LUAT/CHUA_XAC_MINH được nhân điểm để đứng trước đoạn chính sách cùng
# chủ đề. Đo trên kho thật: "Thu phí tất toán như vậy có đúng luật không?" có đoạn chính sách 41,5 điểm, hai
# đoạn pháp lý 38,6/37,7; hệ số 1,5 đưa đoạn pháp lý lên đầu mà đoạn chính sách vượt xa (QT-NGUONG-DUYET-01
# 61,4 với "Điều 8 Thông tư 12/2026…") vẫn đứng đầu.
LOAI_DOAN_PHAP_LY = frozenset({"LUAT", "CHUA_XAC_MINH"})
HE_SO_UU_TIEN_PHAP_LY = 1.5
SO_DOAN_TIM_PHAP_LY = 8
# Câu không có chữ pháp lý nhưng đoạn khớp nhất là đoạn pháp lý điểm cao thì câu trả lời chính là quy định đó
# ("Sao vay tối đa chỉ 24 tháng?" → PL-THOI-HAN-01 75,1 điểm). Câu sai chủ đề chỉ chạm đoạn pháp lý ở mức thấp
# ("Vì sao phải làm eKYC?" → PL-CIC-DU-NO-01 14,7), nên mốc 45 tách được hai nhóm.
NGUONG_DOAN_PHAP_LY_MANH = 45.0
# Câu cần dữ liệu (C3) mà không có LLM: chỉ đưa thân đoạn kiến thức khi đoạn đầu khớp mạnh như trên (fix-2). Đo trên
# chạy thật 16/10: câu chính sách có chữ "tôi" khớp mạnh ("Khoản vay không gọi đủ vốn thì tiền tôi đã góp sẽ thế
# nào?" → 51,9), câu hỏi số liệu của chính mình chỉ khớp vài chữ chung ("Tôi đã trả được bao nhiêu kỳ?" → 16,8, "Tôi
# có bao nhiêu Note của khoản #1?" → 26,6, câu chèn lệnh → 40,8) và trước đây nhận một đoạn không liên quan.
NGUONG_DOAN_MANH_KHONG_LLM = NGUONG_DOAN_PHAP_LY_MANH
SO_THE_TOI_DA = 4
SO_HANH_DONG_TOI_DA = 4
SO_DONG_DU_KIEN_DU_PHONG = 8

# Tên tham số (đúng như LLM thấy) mang mã nghiệp vụ: LLM chỉ được dùng mã đã thấy trong lượt này.
THAM_SO_MA_NGHIEP_VU = frozenset({
    "applicationNumber", "contractNumber", "loanNumber", "listingId", "loanId", "productId",
    "application_number", "contract_number", "loan_number", "listing_id", "loan_id", "product_id",
})
# Màn LoanDetail của app truyền `loanId = String(listingId)` (Task 10), nên hai tên là một mã.
_BI_DANH_THAM_SO: dict[str, tuple[str, ...]] = {"loanId": ("listingId",), "listingId": ("loanId",)}

MIEN_TRU_DAU_TU = (
    "Thông tin chỉ để tham khảo, không phải tư vấn hay khuyến nghị đầu tư. "
    "Bạn tự quyết định và tự chịu rủi ro khi đầu tư."
)
MIEN_TRU_PHAP_LY = "Phần căn cứ pháp luật chỉ để tham khảo, không phải ý kiến pháp lý."
DONG_RUT_GON = "Trợ lý đang ở chế độ rút gọn."
CAU_TU_CHOI_KHUYEN_NGHI = (
    "Mình không thể khuyên bạn nên đầu tư khoản nào hay khoản nào an toàn, lời nhất. "
    "Bạn có thể tự đặt tiêu chí như hạng, lãi suất, kỳ hạn; mình sẽ liệt kê các khoản đang gọi vốn khớp "
    "tiêu chí đó một cách trung lập, không xếp hạng."
)
CAU_QUA_LAU = "Trợ lý trả lời quá lâu. Bạn thử hỏi lại sau ít phút nhé."
CAU_CHUA_DU_THONG_TIN = "Mình chưa có đủ thông tin để trả lời câu này. Bạn thử chọn một gợi ý bên dưới nhé."
CAU_LOI_NOI_BO = "Trợ lý gặp sự cố khi xử lý câu này. Bạn thử lại sau ít phút nhé."
# Câu cần tra dữ liệu (đường C3) mà không có LLM: nói thẳng là chưa làm được thay vì đưa một đoạn kiến thức BM25
# khớp vài chữ chung (chạy thật 16/10/2026: "Tôi đã trả được bao nhiêu kỳ?" nhận đoạn mô tả sản phẩm vay).
CAU_CAN_DU_LIEU_RUT_GON = {
    "mobile": ("Câu này cần tra dữ liệu của bạn mà chế độ rút gọn chưa làm được. Bạn chọn một câu gợi ý bên dưới, "
               "hoặc mở đúng màn trong app rồi bấm “Hỏi trợ lý”."),
    "web": ("Câu này cần tra số liệu mà chế độ rút gọn chưa làm được. Bạn chọn một câu gợi ý bên dưới, hoặc mở "
            "đúng trang quản trị rồi hỏi lại."),
}

# Task 10: mọi `facts` của nhà đầu tư mang cờ `khong_tu_van`; orchestrator gắn căn cứ HUONG_DAN này.
CAN_CU_KHONG_TU_VAN = Citation(
    id="HD-KHONG-TU-VAN",
    kind="HUONG_DAN",
    label="Thông tin tham khảo cho nhà đầu tư, không phải tư vấn hay khuyến nghị đầu tư",
    url=None,
    checked_at="2026-10-15",
)

# Câu hỏi về "của tôi"/"hiện tại" cần dữ liệu thật, không trả lời được chỉ bằng kho kiến thức.
# So trên chữ CÓ dấu: bỏ dấu thì "tối đa" trùng "tôi đã" và câu chính sách bị đẩy nhầm sang đường công cụ.
# Không có "sao tôi": "Sao tôi được xem hồ sơ người vay?" là câu hỏi chính sách.
_DAU_HIEU_DU_LIEU_CA_NHAN = (
    " của tôi ", " của mình ", " của em ", " tôi có ", " mình có ", " tôi đang ", " mình đang ", " tôi đã ",
    " kỳ tới ", " hiện tại ", " hôm nay ", " bây giờ ", " còn bao nhiêu ", " tôi phải ",
)

# Khi công cụ lỗi: nút mở màn gần nhất để người dùng tự xem (tham số lấy từ context/tham số đã gọi).
# `trang_thai_pin` không có nút: bảng hành động không có màn quản lý PIN (Task 9).
_HANH_DONG_KHI_LOI: dict[str, str] = {
    "chi_tiet_ho_so": "mo_ho_so",
    "hop_dong_cua_toi": "mo_hop_dong",
    "khoan_vay_cua_toi": "mo_khoan_vay",
    "vi_cua_toi": "nap_tien",
    "danh_muc_cua_toi": "danh_muc",
    "tien_dang_giu": "lenh_cua_toi",
    "lenh_cua_toi": "lenh_cua_toi",
    "so_lenh": "so_lenh",
    "chi_tiet_khoan": "mo_khoan_san",
    "auto_invest": "auto_invest",
    "hop_dong_dau_tu": "ky_hop_dong_dau_tu",
    "kpi_khoan_vay": "trang_tong_quan",
    "kpi_theo_thoi_gian": "trang_tong_quan",
    "kpi_nguoi_dung": "trang_tong_quan",
    "kpi_dau_tu": "trang_tong_quan",
    "viec_can_lam": "trang_tong_quan",
    "danh_sach_ho_so": "hang_doi_tham_dinh",
    "tom_tat_ho_so": "mo_tham_dinh",
    "chinh_sach_ai": "chinh_sach_ai",
}

# Tên màn do client gửi chỉ được đưa vào prompt khi đúng khuôn tên màn (không chứa câu chèn lệnh).
_TEN_MAN_HOP_LE = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,39}")

# `context.lastErrorCode` là chuỗi tự do tới 80 ký tự từ client: chỉ nhận khuôn mã API (PIN_LOCKED…),
# mọi thứ khác bị bỏ để không thành đường chèn lệnh vào prompt.
_MA_LOI_HOP_LE = re.compile(r"[A-Z][A-Z0-9_]{2,79}")
# Mã người dùng chép vào câu hỏi ("Mã AI_POLICY_REQUIRES_REVIEW nghĩa là gì?").
_MA_LOI_TRONG_CAU = re.compile(r"(?<![A-Za-z0-9_])[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+(?![A-Za-z0-9_])")
SO_MA_GIAI_THICH_TOI_DA = 3
# Mã hồ sơ/hợp đồng/khoản vay (LA-/LC-/LN- + 20 ký tự hex ở Loan) trong câu trả lời của LLM.
_MA_NGHIEP_VU_TRONG_CAU = re.compile(r"(?<![A-Za-z0-9-])(?:LA|LC|LN)-[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
# Mã đã thấy trong lịch sử hội thoại chỉ được tính khi có cả chữ và số (LA-…): số trần ("12 tháng",
# "2.350.000") có mặt khắp nơi, tính chúng thì mã đợt gọi vốn kiểu số coi như luôn "đã thấy".
_CO_CHU = re.compile(r"[A-Za-z]")

# ── Câu hỏi pháp lý ────────────────────────────────────────────────────────────────────────────
# So trên bản bỏ dấu (guard.chuan_cau). Dấu hiệu mạnh luôn là câu hỏi pháp lý; chữ "luật" đứng riêng thì không
# tính khi câu nói về luật chấm điểm nội bộ của FINORA ("luật R1", "bộ luật chấm điểm có mấy luật").
_PHAP_LY_MANH = re.compile(
    r" (?:phap luat|phap ly|nghi dinh|thong tu|luat dan su|van ban phap|dieu \d+|dieu may|dieu nao|hop phap"
    r"|trai phep|trai luat|nha nuoc|nhnn|co che thu nghiem|sandbox"
    r"|quy dinh (?:\w+ ){0,3}?(?:phap luat|cua luat|nha nuoc|xac minh|chinh thuc|bat buoc))(?= )"
)
_CHU_LUAT = re.compile(r" (?:luat|theo luat|dung luat)(?= )")
_LUAT_NOI_BO = re.compile(r" (?:cham diem|luat noi bo|bo luat finora|luat r\d+)(?= )")

# ── Mã nghiệp vụ người dùng gõ trong câu (fix-1, mục 6) ───────────────────────────────────────
# Theo tên tham số công cụ/màn hình: mã chữ (LA-/LC-/LN-) hoặc số đợt gọi vốn ("#31", "khoản số 31").
_TIEN_TO_MA: dict[str, str] = {"applicationNumber": "LA", "contractNumber": "LC", "loanNumber": "LN"}
# Người dùng hay gõ chữ thường ("la-7fa6…"); mã của FINORA viết hoa nên so không phân biệt hoa/thường.
_MA_CHU_TRONG_CAU = re.compile(_MA_NGHIEP_VU_TRONG_CAU.pattern, re.IGNORECASE)
_THAM_SO_SO_KHOAN = frozenset({"listingId", "loanId"})
_SO_KHOAN = re.compile(
    r"#\s?(\d{1,9})(?!\d)"
    r"|\bkhoản\s+(?:vay\s+)?(?:số\s+|#\s?)?(\d{1,9})(?![\d.,]|\s*(?:triệu|tr\b|tỷ|tỉ|nghìn|ngàn|k\b|đ|%|tháng|năm|ngày|kỳ))",
    re.IGNORECASE,
)
# Danh từ chỉ đối tượng của tham số: câu có danh từ này mà thiếu mã thì là hỏi về một đối tượng cụ thể.
_DANH_TU_DOI_TUONG: dict[str, str] = {
    "applicationNumber": " ho so ", "contractNumber": " hop dong ", "loanNumber": " khoan vay ",
    "listingId": " khoan ", "loanId": " khoan ",
}
# Người vay: công cụ liệt kê (chỉ đọc) của chính họ để tìm mã khi câu không nêu.
_DANH_SACH_CUA_TOI: dict[str, str] = {
    "applicationNumber": "ho_so_cua_toi", "contractNumber": "hop_dong_cua_toi", "loanNumber": "khoan_vay_cua_toi",
}
_HOI_LAI_DOI_TUONG: dict[str, tuple[str, str]] = {
    "applicationNumber": ("hồ sơ", "mã hồ sơ (dạng LA-…)"),
    "contractNumber": ("hợp đồng", "mã hợp đồng (dạng LC-…)"),
    "loanNumber": ("khoản vay", "mã khoản vay (dạng LN-…)"),
    "listingId": ("khoản vay", "số khoản (ví dụ #31)"),
    "loanId": ("khoản vay", "số khoản (ví dụ #31)"),
}
# Bỏ mã khỏi câu trước khi khớp từ khóa (fix-2): "Hồ sơ LA-… đang ở bước nào?" phải khớp cụm "hồ sơ đang ở".
_MA_DE_BO_KHI_KHOP = re.compile(rf"{_MA_NGHIEP_VU_TRONG_CAU.pattern}|#\s?\d+", re.IGNORECASE)

# Tham số tùy chọn chỉ đưa đối tượng đang mở lên đầu, không đổi phạm vi dữ liệu (fix-2): điền từ màn đang mở
# cho mọi đường. Tham số lọc (status của danh sách hồ sơ) chỉ điền ở đường B, để nút gợi ý "Hồ sơ nào đang chờ
# thẩm định?" bấm ở tab "Đã duyệt" vẫn trả đúng hàng chờ thẩm định.
_THAM_SO_TIEU_DIEM = frozenset({"loanNumber", "contractNumber"})

# Câu hỏi định nghĩa ("Note nợ xấu là gì?", "Nhóm nợ là gì?") là câu kiến thức: không để từ khóa của gợi ý dữ
# liệu ("nợ xấu", "nhóm nợ") chiếm, trừ khi câu nhắc dữ liệu của chính người hỏi hoặc có mã đối tượng.
_HOI_DINH_NGHIA = re.compile(r" (?:la gi|la cai gi|nghia la gi|co nghia la|nghia la sao|dinh nghia|hieu the nao) ")


def _hoi_dinh_nghia(text: str) -> bool:
    return _HOI_DINH_NGHIA.search(guard.chuan_cau(text)) is not None


def _bo_trung_can_cu(can_cu: Sequence[Citation]) -> list[Citation]:
    """Mỗi mã căn cứ một lần: công cụ và đoạn kiến thức có thể gắn cùng mã với nhãn hơi khác nhau."""
    thay: dict[str, Citation] = {}
    for muc in can_cu:
        thay.setdefault(muc.id, muc)
    return list(thay.values())


def hoi_phap_ly(text: str) -> bool:
    """Câu có hỏi về luật/quy định pháp luật không (để ưu tiên đoạn LUAT/CHUA_XAC_MINH)."""
    cau = guard.chuan_cau(text)
    if _PHAP_LY_MANH.search(cau):
        return True
    return _CHU_LUAT.search(cau) is not None and _LUAT_NOI_BO.search(cau) is None


def ma_trong_cau_theo_loai(text: str, ten_tham_so: str) -> set[str]:
    """Mã đúng loại của tham số `ten_tham_so` người dùng gõ trong câu (viết hoa), rỗng nếu không có."""
    if ten_tham_so in _THAM_SO_SO_KHOAN:
        return {a or b for a, b in _SO_KHOAN.findall(text)}
    tien_to = _TIEN_TO_MA.get(ten_tham_so)
    if tien_to is None:
        return set()
    return {ma.upper() for ma in _MA_CHU_TRONG_CAU.findall(text) if ma.upper().startswith(tien_to + "-")}


def _ma_duy_nhat(text: str, ten_tham_so: str) -> str | None:
    ma = ma_trong_cau_theo_loai(text, ten_tham_so)
    return next(iter(ma)) if len(ma) == 1 else None


def _nhac_doi_tuong(text: str, goi_y: SuggestionDef) -> bool:
    """Câu có nhắc đối tượng mà gợi ý cần mã ("hồ sơ", "hợp đồng", "khoản") không."""
    cau = guard.chuan_cau(text)
    return any(_DANH_TU_DOI_TUONG.get(ten, "\x00") in cau for ten in goi_y.params_from_context)


def _can_ma(goi_y: SuggestionDef) -> bool:
    return bool(goi_y.params_from_context)


def match_intent(
    text: str,
    suggestions: Sequence[SuggestionDef],
    toolsets: frozenset[Toolset],
    screen: str | None = None,
    *,
    uu_tien_cu_the: bool = False,
) -> SuggestionDef | None:
    """Chọn gợi ý có nhiều từ khóa khớp nhất với câu tự do (so cả bản bỏ dấu).

    Hòa điểm thì ưu tiên gợi ý của màn đang mở ("Vì sao hồ sơ của tôi bị từ chối?" trên màn hồ sơ khớp
    gợi ý "vì sao bị từ chối" chứ không phải "hồ sơ của tôi"), rồi tới gợi ý khai báo trước. Khớp theo
    cụm từ nguyên vẹn để "lệnh" không khớp nhầm "lệnh phí"; không có từ khóa nào khớp thì None.

    `uu_tien_cu_the`: hòa điểm thì gợi ý về một đối tượng cụ thể (cần mã hồ sơ/khoản) đứng trước gợi ý liệt
    kê, kể cả khi gợi ý liệt kê thuộc màn đang mở — đường C1 chỉ bật cờ này khi câu đã nhắc tới đối tượng
    (fix-1: "Vì sao hồ sơ của tôi bị từ chối?" ở Trang chủ hỏi lý do, không hỏi danh sách hồ sơ).
    """
    cau = guard.chuan_cau(_MA_DE_BO_KHI_KHOP.sub(" ", text))
    tot_nhat: SuggestionDef | None = None
    diem_cao_nhat = (0, 0, 0)
    for goi_y in suggestions:
        if goi_y.toolset not in toolsets:
            continue
        cum_tu = [guard.chuan_cau(tu) for tu in goi_y.keywords]
        so_khop = sum(1 for cum in cum_tu if cum.strip() and cum in cau)
        if so_khop == 0:
            continue
        cu_the = 1 if uu_tien_cu_the and _can_ma(goi_y) else 0
        diem = (so_khop, cu_the, 1 if screen is not None and screen in goi_y.screens else 0)
        if diem > diem_cao_nhat:
            tot_nhat, diem_cao_nhat = goi_y, diem
    return tot_nhat


def _can_du_lieu_ca_nhan(text: str) -> bool:
    """Câu có nhắc tới dữ liệu của chính người hỏi không (khi đó không trả lời bằng kho kiến thức)."""
    co_dau = " " + " ".join(re.sub(r"\W+", " ", unicodedata.normalize("NFC", text.lower())).split()) + " "
    if any(dau_hieu in co_dau for dau_hieu in _DAU_HIEU_DU_LIEU_CA_NHAN):
        return True
    if guard.bo_dau(text) == " ".join(text.lower().split()):
        # Người gõ không dấu: đành so bản bỏ dấu; câu không dấu vốn mơ hồ nên nghiêng về lấy dữ liệu thật.
        cau = guard.chuan_cau(text)
        return any(guard.chuan_cau(dau_hieu) in cau for dau_hieu in _DAU_HIEU_DU_LIEU_CA_NHAN)
    return False


def _ma_trong_cau(text: str) -> set[str]:
    """Mã người dùng tự gõ ("hồ sơ LA-2026-000123", "khoản 12") không phải do LLM bịa, nên được dùng.

    Trả về dạng viết hoa: mã nghiệp vụ của FINORA viết hoa, người dùng gõ "la-2026-…" vẫn là cùng mã.
    """
    return {ma.strip("-").upper() for ma in re.findall(r"[A-Za-z0-9-]*\d[A-Za-z0-9-]*", text) if ma.strip("-")}


def _giai_thich_ma(last_error_code: str | None, message: str) -> ToolResult | None:
    """Câu dịch của mã lỗi app vừa gặp (`context.lastErrorCode`) và mã người dùng chép vào câu hỏi.

    Spec 13.1: app gửi mã lỗi API gần nhất để trợ lý trả lời "vì sao tôi không làm được?". Glossary
    (Task 7) dịch mã trước khi tới LLM — đưa nguyên mã thì model tự đoán nghĩa (LAW-CONSUMER-2023).
    Dữ kiện chỉ chứa câu dịch của lỗi vừa gặp, không chứa mã gốc (người dùng không cần thấy mã kỹ
    thuật). Mã trong câu hỏi chỉ dịch khi glossary biết nghĩa, để mã phiên bản như CREDIT_POLICY_V1
    không bị gắn nhãn "chưa có diễn giải".
    """
    facts: dict[str, Any] = {}
    items: list[CardItem] = []
    trong_cau: dict[str, str] = {}
    for ma in dict.fromkeys(_MA_LOI_TRONG_CAU.findall(message)):
        cau = dich_ma_loi(ma)
        if not cau.endswith(CHUA_CO_DIEN_GIAI):
            trong_cau[ma] = cau
            items.append(CardItem(label=ma, value=cau))
        if len(trong_cau) >= SO_MA_GIAI_THICH_TOI_DA:
            break
    if trong_cau:
        facts["giai_thich_ma"] = trong_cau
    ma_vua_gap = (last_error_code or "").strip().upper()
    if _MA_LOI_HOP_LE.fullmatch(ma_vua_gap) and ma_vua_gap not in trong_cau:
        facts["loi_vua_gap"] = dich_ma_loi(ma_vua_gap)
        items.append(CardItem(label="Lỗi vừa gặp", value=facts["loi_vua_gap"]))
    if not facts:
        return None
    # Thẻ chỉ để câu dự phòng có nhãn tiếng Việt; không hiện cho người dùng (không nằm trong `results`).
    return ToolResult(facts=facts, cards=[Card(kind="kv", title="Giải thích mã", items=items)])


def _tham_so_tu_context(anh_xa: Mapping[str, str], params: Mapping[str, str]) -> dict[str, str] | None:
    """Tham số công cụ lấy từ context.params theo ánh xạ; thiếu khóa nào thì không chạy được (None)."""
    tham_so: dict[str, str] = {}
    for ten_tham_so, khoa_context in anh_xa.items():
        gia_tri = params.get(khoa_context)
        if not gia_tri:
            return None
        tham_so[ten_tham_so] = gia_tri
    return tham_so


def _loc_doan(ket_qua: Sequence[tuple[KnowledgeChunk, float]], limit: int) -> list[KnowledgeChunk]:
    """Đoạn kiến thức đủ liên quan: đoạn đầu phải đạt ngưỡng, các đoạn sau phải gần điểm đoạn đầu."""
    if not ket_qua or ket_qua[0][1] < NGUONG_BM25_CHINH_SACH:
        return []
    nguong = ket_qua[0][1] * TY_LE_GIU_DOAN
    return [chunk for chunk, diem in ket_qua if diem >= nguong][:limit]


def _nhan(khoa: str) -> str:
    chu = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", khoa).replace("_", " ").strip().lower()
    return chu[:1].upper() + chu[1:]


def _vo_huong(gia_tri: Any) -> str | None:
    if gia_tri is None or isinstance(gia_tri, (dict, list, tuple)):
        return None
    if isinstance(gia_tri, bool):
        return "có" if gia_tri else "không"
    return str(gia_tri)


def _dong_du_kien(facts: Mapping[str, Any]) -> list[str]:
    """Trải dữ kiện thành các dòng "Nhãn: giá trị" cho câu dự phòng khi không dùng được câu của LLM."""
    dong: list[str] = []
    for khoa, gia_tri in facts.items():
        if khoa == "khong_tu_van":
            continue  # cờ nội bộ cho orchestrator, không phải thông tin cho người đọc
        don = _vo_huong(gia_tri)
        if don is not None:
            dong.append(f"{_nhan(khoa)}: {don}")
        elif isinstance(gia_tri, Mapping):
            dong.extend(
                f"{_nhan(khoa)} – {_nhan(k)}: {_vo_huong(v)}" for k, v in gia_tri.items() if _vo_huong(v) is not None
            )
        elif isinstance(gia_tri, (list, tuple)):
            for phan_tu in gia_tri[:5]:
                if isinstance(phan_tu, Mapping):
                    cap = [f"{_nhan(k)}: {_vo_huong(v)}" for k, v in phan_tu.items() if _vo_huong(v) is not None]
                    if cap:
                        dong.append(", ".join(cap))
                elif _vo_huong(phan_tu) is not None:
                    dong.append(f"{_nhan(khoa)}: {_vo_huong(phan_tu)}")
    return dong


def _dong_tu_the(ket_qua: ToolResult) -> list[str]:
    """Dòng "Nhãn: giá trị" lấy từ thẻ kv/progress — nhãn tiếng Việt có dấu, dễ đọc hơn tên khóa facts."""
    dong: list[str] = []
    for the in ket_qua.cards:
        if the.kind == "kv":
            dong.extend(f"{muc.label}: {muc.value}" for muc in the.items)
        elif the.kind == "progress" and the.label:
            dong.append(f"{the.title}: {the.label}")
    return dong


def _tim_trong_du_kien(results: Sequence[tuple[str, ToolResult]], ten: str) -> str | None:
    """Giá trị của khóa `ten` trong dữ kiện, chỉ khi cả lượt có đúng MỘT giá trị (tránh gắn nhầm hồ sơ)."""
    thay: set[str] = set()

    def duyet(obj: Any) -> None:
        if isinstance(obj, Mapping):
            for khoa, gia_tri in obj.items():
                if khoa == ten and _vo_huong(gia_tri) is not None:
                    thay.add(str(gia_tri))
                duyet(gia_tri)
        elif isinstance(obj, (list, tuple)):
            for phan_tu in obj:
                duyet(phan_tu)

    for _, ket_qua in results:
        duyet(ket_qua.facts)
    return next(iter(thay)) if len(thay) == 1 else None


def _ma_loi_llm(loi: LlmUnavailable) -> str:
    """Mã lý do đầu tiên router ghi lại (HET_HAN_MUC, TIMEOUT…) để log biết vì sao rơi vào chế độ rút gọn."""
    return f"LLM_{loi.attempts[0][1]}" if loi.attempts else "LLM_UNAVAILABLE"


def _loi_cho_llm(noi_dung: str) -> str:
    return json.dumps({"loi": noi_dung}, ensure_ascii=False)


def _tom_tat_loi(loi: ValidationError) -> str:
    """Lỗi tham số gửi lại cho LLM: chỉ tên trường + thông điệp, không lặp lại giá trị (có thể là PII)."""
    return "; ".join(
        f"{'.'.join(str(p) for p in chi_tiet['loc']) or 'tham_so'}: {chi_tiet['msg']}"
        for chi_tiet in loi.errors()[:3]
    )


def _tin_goi_cong_cu(ket_qua: LlmResult) -> dict[str, Any]:
    """Message 'assistant' kiểu OpenAI chứa các tool call, để vòng sau LLM thấy nó đã gọi gì.

    `content` là chuỗi rỗng thay vì null vì vài nhà cung cấp kiểu OpenAI từ chối null (Task 1 dùng "").
    """
    return {
        "role": "assistant",
        "content": ket_qua.text or "",
        "tool_calls": [
            {
                "id": goi.id,
                "type": "function",
                "function": {"name": goi.name, "arguments": json.dumps(goi.arguments, ensure_ascii=False)},
            }
            for goi in ket_qua.tool_calls
        ],
    }


def _noi_xay_ra(loi: BaseException) -> str:
    """"file.py:dòng" nơi lỗi xảy ra — đủ để sửa mà không ghi thông điệp lỗi (có thể chứa dữ liệu)."""
    khung = traceback.extract_tb(loi.__traceback__)
    if not khung:
        return "?"
    cuoi = khung[-1]
    return f"{cuoi.filename.replace(chr(92), '/').rsplit('/', 1)[-1]}:{cuoi.lineno}"


@dataclass
class _Luot:
    """Trạng thái của một lượt; chỉ sống trong một request."""

    turn_id: str
    user_hash: str
    request: ChatRequest
    toolsets: frozenset[Toolset]
    surface: str
    screen: str | None
    params: dict[str, str]
    message: str  # đã che PII
    history: list[dict[str, Any]]  # đã che PII
    # Câu dịch mã lỗi vừa gặp/mã trong câu hỏi: dữ kiện cho LLM và câu dự phòng, không phải kết quả công cụ.
    giai_thich: ToolResult | None = None
    results: list[tuple[str, ToolResult]] = field(default_factory=list)
    # Công cụ đã chạy trong lượt theo (tên, tham số): model gọi lại y hệt thì dùng lại, không gửi request.
    da_chay: dict[str, ToolResult] = field(default_factory=dict)
    chunks: list[KnowledgeChunk] = field(default_factory=list)
    diem_doan_dau: float = 0.0  # điểm BM25 của đoạn kiến thức khớp nhất (sau ưu tiên pháp lý)
    tools_called: list[str] = field(default_factory=list)
    tham_so_da_dung: list[dict[str, str]] = field(default_factory=list)
    tool_dang_chay: str | None = None
    provider: str | None = None
    model: str | None = None
    prompt_tokens: int = 0
    completion_tokens: int = 0
    error_code: str | None = None

    @property
    def role_names(self) -> frozenset[str]:
        return frozenset(t.value for t in self.toolsets)

    @property
    def investor(self) -> bool:
        return Toolset.INVESTOR in self.toolsets


class AssistantOrchestrator:
    """Điều phối một lượt; trạng thái trong bộ nhớ chỉ có cache chống bấm hai lần."""

    def __init__(
        self,
        settings: AssistantSettings,
        registry: ToolRegistry,
        index: Bm25Index,
        llm: LlmRouter,
        legal_sources: Mapping[str, LegalSource],
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        clock: Callable[[], float] = time.time,
        intents: Sequence[SuggestionDef] = (),
    ) -> None:
        self._settings = settings
        self._registry = registry
        self._index = index
        self._llm = llm
        self._legal_sources = legal_sources
        self._transport = transport
        self._clock = clock
        # Không truyền `intents` (harness đánh giá Task 19, test) thì khớp từ khóa trên mọi gợi ý của
        # registry, để đường C1 chạy giống hệt lúc chạy thật thay vì bị tắt lặng lẽ.
        self._intents = tuple(intents) or tuple(registry.all_suggestions())
        self._cache: dict[str, tuple[float, ChatResponse]] = {}
        self._dang_xu_ly: dict[str, asyncio.Future[ChatResponse]] = {}

    # ── API công khai ──────────────────────────────────────────────────────────────────────────

    def suggestions(self, toolsets: frozenset[Toolset], screen: str | None) -> list[SuggestionItem]:
        return [SuggestionItem(id=g.id, label=g.label) for g in self._registry.suggestions_for(toolsets, screen)]

    async def handle(self, principal: Principal, toolsets: frozenset[Toolset], request: ChatRequest) -> ChatResponse:
        bat_dau = self._clock()
        user_hash = hash_user(principal.user_id, self._settings.hash_salt)
        khoa = self._khoa_chong_trung(user_hash, toolsets, request)
        while True:
            cu = self._lay_cache(khoa)
            if cu is not None:
                # Bấm gửi hai lần: trả lại đúng phản hồi cũ, không tốn hạn mức LLM lần nữa.
                self._ghi_log_trung(cu, user_hash, toolsets, request, bat_dau)
                return cu
            dang_cho = self._dang_xu_ly.get(khoa)
            if dang_cho is None:
                break
            # Lần bấm thứ hai tới khi lần đầu chưa xong: chờ chung kết quả thay vì gọi LLM lần nữa.
            try:
                phan_hoi = await asyncio.shield(dang_cho)
            except asyncio.CancelledError:
                # Lượt đầu bị hủy (client của nó ngắt kết nối) chứ không phải lượt này: tự xử lý lại
                # thay vì trả CancelledError cho một request vẫn đang chờ câu trả lời.
                task = asyncio.current_task()
                if dang_cho.cancelled() and (task is None or not task.cancelling()):
                    continue
                raise
            self._ghi_log_trung(phan_hoi, user_hash, toolsets, request, bat_dau)
            return phan_hoi

        tuong_lai: asyncio.Future[ChatResponse] = asyncio.get_running_loop().create_future()
        self._dang_xu_ly[khoa] = tuong_lai
        try:
            phan_hoi = await self._mot_luot(principal, toolsets, request, user_hash, bat_dau)
        except Exception as loi:
            tuong_lai.set_exception(loi)
            tuong_lai.exception()  # đánh dấu đã đọc để asyncio không cảnh báo khi không ai chờ chung
            raise
        else:
            tuong_lai.set_result(phan_hoi)
            self._luu_cache(khoa, phan_hoi)
            return phan_hoi
        finally:
            self._dang_xu_ly.pop(khoa, None)
            if not tuong_lai.done():
                tuong_lai.cancel()  # bị hủy giữa chừng: người chờ chung nhận CancelledError, không treo

    # ── Một lượt ───────────────────────────────────────────────────────────────────────────────

    async def _mot_luot(self, principal: Principal, toolsets: frozenset[Toolset], request: ChatRequest,
                        user_hash: str, bat_dau: float) -> ChatResponse:
        luot = self._tao_luot(request, toolsets, user_hash)
        try:
            phan_hoi = await asyncio.wait_for(self._xu_ly(principal, luot), timeout=self._settings.turn_timeout_s)
        except TimeoutError:
            luot.error_code = "TURN_TIMEOUT"
            luot.chunks = []  # câu "quá lâu" không dùng đoạn kiến thức nào: không gắn căn cứ của chúng
            phan_hoi = self._dung_phan_hoi(luot, CAU_QUA_LAU, mode="fallback")
        except ApiError:
            # 401 phiên hết hạn: vẫn ghi một dòng log rồi để FastAPI trả lỗi cho app làm mới token.
            self._ghi_log(luot, "error", bat_dau)
            raise
        except Exception as loi:  # noqa: BLE001 — lưới an toàn cuối của một lượt, có ghi log bên dưới
            # Lỗi lập trình (mapper đọc sai trường…): người dùng vẫn nhận câu trả lời thay vì HTTP 500.
            # Chỉ log loại lỗi + vị trí, không log thông điệp (ValidationError chép lại cả giá trị đầu vào).
            logger.error("Trợ lý lỗi nội bộ turn=%s loai=%s noi=%s", luot.turn_id, type(loi).__name__,
                         _noi_xay_ra(loi))
            luot.error_code = "LOI_NOI_BO"
            # Không dựng lại thẻ/nút/căn cứ: dữ liệu của lượt có thể chính là thứ gây lỗi. Câu miễn trừ
            # đầu tư vẫn gắn vì nó áp cho MỌI lượt của nhà đầu tư.
            phan_hoi = ChatResponse(
                turn_id=luot.turn_id,
                answer=CAU_LOI_NOI_BO,
                suggestions=self.suggestions(toolsets, luot.screen),
                mode="fallback",
                disclaimer=MIEN_TRU_DAU_TU if luot.investor else None,
            )
        self._ghi_log(luot, phan_hoi.mode, bat_dau)
        return phan_hoi

    def _tao_luot(self, request: ChatRequest, toolsets: frozenset[Toolset], user_hash: str) -> _Luot:
        ctx = request.context
        message = guard.scrub_user_text(request.message)
        return _Luot(
            turn_id=str(uuid.uuid4()),
            user_hash=user_hash,
            request=request,
            toolsets=toolsets,
            surface=request.surface,
            screen=ctx.screen if ctx else None,
            params=dict(ctx.params) if ctx else {},
            message=message,
            history=[{"role": t.role, "content": guard.scrub_user_text(t.content)} for t in request.history],
            giai_thich=_giai_thich_ma(ctx.last_error_code if ctx else None, message),
        )

    async def _xu_ly(self, principal: Principal, luot: _Luot) -> ChatResponse:
        routes = self._registry.routes_for(luot.toolsets)
        async with FinoraApiClient(self._settings, principal.token, routes, transport=self._transport) as client:
            try:
                return await self._chon_duong(client, luot)
            except ToolError as loi:
                luot.error_code = loi.code
                if loi.code == "PHIEN_HET_HAN":
                    # Service đích trả 401: báo client làm mới token rồi gửi lại (spec mục 12).
                    raise ApiError(401, "ASSISTANT_UNAUTHENTICATED", "Phiên đăng nhập đã hết hạn.") from loi
                # Công cụ lỗi không làm mất lời giải thích mã lỗi người dùng vừa gặp (fix-1, mối lo 3 của
                # Task 13–15): câu dịch có sẵn từ glossary, không cần dữ liệu của công cụ.
                cau = loi.user_message
                if luot.giai_thich is not None:
                    dong = _dong_tu_the(luot.giai_thich)
                    cau = f"{cau}\n\n" + "\n".join(f"- {d}" for d in dong)
                # Đã gọi LLM rồi mới lỗi (đường công cụ) thì là fallback; chưa gọi thì vẫn là câu mẫu. Không tìm thấy
                # (403/404: hồ sơ của người khác, khoản không tồn tại) thì không đưa nút mở lại đúng đối tượng đó.
                nut = [] if loi.code in ("KHONG_TIM_THAY", "KHONG_DUOC_PHEP") else self._hanh_dong_khi_loi(luot)
                luot.chunks = []
                return self._dung_phan_hoi(luot, cau, mode="fallback" if luot.provider else "template",
                                           them_hanh_dong=nut)

    async def _chon_duong(self, client: FinoraApiClient, luot: _Luot) -> ChatResponse:
        request = luot.request
        # A. Nút gợi ý do server cấp.
        if request.suggestion_id:
            goi_y = self._registry.suggestion(request.suggestion_id)
            if goi_y is not None and goi_y.toolset in luot.toolsets:
                chuan_bi = self._chuan_bi(goi_y.tool, goi_y.params_from_context, luot)
                if chuan_bi is not None:
                    return await self._tra_loi_mau(client, luot, goi_y, *chuan_bi)

        # Nhà đầu tư xin khuyến nghị: từ chối bằng câu mẫu, không lấy dữ liệu, không gọi LLM.
        if luot.investor and guard.detect_advice_request(luot.message):
            return self._dung_phan_hoi(luot, CAU_TU_CHOI_KHUYEN_NGHI, mode="template")

        # Xin làm thay một thao tác (trả nợ, ký, đặt lệnh, duyệt…): từ chối bằng câu mẫu + nút mở đúng màn.
        # Chạy trước đường B và C1: "Trả nợ giúp tôi kỳ này" khớp từ khóa "kỳ này" của gợi ý kỳ tới (CHAT-005).
        thao_tac = detect_action_request(luot.message, luot.toolsets)
        if thao_tac is not None:
            return await self._tu_choi_thao_tac(client, luot, thao_tac)

        # Câu tính khoản vay có số tiền + kỳ hạn: code đọc tham số và gọi công cụ tính của Loan (CHAT-004).
        if Toolset.BORROWER in luot.toolsets:
            phan_hoi = await self._tra_loi_tinh_vay(client, luot)
            if phan_hoi is not None:
                return phan_hoi

        # B. Câu hỏi gắn màn hình đang mở (mọi tham số tùy chọn của công cụ màn hình lấy từ màn: mã đang mở, tab).
        man = self._registry.screen_tool(luot.screen, luot.toolsets)
        if man is not None:
            chuan_bi = self._chuan_bi(man[0], man[1], luot, moi_tham_so_tuy_chon=True)
            if chuan_bi is not None:
                return await self._tra_loi_theo_man(client, luot, *chuan_bi)

        tim_thay = self._tim_doan(luot, SO_DOAN_KIEN_THUC_CHINH_SACH)
        doan = _loc_doan(tim_thay, SO_DOAN_KIEN_THUC_CHINH_SACH)
        luot.diem_doan_dau = tim_thay[0][1] if tim_thay else 0.0
        ca_nhan = _can_du_lieu_ca_nhan(luot.message)

        # Câu hỏi pháp lý mà đoạn khớp nhất là đoạn pháp lý: trả lời theo kho kiến thức + căn cứ do code gắn,
        # trước khi khớp từ khóa gợi ý dữ liệu ("Sao vay tối đa chỉ 24 tháng?" khớp "vay tối đa" của gợi ý
        # sản phẩm, CHAT-014).
        if doan and not ca_nhan and self._uu_tien_doan_phap_ly(luot, tim_thay):
            luot.chunks = doan
            return await self._goi_llm_mot_lan(luot, Lane.PUBLIC, mau=None)

        # C1. Câu tự do khớp từ khóa của một gợi ý → như A. Mã gợi ý cần lấy từ màn đang mở, hoặc từ chính câu
        # hỏi khi câu có đúng một mã đúng loại; câu nhắc một đối tượng mà không có mã thì hỏi lại.
        phan_hoi = await self._tra_loi_theo_tu_khoa(client, luot)
        if phan_hoi is not None:
            return phan_hoi

        # C2. Câu chính sách thuần: kho kiến thức đủ trả lời, không cần dữ liệu cá nhân.
        if doan and not ca_nhan:
            luot.chunks = doan
            return await self._goi_llm_mot_lan(luot, Lane.PUBLIC, mau=None)

        # C3. Còn lại: để LLM tự chọn công cụ của vai trò; kèm tối đa 2 đoạn kiến thức liên quan để LLM
        # giải thích được "vì sao" bằng chính sách thật thay vì đoán.
        luot.chunks = doan[:SO_DOAN_KIEN_THUC_CONG_CU]
        return await self._tra_loi_co_cong_cu(client, luot)

    def _chuan_bi(self, ten_tool: str, anh_xa: Mapping[str, str], luot: _Luot, *,
                  moi_tham_so_tuy_chon: bool = False) -> tuple[ToolSpec, BaseModel] | None:
        """Công cụ + tham số đã kiểm, hoặc None nếu công cụ không thuộc vai trò/thiếu hay sai tham số."""
        tham_so = _tham_so_tu_context(anh_xa, luot.params)
        if tham_so is None:
            return None
        return self._kiem_tham_so(ten_tool, tham_so, luot, moi_tham_so_tuy_chon=moi_tham_so_tuy_chon)

    def _kiem_tham_so(self, ten_tool: str, tham_so: Mapping[str, Any], luot: _Luot, *,
                      moi_tham_so_tuy_chon: bool = False) -> tuple[ToolSpec, BaseModel] | None:
        """Như `_chuan_bi` nhưng tham số đã có sẵn (đọc từ câu hỏi), vẫn kiểm bằng `params_model` của công cụ.

        Tham số tùy chọn trùng tên khóa của màn đang mở được điền thêm (fix-2: màn chi tiết khoản vay gửi
        `loanNumber` mà trước đây bị bỏ, nên trợ lý trả lời về khoản khác). Giá trị từ màn sai khuôn thì thử lại
        không có nó, để một tham số màn lạ không làm mất cả câu trả lời.
        """
        spec = self._registry.get(ten_tool)
        if spec is None or spec.toolset not in luot.toolsets:
            return None
        them: dict[str, str] = {}
        for ten, truong in spec.params_model.model_fields.items():
            alias = truong.alias or ten
            if truong.is_required() or alias in tham_so or ten in tham_so:
                continue
            if (moi_tham_so_tuy_chon or alias in _THAM_SO_TIEU_DIEM) and luot.params.get(alias):
                them[alias] = luot.params[alias]
        for thu in ([{**tham_so, **them}] if them else []) + [dict(tham_so)]:
            try:
                return spec, spec.params_model.model_validate(thu)
            except ValidationError:
                continue
        return None

    def _tim_doan(self, luot: _Luot, limit: int) -> list[tuple[KnowledgeChunk, float]]:
        """Đoạn kiến thức theo vai trò; câu hỏi pháp lý thì đoạn LUAT/CHUA_XAC_MINH được nhân điểm để đứng
        trước đoạn chính sách/hướng dẫn cùng chủ đề (CHAT-014). Câu thường giữ nguyên thứ hạng BM25."""
        if not hoi_phap_ly(luot.message):
            return self._index.search(luot.message, toolsets=luot.role_names, limit=limit)
        tim = self._index.search(luot.message, toolsets=luot.role_names, limit=max(limit, SO_DOAN_TIM_PHAP_LY))
        uu_tien = [(c, d * HE_SO_UU_TIEN_PHAP_LY if c.kind in LOAI_DOAN_PHAP_LY else d) for c, d in tim]
        uu_tien.sort(key=lambda cap: (-cap[1], cap[0].id))
        return uu_tien[:limit]

    @staticmethod
    def _uu_tien_doan_phap_ly(luot: _Luot, tim_thay: Sequence[tuple[KnowledgeChunk, float]]) -> bool:
        """Đoạn đứng đầu là đoạn pháp lý, và câu hỏi về pháp luật hoặc đoạn đó khớp rất mạnh."""
        if not tim_thay or tim_thay[0][0].kind not in LOAI_DOAN_PHAP_LY:
            return False
        return hoi_phap_ly(luot.message) or tim_thay[0][1] >= NGUONG_DOAN_PHAP_LY_MANH

    def _mau_khong_tham_so(self, luot: _Luot, ten_tool: str) -> SuggestionDef | None:
        """Câu mẫu của gợi ý chạy công cụ `ten_tool` không cần mã (để diễn đạt kết quả một lần tra cứu)."""
        return next(
            (g for g in self._intents if g.tool == ten_tool and g.toolset in luot.toolsets and not _can_ma(g)), None
        )

    async def _tra_cuu(self, client: FinoraApiClient, luot: _Luot, ten_tool: str) -> ToolResult | None:
        """Chạy một công cụ liệt kê không tham số (chỉ đọc); lỗi tạm thời → None, phiên hết hạn vẫn ném lên."""
        chuan_bi = self._kiem_tham_so(ten_tool, {}, luot)
        if chuan_bi is None:
            return None
        try:
            return await self._chay(client, luot, *chuan_bi)
        except ToolError as loi:
            if loi.code == "PHIEN_HET_HAN":
                raise
            luot.error_code = loi.code
            return None

    # ── Ba nhánh trả lời bằng câu mẫu không cần LLM (fix-1) ───────────────────────────────────

    def _nut_cho(self, luot: _Luot, khoa: str, co_dinh: Mapping[str, str]) -> Action | None:
        """Nút `khoa` với tham số từ màn đang mở, dữ kiện của lượt hoặc mã duy nhất người dùng gõ."""
        if khoa not in ACTIONS or ACTIONS[khoa].surface != luot.surface:
            return None
        tham_so = dict(co_dinh)
        for ten in ACTIONS[khoa].params:
            if ten not in tham_so:
                gia_tri = self._gia_tri_tham_so(luot, ten) or _ma_duy_nhat(luot.message, ten)
                if gia_tri:
                    tham_so[ten] = gia_tri
        try:
            return build_action(khoa, tham_so)
        except ValueError:
            return None

    async def _tu_choi_thao_tac(self, client: FinoraApiClient, luot: _Luot, thao_tac: ThaoTac) -> ChatResponse:
        """Không làm thay: câu từ chối + hướng dẫn tự làm + nút mở đúng màn.

        Người vay hỏi ở Trang chủ thì màn không có mã khoản vay/hợp đồng; khi đó chạy công cụ liệt kê (chỉ đọc,
        của chính họ) để nút trỏ đúng khoản/hợp đồng và kèm câu mẫu của công cụ đó (kỳ tới bao nhiêu, hợp đồng
        nào chờ ký). Không bao giờ gọi API ghi.
        """
        cau = cau_tu_choi_thao_tac(thao_tac, luot.message)
        nut: Action | None = None
        if thao_tac.hanh_dong:
            nut = self._nut_cho(luot, thao_tac.hanh_dong, thao_tac.tham_so_co_dinh)
            if nut is None and thao_tac.tra_cuu:
                ket_qua = await self._tra_cuu(client, luot, thao_tac.tra_cuu)
                if ket_qua is not None:
                    nut = self._nut_cho(luot, thao_tac.hanh_dong, thao_tac.tham_so_co_dinh)
                    mau = self._mau_khong_tham_so(luot, thao_tac.tra_cuu)
                    if mau is not None:
                        cau = f"{cau}\n\n{mau.render(ket_qua)}"
            if nut is None and thao_tac.hanh_dong_du_phong:
                nut = self._nut_cho(luot, thao_tac.hanh_dong_du_phong, {})
        return self._dung_phan_hoi(luot, cau, mode="template", hanh_dong_truoc=[nut] if nut else ())

    async def _tra_loi_tinh_vay(self, client: FinoraApiClient, luot: _Luot) -> ChatResponse | None:
        """Câu có số tiền + kỳ hạn muốn vay → công cụ tính của Loan + câu mẫu; không đọc được thì None."""
        yeu_cau = doc_yeu_cau_tinh_vay(luot.message)
        if yeu_cau is None:
            return None
        ten_tool, tham_so = ke_hoach_tinh_vay(yeu_cau, luot.params)
        chuan_bi = self._kiem_tham_so(ten_tool, tham_so, luot)
        if chuan_bi is None:
            return None
        ket_qua = await self._chay(client, luot, *chuan_bi)
        return self._dung_phan_hoi(luot, CAU_MAU_TINH_VAY[ten_tool](ket_qua), mode="template")

    def _tham_so_goi_y(self, goi_y: SuggestionDef, luot: _Luot) -> dict[str, str] | None:
        """Tham số của gợi ý: lấy từ màn đang mở, thiếu thì lấy mã duy nhất đúng loại trong câu hỏi."""
        tham_so: dict[str, str] = {}
        for ten_tham_so, khoa_context in goi_y.params_from_context.items():
            gia_tri = luot.params.get(khoa_context) or _ma_duy_nhat(luot.message, ten_tham_so)
            if not gia_tri:
                return None
            tham_so[ten_tham_so] = gia_tri
        return tham_so

    async def _tra_loi_theo_tu_khoa(self, client: FinoraApiClient, luot: _Luot) -> ChatResponse | None:
        """Đường C1. Gợi ý cần mã mà câu chỉ nhắc đối tượng ("hồ sơ này") vẫn được xét, để hỏi lại thay vì
        trả lời lạc đề; câu không nhắc đối tượng nào thì gợi ý đó bị bỏ như trước ("Lãi suất cuối là gì?")."""
        if (_hoi_dinh_nghia(luot.message) and not _can_du_lieu_ca_nhan(luot.message)
                and not _MA_DE_BO_KHI_KHOP.search(luot.message)):
            return None
        # Gợi ý chỉ dùng thay khi có mã (đích `khi_co_ma`) không được đứng riêng để hỏi lại: "Hồ sơ của tôi đang ở
        # bước nào?" không mã vẫn là câu liệt kê hồ sơ.
        dich_khi_co_ma = {g.khi_co_ma for g in self._intents if g.khi_co_ma}
        ung_vien: list[SuggestionDef] = []
        tham_so_cua: dict[str, dict[str, str] | None] = {}
        for goi_y in self._intents:
            if goi_y.toolset not in luot.toolsets:
                continue
            tham_so = self._tham_so_goi_y(goi_y, luot)
            if tham_so is None and (goi_y.id in dich_khi_co_ma or not _nhac_doi_tuong(luot.message, goi_y)):
                continue
            ung_vien.append(goi_y)
            tham_so_cua[goi_y.id] = tham_so
        goi_y = match_intent(luot.message, ung_vien, luot.toolsets, luot.screen, uu_tien_cu_the=True)
        if goi_y is None:
            return None
        tham_so = tham_so_cua[goi_y.id]
        if goi_y.khi_co_ma:
            thay = next((g for g in self._intents if g.id == goi_y.khi_co_ma and g.toolset in luot.toolsets), None)
            tham_so_thay = self._tham_so_goi_y(thay, luot) if thay is not None else None
            if thay is not None and tham_so_thay:
                goi_y, tham_so = thay, tham_so_thay
        if tham_so is None:
            return await self._hoi_lai_doi_tuong(client, luot, goi_y)
        chuan_bi = self._kiem_tham_so(goi_y.tool, tham_so, luot)
        if chuan_bi is None:
            return None
        return await self._tra_loi_mau(client, luot, goi_y, *chuan_bi)

    async def _hoi_lai_doi_tuong(self, client: FinoraApiClient, luot: _Luot, goi_y: SuggestionDef) -> ChatResponse:
        """Câu nhắc một hồ sơ/khoản mà không có (hoặc có nhiều) mã: người vay có đúng một đối tượng thì trả lời
        luôn đối tượng đó, còn lại hỏi lại bằng câu mẫu kèm danh sách/nút hàng đợi."""
        ten = next(t for t, k in goi_y.params_from_context.items() if not luot.params.get(k))
        nhieu_ma = len(ma_trong_cau_theo_loai(luot.message, ten)) > 1
        danh_sach = _DANH_SACH_CUA_TOI.get(ten) if goi_y.toolset == Toolset.BORROWER else None
        if danh_sach and not nhieu_ma:
            ket_qua = await self._tra_cuu(client, luot, danh_sach)
            if ket_qua is not None and len(ket_qua.ids) == 1:
                chuan_bi = self._kiem_tham_so(goi_y.tool, {ten: next(iter(ket_qua.ids))}, luot)
                if chuan_bi is not None:
                    # Bảng danh sách một dòng chỉ lặp lại thẻ chi tiết: bỏ khỏi câu trả lời.
                    luot.results = [(t, kq) for t, kq in luot.results if kq is not ket_qua]
                    return await self._tra_loi_mau(client, luot, goi_y, *chuan_bi)
            if ket_qua is not None and not ket_qua.ids:
                mau = self._mau_khong_tham_so(luot, danh_sach)
                if mau is not None:
                    return self._dung_phan_hoi(luot, mau.render(ket_qua), mode="template")
        doi_tuong, cach_go = _HOI_LAI_DOI_TUONG.get(ten, ("đối tượng", "mã"))
        cau = [f"Câu hỏi có nhiều {cach_go.split(' (')[0]}; mỗi lần mình chỉ xem được một {doi_tuong}."] if nhieu_ma else []
        cau.append(f"Bạn muốn hỏi {doi_tuong} nào?")
        if goi_y.toolset == Toolset.ADMIN:
            cau.append(f"Bạn gõ {cach_go} vào câu hỏi, hoặc mở {doi_tuong} từ hàng đợi thẩm định rồi hỏi lại ở đó.")
            nut = [build_action("hang_doi_tham_dinh")] if ten == "applicationNumber" else []
        else:
            cau.append(f"Bạn mở {doi_tuong} đó trong app rồi bấm “Hỏi trợ lý”, hoặc gõ {cach_go} vào câu hỏi.")
            nut = []
        return self._dung_phan_hoi(luot, " ".join(cau), mode="template", hanh_dong_truoc=nut)

    async def _chay(self, client: FinoraApiClient, luot: _Luot, spec: ToolSpec, params: BaseModel) -> ToolResult:
        luot.tool_dang_chay = spec.name
        luot.tools_called.append(spec.name)
        # Ghi lại tham số đã gọi (dạng chuỗi) để dựng nút điều hướng và nút khi lỗi.
        luot.tham_so_da_dung.append({
            ten: str(gia_tri)
            for ten, gia_tri in params.model_dump(by_alias=True, exclude_none=True).items()
            if isinstance(gia_tri, (str, int)) and not isinstance(gia_tri, bool)
        })
        ket_qua = await spec.handler(client, params)
        luot.results.append((spec.name, ket_qua))
        return ket_qua

    # ── Ba kiểu trả lời ────────────────────────────────────────────────────────────────────────

    async def _tra_loi_mau(self, client: FinoraApiClient, luot: _Luot, goi_y: SuggestionDef,
                           spec: ToolSpec, params: BaseModel) -> ChatResponse:
        ket_qua = await self._chay(client, luot, spec, params)
        return self._dung_phan_hoi(luot, goi_y.render(ket_qua), mode="template", goi_y_hien_tai=goi_y.id)

    async def _tra_loi_theo_man(self, client: FinoraApiClient, luot: _Luot, spec: ToolSpec,
                                params: BaseModel) -> ChatResponse:
        await self._chay(client, luot, spec, params)
        luot.chunks = _loc_doan(self._tim_doan(luot, SO_DOAN_KIEN_THUC_MAN_HINH), SO_DOAN_KIEN_THUC_MAN_HINH)
        # Làn theo công cụ chứ không theo LLM: dữ liệu cá nhân chỉ đi qua nhà không huấn luyện.
        lane = Lane.USER_DATA if spec.lane == Lane.USER_DATA else Lane.PUBLIC
        return await self._goi_llm_mot_lan(luot, lane, mau=self._mau_cho_man(luot, spec.name))

    def _mau_cho_man(self, luot: _Luot, ten_tool: str) -> SuggestionDef | None:
        """Câu mẫu dự phòng của đường B: gợi ý cùng công cụ khớp câu hỏi nhất, không thì gợi ý đầu.

        Một màn có thể có nhiều gợi ý cùng công cụ (ApplicationDetail: vì sao bị từ chối / chưa giải
        ngân / lãi suất khác); lấy gợi ý khai báo đầu thì "chưa nhận tiền?" bị trả lời bằng câu "vì sao
        bị từ chối". Gợi ý riêng của màn đứng trước gợi ý chung.
        """
        cung_tool = [
            g for g in self._intents
            if g.tool == ten_tool and g.toolset in luot.toolsets and (not g.screens or luot.screen in g.screens)
        ]
        cung_tool.sort(key=lambda g: 0 if g.screens else 1)
        return match_intent(luot.message, cung_tool, luot.toolsets, luot.screen) or next(iter(cung_tool), None)

    def _lan_cua_luot(self, lane: Lane, luot: _Luot) -> Lane:
        """Có dữ kiện từ một công cụ làn USER_DATA thì cả lượt đi USER_DATA, bất kể đường xử lý đề nghị gì.

        Chốt ở đây (một chỗ, ngay trước khi gọi LLM) để một đường xử lý thêm về sau không thể vô tình
        gửi dữ liệu cá nhân qua làn PUBLIC (Gemini bản miễn phí dùng dữ liệu để huấn luyện).
        """
        for ten, _ in luot.results:
            spec = self._registry.get(ten)
            if spec is None or spec.lane == Lane.USER_DATA:
                return Lane.USER_DATA
        return lane

    async def _goi_llm_mot_lan(self, luot: _Luot, lane: Lane, *, mau: SuggestionDef | None) -> ChatResponse:
        """Đường B và câu chính sách: một request LLM, không kèm công cụ."""
        lane = self._lan_cua_luot(lane, luot)
        tin_nhan = self._tin_nhan(luot, du_lieu=True, lane=lane)
        try:
            guard.assert_outbound_clean(tin_nhan)
        except guard.OutboundBlocked:
            luot.error_code = "PII_OUTBOUND"
            return self._phan_hoi_du_phong(luot, mau)
        try:
            ket_qua = await self._llm.complete(tin_nhan, lane=lane, user_hash=luot.user_hash)
        except LlmUnavailable as loi:
            luot.error_code = _ma_loi_llm(loi)
            return self._phan_hoi_du_phong(luot, mau, rut_gon=True)
        self._ghi_llm(luot, ket_qua)
        return self._kiem_va_dung(luot, ket_qua.text, mau)

    async def _tra_loi_co_cong_cu(self, client: FinoraApiClient, luot: _Luot) -> ChatResponse:
        """Đường C3: LLM gọi công cụ tối đa 3 vòng, vòng thứ 4 bắt buộc trả lời không kèm công cụ.

        Luôn đi làn USER_DATA: đường này dành cho câu cần dữ liệu cá nhân, và kết quả công cụ quay lại
        trong cùng hội thoại.
        """
        specs = {s.name: s for s in self._registry.for_toolsets(luot.toolsets)}
        schemas = [s.openai_schema() for s in specs.values()]
        # Mã được dùng: tham số màn đang mở, mã người dùng gõ, và mã chữ+số đã hiện trong hội thoại
        # ("hồ sơ đó" ở câu nối tiếp trỏ tới mã của lượt trước). Lịch sử do client gửi nên không cho
        # quyền gì hơn việc người dùng tự gõ mã đó; service đích vẫn tự kiểm chủ sở hữu.
        ma_da_thay = {v.upper() for v in luot.params.values()} | _ma_trong_cau(luot.message)
        for luot_cu in luot.history:
            ma_da_thay.update(ma for ma in _ma_trong_cau(luot_cu["content"]) if _CO_CHU.search(ma))
        tin_nhan = self._tin_nhan(
            luot, du_lieu=bool(luot.chunks) or luot.giai_thich is not None, lane=Lane.USER_DATA
        )
        so_lan_sai = 0
        for vong in range(SO_VONG_CONG_CU_TOI_DA + 1):
            vong_cuoi = vong == SO_VONG_CONG_CU_TOI_DA
            try:
                guard.assert_outbound_clean(tin_nhan)
            except guard.OutboundBlocked:
                luot.error_code = "PII_OUTBOUND"
                return self._phan_hoi_du_phong(luot, None)
            try:
                ket_qua = await self._llm.complete(tin_nhan, lane=Lane.USER_DATA,
                                                   tools=None if vong_cuoi else schemas, user_hash=luot.user_hash)
            except LlmUnavailable as loi:
                luot.error_code = _ma_loi_llm(loi)
                if not luot.results and not (luot.chunks and luot.diem_doan_dau >= NGUONG_DOAN_MANH_KHONG_LLM):
                    return self._phan_hoi_can_du_lieu_rut_gon(luot)
                return self._phan_hoi_du_phong(luot, None, rut_gon=True)
            self._ghi_llm(luot, ket_qua)
            if vong_cuoi or not ket_qua.tool_calls:
                return self._kiem_va_dung(luot, ket_qua.text, None)

            tin_nhan.append(_tin_goi_cong_cu(ket_qua))
            for thu_tu, goi in enumerate(ket_qua.tool_calls):
                if thu_tu >= SO_GOI_MOI_VONG_TOI_DA:
                    # Vẫn phải trả lời từng tool call (chuẩn OpenAI), nhưng không chạy thêm.
                    noi_dung = _loi_cho_llm(
                        f"Mỗi vòng chỉ chạy tối đa {SO_GOI_MOI_VONG_TOI_DA} công cụ; hãy dùng dữ liệu đã có."
                    )
                    hop_le = True
                else:
                    noi_dung, hop_le = await self._thuc_hien_goi(
                        client, luot, goi.name, goi.arguments, specs, ma_da_thay
                    )
                tin_nhan.append({"role": "tool", "tool_call_id": goi.id, "content": noi_dung})
                if not hop_le:
                    so_lan_sai += 1
            if so_lan_sai >= 2:
                # Đã báo lỗi một lần mà vẫn sai: dừng, không để model đoán tiếp (spec mục 12).
                luot.error_code = "TOOL_ARGS_INVALID"
                return self._phan_hoi_du_phong(luot, None)
        return self._phan_hoi_du_phong(luot, None)

    async def _thuc_hien_goi(self, client: FinoraApiClient, luot: _Luot, ten: str, doi_so: Mapping[str, Any],
                             specs: Mapping[str, ToolSpec], ma_da_thay: set[str]) -> tuple[str, bool]:
        """Chạy một tool call của LLM. Trả (nội dung gửi lại LLM, tool call có hợp lệ không).

        ToolError (service lỗi, không tìm thấy, phiên hết hạn) không gửi lại LLM mà dừng cả lượt: người
        dùng nhận câu thông báo cố định + nút mở màn, không để model diễn giải lỗi.
        """
        spec = specs.get(ten)
        if spec is None:
            return _loi_cho_llm(f"Không có công cụ tên {ten}."), False
        try:
            params = spec.params_model.model_validate(dict(doi_so))
        except ValidationError as loi:
            return _loi_cho_llm(f"Tham số không hợp lệ: {_tom_tat_loi(loi)}"), False
        bia = [
            str(gia_tri)
            for khoa, gia_tri in params.model_dump(by_alias=True).items()
            if khoa in THAM_SO_MA_NGHIEP_VU and gia_tri is not None and str(gia_tri).upper() not in ma_da_thay
        ]
        if bia:
            return _loi_cho_llm(
                f"Mã {', '.join(bia)} không có trong dữ liệu của lượt này. "
                "Hãy gọi công cụ liệt kê trước để lấy đúng mã, không tự đặt mã."
            ), False
        # Model nhỏ hay gọi lại đúng công cụ + tham số ở vòng sau: dùng lại kết quả, không gửi request
        # lần nữa và không nhân đôi thẻ số liệu.
        khoa_goi = spec.name + json.dumps(params.model_dump(mode="json", by_alias=True), sort_keys=True)
        ket_qua = luot.da_chay.get(khoa_goi)
        if ket_qua is None:
            ket_qua = await self._chay(client, luot, spec, params)
            luot.da_chay[khoa_goi] = ket_qua
            ma_da_thay.update(ma.upper() for ma in ket_qua.ids)
        return build_fact_block([(spec.name, ket_qua)], []), True

    # ── Kiểm đầu ra và dựng phản hồi ───────────────────────────────────────────────────────────

    def _tin_nhan(self, luot: _Luot, *, du_lieu: bool, lane: Lane) -> list[dict[str, Any]]:
        """Messages kiểu OpenAI.

        Làn PUBLIC bỏ lịch sử và tham số màn hình: câu trả lời cũ có thể chứa số liệu cá nhân, mã hồ sơ
        là mã của chính người dùng, mà làn này có thể tới nhà cung cấp dùng dữ liệu để huấn luyện.
        """
        rieng_tu = lane == Lane.USER_DATA
        tin: list[dict[str, Any]] = [{
            "role": "system",
            "content": build_system_prompt(luot.toolsets, luot.surface, allowed_action_keys(luot.surface)),
        }]
        if rieng_tu:
            tin.extend(luot.history)
        phan: list[str] = []
        if du_lieu:
            phan.append(build_fact_block(self._ket_qua_du_kien(luot), luot.chunks))
        if rieng_tu and luot.screen and _TEN_MAN_HOP_LE.fullmatch(luot.screen):
            tham_so = ", ".join(f"{k}={v}" for k, v in sorted(luot.params.items()))
            phan.append(f"MÀN ĐANG MỞ: {luot.screen}" + (f" ({tham_so})" if tham_so else ""))
        if phan:
            tin.append({"role": "user", "content": "\n\n".join(phan) + f"\nCÂU HỎI: {luot.message}"})
        else:
            tin.append({"role": "user", "content": luot.message})
        return tin

    def _ghi_llm(self, luot: _Luot, ket_qua: LlmResult) -> None:
        luot.provider, luot.model = ket_qua.provider, ket_qua.model
        luot.prompt_tokens += ket_qua.prompt_tokens
        luot.completion_tokens += ket_qua.completion_tokens

    def _kiem_va_dung(self, luot: _Luot, text: str | None, mau: SuggestionDef | None) -> ChatResponse:
        dau_ra = parse_model_output(text)
        if not dau_ra.answer:
            luot.error_code = "LLM_EMPTY"
            return self._phan_hoi_du_phong(luot, mau)
        van_ban_du_kien = self._van_ban_du_kien(luot)
        kiem = guard.check_reply(dau_ra.answer, fact_texts=van_ban_du_kien,
                                 citations=self._can_cu(luot), investor=luot.investor)
        if not kiem.ok:
            luot.error_code = "GUARD_" + "_".join(kiem.reasons)
            return self._phan_hoi_du_phong(luot, mau)
        if self._co_ma_bia(luot, dau_ra.answer, van_ban_du_kien):
            luot.error_code = "GUARD_MA_BIA"
            return self._phan_hoi_du_phong(luot, mau)
        return self._dung_phan_hoi(luot, dau_ra.answer, mode="llm", khoa_llm=dau_ra.action_keys)

    @staticmethod
    def _co_ma_bia(luot: _Luot, answer: str, van_ban_du_kien: Sequence[str]) -> bool:
        """Câu trả lời nêu mã hồ sơ/hợp đồng/khoản vay không có ở đâu trong lượt (dữ kiện, câu hỏi, màn
        đang mở, lịch sử)?

        Mã của Loan dài 20 ký tự hex; model nhỏ hay chép thiếu/sai một ký tự, và người dùng sẽ tra theo
        mã sai đó. Kiểm tham số công cụ thôi chưa đủ: mã sai nằm ngay trong câu trả lời.
        """
        trong_cau = {m.upper() for m in _MA_NGHIEP_VU_TRONG_CAU.findall(answer)}
        if not trong_cau:
            return False
        nguon = [*van_ban_du_kien, *luot.params.values(), *(t["content"] for t in luot.history)]
        da_thay = {m.upper() for van_ban in nguon for m in _MA_NGHIEP_VU_TRONG_CAU.findall(van_ban)}
        for _, ket_qua in luot.results:
            da_thay.update(m.upper() for m in ket_qua.ids)
        return not trong_cau <= da_thay

    def _ket_qua_du_kien(self, luot: _Luot) -> list[tuple[str, ToolResult]]:
        """Dữ kiện đưa LLM/câu dự phòng: câu dịch mã (nếu có) trước, rồi kết quả công cụ."""
        giai_thich = [("giai_thich_ma", luot.giai_thich)] if luot.giai_thich is not None else []
        return [*giai_thich, *luot.results]

    def _van_ban_du_kien(self, luot: _Luot) -> list[str]:
        """Mọi chữ chứa số liệu được phép nhắc lại: câu người dùng, dữ kiện, giá trị trên thẻ, đoạn kiến thức.

        JSON dùng dấu phân cách mặc định (có khoảng trắng sau ":") để regex số của guard đọc được số thô.
        """
        van_ban = [luot.message]
        for _, ket_qua in self._ket_qua_du_kien(luot):
            van_ban.append(json.dumps(ket_qua.facts, ensure_ascii=False, default=str))
            for the in ket_qua.cards:
                van_ban.extend(muc.value for muc in the.items)
                van_ban.extend(o for hang in the.rows for o in hang)
                if the.label:
                    van_ban.append(the.label)
        van_ban.extend(chunk.body for chunk in luot.chunks)
        return van_ban

    def _phan_hoi_du_phong(self, luot: _Luot, mau: SuggestionDef | None, *, rut_gon: bool = False) -> ChatResponse:
        cau, dung_doan = self._cau_du_phong(luot, mau)
        # Căn cứ chỉ của nội dung thật sự hiện ra (fix-2): câu mẫu dựng từ dữ kiện công cụ không kèm căn cứ (và câu
        # miễn trừ pháp lý) của các đoạn kiến thức chỉ được tìm để đưa LLM.
        luot.chunks = luot.chunks[:1] if dung_doan else []
        return self._dung_phan_hoi(luot, f"{DONG_RUT_GON}\n{cau}" if rut_gon else cau, mode="fallback")

    def _phan_hoi_can_du_lieu_rut_gon(self, luot: _Luot) -> ChatResponse:
        """Đường C3 không có LLM và chưa chạy công cụ nào: không có dữ liệu để trả lời, nói rõ là chưa làm được."""
        luot.chunks = []
        cau = self._them_giai_thich(luot, CAU_CAN_DU_LIEU_RUT_GON.get(luot.surface, CAU_CAN_DU_LIEU_RUT_GON["mobile"]))
        return self._dung_phan_hoi(luot, f"{DONG_RUT_GON}\n{cau}", mode="fallback")

    @staticmethod
    def _them_giai_thich(luot: _Luot, cau: str) -> str:
        """Nối câu dịch mã lỗi vừa gặp/mã trong câu hỏi vào câu mẫu: đó là thứ người dùng đang hỏi (fix-2)."""
        dong = _dong_tu_the(luot.giai_thich) if luot.giai_thich is not None else []
        return f"{cau}\n\n" + "\n".join(f"- {d}" for d in dong) if dong else cau

    def _mau_cua_cong_cu(self, luot: _Luot) -> SuggestionDef | None:
        """Câu mẫu của công cụ đã chạy đầu tiên có câu mẫu, ưu tiên gợi ý của màn đang mở."""
        for ten, _ in luot.results:
            cung_tool = [g for g in self._intents if g.tool == ten and g.toolset in luot.toolsets]
            cung_tool.sort(key=lambda g: 0 if luot.screen in g.screens else 1)
            if cung_tool:
                return cung_tool[0]
        return None

    def _cau_du_phong(self, luot: _Luot, mau: SuggestionDef | None) -> tuple[str, bool]:
        """Câu thay thế khi không dùng được câu của LLM, kèm cờ "có dùng thân đoạn kiến thức đầu tiên không".

        Ưu tiên câu mẫu của gợi ý cùng công cụ; đường công cụ/câu chính sách (không có `mau`) thì lấy gợi ý khớp
        từ khóa trong số gợi ý của các công cụ đã chạy, không khớp thì câu mẫu của công cụ đã chạy (fix-2: trước
        đây in thẳng khóa dữ kiện không dấu "Tong so khoan vay: 3"). Không có câu mẫu thì ghép "Thông tin:" từ thẻ
        số liệu (nhãn tiếng Việt), thiếu thẻ thì từ dữ kiện, rồi thêm thân đoạn kiến thức đầu tiên.
        """
        if mau is None and luot.results:
            da_chay = {ten for ten, _ in luot.results}
            mau = match_intent(
                luot.message, [g for g in self._intents if g.tool in da_chay], luot.toolsets, luot.screen
            ) or self._mau_cua_cong_cu(luot)
        if mau is not None:
            ket_qua = next((kq for ten, kq in luot.results if ten == mau.tool), None)
            if ket_qua is not None:
                return self._them_giai_thich(luot, mau.render(ket_qua)), False
        dong: list[str] = []
        for _, ket_qua in self._ket_qua_du_kien(luot):
            dong.extend(_dong_tu_the(ket_qua) or _dong_du_kien(ket_qua.facts))
        phan: list[str] = []
        if dong:
            # Dữ kiện đã qua whitelist của công cụ; vẫn che PII một lần nữa vì đây là chữ ghép tự động.
            noi_dung = "\n".join(f"- {d}" for d in dong[:SO_DONG_DU_KIEN_DU_PHONG])
            phan.append("Thông tin:\n" + guard.scrub_user_text(noi_dung))
        if luot.chunks:
            phan.append(luot.chunks[0].body)
        return ("\n\n".join(phan) if phan else CAU_CHUA_DU_THONG_TIN), bool(luot.chunks)

    def _can_cu(self, luot: _Luot) -> list[Citation]:
        """Căn cứ từ công cụ và từ đoạn kiến thức đã đưa cho LLM; bỏ trùng theo (id, nhãn)."""
        thay: dict[tuple[str, str], Citation] = {}
        for _, ket_qua in luot.results:
            for can_cu in ket_qua.citations:
                thay.setdefault((can_cu.id, can_cu.label), can_cu)
        for chunk in luot.chunks:
            for can_cu in citations_for_chunk(chunk, self._legal_sources):
                thay.setdefault((can_cu.id, can_cu.label), can_cu)
        if any(ket_qua.facts.get("khong_tu_van") is True for _, ket_qua in luot.results):
            thay.setdefault((CAN_CU_KHONG_TU_VAN.id, CAN_CU_KHONG_TU_VAN.label), CAN_CU_KHONG_TU_VAN)
        return _bo_trung_can_cu(list(thay.values()))

    def _gia_tri_tham_so(self, luot: _Luot, ten: str) -> str | None:
        """Giá trị cho tham số `ten` của một nút: màn đang mở trước, rồi tham số/nút/dữ kiện của lượt.

        Chỉ dùng khi cả lượt có đúng MỘT giá trị, để không gắn nút "Mở hồ sơ" vào nhầm hồ sơ khác.
        """
        cac_ten = (ten, *_BI_DANH_THAM_SO.get(ten, ()))
        for ten_context in cac_ten:
            if luot.params.get(ten_context):
                return luot.params[ten_context]
        ung_vien: set[str] = set()
        for tham_so in luot.tham_so_da_dung:
            ung_vien.update(v for k, v in tham_so.items() if k in cac_ten)
        for _, ket_qua in luot.results:
            for hanh_dong in ket_qua.actions:
                ung_vien.update(v for k, v in hanh_dong.target.params.items() if k in cac_ten)
        if not ung_vien:
            tim_thay = _tim_trong_du_kien(luot.results, ten)
            if tim_thay:
                ung_vien.add(tim_thay)
        return next(iter(ung_vien)) if len(ung_vien) == 1 else None

    def _hanh_dong_tu_khoa(self, luot: _Luot, khoa: str) -> Action | None:
        if khoa not in ACTIONS or ACTIONS[khoa].surface != luot.surface:
            return None
        tham_so: dict[str, str] = {}
        for ten in ACTIONS[khoa].params:
            gia_tri = self._gia_tri_tham_so(luot, ten)
            if gia_tri:
                tham_so[ten] = gia_tri
        try:
            return build_action(khoa, tham_so)
        except ValueError:
            return None  # thiếu tham số hoặc giá trị lạ: bỏ nút, không gửi đích hỏng cho client

    def _hanh_dong_khi_loi(self, luot: _Luot) -> list[Action]:
        khoa = _HANH_DONG_KHI_LOI.get(luot.tool_dang_chay or "")
        hanh_dong = self._hanh_dong_tu_khoa(luot, khoa) if khoa else None
        return [hanh_dong] if hanh_dong is not None else []

    def _dung_phan_hoi(self, luot: _Luot, answer: str, *, mode: str, khoa_llm: Sequence[str] = (),
                       goi_y_hien_tai: str | None = None, them_hanh_dong: Sequence[Action] = (),
                       hanh_dong_truoc: Sequence[Action] = ()) -> ChatResponse:
        """`hanh_dong_truoc`: nút chính của câu mẫu (ví dụ "Thanh toán khoản vay" khi từ chối trả nợ thay),
        đứng trước nút của công cụ tra cứu."""
        hanh_dong: list[Action] = []
        da_co: set[tuple[str, str]] = set()
        ung_vien = list(hanh_dong_truoc) + [a for _, kq in luot.results for a in kq.actions] + list(them_hanh_dong)
        ung_vien += [a for a in (self._hanh_dong_tu_khoa(luot, k) for k in khoa_llm) if a is not None]
        for a in ung_vien:
            dau = (a.key, a.target.model_dump_json())
            if dau not in da_co:
                da_co.add(dau)
                hanh_dong.append(a)
        can_cu = self._can_cu(luot)
        mien_tru = [MIEN_TRU_DAU_TU] if luot.investor else []
        if any(c.kind == "LUAT" for c in can_cu):
            mien_tru.append(MIEN_TRU_PHAP_LY)
        return ChatResponse(
            turn_id=luot.turn_id,
            answer=answer,
            cards=[the for _, kq in luot.results for the in kq.cards][:SO_THE_TOI_DA],
            actions=hanh_dong[:SO_HANH_DONG_TOI_DA],
            citations=can_cu,
            suggestions=[
                SuggestionItem(id=g.id, label=g.label)
                for g in self._registry.suggestions_for(luot.toolsets, luot.screen)
                if g.id != goi_y_hien_tai
            ],
            mode=mode,
            disclaimer=" ".join(mien_tru) or None,
        )

    # ── Chống bấm hai lần và log ───────────────────────────────────────────────────────────────

    def _khoa_chong_trung(self, user_hash: str, toolsets: frozenset[Toolset], request: ChatRequest) -> str:
        """Khóa theo (người, vai trò, nơi, màn, tham số màn, mã lỗi vừa gặp, lịch sử, gợi ý, câu hỏi).

        Có thêm tham số màn so với spec: cùng câu "sao chưa giải ngân?" ở hai hồ sơ khác nhau trong 60 giây
        không được nhận chung một câu trả lời. Lịch sử cũng vào khóa: câu nối tiếp "còn thiếu bao nhiêu?"
        hỏi về hai hồ sơ khác nhau trong 60 giây là hai câu khác nhau; bấm gửi hai lần thì app gửi y hệt
        lịch sử nên vẫn bị gộp.
        """
        ctx = request.context
        phan = [
            user_hash,
            ",".join(sorted(t.value for t in toolsets)),
            request.surface,
            (ctx.screen if ctx else None) or "",
            json.dumps(sorted((ctx.params if ctx else {}).items()), ensure_ascii=False),
            (ctx.last_error_code if ctx else None) or "",
            json.dumps([(t.role, t.content) for t in request.history], ensure_ascii=False),
            request.suggestion_id or "",
            " ".join(request.message.lower().split()),
        ]
        return hashlib.sha256("\x1f".join(phan).encode("utf-8")).hexdigest()

    def _lay_cache(self, khoa: str) -> ChatResponse | None:
        muc = self._cache.get(khoa)
        if muc is None:
            return None
        het_han, phan_hoi = muc
        if het_han <= self._clock():
            self._cache.pop(khoa, None)
            return None
        return phan_hoi

    def _luu_cache(self, khoa: str, phan_hoi: ChatResponse) -> None:
        bay_gio = self._clock()
        for k in [k for k, (het_han, _) in self._cache.items() if het_han <= bay_gio]:
            self._cache.pop(k, None)
        while len(self._cache) >= SO_MUC_CHONG_TRUNG_TOI_DA:
            self._cache.pop(next(iter(self._cache)))
        self._cache[khoa] = (bay_gio + THOI_GIAN_CHONG_TRUNG_S, phan_hoi)

    def _ghi_log(self, luot: _Luot, mode: str, bat_dau: float) -> None:
        log_turn(
            turn_id=luot.turn_id, user_hash=luot.user_hash, toolsets=luot.role_names, surface=luot.surface,
            screen=luot.screen, mode=mode, tools=luot.tools_called, provider=luot.provider, model=luot.model,
            prompt_tokens=luot.prompt_tokens, completion_tokens=luot.completion_tokens,
            latency_ms=int((self._clock() - bat_dau) * 1000), error_code=luot.error_code,
        )

    def _ghi_log_trung(self, cu: ChatResponse, user_hash: str, toolsets: frozenset[Toolset],
                       request: ChatRequest, bat_dau: float) -> None:
        log_turn(
            turn_id=cu.turn_id, user_hash=user_hash, toolsets=[t.value for t in toolsets], surface=request.surface,
            screen=request.context.screen if request.context else None, mode=cu.mode, tools=[], provider=None,
            model=None, prompt_tokens=0, completion_tokens=0,
            latency_ms=int((self._clock() - bat_dau) * 1000), error_code="DEDUPE_HIT",
        )


def get_orchestrator() -> AssistantOrchestrator:
    """Orchestrator dùng chung của tiến trình; dựng không được thì báo 503 có mã thay vì HTTP 500 trần.

    Kho kiến thức YAML hỏng hay cấu hình sai làm việc dựng lỗi; lifespan chỉ ghi log để dịch vụ chấm điểm vẫn
    chạy, nên lỗi hiện lại ở request. Client đọc `{code, message}` như mọi lỗi khác (spec mục 5) và ẩn nút
    trợ lý khi `/suggestions` không trả 200. `lru_cache` không nhớ ngoại lệ: request sau thử dựng lại.
    """
    try:
        return _dung_orchestrator()
    except Exception as loi:
        logger.error("Không dựng được Trợ lý FINORA loai=%s noi=%s", type(loi).__name__, _noi_xay_ra(loi))
        raise ApiError(503, "ASSISTANT_UNAVAILABLE", "Trợ lý FINORA tạm gián đoạn, bạn thử lại sau ít phút.") from loi


@lru_cache(maxsize=1)
def _dung_orchestrator() -> AssistantOrchestrator:
    """Một orchestrator cho cả tiến trình (uvicorn một worker), dựng lười ở request đầu hoặc lúc khởi động."""
    return AssistantOrchestrator(
        get_settings(),
        default_registry(),
        get_index(),
        get_llm_router(),
        load_legal_sources(),
        intents=(*BORROWER_SUGGESTIONS, *INVESTOR_SUGGESTIONS, *ADMIN_SUGGESTIONS),
    )
