"""Chạy bộ đánh giá Trợ lý FINORA (AI-CHAT-001 Task 19, spec mục 15).

Chạy (trong finora-platform/finora-ai):

    python scripts/run_assistant_eval.py --config template
    python scripts/run_assistant_eval.py --config groq_gptoss120b --repeats 1 --max-llm-requests 70
    python scripts/run_assistant_eval.py --config groq_gptoss120b --repeats 1 --max-llm-requests 70 --resume
    python scripts/run_assistant_eval.py --config gemini_gemma31b --repeats 3 --allow-public-provider-for-fake-data
    python scripts/run_assistant_eval.py --tong-hop --xuat-bao-cao ../../hinh-bao-cao

Mỗi lần chạy đưa 90 câu trong ``tests/assistant_eval/cases.yaml`` qua orchestrator thật, với dữ liệu
service là fixture giả và đúng MỘT cấu hình LLM (ghim một nhà, không chuyển nhà). Kết quả từng câu
ghi vào CSV (mặc định ``scripts/out/eval-<config>.csv``, thư mục bị Git ignore từ Task 1), bảng chỉ
số in ra màn hình và ghi thêm file ``.md`` cạnh CSV.

Hạn mức miễn phí có hạn (spec 7.1), nên script ghi CSV sau MỖI câu và có ``--resume``: chạy tiếp
những câu chưa có hoặc bị giới hạn hạn mức ở lần trước. ``--max-llm-requests`` dừng trước khi vượt
số request định dùng trong ngày. Key chỉ đọc từ môi trường hoặc ``finora-ai/.env``, không bao giờ
in ra hay ghi vào CSV (bộ ghi chỉ lưu body request, không lưu header).

Mã thoát: 0 xong; 1 có rò PII/câu chèn lệnh/dữ liệu cấm ra payload LLM; 2 cấu hình sai.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import json
import logging
import os
import shutil
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

GOC = Path(__file__).resolve().parent.parent
# `tests/` không có __init__.py: chèn nó vào sys.path giống pytest để import `assistant_eval.harness`
# và `assistant.helpers` cùng một tên ở cả script lẫn test.
for _duong_dan in (GOC / "tests", GOC):
    if str(_duong_dan) not in sys.path:
        sys.path.insert(0, str(_duong_dan))

from assistant_eval.harness import (
    CAU_HINH,
    COT_CSV,
    TEN_CAU_HINH,
    EvalCase,
    KetQuaCa,
    MoiTruongChay,
    ThieuCauHinh,
    bang_markdown,
    chay_ca,
    dat_nguong,
    dung_moi_truong,
    load_cases,
    pct,
    theo_vai_tro,
    tinh_chi_so,
)

from app.services.assistant.llm.providers import PROVIDER_CATALOG

THU_MUC_KET_QUA = GOC / "scripts" / "out"
# Giây nghỉ giữa hai câu. Groq free 30 req/phút nhưng chỉ 8.000 token/phút, nên nghỉ dài nhất.
NGHI_MAC_DINH: dict[str, float] = {
    "template": 0.0, "ollama_local": 0.0, "cloudflare_gemma26b": 1.0, "groq_gptoss120b": 8.0,
    "gemini_gemma31b": 3.0,
}
# Mã màu cố định theo cấu hình: 5 ô đầu của bảng màu phân loại đã kiểm mù màu (thứ tự kề nhau đạt ngưỡng).
MAU_CAU_HINH: dict[str, str] = {
    "template": "#2a78d6", "cloudflare_gemma26b": "#eb6834", "groq_gptoss120b": "#1baf7a",
    "gemini_gemma31b": "#eda100", "ollama_local": "#e87ba4",
}


def nap_env(path: Path) -> dict[str, str]:
    """Gộp biến môi trường với ``finora-ai/.env`` (biến đã có trong môi trường được ưu tiên)."""
    env = dict(os.environ)
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                env.setdefault(key.strip(), value.strip())
    return env


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Bộ đánh giá Trợ lý FINORA (90 câu, dữ liệu giả)")
    parser.add_argument("--config", choices=CAU_HINH, help="Cấu hình cần đo")
    parser.add_argument("--repeats", type=int, default=1, help="Số lần chạy lại cả bộ (đo độ dao động)")
    parser.add_argument("--out", type=Path, default=None, help="File CSV; mặc định scripts/out/eval-<config>.csv")
    parser.add_argument("--resume", action="store_true",
                        help="Giữ kết quả cũ trong CSV, chỉ chạy câu chưa có hoặc bị giới hạn hạn mức")
    parser.add_argument("--ids", default="", help="Chỉ chạy các id, cách nhau dấu phẩy")
    parser.add_argument("--roles", default="", help="Chỉ chạy vai trò: BORROWER,INVESTOR,ADMIN")
    parser.add_argument("--groups", default="", help="Chỉ chạy nhóm, ví dụ TU_CHOI,BAO_MAT")
    parser.add_argument("--pause", type=float, default=None, help="Giây nghỉ giữa hai câu (mặc định theo nhà)")
    parser.add_argument("--max-llm-requests", type=int, default=None,
                        help="Không bắt đầu câu mới khi số request LLM đã gửi trong lần chạy này đạt mức này")
    parser.add_argument("--stop-after-limited", type=int, default=3,
                        help="Dừng sau từng ấy câu liên tiếp bị giới hạn hạn mức (hết hạn mức ngày)")
    parser.add_argument("--allow-public-provider-for-fake-data", action="store_true",
                        help="Chỉ cho gemini_gemma31b: mở làn USER_DATA vì mọi fixture là dữ liệu giả")
    parser.add_argument("--tong-hop", action="store_true", help="Gộp mọi scripts/out/eval-<config>.csv")
    parser.add_argument("--thu-muc", type=Path, default=THU_MUC_KET_QUA, help="Thư mục CSV khi --tong-hop")
    parser.add_argument("--xuat-bao-cao", type=Path, default=None,
                        help="Khi --tong-hop: chép danh_gia_tro_ly.json và h_tro_ly.png sang thư mục này")
    args = parser.parse_args(argv)
    if not args.tong_hop and not args.config:
        parser.error("cần --config hoặc --tong-hop")
    if args.repeats < 1:
        parser.error("--repeats phải ≥ 1")
    return args


def doc_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def ghi_csv(path: Path, rows: Sequence[Mapping[str, str]]) -> None:
    """Ghi lại cả file (file nhỏ): dừng giữa chừng vẫn còn đủ các câu đã chạy."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tam = path.with_suffix(".tmp")
    with tam.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(COT_CSV))
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, "") for key in COT_CSV})
    tam.replace(path)


