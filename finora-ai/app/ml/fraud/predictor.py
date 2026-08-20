"""
Bộ phát hiện gian lận giao dịch ví, dùng gói model tự chứa
(`models/fraud/model_v<PHIEN_BAN_MAC_DINH>.pkl` + `.json`).

Cùng nguyên tắc với `app/ml/credit/predictor.py` của bài toán tín dụng: mọi giá trị cần
để chấm một giao dịch mới — thứ tự đặc trưng, median điền thiếu, ngưỡng cắt, hệ
số quy đổi tiền — đều nằm trong gói, không viết cứng trong code. Gói và code lệch
nhau thì service trả ra số sai mà không hề crash, nên các bất biến được kiểm ngay
lúc nạp và raise thẳng.

Ranh giới trách nhiệm (`.agents/rules/07-service-boundaries.md`): `finora-ai` sở
hữu *fraud technical result* — điểm rủi ro và bằng chứng. Việc chặn giao dịch,
giữ tiền hay khóa ví thuộc `finora-payment`. Vì vậy response ở đây KHÔNG chứa
hành động, chỉ chứa điểm số, mức rủi ro kỹ thuật và bằng chứng.
"""

from pathlib import Path

import numpy as np
import xgboost as xgb

from app.ml.fraud.features import (
    FRAUD_FEATURE_NAMES,
    tao_dac_trung_mot_giao_dich,
)
from app.ml.shared.model_registry import _duong_dan_mo_hinh, _tinh_sha256, tai_mo_hinh

THU_MUC_MO_HINH_MAC_DINH = (
    Path(__file__).resolve().parent.parent.parent.parent / "models" / "fraud"
)

PHIEN_BAN_MAC_DINH = "1.1.0"

# Số bằng chứng tối đa trả về. Ba đến năm dòng là mức một nhân viên rà soát đọc
# hết được; dài hơn thì không ai đọc và bằng chứng mất tác dụng.
SO_BANG_CHUNG_TOI_DA = 5

# Bốn cột mang đơn vị tiền tệ. Chúng phải được nhân hệ số quy đổi trước khi đưa
# vào mô hình, xem `he_so_quy_doi_tien` trong gói model.
COT_TIEN_TE = ("so_tien", "so_du_truoc_gui", "so_du_truoc_nhan")

# Nhãn tiếng Việt cho từng đặc trưng, dùng khi sinh bằng chứng.
NHAN_DAC_TRUNG = {
    "loai_giao_dich_encoded": "Loại giao dịch",
    "so_tien": "Số tiền giao dịch",
    "log_so_tien": "Số tiền giao dịch (thang log)",
    "so_du_truoc_gui": "Số dư ví gửi trước giao dịch",
    "log_so_du_truoc_gui": "Số dư ví gửi (thang log)",
    "so_du_truoc_nhan": "Số dư ví nhận trước giao dịch",
    "log_so_du_truoc_nhan": "Số dư ví nhận (thang log)",
    "dest_so_du_bang_0": "Ví nhận đang có số dư bằng 0",
    "gio_trong_ngay": "Giờ thực hiện giao dịch",
    "la_gio_dem": "Giao dịch trong khung giờ đêm",
    "ngay_trong_thang": "Ngày trong tháng",
    "dest_so_lan_nhan_truoc_do": "Số lần ví nhận đã nhận tiền trước đó",
    "dest_tong_tien_nhan_truoc_do": "Tổng tiền ví nhận đã nhận trước đó",
    "dest_so_nguoi_gui_khac_nhau_truoc_do": "Số người gửi khác nhau tới ví nhận",
}


