---
task_id: AI-V18
roadmap_id: P1-B05, P1-B06, P7-B04 (một phần: backtest + so sánh với mô hình đang chạy)
title: Mô hình chấm điểm tín dụng v18 — đo trung thực, bỏ rò rỉ lãi suất, PD đúng xác suất, chuẩn hoá cho người vay Việt Nam
owner: Hai
status: DRAFT
depends_on: []
liên_quan: finora-loan (LN-007: gọi AI và kiểm model version), finora-web (trang chấm điểm, khối giải thích AI), báo cáo khóa luận
created_at: 2026-10-15
updated_at: 2026-10-15
bằng_chứng: ../../../HUONG-TANG-DO-CHINH-XAC-MO-HINH-AI.md và ../../../thi-nghiem-mo-hinh-15-10/
---

# AI-V18 — Mô hình chấm điểm tín dụng v18

Plan này mô tả **việc cần làm, thứ tự, cách nghiệm thu**. Không có đặc tả code: tên file và tên hàm chỉ để
chỉ chỗ sửa. Mọi con số "hiện trạng" và "kỳ vọng" đều đã đo thật ngày 15/10 (xem tài liệu bằng chứng ở
front matter). Plan ở trạng thái `DRAFT` cho tới khi Hải chốt các quyết định ở mục 0.

---

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Mô hình v17 đang chạy báo AUC 0,70. Nhưng khi đưa vào đúng dữ liệu mà finora-loan gửi lúc chạy thật, AUC chỉ
còn **0,632**, và PD (xác suất vỡ nợ) bị thổi lên gấp đôi: trung bình 33% trong khi tỷ lệ vỡ nợ thật là 15%.
Có ba nguyên nhân chính:

1. **Rò rỉ lãi suất.** 47% sức dự đoán của v17 đến từ `int_rate`. Lúc huấn luyện, đây là lãi mà LendingClub
   đặt theo hạng tín dụng họ đã chấm. Lúc chạy thật, Loan gửi lãi cơ sở của sản phẩm, giống nhau cho mọi người.
2. **Dữ liệu tổng hợp.** 200.000 dòng tổng hợp vừa làm đẹp con số kiểm định, vừa làm mô hình kém đi trên người
   vay thật (−0,005 AUC).
3. **Cân bằng lớp bằng `scale_pos_weight`.** Cách này làm PD lệch khỏi xác suất thật.

Thêm vào đó, hệ số k quy đổi tiền làm mọi người vay Việt Nam trông như "người giàu". Khoản vay trên 30,5tr nằm
ngoài vùng dữ liệu. Vài trường CIC (nhóm nợ 2, chưa có lịch sử CIC) bị xử lý sai nghĩa.

v18 sửa các điểm trên để **người được duyệt ít vỡ nợ hơn** và **PD hiển thị cho thẩm định viên/nhà đầu tư đúng
là xác suất**.

### Ai tham gia và nhìn thấy gì?

| Ai | Trước (v17) | Sau (v18) |
|---|---|---|
| Người vay | Gần như không ai được duyệt tự động. Ở sản phẩm lãi 18% thì từ chối cả người có CIC 735, thu nhập 40tr | Hồ sơ tốt có PD thấp, được duyệt hoặc chờ duyệt hợp lý. Hồ sơ ngoài vùng dữ liệu chuyển thẩm định, không bị từ chối oan |
| Thẩm định viên (admin web) | Thấy PD 30–70% cho gần như mọi hồ sơ; giải thích SHAP có dòng "Rò rỉ" lãi suất | Thấy PD gần đúng xác suất; có lý do chuyển thẩm định rõ ràng (CIC thấp ngoài vùng dữ liệu, vay quá 50% thu nhập năm, chưa có lịch sử CIC) |
| Nhà đầu tư (mobile, hồ sơ người vay) | PD khoảng 30% cho khoản bình thường, dễ hiểu nhầm là rủi ro cao | PD sát thực tế, khoảng 5–25% cho phần lớn khoản |
| Loan Service | Gọi AI, kiểm `model_version = 17.0.0` | Kiểm `model_version = 18.0.0`. Hợp đồng request/response giữ nguyên |
| Hội đồng khóa luận | Con số 0,70 không đứng được nếu bị hỏi kỹ | Con số 0,65 đo đúng điều kiện triển khai, kèm giải thích vì sao giảm mà vẫn tốt hơn |

### Luồng chính và luồng không thành công

Luồng chính không đổi: Loan gửi hồ sơ → AI lấy CIC → mô hình ra PD → rule engine ra điểm luật → điểm tổng hợp
→ hạng A–E → quyết định. Loan lưu snapshot và định giá theo hạng.

Thay đổi nằm bên trong AI:
- Mô hình **không dùng** `int_rate` Loan gửi. `int_rate` chỉ còn dùng cho chốt chặn trần 20%.
- Tiền trả hàng tháng để đưa vào mô hình được tính lại theo **một lãi tham chiếu cố định**. Tiền trả thật vẫn
  dùng cho luật gánh nặng trả nợ.
- Thu nhập được đổi sang **phân vị** trong phân bố thu nhập lao động Việt Nam.
- PD đi qua **bộ hiệu chỉnh** trước khi đổi thành điểm.

Luồng không thành công:

| Tình huống | Hệ thống làm gì |
|---|---|
| CIC dưới khoảng 545 (thấp hơn mọi người vay trong dữ liệu thật) | `PENDING_REVIEW`, lý do "ngoài vùng dữ liệu của mô hình" |
| Số tiền vay lớn hơn 50% thu nhập năm (LendingClub gần như không có) | `PENDING_REVIEW`, lý do tương tự |
| CIC trả lời "không có hồ sơ" (người chưa từng vay) | `PENDING_REVIEW`, lý do "chưa có lịch sử tín dụng". Phụ thuộc mục 0, câu Q6 |
| CIC lỗi hoặc timeout | Giữ như hiện tại: điền trung vị, có cờ thiếu, đủ luật thì máy quyết, thiếu quá 40% luật thì chờ duyệt |
| Gói model và cấu hình ngưỡng không cùng phiên bản (vd model v18 mà ngưỡng còn của v17) | AI **từ chối chấm** (503) và ghi log rõ. Loan retry có giới hạn như LN-007. Không bao giờ ra quyết định bằng cặp lệch |
| Loan còn cấu hình `17.0.0` khi AI đã lên v18 | Loan chặn bằng `AI_MODEL_VERSION_MISMATCH` (hành vi hiện có). Phải đổi hai bên trong cùng một lần triển khai |

