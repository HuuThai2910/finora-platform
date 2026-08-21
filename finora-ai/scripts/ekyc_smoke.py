"""Chạy thử luồng eKYC trên ảnh thật và đo thời gian từng bước.

Ba chế độ:

1. **Chỉ OCR** — kiểm tra chất lượng đọc trên một ảnh CCCD thật::

       python scripts/ekyc_smoke.py --cccd data/cccd.jpg

2. **Gọi thẳng AI service** — thêm chuỗi frame để chạy đủ OCR, active liveness
   và so khớp khuôn mặt; không cần Keycloak::

       python scripts/ekyc_smoke.py --cccd data/cccd.jpg --frames data/session-01

3. **Đi qua gateway** — chạy đúng luồng nghiệp vụ của ``finora-user`` (lấy
   challenge rồi xác minh), cần JWT của người dùng đã nộp CCCD::

       python scripts/ekyc_smoke.py --cccd data/cccd.jpg --frames data/session-01 \\
           --gateway http://localhost:8080 --token "$JWT"

Số CCCD in ra luôn bị che, chỉ giữ 4 số cuối — không đưa PII thật vào log/báo cáo.
"""

import argparse
import base64
import sys
import time
from pathlib import Path

import httpx

IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
DEFAULT_ACTIONS = ["blink", "turn_left"]
REQUEST_TIMEOUT = httpx.Timeout(120.0, connect=5.0)


def _encode(path: Path) -> str:
    if not path.is_file():
        sys.exit(f"Không tìm thấy file: {path}")
    return base64.b64encode(path.read_bytes()).decode()


def _encode_frames(folder: Path) -> list[str]:
    if not folder.is_dir():
        sys.exit(f"Không tìm thấy thư mục frame: {folder}")
    frames = [
        _encode(path)
        for path in sorted(folder.iterdir())
        if path.suffix.lower() in IMAGE_SUFFIXES
    ]
    if not frames:
        sys.exit(f"Thư mục không có frame hợp lệ: {folder}")
    return frames


def _mask_id(id_number: str | None) -> str:
    if not id_number:
        return "(không đọc được)"
    return "*" * max(0, len(id_number) - 4) + id_number[-4:]


def _post(client: httpx.Client, url: str, payload: dict, label: str) -> dict:
    """Gọi một endpoint, in thời gian xử lý và trả JSON."""
    started = time.perf_counter()
    response = client.post(url, json=payload)
    elapsed = time.perf_counter() - started
    print(f"  {label:18s} {elapsed:6.2f}s  HTTP {response.status_code}")
    if response.status_code >= 400:
        print(f"    lỗi: {response.text[:300]}")
        sys.exit(1)
    return response.json()


def _print_ocr(ocr: dict) -> None:
    """In kết quả OCR. ``success`` chỉ bật khi đọc được số CCCD 12 chữ số."""
    print(f"Kết quả OCR (success={ocr.get('success')}):")
    print(f"  số CCCD    : {_mask_id(ocr.get('id_number'))}")
    print(f"  họ tên     : {ocr.get('full_name')}")
    print(f"  ngày sinh  : {ocr.get('date_of_birth')}")
    print(f"  giới tính  : {ocr.get('gender')}")
    print(f"  quê quán   : {ocr.get('place_of_origin')}")
    print(f"  confidence : {ocr.get('confidence')}")


def run_ocr_only(ai_url: str, cccd_base64: str) -> None:
    """Chỉ chạy OCR — kiểm tra chất lượng đọc trên ảnh CCCD thật."""
    print(f"Gọi OCR tại {ai_url} (lần đầu EasyOCR phải tải model nên sẽ lâu)")
    with httpx.Client(timeout=REQUEST_TIMEOUT) as client:
        ocr = _post(
            client,
            f"{ai_url}/api/v1/ai/ekyc/ocr",
            {"image_base64": cccd_base64},
            "OCR CCCD",
        )
    print()
    _print_ocr(ocr)


