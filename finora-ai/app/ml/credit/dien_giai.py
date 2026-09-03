"""
Diễn giải kết quả chấm điểm sang ngôn ngữ người vay đọc được.

Vì sao cần lớp này
------------------
`explainer.py` trả về đóng góp TreeSHAP trên thang log-odds, ví dụ
`{"mo_ta": "Điểm tín dụng CIC", "muc_dong_gop": 0.8915}`. Con số đó đúng về mặt
toán học nhưng vô nghĩa với người vay: nó không phải phần trăm, không phải tiền,
và không nói được phải làm gì để lần sau được duyệt.

Một lời giải thích chỉ hoàn thành nhiệm vụ khi người nhận hiểu được. Vì vậy lớp
này dịch ba thứ:

  1. `thong_diep` — một câu tóm tắt quyết định.
  2. `ly_do_chinh` — vì sao, diễn đạt bằng chính giá trị hồ sơ chứ không phải hệ số.
  3. `goi_y_cai_thien` — làm gì để lần sau khá hơn, kèm mốc cụ thể.

Nguồn của gợi ý: NGƯỠNG THẬT trong Rule Engine, không phải lời khuyên bịa
-------------------------------------------------------------------------
Mỗi gợi ý được suy ra từ `bac` của chính luật đang chấm — đọc từ
`product_config.json` lúc chạy. Ví dụ luật CIC có bậc (740, 700, 670): hồ sơ đang
ở 723 thì gợi ý nêu đúng mốc kế tiếp là 740, và mốc đó tự đổi theo nếu admin sửa
cấu hình. Nếu viết cứng "hãy nâng điểm CIC lên 750" thì lời khuyên sẽ sai ngay
khi ai đó chỉnh ngưỡng, mà không có gì báo lỗi.

Ranh giới trách nhiệm
---------------------
Lớp này KHÔNG quyết định gì và KHÔNG chấm lại điểm. Nó chỉ diễn đạt lại kết quả
đã có. Quyết định duyệt/từ chối vẫn thuộc `rule_engine.quyet_dinh()`, và hành
động nghiệp vụ thuộc `finora-loan`.
"""

from app.services.credit.rule_engine import lay_bo_luat

# Ngưỡng "còn lại" trong config là một số rất lớn thay cho vô cực (JSON không có
# Infinity). Bậc nào có ngưỡng từ mức này trở lên thì không phải mốc phấn đấu.
NGUONG_VO_CUC = 1_000_000_000

# Số gợi ý tối đa trả về. Ba là mức người vay còn hành động được; dài hơn thì
# thành danh sách ước và không ai làm theo.
SO_GOI_Y_TOI_DA = 3

# Mã chốt chặn → câu giải thích cho người vay. Không dùng mã kỹ thuật ở đây vì
# `INTEREST_RATE_EXCEEDS_LEGAL_LIMIT` không nói được gì với người ngoài ngành.
DIEN_GIAI_CHOT_CHAN: dict[str, str] = {
    "INTEREST_RATE_EXCEEDS_LEGAL_LIMIT": (
        "Lãi suất của khoản vay vượt trần 20%/năm mà Bộ luật Dân sự cho phép. "
        "Đây là giới hạn pháp lý, không phải đánh giá về hồ sơ của bạn."
    ),
    "INVALID_INTEREST_RATE": (
        "Lãi suất khoản vay không hợp lệ. Vui lòng kiểm tra lại thông tin sản phẩm vay."
    ),
    "TERM_EXCEEDS_LEGAL_LIMIT": (
        "Kỳ hạn vay vượt 24 tháng — mức tối đa cho vay ngang hàng theo Nghị định "
        "94/2025. Hãy chọn kỳ hạn ngắn hơn."
    ),
    "DEBT_SERVICE_RATIO_TOO_HIGH": (
        "Số tiền phải trả hàng tháng vượt quá một nửa thu nhập của bạn. Khoản vay "
        "này sẽ khiến bạn không đủ chi tiêu sinh hoạt."
    ),
    "CIC_BAD_DEBT_GROUP": (
        "Bạn đang có nợ xấu từ nhóm 3 trở lên trên hệ thống CIC. Theo Thông tư "
        "11/2021 của Ngân hàng Nhà nước, tổ chức tín dụng không được cấp khoản vay "
        "mới trong trường hợp này."
    ),
    "TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT": (
        "Tổng dư nợ của bạn cộng khoản vay này vượt trần 400 triệu đồng trên toàn "
        "bộ nền tảng cho vay ngang hàng, theo Quyết định 2866/QĐ-NHNN."
    ),
    "AGE_AND_EXPERIENCE_INCONSISTENCY": (
        "Thông tin tuổi và số năm làm việc trong hồ sơ mâu thuẫn nhau. Vui lòng "
        "kiểm tra lại trước khi nộp."
    ),
}