### Dữ liệu nào ảnh hưởng kết quả?

| Dữ liệu | Người dùng hiểu là gì | Nguồn | Dùng ở bước nào | Nếu thiếu/sai | Vì sao lưu trong gói model |
|---|---|---|---|---|---|
| Lãi tham chiếu | Một mức lãi chung để tính "tiền trả mỗi tháng" khi so sánh gánh nặng trả nợ giữa người với người | Chốt ở Q2 | Huấn luyện và chấm điểm | Hai lúc dùng hai mức khác nhau thì PD lệch (train/serve skew) | Bảo đảm huấn luyện và chạy thật cùng một công thức |
| Phân bố thu nhập Việt Nam | "Người này đứng ở đâu so với lao động Việt Nam" | Tổng cục Thống kê 2024: bình quân 7,7tr/tháng, nhóm cao nhất gấp 7,4 lần nhóm thấp nhất. Chốt ở Q3 | Chấm điểm (đổi thu nhập ra phân vị) | Sai phân bố thì mọi người bị xếp quá giàu hoặc quá nghèo | Cần tái lập đúng phân vị và truy vết nguồn số liệu |
| Bộ hiệu chỉnh PD | Bảng quy đổi điểm thô của mô hình thành xác suất thật | Học từ một năm dữ liệu tách riêng | Sau khi mô hình ra điểm thô | Thiếu thì PD lệch mức như v17 | PD là con số hiển thị cho người, phải tái lập được |
| Biên vùng dữ liệu | CIC thấp nhất, tỷ lệ vay/thu nhập cao nhất mà mô hình từng thấy | Tính từ dữ liệu huấn luyện | Chốt chuyển thẩm định | Hồ sơ ngoài vùng bị chấm như ở mép vùng, thường là quá lạc quan | Biên phải đi cùng đúng mô hình đã học nó |
| Ngưỡng hạng/duyệt (`product_config.json`) | A–E và ngưỡng tự duyệt/tự từ chối | Đặt theo phân vị dân số, chốt ở Q4 | Xếp hạng, quyết định | Ngưỡng của v17 áp lên PD v18 sẽ làm gần như ai cũng lên hạng cao | Là policy có version (`CREDIT_POLICY_V2`) và khai báo model nó được hiệu chỉnh cho |

### Ví dụ thực tế

Kết quả giả lập 8 hồ sơ người Việt (`thi-nghiem-mo-hinh-15-10/mo_phong_ho_so_vn.py`, `chuan_hoa_vn.py`):

| Hồ sơ | v17, sản phẩm lãi 12% | v17, sản phẩm lãi 18% | v18 dự kiến (PD) |
|---|---|---|---|
| NV văn phòng HN, 15tr/tháng, CIC 680 | PD 32% · chờ duyệt | 49% · từ chối | khoảng 7% |
| Kỹ sư IT, 40tr/tháng, CIC 735, vay 100tr | 33% · chờ duyệt | 47% · từ chối | khoảng 7% |
| Vay nhiều app, CIC 480, trễ 3 lần | 58% · từ chối | 64% · từ chối | khoảng 36% → **chuyển thẩm định** vì CIC < 545 |

Hạng và quyết định của v18 chỉ có sau khi đặt lại ngưỡng ở Giai đoạn 4.

### Điều kiện hoàn thành nghiệp vụ

- Trên dữ liệu thật năm 2015, với đầu vào giống lúc chạy thật, v18 phải **tốt hơn v17 có ý nghĩa thống kê**. Cụ
  thể: AUC ≥ 0,645, và khoảng tin cậy 95% của phần chênh với v17 nằm hoàn toàn trên 0.
- PD trung bình lệch tỷ lệ vỡ nợ thật không quá 2 điểm phần trăm (ECE ≤ 0,02).
- Đổi `int_rate` trong request thì PD **không đổi**.
- Tỷ lệ vỡ nợ giảm dần theo hạng A → E.
- Luồng demo Loan → AI → quyết định → định giá chạy thông với v18.
- Báo cáo khóa luận dùng số đo mới và giải thích được vì sao con số giảm từ 0,70.

---

## 0. Quyết định cần Hải chốt trước khi bắt đầu

Cột "Đề xuất" là phương án mặc định nếu Hải không chọn khác.

| # | Câu hỏi | Lựa chọn | Đề xuất | Ảnh hưởng |
|---|---|---|---|---|
| Q1 | Dữ liệu tổng hợp 200.000 dòng | Bỏ hẳn / giữ với trọng số 0,1 | **Bỏ hẳn**. AUC thật bằng nhau (0,6526 / 0,6525), lập luận trước hội đồng gọn hơn | Bỏ thì phải có chốt CIC < 545 |
| Q2 | Lãi tham chiếu để tính tiền trả mỗi tháng | 13,11% (median gói v17) / median lãi cơ sở các sản phẩm thật | **Median lãi cơ sở sản phẩm thật** trên Fineract. Đã đo: đổi 8–19% gần như không làm đổi AUC | Chỉ ảnh hưởng mức tỷ lệ trả nợ/thu nhập |
| Q3 | Phân bố thu nhập để đổi ra phân vị | Lao động toàn quốc / lao động thành thị | **Toàn quốc** (trung vị khoảng 6tr/tháng). Thay bằng phân bố hồ sơ thật khi FINORA có dữ liệu | Thành thị (9,3tr) làm phân vị của mọi người thấp hơn |
| Q4 | Tỷ trọng hạng A/B/C/D/E và tỷ lệ tự duyệt | Giữ 5/25/40/22/8% như lần hiệu chỉnh trước / số khác | **Giữ 5/25/40/22/8%**. Tự duyệt khoảng 20%, chờ duyệt khoảng 10% | Quyết định số hồ sơ admin phải xem |
| Q5 | Hiệu chỉnh PD | Chỉ bỏ `scale_pos_weight` (ECE 0,011) / thêm isotonic (ECE 0,004) | **Thêm isotonic**. Rẻ, và PD hiển thị cho nhà đầu tư nên càng sát càng tốt | Thêm một bảng trong gói model |
| Q6 | Người chưa có lịch sử CIC | Chuyển thẩm định / cho vay hạn mức nhỏ / giữ như hiện tại (điền trung vị) | **Chuyển thẩm định**. Dữ liệu LendingClub không có người chưa từng vay, mô hình không biết họ | Cần cic-service phân biệt "không có hồ sơ" với "lỗi" (mục 2) |
| Q7 | Trần 400tr đang cộng cả vay mua nhà ngân hàng | Sửa ngay (cần CIC trả dư nợ P2P riêng) / ghi nhận hạn chế, làm sau | **Ghi nhận hạn chế, tách task riêng**. `LEGAL-LIMIT-01` còn `NEEDS_PRIMARY_SOURCE` | Người có vay nhà vẫn bị từ chối cho tới khi sửa |
| Q8 | Có làm phần chuẩn hoá thu nhập/khoản vay (phân vị + tỷ lệ) trong v18 không | Có / để v19 | **Có**. AUC chỉ −0,002, nhưng sửa đúng điểm người Việt bị xếp sai | Thêm 0,5 ngày |
| Q9 | Lộ trình | Đầy đủ (khoảng 5 ngày) / tối thiểu (khoảng 2,5 ngày, mục 14) | Tùy hạn nộp báo cáo | |

