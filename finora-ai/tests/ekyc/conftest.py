"""Fixture chung cho test eKYC.

- Luôn tắt Gemini: ``main.py`` nạp ``finora-ai/.env`` (máy dev có thể có
  ``GEMINI_API_KEY`` thật) — test không bao giờ được gọi API thật.
- Xoá cache cấu hình/service trước và sau mỗi test để ``monkeypatch.setenv`` có tác dụng.
"""

import pytest

from app.ml.ekyc import face, gemini_extractor, thresholds


def _clear_caches() -> None:
    thresholds.settings.cache_clear()
    thresholds.doc_thresholds.cache_clear()
    thresholds.liveness_thresholds.cache_clear()
    from app.api import ekyc_router

    # Test có thể đã monkeypatch get_ekyc_service bằng hàm thường (chưa được hoàn tác).
    clear = getattr(ekyc_router.get_ekyc_service, "cache_clear", None)
    if clear is not None:
        clear()


@pytest.fixture(autouse=True)
def _no_real_gemini(monkeypatch):
    monkeypatch.setenv("EKYC_GEMINI_MODE", "off")
    monkeypatch.setattr(gemini_extractor, "genai", None)
    _clear_caches()
    yield
    _clear_caches()


@pytest.fixture
def face_engine():
    """Engine YuNet/SFace thật; bỏ qua test nếu máy chưa tải model."""
    if not face.models_present():
        pytest.skip("Chưa tải model mặt — chạy scripts/download_face_models.py")
    return face.get_engine()
