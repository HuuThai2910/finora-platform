# STATS-001: API thống kê cho trang quản trị

| Mục | Nội dung |
|---|---|
| Trạng thái | READY_FOR_REVIEW (Hải yêu cầu 2026-10-08 "viết rồi làm luôn"; đã chạy thật qua gateway trên Neon) |
| Module | `finora-common` (dùng chung), `finora-loan`, `finora-user`, `finora-investment`, `finora-web` |
| Không đụng | `finora-payment`, `finora-ai`, gateway (route sẵn có đã phủ các path mới), migration |
| Ownership | Thái giao Hải làm toàn bộ, kể cả `finora-loan` và `finora-common`; không chờ Thái duyệt (Hải xác nhận 2026-10-08) |

## 1. Lớp nghiệp vụ

**Ai dùng:** quản trị viên trên finora-web.

**Họ muốn gì:** nhìn một lần biết danh mục cho vay đang thế nào và thay đổi ra sao theo thời gian:
dư nợ, tỷ lệ nợ xấu, hồ sơ nộp/duyệt/từ chối mỗi ngày, tiền giải ngân, vốn nhà đầu tư góp, giá trị khớp
trên chợ Notes, người dùng mới và eKYC theo tuần, phân bố điểm tín dụng.

**Hiện trạng:** backend không có API thống kê nào ngoài `/admin/users/stats` và
`/investments/admin/order-books/summary`. Web đang đếm bằng cách gọi API danh sách với `size=1` rồi đọc
`totalElements` (mỗi ô số một lần gọi), và **ẩn** mọi biểu đồ theo thời gian.

**Sau task:** mỗi service trả số liệu của chính nó qua hai loại API:

- **summary**: ảnh chụp hiện tại (đếm theo trạng thái, dư nợ, phân bố). Ví dụ: "đang có 31 khoản vay
  hoạt động, dư nợ 2,1 tỷ, 6,4% là nợ xấu (nhóm 3 đến 5)".
- **series**: chuỗi theo cột thời gian `DAY | WEEK | MONTH` trong khoảng `from..to`. Ví dụ: "ngày 3/10 có
  4 hồ sơ nộp, 2 được duyệt, giải ngân 85 triệu".

**Failure người dùng thấy:** khoảng ngày sai (from sau to, quá 366 cột) trả 400 `STATISTICS_RANGE_INVALID`;
web hiện thông báo lỗi kèm mã trong ô biểu đồ, các ô khác vẫn chạy. Không phải admin: 403.

**Giới hạn trung thực (không giả lập):**

- Dư nợ quá hạn theo nhóm nợ chỉ có **hiện tại**. Không có bảng lưu lịch sử projection nên không vẽ được
  "6 tháng qua" như mockup; web hiện ảnh chụp hiện tại và ghi rõ.
- Số dư nợ lấy từ `loan_servicing_projections` (bản sao đồng bộ từ Fineract). Projection `stale` được đếm
  riêng (`staleProjections`) để web cảnh báo "số liệu có thể cũ".
- Không có API thống kê Payment (dòng tiền ví) trong task này.

## 2. Lớp kỹ thuật

### 2.1 Phần dùng chung: `finora-common/statistics`

- `StatisticsBucket` (`DAY`, `WEEK`, `MONTH`): `sqlUnit()` cho `date_trunc`, `startOf`, `next`. Tuần bắt đầu
  thứ Hai, trùng `date_trunc('week')` của PostgreSQL.
- `StatisticsPeriod.of(from, to, bucket, clock)`: mặc định 30 ngày gần nhất theo giờ Việt Nam; nới `from`
  về đầu cột, `to` tới cuối cột; tối đa 366 cột; `startInclusive()`/`endExclusive()` là `Instant`;
  `dense(map, empty)` trải đủ cột kể cả cột 0.
- Vì sao đặt ở common: ba service cần cùng một cách cắt cột; mỗi nơi tự viết sẽ lệch múi giờ hoặc tuần.

**Cắt cột theo giờ Việt Nam trong SQL** (DB lưu UTC, cột `timestamptz`):

```sql
date_trunc(:unit, submitted_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS bucket_start
... WHERE submitted_at >= :startInclusive AND submitted_at < :endExclusive
GROUP BY 1
```

Lọc bằng `Instant` thô trên cột gốc (không bọc hàm) để dùng được index trên cột thời gian nếu có.
`unit` lấy từ enum nên ghép chuỗi an toàn; vẫn ưu tiên truyền tham số khi driver cho phép.

### 2.2 Hợp đồng REST

