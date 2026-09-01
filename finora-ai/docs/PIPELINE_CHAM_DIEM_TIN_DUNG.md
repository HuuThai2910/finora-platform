# Pipeline chấm điểm tín dụng — từ dữ liệu thô đến quyết định

Tài liệu kỹ thuật cho người bảo trì `finora-ai`. Đi theo **trình tự nhân quả** của hệ
thống: dữ liệu thô → huấn luyện → gói model → chấm điểm hồ sơ thật.

Hai luồng huấn luyện và chấm điểm dùng chung `preprocessing.py` và `features.py`. Gói
model là hợp đồng nối chúng — mô tả ở phần II, đúng vị trí bản lề giữa hai phần.

---

## Sơ đồ tổng thể

```
┌─ PHẦN I — HUẤN LUYỆN ────────────  scripts/train_credit_model.py ─┐
│                                                                   │
│  data/lc_clean.csv                                                │
│        │                                                          │
│        ▼                                                          │
│  nap_va_chuan_hoa()                              [1/5]            │
│     ├─ parse issue_year, emp_length_years                         │
│     ├─ lọc issue_year >= 2009, term_months <= 24                  │
│     ├─ quy đổi tiền tệ USD → VND (hệ số k)                        │
│     ├─ tạo 9 CIC features từ cột LendingClub                      │
│     └─ tổng hợp cic_score + 15 % NaN đồng bộ                      │
│        │                                                          │
│        ├──► do_out_of_time()      [2/5]  3 fold trượt thời gian   │
│        ├──► do_kfold_ngau_nhien() [3/5]  5 fold StratifiedKFold   │
│        │        └─ do_mot_fold(): median & encoding CHỈ trên train│
│        ▼                                                          │
│  fit_xgboost(toàn bộ dữ liệu)                    [4/5]            │
└────────────────────────┬──────────────────────────────────────────┘
                         ▼                                [5/5]
┌─ PHẦN II — GÓI MODEL ─────────────────────────────────────────────┐
│  models/credit/model_v<PHIEN_BAN>.pkl  +  .json                   │
│  feature_names · median_dien_thieu · target_encodings · sha256    │
└────────────────────────┬──────────────────────────────────────────┘
                         ▼
┌─ PHẦN III — CHẤM ĐIỂM ─────────────  app/api/credit_router.py ────┐
│                                                                   │
│  POST /api/v1/ai/credit/score                                     │
│        │                                                          │
│        ▼                                                          │
│  CreditScoreRequest (13 field)                                    │
│        │                                                          │
│        ├─ có so_cccd? ─► CicClient ─► cic-service :8082           │
│        │                     ▼                                    │
│        │                dict 11 khóa  hoặc  None (fail-open)      │
│        ▼                                                          │
│  BoDuDoan.du_doan(ho_so, cic_data)                                │
│     ├─(1) chuan_bi_dac_trung()   → row thô, cờ missing, median    │
│     ├─(2) encode_features()      → encoding, age bucket, dẫn xuất │
│     ├─(3) model.predict_proba()  → PD                             │
│     ├─(4) tinh_diem_rui_ro()     → risk_score (rule engine 5C)    │
│     ├─(5) tinh_diem_tong_hop()   → evaluation_score               │
│     └─(6) chốt chặn + xep_hang() + quyet_dinh()                   │
│        ▼                                                          │
│  CreditScoreResponse                                              │
└───────────────────────────────────────────────────────────────────┘
```

---

# PHẦN I — HUẤN LUYỆN

Chạy: `python scripts/train_credit_model.py` (từ thư mục `finora-ai`).

## 1. Chuẩn bị dữ liệu — `nap_va_chuan_hoa()`

### 1.1 Tính lại hai cột dẫn xuất

`issue_year` và `emp_length_years` **không được lưu trong CSV** mà tính lại bằng đúng
hàm (`_parse_issue_year`, `_parse_emp_length`) mà luồng chấm điểm cũng dùng. Mục đích:
công thức không lệch giữa huấn luyện và triển khai.

### 1.2 Lọc

| Điều kiện | Lý do |
|---|---|
| `issue_year >= 2009` | Bỏ 2007–2008 (khủng hoảng tài chính, phân phối bất thường) |
| `term_months <= 24` | Trần kỳ hạn theo NĐ 94/2025 — dữ liệu ngoài khung pháp lý VN thì vô dụng |

### 1.3 Quy đổi tiền tệ

5 cột (`annual_inc`, `loan_amnt`, `installment`, `tot_cur_bal`, `revol_bal`) nhân hệ số
`HE_SO_K`:

```
        Thu nhập bình quân Việt Nam
k = ─────────────────────────────────
        Thu nhập bình quân Mỹ 2018
```

Mục đích: bảo toàn **vị trí thu nhập tương đối** giữa hai quốc gia, để ngưỡng nghiệp vụ
trong rule engine (120 triệu / 300 triệu VNĐ) có ý nghĩa.

