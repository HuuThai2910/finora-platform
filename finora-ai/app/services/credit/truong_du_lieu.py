"""Danh mục trường dữ liệu mà luật chấm điểm được phép đọc.

Sau khi luật trở thành dữ liệu trong `product_config.json`, đây là "nửa code" duy
nhất còn lại của rule engine: cách đọc một giá trị từ hồ sơ. Admin tạo luật bằng
cách CHỌN một trường trong danh mục này, không tự gõ tên trường — vì đọc sai một
trường là lỗi lập trình (giá trị luôn None, luật luôn cho điểm trung tính mà không
ai biết), không phải lựa chọn nghiệp vụ.

Mỗi trường khai: mã, mô tả tiếng Việt, kiểu (`so` hoặc `phan_loai`), nguồn dữ
liệu, đơn vị để hiển thị, cờ `la_ty_le` cho trường 0–1 (gợi ý cho người vay phải
nhân 100), nhóm SHAP tương ứng (để gợi ý xếp theo mức ảnh hưởng của mô hình) và
hàm đọc giá trị.

Vì sao KHÔNG có int_rate
------------------------
Lãi suất có sức phân biệt cao nhất trong dữ liệu (AUC 0,68) nhưng đó là target
leakage: LendingClub gán lãi suất SAU KHI đã chấm rủi ro, nên nó là kết quả chứ
không phải nguyên nhân. FINORA cũng tự quyết lãi suất theo hạng tín dụng mình chấm
— dùng nó làm đầu vào là lập luận vòng tròn. int_rate chỉ dùng ở chốt chặn pháp lý
(trần 20%/năm) và để tính ra số tiền phải trả hàng tháng (`_tinh_tien_tra_thang`).
Không đưa vào danh mục thì admin không thể vô tình tạo luật chấm theo lãi suất.
"""

import math
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal

from app.ml.credit.preprocessing import _parse_emp_length

KieuTruong = Literal["so", "phan_loai"]
NguonTruong = Literal["ho_so", "cic", "fineract", "dan_xuat"]


# ── Hàm tiện ích đọc số ───────────────────────────────────────────────────────


def _so_hoac_none(gia_tri: Any) -> float | None:
    """Ép về float, trả None cho mọi thứ không phải số.

    NaN cũng bị loại: pandas dùng NaN cho ô trống, và NaN lọt vào phép so sánh
    ngưỡng sẽ luôn cho False — luật rơi xuống bậc thấp nhất mà không ai biết là
    do thiếu dữ liệu.
    """
    if gia_tri is None:
        return None
    try:
        so = float(gia_tri)
    except (TypeError, ValueError):
        return None
    return None if math.isnan(so) else so


def _ty_le_lai_nam(int_rate: float) -> float:
    """Đổi `int_rate` sang tỷ lệ thập phân/năm: 18 -> 0,18.

    `int_rate` luôn là PHẦN TRĂM một năm — `CreditScoreRequest` khai `ge=0, le=100`
    kèm mô tả "(%/năm)", và cả finora-web lẫn finora-loan đều gửi theo đơn vị đó.
    Không đoán đơn vị theo độ lớn: bản cũ coi mọi giá trị ≤ 1,0 là thập phân nên
    khoản vay ưu đãi 0,5%/năm bị đọc thành 50%/năm và bị từ chối oan.
    """
    return int_rate / 100.0


def _tinh_tien_tra_thang(f: dict) -> float | None:
    """Số tiền trả hàng tháng theo công thức niên kim, None nếu thiếu dữ liệu."""
    goc = _so_hoac_none(f.get("loan_amnt"))
    ky_han = _so_hoac_none(f.get("term_months"))
    lai_nam = _so_hoac_none(f.get("int_rate"))
    if goc is None or ky_han is None or ky_han <= 0 or lai_nam is None:
        return None

    lai_thang = _ty_le_lai_nam(lai_nam) / 12.0
    so_ky = int(ky_han)
    if lai_thang <= 0:
        return goc / so_ky
    luy_thua = (1 + lai_thang) ** so_ky
    return goc * lai_thang * luy_thua / (luy_thua - 1)


