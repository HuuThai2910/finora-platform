"""Tải hai model khuôn mặt của OpenCV Zoo cho eKYC và kiểm SHA-256.

- YuNet ``face_detection_yunet_2023mar.onnx`` (MIT): dò mặt + 5 điểm mốc.
- SFace ``face_recognition_sface_2021dec.onnx`` (Apache-2.0): embedding 128 chiều.

Model **không commit** vào repo (``.gitignore``), nên máy dev và ``Dockerfile.ai`` đều
chạy script này. URL ghim theo commit của opencv_zoo và đi qua
``media.githubusercontent.com`` để lấy đúng file Git LFS (đường ``raw`` của nhánh chỉ
trả con trỏ LFS nếu GitHub đổi cách chuyển hướng). Hash sai thì dừng với mã lỗi khác 0
và không để lại file dở dang — thà build hỏng còn hơn chạy model lạ.

Chỉ dùng thư viện chuẩn (urllib, hashlib) để chạy được trong stage build không có curl.

Cách dùng::

    python scripts/download_face_models.py                 # ghi vào finora-ai/models/face
    python scripts/download_face_models.py --dest /models/face
    python scripts/download_face_models.py --check         # chỉ kiểm file đã có
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DEST = Path(__file__).resolve().parent.parent / "models" / "face"
USER_AGENT = "finora-ai-face-model-fetch/1.0"
TIMEOUT_SECONDS = 120
CHUNK_BYTES = 1 << 20


@dataclass(frozen=True)
class ModelFile:
    """Một file model cần tải, ghim theo commit và SHA-256 đã kiểm."""

    name: str
    url: str
    sha256: str
    size: int


MODELS: tuple[ModelFile, ...] = (
    ModelFile(
        name="face_detection_yunet_2023mar.onnx",
        # Commit f12e127 "update yunet to v2 (#151)", 2023-06-06.
        url=(
            "https://media.githubusercontent.com/media/opencv/opencv_zoo/"
            "f12e12798e8314f7c074a6656816c048dcc95b7a/models/face_detection_yunet/"
            "face_detection_yunet_2023mar.onnx"
        ),
        sha256="8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
        size=232_589,
    ),
    ModelFile(
        name="face_recognition_sface_2021dec.onnx",
        # Commit ba91a3b "update sface: quantization ready (#35)", 2022-01-06.
        url=(
            "https://media.githubusercontent.com/media/opencv/opencv_zoo/"
            "ba91a3b91d00d76e86540d4013f944bd6b514e39/models/face_recognition_sface/"
            "face_recognition_sface_2021dec.onnx"
        ),
        sha256="0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
        size=38_696_353,
    ),
)


def sha256_of(path: Path) -> str:
    """SHA-256 của file, đọc theo khối để không nạp cả 37 MB vào RAM một lúc."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_valid(path: Path, model: ModelFile) -> bool:
    """File đã có đúng kích thước và đúng hash thì không cần tải lại."""
    return (
        path.is_file()
        and path.stat().st_size == model.size
        and sha256_of(path) == model.sha256
    )


def download(model: ModelFile, dest_dir: Path) -> None:
    """Tải vào file tạm cùng thư mục rồi đổi tên nguyên tử khi hash khớp."""
    target = dest_dir / model.name
    request = urllib.request.Request(model.url, headers={"User-Agent": USER_AGENT})
    fd, tmp_name = tempfile.mkstemp(prefix=f".{model.name}.", dir=dest_dir)
    tmp_path = Path(tmp_name)
    try:
        digest = hashlib.sha256()
        written = 0
        with (
            os.fdopen(fd, "wb") as out,
            urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response,
        ):
            for chunk in iter(lambda: response.read(CHUNK_BYTES), b""):
                out.write(chunk)
                digest.update(chunk)
                written += len(chunk)
        actual = digest.hexdigest()
        if actual != model.sha256 or written != model.size:
            raise RuntimeError(
                f"{model.name}: hash/kích thước không khớp "
                f"(nhận {written} byte, sha256 {actual}; cần {model.size} byte, "
                f"sha256 {model.sha256})"
            )
        os.replace(tmp_path, target)
    finally:
        if tmp_path.exists():
            tmp_path.unlink()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST, help="Thư mục đích")
    parser.add_argument(
        "--check", action="store_true", help="Chỉ kiểm file đã có, không tải"
    )
    parser.add_argument(
        "--force", action="store_true", help="Tải lại kể cả khi file đã đúng hash"
    )
    args = parser.parse_args(argv)

    dest: Path = args.dest
    dest.mkdir(parents=True, exist_ok=True)
    failed = False
    for model in MODELS:
        path = dest / model.name
        if not args.force and is_valid(path, model):
            print(f"[ok]   {model.name} (sha256 {model.sha256[:12]}…)")
            continue
        if args.check:
            print(f"[thiếu/sai] {model.name}")
            failed = True
            continue
        print(f"[tải]  {model.name} ← {model.url}")
        try:
            download(model, dest)
        except Exception as exc:  # noqa: BLE001 — báo lỗi rõ ràng rồi trả mã thoát khác 0
            print(f"[lỗi]  {exc}", file=sys.stderr)
            failed = True
            continue
        print(f"[ok]   {model.name} (sha256 {model.sha256[:12]}…)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
