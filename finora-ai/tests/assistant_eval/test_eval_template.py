"""Chạy cả 90 câu ở cấu hình `template` (không gọi LLM, không gọi mạng) qua orchestrator thật.

Đây là chốt chặn cho bộ đánh giá: fixture đủ route, các câu có nút gợi ý (đường A) phải ĐẠT bằng câu
mẫu, và không câu nào làm lộ token/PII ra câu trả lời. Câu tự do ở chế độ này thường là fallback; tỷ
lệ của chúng chỉ là số tham chiếu, không phải điều kiện của test.
"""

from scripts.run_assistant_eval import doc_csv, main


def test_template_chay_du_90_cau_va_goi_y_deu_dat(tmp_path):
    out = tmp_path / "eval-template.csv"

    ma_thoat = main(["--config", "template", "--out", str(out)])

    rows = doc_csv(out)
    assert ma_thoat == 0
    assert len(rows) == 90
    assert all(r["mode"] in {"template", "fallback", "llm"} for r in rows)
    assert not [r["case_id"] for r in rows if "URL chưa có trong fixture" in r["error"]]
    assert all(r["llm_requests"] == "0" for r in rows)
    assert sum(int(r["pii_leaks"]) for r in rows) == 0
    goi_y = {"NV-TT-01", "NV-TT-02", "NV-TT-04", "NV-TT-06", "NV-DM-01", "NV-DM-02", "NV-DM-03", "NV-TC-03",
             "NDT-TT-01", "NDT-TT-02", "NDT-TT-04", "NDT-TT-05", "NDT-DM-01", "NDT-DM-03", "NDT-DM-04",
             "QT-TT-01", "QT-TT-02", "QT-TT-03", "QT-TT-04", "QT-CS-01"}
    chua_dat = sorted(r["case_id"] for r in rows if r["case_id"] in goi_y and r["passed"] != "1")
    assert chua_dat == []
    assert all(r["mode"] == "template" for r in rows if r["case_id"] in goi_y)
    assert (tmp_path / "eval-template.md").exists()


def test_tong_hop_ghi_md_json(tmp_path):
    assert main(["--config", "template", "--ids", "NV-TT-01,QT-TT-01", "--out",
                 str(tmp_path / "eval-template.csv")]) == 0

    assert main(["--tong-hop", "--thu-muc", str(tmp_path)]) == 0

    assert "Chỉ câu mẫu" in (tmp_path / "eval-tong-hop.md").read_text(encoding="utf-8")
    assert '"cau_hinh": "template"' in (tmp_path / "eval-tong-hop.json").read_text(encoding="utf-8")


def test_resume_chi_chay_cau_con_thieu(tmp_path, capsys):
    out = tmp_path / "eval-template.csv"
    main(["--config", "template", "--ids", "NV-TT-01", "--out", str(out)])
    capsys.readouterr()

    main(["--config", "template", "--ids", "NV-TT-01,NV-TT-02", "--out", str(out), "--resume"])

    in_ra = capsys.readouterr().out
    assert "NV-TT-02" in in_ra and "] NV-TT-01 " not in in_ra
    assert sorted(r["case_id"] for r in doc_csv(out)) == ["NV-TT-01", "NV-TT-02"]
