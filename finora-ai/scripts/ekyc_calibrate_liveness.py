"""Dò ngưỡng liveness trên bộ ảnh tự chụp.

Ngưỡng mặc định trong ``app/ml/ekyc/thresholds.py`` chỉ là điểm khởi đầu. Script này
đo trên dữ liệu thật để chọn ngưỡng có cơ sở, và in số liệu dùng được cho báo cáo.

Cách dùng::

    # 1) Ngưỡng LBP: cần hai thư mục ảnh — chụp trực tiếp và chụp lại từ màn hình
    python scripts/ekyc_calibrate_liveness.py lbp --live data/liveness/live --spoof data/liveness/spoof

    # 2) Kiểm tra EAR/yaw trên một chuỗi frame đã quay (đặt tên file theo thứ tự)
    python scripts/ekyc_calibrate_liveness.py pose --frames data/liveness/session-01

Lệnh ``pose`` in dấu của yaw: nếu quay đầu sang trái mà yaw ra số dương thì
camera đang lật gương — bật ``EKYC_YAW_INVERT=true``.
"""

import argparse
import sys
from pathlib import Path

# Cho phép chạy trực tiếp bằng python scripts/... — cùng cách với train_final_model.py
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.ml.ekyc.active_liveness import (
    ActiveLivenessDetector,
    detect_blinks,
    detect_turns,
)
from app.ml.ekyc.image_io import decode_image
from app.ml.ekyc.liveness_detector import LivenessDetector
from app.ml.ekyc.thresholds import (
    EAR_CLOSED_THRESHOLD,
    EAR_OPEN_THRESHOLD,
    LBP_VARIANCE_THRESHOLD,
    YAW_FRONTAL_DEGREES,
    YAW_TURN_DEGREES,
)

IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".bmp", ".webp")


def _load_images(folder: Path) -> list[tuple[str, "object"]]:
    """Đọc mọi ảnh trong thư mục, sắp theo tên file để giữ thứ tự thời gian."""
    if not folder.is_dir():
        sys.exit(f"Không tìm thấy thư mục: {folder}")

    items = []
    for path in sorted(folder.iterdir()):
        if path.suffix.lower() not in IMAGE_SUFFIXES:
            continue
        image = decode_image(path.read_bytes())
        if image is None:
            print(f"  bỏ qua (không giải mã được): {path.name}")
            continue
        items.append((path.name, image))

    if not items:
        sys.exit(f"Thư mục không có ảnh hợp lệ: {folder}")
    return items


def _describe(label: str, values: list[float]) -> None:
    ordered = sorted(values)
    median = ordered[len(ordered) // 2]
    print(
        f"  {label:6s} n={len(values):3d}  min={min(values):9.2f}  "
        f"median={median:9.2f}  max={max(values):9.2f}"
    )


def _best_threshold(live: list[float], spoof: list[float]) -> tuple[float, int]:
    """Chọn ngưỡng cho ít lỗi phân loại nhất.

    Duyệt mọi giá trị quan sát được làm ứng viên; ngưỡng tốt nhất là ngưỡng có
    tổng (ảnh thật bị từ chối + ảnh giả được chấp nhận) nhỏ nhất. Khi hoà, lấy
    ngưỡng nằm giữa hai nhóm để có biên an toàn.
    """
    candidates = sorted(set(live + spoof))
    best, best_errors = candidates[0], len(live) + len(spoof)

    for threshold in candidates:
        errors = sum(1 for v in live if v <= threshold) + sum(
            1 for v in spoof if v > threshold
        )
        if errors < best_errors:
            best, best_errors = threshold, errors

    return best, best_errors


def calibrate_lbp(live_dir: Path, spoof_dir: Path) -> None:
    detector = LivenessDetector()

    print(f"Đọc ảnh chụp trực tiếp từ {live_dir}")
    live = [detector.texture_variance(img) for _, img in _load_images(live_dir)]
    print(f"Đọc ảnh chụp lại từ màn hình/ảnh in từ {spoof_dir}")
    spoof = [detector.texture_variance(img) for _, img in _load_images(spoof_dir)]

    live = [v for v in live if v is not None]
    spoof = [v for v in spoof if v is not None]

    print("\nLBP variance:")
    _describe("live", live)
    _describe("spoof", spoof)

    threshold, errors = _best_threshold(live, spoof)
    total = len(live) + len(spoof)
    print(
        f"\nNgưỡng đề xuất: {threshold:.2f} "
        f"(sai {errors}/{total} ảnh; ngưỡng đang chạy: {LBP_VARIANCE_THRESHOLD:.2f})"
    )
    print(f"Đặt vào biến môi trường: EKYC_LBP_VARIANCE_THRESHOLD={threshold:.2f}")

    if min(live, default=0.0) <= max(spoof, default=0.0):
        print(
            "Cảnh báo: hai nhóm chồng lấn — không có ngưỡng nào tách sạch được. "
            "Cần thêm ảnh hoặc thêm đặc trưng (ví dụ phân tích tần số vân moiré)."
        )


def calibrate_pose(frames_dir: Path) -> None:
    detector = ActiveLivenessDetector()

    print(f"Đọc chuỗi frame từ {frames_dir}")
    items = _load_images(frames_dir)
    metrics = [detector.measure(image, i) for i, (_, image) in enumerate(items)]

    print(
        f"\nNgưỡng đang chạy: EAR nhắm<{EAR_CLOSED_THRESHOLD} mở>{EAR_OPEN_THRESHOLD}, "
        f"yaw quay>={YAW_TURN_DEGREES}°, chính diện<={YAW_FRONTAL_DEGREES}°"
    )
    print(f"\n{'#':>3}  {'file':30s}  {'EAR':>7}  {'yaw':>8}  {'độ nét':>9}")
    for (name, _), m in zip(items, metrics):
        ear = f"{m.ear:.4f}" if m.ear is not None else "   -   "
        yaw = f"{m.yaw:8.2f}" if m.yaw is not None else "    -   "
        print(f"{m.index:3d}  {name[:30]:30s}  {ear:>7}  {yaw}  {m.sharpness:9.1f}")

    blinks = detect_blinks(metrics)
    turns = detect_turns(metrics)
    print("\nSự kiện phát hiện được:")
    for event in sorted(blinks + turns, key=lambda e: e.complete_index):
        print(
            f"  {event.action:10s} frame {event.start_index}→{event.complete_index}, "
            f"đỉnh {event.peak}"
        )
    if not blinks and not turns:
        print("  (không có) — kiểm tra lại ánh sáng, tốc độ quay và ngưỡng.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    lbp = sub.add_parser("lbp", help="Dò ngưỡng LBP variance")
    lbp.add_argument(
        "--live", type=Path, required=True, help="Thư mục ảnh chụp trực tiếp"
    )
    lbp.add_argument("--spoof", type=Path, required=True, help="Thư mục ảnh chụp lại")

    pose = sub.add_parser("pose", help="In EAR/yaw của một chuỗi frame")
    pose.add_argument(
        "--frames", type=Path, required=True, help="Thư mục chứa các frame"
    )

    args = parser.parse_args()
    if args.command == "lbp":
        calibrate_lbp(args.live, args.spoof)
    else:
        calibrate_pose(args.frames)


if __name__ == "__main__":
    main()
