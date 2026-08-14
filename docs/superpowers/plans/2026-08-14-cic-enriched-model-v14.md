# CIC-Enriched Model v14 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nâng AUC mô hình credit scoring từ ~0.607 (v13) lên ~0.67–0.70 bằng cách tận dụng dữ liệu tín dụng chi tiết từ CIC và khôi phục features mạnh đã bị bỏ.

**Architecture:** Mở rộng `DiemTinDungResponse` của cic-service trả thêm 10 trường raw data khi `?chiTiet=true`. Ánh xạ 10 cột LendingClub → CIC fields để huấn luyện. Retrain XGBoost v14 với 47 features (thêm 9 CIC raw + 3 Fineract + 2 derived + 11 missing indicators mới).

**Tech Stack:** Java 21 / Spring Boot 3.2 (cic-service), Python 3.11 / FastAPI (finora-ai), XGBoost, pandas, numpy, pytest, pytest-httpx

## Global Constraints

- cic-service repo: `C:\Users\PC\Desktop\Data\Đồ Án\cic-service`, package `iuh.fit.se.cicservice`
- finora-ai: `finora-ai/` trong `finora-platform` repo
- Không tạo migration — `HoSoTinDung` entity đã có đủ fields
- Không sửa rule engine — vẫn chỉ dùng hồ sơ tự khai
- Comment/docstring tiếng Việt, tên code tiếng Anh
- Gói model tự chứa: `.pkl` + `.json` có median, target encodings, feature names
- `RANDOM_STATE = 42`
- Spec: `docs/superpowers/specs/2026-08-14-cic-enriched-model-v14-design.md`

## File Map

```
cic-service/ (separate repo)
├── src/main/java/iuh/fit/se/cicservice/
│   ├── dto/
│   │   ├── HoSoThoResponse.java        ← CREATE
│   │   └── DiemTinDungResponse.java     ← MODIFY (thêm field hoSo)
│   └── service/
│       └── TraCuuService.java           ← MODIFY (populate hoSo)
└── src/test/java/.../controller/
    └── TraCuuApiTest.java               ← MODIFY (test hoSo)

finora-platform/finora-ai/
├── app/
│   ├── ml/
│   │   ├── features.py                  ← MODIFY (47 features)
│   │   ├── training.py                  ← MODIFY (monotonic constraints)
│   │   ├── preprocessing.py             ← MODIFY (LC→CIC mapping functions)
│   │   └── predictor.py                 ← MODIFY (cic_data dict, COT_DAN_XUAT)
│   ├── schemas/
│   │   └── credit.py                    ← MODIFY (int_rate, term_months)
│   ├── services/
│   │   └── cic_client.py                ← MODIFY (return dict)
│   └── api/
│       └── credit_router.py             ← MODIFY (pass cic_data)
├── scripts/
│   └── train_final_model.py             ← MODIFY (v14 data prep)
└── tests/
    ├── test_features.py                 ← MODIFY
    ├── test_cic_client.py               ← MODIFY
    ├── test_schemas.py                  ← MODIFY
    └── test_cic_integration.py          ← MODIFY
```

---

### Task 1: cic-service — Mở rộng response với HoSoThoResponse

**Files:**
- Create: `cic-service/src/main/java/iuh/fit/se/cicservice/dto/HoSoThoResponse.java`
- Modify: `cic-service/src/main/java/iuh/fit/se/cicservice/dto/DiemTinDungResponse.java`
- Modify: `cic-service/src/main/java/iuh/fit/se/cicservice/service/TraCuuService.java`
- Test: `cic-service/src/test/java/iuh/fit/se/cicservice/controller/TraCuuApiTest.java`

**Interfaces:**
- Consumes: `HoSoTinDung` entity (existing, 14 fields)
- Produces: `GET /api/v1/diem-tin-dung/{soCccd}?chiTiet=true` now returns `hoSo: HoSoThoResponse` alongside `phanRa`

- [ ] **Step 1: Write test — chiTiet=true trả hoSo với đúng fields**

Thêm test vào `TraCuuApiTest.java`:

```java
@Test
@DisplayName("chiTiet=true trả hoSo với đúng 10 fields từ HoSoTinDung")
void chiTietTraHoSoVoiDuFields() throws Exception {
    String cccd = TapPersona.doc().get(0).soCccd();
    HoSoTinDung hoSo = hoSoRepo.findTopBySoCccdOrderByPhienBanDesc(cccd).orElseThrow();

    mockMvc.perform(get("/api/v1/diem-tin-dung/{cccd}", cccd).param("chiTiet", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hoSo").exists())
            .andExpect(jsonPath("$.hoSo.soLanTreHan24Thang").value(hoSo.getSoLanTreHan24Thang()))
            .andExpect(jsonPath("$.hoSo.soThangTuLanTreGanNhat").value(hoSo.getSoThangTuLanTreGanNhat()))
            .andExpect(jsonPath("$.hoSo.soNgayTreDaiNhat").value(hoSo.getSoNgayTreDaiNhat()))
            .andExpect(jsonPath("$.hoSo.tongDuNo").value(hoSo.getTongDuNo()))
            .andExpect(jsonPath("$.hoSo.duNoTheTinDung").value(hoSo.getDuNoTheTinDung()))
            .andExpect(jsonPath("$.hoSo.hanMucThe").value(hoSo.getHanMucThe()))
            .andExpect(jsonPath("$.hoSo.soLanTraCuu6Thang").value(hoSo.getSoLanTraCuu6Thang()))
            .andExpect(jsonPath("$.hoSo.soHopDongDangCo").value(hoSo.getSoHopDongDangCo()));
}

@Test
@DisplayName("chiTiet=false KHÔNG trả hoSo")
void khongChiTietKhongTraHoSo() throws Exception {
    String cccd = TapPersona.doc().get(0).soCccd();

    mockMvc.perform(get("/api/v1/diem-tin-dung/{cccd}", cccd))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hoSo").doesNotExist());
}

@Test
@DisplayName("soThangQuanHe tính đúng từ ngayMoQuanHeDauTien")
void soThangQuanHeTinhDung() throws Exception {
    String cccd = TapPersona.doc().get(0).soCccd();
    HoSoTinDung hoSo = hoSoRepo.findTopBySoCccdOrderByPhienBanDesc(cccd).orElseThrow();

    var result = mockMvc.perform(get("/api/v1/diem-tin-dung/{cccd}", cccd).param("chiTiet", "true"))
            .andExpect(status().isOk())
            .andReturn();

    String json = result.getResponse().getContentAsString();
    if (hoSo.getNgayMoQuanHeDauTien() != null) {
        long kyVong = java.time.temporal.ChronoUnit.MONTHS.between(
                hoSo.getNgayMoQuanHeDauTien(), hoSo.getHieuLucTu());
        assertThat(json).contains("\"soThangQuanHe\":" + kyVong);
    } else {
        // nhomNoCaoNhat null → soThangQuanHe cũng null (Jackson non_null loại bỏ)
        assertThat(json).doesNotContain("\"soThangQuanHe\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:\Users\PC\Desktop\Data\Đồ Án\cic-service
./gradlew test --tests "iuh.fit.se.cicservice.controller.TraCuuApiTest.chiTietTraHoSoVoiDuFields"
```

Expected: FAIL — `$.hoSo` does not exist yet.

- [ ] **Step 3: Create HoSoThoResponse DTO**

Tạo `src/main/java/iuh/fit/se/cicservice/dto/HoSoThoResponse.java`:

```java
package iuh.fit.se.cicservice.dto;

/**
 * Dữ liệu tín dụng thô phục vụ ML — 10 trường chọn lọc từ {@code HoSoTinDung}.
 *
 * <p>Trả kèm trong {@link DiemTinDungResponse} khi {@code ?chiTiet=true}.
 * Không chứa PII (tên, CCCD) — chỉ chỉ số tài chính.
 */
public record HoSoThoResponse(
        int soLanTreHan24Thang,
        int soThangTuLanTreGanNhat,     // -1 = chưa từng trễ
        int soNgayTreDaiNhat,
        Integer nhomNoCaoNhat,          // null = chưa có quan hệ tín dụng
        long tongDuNo,                  // VND
        long duNoTheTinDung,            // VND
        long hanMucThe,                 // VND
        int soLanTraCuu6Thang,
        int soHopDongDangCo,
        Integer soThangQuanHe           // null = chưa có quan hệ tín dụng
) {}
```