---

## 1. Current state, phạm vi và ngoài phạm vi

### Hiện trạng (đã kiểm trong code ngày 15/10)

- Gói đang chạy: `models/credit/model_v17.0.0.{pkl,json}`, 47 đặc trưng, `PHIEN_BAN_MAC_DINH = "17.0.0"` trong
  `app/ml/credit/predictor.py`. Gói được nạp một lần qua `lru_cache` trong `app/api/credit_router.py`, nên đổi
  gói phải khởi động lại service.
- Huấn luyện: `scripts/train_credit_model.py`.
  - Dữ liệu `data/lc_clean.csv`: 873.540 dòng, trong đó 200.000 dòng tổng hợp nối ở cuối file, không có cột
    đánh dấu.
  - Có `scale_pos_weight`, có `int_rate`. `installment` được tính từ lãi LendingClub.
  - Hệ số k = 871,6 cho cả 5 cột tiền.
- Rule engine: luật nằm trong `config/product_config.json`. Luật `CHARACTER_CIC_HISTORY` dùng mốc 740/700/670 của
  thang FICO cho trường `cic_score` có thang 150–750. Ngưỡng hạng 84/69/50/37, tự duyệt 75, tự từ chối 65,
  `decision_policy_version = CREDIT_POLICY_V1`.
- `scripts/validate_rule_engine.py` gán `cic_score = fico_score` (sai thang).
- `app/ml/credit/preprocessing.py`:
  - `tinh_so_thang_quan_he()` đọc năm hai chữ số, nên "Dec-68" thành 2068. 1.823 dòng thật bị âm tới −720 tháng.
  - `map_nhom_no()` chỉ sinh nhóm 1, 3, 4; không có nhóm 2.
- `person_age` không có trong LendingClub gốc. Nó được suy ra từ lịch sử tín dụng (tương quan 0,81 với số tháng
  quan hệ tín dụng).
- `app/services/credit/cic_client.py` trả `None` cho mọi trường hợp không phải 200, nên không phân biệt "không có
  hồ sơ" với "lỗi".
- Loan: `finora-loan/src/main/resources/application.yml` có `AI_CREDIT_MODEL_VERSION:17.0.0`, và
  `AiCreditScoringHttpClient` chặn khi phiên bản không khớp.
- Web: `finora-web/src/features/credit-score/*` và `features/loan/components/review/AiExplanationBlocks.tsx` hiển thị
  cờ `la_leakage` ("Rò rỉ").

### Trong phạm vi

1. Khóa cách đo trung thực và đo lại mốc v17.
2. Sửa dữ liệu huấn luyện: đánh dấu nguồn dòng, sửa ngày, nhóm nợ 2.
3. Thí nghiệm chọn đặc trưng có luật quyết định rõ.
4. Huấn luyện v18 và mở rộng gói model.
5. Sửa đường chấm điểm cho khớp huấn luyện.
6. Đặt lại ngưỡng hạng/duyệt, sửa thang luật CIC, thêm lý do chuyển thẩm định.
7. Cập nhật giải thích (XAI), web và cấu hình Loan.
8. Kiểm thử, triển khai, rollback.
9. Cập nhật tài liệu `finora-ai/docs`, báo cáo khóa luận, bộ test case.

### Ngoài phạm vi

- Đổi sang tập Home Credit. Đây là task sau nếu cần, xem mục 8 của tài liệu bằng chứng.
- Sửa trần 400tr (Q7).
- Đổi hợp đồng request/response giữa Loan và AI.
- Chấm lại các hồ sơ đã có kết quả v17. Snapshot đó là bằng chứng bất biến theo `LEGAL-AI-01`.
- Mô hình gian lận và eKYC.

---

## 2. Ownership, nguồn sự thật và phụ thuộc

| Phần | Module | Owner | Ghi chú |
|---|---|---|---|
| Dữ liệu huấn luyện, gói model, predictor, explainer, rule engine, config policy | `finora-ai` | Hải | Phần chính |
| Phiên bản model Loan chấp nhận | `finora-loan` (`application.yml`, biến môi trường) | Thái, đã giao Hải được sửa | Chỉ đổi cấu hình, không đổi code |
| Hiển thị giải thích, mã lý do thẩm định | `finora-web` | Hải | Chỉ thêm nhãn cho mã mới, nếu web hiển thị mã |
| Trường "không có hồ sơ" của CIC | cic-service (mock, ngoài phạm vi khóa luận) | Hải | **Phụ thuộc** của Q6. Cần kiểm tra mock trả gì khi CCCD không có hồ sơ |
| Triển khai | Docker compose, VM Azure | Hải | Theo quy trình deploy hiện có |
| Báo cáo khóa luận, test case | `Đồ Án/scripts-bao-cao-15-10`, `scripts-test-cases` | Hải | Sửa script rồi build lại, không sửa tay file docx/xlsx |

Nguồn sự thật: gói model (`.pkl` + `.json`) là nguồn duy nhất cho mọi thứ mô hình đã học. `product_config.json`
là nguồn duy nhất cho policy. Loan lưu snapshot kết quả và là nguồn sự thật của quyết định đã ra.

---

## 3. Thành phần dữ liệu

Task này không thêm bảng database. "Dữ liệu" ở đây là các file có vòng đời riêng:

| Thành phần | Loại | Vòng đời | Thay đổi trong v18 |
|---|---|---|---|
| `data/lc_clean.csv` | Dữ liệu nguồn | Sửa có sao lưu (`.bak`) | Thêm cột đánh dấu nguồn dòng (thật/tổng hợp). Giữ nguyên giá trị các cột khác |
| Gói `model_v18.0.0.pkl` | Bất biến sau khi sinh | Một phiên bản = một file, có SHA-256 | Mới |
| Metadata `model_v18.0.0.json` | Value object đi cùng gói, bất biến | Sinh cùng gói | Thêm: lãi tham chiếu, tham số phân bố thu nhập Việt Nam (kèm nguồn, ngày), bảng hiệu chỉnh PD, biên vùng dữ liệu, chỉ số theo cách đo mới, mốc v17 đo lại, mô tả nguồn dữ liệu không còn dòng tổng hợp |
| `config/product_config.json` | Policy có version, admin sửa được qua API | Sửa qua `PUT /config/product`, `PUT /config/rules` | Ngưỡng hạng/duyệt mới, mốc luật CIC theo thang CIC, `decision_policy_version = CREDIT_POLICY_V2`, thêm khai báo "model version được hiệu chỉnh cho" |
| Gói v17 | Bất biến | Giữ nguyên để rollback | Không xóa |
| Snapshot kết quả AI trong Loan | Bất biến, Loan sở hữu | Không đổi | Hồ sơ cũ giữ v17. Hồ sơ mới mang `model_version = 18.0.0` |

## 4. Quan hệ giữa các thành phần

```text
model_v18.0.0.pkl  1 ──── 1  model_v18.0.0.json   (SHA-256 trong json khớp file pkl)
        │
        │ được hiệu chỉnh cho
        ▼
product_config.json (CREDIT_POLICY_V2)  khai báo model 18.0.0
        │
        │ mỗi lần chấm điểm sinh
        ▼
Snapshot kết quả trong Loan  0..N  (mỗi snapshot ghi model_version + decision_policy_version)
```

| Quan hệ | Vì sao | Ai bảo vệ | Khi đổi |
|---|---|---|---|
| Gói pkl ↔ json `1:1` | Metadata mô tả đúng file mô hình | `BoDuDoan.nap()` kiểm SHA-256 và danh sách đặc trưng (đã có) | Sinh lại cả cặp |
| Policy ↔ model `N:1` theo version | Ngưỡng chỉ đúng với thang PD của mô hình đã hiệu chỉnh | **Mới:** AI kiểm khi nạp, lệch thì không chấm | Đổi model phải đổi policy cùng lúc |
| Model/policy → snapshot `1:0..N` | Truy vết quyết định cũ | Loan lưu version trong snapshot (đã có) | Không sửa snapshot cũ |

## 5. Bất biến

1. Cùng một hồ sơ: huấn luyện và chấm điểm phải dựng **cùng giá trị đặc trưng**. Kiểm bằng test "dòng mẫu vàng":
   một hồ sơ cố định đi qua hai đường phải ra cùng vector.
2. PD không phụ thuộc `int_rate` của request.
3. PD tăng (hoặc không giảm) khi CIC giảm, khi số lần trễ tăng, khi nhóm nợ xấu đi. Kiểm trên bộ hồ sơ tổng hợp
   dùng làm kiểm tra áp lực.
4. Tổng đóng góp SHAP + bias = log-odds **trước hiệu chỉnh**. Bất biến cộng dồn hiện có vẫn giữ, áp cho điểm thô.
   Giải thích phải ghi rõ PD hiển thị là PD sau hiệu chỉnh.
5. Không chạy model v18 với policy chưa khai báo v18, và ngược lại.
6. `risk_score` ∈ [0,100], `credit_grade` ∈ A–E. Hợp đồng với Loan giữ nguyên.

## 6. Hợp đồng API và tương thích

- `POST /api/v1/ai/credit/score` và `/explain`: **không đổi** request/response. Loan vẫn gửi `int_rate`,
  `installment`. AI vẫn dùng chúng cho chốt chặn và luật. Mô hình bỏ qua chúng.
- `model_version` trả về đổi từ `17.0.0` sang `18.0.0`. `decision_policy_version` đổi sang `CREDIT_POLICY_V2`.
- `review_reasons` có thể chứa mã mới. Dự kiến: `NGOAI_MIEN_CIC`, `NGOAI_MIEN_VAY_THU_NHAP`, `CIC_CHUA_CO_LICH_SU`.
  Phải kiểm Loan và web chấp nhận mã lạ, không có danh sách cứng.
- `giai_thich_mo_hinh`: cờ `la_leakage` vẫn có trong response để tương thích nhưng luôn `false`. Nhóm "Lãi suất
  và cách tính lãi" biến mất.
- **Loan không sửa payload, không sửa code** (đã kiểm `AiCreditScoreRequest`/`AiCreditScoreResponse` ngày 15/10).
  - Mọi dữ liệu v18 cần đều đã có trong request:
    - `annual_inc` → phân vị thu nhập;
    - `loan_amnt`, `term_months`, `interest_method` → tiền trả theo lãi tham chiếu;
    - các trường hồ sơ còn lại giữ nguyên;
    - CIC do AI tự tra qua `so_cccd`/`borrower_id`.
  - Loan chỉ kiểm `decision_policy_version` khác null rồi lưu lại, và lưu `review_reasons` dạng danh sách chuỗi,
    không so với danh sách mã cố định.
  - Thay đổi duy nhất phía Loan là cấu hình `AI_CREDIT_MODEL_VERSION = 18.0.0`.

## 7. Query, phân trang, index

Không áp dụng. Task không thêm truy vấn database. Đường chấm điểm chỉ đọc gói model trong bộ nhớ.

## 8. Triển khai đồng bộ và chạy đồng thời

Task không có transaction database, nhưng có một ràng buộc giống transaction: **model v18, policy V2 và cấu hình
Loan phải lên cùng nhau hoặc cùng quay về**.

```text
Bước 1: dừng nhận chấm điểm mới (hoặc chọn lúc không có hồ sơ đang chờ chấm)
Bước 2: AI lên gói v18 + product_config V2 → khởi động lại AI → AI tự kiểm cặp version khi nạp
Bước 3: Loan đổi AI_CREDIT_MODEL_VERSION = 18.0.0 → khởi động lại Loan
Bước 4: chấm thử 1 hồ sơ demo qua Loan → xác nhận snapshot ghi 18.0.0 / CREDIT_POLICY_V2
Rollback: đảo ngược bước 2–3 về v17 / V1 (gói v17 và config V1 được giữ nguyên)
```

| Tình huống đồng thời | Nguy cơ | Cơ chế | Kết quả |
|---|---|---|---|
| Hồ sơ được Loan gửi chấm đúng lúc AI vừa lên v18, Loan vẫn ở 17.0.0 | Loan nhận kết quả v18 nhưng chờ v17 | Kiểm version hiện có của Loan | Loan coi là lỗi tạm và retry có giới hạn (LN-007). Sau bước 3 thì thông |
| Admin sửa ngưỡng qua `PUT /config/product` trong lúc đổi phiên bản | Ghi đè ngưỡng V2 bằng giá trị cũ | Khai báo model version trong policy. AI từ chối policy lệch model | Admin nhận lỗi rõ, không lưu |
| Hai request chấm điểm song song | Không có trạng thái chung bị ghi | Gói model chỉ đọc, `lru_cache` | Không có xung đột |