Mọi endpoint: chỉ đọc, `GET`, yêu cầu quyền admin theo đúng cách module đang bảo vệ API admin khác.
Tiền là `BigDecimal` scale 2 (JSON number như các response khác của module). Tham số
`from`, `to` dạng `YYYY-MM-DD` (không bắt buộc), `bucket` (`DAY` mặc định). Response series luôn có
`from`, `to` (đã nới), `bucket`, `timezone` = `"Asia/Ho_Chi_Minh"` và `points` đủ mọi cột, `bucketStart` dạng `YYYY-MM-DD`.

#### Loan: `GET /api/v1/admin/loan-statistics/summary`

```json
{
  "asOf": "2026-10-08T09:30:00Z",
  "applications": {
    "total": 195,
    "byStatus": {"PENDING_REVIEW": 23, "APPROVED": 97, "REJECTED": 67, "WITHDRAWN": 8},
    "byFundingStatus": {"REQUESTED": 26, "FULLY_FUNDED": 55}
  },
  "portfolio": {
    "loansByStatus": {"ACTIVE": 28, "DEFAULTED": 4, "SETTLED": 2},
    "outstandingLoans": 32,
    "principalOutstanding": 2100000000.00,
    "totalOutstanding": 2250000000.00,
    "overdueAmount": 180000000.00,
    "nplPrincipalOutstanding": 134000000.00,
    "nplRatioPercent": 6.38,
    "staleProjections": 0,
    "byDebtGroup": [{"debtGroup": 1, "loans": 20, "principalOutstanding": 0, "overdueAmount": 0}],
    "byCreditGrade": [{"grade": "A", "loans": 3, "principalOutstanding": 0}],
    "byProduct": [{"productId": 3, "productCode": "VAY_TIEU_DUNG_NHANH", "productName": "Vay tiêu dùng nhanh",
                   "applications": 40, "outstandingLoans": 6, "principalOutstanding": 0,
                   "nplPrincipalOutstanding": 0, "nplRatioPercent": 0.00}]
  },
  "collections": {"openCases": 14, "openByStage": {"EARLY": 6}, "byStatus": {"OPEN": 14}},
  "reschedules": {"byStatus": {"PENDING_REVIEW": 5}},
  "reconciliationIncidents": {"byStatus": {"OPEN": 2}},
  "creditScores": {
    "assessed": 189,
    "byGrade": {"A": 8, "B": 79, "C": 80, "D": 19, "E": 3},
    "histogram": [{"from": 0, "to": 10, "count": 0}, {"from": 90, "to": 100, "count": 4}]
  }
}
```

Định nghĩa:

- **Khoản vay còn dư nợ** (`outstandingLoans`): `finora_loans` chưa đóng (`closed_at IS NULL`, trạng thái
  không phải tất toán) có projection. Dư nợ, quá hạn cộng từ `loan_servicing_projections`.
- **Nhóm nợ**: dùng đúng hàm phân nhóm theo số ngày quá hạn đang có trong `LoanServicingSyncService.debtGroup`
  (tách ra chỗ dùng chung trong module, không viết công thức thứ hai). `byDebtGroup` luôn đủ nhóm 1 đến 5.
- **Nợ xấu (NPL)**: dư nợ gốc của khoản thuộc nhóm 3 đến 5. `nplRatioPercent` = NPL / dư nợ gốc × 100, 2 chữ
  số, `null` khi dư nợ bằng 0.
- **Hạng tín dụng của khoản vay**: `loan_applications.pricing_credit_grade`; thiếu thì `"UNGRADED"`.
- **Phân bố điểm**: `evaluation_score` (thang 0 đến 100) của lần chấm **mới nhất thành công** mỗi hồ sơ
  (`latest_credit_assessment_id`), 10 cột rộng 10, cột cuối gồm cả 100; luôn đủ 10 cột.

#### Loan: `GET /api/v1/admin/loan-statistics/series?from&to&bucket`

```json
{
  "from": "2026-09-09", "to": "2026-10-08", "bucket": "DAY", "timezone": "Asia/Ho_Chi_Minh",
  "points": [{"bucketStart": "2026-10-03", "applicationsSubmitted": 4, "applicationsSubmittedAmount": 210000000.00,
              "applicationsApproved": 2, "applicationsRejected": 1, "loansDisbursed": 1, "disbursedAmount": 85000000.00}],
  "productPoints": [{"bucketStart": "2026-10-03", "productId": 3, "applicationsSubmitted": 2,
                     "applicationsSubmittedAmount": 100000000.00, "disbursedAmount": 0}],
  "funnel": {"submitted": 60, "scored": 58, "approved": 31, "termsAccepted": 25,
             "fundingRequested": 22, "fullyFunded": 15, "disbursed": 12}
}
```