### 1.4 Tạo 9 CIC features từ LendingClub

LendingClub không có dữ liệu CIC. Các cột được đổi tên theo schema CIC để mô hình học
cùng schema mà nó sẽ nhận lúc triển khai:

| Cột CIC | Nguồn LendingClub |
|---|---|
| `so_lan_tre_han` | `delinq_2yrs` |
| `thang_tu_tre_gan_nhat` | `mths_since_last_delinq` (NaN → `-1`) |
| `tong_du_no` | `tot_cur_bal` (đã VND-scaled) |
| `du_no_the_tin_dung` | `revol_bal` (đã VND-scaled) |
| `ty_le_su_dung_the` | `revol_util` |
| `so_lan_tra_cuu` | `inq_last_6mths` |
| `so_hop_dong_dang_co` | `open_acc` |
| `so_thang_quan_he` | `tinh_so_thang_quan_he(earliest_cr_line, issue_d)` |
| `nhom_no_cao_nhat` | `map_nhom_no(pub_rec, acc_now_delinq)` — proxy gần đúng |

`map_nhom_no` là ánh xạ **gần đúng**: `acc_now_delinq > 0` → nhóm 4, `pub_rec > 0` →
nhóm 3, còn lại → nhóm 1. LendingClub không có khái niệm nhóm nợ CIC.

### 1.5 Tổng hợp `cic_score`

```
cic_raw    = 150 + (fico_score − 300) × (600 / 550)      # FICO 300–850 → CIC 150–750
cic_noisy  = cic_raw + N(0, 30)                          # nhiễu Gaussian
cic_score  = clip(cic_noisy, 150, 750)
```

Sau đó **15 % số dòng bị đặt NaN đồng bộ** cho `cic_score` *và* cả 9 CIC features —
mô phỏng cic-service timeout, để mô hình học cách xử lý khi CIC không khả dụng.

> **Đây là dữ liệu proxy, không phải CIC thật.** Khi triển khai với điểm CIC thật, sức
> phân biệt có thể khác. Ghi chú này nằm trong `nguon_du_lieu` của metadata.

---

## 2. Median và target encoding — chỉ fit trên train của từng fold

Đây là chỗ dễ sai nhất trong toàn bộ pipeline.

Trong `do_mot_fold(train, val, ten)`:

```python
median = tinh_median(train)                                  # CHỈ train
target_encodings, global_mean = tinh_target_encodings(
    train, TARGET_COLS, "loan_status", m=10.0)               # CHỈ train

X_train, y_train = tao_ma_tran(train, median, target_encodings, global_mean)
X_val,   y_val   = tao_ma_tran(val,   median, target_encodings, global_mean)
```

**Nếu tính median trên toàn bộ dữ liệu rồi mới chia fold, tập validation rò rỉ sang tập
train** — chỉ số đánh giá sẽ đẹp hơn thực tế và không phát hiện được.

### Target encoding có làm mịn

Cho 4 cột trong `TARGET_COLS` (`home_ownership`, `purpose_cat`, `verification_status`,
`interest_method`):

```
                n_i × S_i + m × global_mean
encoded_i = ───────────────────────────────        m = 10.0
                      n_i + m
```

- `n_i` — số mẫu của giá trị *i*
- `S_i` — tỷ lệ vỡ nợ của giá trị *i*
- `m` — hệ số làm mịn

Mục đích: nhóm hiếm (`n_i` nhỏ) bị kéo về `global_mean` thay vì tin vào một tỷ lệ tính
từ vài chục mẫu. Chống overfitting và giảm số chiều so với one-hot.

### Thứ tự trong `tao_ma_tran()`

```
1. Tạo cờ *_missing        ← PHẢI trước, nếu không mọi cờ đều bằng 0
2. Điền median
3. encode_features()       ← dẫn xuất tính SAU khi điền
4. X = d[FEATURE_NAMES].values   ← reindex tường minh, đúng thứ tự cột
5. Nếu còn NaN → ValueError kèm tên cột
```

Bước 4 là lý do ràng buộc đơn điệu ở mục 3 áp đúng cột.

---

## 3. Ràng buộc đơn điệu

[`app/ml/credit/training.py`](../app/ml/credit/training.py) — `DAC_TRUNG_DON_DIEU_TANG`.

7 đặc trưng bị ép **PD không giảm** khi giá trị tăng:

| Đặc trưng | Quan hệ nghiệp vụ |
|---|---|
| `installment` | Trả hàng tháng nhiều hơn → gánh nặng nặng hơn |
| `effective_apr` | Chi phí vay cao hơn → rủi ro cao hơn |
| `dti` | Nợ trên thu nhập cao hơn → rủi ro cao hơn |
| `so_lan_tre_han` | Trễ nhiều hơn → rủi ro cao hơn |
| `tong_du_no` | Dư nợ lớn hơn → rủi ro cao hơn |
| `du_no_the_tin_dung` | Dư nợ thẻ lớn hơn → rủi ro cao hơn |
| `nhom_no_cao_nhat` | Nhóm nợ xấu hơn → rủi ro cao hơn |

