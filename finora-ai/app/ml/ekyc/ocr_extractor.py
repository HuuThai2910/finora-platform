"""Trích xuất thông tin từ ảnh CCCD Việt Nam bằng EasyOCR."""

import logging
import re
import unicodedata

from numpy.typing import NDArray

from app.ml.ekyc.image_io import decode_image, require_cv2, resize_to_width
from app.ml.ekyc.thresholds import OCR_RESIZE_WIDTH

try:
    import cv2
except ImportError:
    cv2 = None  # type: ignore[assignment]

try:
    import easyocr
except ImportError:
    easyocr = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

# Regex patterns cho CCCD Việt Nam
RE_CCCD_NUMBER = re.compile(r"\b0\d{11}\b")
RE_DATE = re.compile(r"\b(\d{2}[/-]\d{2}[/-]\d{4})\b")

# Nhãn trường trên phôi thẻ, đã bỏ dấu + thường hoá. Gồm cả cách gọi của thẻ
# CCCD 2021 (quê quán, nơi thường trú — in mặt trước) lẫn thẻ căn cước 2024
# (nơi đăng ký khai sinh, nơi cư trú — in mặt sau).
LABELS_NAME = ("ho va ten", "full name")
LABELS_DOB = ("ngay sinh", "date of birth")
LABELS_GENDER = ("gioi tinh", "sex")
LABELS_ORIGIN = ("que quan", "place of origin", "noi dang ky khai sinh", "place of birth")
LABELS_ADDRESS = ("noi thuong tru", "place of residence", "noi cu tru")

# Dòng chứa các từ này là hạn thẻ/ngày cấp — ngày trên đó không phải ngày sinh
EXPIRY_HINTS = ("gia tri", "expiry", "het han", "ngay cap", "date of issue")

# Ký tự OCR hay đọc nhầm trong chuỗi số. Chỉ áp dụng cho bản sao dùng để dò số
# CCCD — không đụng tới text gốc, nếu không họ tên sẽ bị bóp méo.
DIGIT_CONFUSABLES = str.maketrans({
    "O": "0", "o": "0", "Q": "0", "D": "0",
    "I": "1", "l": "1", "|": "1",
    "S": "5", "s": "5",
    "B": "8",
    "Z": "2",
})

# Dấu phân cách chen giữa hai chữ số — CCCD thường in thành nhóm "079 204 001 234"
RE_DIGIT_SEPARATOR = re.compile(r"(?<=\d)[\s.\-](?=\d)")

# Dòng tiêu đề trên phôi thẻ, không bao giờ là họ tên
HEADER_KEYWORDS = (
    "CỘNG HÒA", "CHỦ NGHĨA", "ĐỘC LẬP", "CĂN CƯỚC", "CÔNG DÂN",
    "SOCIALIST", "REPUBLIC", "IDENTITY", "CITIZEN", "VIET NAM", "VIỆT NAM",
)


