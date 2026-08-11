"""Test schema thay đổi: so_cccd trong request, cic_score trong response."""

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


class TestCreditScoreResponseCicScore:
    """cic_score là optional trong response."""

    def test_response_co_cic_score(self):
        res = CreditScoreResponse(
            pd_probability=0.12,
            risk_score=65,
            evaluation_score=72.5,
            credit_grade="B",
            suggested_limit=80_000_000,
            decision="APPROVED",
            rejection_reason=None,
            model_version="11.0.0",
            cic_score=580,
        )
        assert res.cic_score == 580

    def test_response_khong_co_cic_score(self):
        res = CreditScoreResponse(
            pd_probability=0.12,
            risk_score=65,
            evaluation_score=72.5,
            credit_grade="B",
            suggested_limit=80_000_000,
            decision="APPROVED",
            rejection_reason=None,
            model_version="10.0.0",
        )
        assert res.cic_score is None
