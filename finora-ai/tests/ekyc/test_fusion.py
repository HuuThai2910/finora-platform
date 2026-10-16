"""Ma trận quyết định ``/document`` (hợp đồng EKYC-002 mục 2.1 bước 10)."""

from dataclasses import replace
from datetime import date

import pytest

from app.ml.ekyc import fusion, mrz, qr_reader
from app.ml.ekyc.fusion import AiFields, DocumentSignals, PortraitSignal, QrEvidence
from app.ml.ekyc.quality import ISSUE_BLUR, ISSUE_GLARE, ISSUE_TOO_DARK, SideQuality
from app.ml.ekyc.thresholds import EkycSettings

CFG = EkycSettings()
GOOD = SideQuality(True, 300.0, 0.001, 0.6, True, ())


def bad_quality(issue: str) -> SideQuality:
    return SideQuality(True, 20.0, 0.05, 0.1, False, (issue,))


def make_mrz(
    id12: str, dob: str, sex: str, expiry: str, name: str = "NGUYEN<<VAN<A"
) -> tuple[str, str, str]:
    """Dựng MRZ hợp lệ (tự tính check digit) cho dữ liệu tổng hợp."""
    cd = mrz.check_digit
    doc = id12[3:]
    line1 = "IDVNM" + doc + cd(doc) + id12 + "<<"
    line1 += cd(line1[15:29])
    head = dob + cd(dob) + sex + expiry + cd(expiry) + "VNM" + "<" * 11
    line2 = head + cd(line1[5:30] + head[0:7] + head[8:15] + head[18:29])
    return line1, line2, (name + "<" * 30)[:30]


def make_qr(
    id12: str = "001095012345",
    name: str = "Nguyễn Văn A",
    dob: str = "15031995",
    gender: str = "Nam",
) -> qr_reader.QrPayload:
    return qr_reader.parse_qr_text(
        f"{id12}||{name}|{dob}|{gender}|Số 1 Hàng Bạc, Hà Nội|01072021"
    )


def mrz_read(lines: tuple[str, str, str], engine: str = "TESSERACT") -> mrz.MrzRead:
    return mrz.best_of([lines], engine)


MRZ_OK_LINES = make_mrz("001095012345", "950315", "M", "350315")
MRZ_OK = mrz_read(MRZ_OK_LINES)
MRZ_BAD = mrz_read((MRZ_OK_LINES[0], MRZ_OK_LINES[1][:-1] + "9", MRZ_OK_LINES[2]))
MRZ_NONE = mrz.MrzRead(found=False, result=None, engine=None)
QR_FRONT = QrEvidence(server_found=True, server_payload=make_qr(), server_side="FRONT")
QR_BACK = QrEvidence(server_found=True, server_payload=make_qr(), server_side="BACK")
QR_NONE = QrEvidence()


def signals(**overrides) -> DocumentSignals:
    base = DocumentSignals(
        front_quality=GOOD,
        back_quality=GOOD,
        portrait=PortraitSignal(found=True),
        qr=QR_FRONT,
        mrz=MRZ_OK,
    )
    return replace(base, **overrides)


def retakes(result: fusion.FusionResult) -> set[tuple[str, str]]:
    return {(item["side"], item["reason"]) for item in result.retake}


