"""
Giải thích quyết định của mô hình chấm điểm tín dụng bằng TreeSHAP (C1.2).

Vì sao cần Explainable AI ở đây
-------------------------------
`pd_probability` là một con số duy nhất. Khi người vay bị từ chối và hỏi "vì sao",
hoặc khi thẩm định viên phải quyết định có lật lại kết quả không, một con số không
trả lời được. Rule Engine (D4) đã tự giải trình được bằng `rule_trace` — mỗi điểm
cộng truy ngược về một luật có tên. Phần còn thiếu là nửa ML: vì sao chính mô hình
XGBoost lại cho hồ sơ này PD cao.

Dùng `pred_contribs` thay vì thư viện `shap`
--------------------------------------------
Cùng lựa chọn với `app/ml/fraud/predictor.py`: gọi `pred_contribs=True` của chính
booster XGBoost. Với mô hình cây đây là giá trị SHAP **chính xác** (thuật toán
TreeSHAP của Lundberg), không phải xấp xỉ bằng lấy mẫu như KernelSHAP. Nó chạy
nhanh hơn nhiều lần và không thêm phụ thuộc nặng vào đường chấm điểm trực tuyến.

Đóng góp trả về nằm trên thang **log-odds**, không phải xác suất: tổng các đóng góp
cộng với `bias` bằng đúng log-odds đầu ra của mô hình. Vì vậy chúng cộng lại được —
đó chính là tính chất additive khiến SHAP giải thích được, và là lý do không thể
thay bằng `feature_importances_` (importance toàn cục, không nói gì về hồ sơ này).

Quy ước dấu
-----------
Nhãn của mô hình là "vỡ nợ", nên:
  - đóng góp **dương** đẩy hồ sơ về phía rủi ro cao hơn (bất lợi cho người vay),
  - đóng góp **âm** kéo hồ sơ về phía an toàn hơn (có lợi cho người vay).

Khác với fraud (chỉ trả bằng chứng buộc tội để nhân viên rà soát đọc nhanh), ở đây
trả **cả hai chiều**. Lý do là nghiệp vụ khác nhau: hồ sơ tín dụng bị từ chối thì
người vay có quyền biết yếu tố nào đang cứu mình và yếu tố nào đang hại mình, còn
thẩm định viên cần thấy bức tranh cân bằng trước khi lật lại quyết định của máy.

Cảnh báo target leakage
-----------------------
`int_rate` nằm trong bộ đặc trưng của mô hình và thường là đóng góp lớn nhất, nhưng
nó là target leakage đã biết: LendingClub gán lãi suất SAU KHI chấm rủi ro, và
FINORA cũng tự quyết lãi suất theo hạng mình chấm. Giải thích "bạn rủi ro vì lãi
suất cao" vì thế là lập luận vòng tròn.

Ở đây CỐ Ý không lọc nó khỏi kết quả: một lời giải thích phải mô tả trung thực thứ
mô hình thật sự đã làm, che đi một đặc trưng là mô tả sai mô hình. Thay vào đó nó
được **đánh dấu** bằng `la_leakage=True` kèm `canh_bao`, để người đọc thấy được
vấn đề thay vì bị giấu. Rule Engine xử lý theo hướng ngược lại — không dùng
int_rate để chấm điểm — xem docstring `rule_engine.py`.

Hai bản trình bày: thô cho kỹ sư, gộp cho thẩm định viên
--------------------------------------------------------
Bản thô (`yeu_to_bat_loi` / `yeu_to_co_loi`) liệt kê từng đặc trưng đúng như mô
hình nhìn thấy. Nó trung thực nhưng khó đọc vì một dữ kiện đi vào mô hình dưới
nhiều dạng: dư nợ có bản thô, bản log và tỷ lệ trên thu nhập; SHAP chia đóng góp
cho từng dạng và các phần thường trái dấu nhau. Người đọc thấy "dư nợ (log)" đẩy
rủi ro lên còn "dư nợ hiện có" kéo xuống, và không kết luận được gì.

Bản gộp (`tom_tat`) cộng đóng góp về từng dữ kiện gốc — hợp lệ vì SHAP cộng dồn
được — rồi bỏ nhóm lãi suất (leakage và những đặc trưng suy từ nó), giữ ba nhóm
mạnh nhất mỗi chiều, và thay số log-odds bằng mức mạnh / vừa / nhẹ so với nhóm
mạnh nhất của hồ sơ. Bản thô vẫn giữ nguyên để đối chứng; bản gộp không thay thế
mà chỉ dịch nó.
"""

import numpy as np
import pandas as pd
import xgboost as xgb

from app.ml.credit.features import encode_features

