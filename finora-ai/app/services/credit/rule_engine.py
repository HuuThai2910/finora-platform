"""Rule Engine — chấm điểm rủi ro bằng quy tắc tường minh, chạy song song với mô hình ML.

Đây là phần minh bạch nhất của hệ thống: ngưỡng cố định, không học từ dữ liệu, giải
trình được với người vay và với kiểm toán mà không cần công cụ nào.

Vai trò so với mô hình ML
-------------------------
Rule engine KHÔNG cạnh tranh với ML về độ chính xác — đo trên 150.000 hồ sơ thật,
ML đạt AUC 0,70 còn rule engine đạt 0,64. Nó tồn tại vì ba việc ML không làm được:

  1. Giải trình — mỗi điểm cộng đều truy ngược được về một luật có tên (rule trace).
  2. Chốt chặn pháp lý — trần lãi suất, trần kỳ hạn, nợ xấu CIC là luật, không phải
     xác suất; không thể để mô hình "cân nhắc" một điều luật.
  3. Hoạt động khi ML hoặc CIC hỏng — luật vẫn chấm được với dữ liệu tự khai.

Vì vậy trọng số của rule trong điểm tổng hợp cố ý giữ thấp (risk_weight 0,15).
Đo thực nghiệm cho thấy tăng trọng số làm AUC điểm tổng hợp GIẢM (0,7011 ở mức 0,15
xuống 0,6904 ở mức 0,30) — trộn tín hiệu yếu vào tín hiệu mạnh thì loãng đi.

Luật là dữ liệu, không phải code
--------------------------------
Mỗi luật là một `LuatChamDiem` khai báo: lấy giá trị từ đâu, quy đổi ra điểm theo
bậc nào, cho bao nhiêu điểm khi thiếu dữ liệu. `cham_diem_chi_tiet()` chỉ chạy qua
danh sách `BO_LUAT` — thêm hay bớt một luật không phải sửa logic tính điểm, và vết
luật sinh ra tự động.

Vì sao trường thiếu KHÔNG bị cho điểm sàn
-----------------------------------------
Ở Việt Nam cic-service có thể không trả lời, và khi đó "không tra được lịch sử tín
dụng" không đồng nghĩa với "lịch sử tín dụng xấu". Cho điểm sàn là phạt oan người
vay vì sự cố hạ tầng của nền tảng. Mỗi luật vì vậy cho điểm trung tính khi thiếu dữ
liệu và đánh dấu `thieu_du_lieu=True` để thẩm định viên biết mà xem lại.

Vì sao KHÔNG luật nào dùng int_rate để chấm điểm
------------------------------------------------
Lãi suất có sức phân biệt cao nhất trong dữ liệu (AUC 0,68) nhưng đó là target
leakage: LendingClub gán lãi suất SAU KHI đã chấm rủi ro, nên nó là kết quả chứ
không phải nguyên nhân. FINORA cũng tự quyết lãi suất theo hạng tín dụng mình chấm
— dùng nó làm đầu vào là lập luận vòng tròn. int_rate chỉ dùng ở chốt chặn pháp lý
(trần 20%/năm) và để tính ra số tiền phải trả hàng tháng.

Khoảng điểm, hạng tín dụng, hạn mức và ngưỡng duyệt đọc từ config/product_config.json.
"""
import math
from collections import namedtuple
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from app.services.credit.product_config import (
    get_approval_thresholds,
    get_grades,
    get_legal_limits,
    get_model_weights,
    get_rules,
)

XepHangTinDung = namedtuple("XepHangTinDung", ["hang", "han_muc"])

# Nhóm 5C — dùng cho báo cáo và cho màn hình giải trình của thẩm định viên.
CHARACTER = "Character"
CAPACITY = "Capacity"
CAPITAL = "Capital"

DIEM_TOI_DA_MOI_LUAT = 20

# Nợ nhóm 3 trở lên là nợ xấu theo Thông tư 11/2021/TT-NHNN. Tổ chức tín dụng
# không được cấp tín dụng mới cho khách hàng đang có nợ xấu.
NHOM_NO_XAU_TOI_THIEU = 3

# Quyết định 2866/QĐ-NHNN (22/7/2025) đặt HAI trần dư nợ cho cơ chế thử nghiệm P2P,
# cả hai đều phải kiểm:
#   · 100 triệu — một khách hàng tại MỘT nền tảng, áp ở `_build_bang_xep_hang()`
#     bằng cách chặn hạn mức của từng hạng tín dụng.
#   · 400 triệu — một khách hàng trên TOÀN BỘ nền tảng thử nghiệm, áp ở
#     `kiem_tra_chot_chan_cung()` dựa trên tổng dư nợ CIC trả về.
# Chỉ kiểm trần thứ nhất là chưa đủ tuân thủ: bốn nền tảng mỗi nơi 100 triệu đều
# hợp lệ riêng lẻ nhưng tổng 400 triệu đã chạm trần, khoản thứ năm phải bị chặn.