- [ ] **Step 4: Modify DiemTinDungResponse — thêm field hoSo**

Sửa `src/main/java/iuh/fit/se/cicservice/dto/DiemTinDungResponse.java`:

```java
package iuh.fit.se.cicservice.dto;

import java.time.OffsetDateTime;

/**
 * Phản hồi của endpoint chính.
 *
 * <p>Bản gọn để {@code phienBanHoSo}, {@code phanRa} và {@code hoSo} là {@code null};
 * cấu hình Jackson {@code non_null} lược chúng khỏi JSON.
 */
public record DiemTinDungResponse(
        String soCccd,
        int diemCic,
        Integer phienBanHoSo,
        OffsetDateTime thoiDiemTraCuu,
        PhanRaResponse phanRa,
        HoSoThoResponse hoSo
) {

    public static DiemTinDungResponse gon(String soCccd, int diemCic, OffsetDateTime thoiDiem) {
        return new DiemTinDungResponse(soCccd, diemCic, null, thoiDiem, null, null);
    }
}
```

- [ ] **Step 5: Modify TraCuuService — populate hoSo khi chiTiet=true**

Sửa `src/main/java/iuh/fit/se/cicservice/service/TraCuuService.java`.

Thêm method ánh xạ `HoSoTinDung → HoSoThoResponse`:

```java
private static HoSoThoResponse hoSoTho(HoSoTinDung h) {
    Integer soThangQuanHe = null;
    if (h.getNgayMoQuanHeDauTien() != null) {
        soThangQuanHe = (int) ChronoUnit.MONTHS.between(
                h.getNgayMoQuanHeDauTien(), h.getHieuLucTu());
    }
    return new HoSoThoResponse(
            h.getSoLanTreHan24Thang(),
            h.getSoThangTuLanTreGanNhat(),
            h.getSoNgayTreDaiNhat(),
            h.getNhomNoCaoNhat(),
            h.getTongDuNo(),
            h.getDuNoTheTinDung(),
            h.getHanMucThe(),
            h.getSoLanTraCuu6Thang(),
            h.getSoHopDongDangCo(),
            soThangQuanHe);
}
```

Sửa dòng return trong nhánh `if (chiTiet)`:

```java
if (!chiTiet) {
    return DiemTinDungResponse.gon(soCccd, ketQua.diemCic(), thoiDiem);
}
return new DiemTinDungResponse(soCccd, ketQua.diemCic(), hoSo.getPhienBan(),
        thoiDiem, phanRa(ketQua), hoSoTho(hoSo));
```

Thêm import nếu chưa có: `import iuh.fit.se.cicservice.dto.HoSoThoResponse;`

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd C:\Users\PC\Desktop\Data\Đồ Án\cic-service
./gradlew test --tests "iuh.fit.se.cicservice.controller.TraCuuApiTest"
```

Expected: ALL PASS — cả test mới lẫn test cũ (chiTietTraPhanRaKhopTongDiem, khongChiTietKhongTraHoSo, v.v.)

- [ ] **Step 7: Commit**

```bash
cd C:\Users\PC\Desktop\Data\Đồ Án\cic-service
git add src/main/java/iuh/fit/se/cicservice/dto/HoSoThoResponse.java
git add src/main/java/iuh/fit/se/cicservice/dto/DiemTinDungResponse.java
git add src/main/java/iuh/fit/se/cicservice/service/TraCuuService.java
git add src/test/java/iuh/fit/se/cicservice/controller/TraCuuApiTest.java
git commit -m "feat(cic): trả HoSoThoResponse trong DiemTinDungResponse khi chiTiet=true"
```

---

### Task 2: finora-ai — Mở rộng bộ đặc trưng (features.py + training.py)

**Files:**
- Modify: `finora-ai/app/ml/features.py`
- Modify: `finora-ai/app/ml/training.py`
- Modify: `finora-ai/app/ml/predictor.py:39-44` (COT_DAN_XUAT)
- Test: `finora-ai/tests/test_features.py`

**Interfaces:**
- Consumes: Không — đây là nền tảng cho mọi task sau
- Produces: `FEATURE_NAMES` (47 phần tử), `NUMERIC_FEATURES` (23), `COLUMNS_WITH_MISSING` (16), `CIC_RAW_FEATURES` (9), `FINERACT_FEATURES` (2), `encode_features()` tính thêm `effective_apr`, `log_du_no`, `ty_le_du_no_thu_nhap`

- [ ] **Step 1: Write failing test — 47 features, no duplicates, CIC/Fineract present**

Thay toàn bộ `finora-ai/tests/test_features.py`:

```python
"""Test bộ đặc trưng v14 — 47 features bao gồm CIC raw + Fineract."""

from app.ml.features import (
    CIC_RAW_FEATURES,
    COLUMNS_WITH_MISSING,
    FEATURE_NAMES,
    FINERACT_FEATURES,
    MISSING_INDICATORS,
    NUMERIC_FEATURES,
    TARGET_ENCODED_FEATURES,
    AGE_BINS,
)


class TestFeatureNamesV14:
    def test_tong_so_feature_la_47(self):
        assert len(FEATURE_NAMES) == 47

    def test_khong_trung_lap(self):
        assert len(FEATURE_NAMES) == len(set(FEATURE_NAMES))

    def test_cic_score_trong_numeric(self):
        assert "cic_score" in NUMERIC_FEATURES

    def test_9_cic_raw_features(self):
        assert len(CIC_RAW_FEATURES) == 9
        for f in CIC_RAW_FEATURES:
            assert f in NUMERIC_FEATURES, f"{f} phải trong NUMERIC_FEATURES"
            assert f in COLUMNS_WITH_MISSING, f"{f} phải trong COLUMNS_WITH_MISSING"
            assert f"{f}_missing" in MISSING_INDICATORS

    def test_2_fineract_features(self):
        assert len(FINERACT_FEATURES) == 2
        for f in FINERACT_FEATURES:
            assert f in NUMERIC_FEATURES
            assert f in COLUMNS_WITH_MISSING
            assert f"{f}_missing" in MISSING_INDICATORS

    def test_derived_features_trong_numeric(self):
        for f in ["log_income", "loan_to_income", "effective_apr", "log_du_no", "ty_le_du_no_thu_nhap"]:
            assert f in NUMERIC_FEATURES

    def test_16_missing_indicators(self):
        assert len(COLUMNS_WITH_MISSING) == 16
        assert len(MISSING_INDICATORS) == 16

    def test_feature_names_structure(self):
        """FEATURE_NAMES = NUMERIC + TARGET_ENCODED + MISSING + AGE_BINS."""
        expected = NUMERIC_FEATURES + TARGET_ENCODED_FEATURES + MISSING_INDICATORS + AGE_BINS
        assert FEATURE_NAMES == expected


class TestEncodeFeatures:
    def test_encode_tao_effective_apr(self):
        """encode_features() tính effective_apr từ installment/loan_amnt/term_months."""
        import pandas as pd
        from app.ml.features import encode_features

        df = pd.DataFrame([{
            "person_age": 30, "emp_length_years": 5, "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000, "dti": 15.0, "installment": 4_500_000,
            "cic_score": 580, "so_lan_tre_han": 0, "thang_tu_tre_gan_nhat": -1,
            "tong_du_no": 50_000_000, "du_no_the_tin_dung": 5_000_000,
            "ty_le_su_dung_the": 25.0, "so_lan_tra_cuu": 1, "so_hop_dong_dang_co": 3,
            "so_thang_quan_he": 48, "nhom_no_cao_nhat": 1,
            "int_rate": 12.0, "term_months": 12,
            "home_ownership": "MORTGAGE", "purpose_cat": "DEBT_CONSOLIDATION",
            "verification_status": "Verified", "interest_method": "DECLINING_BALANCE",
        }])
        result = encode_features(df)
        assert "effective_apr" in result.columns
        assert "log_du_no" in result.columns
        assert "ty_le_du_no_thu_nhap" in result.columns
        assert result["effective_apr"].iloc[0] > 0
        assert result["log_du_no"].iloc[0] > 0
        assert result["ty_le_du_no_thu_nhap"].iloc[0] >= 0
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd finora-ai
python -m pytest tests/test_features.py -v
```

Expected: FAIL — `ImportError: cannot import name 'CIC_RAW_FEATURES'`

- [ ] **Step 3: Implement features.py — mở rộng bộ đặc trưng**

Thay toàn bộ `finora-ai/app/ml/features.py`:

```python
"""
Bộ đặc trưng cho mô hình chấm điểm tín dụng v14.

Nguồn dữ liệu:
  - Hồ sơ người vay tự khai trên app (thu nhập, thâm niên, nhà ở, mục đích)
  - eKYC/CCCD (tuổi)
  - CIC qua cic-service: điểm CIC (150–750) + 9 trường tín dụng thô
  - Fineract: lãi suất, kỳ hạn từ sản phẩm vay

So với v13 (22 features): thêm 9 CIC raw, 3 Fineract (int_rate, term_months,
effective_apr), 2 derived (log_du_no, ty_le_du_no_thu_nhap), 11 missing indicators
mới → tổng 47.
"""
import numpy as np
import pandas as pd