# Số dòng giải thích tối đa mỗi chiều (đẩy lên / kéo xuống). Năm dòng là mức một
# thẩm định viên đọc hết được; dài hơn thì không ai đọc và giải thích mất tác dụng.
SO_YEU_TO_TOI_DA = 5

# Đặc trưng là target leakage — vẫn hiển thị nhưng phải gắn cảnh báo, xem docstring.
DAC_TRUNG_LEAKAGE = {"int_rate"}

CANH_BAO_LEAKAGE = (
    "int_rate là target leakage: lãi suất được gán SAU khi chấm rủi ro nên là kết "
    "quả chứ không phải nguyên nhân. Không dùng yếu tố này để giải thích cho người "
    "vay; Rule Engine cũng không dùng nó để chấm điểm."
)

# Nhãn tiếng Việt cho cả 47 đặc trưng. Không có bảng này thì giải thích trả về
# `log_du_no` hay `purpose_cat_encoded` — đúng với mô hình nhưng vô nghĩa với người
# đọc, và một lời giải thích không ai hiểu thì không phải lời giải thích.
NHAN_DAC_TRUNG = {
    # Hồ sơ tự khai + eKYC
    "person_age": "Tuổi người vay",
    "emp_length_years": "Số năm làm việc",
    "annual_inc": "Thu nhập năm",
    "loan_amnt": "Số tiền vay yêu cầu",
    "dti": "Tỷ lệ nợ trên thu nhập (DTI)",
    "installment": "Số tiền phải trả hàng tháng",
    # CIC
    "cic_score": "Điểm tín dụng CIC",
    "so_lan_tre_han": "Số lần trễ hạn 24 tháng gần nhất",
    "thang_tu_tre_gan_nhat": "Số tháng từ lần trễ hạn gần nhất",
    "tong_du_no": "Tổng dư nợ hiện có",
    "du_no_the_tin_dung": "Dư nợ thẻ tín dụng",
    "ty_le_su_dung_the": "Tỷ lệ sử dụng hạn mức thẻ",
    "so_lan_tra_cuu": "Số lần bị tra cứu tín dụng 6 tháng gần nhất",
    "so_hop_dong_dang_co": "Số hợp đồng tín dụng đang có",
    "so_thang_quan_he": "Số tháng có quan hệ tín dụng",
    "nhom_no_cao_nhat": "Nhóm nợ cao nhất từng có",
    # Fineract — thông tin sản phẩm vay
    "int_rate": "Lãi suất danh nghĩa",
    "term_months": "Kỳ hạn vay",
    # Đặc trưng dẫn xuất
    "log_income": "Thu nhập năm (thang log)",
    "loan_to_income": "Tỷ lệ khoản vay trên thu nhập",
    "effective_apr": "Lãi suất thực tế (APR)",
    "log_du_no": "Tổng dư nợ (thang log)",
    "ty_le_du_no_thu_nhap": "Tỷ lệ dư nợ trên thu nhập",
    # Target-encoded
    "home_ownership_encoded": "Tình trạng nhà ở",
    "purpose_cat_encoded": "Mục đích vay",
    "verification_status_encoded": "Tình trạng xác minh thu nhập",
    "interest_method_encoded": "Phương pháp tính lãi",
    # Age bucket
    "age_under_25": "Nhóm tuổi dưới 25",
    "age_25_to_39": "Nhóm tuổi 25-39",
    "age_40_to_59": "Nhóm tuổi 40-59",
    "age_over_60": "Nhóm tuổi trên 60",
}