# Số luật tối thiểu phải có dữ liệu thật thì kết quả mới đáng tin.
# Dưới ngưỡng này hồ sơ bị đẩy sang thẩm định viên thay vì để máy quyết.
SO_LUAT_TOI_THIEU_CO_DU_LIEU = 3

# Các giá trị nhà ở hợp lệ. Đây là miền giá trị của dữ liệu đầu vào (do schema
# CreditScoreRequest quy định), không phải cấu hình nghiệp vụ — admin sửa được
# điểm của từng loại, nhưng không thêm được loại nhà ở mới.
TINH_TRANG_NHA_O = ("OWN", "MORTGAGE", "RENT", "OTHER")


# ── Hàm đọc dữ liệu từ hồ sơ ──────────────────────────────────────────────────
# Tách thành hàm có tên thay vì lambda để test gọi thẳng được và stack trace đọc được.


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

    Bản trước đoán đơn vị bằng `lai_nam if lai_nam <= 1.0 else lai_nam / 100.0`,
    tức coi mọi giá trị từ 1,0 trở xuống là đã ở dạng thập phân. Cách đoán đó phá
    đúng khoảng lãi suất ưu đãi hợp lệ: khoản vay 0,5%/năm bị đọc thành 50%/năm và
    dính `INTEREST_RATE_EXCEEDS_LEGAL_LIMIT` — từ chối một hồ sơ hoàn toàn hợp lệ.
    Nó còn làm tiền trả hàng tháng NGHỊCH biến quanh mốc 1: `int_rate=1.0` cho ra
    1.619.949 đ/tháng còn `int_rate=2.0` chỉ 1.010.866 đ/tháng, vì 1,0 bị hiểu là
    100%/năm. Không đoán nữa: tin vào đơn vị mà schema đã quy định.
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


def _lay_cic_score(f: dict) -> float | None:
    return _so_hoac_none(f.get("cic_score"))


def _lay_dti(f: dict) -> float | None:
    return _so_hoac_none(f.get("dti"))


def _lay_so_lan_tra_cuu(f: dict) -> float | None:
    return _so_hoac_none(f.get("so_lan_tra_cuu"))


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


def _lay_on_dinh_cu_tru(f: dict) -> str | None:
    tinh_trang = f.get("home_ownership")
    return tinh_trang if tinh_trang in TINH_TRANG_NHA_O else None


# ── Đặc tả luật ───────────────────────────────────────────────────────────────
#
# Mỗi luật gồm hai nửa:
#   · Nửa CODE  — cách đọc giá trị từ hồ sơ (`trich_xuat`) và kiểu so sánh.
#                 Không đưa ra config được vì đó là hàm Python, và cũng không nên:
#                 đọc sai một trường là lỗi lập trình, không phải lựa chọn nghiệp vụ.
#   · Nửa CONFIG — ngưỡng, điểm mỗi bậc, điểm khi thiếu, bật/tắt. Admin sửa được
#                 qua PUT /api/v1/ai/config/rules, ghi xuống config/product_config.json.
#
# `_DINH_NGHIA_LUAT` là nửa code; `product_config.json["rules"]` là nửa config;
# `lay_bo_luat()` ghép hai nửa lại thành `LuatChamDiem` để chấm điểm.


@dataclass(frozen=True)
class LuatChamDiem:
    """Một luật chấm điểm đã ghép đủ hai nửa, sẵn sàng chạy.

    bac: danh sách (nguong, diem). Luật lấy điểm của ngưỡng đầu tiên mà giá trị
         đạt tới. Với chỉ số "càng cao càng xấu" (ví dụ dti), đặt `nghich_dao=True`
         để so sánh đổi chiều.
    bang_diem: dùng thay `bac` cho luật tra bảng theo giá trị rời rạc (nhà ở).
    bat: False thì luật bị bỏ qua hoàn toàn, không cộng điểm và không vào trace.
    """

    ma: str
    nhom_5c: str
    mo_ta: str
    trich_xuat: Callable[[dict], Any]
    diem_khi_thieu: int
    bac: tuple[tuple[float, int], ...] = ()
    bang_diem: dict[str, int] | None = None
    nghich_dao: bool = False
    bat: bool = True

    @property
    def diem_toi_da(self) -> int:
        """Điểm cao nhất luật có thể cho — suy ra từ cấu hình, không khai cứng."""
        if self.bang_diem is not None:
            return max(self.bang_diem.values())
        return max(diem for _, diem in self.bac)

    def cham(self, features: dict) -> tuple[int, Any, bool]:
        """Trả về (điểm, giá trị đã dùng, có thiếu dữ liệu không)."""
        gia_tri = self.trich_xuat(features)
        if gia_tri is None:
            return self.diem_khi_thieu, None, True

        if self.bang_diem is not None:
            return self.bang_diem[gia_tri], gia_tri, False

        for nguong, diem in self.bac:
            if (gia_tri <= nguong) if self.nghich_dao else (gia_tri >= nguong):
                return diem, gia_tri, False
        return self.bac[-1][1], gia_tri, False