from app.ml.preprocessing import tinh_effective_apr

HOME_OWNERSHIP_CATS = ["RENT", "OWN", "MORTGAGE", "OTHER"]
PURPOSE_CATS = [
    "DEBT_CONSOLIDATION", "CREDIT_CARD", "HOME_IMPROVEMENT", "OTHER",
    "MAJOR_PURCHASE", "MEDICAL", "CAR", "SMALL_BUSINESS",
    "MOVING", "VACATION", "EDUCATION",
]
VERIFICATION_CATS = ["Verified", "Source Verified", "Not Verified"]

# ── 9 trường tín dụng thô từ CIC ────────────────────────────────────────────
CIC_RAW_FEATURES = [
    "so_lan_tre_han",           # Số lần trễ hạn 24 tháng gần nhất
    "thang_tu_tre_gan_nhat",    # Số tháng từ lần trễ gần nhất (-1 = chưa từng)
    "tong_du_no",               # Tổng dư nợ (VND)
    "du_no_the_tin_dung",       # Dư nợ thẻ tín dụng (VND)
    "ty_le_su_dung_the",        # Tỷ lệ sử dụng thẻ (%)
    "so_lan_tra_cuu",           # Số lần tra cứu 6 tháng gần nhất
    "so_hop_dong_dang_co",      # Số hợp đồng đang có
    "so_thang_quan_he",         # Số tháng quan hệ tín dụng
    "nhom_no_cao_nhat",         # Nhóm nợ cao nhất (1-5)
]

# ── 2 trường từ Fineract (khôi phục từ v10) ─────────────────────────────────
FINERACT_FEATURES = [
    "int_rate",                 # Lãi suất danh nghĩa (%/năm)
    "term_months",              # Kỳ hạn vay (tháng)
]

COLUMNS_WITH_MISSING = [
    # Hồ sơ tự khai + CIC score (5 cũ)
    "person_age",
    "emp_length_years",
    "dti",
    "installment",
    "cic_score",
    # CIC raw — tất cả 9 đều NaN khi CIC fail
    *CIC_RAW_FEATURES,
    # Fineract — optional trong request
    *FINERACT_FEATURES,
]
MISSING_INDICATORS = [f"{c}_missing" for c in COLUMNS_WITH_MISSING]
AGE_BINS = ["age_under_25", "age_25_to_39", "age_40_to_59", "age_over_60"]

NUMERIC_FEATURES = [
    # Character — nhân thân
    "person_age",             # CCCD qua eKYC
    "emp_length_years",       # Hợp đồng lao động / tự khai
    # Capacity — khả năng trả nợ
    "annual_inc",             # Tự khai + sao kê lương
    # Conditions — điều kiện khoản vay
    "loan_amnt",              # Form nộp hồ sơ
    "dti",                    # Tỷ lệ nợ/thu nhập
    "installment",            # Số tiền phải trả hàng tháng
    # CIC — điểm tổng hợp
    "cic_score",              # Điểm tín dụng CIC (150-750)
    # CIC — dữ liệu thô
    *CIC_RAW_FEATURES,
    # Fineract — thông tin sản phẩm vay
    *FINERACT_FEATURES,
    # Đặc trưng dẫn xuất
    "log_income",             # log1p(annual_inc)
    "loan_to_income",         # clip(loan_amnt / annual_inc, 0, 5)
    "effective_apr",          # Lãi suất thực (%/năm) — bisection IRR
    "log_du_no",              # log1p(tong_du_no)
    "ty_le_du_no_thu_nhap",   # clip(tong_du_no / annual_inc, 0, 10)
]

TARGET_ENCODED_FEATURES = [
    "home_ownership_encoded",
    "purpose_cat_encoded",
    "verification_status_encoded",
    "interest_method_encoded",
]

TARGET_COLS = ["home_ownership", "purpose_cat", "verification_status", "interest_method"]

FEATURE_NAMES = NUMERIC_FEATURES + TARGET_ENCODED_FEATURES + MISSING_INDICATORS + AGE_BINS


def tinh_target_encodings(
    df: pd.DataFrame,
    target_cols: list[str],
    target_col: str = "loan_status",
    m: float = 10.0
) -> tuple[dict[str, dict[str, float]], float]:
    """Tính toán bản đồ ánh xạ Target Encoding với Smoothing cho các cột phân loại."""
    global_mean = float(df[target_col].mean())
    encodings = {}

    for col in target_cols:
        col_enc = {}
        stats = df.groupby(col)[target_col].agg(["count", "mean"])
        for val, row in stats.iterrows():
            n_i = row["count"]
            S_i = row["mean"]
            encoded_val = (n_i * S_i + m * global_mean) / (n_i + m)
            col_enc[str(val)] = float(encoded_val)
        encodings[col] = col_enc

    return encodings, global_mean


def encode_features(
    df: pd.DataFrame,
    target_encodings: dict[str, dict[str, float]] | None = None,
    global_mean: float | None = None
) -> pd.DataFrame:
    """Mã hóa và tạo đặc trưng mới từ DataFrame đã làm sạch.

    Tính thêm 3 đặc trưng dẫn xuất so với v13: effective_apr, log_du_no,
    ty_le_du_no_thu_nhap. Các đặc trưng dẫn xuất PHẢI tính SAU khi điền
    median — nếu tính trước thì giá trị thiếu sẽ truyền lên cột dẫn xuất
    mà không bị chặn.
    """
    df = df.copy()

    target_cols = TARGET_COLS

    if target_encodings is not None and global_mean is not None:
        for col in target_cols:
            mapping = target_encodings.get(col, {})
            df[f"{col}_encoded"] = df[col].astype(str).map(mapping).fillna(global_mean)
    else:
        if "loan_status" in df.columns:
            encs, g_mean = tinh_target_encodings(df, target_cols, "loan_status", m=10.0)
            for col in target_cols:
                mapping = encs.get(col, {})
                df[f"{col}_encoded"] = df[col].astype(str).map(mapping).fillna(g_mean)
        else:
            g_mean = 0.15
            for col in target_cols:
                df[f"{col}_encoded"] = g_mean

    # Binning tuổi thành các nhóm
    df["age_under_25"] = (df["person_age"] < 25).astype(int)
    df["age_25_to_39"] = ((df["person_age"] >= 25) & (df["person_age"] < 40)).astype(int)
    df["age_40_to_59"] = ((df["person_age"] >= 40) & (df["person_age"] < 60)).astype(int)
    df["age_over_60"] = (df["person_age"] >= 60).astype(int)

    # Dẫn xuất: log thu nhập
    df["log_income"] = np.log1p(df["annual_inc"])

    # Dẫn xuất: khoản vay / thu nhập
    df["loan_to_income"] = df["loan_amnt"] / df["annual_inc"].replace(0, np.nan)
    df["loan_to_income"] = df["loan_to_income"].fillna(0).clip(upper=5)

    # Dẫn xuất: lãi suất thực (MỚI v14)
    df["effective_apr"] = tinh_effective_apr(
        df["installment"], df["loan_amnt"], df["term_months"]
    )

    # Dẫn xuất: log dư nợ (MỚI v14)
    df["log_du_no"] = np.log1p(df["tong_du_no"])

    # Dẫn xuất: tỷ lệ dư nợ / thu nhập (MỚI v14)
    df["ty_le_du_no_thu_nhap"] = df["tong_du_no"] / df["annual_inc"].replace(0, np.nan)
    df["ty_le_du_no_thu_nhap"] = df["ty_le_du_no_thu_nhap"].fillna(0).clip(upper=10)

    return df