# Nhóm đặc trưng theo dữ kiện gốc mà người thẩm định nhìn thấy trên hồ sơ. Chỉ báo
# thiếu (`*_missing`) đi cùng nhóm của cột gốc: SHAP chấm cho việc thiếu thông tin
# về dữ kiện đó, nên vẫn là chuyện của dữ kiện đó.
_CAC_NHOM: list[tuple[str, str, list[str]]] = [
    ("tuoi", "Tuổi người vay",
     ["person_age", "person_age_missing", "age_under_25", "age_25_to_39",
      "age_40_to_59", "age_over_60"]),
    ("tham_nien", "Số năm làm việc", ["emp_length_years", "emp_length_years_missing"]),
    ("thu_nhap", "Thu nhập năm", ["annual_inc", "log_income"]),
    ("khoan_vay", "Số tiền vay so với thu nhập", ["loan_amnt", "loan_to_income"]),
    ("dti", "Tỷ lệ nợ trên thu nhập (DTI)", ["dti", "dti_missing"]),
    ("tra_hang_thang", "Số tiền phải trả hàng tháng", ["installment", "installment_missing"]),
    ("ky_han", "Kỳ hạn vay", ["term_months", "term_months_missing"]),
    ("diem_cic", "Điểm tín dụng CIC", ["cic_score", "cic_score_missing"]),
    ("lich_su_tra_no", "Lịch sử trễ hạn và nhóm nợ (CIC)",
     ["so_lan_tre_han", "so_lan_tre_han_missing", "thang_tu_tre_gan_nhat",
      "thang_tu_tre_gan_nhat_missing", "nhom_no_cao_nhat", "nhom_no_cao_nhat_missing"]),
    ("du_no", "Tổng dư nợ hiện có",
     ["tong_du_no", "tong_du_no_missing", "log_du_no", "ty_le_du_no_thu_nhap"]),
    ("the_tin_dung", "Dư nợ và mức dùng thẻ tín dụng",
     ["du_no_the_tin_dung", "du_no_the_tin_dung_missing",
      "ty_le_su_dung_the", "ty_le_su_dung_the_missing"]),
    ("tra_cuu", "Số lần bị tra cứu tín dụng gần đây",
     ["so_lan_tra_cuu", "so_lan_tra_cuu_missing"]),
    ("quan_he_tin_dung", "Số hợp đồng và thời gian quan hệ tín dụng",
     ["so_hop_dong_dang_co", "so_hop_dong_dang_co_missing",
      "so_thang_quan_he", "so_thang_quan_he_missing"]),
    ("lai_suat", "Lãi suất và cách tính lãi",
     ["int_rate", "int_rate_missing", "effective_apr", "interest_method_encoded"]),
    ("nha_o", "Tình trạng nhà ở", ["home_ownership_encoded"]),
    ("muc_dich", "Mục đích vay", ["purpose_cat_encoded"]),
    ("xac_minh", "Tình trạng xác minh thu nhập", ["verification_status_encoded"]),
]

# Đặc trưng → (mã nhóm, nhãn nhóm). Test bắt buộc mọi cột trong FEATURE_NAMES có mặt.
NHOM_DAC_TRUNG: dict[str, tuple[str, str]] = {
    cot: (ma, nhan) for ma, nhan, cac_cot in _CAC_NHOM for cot in cac_cot
}

# Nhóm bị bỏ khỏi bản gộp. Lãi suất là leakage (xem trên); APR và phương pháp tính
# lãi suy ra từ chính lãi suất nên mang cùng vấn đề. Bản thô vẫn hiển thị chúng.
NHOM_LOAI_KHOI_TOM_TAT = {"lai_suat"}

# Ba nhóm mỗi chiều là mức một thẩm định viên nắm được trong một lần đọc.
SO_NHOM_TOI_DA = 3

# Mức ảnh hưởng tính theo tỷ lệ với nhóm mạnh nhất của chính hồ sơ này, không theo
# ngưỡng log-odds tuyệt đối: hồ sơ nào cũng có một yếu tố "mạnh" để bám vào, và
# thang đo không lệch khi huấn luyện lại mô hình.
NGUONG_MUC_DO = ((0.66, "manh"), (0.33, "vua"))


def _muc_do(do_lon: float, do_lon_max: float) -> str:
    ty_le = do_lon / do_lon_max
    for nguong, nhan in NGUONG_MUC_DO:
        if ty_le >= nguong:
            return nhan
    return "nhe"


def tom_tat_yeu_to(dong_gop: np.ndarray, ten_cot: list[str]) -> dict:
    """Gộp đóng góp SHAP về dữ kiện gốc và xếp mức ảnh hưởng cho thẩm định viên.

    Trả về `{"bat_loi": [...], "co_loi": [...]}`, mỗi chiều tối đa `SO_NHOM_TOI_DA`
    dòng sắp theo độ lớn giảm dần. Nhóm có tổng đúng 0 (các dạng triệt tiêu nhau)
    bị bỏ vì với hồ sơ này dữ kiện đó không nghiêng về bên nào.
    """
    tong: dict[str, float] = {}
    for muc, ten in zip(dong_gop, ten_cot):
        ma, _ = NHOM_DAC_TRUNG[ten]
        if ma in NHOM_LOAI_KHOI_TOM_TAT:
            continue
        tong[ma] = tong.get(ma, 0.0) + float(muc)

    tong = {ma: muc for ma, muc in tong.items() if abs(muc) > 1e-9}
    if not tong:
        return {"bat_loi": [], "co_loi": []}

    do_lon_max = max(abs(muc) for muc in tong.values())
    nhan_nhom = {ma: nhan for ma, nhan, _ in _CAC_NHOM}

    def dong(ma: str, muc: float) -> dict:
        return {
            "ma_nhom": ma,
            "mo_ta": nhan_nhom[ma],
            "muc_do": _muc_do(abs(muc), do_lon_max),
            "muc_dong_gop": round(muc, 6),
        }

    theo_do_lon = sorted(tong.items(), key=lambda kv: -abs(kv[1]))
    return {
        "bat_loi": [dong(ma, muc) for ma, muc in theo_do_lon if muc > 0][:SO_NHOM_TOI_DA],
        "co_loi": [dong(ma, muc) for ma, muc in theo_do_lon if muc < 0][:SO_NHOM_TOI_DA],
    }


