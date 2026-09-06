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
Toàn bộ luật chấm điểm nằm trong `config/product_config.json["rules"]`, admin
thêm/sửa/xoá qua `PUT /api/v1/ai/config/rules`. Mỗi luật chỉ định: đọc TRƯỜNG nào
(chọn trong `truong_du_lieu.DANH_MUC_TRUONG`), quy đổi ra điểm theo bậc ngưỡng
hoặc bảng tra, trọng số so với luật khác, và điểm khi thiếu dữ liệu. Code ở đây
không biết trước có bao nhiêu luật hay luật tên gì — `lay_bo_luat()` ghép config
với danh mục trường thành `LuatChamDiem`, `cham_diem_chi_tiet()` chỉ chạy qua
danh sách đó. Bộ luật mặc định (năm luật đã kiểm chứng AUC) chỉ là dữ liệu khởi
tạo, không có vị thế gì đặc biệt so với luật admin tạo thêm.

Vì sao trường thiếu KHÔNG bị cho điểm sàn
-----------------------------------------
Ở Việt Nam cic-service có thể không trả lời, và khi đó "không tra được lịch sử tín
dụng" không đồng nghĩa với "lịch sử tín dụng xấu". Cho điểm sàn là phạt oan người
vay vì sự cố hạ tầng của nền tảng. Mỗi luật vì vậy cho điểm trung tính khi thiếu dữ
liệu và đánh dấu `thieu_du_lieu=True` để thẩm định viên biết mà xem lại.

Vì sao KHÔNG luật nào dùng int_rate để chấm điểm
------------------------------------------------
Lãi suất là target leakage (xem docstring `truong_du_lieu.py`). Danh mục trường
không có int_rate nên admin không thể tạo luật chấm theo nó; int_rate chỉ dùng ở
chốt chặn pháp lý (trần 20%/năm) và để tính ra số tiền phải trả hàng tháng.