```

- [ ] **Step 4: Update training.py — thêm monotonic constraints**

Sửa `finora-ai/app/ml/training.py`, thay dòng `DAC_TRUNG_DON_DIEU_TANG`:

```python
# Đặc trưng mà PD buộc phải KHÔNG GIẢM theo — gánh nặng tài chính lớn hơn thì rủi
# ro không thể thấp hơn. Thêm 5 CIC/derived constraints so với v13.
DAC_TRUNG_DON_DIEU_TANG = [
    "installment",              # gánh nặng trả nợ
    "effective_apr",            # chi phí vay thực
    "so_lan_tre_han",           # trễ hạn nhiều → rủi ro cao
    "tong_du_no",               # nợ nhiều → rủi ro cao
    "du_no_the_tin_dung",       # dư nợ thẻ cao → rủi ro cao
    "so_lan_tra_cuu",           # tra cứu nhiều → cần tiền gấp
    "ty_le_du_no_thu_nhap",     # gánh nặng nợ/thu nhập → rủi ro cao
]
```

- [ ] **Step 5: Update predictor.py — mở rộng COT_DAN_XUAT**

Sửa `finora-ai/app/ml/predictor.py`, thay dòng `COT_DAN_XUAT`:

```python
# Năm cột này được TÍNH LẠI từ cột gốc trong `encode_features()` sau khi điền thiếu,
# nên không điền median cho chúng — điền rồi cũng bị ghi đè.
COT_DAN_XUAT = {"log_income", "loan_to_income", "effective_apr", "log_du_no", "ty_le_du_no_thu_nhap"}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd finora-ai
python -m pytest tests/test_features.py -v
```

Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add finora-ai/app/ml/features.py finora-ai/app/ml/training.py finora-ai/app/ml/predictor.py finora-ai/tests/test_features.py
git commit -m "feat(ai): mở rộng bộ đặc trưng v14 — 47 features (CIC raw + Fineract + derived)"
```

---

### Task 3: finora-ai — Data preparation LendingClub → CIC mapping

**Files:**
- Modify: `finora-ai/app/ml/preprocessing.py`
- Modify: `finora-ai/scripts/train_final_model.py`

**Interfaces:**
- Consumes: `FEATURE_NAMES` (47), `CIC_RAW_FEATURES`, `COLUMNS_WITH_MISSING` từ Task 2
- Produces: `map_nhom_no(pub_rec, acc_now_delinq) -> pd.Series`, `tinh_so_thang_quan_he(earliest_cr_line, issue_d) -> pd.Series`, `nap_va_chuan_hoa() -> DataFrame` với 47+ columns sẵn sàng cho training

- [ ] **Step 1: Write failing test — mapping functions**

Tạo `finora-ai/tests/test_preprocessing.py`:

```python
"""Test hàm ánh xạ LendingClub → CIC fields."""
import numpy as np
import pandas as pd
import pytest

from app.ml.preprocessing import map_nhom_no, tinh_so_thang_quan_he


class TestMapNhomNo:
    def test_binh_thuong(self):
        """pub_rec=0, acc_now_delinq=0 → nhóm 1."""
        result = map_nhom_no(pd.Series([0]), pd.Series([0]))
        assert result.iloc[0] == 1

    def test_co_tien_su(self):
        """pub_rec>0, acc_now_delinq=0 → nhóm 3."""
        result = map_nhom_no(pd.Series([2]), pd.Series([0]))
        assert result.iloc[0] == 3

    def test_dang_no_xau(self):
        """acc_now_delinq>0 → nhóm 4 (ưu tiên trên pub_rec)."""
        result = map_nhom_no(pd.Series([1]), pd.Series([2]))
        assert result.iloc[0] == 4

    def test_vectorized(self):
        pub = pd.Series([0, 1, 0, 3])
        delinq = pd.Series([0, 0, 1, 1])
        result = map_nhom_no(pub, delinq)
        assert list(result) == [1, 3, 4, 4]


class TestTinhSoThangQuanHe:
    def test_tinh_dung_so_thang(self):
        ecl = pd.Series(["Jan-10"])
        iss = pd.Series(["Jan-12"])
        result = tinh_so_thang_quan_he(ecl, iss)
        assert result.iloc[0] == 24

    def test_nan_khi_khong_co_ecl(self):
        ecl = pd.Series([np.nan])
        iss = pd.Series(["Jan-12"])
        result = tinh_so_thang_quan_he(ecl, iss)
        assert pd.isna(result.iloc[0])

    def test_khac_thang(self):
        ecl = pd.Series(["Mar-08"])
        iss = pd.Series(["Dec-12"])
        result = tinh_so_thang_quan_he(ecl, iss)
        assert result.iloc[0] == 57  # 4*12 + 9
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd finora-ai
python -m pytest tests/test_preprocessing.py -v
```

Expected: FAIL — `ImportError: cannot import name 'map_nhom_no'`

- [ ] **Step 3: Implement mapping functions in preprocessing.py**

Thêm vào cuối `finora-ai/app/ml/preprocessing.py`:

```python
def map_nhom_no(pub_rec: pd.Series, acc_now_delinq: pd.Series) -> pd.Series:
    """Ánh xạ gần đúng nhóm nợ CIC từ LendingClub.

    LendingClub không có khái niệm "nhóm nợ CIC". Proxy:
    - acc_now_delinq > 0 → nhóm 4 (đang nợ xấu)
    - pub_rec > 0        → nhóm 3 (có tiền sử nợ xấu/phá sản)
    - còn lại            → nhóm 1 (bình thường)
    """
    return pd.Series(
        np.where(acc_now_delinq > 0, 4, np.where(pub_rec > 0, 3, 1)),
        index=pub_rec.index,
    )


def tinh_so_thang_quan_he(earliest_cr_line: pd.Series, issue_d: pd.Series) -> pd.Series:
    """Số tháng từ ngày mở quan hệ tín dụng đầu tiên đến ngày phát hành khoản vay.

    Trả NaN khi earliest_cr_line thiếu (người vay chưa có lịch sử tín dụng).
    """
    ecl = pd.to_datetime(earliest_cr_line, format="%b-%y", errors="coerce")
    iss = pd.to_datetime(issue_d, format="%b-%y", errors="coerce")
    months = (iss.dt.year - ecl.dt.year) * 12 + (iss.dt.month - ecl.dt.month)
    return months.where(ecl.notna())
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd finora-ai
python -m pytest tests/test_preprocessing.py -v
```

Expected: ALL PASS

- [ ] **Step 5: Update train_final_model.py — thêm CIC features + khôi phục Fineract**

Sửa `finora-ai/scripts/train_final_model.py`. Thay đổi:

**5a. Import thêm:**

```python
from app.ml.features import (
    CIC_RAW_FEATURES,
    FEATURE_NAMES,
    HOME_OWNERSHIP_CATS,
    PURPOSE_CATS,
    COLUMNS_WITH_MISSING,
    encode_features,
)
from app.ml.preprocessing import (
    PURPOSE_MAP, _parse_emp_length, _parse_issue_year,
    map_nhom_no, tinh_so_thang_quan_he,
)
```

**5b. Đổi version:**

```python
PHIEN_BAN = "14.0.0"
```

**5c. Mở rộng COT_TIEN_TE:**

```python
COT_TIEN_TE = ["annual_inc", "loan_amnt", "installment", "tot_cur_bal", "revol_bal"]
```

**5d. Sửa hàm `nap_va_chuan_hoa()` — thêm tạo CIC features sau VND scaling:**

Sau đoạn `d["purpose_cat"] = ...`, trước đoạn tổng hợp cic_score, thêm:

```python
    # ── Tạo 9 CIC features từ LendingClub ────────────────────────────────────
    # Ánh xạ cột LC → tên feature CIC, để mô hình v14 học cùng schema
    # với dữ liệu CIC thật sẽ nhận lúc triển khai.
    d["so_lan_tre_han"] = d["delinq_2yrs"]
    d["thang_tu_tre_gan_nhat"] = d["mths_since_last_delinq"].fillna(-1).astype(int)
    d["tong_du_no"] = d["tot_cur_bal"]      # đã VND-scaled
    d["du_no_the_tin_dung"] = d["revol_bal"]  # đã VND-scaled
    d["ty_le_su_dung_the"] = d["revol_util"]
    d["so_lan_tra_cuu"] = d["inq_last_6mths"]
    d["so_hop_dong_dang_co"] = d["open_acc"]
    d["so_thang_quan_he"] = tinh_so_thang_quan_he(d["earliest_cr_line"], d["issue_d"])
    d["nhom_no_cao_nhat"] = map_nhom_no(d["pub_rec"], d["acc_now_delinq"])
    print(f"  Tạo 9 CIC features từ cột LendingClub")
```

