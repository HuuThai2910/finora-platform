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

AI dẫn dắt, Rule Engine cấp mốc
-------------------------------
Điểm tổng hợp lấy 85% từ mô hình và 15% từ rule engine, nên thứ tự ưu tiên của
lời khuyên phải theo mô hình. Bản trước xếp gợi ý theo "luật nào mất nhiều điểm
nhất" — hệ quả là yếu tố mô hình coi nặng nhất có thể không được nhắc lần nào,
trong khi người vay bị từ chối chủ yếu vì chính nó.

Nay thứ tự do TreeSHAP quyết định: `tom_tat_yeu_to()` gộp 47 đặc trưng về dữ kiện
gốc, và các nhóm bất lợi được duyệt theo độ lớn đóng góp giảm dần.

Mỗi nhóm nói được tới đâu thì tuỳ dữ liệu sẵn có:

  * Nhóm có luật đọc cùng dữ kiện (tra qua `nhom_shap` của trường trong danh mục)
    → nêu **mốc thật** lấy từ `bac` của luật trong `product_config.json`. Ví dụ
    luật CIC có bậc (740, 700, 670), hồ sơ đang ở 600 thì gợi ý nêu đúng mốc gần
    nhất là 670, và mốc đó tự đổi khi admin sửa cấu hình. Viết cứng "nâng lên 750"
    thì lời khuyên sai ngay khi ai đó chỉnh ngưỡng, mà không có gì báo lỗi.
  * Nhóm chỉ mô hình biết, rule engine không có luật → nêu bằng **chính giá trị
    trên hồ sơ**, không bịa ra mốc. SHAP cho biết đặc trưng đó đẩy rủi ro lên bao
    nhiêu, nhưng không cho biết "cần đạt bao nhiêu" — nói mốc ở đây là bịa số.

Luật là dữ liệu, câu gợi ý cũng là dữ liệu
------------------------------------------
Admin thêm luật tuỳ ý, nên lớp này KHÔNG có bảng câu viết cứng theo mã luật. Mỗi
luật mang mẫu câu `goi_y` riêng (chỗ trống `{moc}` là ngưỡng kế tiếp); luật không
có mẫu thì ghép câu chung từ mô tả luật, chiều so sánh và đơn vị của trường. Câu
chung khô hơn câu admin viết, nhưng vẫn đúng mốc và không lộ mã kỹ thuật.

Nhóm lãi suất bị loại khỏi gợi ý vì `int_rate` là target leakage: lãi suất được
gán SAU khi chấm rủi ro, nên khuyên "giảm lãi suất để bớt rủi ro" là lập luận
vòng tròn. `explainer.NHOM_LOAI_KHOI_TOM_TAT` đã lọc sẵn nhóm này.