@dataclass(frozen=True)
class _DinhNghiaLuat:
    """Nửa code của một luật — cố định, admin không sửa được."""

    ma: str
    nhom_5c: str
    mo_ta: str
    trich_xuat: Callable[[dict], Any]
    nghich_dao: bool = False
    la_bang_diem: bool = False


# Thứ tự ở đây là thứ tự hiển thị trong rule trace.
_DINH_NGHIA_LUAT: tuple[_DinhNghiaLuat, ...] = (
    _DinhNghiaLuat(
        ma="CHARACTER_CIC_HISTORY",
        nhom_5c=CHARACTER,
        mo_ta="Điểm tín dụng CIC — lịch sử trả nợ tại các tổ chức tín dụng",
        trich_xuat=_lay_cic_score,
    ),
    _DinhNghiaLuat(
        ma="CAPACITY_EXISTING_DEBT",
        nhom_5c=CAPACITY,
        mo_ta="Tỷ lệ nợ trên thu nhập hiện có (DTI) — càng thấp càng tốt",
        trich_xuat=_lay_dti,
        nghich_dao=True,
    ),
    _DinhNghiaLuat(
        ma="CAPACITY_INSTALLMENT_BURDEN",
        nhom_5c=CAPACITY,
        mo_ta="Tiền trả hàng tháng trên thu nhập tháng — càng thấp càng tốt",
        trich_xuat=_lay_ty_le_tra_no_thang,
        nghich_dao=True,
    ),
    _DinhNghiaLuat(
        ma="CHARACTER_CREDIT_SEEKING",
        nhom_5c=CHARACTER,
        mo_ta="Số lần bị tra cứu CIC 6 tháng — tìm vốn dồn dập là dấu hiệu khát tiền",
        trich_xuat=_lay_so_lan_tra_cuu,
        nghich_dao=True,
    ),
    _DinhNghiaLuat(
        ma="CAPITAL_RESIDENCE_STABILITY",
        nhom_5c=CAPITAL,
        mo_ta="Tình trạng nhà ở — đại diện cho tài sản tích lũy và độ ổn định cư trú",
        trich_xuat=_lay_on_dinh_cu_tru,
        la_bang_diem=True,
    ),
)

MA_LUAT_HOP_LE = tuple(dn.ma for dn in _DINH_NGHIA_LUAT)


def lay_bo_luat() -> tuple[LuatChamDiem, ...]:
    """Ghép nửa code với nửa config thành bộ luật chạy được.

    Đọc lại config mỗi lần gọi để admin sửa ngưỡng có hiệu lực ngay, không cần
    khởi động lại service. Luật bị tắt (`bat=False`) vẫn được ghép nhưng
    `cham_diem_chi_tiet()` sẽ bỏ qua.
    """
    cau_hinh = get_rules()
    bo_luat = []
    for dn in _DINH_NGHIA_LUAT:
        c = cau_hinh[dn.ma]
        bo_luat.append(
            LuatChamDiem(
                ma=dn.ma,
                nhom_5c=dn.nhom_5c,
                mo_ta=dn.mo_ta,
                trich_xuat=dn.trich_xuat,
                nghich_dao=dn.nghich_dao,
                diem_khi_thieu=c["diem_khi_thieu"],
                bat=c.get("bat", True),
                bang_diem=dict(c["bang_diem"]) if dn.la_bang_diem else None,
                bac=() if dn.la_bang_diem else tuple(
                    (float(nguong), int(diem)) for nguong, diem in c["bac"]
                ),
            )
        )
    return tuple(bo_luat)


# ── Chấm điểm ─────────────────────────────────────────────────────────────────


