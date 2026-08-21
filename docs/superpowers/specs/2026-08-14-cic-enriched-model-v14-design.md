# Design: Mở rộng CIC data + Retrain model v14

**Ngày:** 2026-08-14
**Phạm vi:** cic-service (response mở rộng) + finora-ai (client, data prep, features, retrain, predictor)
**Mục tiêu:** Nâng AUC từ ~0.607 (v13) lên ~0.67–0.70 bằng cách tận dụng dữ liệu tín dụng chi tiết CIC đã có sẵn trong DB.

---

## 1. Bối cảnh và vấn đề

### Hiện trạng

- **Model v13.0.0**: 22 features, AUC 0.607 (OOT), accuracy 64.2%.
- **cic-service**: Entity `HoSoTinDung` lưu 14 trường thô (5 nhóm: lịch sử trả nợ, dư nợ, thời gian quan hệ, tín dụng mới, cơ cấu). Endpoint `GET /api/v1/diem-tin-dung/{soCccd}` chỉ trả `diemCic` (1 con số 150–750).
- **finora-ai CicClient**: Chỉ parse `diemCic`, bỏ qua toàn bộ raw data.

### Nguyên nhân accuracy thấp

1. `cic_score` trong data huấn luyện là **giả lập** (FICO → linear map + noise σ=30 + 15% NaN).
2. v13 **xóa 5 features mạnh** so với v10: `delinq_2yrs`, `pub_rec`, `int_rate`, `term_months`, `effective_apr` → AUC giảm từ 0.652 xuống 0.607.
3. Một con số `cic_score` tổng hợp không thể thay thế 5+ features tín dụng chi tiết.

### Tham chiếu hiệu năng

| Version | Features | CIC/FICO | AUC (OOT) | KS | Recall |
|---------|----------|----------|-----------|------|--------|
| v7 (tham chiếu) | 35 | ✅ FICO gốc | 0.673 | 0.250 | 64.1% |
| v10 | 28 | ❌ | 0.652 | 0.220 | 38.1% |
| v13 (hiện tại) | 22 | ✅ synthetic | 0.607 | 0.157 | 47.6% |
| **v14 (mục tiêu)** | **~47** | **✅ mapped từ FICO** | **0.67–0.70** | **0.23–0.27** | **55–65%** |

---

## 2. Phần 1 — CIC Service: Mở rộng response

### Thay đổi

Khi `?chiTiet=true`, `DiemTinDungResponse` trả thêm field `hoSo` chứa 10 trường thô cần cho ML.

### DTO mới: `HoSoThoResponse`

```java
public record HoSoThoResponse(
    int soLanTreHan24Thang,
    int soThangTuLanTreGanNhat,  // -1 = chưa từng trễ
    int soNgayTreDaiNhat,
    Integer nhomNoCaoNhat,        // null = chưa có quan hệ tín dụng
    long tongDuNo,                // VND
    long duNoTheTinDung,          // VND
    long hanMucThe,               // VND
    int soLanTraCuu6Thang,
    int soHopDongDangCo,
    Integer soThangQuanHe         // null = chưa có quan hệ, tính từ ngayMoQuanHeDauTien
) {}
```

### Sửa `DiemTinDungResponse`

```java
public record DiemTinDungResponse(
    String soCccd,
    int diemCic,
    Integer phienBanHoSo,
    OffsetDateTime thoiDiemTraCuu,
    PhanRaResponse phanRa,
    HoSoThoResponse hoSo          // ← MỚI
) {
    public static DiemTinDungResponse gon(String soCccd, int diemCic, OffsetDateTime thoiDiem) {
        return new DiemTinDungResponse(soCccd, diemCic, null, thoiDiem, null, null);
    }
}
```

### Sửa `TraCuuService.traCuu()`

Khi `chiTiet=true`, map `HoSoTinDung` → `HoSoThoResponse`:
- `soThangQuanHe` = tính `ChronoUnit.MONTHS.between(ngayMoQuanHeDauTien, hieuLucTu)`, hoặc `null` nếu `ngayMoQuanHeDauTien == null`.
- Các field còn lại map thẳng từ entity.

