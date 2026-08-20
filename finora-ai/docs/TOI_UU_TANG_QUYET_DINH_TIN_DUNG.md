# Tối ưu tầng quyết định tín dụng

Tài liệu này ghi lại việc hiệu chỉnh `config/product_config.json` và — quan trọng
hơn — **toàn bộ các hướng cải thiện đã thử và bị bác bỏ bằng số đo**.

Mọi con số đo trên fold out-of-time: train 2009–2014 → validate 2015 (283.014 hồ sơ,
tỷ lệ vỡ nợ 14,89%).

## 1. Vì sao phải hiệu chỉnh

Ba tham số trong `product_config.json` chưa từng được đặt bằng số liệu. Đo lại thấy
ba vấn đề:

**a) Rule engine đang làm hỏng thứ hạng**

| Nguồn điểm | AUC |
|---|---|
| Chỉ PD (mô hình ML) | 0,6914 |
| Chỉ `risk_score` 5C | **0,5616** — gần như ngẫu nhiên |
| Trộn 60/40 (cấu hình cũ) | 0,6756 |

Trộn 5C vào làm **mất 0,0158 AUC**. Nguyên nhân: bốn yếu tố 5C tính từ
`annual_inc`, `loan_amnt`, `emp_length`, `home_ownership`, `person_age` — cả năm
biến này **đều đã nằm trong 47 đặc trưng của mô hình**. Rule engine chỉ là một hàm
bậc thang thô của những biến mô hình đã dùng tối ưu hơn; trộn vào chỉ pha loãng.

**b) Hạng A thực tế không tồn tại**

Với ngưỡng cũ `min_score = 94`, chỉ **96 / 283.014** hồ sơ (0,03%) đạt hạng A. Hạn
mức 100 triệu của hạng A gần như không bao giờ được cấp.

**c) Gần một nửa hồ sơ phải thẩm định thủ công**

Ngưỡng cũ 80/60: APPROVED 6,0% · **PENDING_REVIEW 47,5%** · REJECTED 46,5%.

## 2. Nhưng chốt chặn cứng thì hoạt động tốt — MUST NOT bỏ

| Nhóm | n | Tỷ lệ vỡ nợ |
|---|---|---|
| Bị `kiem_tra_chot_chan_cung` chặn | 6.182 (2,18%) | **29,26%** |
| Còn lại | 276.832 | 14,56% |

Gấp đôi tỷ lệ vỡ nợ. Quan trọng hơn: phần lớn các quy tắc này là **tuân thủ pháp
luật**, không phải chấm điểm rủi ro — trần lãi suất 20%/năm (Điều 468 Bộ luật Dân
sự 2015) và trần kỳ hạn 24 tháng (Nghị định 94/2025/NĐ-CP). Bỏ chúng đi nghĩa là ký
hợp đồng vô hiệu, không phải chấp nhận thêm rủi ro.

## 3. Cấu hình mới

| Tham số | Cũ | Mới |
|---|---|---|
| `pd_weight` / `risk_weight` | 0,60 / 0,40 | **0,85 / 0,15** |
| Ngưỡng hạng A / B / C / D | 94 / 65 / 50 / 35 | **84 / 70 / 53 / 40** |
| `auto_approve` / `auto_reject` | 80 / 60 | **75 / 70** |

`pd_weight = 0,85` lấy được 88% phần lợi ích của việc bỏ hẳn 5C (AUC 0,6863 so với
0,6882), nhưng vẫn giữ rule engine hiện diện thật trong công thức. Đo tại
`pd_weight = 1,0` chỉ hơn được **+0,0019** — không đủ để đánh đổi lấy việc biến tầng
quyết định thành một phép nhân duy nhất.

Ngưỡng hạng đặt theo **phân vị dân số** mục tiêu 5% / 25% / 40% / 22% / 8%.

## 4. Kết quả sau hiệu chỉnh

Đo bằng chính `tinh_diem_tong_hop()`, `xep_hang()`, `quyet_dinh()` với config mới:

| Hạng | n | % hồ sơ | Tỷ lệ vỡ nợ | Lift | Hạn mức |
|---|---|---|---|---|---|
| A | 14.964 | 5,3% | **2,85%** | 0,19x | 100.000.000 |
| B | 67.220 | 23,8% | 6,22% | 0,42x | 80.000.000 |
| C | 113.224 | 40,0% | 13,46% | 0,90x | 40.000.000 |
| D | 63.192 | 22,3% | 22,69% | 1,52x | 20.000.000 |
| E | 24.414 | 8,6% | **32,53%** | 2,19x | 0 |

