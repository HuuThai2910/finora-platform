"""Trích xuất thông tin từ ảnh CCCD Việt Nam bằng EasyOCR."""

import logging
import re

import numpy as np

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


class OcrExtractor:
    """Trích xuất thông tin từ ảnh CCCD sử dụng EasyOCR."""

    def __init__(self):
        self._reader: easyocr.Reader | None = None

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
            image = self._decode_image(image_bytes)
            if image is None:
                logger.warning("Không thể giải mã ảnh.")
                return result

            reader = self._get_reader()
            detections = reader.readtext(image)

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

            # Trích xuất số CCCD
            cccd_match = RE_CCCD_NUMBER.search(full_text)
            if cccd_match:
                result["id_number"] = cccd_match.group()

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

    def _extract_name(self, texts: list[str]) -> str | None:
        """Tìm họ tên từ danh sách text đã OCR."""
        for i, text in enumerate(texts):
            lower = text.lower()
            if "họ và tên" in lower or "full name" in lower:
                parts = text.split(":", 1)
                if len(parts) > 1 and parts[1].strip():
                    return parts[1].strip().upper()
                if i + 1 < len(texts):
                    return texts[i + 1].strip().upper()
        return None

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

    @staticmethod
    def _decode_image(image_bytes: bytes) -> np.ndarray | None:
        """Giải mã bytes thành numpy array (BGR)."""
        try:
            if cv2 is None:
                raise RuntimeError("opencv-python-headless chưa được cài đặt.")
            nparr = np.frombuffer(image_bytes, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            return image
        except Exception:
            return None