Ranh giới trách nhiệm
---------------------
Lớp này KHÔNG quyết định gì và KHÔNG chấm lại điểm. Nó chỉ diễn đạt lại kết quả
đã có. Quyết định duyệt/từ chối vẫn thuộc `rule_engine.quyet_dinh()`, và hành
động nghiệp vụ thuộc `finora-loan`.
"""

from app.services.credit.rule_engine import LuatChamDiem, lay_bo_luat

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
    "CIC_BAD_DEBT_GROUP": (
        "Bạn đang có nợ xấu từ nhóm 3 trở lên trên hệ thống CIC. Theo Thông tư "
        "11/2021 của Ngân hàng Nhà nước, tổ chức tín dụng không được cấp khoản vay "
        "mới trong trường hợp này."
    ),
    "TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT": (
        "Tổng dư nợ của bạn cộng khoản vay này vượt trần 400 triệu đồng trên toàn "
        "bộ nền tảng cho vay ngang hàng, theo Quyết định 2866/QĐ-NHNN."
    ),
}

# Nhóm chỉ mô hình nhìn thấy, rule engine không có luật nào chấm. Không có bậc
# thang nên không nêu mốc — chỉ nói rõ dữ kiện nào đang kéo hồ sơ xuống và hướng
# xử lý, để người vay biết chỗ mà xoay xở. Cũng là câu dự phòng khi nhóm có luật
# nhưng hồ sơ đã ở bậc cao nhất của luật đó.
GOI_Y_NHOM_AI: dict[str, str] = {
    "ky_han": (
        "Kỳ hạn vay đang là yếu tố kéo hồ sơ xuống. Kỳ hạn ngắn hơn thường được "
        "đánh giá tốt hơn, nhưng sẽ làm tăng số tiền trả hàng tháng — hãy cân đối "
        "với thu nhập của bạn."
    ),
    "du_no": (
        "Tổng dư nợ hiện có đang ảnh hưởng xấu tới đánh giá. Trả bớt các khoản vay "
        "đang có trước khi nộp hồ sơ mới sẽ cải thiện đáng kể."
    ),
    "khoan_vay": (
        "Số tiền vay đang khá lớn so với thu nhập của bạn. Cân nhắc vay ít hơn, "
        "hoặc bổ sung giấy tờ chứng minh thêm nguồn thu."
    ),
    "the_tin_dung": (
        "Dư nợ và mức sử dụng thẻ tín dụng đang kéo điểm xuống. Giảm dư nợ thẻ "
        "xuống dưới 30% hạn mức là mức được đánh giá tốt."
    ),
    "lich_su_tra_no": (
        "Lịch sử trễ hạn trên CIC đang ảnh hưởng tới hồ sơ. Yếu tố này cần thời "
        "gian: trả nợ đúng hạn đều đặn trong vài kỳ tới sẽ cải thiện dần."
    ),
    "quan_he_tin_dung": (
        "Lịch sử quan hệ tín dụng của bạn còn ngắn hoặc đang có nhiều hợp đồng "
        "cùng lúc. Tất toán bớt hợp đồng cũ trước khi vay thêm sẽ tốt hơn."
    ),
    "thu_nhap": (
        "Thu nhập kê khai đang là yếu tố hạn chế. Bổ sung giấy tờ chứng minh thu "
        "nhập đầy đủ hơn (sao kê lương, hợp đồng lao động) sẽ giúp hồ sơ mạnh lên."
    ),
    "tham_nien": (
        "Số năm làm việc còn ngắn so với mặt bằng chung. Đây là yếu tố cần thời "
        "gian tích lũy, không xử lý ngay được."
    ),
    "xac_minh": (
        "Thu nhập của bạn chưa được xác minh. Hoàn tất bước xác minh thu nhập sẽ "
        "cải thiện đánh giá mà không cần thay đổi gì trong tài chính."
    ),
    "tuoi": (
        "Nhóm tuổi là yếu tố thống kê ngoài tầm kiểm soát của bạn. Bạn có thể bù "
        "lại bằng các yếu tố khác như lịch sử trả nợ tốt và tỷ lệ nợ thấp."
    ),
    "muc_dich": (
        "Mục đích vay bạn chọn thuộc nhóm được đánh giá thận trọng hơn. Nếu khoản "
        "vay phục vụ mục đích khác, hãy chọn đúng mục đích thực tế."
    ),
}


def _moc_can_dat(luat: LuatChamDiem, gia_tri: float | None) -> float | None:
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
    """Số cho người đọc: bỏ thập phân thừa, tách nghìn bằng dấu chấm kiểu Việt.

    740.0 -> '740', 50000000 -> '50.000.000', 0.35 -> '0.35'.
    """
    if float(x).is_integer():
        return f"{int(x):,}".replace(",", ".")
    return str(x)


def _moc_hien_thi(luat: LuatChamDiem, moc: float) -> tuple[str, str]:
    """(số để điền vào `{moc}`, số kèm đơn vị cho câu chung).

    Trường 0–1 nhân 100 và gắn '%': người vay nghĩ bằng phần trăm, không bằng 0,2.
    """
    if luat.truong.la_ty_le:
        so = _so_gon(moc * 100)
        return so, f"{so}%"
    so = _so_gon(moc)
    don_vi = luat.truong.don_vi
    if not don_vi:
        return so, so
    return so, f"{so}{don_vi}" if don_vi == "%" else f"{so} {don_vi}"


def _cau_chung(luat: LuatChamDiem, moc_kem_don_vi: str | None, gia_tri) -> str:
    """Câu gợi ý ghép tự động khi admin không nhập mẫu."""
    if luat.bang_diem is not None:
        return (
            f"“{luat.mo_ta}” hiện ở mức “{gia_tri}” chưa được đánh giá cao. Nếu tình "
            "trạng thực tế đã khác, hãy cập nhật hồ sơ và bổ sung giấy tờ chứng minh."
        )
    if luat.nghich_dao:
        return f"Giảm “{luat.mo_ta}” xuống mức {moc_kem_don_vi} trở xuống."
    return f"Nâng “{luat.mo_ta}” lên mức {moc_kem_don_vi} trở lên."


def _cau_goi_y_cho_luat(luat: LuatChamDiem, vet_luat: dict | None) -> str | None:
    """Câu gợi ý của một luật, điền mốc thật từ bậc thang của luật đó.

    Trả None khi không còn gì để khuyên: hồ sơ đã ở bậc cao nhất, hoặc không có
    vết luật để biết hồ sơ đang ở đâu.
    """
    # Luật tra bảng không có bậc số để phấn đấu — câu khuyên không cần mốc.
    if luat.bang_diem is not None:
        if luat.goi_y:
            return luat.goi_y.format(moc="")
        gia_tri = vet_luat.get("gia_tri") if vet_luat else None
        return _cau_chung(luat, None, gia_tri)

    if vet_luat is None:
        return None
    gia_tri = (
        vet_luat["gia_tri"] if isinstance(vet_luat["gia_tri"], (int, float)) else None
    )
    moc = _moc_can_dat(luat, gia_tri)
    if moc is None:
        return None

    so, so_kem_don_vi = _moc_hien_thi(luat, moc)
    if luat.goi_y:
        return luat.goi_y.format(moc=so)
    return _cau_chung(luat, so_kem_don_vi, gia_tri)


def sinh_dien_giai(ket_qua: dict, tom_tat: dict | None = None) -> dict:
    """Dịch kết quả chấm điểm sang thông điệp người vay đọc được.

    Nhận nguyên dict mà `BoDuDoan.du_doan()` trả về. Trả về ba phần: thông điệp
    tóm tắt, lý do chính, và danh sách gợi ý cải thiện.

    `tom_tat` là phần `tom_tat` của `explainer.giai_thich_mo_hinh()` — dùng để xếp
    thứ tự gợi ý theo mức ảnh hưởng thật của mô hình. Để `None` thì xếp theo số
    điểm luật bị mất, dành cho chỗ gọi không có sẵn dữ liệu SHAP.
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
        DIEN_GIAI_CHOT_CHAN.get(ma, f"Hồ sơ vi phạm quy định: {ma}") for ma in vi_pham
    ]

    # Thiếu dữ liệu là chuyện của hệ thống, không phải lỗi người vay: báo ở phần lý
    # do bất kể luật đó có lọt vào top gợi ý hay không.
    for t in vet:
        if t["thieu_du_lieu"]:
            ly_do.append(
                f"Chưa tra được thông tin cho tiêu chí “{t['mo_ta']}”, nên hệ thống "
                "chấm mức trung tính thay vì mức thật của bạn."
            )

    # ── Gợi ý cải thiện ───────────────────────────────────────────────────
    # Đọc bộ luật một lần cho cả lượt diễn giải. Vết luật có thể nhắc luật admin
    # đã xoá (trace lưu kèm quyết định cũ) — luật đó không còn bậc để nêu mốc, bỏ qua.
    bo_luat = {luat.ma: luat for luat in lay_bo_luat()}
    theo_ma = {t["ma"]: t for t in vet}

    if tom_tat:
        goi_y = _goi_y_theo_shap(tom_tat, bo_luat, theo_ma)
    else:
        goi_y = _goi_y_theo_luat(vet, bo_luat)

    return {
        "thong_diep": thong_diep,
        "ly_do_chinh": ly_do,
        "goi_y_cai_thien": goi_y,
    }