class OcrExtractor:
    """Trích xuất thông tin từ ảnh CCCD sử dụng EasyOCR."""

    def __init__(self):
        # Không chú thích kiểu easyocr.Reader ở đây: annotation được đánh giá
        # lúc chạy, sẽ nổ AttributeError khi easyocr chưa được cài.
        self._reader = None

    def _get_reader(self):
        if self._reader is None:
            if easyocr is None:
                raise RuntimeError("easyocr chưa được cài đặt.")
            logger.info("Khởi tạo EasyOCR reader (vi, en)...")
            self._reader = easyocr.Reader(["vi", "en"], gpu=False)
            logger.info("EasyOCR reader sẵn sàng.")
        return self._reader

    def extract(self, image_bytes: bytes) -> dict:
        """Trích xuất thông tin CCCD từ ảnh bytes.

        Returns:
            dict với keys: success, id_number, full_name, date_of_birth,
            gender, place_of_origin, confidence
        """
        result = {
            "success": False,
            "id_number": None,
            "full_name": None,
            "date_of_birth": None,
            "gender": None,
            "place_of_origin": None,
            "address": None,
            "confidence": 0.0,
        }

        # Lỗi hạ tầng (thiếu easyocr/opencv) phải nổ ra ngoài để backend trả
        # AI_UNAVAILABLE — nuốt vào success=False sẽ thành "ảnh mờ, chụp lại"
        # và người dùng chụp lại bao nhiêu lần cũng vô ích.
        require_cv2()
        reader = self._get_reader()

        try:
            image = decode_image(image_bytes)
            if image is None:
                logger.warning("Không thể giải mã ảnh.")
                return result

            detections = reader.readtext(self.preprocess(image))

            if not detections:
                logger.warning("EasyOCR không phát hiện text nào.")
                return result

            texts = []
            total_conf = 0.0
            for _bbox, text, conf in detections:
                texts.append(text.strip())
                total_conf += conf

            avg_conf = total_conf / len(detections) if detections else 0.0

            # Trích xuất số CCCD trên bản sao đã chuẩn hoá ký tự dễ nhầm:
            # chỉ cần đọc "O79..." thay vì "079..." là hỏng cả lần xác minh.
            result["id_number"] = find_id_number(texts)

            result["date_of_birth"] = find_dob(texts)
            result["gender"] = find_gender(texts)
            result["full_name"] = self._extract_name(texts)
            result["place_of_origin"] = single_line_value(texts, LABELS_ORIGIN)
            result["address"] = multi_line_value(texts, LABELS_ADDRESS, max_lines=2)

            result["confidence"] = round(avg_conf, 4)
            result["success"] = result["id_number"] is not None

        except Exception:
            logger.exception("Lỗi khi OCR ảnh CCCD.")

        return result

    @staticmethod
    def preprocess(image: NDArray) -> NDArray:
        """Chuẩn hoá ảnh trước khi OCR: thu nhỏ, chuyển xám, cân bằng tương phản.

        Ảnh CCCD chụp bằng điện thoại thường loá và sáng không đều; CLAHE cân
        bằng tương phản theo từng ô nhỏ nên xử lý được vùng loá cục bộ mà cân
        bằng histogram toàn ảnh không làm được.
        """
        require_cv2()
        resized = resize_to_width(image, OCR_RESIZE_WIDTH)
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY) if resized.ndim == 3 else resized
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        return clahe.apply(gray)

    def _extract_name(self, texts: list[str]) -> str | None:
        """Tìm họ tên từ danh sách text đã OCR.

        Ưu tiên dòng có nhãn "Họ và tên". EasyOCR hay cắt nhãn và giá trị thành
        hai dòng rời nhau, nên nếu không thấy nhãn thì rơi về heuristic: dòng
        in hoa dài nhất, không chứa số và không phải tiêu đề phôi thẻ.
        """
        labeled = single_line_value(texts, LABELS_NAME)
        if labeled:
            return labeled.upper()

        candidates = [t.strip() for t in texts if is_name_like(t)]
        return max(candidates, key=len).upper() if candidates else None


def strip_accents(text: str) -> str:
    """Bỏ dấu tiếng Việt. Riêng ``đ/Đ`` không phải ký tự có dấu ghép nên đổi tay."""
    stripped = "".join(
        c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn"
    )
    return stripped.replace("đ", "d").replace("Đ", "D")


def norm_text(text: str) -> str:
    """Chuẩn hoá một dòng OCR để so nhãn: bỏ dấu + thường hoá.

    EasyOCR đọc nhãn tiếng Việt thường rơi rụng dấu ("Quê quán" → "Que quan"),
    nên mọi phép so nhãn phải chạy trên bản không dấu.
    """
    return strip_accents(text).lower()


ALL_LABELS = LABELS_NAME + LABELS_DOB + LABELS_GENDER + LABELS_ORIGIN + LABELS_ADDRESS + EXPIRY_HINTS


def is_label_line(text: str) -> bool:
    """Dòng này có mở đầu một trường khác trên phôi thẻ không."""
    norm = norm_text(text)
    return any(label in norm for label in ALL_LABELS)


def single_line_value(texts: list[str], labels: tuple[str, ...]) -> str | None:
    """Giá trị một dòng của trường có nhãn: sau dấu ``:`` hoặc ở dòng kế tiếp."""
    for i, text in enumerate(texts):
        if not any(label in norm_text(text) for label in labels):
            continue
        parts = text.split(":", 1)
        if len(parts) > 1 and parts[1].strip():
            return parts[1].strip()
        if i + 1 < len(texts) and texts[i + 1].strip() and not is_label_line(texts[i + 1]):
            return texts[i + 1].strip()
        return None
    return None