### Không thay đổi

- Không sửa DB, không thêm migration.
- Không sửa `HoSoController`, `HoSoResponse` — endpoints hồ sơ thô giữ nguyên.
- Response bản gọn (`chiTiet=false`) giữ nguyên 3 fields.

---

## 3. Phần 2 — Data Preparation: Ánh xạ LendingClub → CIC

### Bảng ánh xạ

| # | Feature cho model | Cột LendingClub | Phép biến đổi |
|---|---|---|---|
| 1 | `cic_score` | `fico_score` | Linear map 300–850 → 150–750 + noise σ=30 (đã có từ v13) |
| 2 | `so_lan_tre_han` | `delinq_2yrs` | Dùng thẳng |
| 3 | `thang_tu_tre_gan_nhat` | `mths_since_last_delinq` | NaN → -1 (convention CIC: chưa từng trễ) |
| 4 | `tong_du_no` | `tot_cur_bal` | × hệ số VND theo năm |
| 5 | `du_no_the_tin_dung` | `revol_bal` | × hệ số VND |
| 6 | `ty_le_su_dung_the` | `revol_util` | Dùng thẳng (đã là %) |
| 7 | `so_lan_tra_cuu` | `inq_last_6mths` | Dùng thẳng |
| 8 | `so_hop_dong_dang_co` | `open_acc` | Dùng thẳng |
| 9 | `so_thang_quan_he` | `earliest_cr_line` | Số tháng từ `earliest_cr_line` đến `issue_d` |
| 10 | `nhom_no_cao_nhat` | `pub_rec` + `acc_now_delinq` | Heuristic: xem mục dưới |

### Heuristic `nhom_no_cao_nhat`

LendingClub không có khái niệm "nhóm nợ CIC". Ánh xạ gần đúng:

```python
def _map_nhom_no(pub_rec, acc_now_delinq):
    if acc_now_delinq > 0:
        return 4   # đang nợ xấu
    if pub_rec > 0:
        return 3   # có tiền sử nợ xấu/phá sản
    return 1       # bình thường
```

Đây là proxy — không chính xác 100% nhưng giữ đúng hướng: `pub_rec` / `acc_now_delinq` cao → nhóm nợ cao → rủi ro lớn.

### Chuẩn hóa VND

Áp dụng cho `tong_du_no`, `du_no_the_tin_dung` — cùng hệ số `VN_AVG[year] / US_AVG[year]` như đã dùng cho `annual_inc`, `loan_amnt`.

### NaN nhân tạo cho CIC features

Đặt ~15% NaN cho mỗi cột CIC trong training data (cùng seed `RANDOM_STATE=42`), mô phỏng trường hợp CIC không khả dụng. Cột nào LendingClub đã có NaN tự nhiên (ví dụ `mths_since_last_delinq`) thì tính tổng: NaN tự nhiên + 15% NaN thêm.

---

## 4. Phần 3 — Model Retraining: v14.0.0

### Bộ features đầy đủ (~47)

| Nhóm | Số lượng | Chi tiết |
|------|----------|----------|
| Từ v13 giữ nguyên | 9 numeric | person_age, emp_length_years, annual_inc, loan_amnt, dti, installment, cic_score, log_income, loan_to_income |
| CIC raw data (mới) | 9 | so_lan_tre_han, thang_tu_tre_gan_nhat, tong_du_no, du_no_the_tin_dung, ty_le_su_dung_the, so_lan_tra_cuu, so_hop_dong_dang_co, so_thang_quan_he, nhom_no_cao_nhat |
| Fineract (đưa lại) | 3 | int_rate, term_months, effective_apr |
| Dẫn xuất mới | 2 | log_du_no = log1p(tong_du_no), ty_le_du_no_thu_nhap = clip(tong_du_no / annual_inc, 0, 10) |
| Target-encoded | 4 | home_ownership_encoded, purpose_cat_encoded, verification_status_encoded, interest_method_encoded |
| Missing indicators | 16 | 5 cũ (person_age, emp_length_years, dti, installment, cic_score) + 9 CIC (tất cả 9 CIC features đều NaN khi CIC fail; `nhom_no_cao_nhat` và `so_thang_quan_he` cũng null khi người vay chưa có quan hệ tín dụng) + 2 Fineract (int_rate, term_months — optional trong request) |
| Age bins | 4 | age_under_25, age_25_to_39, age_40_to_59, age_over_60 |
| **Tổng** | **~47** | |

