"""Test feature list sau khi thêm cic_score."""

from app.ml.features import (
    COLUMNS_WITH_MISSING,
    FEATURE_NAMES,
    MISSING_INDICATORS,
    NUMERIC_FEATURES,
)


class TestCicScoreInFeatures:
    def test_cic_score_trong_numeric_features(self):
        assert "cic_score" in NUMERIC_FEATURES

    def test_cic_score_trong_columns_with_missing(self):
        assert "cic_score" in COLUMNS_WITH_MISSING

    def test_cic_score_missing_trong_missing_indicators(self):
        assert "cic_score_missing" in MISSING_INDICATORS

    def test_cic_score_trong_feature_names(self):
        assert "cic_score" in FEATURE_NAMES
        assert "cic_score_missing" in FEATURE_NAMES

    def test_khong_co_cic_score_trung_lap(self):
        """Không có feature nào bị trùng."""
        assert len(FEATURE_NAMES) == len(set(FEATURE_NAMES))
