"""Test bộ đặc trưng v14 — 47 features bao gồm CIC raw + Fineract."""

from app.ml.credit.features import (
    AGE_BINS,
    CIC_RAW_FEATURES,
    COLUMNS_WITH_MISSING,
    FEATURE_NAMES,
    FINERACT_FEATURES,
    MISSING_INDICATORS,
    NUMERIC_FEATURES,
    TARGET_ENCODED_FEATURES,
)


class TestFeatureNamesV14:
    def test_tong_so_feature_la_47(self):
        assert len(FEATURE_NAMES) == 47

    def test_khong_trung_lap(self):
        assert len(FEATURE_NAMES) == len(set(FEATURE_NAMES))

    def test_cic_score_trong_numeric(self):
        assert "cic_score" in NUMERIC_FEATURES

    def test_9_cic_raw_features(self):
        assert len(CIC_RAW_FEATURES) == 9
        for f in CIC_RAW_FEATURES:
            assert f in NUMERIC_FEATURES, f"{f} phải trong NUMERIC_FEATURES"
            assert f in COLUMNS_WITH_MISSING, f"{f} phải trong COLUMNS_WITH_MISSING"
            assert f"{f}_missing" in MISSING_INDICATORS

    def test_2_fineract_features(self):
        assert len(FINERACT_FEATURES) == 2
        for f in FINERACT_FEATURES:
            assert f in NUMERIC_FEATURES
            assert f in COLUMNS_WITH_MISSING
            assert f"{f}_missing" in MISSING_INDICATORS

    def test_derived_features_trong_numeric(self):
        for f in ["log_income", "loan_to_income", "effective_apr", "log_du_no", "ty_le_du_no_thu_nhap"]:
            assert f in NUMERIC_FEATURES

    def test_16_missing_indicators(self):
        assert len(COLUMNS_WITH_MISSING) == 16
        assert len(MISSING_INDICATORS) == 16

    def test_feature_names_structure(self):
        """FEATURE_NAMES = NUMERIC + TARGET_ENCODED + MISSING + AGE_BINS."""
        expected = NUMERIC_FEATURES + TARGET_ENCODED_FEATURES + MISSING_INDICATORS + AGE_BINS
        assert FEATURE_NAMES == expected


class TestEncodeFeatures:
    def test_encode_tao_effective_apr(self):
        """encode_features() tính effective_apr từ installment/loan_amnt/term_months."""
        import pandas as pd

        from app.ml.credit.features import encode_features

        df = pd.DataFrame([{
            "person_age": 30, "emp_length_years": 5, "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000, "dti": 15.0, "installment": 4_500_000,
            "cic_score": 580, "so_lan_tre_han": 0, "thang_tu_tre_gan_nhat": -1,
            "tong_du_no": 50_000_000, "du_no_the_tin_dung": 5_000_000,
            "ty_le_su_dung_the": 25.0, "so_lan_tra_cuu": 1, "so_hop_dong_dang_co": 3,
            "so_thang_quan_he": 48, "nhom_no_cao_nhat": 1,
            "int_rate": 12.0, "term_months": 12,
            "home_ownership": "MORTGAGE", "purpose_cat": "DEBT_CONSOLIDATION",
            "verification_status": "Verified", "interest_method": "DECLINING_BALANCE",
        }])
        result = encode_features(df)
        assert "effective_apr" in result.columns
        assert "log_du_no" in result.columns
        assert "ty_le_du_no_thu_nhap" in result.columns
        assert result["effective_apr"].iloc[0] > 0
        assert result["log_du_no"].iloc[0] > 0
        assert result["ty_le_du_no_thu_nhap"].iloc[0] >= 0
