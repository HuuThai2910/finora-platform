"""
Rule Engine — chấm điểm rủi ro bằng quy tắc tường minh, chạy song song với mô hình ML.

Đây là phần minh bạch nhất của hệ thống: ngưỡng cố định, không học từ dữ liệu, giải
trình được với người vay và với kiểm toán mà không cần công cụ nào.

**Đã thiết kế lại sau khi bỏ phụ thuộc CIC/FICO.** Bản trước chấm 4 yếu tố, trong đó
3 yếu tố lấy dữ liệu từ báo cáo tín dụng CIC (DTI, điểm FICO, lịch sử trễ hạn + nhóm
nợ). FINORA không có kết nối API tới CIC nên ba yếu tố đó không tính được — nếu giữ
lại, chúng luôn rơi vào nhánh "không có dữ liệu" và cho cùng một mức điểm với mọi hồ
sơ, tức mất hoàn toàn khả năng phân biệt.

Bốn yếu tố hiện tại đều tính được từ hồ sơ tự khai + eKYC:

| Yếu tố | Nhóm 5C | Nguồn dữ liệu | Thay cho yếu tố cũ |
|---|---|---|---|
| Tỷ lệ vay trên thu nhập | Capacity | Form vay + thu nhập tự khai | `dti` (CIC) |
| Thâm niên việc làm | Character | Hợp đồng lao động | Điểm FICO (CIC) |
| Tình trạng nhà ở | Capital | Tự khai | (giữ nguyên) |
| Mức thu nhập năm | Capacity | Sao kê lương | Lịch sử trễ hạn (CIC) |
"""
from collections import namedtuple

XepHangTinDung = namedtuple("XepHangTinDung", ["hang", "han_muc", "lai_suat"])

# Trần lãi suất 20%/năm theo Điều 468 Bộ luật Dân sự 2015: lãi suất do các bên
# thỏa thuận không được vượt quá 20%/năm của khoản tiền vay. FINORA không phải tổ
# chức tín dụng nên không thuộc diện ngoại lệ của Luật Các tổ chức tín dụng —
# giao dịch giữa người vay và nhà đầu tư là quan hệ vay tài sản dân sự thuần túy.
TRAN_LAI_SUAT_NAM = 0.20

# Nghị định 94/2025/NĐ-CP: hạn mức tối đa 100 triệu đồng trên một nền tảng.
TRAN_HAN_MUC_NEN_TANG = 100_000_000

BANG_XEP_HANG = [
    (80, XepHangTinDung("A", 50_000_000, 0.12)),
    (60, XepHangTinDung("B", 30_000_000, 0.15)),
    (40, XepHangTinDung("C", 15_000_000, 0.18)),
    (0,  XepHangTinDung("D",  5_000_000, 0.20)),
]

assert all(h.lai_suat <= TRAN_LAI_SUAT_NAM for _, h in BANG_XEP_HANG), (
    "Lãi suất đề xuất vượt trần 20%/năm của Điều 468 Bộ luật Dân sự 2015"
)
assert all(h.han_muc <= TRAN_HAN_MUC_NEN_TANG for _, h in BANG_XEP_HANG), (
    "Hạn mức đề xuất vượt trần 100 triệu đồng/nền tảng của Nghị định 94/2025"
)

# Ngưỡng thu nhập đặt theo mức lao động phổ thông tại Việt Nam:
# 120 triệu/năm ≈ 10 triệu/tháng · 300 triệu/năm ≈ 25 triệu/tháng.
THU_NHAP_CAO = 300_000_000
THU_NHAP_TRUNG_BINH = 120_000_000


def _doc_tham_nien(emp_length) -> float | None:
    """Đọc thâm niên từ chuỗi dạng LendingClub ("10+ years", "< 1 year")."""
    if emp_length is None:
        return None
    chuoi = str(emp_length).strip()
    if "10+" in chuoi:
        return 10.0
    if "< 1" in chuoi:
        return 0.5
    chu_so = "".join(c for c in chuoi if c.isdigit())
    return float(chu_so) if chu_so else None


def tinh_diem_rui_ro(features: dict) -> int:
    """Tính điểm rủi ro theo quy tắc 5C (0-100), mỗi yếu tố tối đa 25 điểm."""
    diem = 0
    thu_nhap = features.get("annual_inc") or 0

    # 1. Capacity — tỷ lệ khoản vay trên thu nhập năm (25 điểm)
    #
    # Thay cho `dti` của bản cũ. `dti` cần tổng dư nợ hiện tại từ CIC làm tử số nên
    # không tính được; tỷ lệ này chỉ cần hai số đã có sẵn trong hồ sơ. Nó đo cùng một
    # thứ — gánh nặng trả nợ so với thu nhập — nhưng CHỈ tính khoản đang xin vay,
    # không thấy được các khoản nợ khác của người vay. Hạn chế này phải nêu khi báo cáo.
    ty_le_vay = (features.get("loan_amnt") or 0) / thu_nhap if thu_nhap > 0 else 99.0
    if ty_le_vay <= 0.20:
        diem += 25
    elif ty_le_vay <= 0.50:
        diem += 15
    else:
        diem += 5

    # 2. Character — thâm niên việc làm (25 điểm)
    #
    # Thay cho điểm tín dụng của bản cũ. Không đo được lịch sử trả nợ, nhưng thâm niên
    # việc làm là tín hiệu ổn định thu nhập mạnh nhất mà FINORA tự thu thập được
    # (đối chiếu qua hợp đồng lao động / sao kê lương).
    tham_nien = features.get("emp_length_years")
    if tham_nien is None:
        tham_nien = _doc_tham_nien(features.get("emp_length"))
    if tham_nien is None:
        diem += 5
    elif tham_nien >= 5:
        diem += 25
    elif tham_nien >= 2:
        diem += 15
    else:
        diem += 5

    # 3. Capital — tình trạng nhà ở (25 điểm)
    diem_nha = {"OWN": 25, "MORTGAGE": 20, "RENT": 10, "OTHER": 5}
    diem += diem_nha.get(features.get("home_ownership", "OTHER"), 5)

    # 4. Capacity — mức thu nhập năm tuyệt đối (25 điểm)
    #
    # Thay cho yếu tố lịch sử trễ hạn của bản cũ. Khác yếu tố 1 ở chỗ đo mức tuyệt
    # đối: người thu nhập 5 triệu/tháng vay 1 triệu có tỷ lệ rất đẹp nhưng đệm tài
    # chính vẫn mỏng, cú sốc nhỏ cũng mất khả năng trả.
    if thu_nhap >= THU_NHAP_CAO:
        diem += 25
    elif thu_nhap >= THU_NHAP_TRUNG_BINH:
        diem += 15
    else:
        diem += 5

    return diem


def tinh_diem_tong_hop(pd_probability: float, risk_score: int) -> float:
    """Tính điểm tổng hợp = pd_score x 60% + risk_score x 40%."""
    pd_score = (1 - pd_probability) * 100
    return pd_score * 0.6 + risk_score * 0.4


def xep_hang(evaluation_score: float) -> XepHangTinDung:
    """Xếp hạng tín dụng A/B/C/D dựa trên điểm tổng hợp."""
    for nguong, hang in BANG_XEP_HANG:
        if evaluation_score >= nguong:
            return hang
    return BANG_XEP_HANG[-1][1]


def quyet_dinh(evaluation_score: float) -> str:
    """Quyết định tự động: REJECTED / PENDING_REVIEW / APPROVED."""
    if evaluation_score < 10:
        return "REJECTED"
    if evaluation_score >= 90:
        return "APPROVED"
    return "PENDING_REVIEW"