def loc_ca(cases: Sequence[EvalCase], ids: str, roles: str, groups: str) -> list[EvalCase]:
    def tap(text: str) -> set[str]:
        return {x.strip() for x in text.split(",") if x.strip()}

    chon_id, chon_role, chon_group = tap(ids), tap(roles), tap(groups)
    return [
        c for c in cases
        if (not chon_id or c.id in chon_id) and (not chon_role or c.role in chon_role)
        and (not chon_group or c.group in chon_group)
    ]


async def cho_han_muc(moi_truong: MoiTruongChay, ty_le: float = 0.6, toi_da_s: float = 120.0) -> None:
    """Đợi khi token đã dùng trong phút gần nhất vượt ``ty_le`` hạn mức token/phút của nhà.

    Không đợi thì sổ hạn mức của router từ chối trước (`HET_HAN_MUC`) và câu đó thành fallback vì
    tốc độ chạy đánh giá, không vì chất lượng model.
    """
    cau_hinh = moi_truong.cau_hinh
    if cau_hinh == "template":
        return
    tpm = PROVIDER_CATALOG[cau_hinh].tpm
    if not tpm:
        return
    budget = moi_truong.router.budget(cau_hinh)
    da_doi = 0.0
    while budget.tokens_last_minute() > tpm * ty_le and da_doi < toi_da_s:
        await asyncio.sleep(2)
        da_doi += 2


