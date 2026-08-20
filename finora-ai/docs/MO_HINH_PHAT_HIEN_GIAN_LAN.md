# Mô hình phát hiện gian lận giao dịch ví (P7-B05)

Tài liệu này mô tả gói model `models/fraud/model_v1.1.0`, cách nó được huấn luyện
và — quan trọng nhất — **những gì con số của nó không nói**.

## 1. Bài toán

Chấm rủi ro gian lận cho một giao dịch ví của `finora-payment`, trả về xác suất
kèm bằng chứng. `finora-ai` không chặn giao dịch và không khóa ví: theo
`.agents/rules/07-service-boundaries.md`, service này sở hữu *fraud technical
result*, còn hành động thuộc Payment.

Endpoint: `POST /api/v1/ai/fraud/detect`

## 2. Dữ liệu

PaySim (`data/paysim.csv`) — nhật ký mô phỏng của một dịch vụ mobile money,
6.362.620 giao dịch trong 30 ngày, có nhãn `isFraud` sẵn. Schema trùng nghiệp vụ
ví: loại giao dịch, số tiền, số dư trước/sau của bên gửi và bên nhận.

Bốn phát hiện từ khảo sát, mỗi phát hiện dẫn tới một quyết định thiết kế:

| Phát hiện | Số đo | Quyết định |
|---|---|---|
| Gian lận chỉ có ở `TRANSFER` và `CASH_OUT` | 0,769% và 0,184%; `PAYMENT`/`CASH_IN`/`DEBIT` đúng 0 ca | Lọc còn 2 loại → 2.770.409 dòng, tỷ lệ gian lận 0,2965% |
| Ví gửi gần như không lặp lại | 6.353.307 ví duy nhất / 6.362.620 dòng | **Bỏ** đặc trưng vận tốc theo ví gửi — vô dụng |
| Ví nhận lặp tới 113 lần | 2.722.362 ví duy nhất | Dùng lịch sử ví nhận (đặc trưng mule account) |
| Tỷ lệ gian lận theo giờ dao động mạnh | 0,05% → 22,3% | Giữ giờ trong ngày và cờ giờ đêm |

## 3. ⚠️ Đẳng thức rò rỉ của trình mô phỏng

**Đây là phần quan trọng nhất của tài liệu.**

Trong PaySim, điều kiện `amount == oldbalanceOrg` (chuyển đi đúng bằng toàn bộ số
dư) đúng với:

- **97,82%** giao dịch gian lận
- **0,00%** giao dịch bình thường

Một câu lệnh `if` đã gần như giải xong bài toán. Đó là **luật sinh dữ liệu của
trình mô phỏng**, không phải quy luật của gian lận thật: kẻ gian ngoài đời không
bắt buộc phải rút đúng đến đồng cuối cùng.

Vì vậy `app/ml/fraud/features.py` tách hai nhóm đặc trưng:

- `DAC_TRUNG_HANH_VI` (14 cột) — **gói đem triển khai**
- `DAC_TRUNG_RO_RI` (4 cột: `rut_can_tai_khoan`, `ty_le_tren_so_du`,
  `so_du_sau_gui`, `so_du_sau_nhan`) — **chỉ dùng đối chứng**, không vào gói

Test `test_cot_ro_ri_khong_lot_vao_goi_trien_khai` giữ bất biến này.

## 4. Quy trình huấn luyện

Chạy: `python scripts/train_fraud_model.py`

- **Chia theo thời gian**, không xáo trộn — cùng triết lý out-of-time với mô hình
  tín dụng. Ranh giới `step`: train ≤ 323, hiệu chỉnh ≤ 354, validate > 354.
- **Ngưỡng cắt chọn trên tập hiệu chỉnh**, tách khỏi validate. Chọn ngưỡng rồi báo
  cáo trên chính tập đó là tự chấm bài của mình.
- **Tối ưu F2, không phải F1** (từ v1.1.0). F1 coi bỏ lọt gian lận và báo động nhầm
  tốn kém ngang nhau — không đúng nghiệp vụ: bỏ lọt là mất tiền thật và phải bồi
  thường, còn báo nhầm chỉ khiến khách nhập thêm một bước xác thực. F2 coi recall
  quan trọng gấp đôi precision. Xem `BETA_NGUONG` trong script.
- **Cân bằng lớp bằng `scale_pos_weight`** (= 534,8), không lấy mẫu lại. Với tỷ lệ
  dương phần nghìn, undersampling lớp âm sẽ vứt bỏ hàng triệu giao dịch hợp lệ —
  tức vứt bỏ chính thông tin định nghĩa "thế nào là bình thường".
