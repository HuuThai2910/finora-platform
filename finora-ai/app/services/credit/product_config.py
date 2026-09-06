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


def _next_decision_policy_version(current: str) -> str:
    """Tăng phiên bản chính sách sau mỗi lần admin thay đổi cấu hình quyết định."""
    prefix = "CREDIT_POLICY_V"
    if not current.startswith(prefix):
        raise ValueError(
            "decision_policy_version phải có dạng CREDIT_POLICY_V<so_nguyen_duong>"
        )
    try:
        number = int(current.removeprefix(prefix))
    except ValueError as exc:
        raise ValueError(
            "decision_policy_version phải có dạng CREDIT_POLICY_V<so_nguyen_duong>"
        ) from exc
    if number < 1:
        raise ValueError("decision_policy_version phải bắt đầu từ CREDIT_POLICY_V1")
    return f"{prefix}{number + 1}"


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


def save(config: dict) -> dict:
    """Ghi config xuống đĩa rồi nạp lại, trả về bản vừa nạp.

    Đây là lối ghi DUY NHẤT. Nơi khác đừng tự `_CONFIG_PATH.write_text()`: import
    `_CONFIG_PATH` trực tiếp sẽ bind đường dẫn ngay lúc import, nên test không
    trỏ được config sang file tạm và sẽ ghi đè lên file thật.
    """
    config_to_save = dict(config)
    config_to_save["decision_policy_version"] = _next_decision_policy_version(
        config["decision_policy_version"]
    )
    _CONFIG_PATH.write_text(
        json.dumps(config_to_save, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    return reload()


def get_grades() -> list[dict]:
    return _load()["grades"]


def get_decision_policy_version() -> str:
    """Phiên bản bộ quy tắc dùng để giải trình và tái hiện quyết định."""
    return _load()["decision_policy_version"]


def get_approval_thresholds() -> dict:
    return _load()["approval_thresholds"]


def get_model_weights() -> dict:
    cfg = _load()
    return cfg.get("model_weights", {"pd_weight": 0.6, "risk_weight": 0.4})


def get_legal_limits() -> dict:
    return _load()["legal_limits"]


def get_rules() -> dict:
    """Danh sách luật chấm điểm, đúng thứ tự admin sắp.

    Mỗi phần tử là một luật đầy đủ: mã, mô tả, trường đọc (phải có trong
    `truong_du_lieu.DANH_MUC_TRUONG`), chiều so sánh, trọng số, bậc/bảng điểm,
    điểm khi thiếu, bật/tắt, mẫu gợi ý. Không có luật nào khai trong code.
    """
    return _load()["rules"]