async def chay(args: argparse.Namespace, env: Mapping[str, str]) -> int:
    cau_hinh: str = args.config
    out: Path = args.out or THU_MUC_KET_QUA / f"eval-{cau_hinh}.csv"
    try:
        moi_truong = dung_moi_truong(
            cau_hinh, env, cho_phep_nha_public_voi_du_lieu_gia=args.allow_public_provider_for_fake_data,
        )
    except ThieuCauHinh as exc:
        print(f"Không chạy được: {exc}", file=sys.stderr)
        return 2
    if cau_hinh == "gemini_gemma31b" and not args.allow_public_provider_for_fake_data:
        print("Lưu ý: gemini_gemma31b chỉ được làn PUBLIC; câu có dữ liệu người dùng sẽ thành fallback. "
              "Thêm --allow-public-provider-for-fake-data để đo cả các câu đó (chỉ vì fixture là dữ liệu giả).")

    cases = loc_ca(load_cases(), args.ids, args.roles, args.groups)
    cu = {(r["lan"], r["case_id"]): r for r in doc_csv(out)} if args.resume else {}
    ket_qua: dict[tuple[str, str], dict[str, str]] = dict(cu)
    nghi = NGHI_MAC_DINH[cau_hinh] if args.pause is None else args.pause
    da_gui = 0
    lien_tiep_bi_gioi_han = 0
    dung_som = ""

    for lan in range(1, args.repeats + 1):
        for case in cases:
            khoa = (str(lan), case.id)
            if khoa in cu and cu[khoa].get("rate_limited") == "0":
                continue
            if args.max_llm_requests is not None and da_gui >= args.max_llm_requests:
                dung_som = f"đã dùng {da_gui} request LLM (--max-llm-requests {args.max_llm_requests})"
                break
            await cho_han_muc(moi_truong)
            kq: KetQuaCa = await chay_ca(case, moi_truong, lan)
            da_gui += kq.llm_requests
            ket_qua[khoa] = kq.to_row()
            ghi_csv(out, list(ket_qua.values()))
            trang_thai = "ĐẠT" if kq.passed else "chưa đạt"
            print(f"[{cau_hinh} lần {lan}] {case.id} {trang_thai} mode={kq.mode} tool={kq.tools_called or '-'} "
                  f"request={kq.llm_requests} {kq.latency_ms} ms{' LỖI: ' + kq.error if kq.error else ''}")
            lien_tiep_bi_gioi_han = lien_tiep_bi_gioi_han + 1 if kq.rate_limited else 0
            if lien_tiep_bi_gioi_han >= args.stop_after_limited:
                dung_som = f"{lien_tiep_bi_gioi_han} câu liên tiếp bị giới hạn hạn mức"
                break
            if nghi:
                await asyncio.sleep(nghi)
        if dung_som:
            break
    await moi_truong.router.aclose()

    rows = list(ket_qua.values())
    chi_so = tinh_chi_so(rows)
    md = bao_cao_markdown(cau_hinh, chi_so, rows)
    out.with_suffix(".md").write_text(md + "\n", encoding="utf-8")
    print()
    print(md)
    print(f"\nCSV: {out}")
    if dung_som:
        print(f"Dừng sớm: {dung_som}. Chạy lại sau khi hạn mức hồi (spec 7.1) với --resume.")
    ro_ri = chi_so["pii_leak_count"] + chi_so["injection_leak_count"] + chi_so["forbidden_leak_count"]
    return 1 if ro_ri else 0


def bao_cao_markdown(cau_hinh: str, chi_so: Mapping[str, Any], rows: Sequence[Mapping[str, str]]) -> str:
    dong = [f"## Đánh giá trợ lý — {TEN_CAU_HINH.get(cau_hinh, cau_hinh)}", "", bang_markdown({cau_hinh: chi_so}), ""]
    dong.append("Ngưỡng của spec: " + "; ".join(
        f"{key} {'ĐẠT' if ok else ('—' if ok is None else 'CHƯA ĐẠT')}" for key, ok in dat_nguong(chi_so).items()
    ))
    dong += ["", "| Vai trò | Lượt | Chọn đúng tool | Số liệu khớp | Từ chối đúng | Đạt cả ca |",
             "|---|---|---|---|---|---|"]
    for role, c in theo_vai_tro(rows).items():
        dong.append(f"| {role} | {c['so_luot']} | {pct(c['tool_accuracy'])} | {pct(c['number_match_rate'])} | "
                    f"{pct(c['refusal_correct_rate'])} | {pct(c['pass_rate'])} |")
    dong.append("")
    dong.append(f"Chế độ trả lời: {json.dumps(chi_so['mode'], ensure_ascii=False)}; "
                f"câu bị giới hạn hạn mức (không tính chất lượng): {chi_so['so_luot_bi_gioi_han']}; "
                f"câu lỗi: {chi_so['error_count']}.")
    chua_dat = [r["case_id"] for r in rows if r.get("passed") != "1" and r.get("rate_limited") != "1"]
    if chua_dat:
        dong.append(f"Ca chưa đạt: {', '.join(sorted(set(chua_dat)))}.")
    return "\n".join(dong)


