"""Kiểm đầu vào ảnh: base64 chặt, magic bytes, kích thước header, giải mã."""

import base64
import struct

import cv2
import numpy as np
import pytest

from app.ml.ekyc import image_io
from app.ml.ekyc.image_io import ImageInputError

from .synthetic import front_card, jpeg_bytes


def _png_bytes(image: np.ndarray) -> bytes:
    ok, buffer = cv2.imencode(".png", image)
    assert ok
    return buffer.tobytes()


def test_jpeg_va_png_hop_le():
    card = front_card(None)
    for data in (jpeg_bytes(card), _png_bytes(card)):
        assert image_io.image_dimensions(data) == (1400, 883)
        decoded = image_io.decode_image_bytes(data)
        assert decoded.shape == (883, 1400, 3)


def test_base64_co_tien_to_data_url_va_xuong_dong():
    data = jpeg_bytes(front_card(None))
    encoded = base64.encodebytes(data).decode()  # có ký tự xuống dòng như Java MIME
    assert image_io.decode_base64_strict("data:image/jpeg;base64," + encoded) == data


@pytest.mark.parametrize("text", ["@@@khong-phai-base64@@@", "YWJj$", "   "])
def test_base64_sai(text):
    with pytest.raises(ImageInputError) as exc:
        image_io.decode_base64_strict(text)
    assert exc.value.code == "IMAGE_BASE64_INVALID"


def test_base64_qua_dai():
    with pytest.raises(ImageInputError) as exc:
        image_io.decode_base64_strict("A" * (image_io.MAX_IMAGE_B64_CHARS + 4))
    assert exc.value.code == "IMAGE_TOO_LARGE"


def test_khong_phai_anh():
    with pytest.raises(ImageInputError) as exc:
        image_io.decode_image_bytes(b"GIF89a....khong ho tro")
    assert exc.value.code == "IMAGE_TYPE_UNSUPPORTED"


def test_header_khai_kich_thuoc_khong_lo_bi_chan_truoc_khi_giai_ma():
    # PNG giả: chữ ký + IHDR khai 50000 × 50000 px.
    header = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR"
    header += struct.pack(">II", 50_000, 50_000) + b"\x08\x02\x00\x00\x00"
    with pytest.raises(ImageInputError) as exc:
        image_io.decode_image_bytes(header + b"\x00" * 64)
    assert exc.value.code == "IMAGE_TOO_LARGE"


def test_jpeg_hong_sau_magic_bytes():
    with pytest.raises(ImageInputError) as exc:
        image_io.decode_image_bytes(b"\xff\xd8\xff\xe0" + b"\x00" * 50)
    assert exc.value.code == "IMAGE_DECODE_FAILED"


def test_resize_chi_thu_nho_mac_dinh():
    image = np.zeros((100, 200, 3), np.uint8)
    assert image_io.resize_to_width(image, 400).shape == (100, 200, 3)
    assert image_io.resize_to_width(image, 400, allow_upscale=True).shape == (
        200,
        400,
        3,
    )
    assert image_io.resize_to_width(image, 100).shape == (50, 100, 3)


def test_do_net_giam_khi_lam_mo():
    card = front_card(None)
    assert image_io.sharpness(cv2.GaussianBlur(card, (0, 0), 3)) < image_io.sharpness(
        card
    )