class BoPhatHienGianLan:
    """Nạp gói model gian lận và chấm rủi ro cho một giao dịch ví."""

    def __init__(self, model, metadata: dict):
        self.model = model
        self.metadata = metadata
        self.feature_names: list[str] = metadata["feature_names"]
        self.median: dict[str, float] = metadata["median_dien_thieu"]
        self.nguong: float = float(metadata["metrics"]["nguong_quyet_dinh"])
        self.he_so_quy_doi: float = float(metadata.get("he_so_quy_doi_tien", 1.0))
        self._booster = model.get_booster()

    # ── Nạp gói ───────────────────────────────────────────────────────────────
    @classmethod
    def nap(
        cls,
        version: str = PHIEN_BAN_MAC_DINH,
        model_dir: Path | str | None = None,
    ) -> "BoPhatHienGianLan":
        """Nạp gói và kiểm tra toàn vẹn trước khi cho dùng.

        Ba lỗi bị chặn ở đây đều không gây crash nếu bỏ qua — service vẫn trả về
        một con số, chỉ là con số sai. Với hệ thống chống gian lận, chấm sai nghĩa
        là hoặc thả lọt giao dịch xấu, hoặc chặn nhầm khách hàng thật.
        """
        model_dir = (
            Path(model_dir) if model_dir is not None else THU_MUC_MO_HINH_MAC_DINH
        )
        model, metadata = tai_mo_hinh(version, model_dir)

        if "median_dien_thieu" not in metadata:
            raise ValueError(
                f"Gói gian lận v{version} không có 'median_dien_thieu' — gói cũ, "
                "không tự chứa. Chạy scripts/train_fraud_model.py để tạo gói mới."
            )

        if "nguong_quyet_dinh" not in metadata.get("metrics", {}):
            raise ValueError(
                f"Gói gian lận v{version} không có 'metrics.nguong_quyet_dinh'. "
                "Ngưỡng cắt là một phần của gói, không được đoán ở phía service."
            )

        if metadata["feature_names"] != FRAUD_FEATURE_NAMES:
            raise ValueError(
                f"Gói gian lận v{version} có bộ đặc trưng khác fraud_features.py hiện tại "
                f"({len(metadata['feature_names'])} cột trong gói, "
                f"{len(FRAUD_FEATURE_NAMES)} cột trong code). Cột thứ i của ma trận X "
                "không còn là đặc trưng mô hình đã học — phải huấn luyện lại."
            )

        sha_thuc_te = _tinh_sha256(_duong_dan_mo_hinh(version, model_dir))
        if sha_thuc_te != metadata["sha256"]:
            raise ValueError(
                f"SHA-256 của gói gian lận v{version} không khớp metadata "
                f"(file: {sha_thuc_te[:12]}..., metadata: {metadata['sha256'][:12]}...). "
                "File đã bị thay hoặc hỏng."
            )

        return cls(model, metadata)

    # ── Chuẩn bị đặc trưng ────────────────────────────────────────────────────
    def chuan_bi_dac_trung(self, gd: dict) -> dict[str, float]:
        """Quy đổi đơn vị tiền rồi dựng vector đặc trưng.

        Tách riêng khỏi `du_doan()` để test khẳng định được **giá trị nào** đã
        thực sự đi vào mô hình, thay vì chỉ nhìn điểm đầu ra rồi suy đoán.
        """
        gd_quy_doi = dict(gd)
        if self.he_so_quy_doi != 1.0:
            for cot in COT_TIEN_TE:
                if gd_quy_doi.get(cot) is not None:
                    gd_quy_doi[cot] = float(gd_quy_doi[cot]) / self.he_so_quy_doi
            if gd_quy_doi.get("dest_tong_tien_nhan_truoc_do") is not None:
                gd_quy_doi["dest_tong_tien_nhan_truoc_do"] = (
                    float(gd_quy_doi["dest_tong_tien_nhan_truoc_do"])
                    / self.he_so_quy_doi
                )
        return tao_dac_trung_mot_giao_dich(gd_quy_doi, self.median)

    # ── Chấm điểm ─────────────────────────────────────────────────────────────
    def du_doan(self, gd: dict) -> dict:
        """Chấm rủi ro gian lận cho một giao dịch ví."""
        dac_trung = self.chuan_bi_dac_trung(gd)
        X = np.array([[dac_trung[ten] for ten in self.feature_names]], dtype=np.float64)

        xac_suat = float(self.model.predict_proba(X)[0, 1])
        return {
            "fraud_probability": xac_suat,
            "muc_rui_ro": self._xep_muc_rui_ro(xac_suat),
            "nguong_quyet_dinh": self.nguong,
            "vuot_nguong": xac_suat >= self.nguong,
            "bang_chung": self.giai_thich(X, dac_trung),
            "model_version": self.metadata["version"],
        }

    def _xep_muc_rui_ro(self, xac_suat: float) -> str:
        """Xếp mức rủi ro kỹ thuật quanh ngưỡng của gói model.

        Chỉ là cách diễn đạt điểm số cho dễ đọc, KHÔNG phải quyết định nghiệp vụ:
        `finora-payment` mới là nơi quyết định chặn, giữ tiền hay yêu cầu xác thực
        bổ sung. Dải TRUNG_BINH lấy một phần mười ngưỡng làm biên dưới để có một
        vùng đệm cho rà soát thủ công thay vì chỉ có nhị phân qua/không qua.
        """
        if xac_suat >= self.nguong:
            return "CAO"
        if xac_suat >= self.nguong / 10.0:
            return "TRUNG_BINH"
        return "THAP"

    def giai_thich(self, X: np.ndarray, dac_trung: dict[str, float]) -> list[dict]:
        """Sinh bằng chứng bằng đóng góp TreeSHAP của XGBoost.

        Dùng `pred_contribs=True` của chính booster thay vì thư viện `shap`: với mô
        hình cây đây là giá trị SHAP chính xác (không phải xấp xỉ), chạy nhanh hơn
        nhiều lần và không thêm phụ thuộc vào đường chấm điểm trực tuyến.

        Chỉ trả về các đặc trưng **đẩy điểm lên phía gian lận** (đóng góp dương) —
        người rà soát cần biết vì sao giao dịch bị nghi ngờ, không cần danh sách lý
        do khiến nó có vẻ an toàn.
        """
        dmatrix = xgb.DMatrix(X, feature_names=self.feature_names)
        # Phần tử cuối là bias của mô hình, không phải đặc trưng — cắt bỏ.
        dong_gop = self._booster.predict(dmatrix, pred_contribs=True)[0][:-1]

        thu_tu = np.argsort(-dong_gop)
        bang_chung = []
        for i in thu_tu[:SO_BANG_CHUNG_TOI_DA]:
            if dong_gop[i] <= 0:
                break
            ten = self.feature_names[i]
            bang_chung.append(
                {
                    "dac_trung": ten,
                    "mo_ta": NHAN_DAC_TRUNG.get(ten, ten),
                    "gia_tri": float(dac_trung[ten]),
                    "muc_dong_gop": float(dong_gop[i]),
                }
            )
        return bang_chung