CASES = [
    # (mã, tín hiệu, quyết định, retake kỳ vọng, lý do bắt buộc có)
    ("thẻ 2021 chuẩn", {}, "AUTO_OK", set(), {"QR_MRZ_AGREE", "FRONT_BACK_LINKED"}),
    (
        "chân dung hỏng",
        {"portrait": PortraitSignal(found=False)},
        "RETAKE",
        {("FRONT", "PORTRAIT_UNUSABLE")},
        set(),
    ),
    (
        "chân dung hỏng, hết lượt vẫn RETAKE",
        {"portrait": PortraitSignal(found=False), "retake_count": 9},
        "RETAKE",
        {("FRONT", "PORTRAIT_UNUSABLE")},
        set(),
    ),
    (
        "chụp nhầm mặt",
        {"portrait": PortraitSignal(found=False, wrong_side=True)},
        "RETAKE",
        {("FRONT", "WRONG_SIDE")},
        set(),
    ),
    (
        "mặt trước mờ, không đọc được QR mặt trước",
        {
            "front_quality": bad_quality(ISSUE_BLUR),
            "qr": QR_NONE,
            "ocr_front_match": True,
        },
        "RETAKE",
        {("FRONT", "BLUR")},
        set(),
    ),
    (
        "mặt trước mờ nhưng QR mặt trước đọc được",
        {"front_quality": bad_quality(ISSUE_BLUR)},
        "NEEDS_REVIEW",
        set(),
        {"QUALITY_LOW"},
    ),
    (
        "mặt sau loá, không có MRZ",
        {"back_quality": bad_quality(ISSUE_GLARE), "mrz": MRZ_NONE},
        "RETAKE",
        {("BACK", "GLARE")},
        set(),
    ),
    (
        "mặt sau tối, MRZ hỏng",
        {"back_quality": bad_quality(ISSUE_TOO_DARK), "mrz": MRZ_BAD},
        "RETAKE",
        {("BACK", "TOO_DARK"), ("BACK", "MRZ_UNREADABLE")},
        set(),
    ),
    (
        "mặt sau mờ nhưng MRZ hợp lệ",
        {"back_quality": bad_quality(ISSUE_BLUR)},
        "NEEDS_REVIEW",
        set(),
        {"QUALITY_LOW"},
    ),
    (
        "MRZ thấy nhưng sai check digit",
        {"mrz": MRZ_BAD},
        "RETAKE",
        {("BACK", "MRZ_UNREADABLE")},
        set(),
    ),
    (
        "QR và MRZ lệch ngày sinh",
        {"qr": replace(QR_FRONT, server_payload=make_qr(dob="16031995"))},
        "RETAKE",
        {("FRONT", "SOURCES_DISAGREE"), ("BACK", "SOURCES_DISAGREE")},
        set(),
    ),
    (
        "QR và MRZ lệch, hết lượt chụp lại",
        {
            "qr": replace(QR_FRONT, server_payload=make_qr(dob="16031995")),
            "retake_count": 2,
        },
        "NEEDS_REVIEW",
        set(),
        {"SOURCES_DISAGREE_PERSISTENT"},
    ),
    (
        "không có số định danh từ nguồn nào",
        {"qr": QR_NONE, "mrz": MRZ_NONE},
        "RETAKE",
        {("FRONT", "NO_DATA"), ("BACK", "NO_DATA")},
        set(),
    ),
    (
        "không có số định danh, hết lượt vẫn RETAKE",
        {"qr": QR_NONE, "mrz": MRZ_NONE, "retake_count": 5},
        "RETAKE",
        {("FRONT", "NO_DATA"), ("BACK", "NO_DATA")},
        set(),
    ),
    (
        "MRZ hỏng, hết lượt → duyệt tay",
        {"mrz": MRZ_BAD, "retake_count": 2},
        "NEEDS_REVIEW",
        set(),
        {"MRZ_MISSING", "EXPIRY_UNREADABLE", "NOT_LINKED"},
    ),
    (
        "ảnh từ thư viện",
        {"front_source": "GALLERY"},
        "NEEDS_REVIEW",
        set(),
        {"GALLERY_SOURCE"},
    ),
    (
        "thẻ 2024 chưa gắn hai mặt",
        {"qr": QR_BACK},
        "NEEDS_REVIEW",
        set(),
        {"NOT_LINKED"},
    ),
    (
        "thẻ 2024 gắn bằng số in mặt trước (OCR)",
        {"qr": QR_BACK, "ocr_front_match": True},
        "AUTO_OK",
        set(),
        {"QR_MRZ_AGREE", "FRONT_BACK_LINKED"},
    ),
    (
        "thẻ 2024 gắn bằng số in mặt trước (AI)",
        {"qr": QR_BACK, "ai": AiFields(called=True, front_id="001095012345")},
        "AUTO_OK",
        set(),
        {"FRONT_BACK_LINKED"},
    ),
    (
        "QR chỉ do client gửi thì không gắn được hai mặt",
        {
            "qr": QrEvidence(
                client_text_present=True, client_payload=make_qr(), client_side="FRONT"
            )
        },
        "NEEDS_REVIEW",
        set(),
        {"QR_MRZ_AGREE", "NOT_LINKED"},
    ),
    (
        "QR client lệch QR server",
        {
            "qr": replace(
                QR_FRONT,
                client_text_present=True,
                client_payload=make_qr(id12="001095099999"),
                client_side="FRONT",
            )
        },
        "NEEDS_REVIEW",
        set(),
        {"SOFT_FLAGS"},
    ),
]


@pytest.mark.parametrize(
    ("name", "overrides", "decision", "expected_retake", "required_reasons"),
    CASES,
    ids=[case[0] for case in CASES],
)
def test_ma_tran_quyet_dinh(
    name, overrides, decision, expected_retake, required_reasons
):
    result = fusion.fuse(signals(**overrides), CFG)
    assert result.decision == decision, (name, result.reasons)
    assert retakes(result) == expected_retake
    assert required_reasons <= set(result.reasons), result.reasons


