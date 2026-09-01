"""
Bộ đặc trưng cho mô hình phát hiện gian lận giao dịch ví.

Dữ liệu huấn luyện là PaySim — nhật ký mô phỏng của một dịch vụ mobile money,
schema trùng với nghiệp vụ ví của `finora-payment`: loại giao dịch, số tiền, số
dư trước/sau của cả bên gửi lẫn bên nhận.

Hai nhóm đặc trưng được tách bạch có chủ đích:

`DAC_TRUNG_HANH_VI`
    Mô tả *hành vi* của giao dịch và của tài khoản nhận. Đây là nhóm đem triển
    khai, vì mọi trường đều tồn tại trong ví FINORA thật.

`DAC_TRUNG_RO_RI`
    Các trường phái sinh từ đẳng thức `amount == oldbalanceOrg`. Trong PaySim,
    đẳng thức này đúng với 97,82% giao dịch gian lận và 0,00% giao dịch bình
    thường — nó là **luật sinh dữ liệu của trình mô phỏng**, không phải quy luật
    của gian lận thật. Mô hình học nhóm này sẽ đạt chỉ số gần tuyệt đối trên
    PaySim rồi sụp đổ trên dữ liệu thật.

    Nhóm này KHÔNG nằm trong gói triển khai. Nó chỉ được huấn luyện riêng để
    lượng hóa mức chênh lệch, giống cách `tham_chieu_giai_doan_truoc` trong gói
    model tín dụng lượng hóa mất mát khi bỏ nhóm đặc trưng CIC.

Về lịch sử tài khoản nhận: `finora-ai` KHÔNG sở hữu wallet/transaction, nên lúc
chấm điểm thật không được truy vấn database của `finora-payment`. Ba trường
`dest_*_truoc_do` vì vậy phải do Payment truyền sang trong request — đó chính là
"payment behavior contract" mà roadmap P7-B05 ghi là phụ thuộc. Thiếu chúng thì
điền bằng median trong gói model, y hệt cách hồ sơ vay thiếu điểm CIC.
"""

import numpy as np
import pandas as pd

# Gian lận trong PaySim chỉ tồn tại ở hai loại này (đo trên toàn bộ 6.362.620
# dòng: TRANSFER 0,769%, CASH_OUT 0,184%, ba loại còn lại đúng 0 ca). Lọc theo
# đây không phải mẹo làm đẹp số liệu mà phản ánh nghiệp vụ: rút tiền và chuyển
# khoản là hai đường duy nhất mà tiền rời khỏi quyền kiểm soát của chủ ví.
LOAI_GIAO_DICH_RUI_RO = ("TRANSFER", "CASH_OUT")

# Ánh xạ loại giao dịch sang số. Giữ đủ 5 loại để gói model vẫn chấm được nếu
# sau này Payment gửi sang loại nằm ngoài danh sách rủi ro.
LOAI_GIAO_DICH_MAP = {
    "CASH_IN": 0,
    "CASH_OUT": 1,
    "DEBIT": 2,
    "PAYMENT": 3,
    "TRANSFER": 4,
}

DAC_TRUNG_HANH_VI = [
    "loai_giao_dich_encoded",
    "so_tien",
    "log_so_tien",
    "so_du_truoc_gui",
    "log_so_du_truoc_gui",
    "so_du_truoc_nhan",
    "log_so_du_truoc_nhan",
    "dest_so_du_bang_0",
    "gio_trong_ngay",
    "la_gio_dem",
    "ngay_trong_thang",
    "dest_so_lan_nhan_truoc_do",
    "dest_tong_tien_nhan_truoc_do",
    "dest_so_nguoi_gui_khac_nhau_truoc_do",
]

DAC_TRUNG_RO_RI = [
    "rut_can_tai_khoan",
    "ty_le_tren_so_du",
    "so_du_sau_gui",
    "so_du_sau_nhan",
]

