"""Test schema thay đổi: so_cccd + int_rate/term_months trong request."""

import pytest

from app.schemas.credit import CreditScoreRequest


class TestCreditScoreRequestSoCccd:
    """so_cccd là optional, backward compatible."""

    def test_request_khong_co_cccd_van_hop_le(self):
        """Request cũ (không có so_cccd) vẫn validate thành công."""
        req = CreditScoreRequest(
            annual_inc=300_000_000,
            loan_amnt=50_000_000,
            purpose="debt_consolidation",
            home_ownership="MORTGAGE",
        )
        assert req.so_cccd is None

    def test_request_co_cccd_12_ky_tu(self):
        """CCCD 12 ký tự → lưu đúng."""
        req = CreditScoreRequest(
            annual_inc=300_000_000,
            loan_amnt=50_000_000,
            purpose="debt_consolidation",
            home_ownership="MORTGAGE",
            so_cccd="012345678901",
        )
        assert req.so_cccd == "012345678901"


class TestCreditScoreRequestFineract:
    """int_rate và term_months là optional, backward compatible."""

    def test_request_khong_co_fineract_van_hop_le(self):
        req = CreditScoreRequest(
            annual_inc=300_000_000,
            loan_amnt=50_000_000,
            purpose="debt_consolidation",
            home_ownership="MORTGAGE",
        )
        assert req.int_rate is None
        assert req.term_months is None

    def test_request_co_fineract(self):
        req = CreditScoreRequest(
            annual_inc=300_000_000,
            loan_amnt=50_000_000,
            purpose="debt_consolidation",
            home_ownership="MORTGAGE",
            int_rate=12.0,
            term_months=12,
        )
        assert req.int_rate == 12.0
        assert req.term_months == 12

    def test_term_months_toi_da_24(self):
        """NĐ 94/2025: kỳ hạn vay ngang hàng tối đa 24 tháng."""
        import pydantic
        with pytest.raises(pydantic.ValidationError):
            CreditScoreRequest(
                annual_inc=300_000_000,
                loan_amnt=50_000_000,
                purpose="debt_consolidation",
                home_ownership="MORTGAGE",
                term_months=25,
            )


class TestRuleTraceItem:
    """Vết luật không còn nhóm 5C; ghi trường đã đọc và trọng số để giải trình."""

    def test_nhan_vet_luat_moi(self):
        from app.schemas.credit import RuleTraceItem

        muc = RuleTraceItem(
            ma="AGE_BRACKET", mo_ta="Tuổi", truong="person_age", gia_tri=30.0,
            diem=20, toi_da=20, trong_so=1.5, thieu_du_lieu=False,
        )
        assert muc.truong == "person_age" and muc.trong_so == 1.5

    def test_khong_con_nhom_5c(self):
        from app.schemas.credit import RuleTraceItem

        assert "nhom_5c" not in RuleTraceItem.model_fields
        assert {"truong", "trong_so"} <= set(RuleTraceItem.model_fields)