def test_auto_ok_tra_du_truong_va_nguon():
    result = fusion.fuse(signals(ocr_front_match=None), CFG)
    fields = result.fields
    assert result.card_type == "CCCD_CHIP_2021"
    assert fields["id_number"] == {"value": "001095012345", "sources": ["QR", "MRZ"]}
    assert fields["full_name"] == {"value": "NGUYỄN VĂN A", "sources": ["QR"]}
    assert fields["date_of_birth"] == {"value": "15/03/1995", "sources": ["QR", "MRZ"]}
    assert fields["gender"] == {"value": "Nam", "sources": ["QR", "MRZ"]}
    assert fields["issue_date"] == {"value": "01/07/2021", "sources": ["QR"]}
    assert fields["expiry_date"] == {
        "value": "15/03/2035",
        "sources": ["MRZ"],
        "no_expiry": False,
    }
    assert fields["place_of_origin"] == {"value": None, "sources": []}
    assert result.checks["qr"] == {
        "found": True,
        "by": "SERVER",
        "side": "FRONT",
        "valid": True,
    }
    assert (
        result.checks["mrz"]["valid"] and result.checks["mrz"]["engine"] == "TESSERACT"
    )
    assert result.checks["id_rules_ok"] and result.checks["sources_agree"]
    assert result.warnings == []


def test_mau_thuan_het_luot_lay_gia_tri_mrz():
    qr = replace(QR_FRONT, server_payload=make_qr(dob="16031995"))
    result = fusion.fuse(signals(qr=qr, retake_count=2), CFG)
    assert result.fields["date_of_birth"] == {"value": "15/03/1995", "sources": ["MRZ"]}
    assert not result.checks["sources_agree"]


def test_ocr_mat_truoc_duoc_ghi_vao_nguon_so_dinh_danh():
    result = fusion.fuse(signals(qr=QR_BACK, ocr_front_match=True), CFG)
    assert result.fields["id_number"]["sources"] == ["QR", "MRZ", "OCR"]
    assert result.card_type == "CAN_CUOC_2024"


def test_co_mem_ma_tinh_la():
    lines = make_mrz("003095012345", "950315", "M", "350315")
    qr = replace(QR_FRONT, server_payload=make_qr(id12="003095012345"))
    result = fusion.fuse(signals(qr=qr, mrz=mrz_read(lines)), CFG)
    assert result.decision == "NEEDS_REVIEW"
    assert "ID_PROVINCE_UNKNOWN" in result.warnings
    assert result.checks["id_rules_ok"]  # cờ mềm, luật cấu trúc vẫn khớp


def test_lech_cau_truc_so_voi_ngay_sinh():
    lines = make_mrz("001095012345", "960315", "M", "360315")
    qr = replace(QR_FRONT, server_payload=make_qr(dob="15031996"))
    result = fusion.fuse(signals(qr=qr, mrz=mrz_read(lines)), CFG)
    assert result.decision == "NEEDS_REVIEW"
    assert "ID_STRUCTURE_MISMATCH" in result.warnings
    assert not result.checks["id_rules_ok"]
    assert result.retake == []  # lệch cấu trúc không bao giờ bắt chụp lại


def test_ten_qr_lech_ten_mrz():
    lines = make_mrz("001095012345", "950315", "M", "350315", name="TRAN<<THI<BICH")
    result = fusion.fuse(signals(mrz=mrz_read(lines)), CFG)
    assert result.decision == "NEEDS_REVIEW"
    assert "FULL_NAME_MISMATCH" in result.warnings


def test_the_khong_thoi_han_di_duyet_tay():
    line1, _, line3 = MRZ_OK_LINES
    cd = mrz.check_digit
    head = "9503157M<<<<<<0VNM" + "<" * 11
    line2 = head + cd(line1[5:30] + head[0:7] + head[8:15] + head[18:29])
    result = fusion.fuse(signals(mrz=mrz_read((line1, line2, line3))), CFG)
    assert result.decision == "NEEDS_REVIEW"
    assert "NO_EXPIRY_UNVERIFIED" in result.reasons
    assert result.fields["expiry_date"] == {
        "value": None,
        "sources": ["MRZ"],
        "no_expiry": True,
    }


