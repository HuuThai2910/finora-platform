"""Nắn thẻ trên ảnh tổng hợp và cổng chất lượng (nét/mờ, loá, tối)."""

import cv2
import numpy as np
import pytest

from app.ml.ekyc import card_warp, quality
from app.ml.ekyc.thresholds import DocThresholds

from .synthetic import CARD_H, CARD_W, front_card, on_background

TH = DocThresholds()


def _marked_card() -> np.ndarray:
    """Thẻ có ô đỏ ở góc trên-trái để kiểm chiều sau khi nắn."""
    card = front_card(None)
    cv2.rectangle(card, (20, 20), (140, 120), (0, 0, 255), -1)
    return card


def _red_ratio(image: np.ndarray) -> float:
    b, g, r = cv2.split(image.astype(np.int16))
    return float(np.mean((r > 150) & (g < 80) & (b < 80)))


class TestCardWarp:
    @pytest.mark.parametrize("angle", [-15.0, 0.0, 9.0, 20.0])
    def test_tim_va_nan_the_xoay_tren_nen(self, angle):
        scene = on_background(_marked_card(), angle=angle)
        warp = card_warp.warp_card(scene, TH.card_min_area_ratio, TH.card_warp_width)
        assert warp.card_found
        assert warp.image.shape[:2] == (round(1400 / card_warp.CARD_ASPECT), 1400)
        height, width = warp.image.shape[:2]
        # Ô đỏ phải nằm ở góc trên-trái, không ở các góc khác.
        top_left = _red_ratio(warp.image[: height // 4, : width // 4])
        bottom_right = _red_ratio(warp.image[3 * height // 4 :, 3 * width // 4 :])
        assert top_left > 0.1
        assert bottom_right < 0.01

    def test_the_nam_doc_duoc_xoay_ve_ngang(self):
        scene = on_background(_marked_card(), angle=90.0, scale=0.8)
        warp = card_warp.warp_card(scene, TH.card_min_area_ratio, TH.card_warp_width)
        assert warp.card_found
        assert card_warp.aspect_ratio(warp.image) == pytest.approx(
            card_warp.CARD_ASPECT, 0.01
        )

    def test_khong_thay_the_thi_dung_nguyen_anh(self):
        rng = np.random.default_rng(3)
        noise = rng.integers(0, 255, (900, 1200, 3)).astype(np.uint8)
        warp = card_warp.warp_card(noise, TH.card_min_area_ratio, TH.card_warp_width)
        assert not warp.card_found
        assert warp.image.shape[1] == 1400  # đưa về cùng chiều rộng

    def test_anh_da_cat_sat_the_khong_tim_contour(self):
        # Mobile đã cắt theo khung: mép thẻ trùng mép ảnh → card_found=False, vẫn dùng được.
        warp = card_warp.warp_card(front_card(None), TH.card_min_area_ratio, 1400)
        assert warp.image.shape[:2] == (CARD_H, CARD_W)

    def test_thu_tu_goc(self):
        corners = np.array([[10, 90], [100, 100], [5, 5], [95, 0]], dtype=np.float32)
        ordered = card_warp.order_corners(corners)
        assert ordered.tolist() == [[5, 5], [95, 0], [100, 100], [10, 90]]


class TestQuality:
    def test_the_net_dat(self):
        result = quality.measure_quality(front_card(None), True, TH)
        assert result.ok
        assert result.sharpness > TH.min_sharpness
        assert result.issues == ()

    def test_the_mo_bi_blur(self):
        blurred = cv2.GaussianBlur(front_card(None), (0, 0), 4)
        sharp = quality.measure_quality(front_card(None), True, TH)
        result = quality.measure_quality(blurred, True, TH)
        assert result.sharpness < sharp.sharpness / 10
        assert result.issues == (quality.ISSUE_BLUR,)
        assert not result.ok

    def test_loa_sang(self):
        card = front_card(None)
        cv2.circle(card, (900, 450), 220, (255, 255, 255), -1)
        result = quality.measure_quality(card, True, TH)
        assert result.glare_ratio > TH.max_glare_ratio
        assert quality.ISSUE_GLARE in result.issues

    def test_qua_toi_bao_truoc_mo(self):
        dark = (front_card(None).astype(np.float32) * 0.15).astype(np.uint8)
        result = quality.measure_quality(dark, True, TH)
        assert result.brightness < TH.min_brightness
        assert result.issues[0] == quality.ISSUE_TOO_DARK

    def test_as_dict_dung_khuon_hop_dong(self):
        data = quality.measure_quality(front_card(None), False, TH).as_dict()
        assert set(data) == {
            "card_found",
            "sharpness",
            "glare_ratio",
            "brightness",
            "ok",
        }
        assert data["card_found"] is False