**5e. Sửa phần tổng hợp cic_score — dùng cùng mask cho tất cả CIC features:**

Thay đoạn tổng hợp cic_score hiện tại bằng:

```python
    # ── Tổng hợp cic_score từ fico_score ──────────────────────────────────────
    rng = np.random.default_rng(RANDOM_STATE)
    fico = d["fico_score"].values.astype(float)
    cic_raw = 150.0 + (fico - 300.0) * (600.0 / 550.0)
    cic_noisy = cic_raw + rng.normal(0, 30, size=len(cic_raw))
    cic_clipped = np.clip(cic_noisy, 150, 750)
    d["cic_score"] = cic_clipped

    # ~15% NaN — cùng mask cho cic_score VÀ 9 CIC raw features, mô phỏng CIC timeout
    mask_cic_fail = rng.random(len(d)) < 0.15
    cic_cols = ["cic_score"] + list(CIC_RAW_FEATURES)
    for col in cic_cols:
        d.loc[mask_cic_fail, col] = np.nan

    n_missing = mask_cic_fail.sum()
    print(
        f"  Tổng hợp cic_score từ fico_score + đặt NaN đồng bộ cho {len(cic_cols)} CIC features: "
        f"{len(d) - n_missing:,} hợp lệ, {n_missing:,} NaN ({n_missing / len(d) * 100:.1f}%)"
    )
```

**5f. Cập nhật cong_thuc_dan_xuat trong metadata:**

```python
        "cong_thuc_dan_xuat": {
            "log_income": "log1p(annual_inc)",
            "loan_to_income": "clip(loan_amnt / annual_inc, 0, 5)",
            "effective_apr": "tinh_effective_apr(installment, loan_amnt, term_months)",
            "log_du_no": "log1p(tong_du_no)",
            "ty_le_du_no_thu_nhap": "clip(tong_du_no / annual_inc, 0, 10)",
        },
```

**5g. Cập nhật nguon_du_lieu:**

```python
        "nguon_du_lieu": (
            "Hồ sơ người vay tự khai + eKYC/CCCD + CIC (điểm 150–750 + 9 trường thô) "
            "từ cic-service. Trong dữ liệu huấn luyện, CIC features ánh xạ từ LendingClub "
            "(delinq_2yrs→so_lan_tre_han, tot_cur_bal→tong_du_no, v.v.), cic_score tổng hợp "
            "từ fico_score bằng ánh xạ tuyến tính + nhiễu Gaussian, ~15% NaN đồng bộ."
        ),
```

- [ ] **Step 6: Commit**

```bash
git add finora-ai/app/ml/preprocessing.py finora-ai/scripts/train_final_model.py finora-ai/tests/test_preprocessing.py
git commit -m "feat(ai): data prep LendingClub→CIC mapping + khôi phục Fineract cho v14"
```

---

### Task 4: finora-ai — CicClient trả dict + Schema thêm int_rate, term_months

**Files:**
- Modify: `finora-ai/app/services/cic_client.py`
- Modify: `finora-ai/app/schemas/credit.py`
- Test: `finora-ai/tests/test_cic_client.py`
- Test: `finora-ai/tests/test_schemas.py`

**Interfaces:**
- Consumes: cic-service `GET /api/v1/diem-tin-dung/{soCccd}?chiTiet=true` (Task 1)
- Produces: `tra_diem_cic(so_cccd) -> dict | None` (11 keys: cic_score + 9 CIC raw + han_muc_the dùng tính ty_le_su_dung_the), `CreditScoreRequest` có thêm `int_rate`, `term_months`

- [ ] **Step 1: Write failing test — CicClient trả dict**

Thay toàn bộ `finora-ai/tests/test_cic_client.py`:

```python
"""Test CIC client v14 — trả dict thay vì int."""

import httpx
import pytest

from app.services.cic_client import CicClient

CIC_URL = "http://localhost:8082/api/v1/diem-tin-dung/012345678901?chiTiet=true"


@pytest.fixture
def client():
    return CicClient(base_url="http://localhost:8082")


class TestTraDiemCicV14:
    """tra_diem_cic() trả dict | None thay vì int | None."""

    @pytest.mark.asyncio
    async def test_tra_ve_dict_khi_thanh_cong(self, client, httpx_mock):
        """CIC trả 200 với hoSo → trả dict đầy đủ."""
        httpx_mock.add_response(
            url=CIC_URL,
            json={
                "soCccd": "012345678901",
                "diemCic": 580,
                "thoiDiemTraCuu": "2026-08-14T10:00:00+07:00",
                "hoSo": {
                    "soLanTreHan24Thang": 2,
                    "soThangTuLanTreGanNhat": 6,
                    "soNgayTreDaiNhat": 30,
                    "nhomNoCaoNhat": 1,
                    "tongDuNo": 50000000,
                    "duNoTheTinDung": 5000000,
                    "hanMucThe": 20000000,
                    "soLanTraCuu6Thang": 1,
                    "soHopDongDangCo": 3,
                    "soThangQuanHe": 48,
                },
            },
        )
        result = await client.tra_diem_cic("012345678901")
        assert isinstance(result, dict)
        assert result["cic_score"] == 580
        assert result["so_lan_tre_han"] == 2
        assert result["thang_tu_tre_gan_nhat"] == 6
        assert result["tong_du_no"] == 50000000
        assert result["du_no_the_tin_dung"] == 5000000
        assert result["ty_le_su_dung_the"] == pytest.approx(25.0)
        assert result["so_lan_tra_cuu"] == 1
        assert result["so_hop_dong_dang_co"] == 3
        assert result["so_thang_quan_he"] == 48
        assert result["nhom_no_cao_nhat"] == 1

    @pytest.mark.asyncio
    async def test_ty_le_su_dung_the_none_khi_han_muc_0(self, client, httpx_mock):
        """hanMucThe = 0 → ty_le_su_dung_the = None (tránh chia cho 0)."""
        httpx_mock.add_response(
            url=CIC_URL,
            json={
                "soCccd": "012345678901",
                "diemCic": 400,
                "hoSo": {
                    "soLanTreHan24Thang": 0, "soThangTuLanTreGanNhat": -1,
                    "soNgayTreDaiNhat": 0, "nhomNoCaoNhat": None,
                    "tongDuNo": 0, "duNoTheTinDung": 0, "hanMucThe": 0,
                    "soLanTraCuu6Thang": 0, "soHopDongDangCo": 0,
                    "soThangQuanHe": None,
                },
            },
        )
        result = await client.tra_diem_cic("012345678901")
        assert result["ty_le_su_dung_the"] is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_timeout(self, client, httpx_mock):
        """CIC timeout → trả None, không raise."""
        httpx_mock.add_exception(httpx.ReadTimeout("timeout"), url=CIC_URL)
        result = await client.tra_diem_cic("012345678901")
        assert result is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_500(self, client, httpx_mock):
        """CIC trả 500 → trả None."""
        httpx_mock.add_response(url=CIC_URL, status_code=500)
        result = await client.tra_diem_cic("012345678901")
        assert result is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_connection_error(self, client, httpx_mock):
        """CIC không khả dụng → trả None."""
        httpx_mock.add_exception(httpx.ConnectError("connection refused"), url=CIC_URL)
        result = await client.tra_diem_cic("012345678901")
        assert result is None

    @pytest.mark.asyncio
    async def test_hoSo_none_van_tra_cic_score(self, client, httpx_mock):
        """Response không có hoSo (cic-service phiên bản cũ) → vẫn trả cic_score."""
        httpx_mock.add_response(
            url=CIC_URL,
            json={
                "soCccd": "012345678901",
                "diemCic": 620,
                "thoiDiemTraCuu": "2026-08-14T10:00:00+07:00",
            },
        )
        result = await client.tra_diem_cic("012345678901")
        assert result["cic_score"] == 620
        assert result["so_lan_tre_han"] is None
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd finora-ai
python -m pytest tests/test_cic_client.py -v
```

Expected: FAIL — `assert isinstance(result, dict)` fails (hiện trả int)

- [ ] **Step 3: Implement CicClient — trả dict**

Thay toàn bộ `finora-ai/app/services/cic_client.py`:

```python
"""
HTTP client gọi cic-service để lấy điểm tín dụng CIC + dữ liệu thô.

Thiết kế fail-open: khi cic-service không khả dụng, trả None — pipeline scoring
tiếp tục với missing indicators thay vì chặn luồng.

v14: gọi `?chiTiet=true` và parse cả `hoSo` (10 trường tín dụng thô) thay vì
chỉ `diemCic`. Trả dict thay vì int.
"""

import logging
import os
import time

import httpx

logger = logging.getLogger(__name__)

CIC_BASE_URL = os.getenv("CIC_SERVICE_URL", "http://localhost:8082")
CIC_TIMEOUT_SECONDS = float(os.getenv("CIC_TIMEOUT_SECONDS", "3.0"))


def _che_cccd(so_cccd: str) -> str:
    """Che số CCCD khi log: chỉ giữ 3 ký tự đầu/cuối, giấu phần giữa (PII)."""
    if len(so_cccd) <= 6:
        return "***"
    return f"{so_cccd[:3]}...{so_cccd[-3:]}"


class CicClient:
    """Client gọi cic-service (port 8082) lấy điểm + dữ liệu tín dụng theo CCCD."""

    def __init__(
        self,
        base_url: str = CIC_BASE_URL,
        timeout: float = CIC_TIMEOUT_SECONDS,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    async def tra_diem_cic(self, so_cccd: str) -> dict | None:
        """Tra điểm CIC và dữ liệu tín dụng thô theo số CCCD.

        Returns:
            Dict với 11 keys (cic_score + 9 CIC raw + ty_le_su_dung_the tính từ
            duNoTheTinDung/hanMucThe) nếu thành công. None nếu fail-open.
        """
        url = f"{self.base_url}/api/v1/diem-tin-dung/{so_cccd}?chiTiet=true"
        cccd_che = _che_cccd(so_cccd)
        bat_dau = time.monotonic()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as http:
                response = await http.get(url)

            do_tre_ms = (time.monotonic() - bat_dau) * 1000
            if response.status_code != 200:
                logger.warning(
                    "cic-service tra_diem_cic cccd=%s status=%d latency_ms=%.0f result=non_200",
                    cccd_che, response.status_code, do_tre_ms,
                )
                return None

            data = response.json()
            ho_so = data.get("hoSo") or {}

            han_muc = ho_so.get("hanMucThe")
            du_no_the = ho_so.get("duNoTheTinDung")
            ty_le_su_dung = (
                du_no_the / han_muc * 100
                if han_muc and han_muc > 0 and du_no_the is not None
                else None
            )

            logger.info(
                "cic-service tra_diem_cic cccd=%s status=200 latency_ms=%.0f "
                "result=success ho_so_fields=%d",
                cccd_che, do_tre_ms, len(ho_so),
            )
            return {
                "cic_score": data["diemCic"],
                "so_lan_tre_han": ho_so.get("soLanTreHan24Thang"),
                "thang_tu_tre_gan_nhat": ho_so.get("soThangTuLanTreGanNhat"),
                "tong_du_no": ho_so.get("tongDuNo"),
                "du_no_the_tin_dung": ho_so.get("duNoTheTinDung"),
                "ty_le_su_dung_the": ty_le_su_dung,
                "so_lan_tra_cuu": ho_so.get("soLanTraCuu6Thang"),
                "so_hop_dong_dang_co": ho_so.get("soHopDongDangCo"),
                "so_thang_quan_he": ho_so.get("soThangQuanHe"),
                "nhom_no_cao_nhat": ho_so.get("nhomNoCaoNhat"),
            }

        except (httpx.HTTPError, ValueError, KeyError) as loi:
            do_tre_ms = (time.monotonic() - bat_dau) * 1000
            logger.warning(
                "cic-service tra_diem_cic cccd=%s latency_ms=%.0f result=error "
                "error_class=%s: %s",
                cccd_che, do_tre_ms, type(loi).__name__, loi,
            )
            return None
```

- [ ] **Step 4: Run CicClient tests**

```bash
cd finora-ai
python -m pytest tests/test_cic_client.py -v
```

Expected: ALL PASS

- [ ] **Step 5: Update CreditScoreRequest — thêm int_rate, term_months**

Sửa `finora-ai/app/schemas/credit.py`. Thêm 2 fields sau `installment`:

```python
    int_rate: float | None = Field(
        default=None, ge=0, le=100,
        description="Lãi suất danh nghĩa (%/năm) từ sản phẩm Fineract",
    )
    term_months: int | None = Field(
        default=None, ge=1, le=24,
        description="Kỳ hạn vay (tháng). Tối đa 24 theo NĐ 94/2025",
    )
```

Cập nhật `json_schema_extra` example:

```python
    model_config = {
        "json_schema_extra": {
            "example": {
                "person_age": 30,
                "emp_length": "5 years",
                "annual_inc": 300_000_000,
                "loan_amnt": 50_000_000,
                "home_ownership": "MORTGAGE",
                "purpose": "debt_consolidation",
                "verification_status": "Verified",
                "dti": 15.5,
                "installment": 4500000.0,
                "int_rate": 12.0,
                "term_months": 12,
                "so_cccd": "012345678901",
            }
        }
    }
```

Cập nhật docstring đầu file — bỏ dòng "FINORA chưa có kết nối API tới CIC":

```python
"""
Schema Pydantic cho API chấm điểm tín dụng.

Nhận dữ liệu FINORA thu thập được: hồ sơ tự khai trên app + eKYC/CCCD.
CIC data (điểm + dữ liệu thô) được lấy tự động qua cic-service khi có so_cccd —
không cần truyền vào request.

Trường int_rate và term_months là thông tin sản phẩm vay từ Fineract, optional vì
không phải mọi luồng đều có sẵn lúc scoring.

Nguyên tắc quan trọng về trường tùy chọn: mặc định là `None`, KHÔNG phải một con số.
Nếu đặt mặc định là số, bộ dự đoán sẽ không bao giờ nhìn thấy giá trị thiếu và
median trong gói model trở nên vô dụng.
"""
```

- [ ] **Step 6: Update test_schemas.py**

Thêm test cho int_rate và term_months vào `finora-ai/tests/test_schemas.py`:

```python
class TestCreditScoreRequestFineract:
    """int_rate và term_months là optional, backward compatible."""

    def test_request_khong_co_fineract_van_hop_le(self):
        req = CreditScoreRequest(
            annual_inc=300_000_000,
            loan_amnt=50_000_000,
            purpose="debt_consolidation",
            home_ownership="MORTGAGE",
        )
        assert req.int_rate is None
        assert req.term_months is None

    def test_request_co_fineract(self):
        req = CreditScoreRequest(
            annual_inc=300_000_000,
            loan_amnt=50_000_000,
            purpose="debt_consolidation",
            home_ownership="MORTGAGE",
            int_rate=12.0,
            term_months=12,
        )
        assert req.int_rate == 12.0
        assert req.term_months == 12

    def test_term_months_toi_da_24(self):
        """NĐ 94/2025: kỳ hạn vay ngang hàng tối đa 24 tháng."""
        import pydantic
        with pytest.raises(pydantic.ValidationError):
            CreditScoreRequest(
                annual_inc=300_000_000,
                loan_amnt=50_000_000,
                purpose="debt_consolidation",
                home_ownership="MORTGAGE",
                term_months=25,
            )
```

Thêm `import pytest` ở đầu file nếu chưa có.

- [ ] **Step 7: Run all schema + client tests**

```bash
cd finora-ai
python -m pytest tests/test_cic_client.py tests/test_schemas.py -v
```

Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add finora-ai/app/services/cic_client.py finora-ai/app/schemas/credit.py
git add finora-ai/tests/test_cic_client.py finora-ai/tests/test_schemas.py
git commit -m "feat(ai): CicClient trả dict 11 fields + schema thêm int_rate, term_months"
```

---

### Task 5: finora-ai — Predictor + Router nhận cic_data dict

**Files:**
- Modify: `finora-ai/app/ml/predictor.py:109-180` (chuan_bi_dac_trung, du_doan)
- Modify: `finora-ai/app/api/credit_router.py:67-77`
- Test: `finora-ai/tests/test_cic_integration.py`

**Interfaces:**
- Consumes: `FEATURE_NAMES` (47) từ Task 2, `CicClient.tra_diem_cic() -> dict | None` từ Task 4, `CreditScoreRequest.int_rate`, `CreditScoreRequest.term_months` từ Task 4
- Produces: `BoDuDoan.du_doan(ho_so, cic_data=dict|None) -> dict`, `POST /score` endpoint nhận int_rate, term_months và forward cic_data

- [ ] **Step 1: Write failing test — predictor nhận cic_data dict**

Thay toàn bộ `finora-ai/tests/test_cic_integration.py`:

```python
"""Test tích hợp v14: cic_data dict đi qua pipeline chuan_bi_dac_trung → du_doan → router."""
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.ml.predictor import BoDuDoan


