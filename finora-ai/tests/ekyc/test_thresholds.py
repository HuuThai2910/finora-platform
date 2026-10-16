"""Ngưỡng/cờ eKYC đọc từ env, phiên bản ngưỡng tự đổi khi bị ghi đè."""

from app.ml.ekyc import thresholds


def test_mac_dinh_la_placeholder_cua_hop_dong():
    doc = thresholds.load_doc_thresholds({})
    live = thresholds.load_liveness_thresholds({})
    assert doc.version == "doc-2026-10-placeholder"
    assert live.version == "liveness-2026-10-placeholder"
    assert (doc.portrait_min_score, doc.portrait_min_ied) == (0.6, 40.0)
    assert (live.theta_r, live.theta_p, live.t_same, live.t_high, live.t_low) == (
        0.25,
        0.15,
        0.5,
        0.50,
        0.25,
    )
    assert (live.min_ied, live.min_single_face_ratio, live.min_brightness) == (
        40.0,
        0.9,
        0.25,
    )
    assert (live.min_fps, live.max_gap_ms, live.interval_tol_s) == (10.0, 250.0, 0.7)


def test_ghi_de_bang_env_doi_phien_ban():
    live = thresholds.load_liveness_thresholds({"EKYC_LIVENESS_THETA_R": "0.3"})
    assert live.theta_r == 0.3
    assert live.version.startswith("liveness-2026-10-placeholder+env-")
    same = thresholds.load_liveness_thresholds({"EKYC_LIVENESS_THETA_R": "0.3"})
    assert same.version == live.version  # cùng giá trị → cùng nhãn


def test_dat_ten_phien_ban_tuong_minh():
    doc = thresholds.load_doc_thresholds(
        {
            "EKYC_QUALITY_MIN_SHARPNESS": "80",
            "EKYC_DOC_THRESHOLDS_VERSION": "doc-2026-11-hc1",
        }
    )
    assert doc.min_sharpness == 80.0
    assert doc.version == "doc-2026-11-hc1"


def test_gia_tri_sai_bi_bo_qua():
    live = thresholds.load_liveness_thresholds(
        {"EKYC_LIVENESS_T_SAME": "abc", "EKYC_FACE_MATCH_T_HIGH": "nan"}
    )
    assert live.t_same == 0.5
    assert live.t_high == 0.5
    assert live.version == "liveness-2026-10-placeholder"


class TestYawInvertTheoNenTang:
    def test_mac_dinh_khong_dao(self):
        live = thresholds.load_liveness_thresholds({})
        assert not any(live.yaw_invert_for(p) for p in ("ios", "android", "web", None))

    def test_co_chung_ap_cho_moi_nen_tang_khi_khong_dat_rieng(self):
        live = thresholds.load_liveness_thresholds({"EKYC_LIVENESS_YAW_INVERT": "true"})
        assert live.yaw_invert_for("ios") and live.yaw_invert_for("android")
        assert live.yaw_invert_for(None)

    def test_co_rieng_ghi_de_co_chung(self):
        live = thresholds.load_liveness_thresholds(
            {
                "EKYC_LIVENESS_YAW_INVERT": "true",
                "EKYC_LIVENESS_YAW_INVERT_IOS": "false",
            }
        )
        assert not live.yaw_invert_for("ios")
        assert live.yaw_invert_for("android")
        only_android = thresholds.load_liveness_thresholds(
            {"EKYC_LIVENESS_YAW_INVERT_ANDROID": "1"}
        )
        assert only_android.yaw_invert_for("android")
        assert not only_android.yaw_invert_for("ios")
        assert not only_android.yaw_invert_for("web")


def test_co_nghiep_vu():
    cfg = thresholds.load_settings(
        {
            "EKYC_MAX_RETAKES": "3",
            "EKYC_ALLOW_SINGLE_DETERMINISTIC": "true",
            "EKYC_OPTIONAL_FIELDS": "yes",
            "EKYC_GEMINI_MODE": "OFF",
            "EKYC_GEMINI_TIMEOUT_MS": "8000",
        }
    )
    assert cfg.max_retakes == 3
    assert cfg.allow_single_deterministic and cfg.optional_fields
    assert cfg.gemini_mode == "off"
    assert cfg.gemini_timeout_ms == 8000


def test_co_nghiep_vu_mac_dinh_va_gia_tri_la():
    cfg = thresholds.load_settings({"EKYC_GEMINI_MODE": "always"})
    assert cfg.gemini_mode == "off"  # giá trị lạ → tắt AI cho an toàn
    default = thresholds.load_settings({})
    assert (default.max_retakes, default.gemini_mode) == (2, "fallback")
    assert not default.allow_single_deterministic and not default.optional_fields