## 9. Lỗi, fallback, đối chiếu

- Gói v18 thiếu trường mới (bảng hiệu chỉnh, phân bố thu nhập, biên) → `BoDuDoan.nap()` từ chối, giống cách đang
  từ chối gói thiếu median. Service trả 503 thay vì chấm sai.
- cic-service lỗi: giữ hành vi fail-open hiện có. Chỉ khi cic-service **trả lời rõ** "không có hồ sơ" mới dùng lý
  do `CIC_CHUA_CO_LICH_SU` (Q6).
- Hồ sơ ngoài vùng dữ liệu: vẫn tính PD để thẩm định viên tham khảo, nhưng quyết định là `PENDING_REVIEW`.

## 10. Bảo mật, dữ liệu cá nhân, pháp lý

- Không thêm dữ liệu cá nhân mới. Phân vị thu nhập tính từ thu nhập đã có. Không log thu nhập/CCCD thô
  (`LEGAL-DATA-01`).
- `LEGAL-AI-01`: thay model và policy là thay đổi quyết định tự động, nên phải tăng `decision_policy_version`, lưu
  version trong snapshot và giữ khả năng giải thích. Ngưỡng hạng/duyệt là **policy kinh doanh**, không phải quy
  định pháp luật.
- `LEGAL-OPEN-04` (nguy cơ phân biệt đối xử): v17 có `person_age` và 4 nhóm tuổi, nhưng tuổi trong dữ liệu
  huấn luyện là **suy ra**, không phải quan sát. v18 phải đo thí nghiệm E7 (bỏ tuổi). Dù giữ hay bỏ tuổi, nộp
  bảng PD và tỷ lệ duyệt theo nhóm tuổi làm bằng chứng.
- `LEGAL-LIMIT-01`: không đổi trong task này (Q7).

---

## 11. Lộ trình triển khai theo giai đoạn

Mỗi giai đoạn có đầu ra kiểm được. Không sang giai đoạn sau khi giai đoạn trước chưa đạt nghiệm thu.

### Giai đoạn 0 — Khóa cách đo (0,5 ngày)

Mục tiêu: có một thước đo chung, dùng cho mọi quyết định phía sau, và một con số mốc v17 trung thực.

1. Sao lưu `lc_clean.csv`, thêm cột đánh dấu nguồn dòng: 673.540 dòng thật, 200.000 dòng tổng hợp. Script huấn
   luyện kiểm đúng hai con số này, sai thì dừng.
2. Chuyển 4 script thí nghiệm từ `Đồ Án/thi-nghiem-mo-hinh-15-10/` vào `finora-ai/scripts/` thành **một script
   đánh giá dùng chung**. Script gom các phần sau:
   - chỉ đo trên dòng thật;
   - đầu vào giống lúc chạy (lãi tham chiếu, tính lại tiền trả);
   - chỉ số AUC, KS, Brier, ECE, nợ xấu ở tỷ lệ duyệt 20/40/60/80%, balanced accuracy ở cùng tỷ lệ duyệt;
   - so sánh hai mô hình bằng bootstrap ghép cặp. Bootstrap là lấy mẫu lại tập kiểm định nhiều lần để biết phần
     chênh là thật hay do may rủi.
3. Đo lại v17 bằng script này và lưu kết quả làm mốc. Kỳ vọng: AUC khoảng 0,632, ECE khoảng 0,22.
4. Quy ước **tập khóa**: mọi lựa chọn ở Giai đoạn 1 chỉ dùng fold 2009–2013 → 2014. Năm 2015 chỉ mở một lần ở
   cuối Giai đoạn 2. Lý do: 2015 đã bị dùng để chọn quá nhiều phương án, chọn tiếp trên nó sẽ làm con số lạc quan.

Nghiệm thu: số mốc v17 tái lập được trong ±0,002 so với số đo ngày 15/10. Script chạy lại trên máy sạch cho
cùng kết quả.

### Giai đoạn 1 — Sửa dữ liệu và thí nghiệm lựa chọn (1 ngày)

Sửa dữ liệu, đây không phải lựa chọn:
- **Ngày mở tín dụng:** năm hai chữ số lớn hơn năm phát hành khoản vay thì lùi một thế kỷ. Sau khi sửa không còn
  `so_thang_quan_he` âm ở dòng thật. Nếu Q1 giữ dữ liệu tổng hợp thì loại 3.119 dòng tổng hợp có ngày mở sau ngày
  vay.
- **Nhóm nợ 2:** thêm quy tắc "từng trễ từ 30 ngày trong 24 tháng (`delinq_2yrs` > 0) và không thuộc nhóm 3/4 →
  nhóm 2". Đây là phép ghép gần đúng, vì nhóm 2 của CIC là trễ 10–90 ngày. Phải ghi rõ trong báo cáo.

Thí nghiệm có luật quyết định viết sẵn. Chạy trên fold 2014, ghi kết quả vào mục 16:

| Mã | Thử | Giữ phương án nếu |
|---|---|---|
| E1 | Bỏ dữ liệu tổng hợp / trọng số 0,1 | Theo Q1. Kiểm thêm: PD trên bộ kiểm tra áp lực vẫn tăng khi CIC giảm |
| E2 | Bỏ 3 đặc trưng thẻ (dư nợ thẻ, tỷ lệ sử dụng thẻ, dư nợ thẻ/thu nhập) | Bỏ nếu AUC giảm ≤ 0,003. Lý do: 99,6% người LendingClub có thẻ, phần lớn người Việt không có |
| E3 | Bỏ `term_months` (24 tháng ở đây thực chất là khoản 60 tháng LendingClub) | Bỏ nếu AUC giảm ≤ 0,003; giữ thì ghi hạn chế trong báo cáo |
| E4 | Nhóm nợ 2 mới | Giữ nếu AUC không giảm, và PD trung bình nhóm 2 nằm giữa nhóm 1 và nhóm 3 |
| E5 | Hiệu chỉnh: bỏ `scale_pos_weight` ± isotonic | Theo Q5. Đạt ECE ≤ 0,02 |
| E6 | Lãi tham chiếu 13,11% / median sản phẩm thật | Theo Q2. AUC chênh không quá 0,002 |
| E7 | Bỏ `person_age` + 4 nhóm tuổi (tuổi suy ra, không quan sát) | Bỏ nếu AUC giảm ≤ 0,002. Lý do: giảm rủi ro phân biệt theo tuổi, bớt một biến "bịa" |
| E8 | Chuẩn hoá: thu nhập → phân vị, tiền → tỷ lệ | Theo Q8. Đã đo −0,002; chấp nhận nếu giảm ≤ 0,004 |

