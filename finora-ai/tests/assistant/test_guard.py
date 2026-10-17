"""Test lớp chặn của trợ lý: PII, số liệu, trích dẫn pháp lý, khuyến nghị đầu tư.

Đây là lớp cuối trước khi dữ liệu rời máy chủ hoặc câu trả lời tới người dùng, nên test cả hai chiều:
bắt đúng thứ cần bắt, và KHÔNG báo động giả với cách viết số tiền/ngày/mã quen thuộc của FINORA.
"""

from decimal import Decimal

import pytest

from app.schemas.assistant import Citation
from app.services.assistant.guard import (
    AN,
    OutboundBlocked,
    assert_outbound_clean,
    bo_dau,
    check_reply,
    detect_advice_request,
    numbers_in,
    scrub_user_text,
)

CAN_CU_ND94 = Citation(
    id="LAW-P2P-2025:Điều 11 khoản 1 điểm c",
    kind="LUAT",
    label="Nghị định 94/2025/NĐ-CP, Điều 11 khoản 1 điểm c",
    url="https://vbpl.vn/nd-94-2025",
    checked_at="2026-10-10",
)
CAN_CU_CHINH_SACH = Citation(
    id="CS-THAM-DINH-01", kind="CHINH_SACH", label="Chính sách thẩm định FINORA", url=None, checked_at="2026-10-15"
)


class TestScrubUserText:
    @pytest.mark.parametrize(
        "cau, so_bi_che",
        [
            ("CCCD của tôi là 079204001234 nhé", "079204001234"),
            ("CMND cũ 123456789", "123456789"),
            ("gọi 0912345678 giúp", "0912345678"),
            ("SĐT 0912345678, cảm ơn", "0912345678"),
            ("CCCD là 079204001234.", "079204001234"),
            ("số 0912 345 678", "0912 345 678"),
            ("sđt +84912345678", "+84912345678"),
            ("mail an.nguyen@gmail.com", "an.nguyen@gmail.com"),
            ("STK 0071000123456 Vietcombank", "0071000123456"),
            ("số tài khoản: 1903 5555 1234 5678", "1903 5555 1234 5678"),
            ("so tai khoan 19035555123456", "19035555123456"),
        ],
    )
    def test_che_dinh_danh(self, cau, so_bi_che):
        ket_qua = scrub_user_text(cau)
        assert so_bi_che not in ket_qua
        assert AN in ket_qua

    @pytest.mark.parametrize(
        "cau",
        [
            "Hồ sơ LA-2026-000123 sao chưa giải ngân?",
            "Hồ sơ LA-0F9C3C1E2A4B5C6D7E8F bị từ chối vì sao?",
            "Kỳ tới trả 2.350.000 đ phải không?",
            "Vay 30 triệu 12 tháng mỗi tháng trả bao nhiêu?",
            "Số dư tài khoản ví 12.350.000 đ",
            "Hạn ký 25/10/2026 lúc 14:30",
            "Vay 300000000 đ được không?",
            "Mã giao dịch 20261015123456",
        ],
    )
    def test_khong_che_so_lieu_nghiep_vu(self, cau):
        assert scrub_user_text(cau) == cau


class TestAssertOutboundClean:
    def test_cho_qua_khi_sach(self):
        assert_outbound_clean([
            {"role": "system", "content": "Bạn là Trợ lý FINORA."},
            {"role": "user", "content": 'DỮ LIỆU {"so_tien_de_nghi":"30.000.000 đ","ma_ho_so":"LA-2026-000123"}'},
        ])

    def test_chan_so_dien_thoai_dau_dong_trong_du_kien(self):
        with pytest.raises(OutboundBlocked) as loi:
            assert_outbound_clean([{"role": "user", "content": "Ghi chú:\n0912345678"}])
        assert loi.value.kinds == ("SDT",)
        # Ngoại lệ chỉ mang tên loại, không mang giá trị: log lỗi không lộ lại số điện thoại.
        assert "0912345678" not in str(loi.value)

    def test_quet_ca_tham_so_tool_call(self):
        tin = [{"role": "assistant", "content": None, "tool_calls": [
            {"id": "c1", "type": "function", "function": {"name": "x", "arguments": '{"email": "a@b.vn"}'}}]}]
        with pytest.raises(OutboundBlocked) as loi:
            assert_outbound_clean(tin)
        assert loi.value.kinds == ("EMAIL",)


