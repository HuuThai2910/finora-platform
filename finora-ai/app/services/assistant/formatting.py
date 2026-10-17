"""Định dạng số, tiền, phần trăm, ngày giờ theo kiểu Việt Nam cho thẻ số liệu và dữ kiện gửi LLM.

Số trên thẻ do code định dạng, không để LLM tự viết, nên mọi tool dùng chung một bộ hàm này.
Dữ kiện gửi LLM cũng là chuỗi đã định dạng, nhờ vậy câu trả lời chép lại đúng y con số và bộ
kiểm số liệu (`guard.numbers_in`) so được với thẻ.

Chú thích kiểu dùng `Decimal | float | str | None` (không thêm `int`): theo PEP 484 `float` đã
bao gồm `int` trong chữ ký kiểu, viết cả hai bị ruff báo PYI041 (redundant-numeric-union); hành
vi và cách gọi không đổi — `tien_vnd(2350000)` với `int` vẫn hợp lệ và chạy đúng.

Quy ước: nhóm nghìn bằng dấu chấm, thập phân bằng dấu phẩy, làm tròn nửa lên (ROUND_HALF_UP,
như cách người dùng tính tay), bỏ số 0 thừa sau dấu phẩy. Giá trị thiếu hoặc không đọc được
hiện "—" thay vì ném lỗi: một trường hỏng không nên làm hỏng cả câu trả lời.
"""

from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

KHONG_CO = "—"

# Việt Nam dùng UTC+7 cố định, không có giờ mùa hè từ 1975, nên offset cố định cho cùng kết quả
# với ZoneInfo("Asia/Ho_Chi_Minh") mà không phụ thuộc gói tzdata (image python:3.12-slim và
# Windows đều có thể thiếu).
VN_TZ = timezone(timedelta(hours=7), "Asia/Ho_Chi_Minh")


def to_decimal(value: object) -> Decimal | None:
    """Đổi số từ JSON (int, float, chuỗi số) hoặc Decimal sang Decimal; không đọc được thì None.

    float đi qua `repr` để 14.555 giữ đúng 14.555 thay vì 14.554999… của số nhị phân, nhờ vậy
    làm tròn khớp với số người dùng nhìn thấy trong app.
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, Decimal):
        number = value
    elif isinstance(value, int):
        number = Decimal(value)
    elif isinstance(value, float):
        number = Decimal(repr(value))
    elif isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            number = Decimal(text)
        except InvalidOperation:
            return None
    else:
        return None
    return number if number.is_finite() else None


def tien_vnd(value: Decimal | float | str | None) -> str:
    """Tiền đồng làm tròn tới đồng: 2350000 -> "2.350.000 đ"; None -> "—"."""
    number = to_decimal(value)
    if number is None:
        return KHONG_CO
    text = _dinh_dang(number, 0)
    return KHONG_CO if text is None else f"{text} đ"


def phan_tram(value: Decimal | float | str | None, digits: int = 2) -> str:
    """Giá trị đã là phần trăm: 14.5 -> "14,5%"; 12.00 -> "12%"; làm tròn `digits` chữ số."""
    if digits < 0:
        raise ValueError("digits phải >= 0")
    number = to_decimal(value)
    if number is None:
        return KHONG_CO
    text = _dinh_dang(number, digits)
    return KHONG_CO if text is None else f"{text}%"


def so_nguyen(value: int | Decimal | str | None) -> str:
    """Số đếm có nhóm nghìn: 1234 -> "1.234"."""
    number = to_decimal(value)
    if number is None:
        return KHONG_CO
    text = _dinh_dang(number, 0)
    return KHONG_CO if text is None else text


def ngay_vn(value: str | date | datetime | None) -> str:
    """Ngày theo giờ Việt Nam: "25/10/2026".

    Chuỗi chỉ có ngày ("2026-10-25", kiểu LocalDate của Java/Fineract) giữ nguyên ngày, không đổi
    múi giờ. Thời điểm có offset ("…Z", "…+07:00") đổi sang giờ Việt Nam. Thời điểm không offset
    được coi là UTC, vì các service Java trả `Instant` theo UTC và DB lưu UTC.
    """
    parsed = _doc_thoi_diem(value)
    if parsed is None:
        return KHONG_CO
    if isinstance(parsed, datetime):
        parsed = _sang_gio_vn(parsed).date()
    return f"{parsed.day:02d}/{parsed.month:02d}/{parsed.year:04d}"


def ngay_gio_vn(value: str | datetime | None) -> str:
    """Ngày giờ theo giờ Việt Nam: "25/10/2026 14:30". Chuỗi chỉ có ngày trả về phần ngày."""
    parsed = _doc_thoi_diem(value)
    if parsed is None:
        return KHONG_CO
    if not isinstance(parsed, datetime):
        return ngay_vn(parsed)
    local = _sang_gio_vn(parsed)
    return f"{local.day:02d}/{local.month:02d}/{local.year:04d} {local.hour:02d}:{local.minute:02d}"


def _dinh_dang(number: Decimal, digits: int) -> str | None:
    """Làm tròn rồi viết kiểu Việt Nam; None nếu số quá lớn để làm tròn (vượt độ chính xác)."""
    try:
        rounded = number.quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_UP)
    except InvalidOperation:
        return None
    if rounded == 0:
        # -0,4 đ làm tròn thành "-0"; người đọc chỉ cần thấy 0.
        rounded = abs(rounded)
    negative = rounded < 0
    integer_part, _, fraction = f"{abs(rounded):f}".partition(".")
    fraction = fraction.rstrip("0")
    grouped = f"{int(integer_part):,}".replace(",", ".")
    text = f"{grouped},{fraction}" if fraction else grouped
    return f"-{text}" if negative else text


def _doc_thoi_diem(value: str | date | datetime | None) -> date | datetime | None:
    if value is None:
        return None
    if isinstance(value, (datetime, date)):
        return value
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    try:
        if len(text) == 10:
            return date.fromisoformat(text)
        # Python 3.11+ đọc được "Z" và phần lẻ giây tới 9 chữ số của Jackson.
        return datetime.fromisoformat(text)
    except ValueError:
        return None


def _sang_gio_vn(moment: datetime) -> datetime:
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(VN_TZ)