def _diem_hut(vet_luat: dict | None) -> int:
    return (vet_luat["toi_da"] - vet_luat["diem"]) if vet_luat else 0


def _goi_y_theo_shap(
    tom_tat: dict, bo_luat: dict[str, LuatChamDiem], theo_ma: dict[str, dict]
) -> list[str]:
    """Gợi ý xếp theo mức ảnh hưởng của mô hình, cao nhất trước.

    Duyệt các nhóm bất lợi mà `tom_tat_yeu_to()` đã sắp sẵn theo độ lớn đóng góp.
    Nhóm nào có luật đọc trường thuộc nhóm đó thì nêu mốc thật (nhiều luật cùng
    nhóm thì ưu tiên luật đang hụt nhiều điểm nhất); nhóm chỉ mô hình biết thì
    dùng câu mô tả riêng. Nhóm không thuộc cả hai (chưa có câu chữ) bị bỏ qua thay
    vì in ra mã kỹ thuật.
    """
    luat_theo_nhom: dict[str, list[LuatChamDiem]] = {}
    for luat in bo_luat.values():
        if luat.bat and luat.truong.nhom_shap:
            luat_theo_nhom.setdefault(luat.truong.nhom_shap, []).append(luat)

    goi_y: list[str] = []
    for nhom in tom_tat.get("bat_loi", []):
        ma_nhom = nhom["ma_nhom"]

        cau = None
        ung_vien = sorted(
            luat_theo_nhom.get(ma_nhom, []),
            key=lambda luat: _diem_hut(theo_ma.get(luat.ma)),
            reverse=True,
        )
        for luat in ung_vien:
            cau = _cau_goi_y_cho_luat(luat, theo_ma.get(luat.ma))
            if cau:
                break
        # Không có luật, hoặc hồ sơ đã đạt bậc cao nhất ở mọi luật của nhóm: lùi về
        # câu mô tả chung nếu có.
        if cau is None:
            cau = GOI_Y_NHOM_AI.get(ma_nhom)

        if cau and cau not in goi_y:
            goi_y.append(cau)
        if len(goi_y) == SO_GOI_Y_TOI_DA:
            break

    return goi_y


def _goi_y_theo_luat(vet: list[dict], bo_luat: dict[str, LuatChamDiem]) -> list[str]:
    """Gợi ý xếp theo số điểm luật bị mất — dùng khi không có dữ liệu SHAP."""
    goi_y: list[str] = []
    thieu_diem = sorted(
        (t for t in vet if t["diem"] < t["toi_da"] and not t["thieu_du_lieu"]),
        key=_diem_hut,
        reverse=True,
    )

    for t in thieu_diem:
        luat = bo_luat.get(t["ma"])
        if luat is None:
            continue
        cau = _cau_goi_y_cho_luat(luat, t)
        if cau and cau not in goi_y:
            goi_y.append(cau)
        if len(goi_y) == SO_GOI_Y_TOI_DA:
            break

    return goi_y