def cham_diem_chi_tiet(features: dict) -> tuple[int, list[dict]]:
    """Chấm điểm rủi ro và trả kèm vết luật.

    Trả về (điểm 0-100, danh sách vết luật). Mỗi vết ghi lại luật nào đã chạy,
    đọc được giá trị gì và cộng bao nhiêu điểm — đủ để dựng màn hình giải trình
    cho thẩm định viên hoặc trả lời khiếu nại của người vay.

    Điểm được CHUẨN HÓA về thang 100 theo tổng điểm tối đa của các luật đang bật.
    Không chuẩn hóa thì tắt một luật sẽ kéo trần điểm xuống 80 và mọi hồ sơ tụt
    hạng oan — một thay đổi cấu hình tưởng vô hại lại làm sai toàn bộ quyết định.
    """
    tong_tho = 0
    tran_tho = 0
    vet = []

    for luat in lay_bo_luat():
        if not luat.bat:
            continue
        diem, gia_tri, thieu = luat.cham(features)
        tong_tho += diem
        tran_tho += luat.diem_toi_da
        vet.append(
            {
                "ma": luat.ma,
                "nhom_5c": luat.nhom_5c,
                "mo_ta": luat.mo_ta,
                "gia_tri": round(gia_tri, 4) if isinstance(gia_tri, float) else gia_tri,
                "diem": diem,
                "toi_da": luat.diem_toi_da,
                "thieu_du_lieu": thieu,
            }
        )

    if tran_tho == 0:
        # Admin tắt hết luật. Không có cơ sở chấm điểm, trả 0 để hồ sơ rơi vào
        # nhánh thiếu dữ liệu ở `quyet_dinh()` thay vì được điểm khống.
        return 0, vet

    return round(tong_tho * 100 / tran_tho), vet


def tinh_diem_rui_ro(features: dict) -> int:
    """Tính điểm rủi ro theo bộ luật 5C (0-100), bỏ vết chấm.

    Đường chấm điểm thật (`BoDuDoan.du_doan`) gọi thẳng `cham_diem_chi_tiet` vì
    còn cần `rule_trace` cho `dem_luat_co_du_lieu` và cho phần giải thích. Hàm này
    giữ lại cho những chỗ chỉ quan tâm con số — hiện là test và
    `scripts/validate_rule_engine.py` — để khỏi phải viết `tong, _ =` mỗi lần.
    """
    tong, _ = cham_diem_chi_tiet(features)
    return tong


def dem_luat_co_du_lieu(vet: list[dict]) -> int:
    """Đếm số luật chấm được bằng dữ liệu thật, không phải điểm trung tính."""
    return sum(1 for m in vet if not m["thieu_du_lieu"])


def tinh_diem_tong_hop(pd_probability: float, risk_score: int) -> float:
    """Tính điểm tổng hợp = pd_score x pd_weight + risk_score x risk_weight."""
    weights = get_model_weights()
    pd_w = weights["pd_weight"]
    risk_w = weights["risk_weight"]
    pd_score = (1 - pd_probability) * 100
    return pd_score * pd_w + risk_score * risk_w


# ── Xếp hạng ──────────────────────────────────────────────────────────────────


def _build_bang_xep_hang() -> list[tuple[int, XepHangTinDung]]:
    """Dựng bảng xếp hạng từ config, sắp giảm dần theo min_score."""
    legal = get_legal_limits()
    tran = legal["max_platform_limit"]
    grades = sorted(get_grades(), key=lambda g: g["min_score"], reverse=True)
    bang = []
    for g in grades:
        han_muc = g["limit"]
        if han_muc > tran:
            raise ValueError(
                f"Hạng {g['grade']}: hạn mức {han_muc:,} vượt trần "
                f"{tran:,} đồng/khách hàng/nền tảng của Quyết định 2866/QĐ-NHNN"
            )
        bang.append((g["min_score"], XepHangTinDung(g["grade"], han_muc)))
    return bang


def xep_hang(evaluation_score: float) -> XepHangTinDung:
    """Xếp hạng tín dụng A/B/C/D dựa trên điểm tổng hợp và config."""
    bang = _build_bang_xep_hang()
    for nguong, hang in bang:
        if evaluation_score >= nguong:
            return hang
    return bang[-1][1]


# ── Chốt chặn cứng và quyết định ──────────────────────────────────────────────