def multi_line_value(texts: list[str], labels: tuple[str, ...], max_lines: int) -> str | None:
    """Giá trị có thể tràn nhiều dòng (địa chỉ thường trú in thành 2 dòng).

    Gom từ phần sau dấu ``:`` và tối đa ``max_lines`` dòng kế tiếp, dừng khi
    gặp dòng mở đầu trường khác.
    """
    for i, text in enumerate(texts):
        if not any(label in norm_text(text) for label in labels):
            continue
        parts = text.split(":", 1)
        value = parts[1].strip() if len(parts) > 1 else ""
        for j in range(i + 1, min(i + 1 + max_lines, len(texts))):
            line = texts[j].strip()
            if not line or is_label_line(line):
                break
            value = f"{value} {line}".strip()
        return value or None
    return None


def find_dob(texts: list[str]) -> str | None:
    """Tìm ngày sinh: ưu tiên dòng có nhãn, tránh nhặt nhầm hạn thẻ/ngày cấp.

    Phôi thẻ in cả "Có giá trị đến" — lấy đại ngày đầu tiên trong toàn văn bản
    sẽ dính ngày hết hạn nếu OCR trả dòng đó trước dòng ngày sinh.
    """
    for i, text in enumerate(texts):
        if not any(label in norm_text(text) for label in LABELS_DOB):
            continue
        match = RE_DATE.search(text)
        if not match and i + 1 < len(texts):
            match = RE_DATE.search(texts[i + 1])
        if match:
            return match.group(1)

    for text in texts:
        if any(hint in norm_text(text) for hint in EXPIRY_HINTS):
            continue
        match = RE_DATE.search(text)
        if match:
            return match.group(1)
    return None


def find_gender(texts: list[str]) -> str | None:
    """Tìm giới tính, chỉ trong dòng có nhãn "Giới tính/Sex" (hoặc dòng kế).

    Không dò trên toàn văn bản: chữ "Nam" nằm sẵn trong "VIỆT NAM" của tiêu đề
    và dòng quốc tịch, dò toàn cục sẽ trả "Nam" cho tất cả mọi người.
    """
    for i, text in enumerate(texts):
        if not any(label in norm_text(text) for label in LABELS_GENDER):
            continue
        candidates = [norm_text(text)]
        if i + 1 < len(texts):
            candidates.append(norm_text(texts[i + 1]))
        for candidate in candidates:
            match = re.search(r"\b(nam|nu|male|female)\b", candidate)
            if match:
                return "Nam" if match.group(1) in ("nam", "male") else "Nữ"
    return None


def find_id_number(texts: list[str]) -> str | None:
    """Dò số CCCD 12 chữ số theo **từng dòng** OCR.

    Không dò trên chuỗi đã nối tất cả các dòng: sau khi bỏ dấu phân cách giữa
    các chữ số, năm sinh ở dòng trước có thể dính liền số CCCD thành một dãy số
    dài hơn 12 chữ số, làm mẫu ``\\b0\\d{11}\\b`` không còn khớp.
    """
    for line in texts:
        match = RE_CCCD_NUMBER.search(normalize_digit_text(line))
        if match:
            return match.group()
    return None


def normalize_digit_text(text: str) -> str:
    """Bản sao của text dùng riêng cho việc dò chuỗi số.

    Đổi ký tự dễ nhầm sang chữ số rồi bỏ dấu phân cách chen giữa hai chữ số,
    để "O79 204 001 234" vẫn khớp được mẫu số CCCD 12 chữ số.
    """
    return RE_DIGIT_SEPARATOR.sub("", text.translate(DIGIT_CONFUSABLES))


def is_name_like(text: str) -> bool:
    """Đoán một dòng OCR có phải họ tên in hoa trên CCCD không."""
    candidate = text.strip()
    if len(candidate) < 6 or len(candidate.split()) < 2:
        return False
    if any(ch.isdigit() for ch in candidate):
        return False
    if candidate != candidate.upper():
        return False
    upper = candidate.upper()
    return not any(keyword in upper for keyword in HEADER_KEYWORDS)