# Bộ đặc trưng của gói model đem triển khai.
FRAUD_FEATURE_NAMES = list(DAC_TRUNG_HANH_VI)

# Bộ đặc trưng của mô hình đối chứng — chỉ dùng trong script huấn luyện.
FRAUD_FEATURE_NAMES_DAY_DU = DAC_TRUNG_HANH_VI + DAC_TRUNG_RO_RI

# Ba trường do Payment truyền sang; thiếu thì điền median trong gói model.
COT_LICH_SU_DICH_DEN = [
    "dest_so_lan_nhan_truoc_do",
    "dest_tong_tien_nhan_truoc_do",
    "dest_so_nguoi_gui_khac_nhau_truoc_do",
]

# Khung giờ đêm. Trong PaySim tỷ lệ gian lận theo giờ dao động 0,05%–22,3% vì
# giao dịch hợp lệ thưa hẳn về đêm còn gian lận chạy đều 24/24 — trùng với dấu
# hiệu AML kinh điển "giao dịch ngoài giờ sinh hoạt bình thường".
GIO_DEM_TU = 0
GIO_DEM_DEN = 6


def tinh_lich_su_dich_den(df: pd.DataFrame) -> pd.DataFrame:
    """Tính lịch sử tài khoản nhận **tính đến ngay trước** mỗi giao dịch.

    Ba đặc trưng mule account: tài khoản nhận đã nhận bao nhiêu lần, tổng bao
    nhiêu tiền, và từ bao nhiêu người gửi khác nhau.

    Bắt buộc loại trừ chính giao dịch đang xét: nếu tính gộp cả dòng hiện tại thì
    đặc trưng đã chứa thông tin của chính nhãn cần dự đoán, và mô hình sẽ có chỉ
    số đẹp giả tạo mà lúc chạy thật không tái lập được.

    Trả về DataFrame giữ nguyên index của `df` để ghép lại an toàn.
    """
    d = df[["step", "nameOrig", "nameDest", "amount"]].sort_values(
        ["nameDest", "step"], kind="mergesort"
    )
    nhom = d.groupby("nameDest", sort=False)

    so_lan = nhom.cumcount()
    tong_tien = nhom["amount"].cumsum() - d["amount"]

    # Số người gửi khác nhau: `duplicated` trên cặp (nameDest, nameOrig) cho biết
    # người gửi này đã từng gửi tới đích chưa; cộng dồn các lần "chưa từng" rồi
    # trừ đi chính dòng hiện tại ra số người gửi phân biệt trước đó.
    lan_dau = ~d.duplicated(subset=["nameDest", "nameOrig"])
    so_nguoi_gui = lan_dau.groupby(d["nameDest"], sort=False).cumsum() - lan_dau.astype(
        int
    )

    ket_qua = pd.DataFrame(
        {
            "dest_so_lan_nhan_truoc_do": so_lan.astype("float64"),
            "dest_tong_tien_nhan_truoc_do": tong_tien.astype("float64"),
            "dest_so_nguoi_gui_khac_nhau_truoc_do": so_nguoi_gui.astype("float64"),
        },
        index=d.index,
    )
    return ket_qua.reindex(df.index)