def kiem_tra_chot_chan_cung(features: dict) -> list[str]:
    """Kiểm tra các quy tắc loại trừ thẳng (Knock-out Rules).

    Trả về DANH SÁCH mã vi phạm, rỗng nếu hồ sơ sạch. Trả về tất cả thay vì
    dừng ở lỗi đầu tiên: người vay sửa xong một lỗi mà vẫn bị từ chối vì lỗi
    thứ hai là trải nghiệm tệ và tốn thêm một vòng thẩm định.

    Mọi luật ở đây đều dẫn được số hiệu văn bản pháp luật: vi phạm nghĩa là hợp
    đồng vô hiệu, chứ không phải "rủi ro cao hơn". Chốt chặn có quyền phủ quyết
    tuyệt đối — REJECTED bất kể điểm số — nên chỉ dành cho ràng buộc pháp lý,
    không dành cho khẩu vị rủi ro. Khẩu vị rủi ro thuộc tầng điểm số.
    """
    legal = get_legal_limits()
    vi_pham: list[str] = []

    # 1. Trần lãi suất 20%/năm theo Điều 468 Bộ luật Dân sự 2015
    lai_suat = _so_hoac_none(features.get("int_rate"))
    if lai_suat is not None:
        ty_le_lai = _ty_le_lai_nam(lai_suat)
        if ty_le_lai > legal["max_interest_rate"]:
            vi_pham.append("INTEREST_RATE_EXCEEDS_LEGAL_LIMIT")
        elif ty_le_lai <= 0.0:
            vi_pham.append("INVALID_INTEREST_RATE")

    # 2. Trần kỳ hạn theo Nghị định 94/2025/NĐ-CP
    ky_han = _so_hoac_none(features.get("term_months"))
    if ky_han is not None and ky_han > legal["max_term_months"]:
        vi_pham.append("TERM_EXCEEDS_LEGAL_LIMIT")

    # 3. Nợ xấu CIC — Thông tư 11/2021/TT-NHNN
    nhom_no = _so_hoac_none(features.get("nhom_no_cao_nhat"))
    if nhom_no is not None and nhom_no >= NHOM_NO_XAU_TOI_THIEU:
        vi_pham.append("CIC_BAD_DEBT_GROUP")

    # 4. Trần tổng dư nợ 400 triệu trên TOÀN BỘ nền tảng thử nghiệm —
    # Quyết định 2866/QĐ-NHNN ngày 22/7/2025. Đây là trần thứ hai, độc lập với
    # trần 100 triệu/nền tảng đã áp ở `_build_bang_xep_hang()`: một người vay đủ
    # hạn mức ở bốn nền tảng khác nhau vẫn hợp lệ ở từng nơi nhưng vi phạm trần tổng.
    #
    # Phải cộng cả khoản đang xin vay, không chỉ dư nợ hiện có: trần là mức dư nợ
    # SAU khi giải ngân, kiểm tra trước khi cộng sẽ luôn cho lọt khoản vay đẩy
    # người vay vượt trần.
    #
    # `tong_du_no` lấy từ CIC, nên hồ sơ không tra được CIC sẽ bỏ qua chốt chặn này
    # và rơi vào PENDING_REVIEW theo cơ chế thiếu dữ liệu — đúng nguyên tắc không
    # phạt người vay vì sự cố hạ tầng, nhưng cũng không tự động duyệt.
    du_no_hien_co = _so_hoac_none(features.get("tong_du_no"))
    khoan_vay_moi = _so_hoac_none(features.get("loan_amnt"))
    if (
        du_no_hien_co is not None
        and khoan_vay_moi is not None
        and du_no_hien_co + khoan_vay_moi > legal["max_total_debt_all_platforms"]
    ):
        vi_pham.append("TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT")

    return vi_pham


def quyet_dinh(
    evaluation_score: float,
    vi_pham: list[str] | None = None,
    so_luat_co_du_lieu: int | None = None,
) -> str:
    """Quyết định tự động: REJECTED / PENDING_REVIEW / APPROVED.

    Thứ tự ưu tiên:
      1. Vi phạm chốt chặn cứng           → REJECTED
      2. Thiếu dữ liệu để chấm đáng tin   → PENDING_REVIEW
      3. Ngưỡng điểm từ config

    Hồ sơ thiếu dữ liệu KHÔNG bị từ chối: không tra được thông tin là sự cố của
    nền tảng chứ không phải lỗi người vay, nên đẩy sang thẩm định viên xem xét.
    """
    if vi_pham:
        return "REJECTED"

    if so_luat_co_du_lieu is not None and so_luat_co_du_lieu < SO_LUAT_TOI_THIEU_CO_DU_LIEU:
        return "PENDING_REVIEW"

    thresholds = get_approval_thresholds()
    if evaluation_score >= thresholds["auto_approve"]:
        return "APPROVED"
    if evaluation_score < thresholds["auto_reject"]:
        return "REJECTED"
    return "PENDING_REVIEW"