### Ràng buộc đơn điệu (monotonic constraints)

Features mà giá trị tăng → PD **phải không giảm**:

```python
DAC_TRUNG_DON_DIEU_TANG = [
    "installment",              # (đã có)
    "effective_apr",            # (đưa lại)
    "so_lan_tre_han",           # trễ hạn nhiều → rủi ro cao
    "tong_du_no",               # nợ nhiều → rủi ro cao
    "du_no_the_tin_dung",       # dư nợ thẻ cao → rủi ro cao
    "so_lan_tra_cuu",           # tra cứu nhiều → cần tiền gấp
    "ty_le_du_no_thu_nhap",     # gánh nặng nợ/thu nhập → rủi ro cao
]
```

### Siêu tham số

Giữ nguyên bộ hiện tại (đã tuned bằng RandomizedSearchCV 40 vòng). Chạy lại RandomizedSearchCV nếu AUC không đạt kỳ vọng — nhưng thử với tham số cũ trước, vì bộ features lớn hơn thường cải thiện mà không cần re-tune.

### Đánh giá

- Out-of-time: Train 2012 → Validate 2014 (1 fold)
- K-fold ngẫu nhiên: 5-fold StratifiedKFold
- So sánh với v7 tham chiếu và v13 hiện tại
- Lưu gói model tự chứa: `models/model_v14.0.0.pkl` + `.json`

---

## 5. Phần 4 — finora-ai Client + Predictor

### 5A. CicClient — mở rộng

Sửa `tra_diem_cic()`:
- URL: thêm `?chiTiet=true`
- Trả `dict | None` thay vì `int | None`
- Parse cả `diemCic` + `hoSo.*` (10 fields)
- Fail-open giữ nguyên: timeout/lỗi → `None` → predictor dùng median + missing indicators

```python
async def tra_diem_cic(self, so_cccd: str) -> dict | None:
    url = f"{self.base_url}/api/v1/diem-tin-dung/{so_cccd}?chiTiet=true"
    ...
    data = response.json()
    ho_so = data.get("hoSo") or {}
    return {
        "cic_score": data["diemCic"],
        "so_lan_tre_han": ho_so.get("soLanTreHan24Thang"),
        "thang_tu_tre_gan_nhat": ho_so.get("soThangTuLanTreGanNhat"),
        "tong_du_no": ho_so.get("tongDuNo"),
        "du_no_the_tin_dung": ho_so.get("duNoTheTinDung"),
        "han_muc_the": ho_so.get("hanMucThe"),
        "ty_le_su_dung_the": (
            ho_so["duNoTheTinDung"] / ho_so["hanMucThe"] * 100
            if ho_so.get("hanMucThe") and ho_so["hanMucThe"] > 0
            else None
        ),
        "so_lan_tra_cuu": ho_so.get("soLanTraCuu6Thang"),
        "so_hop_dong_dang_co": ho_so.get("soHopDongDangCo"),
        "so_thang_quan_he": ho_so.get("soThangQuanHe"),
        "nhom_no_cao_nhat": ho_so.get("nhomNoCaoNhat"),
    }
```

### 5B. CreditScoreRequest — thêm fields Fineract

```python
int_rate: float | None = Field(
    default=None, ge=0, le=100,
    description="Lãi suất danh nghĩa (%/năm) từ sản phẩm Fineract"
)
term_months: int | None = Field(
    default=None, ge=1, le=24,
    description="Kỳ hạn vay (tháng). Tối đa 24 theo NĐ 94/2025"
)
```

`effective_apr` tính từ `installment`, `loan_amnt`, `term_months` bằng hàm `tinh_effective_apr()` hiện có — không cần nhận từ request.