- Thuật toán: XGBoost có giám sát. Docstring cũ dự kiến Isolation Forest (không
  giám sát); đổi vì dữ liệu **có nhãn**, và quan trọng hơn: Isolation Forest chỉ
  trả điểm bất thường nên không tính được precision/recall — tức không kiểm chứng
  được.

| Tập | n | Gian lận | Tỷ lệ |
|---|---|---|---|
| train | 1.951.895 | 3.643 | 0,1866% |
| hiệu chỉnh | 266.010 | 312 | 0,1173% |
| validate | 552.504 | 4.258 | 0,7707% |

## 5. Kết quả (đo trên tập validate)

| Chỉ số | `hanh_vi` (triển khai) | `day_du` (có rò rỉ) |
|---|---|---|
| **AUC-PR** | **0,9176** | 1,0000 |
| AUC-ROC | 0,9981 | 1,0000 |
| KS | 0,9575 | 1,0000 |
| **Precision** | **0,9034** | 0,9932 |
| **Recall** | **0,8321** | 1,0000 |
| **F1** | **0,8663** | 0,9966 |
| Accuracy | 0,9980 | 0,9999 |
| *Accuracy baseline* | *0,9923* | *0,9923* |
| *Chênh so với baseline* | *+0,0057* | *+0,0077* |

Ngưỡng quyết định: **0,922867** (tối ưu F2 trên tập hiệu chỉnh).

### v1.0.0 → v1.1.0: cùng model, khác điểm vận hành

| | v1.0.0 (F1) | v1.1.0 (F2) |
|---|---|---|
| Ngưỡng | 0,969341 | **0,922867** |
| Recall | 0,8006 | **0,8321** (+3,15 điểm) |
| Precision | 0,9417 | 0,9034 (−3,83 điểm) |
| F1 | 0,8654 | **0,8663** |

SHA-256 của hai file `.pkl` **giống hệt nhau**: cùng dữ liệu, cùng seed, cùng siêu
tham số nên mô hình huấn luyện ra y hệt. v1.1.0 không phải mô hình mới mà là **cùng
một mô hình ở điểm vận hành khác** — đó là lý do đổi ngưỡng lại "rẻ" đến vậy.

### Bảng đánh đổi cho Payment chọn điểm vận hành

Ghi trong gói tại khóa `bang_danh_doi_nguong`. Ngưỡng chọn trên tập hiệu chỉnh, chỉ
số đo trên validate:

| Nhắm recall | Ngưỡng | Recall thực | Precision | Accuracy vs baseline |
|---|---|---|---|---|
| 85% | 0,731245 | 0,8852 | 0,7405 | +0,0044 |
| 90% | 0,586822 | 0,9070 | 0,6099 | +0,0025 |
| 95% | 0,319932 | 0,9523 | 0,4089 | **−0,0033** |
| 99% | 0,069067 | 0,9868 | 0,1917 | **−0,0245** |

Hai điều bảng này nói:

1. **Có một điểm gãy giữa 85% và 90% recall.** Đi từ 83%→88,5% mất khoảng 16 điểm
   precision; từ đó lên 90,7% mất thêm 13 điểm nữa; qua 95% thì precision sụp.
2. **Từ recall 95% trở lên, `chenh_so_voi_baseline` chuyển sang ÂM** — mô hình bắt
   đầu kém hơn cả việc đoán "không giao dịch nào gian lận". Không có cấu hình nào
   vừa cho recall rất cao vừa giữ được cả ba chỉ số ở mức cao.

Chọn điểm nào là **quyết định chính sách của `finora-payment`**, không phải của
`finora-ai` (`07-service-boundaries.md`). Ngưỡng trong gói chỉ là mặc định hợp lý.

### Đọc bảng này cho đúng

1. **Chỉ số xếp hạng là `auc_pr`, không phải `auc_roc`.** Với 99,2% giao dịch là
   âm tính, thêm hàng chục nghìn âm tính đúng gần như không đổi FPR, nên ROC-AUC
   bị thổi phồng. `auc_pr` = 0,9176 so với baseline 0,0077 — gấp **119 lần** mô
   hình ngây thơ. Đó mới là con số đáng kể.

2. **Accuracy vẫn gần như vô nghĩa, y hệt bài toán tín dụng.** 0,9981 nghe rất
   đẹp nhưng baseline đã là 0,9923: đoán "không giao dịch nào gian lận" đã đúng
   99,23%. Phần đóng góp thật của mô hình chỉ là **+0,58 điểm phần trăm**. Trường
   `chenh_so_voi_baseline` trong gói model tồn tại để không ai đọc nhầm con số này.

