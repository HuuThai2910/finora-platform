"""
Bộ dự đoán dùng gói model tự chứa (`models/model_v9.0.0.pkl` + `.json`).

Trường thiếu trong hồ sơ được điền bằng **median lưu trong gói** — đúng giá trị mà
mô hình đã học lúc huấn luyện — chứ không phải hằng số viết cứng trong code.

Đây là điểm dễ sai nhất khi triển khai mô hình tín dụng: nếu lúc huấn luyện điền
median nhưng lúc chấm điểm lại điền một hằng số khác, mô hình nhận đầu vào lệch phân
phối so với thứ nó đã học — gọi là train/serve skew. Lỗi này **không gây crash**: API
vẫn trả về một con số, chỉ là con số sai. Vì vậy median là một phần của gói model,
không phải chi tiết nội bộ của quá trình huấn luyện.

Không còn cổng chặn CIC: FINORA chưa có kết nối API tới CIC nên không lấy được nhóm
nợ hay điểm tín dụng để chặn. Việc loại hồ sơ không đủ điều kiện pháp lý vì vậy phải
nằm ở tầng nghiệp vụ khác, không phải ở đây.

Gói được tạo bởi `scripts/train_final_model.py`.
"""
from pathlib import Path

import numpy as np
import pandas as pd

from app.ml.features import FEATURE_NAMES, NUMERIC_FEATURES, COLUMNS_WITH_MISSING, encode_features
from app.ml.model_registry import _duong_dan_mo_hinh, _tinh_sha256, tai_mo_hinh
from app.ml.preprocessing import HOME_OWNERSHIP_MAP, PURPOSE_MAP, _parse_emp_length
from app.services.rule_engine import (
    quyet_dinh,
    tinh_diem_rui_ro,
    tinh_diem_tong_hop,
    xep_hang,
    kiem_tra_chot_chan_cung,
)

# Hai cột này được TÍNH LẠI từ cột gốc trong `encode_features()` sau khi điền thiếu,
# nên không điền median cho chúng — điền rồi cũng bị ghi đè.
COT_DAN_XUAT = {"log_income", "loan_to_income"}

# 4 cột gốc cần median. Là nguồn sự thật duy nhất cho cả `scripts/train_final_model.py`
# lẫn `predictor.py`, để danh sách lúc huấn luyện và lúc chấm điểm không thể lệch nhau.
COT_DIEN_MEDIAN = [c for c in NUMERIC_FEATURES if c not in COT_DAN_XUAT]

THU_MUC_MO_HINH_MAC_DINH = Path(__file__).resolve().parent.parent.parent / "models"

PHIEN_BAN_MAC_DINH = "10.0.0"


