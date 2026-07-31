import json
import hashlib
from pathlib import Path
from datetime import datetime, timezone

import joblib


def _duong_dan_mo_hinh(version: str, model_dir: Path) -> Path:
    return model_dir / f"model_v{version}.pkl"


def _duong_dan_metadata(version: str, model_dir: Path) -> Path:
    return model_dir / f"model_v{version}.json"


def _tinh_sha256(duong_dan: Path) -> str:
    sha = hashlib.sha256()
    with open(duong_dan, "rb") as f:
        for khoi in iter(lambda: f.read(8192), b""):
            sha.update(khoi)
    return sha.hexdigest()


KHOA_HE_THONG = {"version", "sha256", "metrics", "feature_names", "model_class", "saved_at"}


def luu_mo_hinh(
    model,
    version: str,
    metrics: dict,
    feature_names: list[str],
    model_dir: Path,
    thong_so_bo_sung: dict | None = None,
) -> dict:
    """Lưu mô hình đã huấn luyện kèm metadata.

    `thong_so_bo_sung` cho phép ghi thêm mọi thông số cần để **dự đoán một hồ sơ
    mới** mà không phải đọc lại code huấn luyện: median điền thiếu, siêu tham số,
    công thức đặc trưng dẫn xuất, chỉ số theo từng fold... Gói model khi đó tự
    chứa — nạp lên là chấm điểm được.

    Các khóa hệ thống (`KHOA_HE_THONG`) không cho phép ghi đè: nếu `thong_so_bo_sung`
    lỡ chứa `sha256` hay `version`, hàm raise thay vì âm thầm thay giá trị thật
    bằng giá trị người gọi truyền vào.
    """
    model_dir.mkdir(parents=True, exist_ok=True)

    pkl_path = _duong_dan_mo_hinh(version, model_dir)
    joblib.dump(model, pkl_path)

    sha256 = _tinh_sha256(pkl_path)

    metadata = {
        "version": version,
        "sha256": sha256,
        "metrics": metrics,
        "feature_names": feature_names,
        "model_class": type(model).__name__,
        "saved_at": datetime.now(timezone.utc).isoformat(),
    }

    if thong_so_bo_sung:
        trung = KHOA_HE_THONG & set(thong_so_bo_sung)
        if trung:
            raise ValueError(
                f"thong_so_bo_sung không được ghi đè khóa hệ thống: {sorted(trung)}"
            )
        metadata.update(thong_so_bo_sung)

    meta_path = _duong_dan_metadata(version, model_dir)
    meta_path.write_text(json.dumps(metadata, indent=2, ensure_ascii=False))

    return {
        "path": str(pkl_path),
        "sha256": sha256,
        "version": version,
        "metrics": metrics,
    }


def tai_mo_hinh(version: str, model_dir: Path):
    """Tải mô hình và metadata từ phiên bản chỉ định."""
    pkl_path = _duong_dan_mo_hinh(version, model_dir)
    meta_path = _duong_dan_metadata(version, model_dir)

    model = joblib.load(pkl_path)
    metadata = json.loads(meta_path.read_text())

    return model, metadata


def danh_sach_phien_ban(model_dir: Path) -> list[dict]:
    """Liệt kê tất cả phiên bản mô hình đã lưu."""
    ds = []
    for meta_file in sorted(model_dir.glob("model_v*.json")):
        metadata = json.loads(meta_file.read_text())
        ds.append(metadata)
    return ds


def phien_ban_moi_nhat(model_dir: Path) -> str:
    """Trả về chuỗi phiên bản mới nhất."""
    ds = danh_sach_phien_ban(model_dir)
    if not ds:
        return "0.0.0"
    return ds[-1]["version"]