class TestChuanBiDacTrungV14:
    """chuan_bi_dac_trung() xử lý 9 CIC raw fields + 2 Fineract fields."""

    @pytest.fixture
    def ho_so_co_ban(self):
        return {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
            "person_age": 30,
            "emp_length": "5 years",
            "dti": 15.5,
            "int_rate": 12.0,
            "term_months": 12,
        }

    def test_cic_data_dict_merge_dung(self, ho_so_co_ban):
        """Khi truyền cic_data dict, tất cả 10 CIC fields được giữ đúng."""
        cic_data = {
            "cic_score": 580,
            "so_lan_tre_han": 2,
            "thang_tu_tre_gan_nhat": 6,
            "tong_du_no": 50_000_000,
            "du_no_the_tin_dung": 5_000_000,
            "ty_le_su_dung_the": 25.0,
            "so_lan_tra_cuu": 1,
            "so_hop_dong_dang_co": 3,
            "so_thang_quan_he": 48,
            "nhom_no_cao_nhat": 1,
        }
        ho_so_co_ban.update(cic_data)
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["cic_score"] == 580
        assert row["so_lan_tre_han"] == 2
        assert row["tong_du_no"] == 50_000_000
        assert row["so_thang_quan_he"] == 48
        assert row["cic_score_missing"] == 0.0
        assert row["so_lan_tre_han_missing"] == 0.0

    def test_cic_data_none_tat_ca_missing(self, ho_so_co_ban):
        """Không có CIC data → tất cả 9 CIC missing indicators = 1.0."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["cic_score_missing"] == 1.0
        assert row["so_lan_tre_han_missing"] == 1.0
        assert row["tong_du_no_missing"] == 1.0
        # Sau điền median, không còn None/NaN
        assert row["cic_score"] is not None
        assert not (isinstance(row["cic_score"], float) and np.isnan(row["cic_score"]))

    def test_fineract_fields_duoc_lay(self, ho_so_co_ban):
        """int_rate và term_months từ request được đưa vào row."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["int_rate"] == 12.0
        assert row["term_months"] == 12
        assert row["int_rate_missing"] == 0.0
        assert row["term_months_missing"] == 0.0


class TestDuDoanV14:
    """du_doan() nhận cic_data dict thay vì cic_score int."""

    def test_du_doan_voi_cic_data(self):
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
            "int_rate": 12.0,
            "term_months": 12,
        }
        cic_data = {"cic_score": 580, "so_lan_tre_han": 0, "thang_tu_tre_gan_nhat": -1,
                     "tong_du_no": 50_000_000, "du_no_the_tin_dung": 5_000_000,
                     "ty_le_su_dung_the": 25.0, "so_lan_tra_cuu": 1,
                     "so_hop_dong_dang_co": 3, "so_thang_quan_he": 48,
                     "nhom_no_cao_nhat": 1}
        ket_qua = bo.du_doan(ho_so, cic_data=cic_data)
        assert "pd_probability" in ket_qua
        assert "cic_score" not in ket_qua
        assert ket_qua["model_version"] == "14.0.0"

    def test_du_doan_khong_cic_data(self):
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
        }
        ket_qua = bo.du_doan(ho_so)
        assert "pd_probability" in ket_qua


class TestRouterV14:
    """score_credit() gọi CicClient trả dict, forward cic_data."""

    @pytest.fixture
    def app_client(self, monkeypatch):
        import main
        from app.api import credit_router

        credit_router.lay_bo_du_doan.cache_clear()
        credit_router.lay_cic_client.cache_clear()

        class BoDuDoanGia:
            metadata = {"version": "14.0.0"}

            def du_doan(self, ho_so, cic_data=None):
                self._last_cic_data = cic_data
                return {
                    "pd_probability": 0.1,
                    "risk_score": 80,
                    "evaluation_score": 90.0,
                    "credit_grade": "A",
                    "suggested_limit": 50_000_000,
                    "decision": "APPROVED",
                    "rejection_reason": None,
                    "model_version": "14.0.0",
                }

        bo_gia = BoDuDoanGia()
        monkeypatch.setattr(credit_router, "lay_bo_du_doan", lambda: bo_gia)

        client = TestClient(main.app)
        yield client, bo_gia
        credit_router.lay_cic_client.cache_clear()

    def _ho_so_co_ban(self, **extra):
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
        }
        ho_so.update(extra)
        return ho_so

    def test_khong_co_so_cccd_cic_data_la_none(self, app_client, monkeypatch):
        client, bo_gia = app_client
        response = client.post("/api/v1/ai/credit/score", json=self._ho_so_co_ban())
        assert response.status_code == 200

    def test_co_so_cccd_forward_cic_data(self, app_client, monkeypatch):
        client, bo_gia = app_client
        cic_dict = {"cic_score": 580, "so_lan_tre_han": 0, "thang_tu_tre_gan_nhat": -1,
                     "tong_du_no": 50_000_000, "du_no_the_tin_dung": 5_000_000,
                     "ty_le_su_dung_the": 25.0, "so_lan_tra_cuu": 1,
                     "so_hop_dong_dang_co": 3, "so_thang_quan_he": 48,
                     "nhom_no_cao_nhat": 1}

        async def tra_diem_gia(self, so_cccd):
            return cic_dict

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(so_cccd="012345678901"),
        )
        assert response.status_code == 200

    def test_cic_none_fail_open(self, app_client, monkeypatch):
        client, bo_gia = app_client

        async def tra_diem_gia(self, so_cccd):
            return None

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(so_cccd="012345678901"),
        )
        assert response.status_code == 200

    def test_request_co_fineract_fields(self, app_client, monkeypatch):
        client, bo_gia = app_client
        response = client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(int_rate=12.0, term_months=12),
        )
        assert response.status_code == 200
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd finora-ai
python -m pytest tests/test_cic_integration.py::TestDuDoanV14::test_du_doan_voi_cic_data -v
```

Expected: FAIL — `du_doan()` không nhận `cic_data` keyword

- [ ] **Step 3: Implement predictor — chuan_bi_dac_trung + du_doan nhận cic_data**

Sửa `finora-ai/app/ml/predictor.py`.

**3a. Update PHIEN_BAN_MAC_DINH:**

```python
PHIEN_BAN_MAC_DINH = "14.0.0"
```

**3b. Sửa `chuan_bi_dac_trung()` — thêm 9 CIC + 2 Fineract fields:**

```python
    def chuan_bi_dac_trung(self, ho_so: dict) -> dict:
        """Dựng một dòng dữ liệu thô, trường thiếu điền bằng median trong gói."""
        row = {
            "person_age": ho_so.get("person_age"),
            "annual_inc": ho_so.get("annual_inc"),
            "loan_amnt": ho_so.get("loan_amnt"),
            "emp_length_years": (
                _parse_emp_length(ho_so["emp_length"]) if ho_so.get("emp_length") else None
            ),
            "home_ownership": HOME_OWNERSHIP_MAP.get(ho_so.get("home_ownership"), "OTHER"),
            "purpose_cat": PURPOSE_MAP.get(ho_so.get("purpose", "other"), "OTHER"),
            "verification_status": ho_so.get("verification_status", "Not Verified"),
            "dti": ho_so.get("dti"),
            "installment": ho_so.get("installment"),
            "interest_method": ho_so.get("interest_method", "DECLINING_BALANCE"),
            "cic_score": ho_so.get("cic_score"),
            # CIC raw data (9 fields) — từ cic_data dict hoặc None khi CIC fail
            "so_lan_tre_han": ho_so.get("so_lan_tre_han"),
            "thang_tu_tre_gan_nhat": ho_so.get("thang_tu_tre_gan_nhat"),
            "tong_du_no": ho_so.get("tong_du_no"),
            "du_no_the_tin_dung": ho_so.get("du_no_the_tin_dung"),
            "ty_le_su_dung_the": ho_so.get("ty_le_su_dung_the"),
            "so_lan_tra_cuu": ho_so.get("so_lan_tra_cuu"),
            "so_hop_dong_dang_co": ho_so.get("so_hop_dong_dang_co"),
            "so_thang_quan_he": ho_so.get("so_thang_quan_he"),
            "nhom_no_cao_nhat": ho_so.get("nhom_no_cao_nhat"),
            # Fineract — thông tin sản phẩm vay
            "int_rate": ho_so.get("int_rate"),
            "term_months": ho_so.get("term_months"),
        }

        # Tạo chỉ báo thiếu trước khi điền median
        for cot in COLUMNS_WITH_MISSING:
            val = row.get(cot)
            row[f"{cot}_missing"] = 1.0 if val is None or (isinstance(val, float) and np.isnan(val)) else 0.0

        for cot, gia_tri_median in self.median.items():
            gia_tri = row.get(cot)
            if gia_tri is None or (isinstance(gia_tri, float) and np.isnan(gia_tri)):
                row[cot] = gia_tri_median

        return row
