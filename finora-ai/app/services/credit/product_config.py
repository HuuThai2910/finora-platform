"""
Nạp cấu hình sản phẩm từ config/product_config.json.

File JSON là nguồn chuẩn duy nhất cho khoảng điểm AI, hạng tín dụng, hạn mức
và ngưỡng duyệt tự động. Khi frontend thay đổi cấu hình, chỉ cần ghi lại file
này — rule_engine và predictor tự lấy giá trị mới qua module này.
"""
import json
from pathlib import Path

_CONFIG_PATH = Path(__file__).resolve().parent.parent.parent.parent / "config" / "product_config.json"

_cache: dict | None = None


def _load() -> dict:
    global _cache
    if _cache is None:
        with open(_CONFIG_PATH, encoding="utf-8") as f:
            _cache = json.load(f)
    return _cache


def reload() -> dict:
    """Buộc đọc lại file config (dùng khi cập nhật runtime)."""
    global _cache
    _cache = None
    return _load()


def get_grades() -> list[dict]:
    return _load()["grades"]


def get_approval_thresholds() -> dict:
    return _load()["approval_thresholds"]


def get_model_weights() -> dict:
    cfg = _load()
    return cfg.get("model_weights", {"pd_weight": 0.6, "risk_weight": 0.4})


def get_legal_limits() -> dict:
    return _load()["legal_limits"]
