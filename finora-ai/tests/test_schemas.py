"""Test schema thay đổi: so_cccd + int_rate/term_months trong request."""

import pytest
from app.schemas.credit import CreditScoreRequest, CreditScoreResponse


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


class TestCreditScoreResponseKhongCoCic:
    """Response không chứa cic_score — CIC chỉ dùng nội bộ trong model."""

    def test_response_khong_co_field_cic_score(self):
        res = CreditScoreResponse(
            pd_probability=0.12,
            risk_score=65,
            evaluation_score=72.5,
            credit_grade="B",
            suggested_limit=80_000_000,
            decision="APPROVED",
            rejection_reason=None,
            model_version="13.0.0",
        )
        assert not hasattr(res, "cic_score")