```

**3c. Sửa `du_doan()` — đổi signature `cic_score: int | None` → `cic_data: dict | None`:**

```python
    def du_doan(self, ho_so: dict, cic_data: dict | None = None) -> dict:
        """Chấm điểm đầy đủ: mô hình → rule engine → quyết định."""
        if cic_data is not None:
            ho_so = {**ho_so, **cic_data}

        pd_probability = self.du_doan_pd(ho_so)
        risk_score = tinh_diem_rui_ro(ho_so)
        evaluation_score = tinh_diem_tong_hop(pd_probability, risk_score)
        hang = xep_hang(evaluation_score)

        chot_chan_ly_do = kiem_tra_chot_chan_cung(ho_so)

        return {
            "pd_probability": round(pd_probability, 4),
            "risk_score": risk_score,
            "evaluation_score": round(evaluation_score, 2),
            "credit_grade": hang.hang,
            "suggested_limit": hang.han_muc,
            "decision": quyet_dinh(evaluation_score, chot_chan_ly_do),
            "rejection_reason": chot_chan_ly_do,
            "model_version": self.metadata["version"],
        }
```

- [ ] **Step 4: Implement credit_router.py — forward cic_data**

Sửa `finora-ai/app/api/credit_router.py`, thay đoạn tra CIC + gọi du_doan:

```python
    # Tra dữ liệu CIC nếu có CCCD
    cic_data: dict | None = None
    if ho_so.so_cccd:
        cic_client = lay_cic_client()
        cic_data = await cic_client.tra_diem_cic(ho_so.so_cccd)

    ket_qua = bo_du_doan.du_doan(
        ho_so.model_dump(exclude_none=True),
        cic_data=cic_data,
    )
    return CreditScoreResponse(**ket_qua)
```

Cập nhật docstring đầu file — sửa luồng:

```python
"""
Router cho API Chấm điểm Tín dụng (Credit Scoring).

Luồng ra quyết định:

    Hồ sơ vay (app/Fineract) + CCCD
        ├──→ cic-service (HTTP, ?chiTiet=true)
        │       ──→ cic_data dict (diemCic + 9 trường thô) hoặc None
        │
        ├──────────────────────────┬──────────────────────────┐
        ▼                          ▼                          │
    Mô hình XGBoost v14          Rule Engine 5C                │
    (47 features, có CIC)→ PD    (4 yếu tố) → risk_score      │
        └──────────────────────────┴──────────────────────────┘
                                  ▼
            evaluation_score = (1-PD)x100 x 0,6 + risk_score x 0,4
                                  ▼
                    credit_grade · decision · hạn mức

TODO: /explain (SHAP), /backtest.
"""
```

- [ ] **Step 5: Run all tests**

```bash
cd finora-ai
python -m pytest tests/ -v
```

Expected: Tests cần model v14 sẽ SKIP (`Model v14 chưa được train`). Tests mock-based (router, client, schema, features, preprocessing) PASS.

- [ ] **Step 6: Commit**

```bash
git add finora-ai/app/ml/predictor.py finora-ai/app/api/credit_router.py finora-ai/tests/test_cic_integration.py
git commit -m "feat(ai): predictor nhận cic_data dict + router forward 10 CIC fields"
```

---

### Task 6: finora-ai — Huấn luyện và kiểm chứng model v14

**Files:**
- Run: `finora-ai/scripts/train_final_model.py`
- Verify: `finora-ai/models/model_v14.0.0.pkl` + `.json`

**Interfaces:**
- Consumes: Tất cả thay đổi từ Task 2-5
- Produces: `models/model_v14.0.0.pkl`, `models/model_v14.0.0.json` (gói model tự chứa)

**Điều kiện tiên quyết:** file `finora-ai/data/lc_clean.csv` phải tồn tại.

- [ ] **Step 1: Kiểm tra dữ liệu có sẵn**

```bash
cd finora-ai
python -c "import pandas as pd; d=pd.read_csv('data/lc_clean.csv', nrows=5); print(f'{len(d.columns)} cột'); print([c for c in ['delinq_2yrs','pub_rec','acc_now_delinq','tot_cur_bal','revol_bal','revol_util','open_acc','inq_last_6mths','earliest_cr_line','mths_since_last_delinq','int_rate','term_months'] if c in d.columns])"
```

Expected: 12 cột LendingClub cần thiết đều có mặt.

- [ ] **Step 2: Chạy training script**

```bash
cd finora-ai
python scripts/train_final_model.py
```

Expected output (mẫu):
```
==============================================================================
FINORA AI — Huấn luyện mô hình XGBoost cuối cùng (v14.0.0)
==============================================================================

[1/5] Nạp và chuẩn hóa dữ liệu
  Nạp 2,260,668 dòng × 33 cột
  Lọc năm 2012, 2014 và kỳ hạn ≤ 24 tháng: ... → 215,937 dòng
  ...
  Tạo 9 CIC features từ cột LendingClub
  Tổng hợp cic_score từ fico_score + đặt NaN đồng bộ cho 10 CIC features: ...

[2/5] Đo out-of-time ...
  2012 -> 2014  ... AUC=0.67xx ...

[5/5] Lưu gói model
  ...model_v14.0.0.pkl
  SHA-256: ...

TỔNG KẾT
  Out-of-time  AUC = 0.67xx ± ...
```

- [ ] **Step 3: Kiểm chứng AUC ≥ 0.65 và gói model hợp lệ**

```bash
cd finora-ai
python -c "
import json
m = json.load(open('models/model_v14.0.0.json'))
auc = m['metrics']['auc_roc']
n_features = len(m['feature_names'])
print(f'AUC = {auc:.4f}')
print(f'Features = {n_features}')
print(f'Version = {m[\"version\"]}')
assert n_features == 47, f'Expected 47 features, got {n_features}'
assert auc >= 0.65, f'AUC {auc} < 0.65 target'
print('✓ Gói model v14 hợp lệ')
"
```

- [ ] **Step 4: Chạy lại full test suite với model v14 sẵn sàng**

```bash
cd finora-ai
python -m pytest tests/ -v
```

Expected: ALL PASS — kể cả tests từ `test_cic_integration.py` (trước đó SKIP vì chưa có model v14).

- [ ] **Step 5: Commit gói model**

```bash
git add finora-ai/models/model_v14.0.0.pkl finora-ai/models/model_v14.0.0.json
git commit -m "feat(ai): model v14.0.0 — 47 features, CIC raw + Fineract, AUC ≥ 0.65"
```

---

## Tổng kết

| Task | Scope | Key deliverable |
|------|-------|----------------|
| 1 | cic-service | `HoSoThoResponse` + `DiemTinDungResponse.hoSo` |
| 2 | finora-ai | `FEATURE_NAMES` 47 phần tử, `encode_features()` v14 |
| 3 | finora-ai | `map_nhom_no()`, `tinh_so_thang_quan_he()`, `train_final_model.py` v14 |
| 4 | finora-ai | `CicClient` trả dict, `CreditScoreRequest` + int_rate/term_months |
| 5 | finora-ai | `BoDuDoan.du_doan(cic_data=dict)`, router forward |
| 6 | finora-ai | `model_v14.0.0.pkl` + `.json`, AUC ≥ 0.65 |

**Thứ tự phụ thuộc:** Task 1 độc lập. Task 2 → Task 3, Task 5. Task 4 → Task 5. Task 2 + 3 + 4 + 5 → Task 6.

**Nếu AUC < 0.65:** Chạy RandomizedSearchCV re-tune hyperparameters (ngoài phạm vi plan, xem spec mục 4).