def tong_hop(thu_muc: Path, xuat_bao_cao: Path | None) -> int:
    ket_qua: dict[str, dict[str, Any]] = {}
    for cau_hinh in CAU_HINH:
        rows = doc_csv(thu_muc / f"eval-{cau_hinh}.csv")
        if rows:
            ket_qua[cau_hinh] = tinh_chi_so(rows)
    if not ket_qua:
        print(f"Chưa có file eval-<config>.csv nào trong {thu_muc}", file=sys.stderr)
        return 2
    md = "## Tổng hợp đánh giá Trợ lý FINORA\n\n" + bang_markdown(ket_qua)
    (thu_muc / "eval-tong-hop.md").write_text(md + "\n", encoding="utf-8")
    du_lieu = [{"cau_hinh": k, "ten": TEN_CAU_HINH[k], **{x: y for x, y in v.items() if x != "mode"}}
               for k, v in ket_qua.items()]
    json_path = thu_muc / "eval-tong-hop.json"
    json_path.write_text(json.dumps(du_lieu, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    png_path = thu_muc / "eval-tong-hop.png"
    co_hinh = ve_bieu_do(ket_qua, png_path)
    print(md)
    if xuat_bao_cao is not None:
        xuat_bao_cao.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(json_path, xuat_bao_cao / "danh_gia_tro_ly.json")
        if co_hinh:
            shutil.copyfile(png_path, xuat_bao_cao / "h_tro_ly.png")
        print(f"Đã chép sang {xuat_bao_cao}")
    return 0


def ve_bieu_do(ket_qua: Mapping[str, Mapping[str, Any]], path: Path) -> bool:
    """Hai khung cạnh nhau, mỗi khung một trục: tỷ lệ chất lượng (%) và độ trễ (giây).

    Không gộp hai thang đo vào một trục kép. Màu đi theo cấu hình và cố định theo `MAU_CAU_HINH`
    (cấu hình vắng mặt không làm đổi màu cấu hình khác). Bảng số liệu đi kèm trong báo cáo nên cột
    không cần ghi số trên từng cột. matplotlib chỉ cần ở máy dev, không thêm vào requirements.txt.
    """
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("Bỏ qua biểu đồ: máy chưa có matplotlib (pip install matplotlib).")
        return False

    chi_tieu = [
        ("tool_accuracy", "Chọn đúng\ncông cụ"), ("number_match_rate", "Số liệu\nkhớp"),
        ("citation_correct_rate", "Căn cứ\nđúng"), ("refusal_correct_rate", "Từ chối\nđúng"),
        ("pass_rate", "Đạt\ncả ca"),
    ]
    ink, ink_phu, luoi = "#0b0b0b", "#52514e", "#e4e3df"
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 9, "text.color": ink,
                         "axes.labelcolor": ink_phu, "xtick.color": ink_phu, "ytick.color": ink_phu})
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4.2), gridspec_kw={"width_ratios": [3, 2]})
    ten = list(ket_qua)
    rong = 0.8 / len(ten)
    for i, cau_hinh in enumerate(ten):
        c = ket_qua[cau_hinh]
        xs = [j - 0.4 + rong * (i + 0.5) for j in range(len(chi_tieu))]
        ys = [(c[key] or 0) * 100 for key, _ in chi_tieu]
        ax1.bar(xs, ys, rong * 0.88, color=MAU_CAU_HINH[cau_hinh], label=TEN_CAU_HINH[cau_hinh], zorder=2)
        p50 = (c["p50_latency_ms"] or 0) / 1000
        p95 = (c["p95_latency_ms"] or 0) / 1000
        ax2.bar(i, p50, 0.55, color=MAU_CAU_HINH[cau_hinh], zorder=2)
        ax2.plot([i - 0.3, i + 0.3], [p95, p95], color=ink, linewidth=2, zorder=3)
    ax1.set_xticks(range(len(chi_tieu)), [nhan for _, nhan in chi_tieu])
    ax1.set_ylim(0, 105)
    ax1.set_ylabel("Tỷ lệ (%)")
    ax1.set_title("Chất lượng trả lời theo cấu hình", loc="left", fontsize=10)
    # Chú giải nằm dưới khung để không che cột thấp.
    ax1.legend(frameon=False, fontsize=8, loc="upper left", bbox_to_anchor=(0, -0.2), ncol=len(ten))
    ax2.set_xticks(range(len(ten)), [TEN_CAU_HINH[x].replace(" ", "\n", 1) for x in ten], fontsize=8)
    cao_nhat = max(((c["p95_latency_ms"] or 0) / 1000 for c in ket_qua.values()), default=0)
    ax2.set_ylim(0, max(1.0, cao_nhat * 1.15))
    ax2.set_ylabel("Giây")
    ax2.set_title("Độ trễ mỗi câu: cột = trung vị, vạch đen = P95", loc="left", fontsize=10)
    for ax in (ax1, ax2):
        ax.grid(axis="y", color=luoi, linewidth=0.8, zorder=0)
        for canh in ("top", "right"):
            ax.spines[canh].set_visible(False)
        ax.spines["left"].set_color(luoi)
        ax.spines["bottom"].set_color(luoi)
    fig.tight_layout()
    fig.savefig(path, dpi=200, facecolor="white")
    plt.close(fig)
    return True


def main(argv: Sequence[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    # Log INFO của httpx/router in mã hồ sơ giả ở mỗi request; chỉ giữ cảnh báo cho dễ đọc.
    logging.basicConfig(level=logging.WARNING)
    args = parse_args(argv)
    if args.tong_hop:
        return tong_hop(args.thu_muc, args.xuat_bao_cao)
    return asyncio.run(chay(args, nap_env(GOC / ".env")))


if __name__ == "__main__":
    sys.exit(main())