Khoảng điểm, hạng tín dụng, hạn mức và ngưỡng duyệt đọc từ config/product_config.json.
"""

import math
from collections import namedtuple
from dataclasses import dataclass
from typing import Any

from app.services.credit.product_config import (
    get_approval_thresholds,
    get_grades,
    get_legal_limits,
    get_model_weights,
    get_rules,
)
from app.services.credit.truong_du_lieu import (
    DANH_MUC_TRUONG,
    TruongDuLieu,
    _so_hoac_none,
    _ty_le_lai_nam,
)

XepHangTinDung = namedtuple("XepHangTinDung", ["hang", "han_muc"])

# Điểm cao nhất một luật có thể cho ở bậc tốt nhất. Mọi luật cùng trần này để trọng
# số giữa chúng nằm tường minh ở `trong_so`, không ẩn trong độ lớn điểm bậc.
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

# Tỷ lệ luật (trong số luật đang bật) phải có dữ liệu thật thì kết quả mới đáng tin;
# dưới ngưỡng này hồ sơ bị đẩy sang thẩm định viên thay vì để máy quyết.
# Là tỷ lệ chứ không phải số tuyệt đối vì số luật do admin quyết: hằng số 3 của bản
# cũ chỉ đúng với 5 luật — với 10 luật, 3 luật có dữ liệu là quá lỏng. 0,6 cho đúng
# 3 với 5 luật (giữ hành vi đã kiểm chứng) và tự nâng lên khi bộ luật lớn hơn.
TY_LE_LUAT_TOI_THIEU_CO_DU_LIEU = 0.6


def so_luat_toi_thieu_co_du_lieu(so_luat_da_cham: int) -> int:
    """Số luật tối thiểu phải có dữ liệu thật, làm tròn LÊN để không bao giờ lỏng hơn tỷ lệ."""
    return math.ceil(so_luat_da_cham * TY_LE_LUAT_TOI_THIEU_CO_DU_LIEU)


# ── Đặc tả luật ───────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class LuatChamDiem:
    """Một luật chấm điểm đã ghép config với danh mục trường, sẵn sàng chạy.

    bac: danh sách (nguong, diem). Luật lấy điểm của ngưỡng đầu tiên mà giá trị
         đạt tới. Với chỉ số "càng cao càng xấu" (ví dụ dti), đặt `nghich_dao=True`
         để so sánh đổi chiều.
    bang_diem: dùng thay `bac` cho luật tra bảng theo giá trị rời rạc (nhà ở, mục đích).
    trong_so: tỷ trọng của luật so với các luật khác khi chuẩn hoá về thang 100.
    bat: False thì luật bị bỏ qua hoàn toàn, không cộng điểm và không vào trace.
    goi_y: mẫu câu gợi ý cải thiện cho người vay, có chỗ trống `{moc}`; None thì
           `dien_giai.py` tự ghép câu chung từ mô tả và chiều so sánh.
    """

    ma: str
    mo_ta: str
    truong: TruongDuLieu
    diem_khi_thieu: int
    trong_so: float = 1.0
    bac: tuple[tuple[float, int], ...] = ()
    bang_diem: dict[str, int] | None = None
    nghich_dao: bool = False
    bat: bool = True
    goi_y: str | None = None

    @property
    def diem_toi_da(self) -> int:
        """Điểm cao nhất luật có thể cho — suy ra từ cấu hình, không khai cứng."""
        if self.bang_diem is not None:
            return max(self.bang_diem.values())
        return max(diem for _, diem in self.bac)

    def cham(self, features: dict) -> tuple[int, Any, bool]:
        """Trả về (điểm, giá trị đã dùng, có thiếu dữ liệu không)."""
        gia_tri = self.truong.doc(features)
        if gia_tri is None:
            return self.diem_khi_thieu, None, True

        if self.bang_diem is not None:
            return self.bang_diem[gia_tri], gia_tri, False

        for nguong, diem in self.bac:
            if (gia_tri <= nguong) if self.nghich_dao else (gia_tri >= nguong):
                return diem, gia_tri, False
        return self.bac[-1][1], gia_tri, False


def _ghep_luat(c: dict) -> LuatChamDiem:
    """Ghép một bản ghi config với danh mục trường. Lỗi config phải nổ RÕ, không im lặng."""
    truong = DANH_MUC_TRUONG.get(c["truong"])
    if truong is None:
        raise ValueError(
            f"Luật {c['ma']}: trường '{c['truong']}' không có trong danh mục trường "
            f"(hợp lệ: {', '.join(DANH_MUC_TRUONG)})"
        )
    la_bang_diem = truong.kieu == "phan_loai"
    return LuatChamDiem(
        ma=c["ma"],
        mo_ta=c["mo_ta"],
        truong=truong,
        diem_khi_thieu=int(c["diem_khi_thieu"]),
        trong_so=float(c.get("trong_so", 1.0)),
        nghich_dao=bool(c.get("nghich_dao", False)) and not la_bang_diem,
        bat=c.get("bat", True),
        goi_y=c.get("goi_y") or None,
        bang_diem={k: int(v) for k, v in c["bang_diem"].items()}
        if la_bang_diem
        else None,
        bac=()
        if la_bang_diem
        else tuple((float(nguong), int(diem)) for nguong, diem in c["bac"]),
    )


def lay_bo_luat() -> tuple[LuatChamDiem, ...]:
    """Dựng bộ luật chạy được từ config, đúng thứ tự admin sắp.

    Đọc lại config mỗi lần gọi để admin sửa luật có hiệu lực ngay, không cần
    khởi động lại service. Luật bị tắt (`bat=False`) vẫn được ghép nhưng
    `cham_diem_chi_tiet()` sẽ bỏ qua.
    """
    return tuple(_ghep_luat(c) for c in get_rules())


# ── Chấm điểm ─────────────────────────────────────────────────────────────────


def cham_diem_chi_tiet(features: dict) -> tuple[int, list[dict]]:
    """Chấm điểm rủi ro và trả kèm vết luật.

    Trả về (điểm 0-100, danh sách vết luật). Mỗi vết ghi lại luật nào đã chạy,
    đọc trường gì được giá trị gì và cộng bao nhiêu điểm — đủ để dựng màn hình
    giải trình cho thẩm định viên hoặc trả lời khiếu nại của người vay.

    Điểm được CHUẨN HÓA về thang 100 theo tổng điểm tối đa CÓ TRỌNG SỐ của các luật
    đang bật: Σ(điểm × trọng số) / Σ(tối đa × trọng số). Không chuẩn hóa thì tắt
    hay thêm một luật sẽ kéo trần điểm lệch đi và mọi hồ sơ đổi hạng oan — một
    thay đổi cấu hình tưởng vô hại lại làm sai toàn bộ quyết định.
    """
    tong_tho = 0.0
    tran_tho = 0.0
    vet = []

    for luat in lay_bo_luat():
        if not luat.bat:
            continue
        diem, gia_tri, thieu = luat.cham(features)
        tong_tho += diem * luat.trong_so
        tran_tho += luat.diem_toi_da * luat.trong_so
        vet.append(
            {
                "ma": luat.ma,
                "mo_ta": luat.mo_ta,
                "truong": luat.truong.ma,
                "gia_tri": round(gia_tri, 4) if isinstance(gia_tri, float) else gia_tri,
                "diem": diem,
                "toi_da": luat.diem_toi_da,
                "trong_so": luat.trong_so,
                "thieu_du_lieu": thieu,
            }
        )

    if tran_tho == 0:
        # Admin tắt hết luật. Không có cơ sở chấm điểm, trả 0 để hồ sơ rơi vào
        # nhánh thiếu dữ liệu ở `quyet_dinh()` thay vì được điểm khống.
        return 0, vet

    return round(tong_tho * 100 / tran_tho), vet


def tinh_diem_rui_ro(features: dict) -> int:
    """Tính điểm rủi ro theo bộ luật (0-100), bỏ vết chấm.

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
    không dành cho khẩu vị rủi ro. Khẩu vị rủi ro thuộc tầng điểm số, và đó là lý
    do chốt chặn cố định trong code trong khi luật chấm điểm là config.
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
    so_luat_da_cham: int | None = None,
) -> str:
    """Quyết định tự động: REJECTED / PENDING_REVIEW / APPROVED.

    Quyết định dựa trên ngưỡng điểm, độc lập với hạng tín dụng. Hạng A-E là đầu
    vào để Loan định giá lãi suất; vì vậy một hồ sơ hạng B vẫn có thể chờ admin
    nếu điểm chưa đạt `auto_approve` mà không làm thay đổi mức lãi của hạng B.

    Thứ tự ưu tiên:
      1. Vi phạm chốt chặn cứng           → REJECTED
      2. Thiếu dữ liệu để chấm đáng tin   → PENDING_REVIEW
      3. Ngưỡng điểm từ config

    Hồ sơ thiếu dữ liệu KHÔNG bị từ chối: không tra được thông tin là sự cố của
    nền tảng chứ không phải lỗi người vay, nên đẩy sang thẩm định viên xem xét.
    `so_luat_da_cham` là mẫu số của tỷ lệ; chỗ gọi cũ chỉ truyền tử số thì lấy
    số luật đang bật trong config làm mẫu số.
    """
    if vi_pham:
        return "REJECTED"

    if so_luat_co_du_lieu is not None:
        if so_luat_da_cham is None:
            so_luat_da_cham = sum(1 for luat in lay_bo_luat() if luat.bat)
        if so_luat_co_du_lieu < so_luat_toi_thieu_co_du_lieu(so_luat_da_cham):
            return "PENDING_REVIEW"

    thresholds = get_approval_thresholds()
    if evaluation_score >= thresholds["auto_approve"]:
        return "APPROVED"
    if evaluation_score < thresholds["auto_reject"]:
        return "REJECTED"
    return "PENDING_REVIEW"