class TestNumbersIn:
    @pytest.mark.parametrize(
        "cau, mong_doi",
        [
            ("Kỳ tới trả 2.350.000 đ", {Decimal(2350000)}),
            ("khoảng 2,35 triệu", {Decimal(2350000)}),
            ("lãi 12,5%/năm", {Decimal("12.5")}),
            ("hạn mức 1,2 tỷ", {Decimal(1200000000)}),
            ("2,350,000 VND", {Decimal(2350000)}),
            ("phí 500k", {Decimal(500000)}),
            ("kỳ hạn 36 tháng", {Decimal(36)}),
            ("lãi suất 14.5%", {Decimal("14.5")}),
        ],
    )
    def test_chuan_hoa_dinh_dang_viet_nam(self, cau, mong_doi):
        assert numbers_in(cau) == mong_doi

    @pytest.mark.parametrize(
        "cau",
        [
            "Hạn ký 25/10/2026",
            "ngày 2026-10-25T14:30:00+07:00",
            "lúc 14:30",
            "vay 12 tháng, kỳ 3",
            "hồ sơ LA-2026-000123",
            "khoản #12, luật R1, CREDIT_POLICY_V1",
            "Nghị định 94/2025/NĐ-CP",
        ],
    )
    def test_bo_qua_ngay_ma_va_so_nho(self, cau):
        assert numbers_in(cau) == set()


class TestCheckReply:
    def _kiem(self, cau, *, du_kien=("",), can_cu=(), nha_dau_tu=False):
        return check_reply(cau, fact_texts=list(du_kien), citations=list(can_cu), investor=nha_dau_tu)

    def test_cau_dung_so_lieu_thi_hop_le(self):
        ket_qua = self._kiem(
            "Đã gọi được 12 triệu trên 30.000.000 đ, lãi 14,5%/năm.",
            du_kien=['{"da_goi": "12.000.000 đ", "can_goi": "30.000.000 đ", "lai_suat": "14,5%"}'],
        )
        assert ket_qua.ok
        assert ket_qua.reasons == ()

    def test_so_tien_khong_co_trong_du_kien(self):
        ket_qua = self._kiem("Bạn còn thiếu 18.000.000 đ.", du_kien=['{"da_goi": "12.000.000 đ"}'])
        assert ket_qua.reasons == ("SO_LIEU",)

    def test_phan_tram_bia(self):
        assert self._kiem("Lãi suất là 16%.", du_kien=['{"lai_suat": "14,5%"}']).reasons == ("SO_LIEU",)

    def test_nam_va_so_dem_khong_bi_kiem(self):
        assert self._kiem("Năm 2026 bạn có 3 hồ sơ và 36 kỳ.", du_kien=["{}"]).ok

    def test_so_nguoi_dung_tu_go_duoc_coi_la_du_kien(self):
        ket_qua = self._kiem("Khoản 30 triệu bạn hỏi cần hồ sơ mới.", du_kien=["Vay 30 triệu 12 tháng?"])
        assert ket_qua.ok

    def test_lo_pii(self):
        assert "PII" in self._kiem("Liên hệ 0912345678 nhé.").reasons

    def test_trich_dan_khop_can_cu(self):
        ket_qua = self._kiem(
            "Theo Nghị định 94/2025/NĐ-CP, Điều 11, hợp đồng không quá 02 năm.", can_cu=[CAN_CU_ND94]
        )
        assert ket_qua.ok

    def test_trich_dan_sai_dieu(self):
        assert self._kiem("Theo Điều 12 thì…", can_cu=[CAN_CU_ND94]).reasons == ("TRICH_DAN",)

    def test_trich_dan_khi_chi_co_can_cu_chinh_sach(self):
        ket_qua = self._kiem("Thông tư của NHNN quy định…", can_cu=[CAN_CU_CHINH_SACH])
        assert ket_qua.reasons == ("TRICH_DAN",)

    def test_luat_cham_diem_cua_finora_khong_phai_trich_dan(self):
        assert self._kiem("Luật chấm điểm của FINORA dùng bộ luật nội bộ.").ok

    def test_bo_luat_dan_su_viet_thuong_van_bi_bat(self):
        assert self._kiem("Theo bộ luật dân sự thì lãi tối đa…").reasons == ("TRICH_DAN",)

    def test_luat_viet_hoa_ten_rieng_bi_bat(self):
        assert self._kiem("Luật Đất đai không áp dụng ở đây.").reasons == ("TRICH_DAN",)

    def test_quyet_dinh_chua_xac_minh_khong_bi_bat(self):
        # Đoạn CHUA_XAC_MINH của Task 8 cố ý nêu số quyết định này để nói nguồn chưa kiểm.
        assert self._kiem("Mức 100 triệu được nhắc ở Quyết định 2866/QĐ-NHNN, chưa xác minh.",
                          du_kien=["100 triệu"]).ok

    def test_khuyen_nghi_voi_nha_dau_tu(self):
        assert self._kiem("Bạn nên đầu tư khoản hạng A.", nha_dau_tu=True).reasons == ("KHUYEN_NGHI",)

    def test_cau_mien_tru_khong_bi_tinh_la_khuyen_nghi(self):
        cau = "Mình không đưa ra khuyến nghị đầu tư. Việc có nên đầu tư hay không do bạn quyết định."
        assert self._kiem(cau, nha_dau_tu=True).ok

    def test_khong_kiem_khuyen_nghi_voi_nguoi_vay(self):
        assert self._kiem("Bạn nên mua bảo hiểm khoản vay.", nha_dau_tu=False).ok

    def test_nhieu_vi_pham_cung_luc(self):
        ket_qua = self._kiem("Gọi 0912345678, lãi chắc chắn có lãi 20%.", du_kien=["{}"], nha_dau_tu=True)
        assert ket_qua.reasons == ("PII", "SO_LIEU", "KHUYEN_NGHI")
        assert not ket_qua.ok