class BoDuDoan:
    """Nạp gói model tự chứa và chấm điểm một hồ sơ vay."""

    def __init__(self, model, metadata: dict):
        self.model = model
        self.metadata = metadata
        self.feature_names = metadata["feature_names"]
        self.median = metadata["median_dien_thieu"]
        self.target_encodings = metadata.get("target_encodings")
        self.global_mean = metadata.get("global_mean")

    # ── Nạp gói ───────────────────────────────────────────────────────────────
    @classmethod
    def nap(
        cls,
        version: str = PHIEN_BAN_MAC_DINH,
        model_dir: Path | str | None = None,
    ) -> "BoDuDoan":
        """Nạp gói và kiểm tra tính toàn vẹn trước khi cho dùng.

        Hai lỗi được chặn ở đây đều **không gây crash** nếu bỏ qua — mô hình vẫn
        trả về một con số, chỉ là con số sai. Với hệ thống tín dụng đó là kiểu lỗi
        tệ nhất, nên chặn thẳng thay vì cảnh báo.
        """
        model_dir = Path(model_dir) if model_dir is not None else THU_MUC_MO_HINH_MAC_DINH
        model, metadata = tai_mo_hinh(version, model_dir)

        if "median_dien_thieu" not in metadata:
            raise ValueError(
                f"Gói v{version} không có 'median_dien_thieu' — đây là gói cũ, "
                "không tự chứa. Chạy scripts/train_final_model.py để tạo gói mới."
            )

        if "target_encodings" not in metadata or "global_mean" not in metadata:
            raise ValueError(
                f"Gói v{version} không có 'target_encodings' hoặc 'global_mean' — đây là gói cũ, "
                "không tự chứa Target Encoding. Chạy scripts/train_final_model.py để tạo gói mới."
            )

        if metadata["feature_names"] != FEATURE_NAMES:
            raise ValueError(
                f"Gói v{version} có bộ đặc trưng khác features.py hiện tại "
                f"({len(metadata['feature_names'])} cột trong gói, "
                f"{len(FEATURE_NAMES)} cột trong code). Cột thứ i của ma trận X "
                "không còn là đặc trưng mô hình đã học — phải huấn luyện lại."
            )

        sha_thuc_te = _tinh_sha256(_duong_dan_mo_hinh(version, model_dir))
        if sha_thuc_te != metadata["sha256"]:
            raise ValueError(
                f"SHA-256 của model_v{version}.pkl không khớp metadata "
                f"(file: {sha_thuc_te[:12]}..., metadata: {metadata['sha256'][:12]}...). "
                "File đã bị thay hoặc hỏng."
            )

        return cls(model, metadata)

    # ── Chuẩn bị đặc trưng ────────────────────────────────────────────────────
    def chuan_bi_dac_trung(self, ho_so: dict) -> dict:
        """Dựng một dòng dữ liệu thô, trường thiếu điền bằng median trong gói.

        Tách riêng khỏi `du_doan_pd()` để test khẳng định được **giá trị nào** đã
        thực sự được điền, thay vì chỉ nhìn PD đầu ra rồi đoán.
        """
        # Chuyển đổi int_rate: nếu là dạng tỷ lệ thập phân (ví dụ: 0.15), chuyển thành phần trăm thô (15.0)
        int_rate_val = ho_so.get("int_rate")
        if int_rate_val is not None:
            int_rate_val = float(int_rate_val)
            if int_rate_val <= 1.0:
                int_rate_val = int_rate_val * 100.0

        row = {
            "person_age": ho_so.get("person_age"),
            "annual_inc": ho_so.get("annual_inc"),
            "loan_amnt": ho_so.get("loan_amnt"),
            "emp_length_years": (
                _parse_emp_length(ho_so["emp_length"]) if ho_so.get("emp_length") else None
            ),
            "home_ownership": HOME_OWNERSHIP_MAP.get(ho_so.get("home_ownership"), "OTHER"),
            "purpose_cat": PURPOSE_MAP.get(ho_so.get("purpose", "other"), "OTHER"),
            "verification_status": ho_so.get("verification_status", "Not Verified"),
            "dti": ho_so.get("dti"),
            "term_months": ho_so.get("term_months"),
            "delinq_2yrs": ho_so.get("delinq_2yrs"),
            "pub_rec": ho_so.get("pub_rec"),
            "int_rate": int_rate_val,
            "installment": ho_so.get("installment"),
        }

        # Tạo chỉ báo thiếu trước khi điền median
        for cot in COLUMNS_WITH_MISSING:
            val = row.get(cot)
            row[f"{cot}_missing"] = 1.0 if val is None or (isinstance(val, float) and np.isnan(val)) else 0.0

        for cot, gia_tri_median in self.median.items():
            gia_tri = row.get(cot)
            if gia_tri is None or (isinstance(gia_tri, float) and np.isnan(gia_tri)):
                row[cot] = gia_tri_median

        return row

    # ── Dự đoán ───────────────────────────────────────────────────────────────
    def du_doan_pd(self, ho_so: dict) -> float:
        """Xác suất vỡ nợ (PD) của một hồ sơ, trong khoảng (0, 1)."""
        row = self.chuan_bi_dac_trung(ho_so)
        encoded = encode_features(pd.DataFrame([row]), self.target_encodings, self.global_mean)
        X = encoded[self.feature_names].values.astype(np.float64)

        if np.isnan(X).any():
            cot_thieu = [
                ten for ten, la_nan in zip(self.feature_names, np.isnan(X)[0]) if la_nan
            ]
            raise ValueError(f"Còn NaN sau khi điền median, các cột: {cot_thieu}")

        return float(self.model.predict_proba(X)[0][1])

    def du_doan(self, ho_so: dict) -> dict:
        """Chấm điểm đầy đủ: mô hình → rule engine → quyết định."""
        pd_probability = self.du_doan_pd(ho_so)
        risk_score = tinh_diem_rui_ro(ho_so)
        evaluation_score = tinh_diem_tong_hop(pd_probability, risk_score)
        hang = xep_hang(evaluation_score)

        chot_chan_ly_do = kiem_tra_chot_chan_cung(ho_so)

        return {
            "pd_probability": round(pd_probability, 4),
            "risk_score": risk_score,
            "evaluation_score": round(evaluation_score, 2),
            "credit_grade": hang.hang,
            "suggested_limit": hang.han_muc,
            "suggested_rate": hang.lai_suat,
            "decision": quyet_dinh(evaluation_score, chot_chan_ly_do),
            "rejection_reason": chot_chan_ly_do,
            "model_version": self.metadata["version"],
        }
