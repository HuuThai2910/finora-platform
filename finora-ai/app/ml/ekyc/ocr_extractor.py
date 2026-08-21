"""Trích xuất thông tin từ ảnh CCCD Việt Nam bằng EasyOCR."""

import logging
import re

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
RE_GENDER = re.compile(r"\b(Nam|Nữ|Male|Female)\b", re.IGNORECASE)

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
            "confidence": 0.0,
        }

        try:
            image = decode_image(image_bytes)
            if image is None:
                logger.warning("Không thể giải mã ảnh.")
                return result

            reader = self._get_reader()
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
            full_text = " ".join(texts)

            # Trích xuất số CCCD trên bản sao đã chuẩn hoá ký tự dễ nhầm:
            # chỉ cần đọc "O79..." thay vì "079..." là hỏng cả lần xác minh.
            result["id_number"] = find_id_number(texts)

            # Trích xuất ngày sinh
            date_match = RE_DATE.search(full_text)
            if date_match:
                result["date_of_birth"] = date_match.group(1)

            # Trích xuất giới tính
            gender_match = RE_GENDER.search(full_text)
            if gender_match:
                raw = gender_match.group().lower()
                result["gender"] = "Nam" if raw in ("nam", "male") else "Nữ"

            # Trích xuất họ tên
            result["full_name"] = self._extract_name(texts)

            # Trích xuất quê quán
            result["place_of_origin"] = self._extract_place(texts)

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
        for i, text in enumerate(texts):
            lower = text.lower()
            if "họ và tên" in lower or "full name" in lower:
                parts = text.split(":", 1)
                if len(parts) > 1 and parts[1].strip():
                    return parts[1].strip().upper()
                if i + 1 < len(texts):
                    return texts[i + 1].strip().upper()

        candidates = [t.strip() for t in texts if is_name_like(t)]
        return max(candidates, key=len).upper() if candidates else None

    def _extract_place(self, texts: list[str]) -> str | None:
        """Tìm quê quán từ danh sách text đã OCR."""
        for i, text in enumerate(texts):
            lower = text.lower()
            if "quê quán" in lower or "place of origin" in lower:
                parts = text.split(":", 1)
                if len(parts) > 1 and parts[1].strip():
                    return parts[1].strip()
                if i + 1 < len(texts):
                    return texts[i + 1].strip()
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