def run_direct(
    ai_url: str, cccd_base64: str, frames: list[str], actions: list[str]
) -> None:
    print(
        f"Gọi thẳng AI service tại {ai_url} — {len(frames)} frame, hành động {actions}"
    )
    total = time.perf_counter()

    with httpx.Client(timeout=REQUEST_TIMEOUT) as client:
        ocr = _post(
            client,
            f"{ai_url}/api/v1/ai/ekyc/ocr",
            {"image_base64": cccd_base64},
            "OCR CCCD",
        )
        liveness = _post(
            client,
            f"{ai_url}/api/v1/ai/ekyc/liveness-active",
            {"frames": frames, "expected_actions": actions},
            "Active liveness",
        )

        best_index = liveness.get("best_frame_index")
        selfie = frames[best_index] if best_index is not None else frames[0]
        face = _post(
            client,
            f"{ai_url}/api/v1/ai/ekyc/face-match",
            {"selfie_base64": selfie, "cccd_image_base64": cccd_base64},
            "Face match",
        )

    print(f"  {'TỔNG':18s} {time.perf_counter() - total:6.2f}s\n")

    _print_ocr(ocr)

    print("\nKết quả liveness:")
    print(
        f"  is_live={liveness.get('is_live')} confidence={liveness.get('confidence')} "
        f"best_frame={best_index}"
    )
    for action in liveness.get("actions", []):
        mark = "đạt " if action["passed"] else "trượt"
        print(f"  [{mark}] {action['action']}: {action['evidence']}")
    passive = liveness.get("passive_check")
    if passive:
        print(
            f"  lớp texture: is_live={passive['is_live']} confidence={passive['confidence']}"
        )

    print("\nKết quả face match:")
    print(
        f"  match={face.get('match')} similarity={face.get('similarity')} "
        f"threshold={face.get('threshold')}"
    )


def run_gateway(gateway: str, token: str, cccd_base64: str, frames: list[str]) -> None:
    print(f"Đi qua gateway {gateway} — {len(frames)} frame")
    base = f"{gateway}/api/v1/users/profile"
    headers = {"Authorization": f"Bearer {token}"}
    total = time.perf_counter()

    with httpx.Client(timeout=REQUEST_TIMEOUT, headers=headers) as client:
        challenge = _post(client, f"{base}/liveness-challenge", {}, "Lấy challenge")
        data = challenge.get("data", challenge)
        actions = data.get("actions")
        print(
            f"    hành động yêu cầu: {actions} (hết hạn sau {data.get('expiresInSeconds')}s)"
        )

        result = _post(
            client,
            f"{base}/ekyc-verify",
            {
                "sessionId": data.get("sessionId"),
                "frames": frames,
                "cccdImageBase64": cccd_base64,
            },
            "Xác minh eKYC",
        )

    print(f"  {'TỔNG':18s} {time.perf_counter() - total:6.2f}s\n")

    body = result.get("data", result)
    print("Kết quả xác minh:")
    print(f"  status       : {body.get('status')}")
    print(f"  resultCode   : {body.get('resultCode')}")
    print(f"  faceMatch    : {body.get('faceMatch')} ({body.get('faceMatchScore')})")
    print(f"  liveness     : {body.get('livenessVerified')}")
    print(f"  cảnh báo OCR : {body.get('ocrWarnings')}")
    print(f"  thông điệp   : {body.get('message')}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cccd", type=Path, required=True, help="Ảnh mặt trước CCCD")
    parser.add_argument(
        "--frames",
        type=Path,
        help="Thư mục chứa các frame; bỏ trống thì chỉ chạy OCR",
    )
    parser.add_argument(
        "--ai-url", default="http://localhost:8000", help="URL AI service"
    )
    parser.add_argument(
        "--gateway", help="URL gateway; có giá trị thì chạy luồng nghiệp vụ thật"
    )
    parser.add_argument(
        "--token", help="JWT của người dùng, bắt buộc khi dùng --gateway"
    )
    parser.add_argument(
        "--actions",
        default=",".join(DEFAULT_ACTIONS),
        help="Chuỗi hành động khi gọi thẳng AI service, cách nhau bằng dấu phẩy",
    )
    args = parser.parse_args()

    cccd_base64 = _encode(args.cccd)

    if args.gateway:
        if not args.token:
            sys.exit("Chạy qua gateway cần --token là JWT của người dùng đã nộp CCCD.")
        if not args.frames:
            sys.exit("Luồng qua gateway cần --frames vì bước liveness là bắt buộc.")
        run_gateway(args.gateway, args.token, cccd_base64, _encode_frames(args.frames))
    elif args.frames:
        actions = [a.strip() for a in args.actions.split(",") if a.strip()]
        run_direct(args.ai_url, cccd_base64, _encode_frames(args.frames), actions)
    else:
        run_ocr_only(args.ai_url, cccd_base64)


if __name__ == "__main__":
    main()