### Vì sao cần

Không có ràng buộc, mô hình **học ngược dấu**: đo trên một bản không ràng buộc cho thấy
`installment` tăng 2,67 lần thì PD lại **giảm** 3,9 điểm phần trăm. Lý do: trong dữ liệu
huấn luyện, `installment` cao tương quan với kỳ hạn ngắn (nhóm ít vỡ nợ hơn), nên nó bị
học thành proxy cho "kỳ hạn ngắn = an toàn" thay vì thành gánh nặng.

Ràng buộc mã hóa **tri thức nghiệp vụ**, độc lập với chất lượng dữ liệu — nên nó vẫn
đúng kể cả khi CIC features là proxy.

### Cơ chế

`rang_buoc_don_dieu()` trả tuple 47 phần tử khớp thứ tự `FEATURE_NAMES`: `1` = ép tăng,
`0` = tự do. XGBoost áp **theo vị trí cột**, nên nó chỉ đúng khi `X` được dựng bằng
`d[FEATURE_NAMES]` (mục 2, bước 4).

> `cic_score` **không** nằm trong danh sách và không được thêm vào — điểm CIC cao thì
> rủi ro **thấp**, tức quan hệ giảm. Thêm nhầm sẽ ép mô hình học ngược hoàn toàn.

### Cái giá

Ràng buộc luôn làm AUC giảm nhẹ (đo được: khoảng 0,001) vì cấm mô hình khai thác một số
tương quan. Đánh đổi để giữ tính giải thích được — chuẩn mực trong scorecard tín dụng.

---

## 4. Đánh giá

### Hai cách đo, dùng khác nhau

| Cách | Cấu hình | Vai trò |
|---|---|---|
| **Out-of-time (OOT)** | 3 fold: train quá khứ → validate năm kế tiếp | **Số chính thức.** Sát thực tế: mô hình luôn dự đoán tương lai |
| K-fold ngẫu nhiên | 5 fold `StratifiedKFold`, trộn đều mọi năm | Đối chiếu. Số **cao hơn** OOT vì trộn năm làm bài toán dễ hơn |

Fold OOT: `2009-2012 → 2013`, `2009-2013 → 2014`, `2009-2014 → 2015`.

`metrics` trong metadata lấy **trung bình OOT**, không phải K-fold.

### Cân bằng lớp

`scale_pos_weight = số_âm / số_dương`, **không dùng SMOTE**. Thử nghiệm A/B trên chính
bộ dữ liệu này cho thấy SMOTE nén xác suất dự đoán xuống gần 0 (trung vị 0,125) khiến
recall rơi từ 64 % xuống 0,08 %, trong khi AUC gần như không đổi — AUC chỉ đo thứ tự xếp
hạng nên nó "mù" trước hiện tượng nén xác suất này.

Giá trị `scale_pos_weight` khác nhau giữa các fold (tỷ lệ vỡ nợ mỗi năm mỗi khác) nên
được ghi vào metadata để tái lập.

### Ngưỡng báo cáo

`NGUONG_BAO_CAO` chỉ dùng để cắt PD khi tính `recall` / `precision` / `f1` / `accuracy`.
**Đường ra quyết định không cắt ngưỡng** — `tinh_diem_tong_hop()` nhận PD liên tục, nên
đổi giá trị này không làm đổi hành vi chấm điểm.

Chọn bằng cách quét toàn dải ngưỡng trên fold OOT cuối và lấy điểm tối ưu F1.

### Đọc `accuracy` cùng `accuracy_baseline`

Dữ liệu tín dụng mất cân bằng, nên `accuracy` cao **không nói lên điều gì** nếu chỉ ngang
baseline (mô hình luôn đoán "không vỡ nợ"). `chenh_so_voi_baseline` âm là **bình thường
và có chủ đích**: mô hình hy sinh accuracy để bắt được ca vỡ nợ.

Chỉ số xếp hạng mô hình là **AUC / Gini / KS**.

---

# PHẦN II — GÓI MODEL

Đây là **hợp đồng** giữa hai luồng. Gói phải **tự chứa** — đủ để chấm một hồ sơ mới mà
không cần đọc lại script huấn luyện.

`models/credit/model_v<PHIEN_BAN>.pkl` + `.json`

## 5. Gói chứa gì và vì sao