def test_chi_co_ai_the_ma_vach():
    ai = AiFields(
        called=True,
        card_title="CĂN CƯỚC CÔNG DÂN",
        id_number="001095012345",
        full_name="NGUYỄN VĂN A",
        date_of_birth=date(1995, 3, 15),
        gender="Nam",
        address="Hà Nội",
        expiry_date=date(2035, 3, 15),
    )
    result = fusion.fuse(signals(qr=QR_NONE, mrz=MRZ_NONE, ai=ai), CFG)
    assert result.decision == "NEEDS_REVIEW"
    assert result.card_type == "CCCD_BARCODE"
    assert {"AI_ONLY", "CARD_BARCODE"} <= set(result.reasons)
    assert result.fields["id_number"] == {"value": "001095012345", "sources": ["AI"]}
    assert result.fields["full_name"]["sources"] == ["AI"]


def test_cmnd_khong_bi_bat_chup_lai_mai():
    ai = AiFields(called=True, card_title="CHỨNG MINH NHÂN DÂN", id_number="123456789")
    result = fusion.fuse(signals(qr=QR_NONE, mrz=MRZ_NONE, ai=ai, retake_count=0), CFG)
    assert result.card_type == "CMND"
    assert result.decision == "NEEDS_REVIEW"
    assert result.fields["id_number"]["value"] is None  # chỉ trả số 12 chữ số
    assert "CARD_CMND" in result.reasons


def test_ten_ai_chi_nhan_khi_khop_ten_mrz():
    qr = replace(QR_FRONT, server_payload=make_qr(name=""))
    accepted = fusion.fuse(
        signals(qr=qr, ai=AiFields(called=True, full_name="NGUYỄN VĂN A")), CFG
    )
    assert accepted.fields["full_name"] == {"value": "NGUYỄN VĂN A", "sources": ["AI"]}
    rejected = fusion.fuse(
        signals(qr=qr, ai=AiFields(called=True, full_name="LÊ THỊ B")), CFG
    )
    assert rejected.fields["full_name"] == {"value": "NGUYEN VAN A", "sources": ["MRZ"]}


def test_truong_tuy_chon_chi_tra_khi_bat_co():
    ai = AiFields(called=True, place_of_origin="Gò Công, Tiền Giang")
    off = fusion.fuse(signals(ai=ai), CFG)
    assert off.fields["place_of_origin"] == {"value": None, "sources": []}
    on = fusion.fuse(signals(ai=ai), replace(CFG, optional_fields=True))
    assert on.fields["place_of_origin"] == {
        "value": "Gò Công, Tiền Giang",
        "sources": ["AI"],
    }


class TestSingleDeterministic:
    AI_AGREES = AiFields(
        called=True,
        id_number="001095012345",
        date_of_birth=date(1995, 3, 15),
        gender="Nam",
        front_id="001095012345",
        full_name="NGUYỄN VĂN A",
    )

    def test_mac_dinh_mot_nguon_tat_dinh_di_duyet_tay(self):
        result = fusion.fuse(signals(qr=QR_NONE, ai=self.AI_AGREES), CFG)
        assert result.decision == "NEEDS_REVIEW"
        assert {"SINGLE_SOURCE", "QR_MISSING"} <= set(result.reasons)

    def test_bat_co_va_ai_doc_doc_lap_khop_thi_auto_ok(self):
        cfg = replace(CFG, allow_single_deterministic=True)
        result = fusion.fuse(signals(qr=QR_NONE, ai=self.AI_AGREES), cfg)
        assert result.decision == "AUTO_OK", result.reasons
        assert "SINGLE_SOURCE_CONFIRMED" in result.reasons
        assert result.fields["id_number"]["sources"] == ["MRZ", "AI"]

    def test_bat_co_nhung_ai_lech_ngay_sinh(self):
        cfg = replace(CFG, allow_single_deterministic=True)
        ai = replace(self.AI_AGREES, date_of_birth=date(1995, 3, 16))
        result = fusion.fuse(signals(qr=QR_NONE, ai=ai), cfg)
        assert result.decision == "NEEDS_REVIEW"

    def test_chi_co_qr_mat_truoc_khong_gan_duoc_mat_sau(self):
        cfg = replace(CFG, allow_single_deterministic=True)
        result = fusion.fuse(signals(mrz=MRZ_NONE, ai=self.AI_AGREES), cfg)
        assert result.decision == "NEEDS_REVIEW"
        assert "NOT_LINKED" in result.reasons


def test_han_the_ai_lech_moc_tuoi_la_co_mem():
    ai = AiFields(called=True, expiry_date=date(2034, 3, 15))
    qr_only = signals(mrz=MRZ_NONE, ai=ai, retake_count=2)
    result = fusion.fuse(qr_only, CFG)
    assert "EXPIRY_MILESTONE_MISMATCH" in result.warnings
    assert result.fields["expiry_date"]["sources"] == ["AI"]