# Gợi ý cải thiện theo từng luật. `{moc}` được thay bằng mốc thật của bậc kế tiếp.
GOI_Y_THEO_LUAT: dict[str, str] = {
    "CHARACTER_CIC_HISTORY": (
        "Nâng điểm tín dụng CIC lên {moc} điểm bằng cách trả nợ đúng hạn trong "
        "vài kỳ tới. Đây là yếu tố có trọng số cao nhất trong nhóm uy tín."
    ),
    "CAPACITY_EXISTING_DEBT": (
        "Giảm tỷ lệ nợ trên thu nhập xuống dưới {moc}% — bằng cách trả bớt dư nợ "
        "hiện có hoặc chứng minh thêm nguồn thu nhập."
    ),
    "CAPACITY_INSTALLMENT_BURDEN": (
        "Giảm số tiền trả hàng tháng xuống dưới {moc}% thu nhập, bằng cách vay ít "
        "hơn hoặc kéo dài kỳ hạn (tối đa 24 tháng)."
    ),
    "CHARACTER_CREDIT_SEEKING": (
        "Hạn chế nộp hồ sơ vay ở nhiều nơi cùng lúc. Giữ số lần bị tra cứu CIC "
        "trong 6 tháng ở mức {moc} lần trở xuống."
    ),
    "CAPITAL_RESIDENCE_STABILITY": (
        "Tình trạng nhà ở ảnh hưởng tới điểm tài sản tích lũy. Nếu bạn sở hữu nhà "
        "hoặc đang trả góp mua nhà, hãy bổ sung giấy tờ chứng minh."
    ),
}


def _moc_can_dat(luat, gia_tri: float | None) -> float | None:
    """Ngưỡng gần nhất mà hồ sơ chưa đạt, theo đúng chiều so sánh của luật."""
    if luat.bang_diem is not None or not luat.bac or gia_tri is None:
        return None

    ung_vien: list[float] = []
    for nguong, _diem in luat.bac:
        if nguong >= NGUONG_VO_CUC:
            continue
        dat = (gia_tri <= nguong) if luat.nghich_dao else (gia_tri >= nguong)
        if not dat:
            ung_vien.append(nguong)

    if not ung_vien:
        return None
    # Với luật nghịch đảo (càng thấp càng tốt) mốc dễ đạt nhất là ngưỡng LỚN nhất
    # trong nhóm chưa đạt; với luật thuận thì là ngưỡng NHỎ nhất.
    return max(ung_vien) if luat.nghich_dao else min(ung_vien)


def _so_gon(x: float) -> str:
    """Bỏ phần thập phân thừa: 740.0 -> '740', 0.35 -> '35' khi là tỷ lệ."""
    return str(int(x)) if float(x).is_integer() else str(x)