| Khóa | Nội dung | Vì sao nằm trong gói |
|---|---|---|
| `feature_names` | 47 tên, đúng thứ tự cột | Luồng chấm điểm reindex theo nó; lệch là mô hình đọc nhầm cột |
| `median_dien_thieu` | Median 18 cột gốc | Phải đúng giá trị lúc train — hằng số hardcode sẽ gây train/serve skew |
| `target_encodings` + `global_mean` | Bảng ánh xạ 4 cột | Tính lại lúc chấm điểm là sai (không có nhãn) |
| `sha256` | Hash của `.pkl` | Phát hiện file bị thay hoặc hỏng |
| `version` | Phiên bản gói | Client kiểm tra tương thích |
| `cong_thuc_dan_xuat` | Công thức 5 đặc trưng | Tài liệu hóa, không dùng lúc chạy |
| `nguon_du_lieu` | Mô tả dữ liệu huấn luyện | Truy vết nguồn gốc |
| `metrics`, `chi_so` | Trung bình OOT + chi tiết từng fold | Đối chiếu khi nghi ngờ hồi quy |

## 6. `COT_DIEN_MEDIAN` — nguồn sự thật duy nhất

```python
COT_DAN_XUAT   = {"log_income", "loan_to_income", "effective_apr",
                  "log_du_no", "ty_le_du_no_thu_nhap"}
COT_DIEN_MEDIAN = [c for c in NUMERIC_FEATURES if c not in COT_DAN_XUAT]
```

Định nghĩa ở [`predictor.py`](../app/ml/credit/predictor.py), dùng chung cho cả script
huấn luyện lẫn predictor — để danh sách lúc train và lúc chấm điểm **không thể lệch nhau**.

Kết quả: **18 cột** — 16 cột trong `COLUMNS_WITH_MISSING` cộng `annual_inc` và
`loan_amnt` (hai cột bắt buộc nên không có cờ `*_missing`, nhưng vẫn cần median phòng
trường hợp dữ liệu huấn luyện khuyết).

Cột dẫn xuất bị loại vì chúng được **tính lại** trong `encode_features()` sau khi điền
thiếu; điền median cho chúng rồi cũng bị ghi đè.

## 7. Ba lớp kiểm tra khi `BoDuDoan.nap()`

1. **Thiếu `median_dien_thieu` / `target_encodings`** → `ValueError` (gói cũ, không tự chứa)
2. **`metadata["feature_names"] != FEATURE_NAMES`** → `ValueError`. Cột thứ *i* của `X`
   không còn là đặc trưng mô hình đã học
3. **SHA-256 của `.pkl` khác metadata** → `ValueError`. File đã bị thay hoặc hỏng

Cả ba đều chặn thẳng thay vì cảnh báo. Lý do: các lỗi này **không gây crash** nếu bỏ qua
— mô hình vẫn trả về một con số, chỉ là con số sai. Với hệ thống tín dụng đó là kiểu lỗi
tệ nhất.

---

# PHẦN III — CHẤM ĐIỂM

Điểm vào: [`app/api/credit_router.py`](../app/api/credit_router.py).

`BoDuDoan` và `CicClient` đều được bọc `@lru_cache(maxsize=1)` — nạp một lần cho cả vòng
đời tiến trình. Muốn nạp lại gói model sau khi train: khởi động lại service, hoặc gọi
`lay_bo_du_doan.cache_clear()`.

## 8. API nhận vào — 13 field

Định nghĩa: `CreditScoreRequest` trong [`app/schemas/credit.py`](../app/schemas/credit.py).

### Bắt buộc — thiếu thì HTTP 422

| Field | Kiểu | Ràng buộc |
|---|---|---|
| `annual_inc` | float | `> 0` — thu nhập năm (VNĐ) |
| `loan_amnt` | float | `>= 1` — số tiền vay (VNĐ) |
| `purpose` | enum | 11 giá trị: `debt_consolidation`, `credit_card`, … |
| `home_ownership` | enum | `RENT` / `OWN` / `MORTGAGE` / `OTHER` |

### Tùy chọn — bỏ trống thì điền median từ gói model

| Field | Ràng buộc | Ghi chú |
|---|---|---|
| `person_age` | `18–80` | từ CCCD qua eKYC |
| `emp_length` | chuỗi | `"10+ years"`, `"5 years"`, `"< 1 year"` |
| `verification_status` | enum | `Verified` / `Source Verified` / `Not Verified` |
| `dti` | `>= 0` | tỷ lệ nợ trên thu nhập (%) |
| `installment` | `>= 0` | tiền trả hàng tháng — **client tính và gửi sang**; bỏ trống thì điền median |
| `int_rate` | `0–100` | lãi suất danh nghĩa (%/năm) từ Fineract |
| `term_months` | `1–24` | trần 24 tháng theo NĐ 94/2025 |
| `interest_method` | enum | mặc định `DECLINING_BALANCE` |
| `so_cccd` | regex `^\d{12}$` | khóa tra CIC; **không có thì bỏ qua CIC** |