- Nộp theo `submitted_at`; `applicationsSubmittedAmount` = tổng `requested_amount` của chính các hồ sơ đó (cùng câu
  với số hồ sơ), scale 2, `0.00` khi cột không có hồ sơ; có ở cả `points` và `productPoints`. Duyệt/từ chối theo thời điểm quyết định (`admin_decided_at`, nếu không có thì
  `automated_decided_at`) với trạng thái hiện tại `APPROVED`/`REJECTED`; giải ngân theo `finora_loans.disbursed_at`.
- `productPoints` chỉ gồm dòng khác 0 (thưa), web ghép theo `productId`.
- `funnel` là **cohort**: hồ sơ nộp trong khoảng, đếm số đã tới từng bước (chấm điểm xong, được duyệt,
  điều khoản `ACCEPTED`/`AUTO_AUTHORIZED`, đã yêu cầu gọi vốn, đủ vốn, đã có khoản vay).

#### User: `GET /api/v1/admin/users/stats/series?from&to&bucket`

```json
{"from": "...", "to": "...", "bucket": "WEEK", "timezone": "Asia/Ho_Chi_Minh",
 "points": [{"bucketStart": "2026-09-28", "registered": 9, "registeredBorrowers": 6, "registeredInvestors": 3,
             "ekycVerified": 5, "ekycFailed": 1}]}
```

Đăng ký theo `created_at`; eKYC theo `ekyc_completed_at` với trạng thái hiện tại `VERIFIED`/`FAILED`.
`/admin/users/stats` giữ nguyên.

#### Investment: `GET /api/v1/investments/admin/statistics/summary`

```json
{"asOf": "...",
 "listings": {"byStatus": {"OPEN": 12, "FULLY_FUNDED": 30}},
 "openFunding": {"listings": 12, "targetAmount": 0, "committedAmount": 0, "fillRatePercent": 41.20},
 "notes": {"byStatus": {"ACTIVE": 900}, "activeOutstandingPrincipal": 0},
 "autoInvest": {"activeConfigs": 4}}
```

#### Investment: `GET /api/v1/investments/admin/statistics/series?from&to&bucket`

```json
{"from": "...", "to": "...", "bucket": "DAY", "timezone": "Asia/Ho_Chi_Minh",
 "points": [{"bucketStart": "2026-10-03", "committedAmount": 0, "commitments": 0, "listingsFullyFunded": 0,
             "trades": 3, "tradedAmount": 0, "platformFee": 0, "averagePricePermille": 985,
             "tradedQuantity": 3, "performingTrades": 2, "performingQuantity": 2,
             "performingAveragePricePermille": 990,
             "autoInvestPlaced": 0, "autoInvestSkipped": 0}]}
```

- Vốn góp theo `investment_commitments.created_at`, bỏ `CANCELLED`.
- Khớp lệnh theo `order_book_trades.executed_at`, bỏ `settlement_status = 'FAILED'`; giá bình quân có trọng số
  khối lượng, `null` khi cột không có lần khớp. `tradedQuantity` = tổng số Note khớp.
- `performing*`: cùng điều kiện nhưng chỉ lần khớp có `order_book_trades.defaulted = false` (Note chưa nợ xấu lúc
  khớp), tính bằng `FILTER` trong cùng câu; `performingAveragePricePermille` có trọng số khối lượng, HALF_UP, `null`
  khi cột không có lần khớp như vậy. Web tính trung bình trượt 7 ngày = Σ(giá × khối lượng) / Σ khối lượng.
- Auto-Invest theo `auto_invest_matches.created_at` và `outcome` (giá trị outcome lấy từ enum trong code).

### 2.3 Query, index, transaction, concurrency

- Mỗi chỉ số là một câu `GROUP BY` (hoặc vài câu gộp được), không lặp theo cột hay theo sản phẩm (tránh N+1:
  30 cột × 5 sản phẩm không được thành 150 câu). Dùng native query/`JdbcTemplate` trả projection, không nạp entity.
- **Không thêm migration/index**: Neon dùng chung cả nhóm, migration mới làm Flyway của máy chưa pull code
  báo lỗi validate. Quy mô hiện tại vài trăm dòng mỗi bảng nên quét tuần tự rẻ hơn duy trì index. Khi bảng
  lớn (hàng trăm nghìn dòng) cân nhắc index `(submitted_at)`, `(disbursed_at)`, `(executed_at)`.
- Transaction `@Transactional(readOnly = true)`: các câu trong một request đọc cùng một ảnh dữ liệu
  (READ COMMITTED vẫn có thể lệch nhẹ giữa các câu; chấp nhận được với số liệu thống kê, ghi chú trong code).
- Không ghi gì nên không có xung đột đồng thời; hai admin gọi cùng lúc chỉ là hai lần đọc.
- Không cache ở task này (số liệu nhỏ, cần tươi sau mỗi thao tác duyệt).

### 2.4 Bảo mật