3. **Precision và Recall mới là chỗ khác biệt so với mô hình tín dụng.** Model
   credit v15 đạt precision 0,247 / recall 0,525 — trần của dữ liệu. Model fraud
   đạt **0,903 / 0,832** mà không dùng cột rò rỉ. Cùng một khung đánh giá, hai bài
   toán, hai kết quả khác hẳn — vì tín hiệu trong dữ liệu khác hẳn.

4. **Cột `day_du` cho thấy giá của sự trung thực.** Bỏ 4 cột rò rỉ làm recall rơi
   từ 1,0000 xuống 0,8321. Chênh lệch đó chính là phần hiệu năng đến từ artifact
   của trình mô phỏng chứ không từ hành vi gian lận.

## 5b. Thí nghiệm mở rộng đặc trưng — kết quả âm

Đã thử thêm 6 đặc trưng AML, tất cả đều không rò rỉ và đều tính được lúc chạy thật:
`dest_gio_ke_tu_lan_nhan_truoc` (ví ngủ quên bỗng hoạt động),
`dest_so_lan_nhan_24h` và `dest_tong_tien_nhan_24h` (dồn tiền gấp),
`dest_tb_tien_nhan_truoc_do` và `ty_le_so_tien_tren_tb_dest` (lệch so với lịch sử),
`so_tien_tron_nghin` (structuring).

| Bộ đặc trưng | AUC-PR | Precision tại recall 90% |
|---|---|---|
| 14 đặc trưng hiện tại | **0,9176** | **0,6606** |
| + 6 đặc trưng AML | 0,8999 | 0,5395 |

**Thêm vào làm mô hình kém đi**, rõ rệt nhất ở vùng recall cao. Giả thuyết về mule
account và structuring không đúng với PaySim — nhiều khả năng vì trình mô phỏng
không sinh hành vi ví trung gian một cách thực tế, nên 6 cột đó chỉ thêm nhiễu để
mô hình quá khớp tập train (0,1866% gian lận) rồi không chuyển được sang tập validate
(0,7707%). **Không đưa vào gói.**

Ghi lại kết quả âm này có chủ đích: nó cho thấy hướng mở rộng đã được thử và bị bác
bỏ bằng số đo, thay vì bị bỏ qua.

## 6. Hạn chế đã biết

Ba điểm phải nêu khi báo cáo, không được giấu:

1. **Đơn vị tiền chưa được neo.** PaySim không công bố đơn vị tiền tệ, nên
   `he_so_quy_doi_tien = 1,0` và predictor nhận số tiền trên **thang PaySim, không
   phải VND**. Khác mô hình tín dụng — nơi LendingClub là USD nên quy đổi bằng hệ
   số thu nhập trung bình có căn cứ. Trước khi chạy thật với ví FINORA **bắt buộc**
   hiệu chỉnh lại hệ số này hoặc huấn luyện lại trên dữ liệu thật; bỏ qua sẽ gây
   train/serve skew mà service không báo lỗi.

2. **Tỷ lệ gian lận lệch giữa các tập.** Tập hiệu chỉnh có 0,1173% gian lận còn
   validate có 0,7707% — gấp 6,6 lần. Ngưỡng chọn trên tập thưa rồi áp lên tập dày
   sẽ có xu hướng làm precision đo được **cao hơn** thực tế vận hành ổn định.

3. **PaySim là dữ liệu mô phỏng.** Kể cả sau khi loại nhóm rò rỉ, đây vẫn không
   phải hành vi gian lận của người thật trên nền tảng thật.

## 7. Việc còn lại

- `finora-payment` cần cung cấp **payment behavior contract**: ba trường
  `dest_so_lan_nhan_truoc_do`, `dest_tong_tien_nhan_truoc_do`,
  `dest_so_nguoi_gui_khac_nhau_truoc_do`. Thiếu thì gói vẫn chấm được bằng median,
  nhưng độ chính xác giảm.
- Cập nhật trạng thái P7-B05 trong `.agents/plans/finora-team-roadmap.md` và luồng
  liên service trong `.agents/rules/08-cross-service-flows.md` — hai file thuộc
  vùng dùng chung, cần owner còn lại review trước khi sửa.
- `/check-document` (phát hiện giấy tờ giả bằng ELA) — roadmap P7-B06, chưa làm.