class TestDetectAdviceRequest:
    @pytest.mark.parametrize(
        "cau",
        [
            "Nên đầu tư khoản nào an toàn nhất?",
            "nen dau tu khoan nao",
            "Khoản nào lời nhất?",
            "Bạn khuyên tôi mua Note nào?",
            "Có nên bán Note này không?",
            "Đầu tư khoản nào chắc chắn có lãi?",
            "dau tu vao khoan nao thi tot",
        ],
    )
    def test_nhan_ra_cau_xin_khuyen_nghi(self, cau):
        assert detect_advice_request(cau)

    @pytest.mark.parametrize(
        "cau",
        [
            "Vì sao lệnh mua 96% chưa khớp?",
            "Tiền của tôi đang bị giữ ở đâu?",
            "Commitment khác Note thế nào?",
            "Tôi nên bán Note thế nào?",
            "Có khuyến mãi gì không?",
        ],
    )
    def test_cau_hoi_binh_thuong(self, cau):
        assert not detect_advice_request(cau)


def test_bo_dau():
    assert bo_dau("  Đã DUYỆT   sao chưa nhận tiền ") == "da duyet sao chua nhan tien"


class TestKhongBaoDongGiaBoSung:
    """Lỗi tìm thấy khi rà code của brief: báo động giả làm hỏng câu trả lời đúng.

    Mỗi test chạy đỏ trên code nguyên văn của brief.
    """

    def test_so_tien_9_chu_so_trong_tham_so_tool_call_khong_bi_coi_la_cmnd(self):
        # mo_phong_khoan_vay(amount=100000000): tham số kiểu số của LLM được gửi lại ở vòng sau;
        # brief quét chuỗi JSON thô nên "100000000" (9 số) bị coi là CMND và cả lượt bị chặn.
        tham_so = '{"productId": 501, "amount": 100000000, "termMonths": 12}'
        assert_outbound_clean([{"role": "assistant", "content": "", "tool_calls": [
            {"id": "c1", "type": "function", "function": {"name": "mo_phong_khoan_vay", "arguments": tham_so}}]}])

    def test_chuoi_dinh_danh_trong_tham_so_tool_call_van_bi_chan(self):
        tham_so = '{"applicationNumber": "079204001234"}'
        with pytest.raises(OutboundBlocked) as loi:
            assert_outbound_clean([{"role": "assistant", "content": "", "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "x", "arguments": tham_so}}]}])
        assert loi.value.kinds == ("CCCD",)

    def test_tham_so_tool_call_khong_phai_json_van_duoc_quet(self):
        with pytest.raises(OutboundBlocked):
            assert_outbound_clean([{"role": "assistant", "content": "", "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "x", "arguments": "goi 0912345678"}}]}])

    def test_so_tien_bat_dau_bang_84_khong_bi_coi_la_so_dien_thoai(self):
        # "84.350.000.000 đ" khớp khuôn +84/84 + 9 số của SĐT; có đơn vị tiền theo sau thì là số tiền.
        cau = "Tổng giải ngân 84.350.000.000 đ"
        assert scrub_user_text(cau) == cau
        assert_outbound_clean([{"role": "user", "content": '{"tong_giai_ngan":"84.350.000.000 đ"}'}])
        assert check_reply(cau + ".", fact_texts=['"84.350.000.000 đ"'], citations=[], investor=False).ok

    @pytest.mark.parametrize(
        "cau",
        [
            "Luật R1 trừ 10 điểm.",
            "Luật CAPACITY_INSTALLMENT_BURDEN trừ điểm vì tỷ lệ trả nợ cao.",
            "Bộ luật FINORA gồm các luật chấm điểm nội bộ.",
        ],
    )
    def test_ten_luat_cham_diem_noi_bo_khong_phai_trich_dan(self, cau):
        assert check_reply(cau, fact_texts=["10"], citations=[], investor=False).ok

    @pytest.mark.parametrize("cau", ["Luật TCTD quy định…", "Luật 2010 về…", "Luật Các tổ chức tín dụng…"])
    def test_ten_luat_that_van_bi_bat(self, cau):
        assert check_reply(cau, fact_texts=[""], citations=[], investor=False).reasons == ("TRICH_DAN",)
