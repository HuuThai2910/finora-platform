"""Schema JSON của Trợ lý FINORA (`/api/v1/ai/assistant/*`).

JSON đi ra/vào dùng camelCase giống các service Java và hai app; code Python giữ snake_case.
`alias_generator=to_camel` lo phần đổi tên, `populate_by_name=True` cho phép code phía server
dựng model bằng tên snake_case. FastAPI xuất response theo alias (camelCase) mặc định.

Giới hạn độ dài nằm ngay ở schema để request quá khổ bị chặn bằng 422 trước khi tốn công
gọi service nghiệp vụ hay LLM.
"""

import re
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

MAX_MESSAGE_CHARS = 1000
MAX_HISTORY_TURNS = 6
MAX_HISTORY_CONTENT_CHARS = 2000
MAX_CONTEXT_PARAMS = 8

# Khóa tham số màn hình theo kiểu camelCase của navigation (applicationNumber, listingId...).
_KHOA_THAM_SO = re.compile(r"[A-Za-z][A-Za-z0-9]{0,39}")
# Mã nghiệp vụ: LA-…, LC-…, LN-…, số id, ngày ISO. Không cho khoảng trắng, dấu "/" hay "?"
# để giá trị từ app không thể bẻ đường dẫn khi được ghép vào URL gọi service.
MA_NGHIEP_VU = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")

Surface = Literal["mobile", "web"]
CitationKind = Literal["LUAT", "CHINH_SACH", "CHUA_XAC_MINH", "HUONG_DAN"]


class CamelModel(BaseModel):
    """Gốc chung: JSON camelCase, Python snake_case."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ChatTurn(CamelModel):
    """Một lượt cũ client gửi kèm. Server chỉ coi là ngữ cảnh hội thoại, không coi là dữ kiện."""

    role: Literal["user", "assistant"]
    content: str = Field(max_length=MAX_HISTORY_CONTENT_CHARS)


class ScreenContext(CamelModel):
    """Màn người dùng đang mở và mã nghiệp vụ trên màn đó."""

    screen: str | None = Field(default=None, max_length=64)
    params: dict[str, str] = Field(default_factory=dict)
    last_error_code: str | None = Field(default=None, max_length=80)

    @field_validator("params")
    @classmethod
    def _chi_nhan_ma_nghiep_vu(cls, params: dict[str, str]) -> dict[str, str]:
        # Kiểu dict[str, str] đã từ chối số/bool (Pydantic 2 không tự đổi số thành chuỗi).
        # Ở đây chặn thêm khóa lạ và giá trị có ký tự có thể chèn vào URL hoặc prompt.
        if len(params) > MAX_CONTEXT_PARAMS:
            raise ValueError(f"Tối đa {MAX_CONTEXT_PARAMS} tham số màn hình")
        for key, value in params.items():
            if not _KHOA_THAM_SO.fullmatch(key):
                raise ValueError(f"Khóa tham số không hợp lệ: {key[:40]}")
            if not MA_NGHIEP_VU.fullmatch(value):
                raise ValueError(f"Giá trị tham số {key} không phải mã nghiệp vụ hợp lệ")
        return params


class ChatRequest(CamelModel):
    """Body của `POST /chat`."""

    # strip_whitespace chạy trước kiểm độ dài, nên "   " bị coi là rỗng và bị từ chối.
    message: Annotated[
        str,
        StringConstraints(strip_whitespace=True, min_length=1, max_length=MAX_MESSAGE_CHARS),
    ]
    history: list[ChatTurn] = Field(default_factory=list, max_length=MAX_HISTORY_TURNS)
    surface: Surface
    context: ScreenContext | None = None
    suggestion_id: str | None = Field(default=None, max_length=80, pattern=r"^[a-z0-9_.]+$")


class CardItem(CamelModel):
    label: str
    value: str


class Card(CamelModel):
    """Thẻ số liệu. Mọi giá trị hiển thị là chuỗi server đã định dạng (`formatting.py`)."""

    kind: Literal["kv", "table", "progress"]
    title: str
    items: list[CardItem] = Field(default_factory=list)
    columns: list[str] = Field(default_factory=list)
    rows: list[list[str]] = Field(default_factory=list)
    value: float | None = None
    max: float | None = None
    label: str | None = None


class ActionTarget(CamelModel):
    """Đích điều hướng: mobile dùng `screen` + `params`, web dùng `path`."""

    screen: str | None = None
    params: dict[str, str] = Field(default_factory=dict)
    path: str | None = None


class Action(CamelModel):
    key: str
    label: str
    target: ActionTarget


class Citation(CamelModel):
    """Căn cứ của câu trả lời. Code gắn từ đoạn kiến thức, không để LLM tự viết."""

    id: str
    kind: CitationKind
    label: str
    url: str | None = None
    checked_at: str

    @model_validator(mode="after")
    def _luat_phai_co_link(self) -> "Citation":
        # Spec mục 5: căn cứ pháp luật bắt buộc có link văn bản chính thức để người đọc tự kiểm.
        if self.kind == "LUAT" and not self.url:
            raise ValueError("Căn cứ loại LUAT phải có url văn bản chính thức")
        return self


class SuggestionItem(CamelModel):
    id: str
    label: str


class ChatResponse(CamelModel):
    """Body trả về của `POST /chat`."""

    turn_id: str
    answer: str
    cards: list[Card] = Field(default_factory=list)
    actions: list[Action] = Field(default_factory=list)
    citations: list[Citation] = Field(default_factory=list)
    suggestions: list[SuggestionItem] = Field(default_factory=list)
    mode: Literal["template", "llm", "fallback"]
    disclaimer: str | None = None


class FeedbackRequest(CamelModel):
    """Body của `POST /feedback`. Chỉ ghi log siêu dữ liệu, không lưu nội dung."""

    turn_id: str = Field(min_length=1, max_length=64)
    rating: Literal["up", "down"]