> **Mặc định phải là `None`, không phải một con số.** Nếu đặt mặc định là số, bộ dự
> đoán không bao giờ nhìn thấy giá trị thiếu và median trong gói model trở nên vô dụng
> — đồng thời cờ `*_missing` luôn bằng 0, mô hình mất một tín hiệu thật.

Pydantic mặc định `extra="ignore"`: field lạ **bị nuốt im lặng**, không báo lỗi. Đây là
lý do một client gửi sai tên field vẫn nhận HTTP 200 kèm kết quả sai.

## 9. 47 đặc trưng — nguồn và mục tiêu

Danh sách chuẩn: `FEATURE_NAMES` trong [`app/ml/credit/features.py`](../app/ml/credit/features.py).
**Thứ tự trong list này là thứ tự cột của ma trận `X`** — đổi thứ tự là phải train lại.

Client chỉ gửi 13 field; 34 đặc trưng còn lại do service tự sinh.

### 9.1 Hồ sơ tự khai + eKYC (6)

| Đặc trưng | Mục tiêu |
|---|---|
| `person_age` | Độ chín hành vi tài chính; cũng là nguồn của 4 age bucket |
| `emp_length_years` | Ổn định thu nhập — parse từ chuỗi `emp_length` |
| `annual_inc` | Năng lực trả nợ tổng thể |
| `loan_amnt` | Quy mô nghĩa vụ yêu cầu |
| `dti` | Áp lực nợ hiện hữu |
| `installment` | Gánh nặng dòng tiền hàng tháng |

### 9.2 CIC (10)

`cic_score` (thang 150–750) cộng 9 trường thô từ `cic-service`:

| Đặc trưng | Mục tiêu |
|---|---|
| `cic_score` | Điểm tín dụng tổng hợp |
| `so_lan_tre_han` | Số lần trễ hạn 24 tháng gần nhất |
| `thang_tu_tre_gan_nhat` | Độ tươi của vi phạm (`-1` = chưa từng trễ) |
| `tong_du_no` | Tổng dư nợ đang gánh (VNĐ) |
| `du_no_the_tin_dung` | Dư nợ thẻ tín dụng (VNĐ) |
| `ty_le_su_dung_the` | Tỷ lệ sử dụng hạn mức (%) — client tính từ `duNoTheTinDung / hanMucThe` |
| `so_lan_tra_cuu` | Số lần tra cứu 6 tháng — tín hiệu "đang tìm vốn gấp" |
| `so_hop_dong_dang_co` | Số hợp đồng tín dụng đang mở |
| `so_thang_quan_he` | Độ dài lịch sử tín dụng |
| `nhom_no_cao_nhat` | Nhóm nợ CIC 1–5 |

### 9.3 Fineract (2)

`int_rate`, `term_months` — thông tin sản phẩm vay.

### 9.4 Dẫn xuất (5)

`log_income`, `loan_to_income`, `effective_apr`, `log_du_no`, `ty_le_du_no_thu_nhap`
— công thức ở mục 10.

### 9.5 Target-encoded (4)

`home_ownership_encoded`, `purpose_cat_encoded`, `verification_status_encoded`,
`interest_method_encoded`

Bảng ánh xạ nằm trong gói model, **không tính lại lúc chấm điểm**. Giá trị lạ (không có
trong bảng) rơi về `global_mean`.

### 9.6 Missing indicator (16)

Một cờ `0/1` cho mỗi cột trong `COLUMNS_WITH_MISSING`. Mục tiêu: mô hình học được mối
tương quan giữa **hành vi không khai báo** và rủi ro — người vay thường bỏ trống thông
tin bất lợi. Khi CIC timeout, cả 10 cờ CIC cùng bật.

### 9.7 Age bucket (4)

`age_under_25`, `age_25_to_39`, `age_40_to_59`, `age_over_60` — one-hot từ `person_age`.

## 10. Công thức 5 đặc trưng dẫn xuất

Tất cả tính trong `encode_features()`, [`features.py`](../app/ml/credit/features.py).

| Đặc trưng | Công thức | Mục tiêu |
|---|---|---|
| `log_income` | `log1p(annual_inc)` | Nén đuôi phải của phân phối thu nhập |
| `loan_to_income` | `clip(loan_amnt / annual_inc, 0, 5)` | Quy mô vay tương đối |
| `log_du_no` | `log1p(tong_du_no)` | Nén đuôi phải của dư nợ |
| `ty_le_du_no_thu_nhap` | `clip(tong_du_no / annual_inc, 0, 10)` | Tổng gánh nặng nợ |
| `effective_apr` | giải IRR — xem dưới | Lãi suất thực, so sánh được giữa các phương pháp |

Chia cho `annual_inc = 0` được xử lý bằng `replace(0, NaN)` rồi `fillna(0)`.

### `effective_apr` — vì sao phải giải lặp