def _nhan(ten_dac_trung: str) -> str:
    """Nhãn tiếng Việt của đặc trưng, lùi về chính tên cột nếu chưa có nhãn.

    Chỉ báo thiếu (`<cột>_missing`) sinh tự động nên không liệt kê thủ công trong
    `NHAN_DAC_TRUNG` — dựng nhãn từ nhãn của cột gốc. Đây là trường hợp phải giải
    thích rõ: SHAP chấm điểm cho chính việc *không có dữ liệu*, và người đọc cần
    hiểu điểm này đến từ việc thiếu thông tin chứ không phải từ một giá trị xấu.
    """
    if ten_dac_trung.endswith("_missing"):
        goc = ten_dac_trung[: -len("_missing")]
        return f"Thiếu dữ liệu: {NHAN_DAC_TRUNG.get(goc, goc)}"
    return NHAN_DAC_TRUNG.get(ten_dac_trung, ten_dac_trung)


def _dong_gop_treeshap(bo_du_doan, ho_so: dict) -> tuple[np.ndarray, np.ndarray, float]:
    """Tính đóng góp TreeSHAP cho một hồ sơ.

    Trả về `(dong_gop, X, bias)`. Dùng lại đúng đường chuẩn bị đặc trưng của
    `du_doan_pd()` — nếu giải thích tự dựng ma trận theo cách khác thì nó sẽ giải
    thích một hồ sơ KHÁC với hồ sơ đã được chấm điểm, mà không hề báo lỗi.
    """
    row = bo_du_doan.chuan_bi_dac_trung(ho_so)
    encoded = encode_features(
        pd.DataFrame([row]), bo_du_doan.target_encodings, bo_du_doan.global_mean
    )
    X = encoded[bo_du_doan.feature_names].values.astype(np.float64)

    booster = bo_du_doan.model.get_booster()
    dmatrix = xgb.DMatrix(X, feature_names=bo_du_doan.feature_names)
    contribs = booster.predict(dmatrix, pred_contribs=True)[0]

    # Phần tử cuối là bias (giá trị kỳ vọng của mô hình), không phải đặc trưng.
    return contribs[:-1], X[0], float(contribs[-1])


def giai_thich_mo_hinh(bo_du_doan, ho_so: dict) -> dict:
    """Giải thích PD của một hồ sơ bằng TreeSHAP.

    Trả về hai danh sách đã sắp xếp theo độ lớn đóng góp: `yeu_to_bat_loi` (đẩy PD
    lên) và `yeu_to_co_loi` (kéo PD xuống), kèm `bias` để người đọc kiểm chứng được
    tính cộng dồn của SHAP.
    """
    dong_gop, gia_tri_dac_trung, bias = _dong_gop_treeshap(bo_du_doan, ho_so)
    ten_cot = bo_du_doan.feature_names

    bat_loi: list[dict] = []
    co_loi: list[dict] = []

    # Sắp theo trị tuyệt đối giảm dần rồi tách hai chiều, để mỗi chiều đều lấy được
    # đúng những yếu tố mạnh nhất của chiều đó.
    for i in np.argsort(-np.abs(dong_gop)):
        muc = float(dong_gop[i])

        # Đóng góp đúng bằng 0 nghĩa là đặc trưng không ảnh hưởng gì tới hồ sơ này;
        # đưa vào chỉ làm loãng danh sách.
        if muc == 0.0:
            continue

        ten = ten_cot[i]
        yeu_to = {
            "dac_trung": ten,
            "mo_ta": _nhan(ten),
            "gia_tri": float(gia_tri_dac_trung[i]),
            "muc_dong_gop": muc,
            "la_leakage": ten in DAC_TRUNG_LEAKAGE,
        }

        if muc > 0 and len(bat_loi) < SO_YEU_TO_TOI_DA:
            bat_loi.append(yeu_to)
        elif muc < 0 and len(co_loi) < SO_YEU_TO_TOI_DA:
            co_loi.append(yeu_to)

        if len(bat_loi) == SO_YEU_TO_TOI_DA and len(co_loi) == SO_YEU_TO_TOI_DA:
            break

    canh_bao: list[str] = []
    if any(y["la_leakage"] for y in (*bat_loi, *co_loi)):
        canh_bao.append(CANH_BAO_LEAKAGE)

    return {
        "yeu_to_bat_loi": bat_loi,
        "yeu_to_co_loi": co_loi,
        "gia_tri_co_so": round(bias, 6),
        "canh_bao": canh_bao,
        "tom_tat": tom_tat_yeu_to(dong_gop, ten_cot),
    }