| Quyết định | n | % hồ sơ | Tỷ lệ vỡ nợ | | Cũ |
|---|---|---|---|---|---|
| APPROVED | 53.267 | **18,8%** | 4,39% | | 6,0% @ 3,59% |
| PENDING_REVIEW | 28.723 | **10,1%** | 7,84% | | 47,5% |
| REJECTED | 201.024 | 71,0% | 18,67% | | 46,5% |

Duyệt tự động **gấp 3 lần**, gánh nặng thẩm định thủ công **giảm 4,7 lần**. Đổi lại
tỷ lệ từ chối tăng từ 46,5% lên 71,0% — đây là lựa chọn chính sách, không phải kết
quả kỹ thuật, và đổi được bằng cách sửa hai số trong config.

**AUC tầng quyết định: 0,6722 → 0,6863 (+0,0141).**

## 5. Bảy hướng cải thiện MÔ HÌNH đã thử và bị bác bỏ

Đây là phần đáng đọc nhất. Mọi hướng đều được đo, không hướng nào được suy đoán.

| # | Hướng | Kết quả |
|---|---|---|
| 1 | Thêm **mọi** cột LendingClub chưa dùng, kể cả `sub_grade` | +0,004 |
| 2 | Gấp đôi dữ liệu (ngoại suy learning curve 4 điểm đo) | +0,003 |
| 3 | 4 đặc trưng dẫn xuất: PTI, hạn mức thẻ, số dư khả dụng, tổng nghĩa vụ | **+0,000** |
| 4 | CIC **hoàn hảo** — bỏ nhiễu Gaussian, bỏ 15% NaN | +0,002 |
| 5 | Kiểm tra `term_months` bị đổi nhãn có làm hỏng `installment` không | Không có lỗi |
| 6 | Ensemble đa họ (XGBoost + HistGradientBoosting + LogisticRegression) | +0,001 |
| 7 | Dò lại siêu tham số — 5 biến thể quanh cấu hình hiện tại | +0,000 |

Chi tiết mục 6 và 7:

| Cấu hình | AUC |
|---|---|
| XGBoost v15 (có ràng buộc đơn điệu) | 0,6900 |
| HistGradientBoosting | 0,6905 |
| LogisticRegression | 0,6830 |
| **XGBoost + HistGradientBoosting** (tốt nhất) | **0,6911** |
| Cả ba họ | 0,6903 |
| `max_depth=7` | 0,6879 |
| `n_estimators=1500, lr=0.015` | 0,6903 |
| `reg_lambda=10, min_child_weight=20` | 0,6902 |
| `max_depth=4, n_estimators=2000` | 0,6891 |

Ensemble tốt nhất hơn +0,0011, nhưng phần lớn mức đó đến từ việc
HistGradientBoosting **không mang ràng buộc đơn điệu** chứ không phải từ tính đa
dạng thật: XGBoost bỏ ràng buộc cũng đạt 0,6914. Nói cách khác, ensemble chỉ đang
âm thầm trả lại cái giá 0,0014 mà ta **cố ý** trả để giữ tính giải thích được.
Không đáng đánh đổi: gấp đôi thời gian suy luận, gấp đôi gói model, và mất bảo đảm
đơn điệu trên một nửa ensemble.

## 6. Nguyên nhân gốc: dữ liệu bị cắt cụt dải

```
fico_score trong lc_clean.csv: n=671.728  min=662  max=848
```

Trên thang FICO 300–850, dữ liệu chỉ trải **662–848**. LendingClub đã sàng lọc
trước và chỉ duyệt người vay tín dụng khá trở lên. Nhóm rủi ro nhất — chính là nhóm
mà điểm tín dụng phân biệt tốt nhất — **không bao giờ xuất hiện trong dữ liệu**.

Đây là hiện tượng *range restriction*, và nó giải thích cả hai điều: vì sao trần AUC
là ~0,70, và vì sao mục 3 và 4 ở trên đều cho gần bằng không.

**Kết luận:** trần ~0,69–0,70 là giới hạn của **bộ dữ liệu huấn luyện**, không phải
của phương pháp. Con số +0,0017 ở mục 4 đo giá trị của CIC *trong dữ liệu
LendingClub*; điểm CIC thật ở Việt Nam trải đủ 150–750 nên trên tập khách hàng thật
của FINORA, CIC nhiều khả năng đáng giá hơn hẳn.

## 7. Điều rút ra

Tổng mức cải thiện đạt được từ phía **mô hình**: nhiều nhất +0,001 (và không đáng
lấy). Tổng mức cải thiện đạt được từ phía **tầng quyết định**: **+0,0141** — gấp
hơn 10 lần.

Khi mô hình đã chạm trần dữ liệu, đòn bẩy còn lại không nằm ở thuật toán mà ở cách
điểm số được **sử dụng**: trọng số trộn, ngưỡng xếp hạng, ngưỡng duyệt.
