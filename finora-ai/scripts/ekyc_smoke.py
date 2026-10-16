"""Gọi thử hai endpoint eKYC nội bộ của finora-ai đang chạy và đo thời gian.

Ba chế độ::

    # Đọc CCCD hai mặt
    python scripts/ekyc_smoke.py document --front mat-truoc.jpg --back mat-sau.jpg

    # Liveness với embedding chân dung lấy từ lần đọc thẻ ngay trước đó
    python scripts/ekyc_smoke.py full --front mat-truoc.jpg --back mat-sau.jpg \\
        --video quay.mp4 --steps "FRONTAL:1500,TURN_LEFT:1300,FRONTAL:1100,LOOK_UP:1600,FRONTAL:900,TURN_RIGHT:1400,FRONTAL:1000"

    # Liveness với embedding có sẵn (base64)
    python scripts/ekyc_smoke.py liveness --video quay.mp4 --embedding <b64> --steps ...

Token nội bộ lấy từ ``--token`` hoặc biến ``AI_INTERNAL_TOKEN``. Script **không in PII**:
số định danh chỉ hiện 4 số cuối, các trường khác chỉ in nguồn (QR/MRZ/OCR/AI). Dữ liệu
thật để ngoài repo.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
from pathlib import Path

import httpx

TIMEOUT = httpx.Timeout(120.0, connect=5.0)


def _mask(value: str | None) -> str:
    if not value:
        return "(không có)"
    return "*" * max(0, len(value) - 4) + value[-4:]


def _headers(token: str | None) -> dict:
    return {"X-Internal-Token": token} if token else {}


def _read(path: Path) -> bytes:
    if not path.is_file():
        sys.exit(f"Không tìm thấy file: {path}")
    return path.read_bytes()


def parse_steps(text: str) -> list[dict]:
    """``FRONTAL:1500,TURN_LEFT:1300`` → danh sách bước của spec."""
    steps = []
    for item in text.split(","):
        action, _, hold = item.strip().partition(":")
        steps.append({"action": action.strip().upper(), "hold_ms": int(hold)})
    return steps


def call_document(client: httpx.Client, args) -> dict:
    payload = {
        "front_image_base64": base64.b64encode(_read(args.front)).decode(),
        "back_image_base64": base64.b64encode(_read(args.back)).decode(),
        "qr_text": args.qr_text,
        "qr_side": args.qr_side,
        "front_source": args.source,
        "back_source": args.source,
        "retake_count": args.retake_count,
    }
    started = time.perf_counter()
    response = client.post(f"{args.url}/api/v1/ai/ekyc/document", json=payload)
    elapsed = time.perf_counter() - started
    print(f"POST /document  HTTP {response.status_code}  {elapsed:6.2f}s")
    if response.status_code != 200:
        print(f"  lỗi: {response.text[:300]}")
        sys.exit(1)
    body = response.json()
    fields = body["fields"]
    print(f"  decision      : {body['decision']}  (card_type={body['card_type']})")
    print(f"  reasons       : {body['reasons']}")
    print(f"  warnings      : {body['warnings']}")
    print(f"  retake        : {body['retake']}")
    print(
        f"  số định danh  : {_mask(fields['id_number']['value'])} "
        f"nguồn={fields['id_number']['sources']}"
    )
    for name in (
        "full_name",
        "date_of_birth",
        "gender",
        "address",
        "issue_date",
        "expiry_date",
    ):
        print(
            f"  {name:14s}: {'có' if fields[name]['value'] else '—':3s} "
            f"nguồn={fields[name]['sources']}"
        )
    checks = body["checks"]
    print(f"  QR            : {checks['qr']}")
    print(
        f"  MRZ           : found={checks['mrz']['found']} valid={checks['mrz']['valid']} "
        f"engine={checks['mrz']['engine']}"
    )
    print(f"  gắn hai mặt   : {checks['front_back_linked']}  ai_used={body['ai_used']}")
    print(
        f"  chất lượng    : front={body['quality']['front']} back={body['quality']['back']}"
    )
    print(
        f"  chân dung     : found={body['portrait']['found']} "
        f"ied={body['portrait']['ied_px']} sharp={body['portrait']['sharpness']}"
    )
    return body


def call_liveness(client: httpx.Client, args, embedding_b64: str) -> dict:
    spec = {
        "steps": parse_steps(args.steps),
        "portrait_embedding_b64": embedding_b64,
        "capture_mode": "VIDEO",
        "platform": args.platform,
    }
    video = _read(args.video)
    started = time.perf_counter()
    response = client.post(
        f"{args.url}/api/v1/ai/ekyc/liveness",
        data={"spec": json.dumps(spec)},
        files={"video": (args.video.name, video, "video/mp4")},
    )
    elapsed = time.perf_counter() - started
    print(
        f"POST /liveness  HTTP {response.status_code}  {elapsed:6.2f}s  "
        f"({len(video) / 1024:.0f} KB)"
    )
    if response.status_code != 200:
        print(f"  lỗi: {response.text[:300]}")
        sys.exit(1)
    body = response.json()
    print(f"  decision      : {body['decision']}")
    print(f"  reasons       : {body['reasons']}")
    print(f"  video         : {body['video']}")
    print(f"  faces         : {body['faces']}")
    print(f"  actions       : {body['actions']}")
    print(f"  identity      : {body['identity']}")
    print(f"  face_match    : {body['face_match']}")
    print(f"  evidence      : {[f['label'] for f in body['evidence_frames']]}")
    return body


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("mode", choices=["document", "liveness", "full"])
    parser.add_argument("--url", default="http://localhost:8000")
    parser.add_argument("--token", default=os.getenv("AI_INTERNAL_TOKEN"))
    parser.add_argument("--front", type=Path)
    parser.add_argument("--back", type=Path)
    parser.add_argument("--qr-text")
    parser.add_argument("--qr-side", choices=["FRONT", "BACK"])
    parser.add_argument(
        "--source",
        default="IN_APP_CAMERA",
        choices=["IN_APP_CAMERA", "SYSTEM_CAMERA", "GALLERY"],
    )
    parser.add_argument("--retake-count", type=int, default=0)
    parser.add_argument("--video", type=Path)
    parser.add_argument(
        "--steps",
        default="FRONTAL:1500,TURN_LEFT:1300,FRONTAL:1100,"
        "LOOK_UP:1600,FRONTAL:900,TURN_RIGHT:1400,FRONTAL:1000",
    )
    parser.add_argument(
        "--embedding", help="embedding chân dung base64 (chế độ liveness)"
    )
    parser.add_argument("--platform", choices=["ios", "android", "web"])
    args = parser.parse_args()

    total = time.perf_counter()
    with httpx.Client(timeout=TIMEOUT, headers=_headers(args.token)) as client:
        if args.mode in ("document", "full"):
            if not args.front or not args.back:
                sys.exit("Cần --front và --back.")
            document = call_document(client, args)
        if args.mode in ("liveness", "full"):
            if not args.video:
                sys.exit("Cần --video.")
            embedding = args.embedding
            if args.mode == "full":
                embedding = document["portrait"].get("embedding_b64")
                if not embedding:
                    sys.exit(
                        "Chân dung trên thẻ không dùng được — không chạy liveness."
                    )
            if not embedding:
                sys.exit("Cần --embedding (hoặc dùng chế độ full).")
            call_liveness(client, args, embedding)
    print(f"TỔNG {time.perf_counter() - total:6.2f}s")


if __name__ == "__main__":
    main()