def _lay_ty_le_tra_no_thang(f: dict) -> float | None:
    """Tiền trả hàng tháng chia thu nhập tháng.

    Ưu tiên `installment` do bên gọi đưa sang (Loan lấy từ lịch trả Fineract).
    Thiếu thì tự tính từ lãi suất và kỳ hạn — dùng lãi suất ở đây là để ra số tiền
    phải trả, không phải lấy nó làm thước đo rủi ro.
    """
    thu_nhap_nam = _so_hoac_none(f.get("annual_inc"))
    if not thu_nhap_nam or thu_nhap_nam <= 0:
        return None

    tra_thang = _so_hoac_none(f.get("installment"))
    if tra_thang is None:
        tra_thang = _tinh_tien_tra_thang(f)
    if tra_thang is None:
        return None

    return tra_thang / (thu_nhap_nam / 12.0)


def _lay_ty_le_tren_thu_nhap(ten_tu_so: str) -> Callable[[dict], float | None]:
    """Tạo hàm đọc `<tu_so> / annual_inc`, None khi thiếu hoặc thu nhập ≤ 0."""

    def doc(f: dict) -> float | None:
        tu_so = _so_hoac_none(f.get(ten_tu_so))
        thu_nhap = _so_hoac_none(f.get("annual_inc"))
        if tu_so is None or not thu_nhap or thu_nhap <= 0:
            return None
        return tu_so / thu_nhap

    return doc


def _lay_tham_nien(f: dict) -> float | None:
    """Thâm niên (năm) từ chuỗi `emp_length` như "10+ years", "< 1 year"."""
    chuoi = f.get("emp_length")
    if chuoi is None:
        return None
    return _so_hoac_none(_parse_emp_length(chuoi))


def _lay_so(ten: str) -> Callable[[dict], float | None]:
    return lambda f: _so_hoac_none(f.get(ten))


def _lay_phan_loai(ten: str, hop_le: tuple[str, ...]) -> Callable[[dict], str | None]:
    """Giá trị rời rạc; giá trị lạ coi là thiếu để không im lặng nhận điểm."""
    return lambda f: f.get(ten) if f.get(ten) in hop_le else None


# ── Đặc tả trường ─────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class TruongDuLieu:
    """Một trường mà luật có thể đọc. `doc()` trả None khi hồ sơ thiếu dữ liệu."""

    ma: str
    mo_ta: str
    kieu: KieuTruong
    nguon: NguonTruong
    trich_xuat: Callable[[dict], Any]
    don_vi: str = ""
    la_ty_le: bool = False
    gia_tri_hop_le: tuple[str, ...] = ()
    nhom_shap: str | None = None

    def doc(self, features: dict) -> Any:
        return self.trich_xuat(features)

    def mo_ta_cong_khai(self) -> dict:
        """Bản JSON hoá được cho API — bỏ hàm đọc."""
        return {
            "ma": self.ma,
            "mo_ta": self.mo_ta,
            "kieu": self.kieu,
            "nguon": self.nguon,
            "don_vi": self.don_vi,
            "la_ty_le": self.la_ty_le,
            "gia_tri_hop_le": list(self.gia_tri_hop_le),
            "nhom_shap": self.nhom_shap,
        }


# Miền giá trị các trường phân loại — phải khớp Literal trong `schemas/credit.py`.
NHA_O = ("OWN", "MORTGAGE", "RENT", "OTHER")
MUC_DICH = (
    "debt_consolidation",
    "credit_card",
    "home_improvement",
    "major_purchase",
    "medical",
    "car",
    "small_business",
    "moving",
    "vacation",
    "education",
    "other",
)
XAC_MINH = ("Verified", "Source Verified", "Not Verified")


def _so(ma, mo_ta, nguon, don_vi="", nhom_shap=None, la_ty_le=False, trich_xuat=None):
    return TruongDuLieu(
        ma=ma,
        mo_ta=mo_ta,
        kieu="so",
        nguon=nguon,
        don_vi=don_vi,
        la_ty_le=la_ty_le,
        nhom_shap=nhom_shap,
        trich_xuat=trich_xuat or _lay_so(ma),
    )


def _phan_loai(ma, mo_ta, nguon, hop_le, nhom_shap=None):
    return TruongDuLieu(
        ma=ma,
        mo_ta=mo_ta,
        kieu="phan_loai",
        nguon=nguon,
        gia_tri_hop_le=hop_le,
        nhom_shap=nhom_shap,
        trich_xuat=_lay_phan_loai(ma, hop_le),
    )


