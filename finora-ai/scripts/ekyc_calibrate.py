"""Hiệu chỉnh ngưỡng liveness và so mặt trên dữ liệu tự thu (dữ liệu để NGOÀI repo).

Thư mục vào::

    <root>/sessions.json
    [
      {"id": "s01", "person": "p01", "video": "videos/s01.mp4", "platform": "android",
       "steps": [{"action": "FRONTAL", "hold_ms": 1500}, {"action": "TURN_LEFT", "hold_ms": 1300}, ...],
       "genuine": true, "attack": null},
      {"id": "a01", "person": "p01", "video": "videos/a01.mp4", "platform": "ios",
       "steps": [...], "genuine": false, "attack": "PRINT_A4"}
    ]
    <root>/portraits/<person>.jpg   ảnh mặt trước CCCD (hoặc ảnh chân dung) của từng người

Chạy::

    python scripts/ekyc_calibrate.py D:/ekyc-eval/liveness --target-fmr 0.001

In ra (không in PII, chỉ id phiên):

- phân bố độ rung khi giữ chính diện và biên độ đỉnh của từng động tác → gợi ý θ_r, θ_p
  (dưới phân vị 5% biên độ đỉnh, trên phân vị 99% độ rung);
- dấu của ``r`` khi quay trái theo từng nền tảng → gợi ý ``EKYC_LIVENESS_YAW_INVERT_*``;
- cosine nhỏ nhất giữa các khung chính diện trong cùng video thật → gợi ý ``T_same``
  (phân vị 1%);
- điểm so mặt cùng người / khác người → đường DET, EER, ``T_high`` theo FMR mục tiêu,
  ``T_low`` (phân vị 2% cặp cùng người); cỡ mẫu nhỏ thì báo cận trên 95% theo quy tắc 3/n;
- tỉ lệ quyết định: BPCER (người thật không PASS) và APCER theo từng loại tấn công.

Nên hiệu chỉnh trên một nửa số người và đánh giá trên nửa còn lại; đổi ngưỡng thì đặt
``EKYC_LIVENESS_THRESHOLDS_VERSION`` mới.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from dataclasses import replace
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.ml.ekyc import card_warp, face
from app.ml.ekyc import liveness_video as lv
from app.ml.ekyc.thresholds import doc_thresholds, liveness_thresholds

#: Bỏ phần đầu/cuối mỗi bước (người dùng đang chuyển tư thế).
TRANSITION_MARGIN_MS = 300.0


def percentile(values: list[float], q: float) -> float:
    return float(np.percentile(values, q)) if values else float("nan")


def describe(label: str, values: list[float]) -> None:
    if not values:
        print(f"  {label:28s} (không có dữ liệu)")
        return
    print(
        f"  {label:28s} n={len(values):4d}  p1={percentile(values, 1):7.3f}  "
        f"p5={percentile(values, 5):7.3f}  p50={percentile(values, 50):7.3f}  "
        f"p95={percentile(values, 95):7.3f}  p99={percentile(values, 99):7.3f}"
    )


def portrait_embedding(engine: face.FaceEngine, path: Path) -> np.ndarray | None:
    """Embedding chân dung: thử như ảnh thẻ (nắn + vùng trái), không được thì mặt lớn nhất."""
    image = cv2.imread(str(path))
    if image is None:
        return None
    th = doc_thresholds()
    warped = card_warp.warp_card(image, th.card_min_area_ratio, th.card_warp_width)
    result = face.extract_portrait(engine, warped.image, th)
    if result.found:
        return result.embedding
    faces = engine.detect(image, 0.6)
    return engine.embed(image, faces[0]) if faces else None


def step_windows(steps: list[lv.Step]) -> list[tuple[str, float, float]]:
    windows, elapsed = [], 0.0
    for step in steps:
        windows.append(
            (
                step.action,
                elapsed + TRANSITION_MARGIN_MS,
                elapsed + step.hold_ms - TRANSITION_MARGIN_MS,
            )
        )
        elapsed += step.hold_ms
    return windows


def analyze_session(engine, th, root: Path, session: dict) -> dict:
    steps = [lv.Step(s["action"], int(s["hold_ms"])) for s in session["steps"]]
    data = (root / session["video"]).read_bytes()
    meta, observations, store = lv.observe_video(data, steps, engine, th)
    samples = lv.smooth(lv.pose_samples(observations))
    baseline = lv.compute_baseline(samples, lv.first_frontal_window(steps))
    stats = {
        "jitter_r": [],
        "jitter_p": [],
        "peaks": defaultdict(list),
        "turn_left_sign": [],
        "min_pair_cos": None,
        "best": None,
        "meta": meta,
        "observations": observations,
        "store": store,
        "steps": steps,
    }
    if baseline is None:
        return stats
    r0, p0 = baseline
    for action, start, end in step_windows(steps):
        inside = [s for s in samples if start <= s.t_ms <= end]
        if not inside:
            continue
        if action == lv.FRONTAL:
            stats["jitter_r"] += [abs(s.r - r0) for s in inside]
            stats["jitter_p"] += [abs(s.p - p0) for s in inside]
        elif action in (lv.TURN_LEFT, lv.TURN_RIGHT):
            peak = max(inside, key=lambda s: abs(s.r - r0))
            stats["peaks"][action].append(abs(peak.r - r0))
            if action == lv.TURN_LEFT:
                stats["turn_left_sign"].append(float(np.sign(peak.r - r0)))
        else:
            stats["peaks"][action].append(max(abs(s.p - p0) for s in inside))

    frontal = []
    for sample in samples:
        nr, npitch = lv.normalized_deviation(sample, baseline, th)
        obs = observations[sample.index]
        if abs(nr) < 0.5 and abs(npitch) < 0.5 and obs.n_faces == 1:
            frontal.append(obs)
    chosen = lv.select_identity_frames(frontal, th.identity_frames)
    if chosen:
        embeddings = [engine.embed(store.get(o.frame_index), o.face) for o in chosen]
        if len(embeddings) >= 2:
            stats["min_pair_cos"] = min(
                face.cosine(embeddings[i], embeddings[j])
                for i in range(len(embeddings))
                for j in range(i + 1, len(embeddings))
            )
        best = max(range(len(chosen)), key=lambda i: chosen[i].sharpness or 0.0)
        stats["best"] = embeddings[best]
    return stats


def det_curve(
    genuine: list[float], impostor: list[float]
) -> list[tuple[float, float, float]]:
    """(ngưỡng, FMR, FNMR) trên mọi ngưỡng quan sát được."""
    points = []
    for threshold in sorted(set(genuine + impostor)):
        fmr = sum(1 for s in impostor if s >= threshold) / len(impostor)
        fnmr = sum(1 for s in genuine if s < threshold) / len(genuine)
        points.append((threshold, fmr, fnmr))
    return points


def report_face_match(
    genuine: list[float], impostor: list[float], target_fmr: float
) -> None:
    print("\nSo mặt (cosine SFace, khung chính diện nét nhất ↔ chân dung thẻ):")
    describe("cùng người", genuine)
    describe("khác người", impostor)
    if not genuine or not impostor:
        print("  Cần cả cặp cùng người và khác người để tính DET/EER.")
        return
    curve = det_curve(genuine, impostor)
    eer_point = min(curve, key=lambda p: abs(p[1] - p[2]))
    print(
        f"  EER ≈ {(eer_point[1] + eer_point[2]) / 2:.2%} tại ngưỡng {eer_point[0]:.3f}"
    )
    print("  DET (ngưỡng: FMR / FNMR):")
    step = max(1, len(curve) // 12)
    for threshold, fmr, fnmr in curve[::step]:
        print(f"    {threshold:7.3f}: {fmr:7.2%} / {fnmr:7.2%}")
    passing = [p for p in curve if p[1] <= target_fmr]
    if passing:
        t_high = passing[0][0]
        print(
            f"  Gợi ý EKYC_FACE_MATCH_T_HIGH={t_high:.3f} (FMR ≤ {target_fmr:.2%} trên mẫu)"
        )
    if max(impostor) < max(genuine):
        bound = min(1.0, 3.0 / len(impostor))
        print(
            f"  Không cặp khác người nào vượt ngưỡng cao nhất: cận trên 95% của FMR chỉ "
            f"≈ {bound:.2%} (quy tắc 3/n, n={len(impostor)}) — không chứng minh được FMR "
            f"nhỏ hơn mức này."
        )
    print(
        f"  Gợi ý EKYC_FACE_MATCH_T_LOW={percentile(genuine, 2):.3f} (phân vị 2% cùng người)"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "root", type=Path, help="thư mục có sessions.json, videos/, portraits/"
    )
    parser.add_argument("--target-fmr", type=float, default=0.001)
    args = parser.parse_args()

    sessions_file = args.root / "sessions.json"
    if not sessions_file.is_file():
        sys.exit(f"Không thấy {sessions_file}")
    if not face.models_present():
        sys.exit("Thiếu model mặt — chạy scripts/download_face_models.py trước.")
    sessions = json.loads(sessions_file.read_text(encoding="utf-8"))
    engine = face.get_engine()
    th = liveness_thresholds()
    print(
        f"Ngưỡng đang chạy: {th.version} θ_r={th.theta_r} θ_p={th.theta_p} "
        f"T_same={th.t_same} T_high={th.t_high} T_low={th.t_low}"
    )

    portraits = {}
    for path in sorted((args.root / "portraits").glob("*.*")):
        embedding = portrait_embedding(engine, path)
        if embedding is None:
            print(f"  bỏ chân dung {path.stem}: không thấy mặt")
            continue
        portraits[path.stem] = embedding

    jitter_r, jitter_p, peaks = [], [], defaultdict(list)
    signs: dict[str, list[float]] = defaultdict(list)
    same_cos, genuine_scores, impostor_scores = [], [], []
    decisions: dict[str, Counter] = defaultdict(Counter)
    for session in sessions:
        platform = session.get("platform") or "?"
        stats = analyze_session(engine, th, args.root, session)
        label = (
            "GENUINE" if session.get("genuine") else (session.get("attack") or "ATTACK")
        )
        if session.get("genuine"):
            jitter_r += stats["jitter_r"]
            jitter_p += stats["jitter_p"]
            for action, values in stats["peaks"].items():
                peaks[action] += values
            signs[platform] += stats["turn_left_sign"]
            if stats["min_pair_cos"] is not None:
                same_cos.append(stats["min_pair_cos"])
        best = stats["best"]
        if best is not None and session.get("genuine"):
            for person, embedding in portraits.items():
                score = face.cosine(best, embedding)
                (
                    genuine_scores if person == session["person"] else impostor_scores
                ).append(score)
        portrait = portraits.get(session["person"])
        if portrait is not None:
            platform_th = replace(
                th, yaw_invert=th.yaw_invert_for(session.get("platform"))
            )
            embed, render = lv._store_callbacks(engine, stats["store"])
            result = lv.analyze_observations(
                mode=lv.MODE_VIDEO,
                meta=stats["meta"],
                observations=stats["observations"],
                steps=stats["steps"],
                portrait_embedding=portrait,
                embed=embed,
                render_evidence=render,
                th=platform_th,
            )
            decisions[label][result["decision"]] += 1
            print(
                f"  {session['id']:8s} {platform:8s} {label:10s} → {result['decision']:7s} "
                f"{result['reasons']}"
            )

    print("\nĐộ rung khi giữ chính diện (|Δ| so với mức nền) và biên độ đỉnh động tác:")
    describe("rung |Δr| (FRONTAL)", jitter_r)
    describe("rung |Δp| (FRONTAL)", jitter_p)
    for action in (lv.TURN_LEFT, lv.TURN_RIGHT, lv.LOOK_UP, lv.LOOK_DOWN):
        describe(f"đỉnh {action}", peaks[action])
    for axis, jitter, actions in (
        ("r", jitter_r, (lv.TURN_LEFT, lv.TURN_RIGHT)),
        ("p", jitter_p, (lv.LOOK_UP, lv.LOOK_DOWN)),
    ):
        amplitude = [v for a in actions for v in peaks[a]]
        if jitter and amplitude:
            low, high = percentile(jitter, 99), percentile(amplitude, 5)
            verdict = (
                f"gợi ý θ_{axis}={(low + high) / 2:.3f}"
                if low < high
                else "CHỒNG LẤN — cần thêm dữ liệu hoặc yêu cầu động tác rõ hơn"
            )
            print(f"  θ_{axis}: rung p99={low:.3f}, đỉnh p5={high:.3f} → {verdict}")

    print("\nDấu của r khi quay trái (video không lật gương phải dương):")
    for platform, values in sorted(signs.items()):
        mean = float(np.mean(values)) if values else 0.0
        invert = "true" if mean < 0 else "false"
        print(
            f"  {platform:8s} n={len(values):3d} dấu TB={mean:+.2f} → "
            f"EKYC_LIVENESS_YAW_INVERT_{platform.upper()}={invert}"
        )

    print("\nCùng một người trong video (cosine nhỏ nhất giữa các khung chính diện):")
    describe("min_pair_cos (người thật)", same_cos)
    if same_cos:
        print(
            f"  Gợi ý EKYC_LIVENESS_T_SAME={percentile(same_cos, 1):.3f} (phân vị 1%)"
        )

    report_face_match(genuine_scores, impostor_scores, args.target_fmr)

    print("\nQuyết định theo loại phiên:")
    for label, counter in sorted(decisions.items()):
        total = sum(counter.values())
        passed = counter.get(lv.DECISION_PASS, 0)
        rate = "BPCER" if label == "GENUINE" else "APCER"
        value = (total - passed) / total if label == "GENUINE" else passed / total
        print(f"  {label:12s} n={total:3d} {dict(counter)}  {rate}={value:.1%}")


if __name__ == "__main__":
    main()