### 5C. BoDuDoan — sửa predictor

**`chuan_bi_dac_trung()`**: thêm 9 CIC fields + 3 Fineract fields vào row dict. Missing indicators tự tạo từ `COLUMNS_WITH_MISSING` mở rộng.

**`du_doan()`**: đổi signature `cic_score: int | None` → `cic_data: dict | None`, merge toàn bộ dict vào hồ sơ.

**`encode_features()`** trong `features.py`: thêm tính `log_du_no`, `ty_le_du_no_thu_nhap`, `effective_apr`.

### 5D. credit_router.py — sửa luồng

```python
cic_data = await cic_client.tra_diem_cic(ho_so.so_cccd)  # dict | None
ket_qua = bo_du_doan.du_doan(ho_so_dict, cic_data=cic_data)
```

### 5E. Cập nhật `PHIEN_BAN_MAC_DINH`

```python
PHIEN_BAN_MAC_DINH = "14.0.0"
```

---

## 6. Luồng end-to-end sau thay đổi

```
Hồ sơ vay (app/Fineract) + so_cccd
    │
    ▼
finora-ai POST /api/v1/ai/credit/score
    │  {annual_inc, loan_amnt, purpose, home_ownership,
    │   person_age, emp_length, dti, installment,
    │   int_rate, term_months, interest_method,     ← Fineract (đưa lại)
    │   so_cccd: "012345678901"}                    ← CCCD
    │
    ├──→ CicClient.tra_diem_cic("012345678901")
    │      GET cic-service/api/v1/diem-tin-dung/012345678901?chiTiet=true
    │      ← {
    │           diemCic: 620,
    │           hoSo: {
    │             soLanTreHan24Thang: 0,
    │             tongDuNo: 50000000,
    │             duNoTheTinDung: 5000000,
    │             hanMucThe: 20000000,
    │             soHopDongDangCo: 3,
    │             soLanTraCuu6Thang: 1,
    │             soThangQuanHe: 48,
    │             ...
    │           }
    │         }
    │
    ▼
BoDuDoan.du_doan(ho_so, cic_data)
    ├── chuan_bi_dac_trung() → ~42 features
    ├── XGBoost v14.0.0 → PD (probability of default)
    ├── Rule Engine 5C → risk_score (0–100)
    ├── evaluation_score = (1−PD)×100 × 0.6 + risk_score × 0.4
    └── → {
           pd_probability: 0.08,
           risk_score: 70,
           evaluation_score: 83.2,
           credit_grade: "A",
           suggested_limit: 100000000,
           decision: "APPROVED",
           model_version: "14.0.0"
         }
```

---

## 7. Rủi ro và giảm thiểu

| Rủi ro | Xác suất | Giảm thiểu |
|--------|----------|------------|
| AUC không đạt 0.67 do mapping LendingClub → CIC không chính xác | Trung bình | So sánh phân phối features trước/sau mapping. Nếu AUC < 0.65, chạy lại RandomizedSearchCV |
| Train/serve skew — preprocessing lúc train khác lúc predict | Thấp | Dùng chung hàm trong `preprocessing.py` + `features.py` (pattern đã có). Test unit khẳng định giá trị |
| CIC timeout → tất cả CIC features = NaN → chất lượng giảm | Thấp | 15% NaN trong training data dạy mô hình xử lý missing. Fail-open đã kiểm chứng ở v13 |
| Heuristic `nhom_no_cao_nhat` từ `pub_rec`/`acc_now_delinq` không chính xác | Trung bình | Chỉ dùng 3 giá trị {1, 3, 4}. Khi có CIC thật, giá trị sẽ chính xác hơn (1-5) |

---

## 8. Ngoài phạm vi

- Sửa rule engine — vẫn chỉ dùng hồ sơ tự khai, không dùng CIC data.
- Sửa finora-loan AiCreditScoringHttpClient — cần cập nhật sau khi AI schema ổn định.
- Thu thập dữ liệu vận hành FINORA thật để fine-tune — làm sau khi có người dùng.
- Re-tune hyperparameters — chỉ làm nếu AUC không đạt kỳ vọng.