# Thứ tự ở đây là thứ tự hiển thị trong ô chọn trường trên giao diện admin.
_DANH_SACH: tuple[TruongDuLieu, ...] = (
    # ── Hồ sơ tự khai ──
    _so("annual_inc", "Thu nhập năm khai báo", "ho_so", "đ", "thu_nhap"),
    _so("loan_amnt", "Số tiền vay yêu cầu", "ho_so", "đ", "khoan_vay"),
    _so("person_age", "Tuổi người vay (từ CCCD)", "ho_so", "tuổi", "tuoi"),
    _so(
        "emp_length_years",
        "Thâm niên việc làm",
        "ho_so",
        "năm",
        "tham_nien",
        trich_xuat=_lay_tham_nien,
    ),
    _so("dti", "Tỷ lệ nợ trên thu nhập (DTI)", "ho_so", "%", "dti"),
    _so("installment", "Số tiền phải trả hàng tháng", "ho_so", "đ", "tra_hang_thang"),
    _phan_loai("home_ownership", "Tình trạng nhà ở", "ho_so", NHA_O, "nha_o"),
    _phan_loai("purpose", "Mục đích vay", "ho_so", MUC_DICH, "muc_dich"),
    _phan_loai(
        "verification_status",
        "Trạng thái xác minh thu nhập",
        "ho_so",
        XAC_MINH,
        "xac_minh",
    ),
    # ── Sản phẩm vay (Fineract) ──
    _so("term_months", "Kỳ hạn vay", "fineract", "tháng", "ky_han"),
    # ── CIC ──
    _so("cic_score", "Điểm tín dụng CIC", "cic", "điểm", "diem_cic"),
    _so("so_lan_tre_han", "Số lần trễ hạn 24 tháng", "cic", "lần", "lich_su_tra_no"),
    _so(
        "thang_tu_tre_gan_nhat",
        "Số tháng từ lần trễ hạn gần nhất",
        "cic",
        "tháng",
        "lich_su_tra_no",
    ),
    _so("nhom_no_cao_nhat", "Nhóm nợ cao nhất", "cic", "nhóm", "lich_su_tra_no"),
    _so("tong_du_no", "Tổng dư nợ hiện có", "cic", "đ", "du_no"),
    _so("du_no_the_tin_dung", "Dư nợ thẻ tín dụng", "cic", "đ", "the_tin_dung"),
    _so("ty_le_su_dung_the", "Tỷ lệ sử dụng hạn mức thẻ", "cic", "%", "the_tin_dung"),
    _so("so_lan_tra_cuu", "Số lần bị tra cứu CIC 6 tháng", "cic", "lần", "tra_cuu"),
    _so(
        "so_hop_dong_dang_co",
        "Số hợp đồng tín dụng đang có",
        "cic",
        "hợp đồng",
        "quan_he_tin_dung",
    ),
    _so(
        "so_thang_quan_he",
        "Số tháng quan hệ tín dụng",
        "cic",
        "tháng",
        "quan_he_tin_dung",
    ),
    # ── Dẫn xuất ──
    _so(
        "ty_le_tra_no_thang",
        "Tiền trả hàng tháng trên thu nhập tháng",
        "dan_xuat",
        "",
        "tra_hang_thang",
        la_ty_le=True,
        trich_xuat=_lay_ty_le_tra_no_thang,
    ),
    _so(
        "loan_to_income",
        "Số tiền vay trên thu nhập năm",
        "dan_xuat",
        "",
        "khoan_vay",
        la_ty_le=True,
        trich_xuat=_lay_ty_le_tren_thu_nhap("loan_amnt"),
    ),
    _so(
        "ty_le_du_no_thu_nhap",
        "Tổng dư nợ trên thu nhập năm",
        "dan_xuat",
        "",
        "du_no",
        la_ty_le=True,
        trich_xuat=_lay_ty_le_tren_thu_nhap("tong_du_no"),
    ),
)

DANH_MUC_TRUONG: dict[str, TruongDuLieu] = {t.ma: t for t in _DANH_SACH}


def mo_ta_danh_muc() -> list[dict]:
    """Danh mục dạng JSON hoá được, đúng thứ tự hiển thị."""
    return [t.mo_ta_cong_khai() for t in _DANH_SACH]
