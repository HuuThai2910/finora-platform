"""Model YuNet/SFace: nạp được, mã hoá embedding, chân dung trên thẻ tổng hợp.

Không commit khuôn mặt thật nào. Test với ảnh mặt thật chỉ chạy khi đặt biến
``EKYC_TEST_FACE_IMAGE`` trỏ tới một ảnh chân dung trên máy (ví dụ ảnh mẫu công khai).
"""

import cv2
import numpy as np
import pytest

from app.ml.ekyc import face
from app.ml.ekyc.thresholds import DocThresholds

from .synthetic import card_with_face, face_crop, front_card, real_face_image_or_skip

TH = DocThresholds()


class TestEmbeddingCodec:
    def test_ma_hoa_va_giai_ma_128_float32_le(self):
        vector = np.arange(1, 129, dtype=np.float32)
        decoded = face.decode_embedding(face.encode_embedding(vector))
        assert decoded.shape == (128,)
        assert np.linalg.norm(decoded) == pytest.approx(1.0, abs=1e-6)
        assert face.cosine(decoded, vector) == pytest.approx(1.0, abs=1e-6)

    @pytest.mark.parametrize("text", ["@@", "AAAA", face.encode_embedding(np.ones(64))])
    def test_sai_khuon_thi_value_error(self, text):
        with pytest.raises(ValueError):
            face.decode_embedding(text)

    def test_vector_khong_hop_le(self):
        zeros = face.encode_embedding(np.zeros(128, np.float32))
        with pytest.raises(ValueError):
            face.decode_embedding(zeros)

    def test_cosine(self):
        assert face.cosine(np.array([1.0, 0.0]), np.array([0.0, 2.0])) == pytest.approx(
            0.0
        )
        assert face.cosine(np.array([1.0, 1.0]), np.array([2.0, 2.0])) == pytest.approx(
            1.0
        )


def test_thieu_model_thi_bao_loi_ro_rang(tmp_path):
    assert not face.models_present(tmp_path)
    with pytest.raises(face.FaceModelsMissing):
        face.FaceEngine(tmp_path)


def test_nap_model_va_do_tren_anh_khong_co_mat(face_engine):
    blank = np.full((360, 480, 3), 127, np.uint8)
    assert face_engine.detect(blank, 0.6) == []
    result = face.extract_portrait(face_engine, front_card(None), TH)
    assert not result.found
    assert result.reason == face.REASON_NO_FACE
    assert result.embedding is None


def test_che_mat_khong_lam_hong_anh_khong_co_mat(face_engine):
    card = front_card(None)
    assert np.array_equal(face.mask_faces(face_engine, card), card)


class TestRealFace:
    """Chỉ chạy khi có ``EKYC_TEST_FACE_IMAGE`` (không có dữ liệu mặt thật trong repo)."""

    def test_chan_dung_tren_the_tong_hop(self, face_engine):
        card = card_with_face(face_crop(face_engine, real_face_image_or_skip()))
        result = face.extract_portrait(face_engine, card, TH)
        assert result.found, result.reason
        assert result.ied_px >= TH.portrait_min_ied
        assert result.embedding.shape == (128,)

    def test_chan_dung_mo_khong_dung_duoc(self, face_engine):
        crop = face_crop(face_engine, real_face_image_or_skip())
        # Độ nét đo trên ảnh mặt đã căn về 112×112 (đúng độ phân giải SFace dùng), nên độ mờ phải
        # tỉ lệ với cỡ ảnh mặt: σ = 6 trên ảnh mặt 400 px chỉ còn σ ≈ 1,7 sau khi thu về 112 px.
        sigma = max(6.0, crop.shape[1] / 25)
        card = card_with_face(cv2.GaussianBlur(crop, (0, 0), sigma))
        result = face.extract_portrait(face_engine, card, TH)
        assert not result.found

    def test_cung_nguoi_khac_do_phan_giai_van_khop(self, face_engine):
        image = real_face_image_or_skip()
        small = cv2.resize(image, None, fx=0.5, fy=0.5)
        a = face_engine.embed(image, face_engine.detect(image, 0.6)[0])
        b = face_engine.embed(small, face_engine.detect(small, 0.6)[0])
        assert face.cosine(a, b) > 0.5

    def test_che_mat_truoc_khi_gui_ai(self, face_engine):
        card = card_with_face(face_crop(face_engine, real_face_image_or_skip()))
        masked = face.mask_faces(face_engine, card)
        assert face_engine.detect(masked, 0.5) == []