Nghiệm thu: bảng E1–E8 có số liệu và quyết định. Bộ đặc trưng v18 được chốt và ghi vào plan.

### Giai đoạn 2 — Huấn luyện v18 (0,5 ngày)

1. `scripts/train_credit_model.py`:
   - phiên bản 18.0.0, chỉ dòng thật (hoặc theo Q1);
   - bỏ lãi LendingClub khỏi cả `int_rate` lẫn `installment`;
   - bộ đặc trưng theo Giai đoạn 1, không `scale_pos_weight`;
   - cập nhật ràng buộc đơn điệu cho đặc trưng mới: phân vị thu nhập cao thì PD không tăng, các tỷ lệ nợ cao
     thì PD không giảm.
2. **Hiệu chỉnh PD đúng cách khi mô hình cuối học trên toàn bộ dữ liệu.** Bộ hiệu chỉnh phải học từ điểm mà mô
   hình **chưa thấy** hồ sơ đó. Cách làm: chia dữ liệu theo năm, mỗi năm được chấm bởi mô hình học từ các năm
   khác (cross-fitting), rồi học bộ hiệu chỉnh trên toàn bộ các điểm "chưa thấy" đó. Lưu bộ hiệu chỉnh dạng bảng
   quy đổi trong metadata, không lưu thêm file nhị phân.
3. Tính **biên vùng dữ liệu** từ dòng thật và lưu vào metadata: CIC thực tế thấp nhất khoảng 545; tỷ lệ vay/thu
   nhập năm p99,9 = 0,5.
4. Ghi metadata mở rộng (mục 3). Phần mô tả nguồn dữ liệu phải nói thật: LendingClub đã chuẩn hoá, tuổi suy ra,
   CIC tổng hợp từ FICO có nhiễu.
5. **Mở tập khóa 2015 một lần**: chạy script đánh giá chung, so với mốc v17. Đây là con số đưa vào báo cáo.
6. Huấn luyện lại trên toàn bộ dòng thật 2009–2015 để ra gói cuối.

Nghiệm thu: các chỉ tiêu "Mô hình" ở mục 12. Nếu không đạt thì quay lại Giai đoạn 1, **không** chỉnh tiếp trên 2015.

### Giai đoạn 3 — Đường chấm điểm (0,5 ngày)

1. `app/ml/credit/predictor.py`:
   - dựng đặc trưng y như huấn luyện: bỏ qua `int_rate` khi đưa vào mô hình; tính tiền trả mỗi tháng theo lãi
     tham chiếu trong gói; đổi thu nhập ra phân vị theo phân bố Việt Nam trong gói; tính các tỷ lệ;
   - áp bộ hiệu chỉnh;
   - trả cờ "ngoài vùng dữ liệu" theo biên trong gói.

   `int_rate` và `installment` thật vẫn chuyển nguyên cho rule engine và chốt chặn.
2. `BoDuDoan.nap()`: kiểm gói có đủ trường mới. Kiểm `product_config.json` khai báo đúng model version. Thiếu hay
   lệch đều từ chối nạp.
3. Đổi phiên bản mặc định sang 18.0.0. Giữ gói v17 trên đĩa.
4. `app/ml/credit/features.py`: danh sách đặc trưng v18, nhóm cột thiếu, nhóm tuổi theo quyết định E7.

Nghiệm thu: test dòng mẫu vàng (bất biến 1), test PD không đổi khi đổi `int_rate`, test thiếu CIC, test gói thiếu
trường bị từ chối, test policy lệch version bị từ chối.

### Giai đoạn 4 — Rule engine và policy (0,5–1 ngày)

1. **Đặt lại ngưỡng hạng/duyệt.** Tính điểm tổng hợp (PD v18 sau hiệu chỉnh × 0,85 + luật × 0,15) trên dữ liệu
   thật ngoài mẫu. Đặt ngưỡng A–E theo tỷ trọng Q4, và ngưỡng tự duyệt/tự từ chối theo tỷ lệ mục tiêu. Kiểm lại
   `risk_weight` 0,15. Chỉ đổi nếu số đo mới cho thấy khác biệt rõ.
2. **Sửa mốc luật CIC** (`CHARACTER_CIC_HISTORY`) sang thang CIC bằng đúng công thức ánh xạ FICO → CIC lúc huấn
   luyện. Kết quả xấp xỉ: 740 → 630, 700 → 586, 670 → 554.
3. **Sửa `scripts/validate_rule_engine.py`** dùng thang CIC như huấn luyện, rồi đo lại. Ngưỡng nghiệm thu cũ AUC
   luật ≥ 0,62. Codex đo trên thang đúng được khoảng 0,615 với mốc cũ, nên có thể phải chỉnh bậc luật cho đạt.
4. **Lý do chuyển thẩm định mới** trong `kiem_tra_yeu_cau_tham_dinh()`: CIC dưới biên vùng dữ liệu; vay/thu nhập
   năm trên biên; chưa có lịch sử CIC (nếu Q6 chọn chuyển thẩm định và cic-service phân biệt được). Mỗi mã có câu
   mô tả tiếng Việt cho thẩm định viên.
5. `decision_policy_version = CREDIT_POLICY_V2`, khai báo model 18.0.0.
6. `config_router`: `PUT /config/product` và `PUT /config/rules` không cho lưu policy lệch model version.

Nghiệm thu: tỷ lệ vỡ nợ giảm dần A → E. Tỷ trọng hạng lệch mục tiêu không quá 3 điểm %. Tỷ lệ
duyệt/chờ/từ chối khớp mục tiêu Q4 ±3 điểm %. AUC luật ≥ 0,62 trên thang đúng.

### Giai đoạn 5 — Giải thích (XAI) và web (0,5 ngày)

1. `app/ml/credit/explainer.py`:
   - thêm nhãn và nhóm cho đặc trưng mới; bỏ nhóm "Lãi suất và cách tính lãi"; danh sách đặc trưng rò rỉ để
     trống;
   - giải thích ghi rõ "PD hiển thị đã hiệu chỉnh; đóng góp tính trên điểm thô".