def sinh_dien_giai(ket_qua: dict) -> dict:
    """Dịch kết quả chấm điểm sang thông điệp người vay đọc được.

    Nhận nguyên dict mà `BoDuDoan.du_doan()` trả về. Trả về ba phần: thông điệp
    tóm tắt, lý do chính, và danh sách gợi ý cải thiện.
    """
    quyet_dinh = ket_qua["decision"]
    vi_pham: list[str] = ket_qua.get("rejection_reasons") or []
    vet: list[dict] = ket_qua.get("rule_trace") or []

    # ── Thông điệp tóm tắt ────────────────────────────────────────────────
    if quyet_dinh == "APPROVED":
        thong_diep = (
            f"Hồ sơ của bạn đủ điều kiện vay. Hạn mức đề xuất "
            f"{ket_qua['suggested_limit']:,} đồng, xếp hạng tín dụng "
            f"{ket_qua['credit_grade']}.".replace(",", ".")
        )
    elif quyet_dinh == "PENDING_REVIEW":
        thong_diep = (
            "Hồ sơ của bạn cần thẩm định viên xem xét thêm trước khi có kết quả "
            "cuối cùng. Điều này thường xảy ra khi hệ thống chưa tra đủ thông tin."
        )
    elif vi_pham:
        thong_diep = (
            "Hồ sơ của bạn chưa đủ điều kiện vay do vướng quy định bắt buộc. "
            "Xem chi tiết bên dưới."
        )
    else:
        thong_diep = (
            f"Hồ sơ của bạn chưa đạt ngưỡng duyệt tự động (xếp hạng "
            f"{ket_qua['credit_grade']}). Bạn có thể cải thiện theo gợi ý bên dưới "
            "rồi nộp lại."
        )

    # ── Lý do chính ───────────────────────────────────────────────────────
    # Vi phạm chốt chặn được ưu tiên tuyệt đối: đó là lý do thật sự khiến hồ sơ
    # bị từ chối, mọi phân tích điểm số phía sau đều không đổi được kết quả.
    ly_do: list[str] = [
        DIEN_GIAI_CHOT_CHAN.get(ma, f"Hồ sơ vi phạm quy định: {ma}")
        for ma in vi_pham
    ]

    # ── Gợi ý cải thiện ───────────────────────────────────────────────────
    goi_y: list[str] = []
    bo_luat = {l.ma: l for l in lay_bo_luat()}

    # Luật mất điểm nhiều nhất được gợi ý trước — sửa chỗ đó lợi nhất. Chỉ lấy
    # ba gợi ý: danh sách dài thì người vay không biết bắt đầu từ đâu, và những
    # luật chỉ hụt vài điểm không đáng để họ đổi cả kế hoạch tài chính.
    thieu_diem = sorted(
        (t for t in vet if t["diem"] < t["toi_da"]),
        key=lambda t: t["toi_da"] - t["diem"],
        reverse=True,
    )[:SO_GOI_Y_TOI_DA]

    for t in thieu_diem:
        luat = bo_luat.get(t["ma"])
        mau = GOI_Y_THEO_LUAT.get(t["ma"])
        if luat is None or mau is None:
            continue

        if t["thieu_du_lieu"]:
            ly_do.append(
                f"Chưa tra được thông tin cho tiêu chí “{t['mo_ta']}”, nên hệ thống "
                "chấm mức trung tính thay vì mức thật của bạn."
            )
            continue

        gia_tri = t["gia_tri"] if isinstance(t["gia_tri"], (int, float)) else None
        moc = _moc_can_dat(luat, gia_tri)
        if moc is None:
            # Luật tra bảng, hoặc đã ở bậc cao nhất mà vẫn chưa tối đa điểm.
            if luat.bang_diem is not None:
                goi_y.append(mau.format(moc=""))
            continue

        # Hai luật này đo bằng tỷ lệ 0–1 nhưng nói với người dùng bằng phần trăm.
        if t["ma"] == "CAPACITY_INSTALLMENT_BURDEN":
            moc_hien_thi = _so_gon(moc * 100)
        else:
            moc_hien_thi = _so_gon(moc)

        goi_y.append(mau.format(moc=moc_hien_thi))

    return {
        "thong_diep": thong_diep,
        "ly_do_chinh": ly_do,
        "goi_y_cai_thien": goi_y,
    }
