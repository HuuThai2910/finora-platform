"""Đánh giá pipeline đọc CCCD trên bộ ảnh có nhãn: độ khớp chính xác từng trường.

Dữ liệu (ảnh thẻ thật của tình nguyện viên đã đồng ý) để NGOÀI repo. Thư mục vào có
``labels.json``::

    [
      {"id": "c01", "front": "c01_front.jpg", "back": "c01_back.jpg",
       "qr_text": null, "qr_side": null, "source": "IN_APP_CAMERA",
       "truth": {"id_number": "...", "full_name": "...", "date_of_birth": "dd/mm/yyyy",
                 "gender": "Nam", "address": "...", "issue_date": "dd/mm/yyyy",
                 "expiry_date": "dd/mm/yyyy"}}
    ]

Chạy pipeline thật trong tiến trình (cần model mặt; Tesseract/Gemini theo môi trường)::

    python scripts/ekyc_eval_cccd.py D:/ekyc-eval/cards
    python scripts/ekyc_eval_cccd.py D:/ekyc-eval/cards --gemini-baseline --csv ket-qua.csv

``--gemini-baseline`` chạy thêm mốc so sánh "chỉ Gemini" (đọc mặt trước đã che chân dung,
không QR/MRZ) để so với pipeline mới — chỉ dùng key gói trả phí hoặc thẻ mẫu (điều khoản
gói free cấm gửi dữ liệu cá nhân). Script chỉ in id thẻ và đúng/sai, **không in giá trị**.
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
import time
import unicodedata
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.ml.ekyc import card_warp, face, gemini_extractor
from app.ml.ekyc.thresholds import doc_thresholds, settings
from app.ml.ekyc.vn_text import format_date
from app.schemas.ekyc import DocumentRequest
from app.services.ekyc.service import EkycService, _masked_front_jpeg

FIELDS = (
    "id_number",
    "full_name",
    "date_of_birth",
    "gender",
    "address",
    "issue_date",
    "expiry_date",
)


def normalize(value: str | None) -> str | None:
    """So khớp "chính xác" sau khi chuẩn hoá NFC, gộp khoảng trắng, không phân biệt hoa thường."""
    if value is None:
        return None
    text = " ".join(unicodedata.normalize("NFC", str(value)).split())
    return text.casefold() or None


def load_labels(folder: Path) -> list[dict]:
    path = folder / "labels.json"
    if not path.is_file():
        sys.exit(f"Không thấy {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def pipeline_fields(
    service: EkycService, folder: Path, item: dict
) -> tuple[dict, dict, float]:
    front = (folder / item["front"]).read_bytes()
    back = (folder / item["back"]).read_bytes()
    request = DocumentRequest(
        front_image_base64="x",  # service nhận bytes trực tiếp, chuỗi chỉ để qua schema
        back_image_base64="x",
        qr_text=item.get("qr_text"),
        qr_side=item.get("qr_side"),
        front_source=item.get("source", "IN_APP_CAMERA"),
        back_source=item.get("source", "IN_APP_CAMERA"),
    )
    started = time.perf_counter()
    result = service.analyze_document(request, front, back)
    elapsed = time.perf_counter() - started
    return {name: result["fields"][name]["value"] for name in FIELDS}, result, elapsed


def gemini_only_fields(reader, folder: Path, item: dict) -> dict:
    """Mốc so sánh: Gemini đọc mặt trước đã che chân dung, không QR/MRZ."""
    from app.ml.ekyc import image_io

    engine = face.get_engine()
    th = doc_thresholds()
    image = image_io.decode_image_bytes((folder / item["front"]).read_bytes())
    warped = card_warp.warp_card(image, th.card_min_area_ratio, th.card_warp_width)
    read = reader.read_front_fields(_masked_front_jpeg(engine, warped.image), False)
    if read is None:
        return dict.fromkeys(FIELDS)
    return {
        "id_number": read.id_number,
        "full_name": read.full_name,
        "date_of_birth": format_date(read.date_of_birth),
        "gender": read.gender,
        "address": read.address,
        "issue_date": format_date(read.issue_date),
        "expiry_date": format_date(read.expiry_date),
    }


def score(predicted: dict, truth: dict) -> dict:
    """``True``/``False`` từng trường; trường không có nhãn thì ``None`` (bỏ qua)."""
    out = {}
    for name in FIELDS:
        expected = normalize(truth.get(name))
        if expected is None:
            out[name] = None
            continue
        out[name] = normalize(predicted.get(name)) == expected
    return out


def print_table(title: str, rows: list[dict], predictions: list[dict]) -> None:
    print(f"\n{title}")
    print(
        f"  {'trường':15s} {'có nhãn':>8s} {'đọc được':>9s} {'đúng':>6s} {'độ khớp':>8s}"
    )
    for name in FIELDS:
        labeled = [r[name] for r in rows if r[name] is not None]
        read = sum(
            1 for p, r in zip(predictions, rows) if r[name] is not None and p.get(name)
        )
        correct = sum(1 for value in labeled if value)
        accuracy = correct / len(labeled) if labeled else float("nan")
        print(f"  {name:15s} {len(labeled):8d} {read:9d} {correct:6d} {accuracy:8.1%}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("folder", type=Path, help="thư mục chứa labels.json và ảnh")
    parser.add_argument(
        "--gemini-baseline",
        action="store_true",
        help="chạy thêm mốc 'chỉ Gemini' (cần GEMINI_API_KEY gói trả phí)",
    )
    parser.add_argument(
        "--csv", type=Path, help="ghi đúng/sai từng thẻ (không có giá trị)"
    )
    args = parser.parse_args()

    if not face.models_present():
        sys.exit("Thiếu model mặt — chạy scripts/download_face_models.py trước.")
    labels = load_labels(args.folder)
    service = EkycService()
    reader = (
        gemini_extractor.build_from_env(settings()) if args.gemini_baseline else None
    )
    if args.gemini_baseline and reader is None:
        sys.exit("--gemini-baseline cần GEMINI_API_KEY và EKYC_GEMINI_MODE khác off.")

    pipeline_rows, pipeline_preds, baseline_rows, baseline_preds = [], [], [], []
    decisions, latencies = Counter(), []
    qr_valid = mrz_valid = linked = ai_used = 0
    csv_rows = []
    for item in labels:
        predicted, result, elapsed = pipeline_fields(service, args.folder, item)
        row = score(predicted, item.get("truth", {}))
        pipeline_rows.append(row)
        pipeline_preds.append(predicted)
        decisions[result["decision"]] += 1
        latencies.append(elapsed)
        qr_valid += result["checks"]["qr"]["valid"]
        mrz_valid += result["checks"]["mrz"]["valid"]
        linked += result["checks"]["front_back_linked"]
        ai_used += result["ai_used"]
        csv_row = {
            "id": item["id"],
            "decision": result["decision"],
            "card_type": result["card_type"],
            **{f"pipeline_{k}": v for k, v in row.items()},
        }
        if reader is not None:
            base_pred = gemini_only_fields(reader, args.folder, item)
            base_row = score(base_pred, item.get("truth", {}))
            baseline_rows.append(base_row)
            baseline_preds.append(base_pred)
            csv_row.update({f"gemini_{k}": v for k, v in base_row.items()})
        csv_rows.append(csv_row)
        print(
            f"  {item['id']}: {result['decision']:12s} {elapsed:5.2f}s "
            f"đúng {sum(1 for v in row.values() if v)}/"
            f"{sum(1 for v in row.values() if v is not None)} trường"
        )

    total = len(labels)
    print(f"\nSố thẻ: {total}  quyết định: {dict(decisions)}")
    print(
        f"QR hợp lệ: {qr_valid}/{total}  MRZ đủ check digit: {mrz_valid}/{total}  "
        f"gắn hai mặt: {linked}/{total}  có gọi AI: {ai_used}/{total}"
    )
    if latencies:
        ordered = sorted(latencies)
        p95 = ordered[min(len(ordered) - 1, int(0.95 * len(ordered)))]
        print(f"Thời gian: trung vị {statistics.median(ordered):.2f}s  p95 {p95:.2f}s")
    print_table(
        "Pipeline mới (QR/MRZ/OCR + AI điền chỗ trống)", pipeline_rows, pipeline_preds
    )
    if baseline_rows:
        print_table(
            "Mốc so sánh: chỉ Gemini (mặt trước đã che)", baseline_rows, baseline_preds
        )
    if args.csv:
        with args.csv.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(
                handle, fieldnames=sorted({k for r in csv_rows for k in r})
            )
            writer.writeheader()
            writer.writerows(csv_rows)
        print(f"\nĐã ghi {args.csv} (chỉ đúng/sai, không có giá trị trường).")


if __name__ == "__main__":
    main()