2. `app/ml/credit/dien_giai.py` và `app/services/credit/truong_du_lieu.py`: ánh xạ nhóm SHAP → luật theo nhóm mới.
   Câu gợi ý về thu nhập không được nói "phân vị" khó hiểu với người vay.
3. `finora-web`: thêm nhãn tiếng Việt cho mã lý do thẩm định mới trong `NHAN_LY_DO_THAM_DINH`
   (`src/features/rule-engine/constants.ts`). Trang chấm điểm hiển thị `review_reasons` qua bảng nhãn này. Cờ "Rò rỉ" tự biến mất,
   không cần sửa. Kịch bản "Lãi suất 25%" trên trang chấm điểm vẫn đúng vì chốt chặn trần 20% còn.
4. Hồ sơ người vay cho nhà đầu tư (Loan + mobile): kiểm hiển thị PD. Hồ sơ cũ (v17) và mới (v18) sẽ khác thang.
   Cân nhắc ghi phiên bản mô hình cạnh PD.

Nghiệm thu: test explainer cập nhật đủ cột (`test_moi_dac_trung_deu_co_nhan`, `test_moi_dac_trung_deu_thuoc_mot_nhom`),
test cộng dồn SHAP đạt. Mở trang chấm điểm trên web với hồ sơ mẫu thấy giải thích hợp lý.

### Giai đoạn 6 — Tích hợp Loan và triển khai (0,5 ngày)

1. Loan: đổi mặc định `AI_CREDIT_MODEL_VERSION` sang 18.0.0 trong `application.yml`. Kiểm docker compose và biến
   môi trường trên VM Azure.
2. Làm đúng trình tự ở mục 8, chọn lúc không có hồ sơ đang chờ chấm.
3. Dữ liệu demo đã nạp vào Neon (`docker/demo-seed`) giữ kết quả v17. Quyết định có nạp lại để ảnh chụp/báo cáo
   thống nhất không. Nếu nạp lại thì dùng script gỡ/nạp hiện có.
4. Diễn tập rollback một lần trên máy local.

Nghiệm thu: luồng nộp hồ sơ → AI → quyết định → định giá → hợp đồng chạy trên máy local và trên Azure. Snapshot ghi
`18.0.0` / `CREDIT_POLICY_V2`. Rollback về v17 chạy được.

### Giai đoạn 7 — Kiểm thử tổng (0,5 ngày)

- `ruff check`, `ruff format --check`, `pytest` toàn bộ `finora-ai`.
- Script đánh giá chung trên 2015 đạt chỉ tiêu mục 12.
- Bộ 8 hồ sơ người Việt thành **test hồi quy**, chỉ kiểm thứ tự và hành vi, không kiểm số cố định:
  - "vay nhiều app" có PD cao hơn nhân viên văn phòng và kỹ sư;
  - hồ sơ CIC 480 ra `PENDING_REVIEW`;
  - PD hai hồ sơ giống hệt nhau chỉ khác lãi sản phẩm phải bằng nhau.
- Bộ kiểm tra áp lực (dữ liệu tổng hợp): PD trung bình tăng dần khi CIC giảm.
- Bảng PD và tỷ lệ duyệt theo nhóm tuổi (bằng chứng cho `LEGAL-OPEN-04`).
- Chạy test Loan liên quan AI (client, kiểm version) sau khi đổi cấu hình.

### Giai đoạn 8 — Tài liệu và báo cáo (1 ngày)

1. `finora-ai/docs`:
   - `PIPELINE_CHAM_DIEM_TIN_DUNG.md`, `QUY_TRINH_LAM_SACH_VA_TAO_DAC_TRUNG.md`: cập nhật theo v18.
   - `TOI_UU_TANG_QUYET_DINH_TIN_DUNG.md`: thêm mục v18. Đánh dấu ba kết luận cũ không còn đúng: "bỏ int_rate chỉ
     giảm 0,0003", "+0,0116 nhờ dữ liệu tổng hợp", "CIC hoàn hảo chỉ +0,002".
2. Báo cáo khóa luận: sửa `scripts-bao-cao-15-10/build.py` rồi build lại, **không sửa tay** docx.
   - Chương mô hình: số liệu mới, bảng so sánh v17 lúc chạy thật với v18, đoạn giải thích vì sao 0,70 → 0,65.
   - Gợi ý câu chữ ở mục 6 của tài liệu bằng chứng.
   - Bỏ accuracy ở ngưỡng 0,9; nếu nêu accuracy thì kèm mức đoán mò 85,11% và balanced accuracy.
3. Bộ test case: sửa `scripts-test-cases/build.py` cho các TC liên quan v17, PD, hạng; build lại.
4. `docs/LEGAL-COMPLIANCE.md`: dòng "Chấm điểm AI v17" đổi thành v18. Đây là vùng chung.
5. `.agents/plans/finora-team-roadmap.md`: thêm hoặc cập nhật mục cho AI-V18. Đây là vùng chung.
6. Plan này: điền mục 15–16, chuyển `READY_FOR_REVIEW`.

---

## 12. Test plan và chỉ tiêu nghiệm thu

| Nhóm | Chỉ tiêu | Mốc v17 (đo 15/10) | Ngưỡng v18 |
|---|---|---|---|
| Mô hình | AUC, dữ liệu thật 2015, đầu vào giống lúc chạy | 0,632 | ≥ 0,645 |
| Mô hình | Chênh AUC với v17, khoảng tin cậy 95% bootstrap | — | cận dưới > 0 |
| Mô hình | KS | 0,192 | ≥ 0,21 |
| Mô hình | ECE / PD trung bình so với thực tế | 0,225 / 37% vs 15% | ≤ 0,02 / lệch ≤ 2 điểm % |
| Mô hình | Nợ xấu trong nhóm duyệt ở tỷ lệ duyệt 20% | 7,18% | ≤ 6,3% |
| Hành vi | PD không đổi khi đổi `int_rate` | PD trung bình 28% → 52% khi lãi cơ sở 8% → 19% | chênh = 0 |
| Hành vi | PD tăng khi CIC giảm (bộ áp lực) | — | đơn điệu |
| Policy | Vỡ nợ giảm dần A → E | — | đơn điệu |
| Policy | Tỷ trọng hạng, tỷ lệ duyệt | — | lệch mục tiêu ≤ 3 điểm % |
| Rule engine | AUC luật, thang CIC đúng | 0,6356 (thang sai) / khoảng 0,615 (thang đúng) | ≥ 0,62 |
| Code | ruff, pytest `finora-ai` | — | sạch, 100% pass |
| Tích hợp | Loan → AI → quyết định → định giá | v17 chạy | v18 chạy local và Azure; snapshot đúng version |
| Rollback | Về v17/V1 | — | chạy được |