`int_rate` **không so sánh được** giữa các phương pháp tính lãi: khoản vay FLAT ghi
12 %/năm có chi phí thực khoảng 21 %, còn DECLINING ghi 12 % thì đúng 12 %.
`effective_apr` đưa mọi phương pháp về cùng một thang.

Bài toán: biết `installment`, `loan_amnt`, `term_months` — tìm lãi suất `r`. Công thức
niên kim đi xuôi thì có sẵn:

```
installment = goc × r × (1+r)^n / ((1+r)^n − 1)
```

Đi ngược thì **không có công thức đóng**, phải dò bằng bisection — `tinh_effective_apr`
trong [`preprocessing.py`](../app/ml/credit/preprocessing.py):

```
thấp = 1e-12,  cao = 0.5          # lãi tháng: 0 % → 600 %/năm
lặp 60 lần:
    giữa = (thấp + cao) / 2
    thử  = niên_kim(goc, giữa, n)
    nếu thử < installment → thấp = giữa      # đang đoán thấp
    ngược lại             → cao  = giữa
trả về (thấp + cao) / 2 × 12 × 100           # lãi tháng → %/năm
```

60 vòng chia đôi cho sai số cỡ `0.5 / 2^60`.

> **Đặc trưng dẫn xuất PHẢI tính SAU khi điền median.** `installment` và `term_months`
> đều nằm trong `COLUMNS_WITH_MISSING`. Tính trước thì giá trị thiếu lan lên cột dẫn
> xuất mà không bị chặn. Đây cũng là lý do dịch vụ gọi **không** được tự tính
> `effective_apr` rồi gửi sang.

## 11. Sáu bước trong `du_doan()`

[`app/ml/credit/predictor.py`](../app/ml/credit/predictor.py)

### Bước 1 — `chuan_bi_dac_trung()`

Dựng dict 22 cột thô từ request. Thứ tự bắt buộc:

1. Ánh xạ `purpose` → `purpose_cat` và `home_ownership` qua `PURPOSE_MAP` /
   `HOME_OWNERSHIP_MAP` (giá trị lạ → `OTHER`)
2. **Tạo cờ `*_missing` trước** — phải làm trước khi điền, nếu không mọi cờ đều bằng 0
3. Điền `None` / `NaN` bằng `self.median` lấy từ gói model

Tách riêng khỏi `du_doan_pd()` để test khẳng định được **giá trị nào** đã thực sự được
điền, thay vì chỉ nhìn PD đầu ra rồi đoán.

### Bước 2 — `encode_features()`

Target encoding (dùng bảng trong gói) → 4 age bucket → 5 dẫn xuất. Sau đó reindex tường
minh `encoded[self.feature_names]` để cột đúng thứ tự mô hình đã học.

Nếu còn `NaN` sau bước này, `du_doan_pd()` **ném `ValueError` kèm tên cột** thay vì để
mô hình đoán bừa.

### Bước 3 — `predict_proba()` → PD

Xác suất vỡ nợ, khoảng `(0, 1)`.

### Bước 4 — `tinh_diem_rui_ro()` → risk_score

Rule engine 5C, [`rule_engine.py`](../app/services/credit/rule_engine.py). Bốn yếu tố ×
25 điểm: tỷ lệ vay trên thu nhập, thâm niên việc làm, tình trạng nhà ở, mức thu nhập
năm. Trừ 10 điểm nếu tuổi và thâm niên chênh bất hợp lý (10 ≤ `tuổi − thâm_niên` < 18).

Đây là phần **giải trình được không cần công cụ** — ngưỡng cố định, không học từ dữ
liệu. `cic_score` **không** dùng ở đây, chỉ dùng trong mô hình ML.

### Bước 5 — `tinh_diem_tong_hop()`

```
evaluation_score = (1 − PD) × 100 × pd_weight + risk_score × risk_weight
```

Trọng số đọc từ `config/product_config.json` (`model_weights`), **không hardcode**.

### Bước 6 — Chốt chặn, xếp hạng, quyết định

`kiem_tra_chot_chan_cung()` chạy các luật loại trừ thẳng:

| Luật | Điều kiện | Mã trả về |
|---|---|---|
| Trần lãi suất | > 20 %/năm (Điều 468 BLDS 2015) | `INTEREST_RATE_EXCEEDS_LEGAL_LIMIT` |
| Lãi suất không hợp lệ | ≤ 0 | `INVALID_INTEREST_RATE` |
| Trần kỳ hạn | > 24 tháng (NĐ 94/2025) | `TERM_EXCEEDS_LEGAL_LIMIT` |
| Áp lực trả nợ | installment / thu nhập tháng > 50 % | `DEBT_SERVICE_RATIO_TOO_HIGH` |
| Tuổi vs kinh nghiệm | `tuổi − thâm_niên < 10` | `AGE_AND_EXPERIENCE_INCONSISTENCY` |

