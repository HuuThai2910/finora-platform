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
# Ngày trên ảnh thật hay bị OCR chèn khoảng trắng quanh dấu phân cách
# ("24 / 08 / 2004") hoặc đọc "/" thành "." — nhận cả các biến thể đó.
RE_DATE = re.compile(r"\b(\d{2})\s*[/\-.]\s*(\d{2})\s*[/\-.]\s*(\d{4})\b")

# Các trường mềm — thiếu trường nào thì lượt OCR độ phân giải cao vớt trường đó
SOFT_FIELDS = ("full_name", "date_of_birth", "gender", "place_of_origin", "address")

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

# Dòng tiêu đề trên phôi thẻ, không bao giờ là họ tên. So trên bản đã bỏ dấu
# vì OCR thường đọc rụng dấu ("CĂN CƯỚC" → "CAN CUOC").
HEADER_KEYWORDS = (
    "cong hoa", "chu nghia", "doc lap", "can cuoc", "cong dan",
    "socialist", "republic", "identity", "citizen", "viet nam",
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

            # mag_ratio phóng đại nội bộ giúp detector bắt được dòng chữ nhỏ
            # trên ảnh mềm nét; adjust_contrast cứu dòng in nhạt trên phôi thẻ.
            detections = reader.readtext(
                self.preprocess(image), mag_ratio=1.5, adjust_contrast=0.7
            )
            texts = merge_rows(detections)

            if not detections:
                logger.warning("EasyOCR không phát hiện text nào.")
                return result

            total_conf = sum(conf for _bbox, _text, conf in detections)
            avg_conf = total_conf / len(detections) if detections else 0.0

            # Trích xuất số CCCD trên bản sao đã chuẩn hoá ký tự dễ nhầm:
            # chỉ cần đọc "O79..." thay vì "079..." là hỏng cả lần xác minh.
            result["id_number"] = find_id_number(texts)

            result["date_of_birth"] = find_dob(texts)
            result["gender"] = find_gender(texts)
            result["full_name"] = self._extract_name(texts)
            result["place_of_origin"] = single_line_value(texts, LABELS_ORIGIN)
            result["address"] = multi_line_value(texts, LABELS_ADDRESS, max_lines=2)

            # Ảnh thật chất lượng thấp hay đọc sót vài trường mềm. Chạy thêm một
            # lượt OCR ở độ phân giải cao hơn để vớt đúng những trường còn thiếu
            # — chỉ tốn thêm thời gian khi lượt đầu đọc không đủ.
            missing = [key for key in SOFT_FIELDS if not result[key]]
            if result["id_number"] is not None and missing:
                self._fill_from_hires_pass(image, missing, result)

            result["confidence"] = round(avg_conf, 4)
            result["success"] = result["id_number"] is not None

            # Chỉ log CÓ/KHÔNG cho từng trường (không log giá trị — PII) để
            # chẩn đoán được ảnh thật đọc thiếu trường nào.
            # size cho biết ảnh client gửi đã crop theo khung ngắm chưa:
            # đã crop ≈ 1280x950, chưa crop (nguyên khung sensor dọc) ≈ 1280x1707.
            logger.info(
                "OCR CCCD: size=%dx%d, rows=%d, conf=%.2f, id=%s, name=%s, dob=%s, gender=%s, origin=%s, address=%s",
                image.shape[1], image.shape[0], len(texts), avg_conf,
                result["id_number"] is not None, result["full_name"] is not None,
                result["date_of_birth"] is not None, result["gender"] is not None,
                result["place_of_origin"] is not None, result["address"] is not None,
            )

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

    #: Chiều rộng lượt OCR bổ sung — phóng to 1.5× so với ảnh chuẩn 1280.
    HIRES_WIDTH = 1920

    def _fill_from_hires_pass(self, image: NDArray, missing: list[str], result: dict) -> None:
        """Vớt các trường còn thiếu bằng một lượt OCR ở độ phân giải cao hơn.

        Ảnh conf thấp thường do chữ nhỏ/mảnh; phóng to trước khi OCR giúp
        EasyOCR đọc được các dòng lượt chuẩn bỏ sót. Chỉ chạy khi lượt đầu
        thiếu trường, và là best-effort: lỗi ở đây không được phá kết quả chính.
        """
        try:
            h, w = image.shape[:2]
            if w < self.HIRES_WIDTH:
                scale = self.HIRES_WIDTH / w
                image = cv2.resize(
                    image, (self.HIRES_WIDTH, max(1, int(h * scale))),
                    interpolation=cv2.INTER_CUBIC,
                )
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image
            clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
            processed = clahe.apply(gray)

            texts = merge_rows(
                self._get_reader().readtext(processed, mag_ratio=1.5, adjust_contrast=0.7)
            )
            extractors = {
                "full_name": lambda: self._extract_name(texts),
                "date_of_birth": lambda: find_dob(texts),
                "gender": lambda: find_gender(texts),
                "place_of_origin": lambda: single_line_value(texts, LABELS_ORIGIN),
                "address": lambda: multi_line_value(texts, LABELS_ADDRESS, max_lines=2),
            }

            recovered = []
            for key in missing:
                value = extractors[key]()
                if value:
                    result[key] = value
                    recovered.append(key)
            if recovered:
                logger.info("Lượt OCR phân giải cao vớt thêm: %s", ", ".join(recovered))
        except Exception:
            logger.exception("Lượt OCR phân giải cao thất bại — giữ kết quả lượt chuẩn.")

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


def merge_rows(detections: list) -> list[str]:
    """Ghép các ô text EasyOCR về lại thành từng hàng theo toạ độ.

    EasyOCR tách "Quê quán / Place of origin: X" thành 2–3 ô rời, và ô tràn
    dòng có thể nằm cuối danh sách vì thứ tự trả về theo phát hiện chứ không
    theo hàng. Gom các ô có tâm dọc gần nhau thành một hàng rồi nối trái→phải,
    để các hàm dò nhãn nhìn thấy nguyên câu như in trên thẻ.
    """
    items = []
    for bbox, text, _conf in detections:
        stripped = text.strip()
        if not stripped:
            continue
        xs = [point[0] for point in bbox]
        ys = [point[1] for point in bbox]
        center_y = (min(ys) + max(ys)) / 2
        items.append((center_y, max(ys) - min(ys), min(xs), stripped))

    items.sort(key=lambda item: item[0])

    rows: list[dict] = []
    for center_y, height, x, text in items:
        if rows and abs(center_y - rows[-1]["center_y"]) <= max(height, rows[-1]["height"]) * 0.6:
            rows[-1]["parts"].append((x, text))
        else:
            rows.append({"center_y": center_y, "height": height, "parts": [(x, text)]})

    return [" ".join(text for _x, text in sorted(row["parts"])) for row in rows]


def strip_accents(text: str) -> str:
    """Bỏ dấu tiếng Việt, giữ nguyên độ dài chuỗi (mỗi ký tự đổi 1-1).

    Giữ độ dài để chỉ số tìm được trên bản không dấu dùng lại được trên bản
    gốc — cần cho việc cắt giá trị đứng sau nhãn. ``đ/Đ`` không phải ký tự có
    dấu ghép nên đổi tay.
    """
    out = []
    for ch in text:
        if ch == "đ":
            out.append("d")
            continue
        if ch == "Đ":
            out.append("D")
            continue
        base = "".join(
            c for c in unicodedata.normalize("NFD", ch) if unicodedata.category(c) != "Mn"
        )
        out.append(base if len(base) == 1 else ch)
    return "".join(out)


def norm_text(text: str) -> str:
    """Chuẩn hoá một dòng OCR để so nhãn: bỏ dấu + thường hoá, giữ độ dài.

    EasyOCR đọc nhãn tiếng Việt thường rơi rụng dấu ("Quê quán" → "Que quan"),
    nên mọi phép so nhãn phải chạy trên bản không dấu.
    """
    return strip_accents(text).lower()


def find_label_end(norm: str, labels: tuple[str, ...]) -> int:
    """Vị trí kết thúc (xa nhất) của nhãn trong dòng đã chuẩn hoá; ``-1`` nếu không có.

    Ảnh thật hay bị OCR đọc sai một ký tự trong nhãn ("gioi tinh" → "gio1 tinh"),
    nên nhãn dài (≥ 6 ký tự) chấp nhận lệch tối đa 1 ký tự thay thế khi so bằng
    cửa sổ trượt. Nhãn ngắn chỉ so khớp tuyệt đối để không vơ nhầm.
    """
    best = -1
    for label in labels:
        idx = norm.rfind(label)
        if idx >= 0:
            best = max(best, idx + len(label))
            continue

        length = len(label)
        if length < 6 or len(norm) < length:
            continue
        for start in range(len(norm) - length, -1, -1):
            window = norm[start : start + length]
            mismatches = sum(1 for a, b in zip(window, label) if a != b)
            if mismatches <= 1:
                best = max(best, start + length)
                break
    return best


def value_after_label(text: str, labels: tuple[str, ...]) -> str | None:
    """Phần đứng sau nhãn trên cùng một dòng, KHÔNG phụ thuộc dấu ``:``.

    OCR thẻ thật rất hay đọc rụng dấu hai chấm (nét quá mảnh), nên cắt giá trị
    theo vị trí kết thúc của nhãn thay vì tách chuỗi tại ``:``. Dòng có cả nhãn
    Việt lẫn Anh ("Quê quán / Place of origin: X") thì lấy phần sau nhãn xuất
    hiện cuối cùng.

    Trả ``None`` khi dòng không có nhãn; trả ``""`` khi có nhãn nhưng giá trị
    không nằm cùng dòng.
    """
    end = find_label_end(norm_text(text), labels)
    if end < 0:
        return None

    return text[end:].strip().lstrip(":;,.·/|-–— ").strip()


ALL_LABELS = LABELS_NAME + LABELS_DOB + LABELS_GENDER + LABELS_ORIGIN + LABELS_ADDRESS + EXPIRY_HINTS


def is_label_line(text: str) -> bool:
    """Dòng này có mở đầu một trường khác trên phôi thẻ không."""
    return find_label_end(norm_text(text), ALL_LABELS) >= 0


def single_line_value(texts: list[str], labels: tuple[str, ...]) -> str | None:
    """Giá trị một dòng của trường có nhãn: sau dấu ``:`` hoặc ở dòng kế tiếp.

    Không bỏ cuộc ở lần khớp nhãn đầu tiên: nhãn Việt và nhãn Anh của cùng một
    trường có thể bị OCR tách thành hai dòng, giá trị nằm ở dòng nhãn thứ hai.
    """
    for i, text in enumerate(texts):
        value = value_after_label(text, labels)
        if value is None:
            continue
        if value:
            return value
        if i + 1 < len(texts) and texts[i + 1].strip() and not is_label_line(texts[i + 1]):
            return texts[i + 1].strip()
    return None


def multi_line_value(texts: list[str], labels: tuple[str, ...], max_lines: int) -> str | None:
    """Giá trị có thể tràn nhiều dòng (địa chỉ thường trú in thành 2 dòng).

    Gom từ phần sau dấu ``:`` và tối đa ``max_lines`` dòng kế tiếp, dừng khi
    gặp dòng mở đầu trường khác. Nhãn khớp mà không moi được giá trị thì thử
    tiếp các dòng nhãn sau, không bỏ cuộc sớm.
    """
    for i, text in enumerate(texts):
        value = value_after_label(text, labels)
        if value is None:
            continue
        for j in range(i + 1, min(i + 1 + max_lines, len(texts))):
            line = texts[j].strip()
            if not line or is_label_line(line):
                break
            value = f"{value} {line}".strip()
        if value:
            return value
    return None


def find_date(text: str) -> str | None:
    """Tìm một ngày trong dòng, chịu được ký tự số bị đọc nhầm và khoảng trắng."""
    match = RE_DATE.search(text.translate(DIGIT_CONFUSABLES))
    if not match:
        return None
    return f"{match.group(1)}/{match.group(2)}/{match.group(3)}"


def find_dob(texts: list[str]) -> str | None:
    """Tìm ngày sinh: ưu tiên dòng có nhãn, tránh nhặt nhầm hạn thẻ/ngày cấp.

    Phôi thẻ in cả "Có giá trị đến" — lấy đại ngày đầu tiên trong toàn văn bản
    sẽ dính ngày hết hạn nếu OCR trả dòng đó trước dòng ngày sinh.
    """
    for i, text in enumerate(texts):
        if find_label_end(norm_text(text), LABELS_DOB) < 0:
            continue
        date = find_date(text)
        if not date and i + 1 < len(texts):
            date = find_date(texts[i + 1])
        if date:
            return date

    for text in texts:
        if find_label_end(norm_text(text), EXPIRY_HINTS) >= 0:
            continue
        date = find_date(text)
        if date:
            return date
    return None


def find_gender(texts: list[str]) -> str | None:
    """Tìm giới tính, chỉ trong dòng có nhãn "Giới tính/Sex" (hoặc dòng kế).

    Không dò trên toàn văn bản: chữ "Nam" nằm sẵn trong "VIỆT NAM" của tiêu đề
    và dòng quốc tịch, dò toàn cục sẽ trả "Nam" cho tất cả mọi người.
    """
    for i, text in enumerate(texts):
        if find_label_end(norm_text(text), LABELS_GENDER) < 0:
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
    norm = norm_text(candidate)
    return not any(keyword in norm for keyword in HEADER_KEYWORDS)