def tao_dac_trung(
    df: pd.DataFrame, lich_su: pd.DataFrame | None = None
) -> pd.DataFrame:
    """Dựng toàn bộ cột đặc trưng (cả hai nhóm) từ dữ liệu giao dịch thô.

    `lich_su` là kết quả của `tinh_lich_su_dich_den()`. Tách thành tham số thay vì
    tính bên trong, vì lịch sử phải được tính trên TOÀN BỘ nhật ký trước khi lọc
    loại giao dịch — một tài khoản mule nhận tiền từ nhiều loại giao dịch khác
    nhau, lọc trước sẽ làm mất phần lịch sử đó.
    """
    d = pd.DataFrame(index=df.index)

    so_tien = df["amount"].astype("float64")
    so_du_gui = df["oldbalanceOrg"].astype("float64")
    so_du_nhan = df["oldbalanceDest"].astype("float64")

    d["loai_giao_dich_encoded"] = df["type"].map(LOAI_GIAO_DICH_MAP).astype("float64")
    d["so_tien"] = so_tien
    d["log_so_tien"] = np.log1p(so_tien)
    d["so_du_truoc_gui"] = so_du_gui
    d["log_so_du_truoc_gui"] = np.log1p(so_du_gui.clip(lower=0))
    d["so_du_truoc_nhan"] = so_du_nhan
    d["log_so_du_truoc_nhan"] = np.log1p(so_du_nhan.clip(lower=0))
    d["dest_so_du_bang_0"] = (so_du_nhan == 0).astype("float64")

    gio = (df["step"] % 24).astype("float64")
    d["gio_trong_ngay"] = gio
    d["la_gio_dem"] = ((gio >= GIO_DEM_TU) & (gio < GIO_DEM_DEN)).astype("float64")
    d["ngay_trong_thang"] = (df["step"] // 24).astype("float64")

    if lich_su is None:
        lich_su = tinh_lich_su_dich_den(df)
    for cot in COT_LICH_SU_DICH_DEN:
        d[cot] = lich_su[cot].astype("float64")

    # ── Nhóm rò rỉ ────────────────────────────────────────────────────────────
    # So sánh bằng dung sai 1 đồng thay vì `==`: số dư là số thực dấu phẩy động,
    # so sánh bằng tuyệt đối sẽ trượt do sai số biểu diễn.
    d["rut_can_tai_khoan"] = (np.abs(so_tien - so_du_gui) < 1.0).astype("float64")
    d["ty_le_tren_so_du"] = (so_tien / (so_du_gui + 1.0)).clip(upper=10.0)
    d["so_du_sau_gui"] = df["newbalanceOrig"].astype("float64")
    d["so_du_sau_nhan"] = df["newbalanceDest"].astype("float64")

    return d


def tao_dac_trung_mot_giao_dich(gd: dict, median: dict[str, float]) -> dict[str, float]:
    """Dựng vector đặc trưng cho MỘT giao dịch lúc chấm điểm thật.

    Chỉ sinh `DAC_TRUNG_HANH_VI` — nhóm rò rỉ không có trong gói triển khai.

    Trường lịch sử tài khoản nhận thiếu thì điền bằng `median` lấy từ gói model,
    không phải hằng số viết cứng: điền lệch so với lúc huấn luyện sẽ gây
    train/serve skew, và lỗi này không làm service crash mà chỉ trả ra số sai.
    """
    so_tien = float(gd["so_tien"])
    so_du_gui = float(gd["so_du_truoc_gui"])
    so_du_nhan = float(gd.get("so_du_truoc_nhan") or 0.0)
    gio = float(gd["gio_trong_ngay"])

    dac_trung = {
        "loai_giao_dich_encoded": float(LOAI_GIAO_DICH_MAP[gd["loai_giao_dich"]]),
        "so_tien": so_tien,
        "log_so_tien": float(np.log1p(so_tien)),
        "so_du_truoc_gui": so_du_gui,
        "log_so_du_truoc_gui": float(np.log1p(max(so_du_gui, 0.0))),
        "so_du_truoc_nhan": so_du_nhan,
        "log_so_du_truoc_nhan": float(np.log1p(max(so_du_nhan, 0.0))),
        "dest_so_du_bang_0": 1.0 if so_du_nhan == 0 else 0.0,
        "gio_trong_ngay": gio,
        "la_gio_dem": 1.0 if GIO_DEM_TU <= gio < GIO_DEM_DEN else 0.0,
        "ngay_trong_thang": float(gd.get("ngay_trong_thang") or 0.0),
    }

    for cot in COT_LICH_SU_DICH_DEN:
        gia_tri = gd.get(cot)
        dac_trung[cot] = float(median[cot]) if gia_tri is None else float(gia_tri)

    return dac_trung