`xep_hang()` tra bảng dựng từ `product_config.json` (hạng A/B/C/D). `quyet_dinh()` so
`evaluation_score` với `auto_approve` / `auto_reject`; **vi phạm chốt chặn thì luôn
`REJECTED`** bất kể điểm.

Đường ra quyết định **không cắt ngưỡng PD** — `tinh_diem_tong_hop()` nhận PD liên tục.
`nguong_bao_cao` trong metadata chỉ dùng để tính recall/precision/F1 khi báo cáo.

---

# PHẦN IV — VẬN HÀNH

## 12. Lỗi và cách xử lý

| Tình huống | Nơi phát sinh | Hành vi |
|---|---|---|
| Gói model thiếu / hỏng / lệch feature | `lay_bo_du_doan()` | HTTP **503** `MODEL_NOT_AVAILABLE` — vấn đề triển khai, không phải lỗi request |
| Còn `NaN` sau khi điền median | `du_doan_pd()` | `ValueError` kèm tên cột |
| cic-service timeout / non-200 | `CicClient` | **Fail-open**: trả `None`, log WARNING, pipeline chạy tiếp với 10 cờ `*_missing` bật |
| Không có `so_cccd` | `credit_router` | Bỏ qua CIC hoàn toàn — `cic_data = None` |
| Field lạ trong request | Pydantic | **Bị nuốt im lặng** (`extra="ignore"`) |
| Giá trị phân loại lạ | `PURPOSE_MAP` / target encoding | → `OTHER` / `global_mean` |

**Fail-open của CIC là quyết định có chủ đích**: chặn luồng vay vì bên thứ ba lỗi thì tệ
hơn là chấm điểm với thông tin thiếu. Nhưng hệ quả là mô hình mất 10/47 đặc trưng. Số
CCCD bị che khi log (`_che_cccd`), còn tỷ lệ fail nên được theo dõi qua log
`result=error`.

## 13. Checklist train lại model

### Khi nào bắt buộc train lại

- Sửa `FEATURE_NAMES` (thêm/bớt/đổi thứ tự đặc trưng)
- Sửa công thức đặc trưng dẫn xuất trong `encode_features()`
- Sửa `PURPOSE_MAP` / `HOME_OWNERSHIP_MAP`
- Đổi dữ liệu nguồn `data/lc_clean.csv`
- Sửa `DAC_TRUNG_DON_DIEU_TANG`

Lớp kiểm tra `feature_names` (mục 7) sẽ chặn service khởi động nếu quên — nhưng chỉ với
trường hợp thứ nhất. Bốn trường hợp còn lại **không bị phát hiện tự động**.

### Các bước

1. **Bump `PHIEN_BAN`** trong `scripts/train_credit_model.py` — **không ghi đè version cũ**.
   Cùng một tên version mang hai nội dung khác nhau là lỗi truy vết nghiêm trọng.
2. Kiểm tra `NGUONG_BAO_CAO` còn hợp lý với dữ liệu mới không (quét lại ngưỡng tối ưu F1).
3. Cập nhật `nguon_du_lieu` nếu dữ liệu nguồn đổi.
4. Chạy `python scripts/train_credit_model.py`.
5. **Sau khi train xong**, đổi `PHIEN_BAN_MAC_DINH` trong `app/ml/credit/predictor.py`.
   Đổi trước thì predictor tìm file chưa tồn tại và test sẽ đỏ.
6. Chạy `pytest tests -q`.
7. Thông báo version mới cho các service gọi đến — chúng có thể đang khóa cứng version cũ.

### Đối chiếu sau khi train

So `metrics` của gói mới với gói cũ. AUC/Gini/KS giảm đáng kể mà không có lý do rõ ràng
là dấu hiệu pipeline hỏng ở đâu đó — kiểm tra lại thứ tự trong `tao_ma_tran()` (mục 2)
trước tiên.

---

## 14. Việc `finora-loan` cần làm để kết nối thành công

> Mục này mô tả **phía gọi**, nằm ngoài `finora-ai`. Owner của `finora-loan` thực hiện.
> Trạng thái ghi nhận tại thời điểm model `v16.0.0`.

Hiện tại `finora-loan` gọi sang sẽ **fail cứng**: cấu hình yêu cầu model `13.0.0` trong
khi service phục vụ `16.0.0`, `validate()` ném `AI_MODEL_VERSION_MISMATCH` với
`retryable = false` — hồ sơ kẹt vĩnh viễn, không tự hồi phục.

### 14.1 Bốn việc bắt buộc