---

## 13. Rủi ro và cách giảm

| Rủi ro | Hậu quả | Cách giảm |
|---|---|---|
| Con số báo cáo giảm từ 0,70 xuống khoảng 0,65 | Hội đồng nghĩ mô hình tệ đi | Trình bày cặp số "v17 lúc chạy thật 0,632 → v18 0,653" và nợ xấu nhóm duyệt −16%. Giải thích rò rỉ lãi suất bằng một đoạn ngắn |
| Thang PD đổi làm phân bố hạng đổi | Loan định giá khác hẳn. Nhiều hạng A thì lãi cuối thấp hơn, lợi suất nhà đầu tư đổi | Đặt ngưỡng theo phân vị dân số (Giai đoạn 4) để giữ tỷ trọng hạng |
| Hồ sơ cũ v17 và mới v18 cùng nằm trên sàn | Nhà đầu tư so PD hai thang khác nhau | Hiển thị phiên bản mô hình, hoặc ghi chú trong báo cáo |
| Phân bố thu nhập Việt Nam là giả định | Phân vị lệch nếu người vay FINORA khác lao động trung bình | Lưu nguồn và tham số trong gói. Thay bằng phân bố hồ sơ thật khi có |
| Ghép nhóm nợ 2 từ `delinq_2yrs` là gần đúng | Nhóm 2 mang nghĩa "trễ 30+ ngày", lệch so với định nghĩa CIC (10–90 ngày) | Ghi rõ trong báo cáo. E4 kiểm PD nhóm 2 nằm giữa nhóm 1 và 3 |
| cic-service không phân biệt "không có hồ sơ" | Không làm được Q6 | Tạm giữ hành vi cũ, ghi hạn chế |
| Chọn mô hình lại trên 2015 | Con số lạc quan | Quy ước tập khóa ở Giai đoạn 0 |
| Thiếu thời gian trước hạn nộp | Báo cáo và code lệch nhau | Dùng lộ trình tối thiểu (mục 14), phần còn lại ghi "hướng phát triển" |

## 14. Thời gian ước tính

| Giai đoạn | Đầy đủ | Tối thiểu |
|---|---|---|
| 0 Khóa cách đo | 0,5 ngày | 0,25 ngày (chỉ đo mốc v17) |
| 1 Sửa dữ liệu + thí nghiệm | 1 ngày | 0,25 ngày (chỉ sửa ngày, bỏ dữ liệu tổng hợp; bỏ E2–E4, E7) |
| 2 Huấn luyện v18 | 0,5 ngày | 0,5 ngày |
| 3 Đường chấm điểm | 0,5 ngày | 0,5 ngày |
| 4 Rule engine, policy | 0,5–1 ngày | 0,25 ngày (chỉ đặt lại ngưỡng + version) |
| 5 XAI, web | 0,5 ngày | 0,25 ngày (chỉ sửa nhãn cho test đạt) |
| 6 Tích hợp, triển khai | 0,5 ngày | 0,25 ngày |
| 7 Kiểm thử tổng | 0,5 ngày | 0,25 ngày |
| 8 Tài liệu, báo cáo | 1 ngày | 0,5 ngày (chỉ chương mô hình) |
| **Tổng** | **khoảng 5–5,5 ngày** | **khoảng 3 ngày** |

Lộ trình tối thiểu vẫn giữ ba thay đổi tạo ra mức cải thiện đã đo:
- bỏ lãi LendingClub;
- bỏ dữ liệu tổng hợp;
- hiệu chỉnh PD và đặt lại ngưỡng.

Chuẩn hoá thu nhập (E8) nên làm nếu còn nửa ngày, vì đây là phần giúp người Việt không bị xếp sai.

---

## 15. Bản đồ code thực tế sau triển khai

*(Điền sau khi code: chuỗi hàm từ endpoint → predictor → rule engine, các trường metadata thực tế, test chứng minh.)*

## 16. Kết quả thí nghiệm, sai khác so với dự kiến và bằng chứng

*(Điền bảng E1–E8 ở Giai đoạn 1, số đo cuối ở Giai đoạn 2, và mọi chỗ làm khác plan.)*

---

## Phụ lục — Thuật ngữ

| Thuật ngữ | Hiểu đơn giản | Trong FINORA |
|---|---|---|
| Target leakage (rò rỉ nhãn) | Đặc trưng chứa sẵn đáp án, nên mô hình "ăn gian" lúc kiểm tra | `int_rate` của LendingClub chứa hạng tín dụng họ đã chấm |
| Train/serve skew | Lúc học và lúc dùng, cùng một cột mang nghĩa khác nhau | Học với lãi theo hạng, chạy với lãi cơ sở sản phẩm |
| PD | Xác suất vỡ nợ: 12% nghĩa là khoảng 12/100 người như vậy không trả hết nợ | Hiển thị cho thẩm định viên và nhà đầu tư |
| Hiệu chỉnh (calibration), isotonic | Bảng quy đổi điểm thô thành xác suất thật, chỉ giữ thứ tự, không đảo thứ hạng | PD 33% của v17 thực ra ứng với khoảng 15% |
| ECE | Sai lệch trung bình giữa PD dự đoán và tỷ lệ vỡ nợ thật, tính theo nhóm | 0,225 ở v17 → mục tiêu ≤ 0,02 |
| AUC, KS | Khả năng xếp người rủi ro xuống dưới. 0,5 là đoán bừa | Chỉ số chính để so mô hình |
| Bootstrap ghép cặp | Lấy mẫu lại tập kiểm định hàng trăm lần, xem phần chênh giữa hai mô hình có luôn dương không | Chứng minh v18 hơn v17 không phải do may |
| Tập khóa (holdout) | Dữ liệu cất đi, chỉ mở một lần để chấm điểm cuối | Năm 2015 |
| Cross-fitting | Mỗi phần dữ liệu được chấm bởi mô hình không học phần đó | Để học bộ hiệu chỉnh mà không tự chấm bài của mình |
| Ngoài vùng dữ liệu (out-of-distribution) | Hồ sơ khác hẳn mọi hồ sơ mô hình từng học | CIC < 545, vay > 50% thu nhập năm |
| Phân vị (quantile mapping) | So người với người trong cùng quốc gia thay vì quy đổi tiền | Thu nhập 15tr/tháng ≈ phân vị 90 ở Việt Nam |
| Ràng buộc đơn điệu | Ép mô hình: yếu tố xấu hơn thì PD không được giảm | Đã có cho trễ hạn, dư nợ; v18 thêm cho đặc trưng mới |