Chỉ admin. Response không chứa PII (không email, tên, CCCD, số điện thoại); chỉ số đếm và tổng tiền.

### 2.5 Test và acceptance

- Unit test `StatisticsPeriod` (đã có, 7 test).
- Mỗi service: test service/mapper cho trải cột, NPL, nhóm nợ, histogram, funnel; test controller cho 403/400.
  Nếu module có sẵn Testcontainers IT thì thêm IT cho câu SQL.
- `mvn -pl <module> -am test` xanh; build image và gọi thật qua gateway trên dữ liệu Neon.
- Web: bỏ các lần gọi `size=1` thay bằng summary; bật lại biểu đồ đã ẩn; `npm run build` xanh.

## 3. Lớp code thực tế

Mọi câu SQL dùng `NamedParameterJdbcTemplate`, mỗi chỉ số một `GROUP BY`, số câu cố định không phụ thuộc số cột.
Controller nhận `from`/`to`/`bucket` dạng chuỗi rồi gọi `StatisticsPeriod.parseDate` và `StatisticsBucket.parse`
(finora-common) để lỗi định dạng trả 400 `STATISTICS_RANGE_INVALID` thay vì 500 của handler chung.

| Endpoint | Controller → service → repository | Số câu SQL |
|---|---|---|
| `GET /api/v1/admin/loan-statistics/summary` | `AdminLoanStatisticsController.summary` → `AdminLoanStatisticsService.summary` (`requireAdmin`, `readOnly`) → `LoanStatisticsRepository` | 9 |
| `GET /api/v1/admin/loan-statistics/series` | `AdminLoanStatisticsController.series` → `AdminLoanStatisticsService.series` → `LoanStatisticsRepository` | 4 |
| `GET /api/v1/admin/users/stats/series` | `AdminUserController.getUserStatsSeries` (`user:admin:read_all`) → `UserStatisticsService.series` → `UserStatisticsRepository` | 2 |
| `GET /api/v1/investments/admin/statistics/summary` | `InvestmentStatisticsAdminController.summary` (rule `/api/v1/investments/admin/**` → ADMIN) → `InvestmentStatisticsService.summary` → `InvestmentStatisticsRepository` | 3 |
| `GET /api/v1/investments/admin/statistics/series` | `InvestmentStatisticsAdminController.series` → `InvestmentStatisticsService.series` → `InvestmentStatisticsRepository` | 4 |

Thay đổi kèm theo trong finora-loan: công thức nhóm nợ trước đây chép ở hai nơi (`LoanServicingSyncService.debtGroup`,
`LoanCollectionCase.debtGroup`) nay gom về `domain/servicing/DebtGroup` (ngưỡng 9/90/180/360 ngày); thống kê, đồng bộ
servicing, event nợ quá hạn và hồ sơ thu hồi cùng gọi một chỗ.

### Sai khác so với dự kiến

- Khóa `openByStage` là enum thật `EARLY_REMINDER, ATTENTION, NPL, INTENSIVE, LOSS` (ví dụ ở §2.2 ghi "EARLY").
- Các map `byStatus` trả đủ mọi giá trị enum kể cả 0, theo thứ tự khai báo.
- Thời điểm duyệt/từ chối là `COALESCE(admin_decided_at, automated_decided_at, updated_at)`: hồ sơ bị loại ở bước
  kiểm tra điều kiện không có hai cột đầu.
- Phễu không bắt buộc giảm dần: admin có thể duyệt tay hồ sơ chấm điểm lỗi nên "được duyệt" có thể lớn hơn "chấm xong".
- Tiền trong response thống kê Investment là JSON number (các response khác của Investment trả chuỗi).
- `ekycFailed` chỉ có số với dữ liệu mẫu: code eKYC hiện chỉ ghi `ekyc_completed_at` khi xác minh thành công.

### Bằng chứng

- `mvn -o -pl finora-common,finora-loan,finora-user,finora-investment test`: common 21, loan 176, user 40,
  investment 51 test, 0 lỗi. `verify` (Testcontainers PostgreSQL): loan IT 13 (1 bỏ qua có chủ đích),
  investment IT 26, user IT 1, 0 lỗi. IT của Loan chạy SQL thật, kiểm cả ranh giới 23:30 UTC sang ngày sau giờ Việt Nam.
- Gọi thật qua gateway trên Neon (2026-10-08): 5 endpoint trả 200 với admin, 403 với investor, 401 khi chưa đăng nhập,
  400 `STATISTICS_RANGE_INVALID` khi from sau to hoặc `bucket=YEAR`.
- finora-web `npm run build` xanh; chụp màn hình Tổng quan, Sản phẩm, eKYC, Vận hành, Chợ Notes, Chấm điểm, Gọi vốn
  với dữ liệu thật, không có lỗi console.