| # | Việc | File phía Loan | Không làm thì sao |
|---|---|---|---|
| 1 | Đồng bộ `model-version` | `application.yml` (`finora.ai.credit.model-version`) **và** `docker/docker-compose.yml` | Fail cứng `AI_MODEL_VERSION_MISMATCH`, non-retryable |
| 2 | Thêm `so_cccd` | `AiCreditScoreRequest` + `AiCreditScoringMapper` | Không tra được CIC → mất **10/47 đặc trưng**, PD kém chính xác, **không báo lỗi** |
| 3 | Thêm `interest_method` | `AiCreditScoreRequest` + `AiCreditScoringMapper` | Mặc định `DECLINING_BALANCE`; sản phẩm FLAT bị tính sai `effective_apr` (12 % danh nghĩa ≈ 21 % thực) |
| 4 | Bỏ `delinq_2yrs`, `pub_rec` | `AiCreditScoreRequest` + `AiCreditScoringMapper` | Không sai kết quả (Pydantic `extra="ignore"` nuốt im lặng) nhưng là tàn dư của schema cũ |

`docker-compose.yml` hiện **không khai báo** `AI_CREDIT_MODEL_VERSION` — chạy bằng Docker
thì container Loan lấy giá trị mặc định trong `application.yml`. Sửa một chỗ mà quên chỗ
kia thì lỗi tái xuất hiện khi deploy.

### 14.2 Ánh xạ `interest_method`

`finora-loan` có `RepaymentMethod { ANNUITY, EQUAL_PRINCIPAL }` — mô tả **cách phân bổ
gốc/lãi**. `finora-ai` nhận `{ FLAT, DECLINING_BALANCE, DECLINING_BALANCE_RECALC }` — mô
tả **cách tính lãi**. Hai khái niệm khác nhau, không ánh xạ 1-1.

Cả `ANNUITY` lẫn `EQUAL_PRINCIPAL` đều tính lãi trên dư nợ giảm dần → cùng thành
`DECLINING_BALANCE`. Nếu sản phẩm Fineract có dùng lãi FLAT, thông tin đó nằm ở chỗ khác
trong `LoanProduct` và cần được truyền qua — `RepaymentMethod` không mang nó.

Đây là field **target-encoded**, ảnh hưởng thật tới PD, không phải field trang trí.

### 14.3 Kiểm tra `credit_grade` — nên nới

`validate()` hiện khoá cứng `Set.of("A","B","C","D")`. Tập hạng phía AI đọc từ
`config/product_config.json` và **sửa được lúc chạy** qua `PUT /api/v1/ai/config/product`
(trường `grade` khai báo là `str`, không enum).

Hiện hai bên khớp (A/B/C/D), nhưng admin thêm một hạng mới là Loan kẹt lại với
`AI_CONTRACT_MISMATCH` non-retryable — và lần đó rất khó truy nguyên nhân.

`creditGrade` phía Loan lưu dạng `String` và chỉ để hiển thị, không dùng ra quyết định.
Cái Loan thực sự cần kiểm tra là `decision` (enum 3 giá trị, ổn định) và `suggested_limit`.

### 14.4 Về khoá version

`validate()` so khớp **tuyệt đối** `properties.modelVersion().equals(response.modelVersion())`.
Mỗi lần `finora-ai` train model mới sẽ làm luồng đứt cho tới khi Loan sửa cấu hình —
xem mục 13, bước 7.

Nếu muốn tránh lặp lại, cân nhắc nới thành so khớp **major** (chấp nhận `16.x.x`): đổi
`FEATURE_NAMES` là đổi major, nên contract vẫn được bảo vệ ở chỗ quan trọng. Việc này cần
hai bên thống nhất quy ước đánh version trước.

### 14.5 Những gì KHÔNG cần đổi

- **`installment`** — Loan tính từ `ScheduleCalculationSnapshot` của Fineract rồi gửi
  sang; AI chỉ nhận, **không tự tính**. Bỏ trống thì AI điền median từ gói model — tức
  mất một đặc trưng thật. Giữ nguyên cách Loan đang làm.
- **`effective_apr`** — AI tự tính. Loan **không** được gửi: nó phải được tính sau bước
  điền median (mục 10).
- **`suggested_rate`** trong `AiCreditScoreResponse` — AI không trả field này, Jackson map
  thành `null`. Vô hại, dọn khi tiện.
- **`cic-service`** — đã đồng nhất với `finora-ai` (endpoint, thang điểm 150–750, 9 trường
  thô). Không cần sửa.

### 14.6 Xác minh sau khi sửa

1. `GET /health` trên `finora-ai` trả 200.
2. Gọi thử `POST /api/v1/ai/credit/score` với một hồ sơ có `so_cccd` hợp lệ; kiểm tra
   `model_version` trong response khớp cấu hình Loan.
3. Đối chiếu log `finora-ai`: dòng `cic-service tra_diem_cic ... result=success` xác nhận
   CIC được tra thật, không rơi vào fail-open.
4. Chạy một hồ sơ vi phạm chốt chặn (ví dụ `int_rate` > 20) — phải nhận `decision`
   `REJECTED` kèm `rejection_reason`, không phải lỗi tích hợp.
