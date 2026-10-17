---
task_id: LN-003
title: Loan Product min/base/max rate, repayment method và core mapping
owner: Thai
status: REVIEW
roadmap_task: P1-A01
depends_on: [LN-001A_ACCEPTED, LOAN_SERVICE_DESIGN_APPROVED]
created_at: 2026-08-02
updated_at: 2026-09-05
approved_by: Thai
approved_at: 2026-08-03
accepted_by: Thai
accepted_at: 2026-08-08
---

# LN-003 — Loan Product min/base/max rate và Fineract mapping

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Loan Product là “gói vay mẫu” do admin cấu hình để borrower lựa chọn trước khi nộp hồ sơ. Nó quy định khoảng tiền được vay, khoảng kỳ hạn, khung lãi suất `min/base/max` và cách trả nợ. `baseRate` là mức dùng để minh họa trước thẩm định; lãi suất cuối được Loan điều chỉnh theo grade AI nhưng luôn nằm trong khung Product. Product chưa đồng bộ thành công với Fineract không được hiển thị để nhận hồ sơ.

### Ai tham gia và nhìn thấy gì?

- Admin tạo, sửa, yêu cầu đồng bộ, kích hoạt, tạm ngừng hoặc lưu trữ Product.
- Borrower chỉ nhìn thấy Product `ACTIVE`: tên gói, số tiền/kỳ hạn được chọn, khung lãi suất, mức base dùng để tính ban đầu và cách trả.
- Fineract nhận một cấu hình tương ứng để về sau tính lịch trả và quản lý khoản vay core.
- AI trả grade/decision; Loan sở hữu công thức chuyển grade thành `finalRate`. AI không trực tiếp ghi lãi suất Product hay hợp đồng.

### Luồng chính trên màn hình

```text
Admin nhập gói vay → lưu DRAFT → đồng bộ Fineract
    ├── thành công: admin kích hoạt → borrower nhìn thấy và chọn
    └── thất bại: hiện lý do/rút gọn → cho retry, borrower chưa nhìn thấy
```

Khi Product đã có hồ sơ tham chiếu, không xóa cứng. Nếu cần điều khoản tài chính khác, phải tạo phiên bản/Product mới để không làm thay đổi những gì borrower trước đó đã xem.

### Dữ liệu quan trọng và tác động trong hệ thống

| Dữ liệu | Người dùng hiểu là gì | Ai cung cấp | Hệ thống dùng lúc nào | Nếu thiếu hoặc sai | Vì sao phải lưu |
|---|---|---|---|---|---|
| `code` | Mã gói vay ổn định | Admin | Tra cứu, đồng bộ và hỗ trợ vận hành | Trùng mã thì không tạo | Không nhầm hai gói có tên gần giống nhau |
| `name/description` | Tên và nội dung borrower đọc | Admin | Catalog và bản chụp hồ sơ/hợp đồng | Tên thiếu thì không công bố | Giữ đúng nội dung đã giới thiệu |
| `minAmount/maxAmount` | Khoảng tiền borrower được chọn | Admin/policy | Form, validation và Fineract | Ngoài khoảng thì không cho nộp | Ngăn tạo khoản vay ngoài chính sách Product |
| `minTermMonths/maxTermMonths` | Khoảng thời gian được chọn | Admin/policy | Form, preview, hồ sơ | Kỳ hạn ngoài khoảng bị từ chối | Bảo đảm lịch trả đúng cấu hình đã duyệt |
| `minAnnualInterestRate` | Mức thấp nhất Product cho phép | Admin/policy | Chặn kết quả định giá thấp hơn biên | Sai thứ tự thì không lưu/kích hoạt | Không để ưu đãi vượt khung đã duyệt |
| `annualInterestRate` | `baseRate` dự kiến theo năm; giữ tên API/cột cũ để tương thích | Admin/policy | Catalog, preview ban đầu, `int_rate` gửi AI và Product Fineract | Sai thứ tự thì không lưu/kích hoạt | Có một mốc giá công khai trước thẩm định |
| `maxAnnualInterestRate` | Mức cao nhất Product cho phép | Admin/policy | Chặn kết quả định giá vượt biên và trần pháp lý | Trên 20% thì từ chối | Không để risk adjustment tạo mức ngoài Product |
| `repaymentMethod` | Cách phân bổ tiền gốc/lãi theo kỳ | Admin | Fineract tính lịch và UI giải thích | Không hỗ trợ thì không đồng bộ | Borrower biết khoản trả đều hay giảm dần |
| `status` | Product có đang được bán không | Admin qua state machine | Quyết định có xuất hiện trên catalog | Không ACTIVE thì không nhận hồ sơ | Quản lý vòng đời mà không xóa lịch sử |
| `coreSyncStatus` | Cấu hình đã sẵn sàng trong Fineract chưa | Loan/LN-006 | Chặn kích hoạt và tạo preview | FAILED/PENDING thì cho retry, chưa bán | Tránh hứa điều khoản mà core chưa tính được |
| `version` | Phiên bản dữ liệu admin đang sửa | Hệ thống | Mọi lệnh sửa/trạng thái | Cũ hơn thì báo xung đột | Không để hai thao tác ghi đè lẫn nhau |

`LoanProduct` có nhiều `FineractProductMapping` vì một gói có thể được đồng bộ lại theo phiên bản cấu hình khác nhau. Mapping cũ được giữ làm bằng chứng; Product chỉ trỏ tới mapping hiện hành đã `SYNCED`.

### Ví dụ thực tế

Admin tạo “Vay tiêu dùng linh hoạt”, cho vay 10–100 triệu, 6–24 tháng, khung lãi 12–18%/năm, base 15%, trả góp đều. Product ở `DRAFT/NOT_SYNCED`; borrower chưa thấy. Sau khi Fineract trả ID và mapping thành `SYNCED`, admin kích hoạt; borrower chọn 50 triệu/12 tháng và LN-006 dùng base 15% để tính preview ban đầu.

### Điều kiện hoàn thành về nghiệp vụ

- Borrower chỉ chọn được Product `ACTIVE + SYNCED`.
- Khung lãi/base rate/cách trả hiển thị, snapshot và gửi Fineract đúng vai trò; final rate thuộc LN-007/LN-008.
- Sửa đồng thời không làm mất thay đổi; retry sync không tạo Product core trùng.
- Người duyệt có thể tạo, sync, activate, deactivate và xem lỗi bằng Postman mà không cần sửa database.

## 1. Người đọc cần hiểu gì?

Mỗi Product FINORA công bố một khung `min/base/max` và một cách trả nợ. `annualInterestRate` hiện hữu được định nghĩa là base rate để không phá API/database cũ. Product chỉ được nhận hồ sơ khi cấu hình base tương ứng đã đồng bộ thành công sang Apache Fineract.

```text
Admin tạo Product DRAFT
    → cấu hình amount/term/min-base-max rate/repayment method
    → yêu cầu đồng bộ core
    → LN-006 gọi Fineract và tạo mapping
    → mapping SYNCED
    → admin kích hoạt Product ACTIVE
```

LN-003 tạo đúng domain/schema/API local. LN-006 triển khai REST adapter và hoàn tất happy path đồng bộ Fineract. Trước LN-006, Product có thể tạo/sửa ở `DRAFT` nhưng không được `ACTIVE` nếu chưa có mapping `SYNCED`.

## 2. Quyết định thiết kế hiện hành

LN-003 dùng khung lãi suất để công bố trước rủi ro và bảo vệ giới hạn Product:

- Product có `minAnnualInterestRate <= annualInterestRate(base) <= maxAnnualInterestRate <= 20`.
- AI trả grade; Loan áp policy có version để tăng/giảm base rồi clamp trong khung.
- Admin chọn `ANNUITY` hoặc `EQUAL_PRINCIPAL`.
- Fineract chịu trách nhiệm tính schedule; Loan không giữ engine công thức riêng.
- Product FINORA và Product Fineract có vòng đời khác nhau, nên phải lưu mapping/sync status.

Chi tiết pháp lý: [`LEGAL-RATE-01`](../../docs/LEGAL-COMPLIANCE.md#legal-rate-01--khung-minbasemax-và-trần-lãi-suất), [`LEGAL-TERM-01`](../../docs/LEGAL-COMPLIANCE.md#legal-term-01--thời-hạn-product-và-hợp-đồng) và [`LEGAL-LIMIT-01`](../../docs/LEGAL-COMPLIANCE.md#legal-limit-01--dư-nợ-người-vay).

## 3. Phạm vi

### Trong task

- Sửa `LoanProduct`, request/response, mapper, service và validation.
- Thêm `RepaymentMethod`, `CoreSyncStatus` và `FineractProductMapping` domain.
- Viết lại `V1__create_loan_products.sql` thành schema Product cuối vì migration cũ chưa merge/chưa chạy trên Neon dùng chung.
- API CRUD/lifecycle Product phản ánh min/base/max rate và sync status.
- Chặn ACTIVE khi core mapping chưa `SYNCED`.
- Unit/integration/controller/repository test và Postman Product.

### Ngoài task

- Không khởi chạy Fineract/PostgreSQL và không gọi API thật; thuộc LN-006/P0-C05.
- Không tính schedule; thuộc LN-006.
- Không tạo Application mới; thuộc LN-004.
- Không thêm Redis/cache.
- Không triển khai JWT thật; tạm dùng mock admin tập trung như hiện tại.

## 4. Luồng nghiệp vụ chi tiết

### 4.1 Tạo Product

1. Admin gửi code, tên, giới hạn tiền/kỳ hạn, min/base/max rate và repayment method.
2. Loan validate DTO và invariant.
3. Loan tạo Product `DRAFT`, `coreSyncStatus=NOT_SYNCED`, version 0.
4. Product chưa xuất hiện trong catalog borrower.

### 4.2 Sửa Product

- Chỉ `DRAFT` được sửa cấu hình tài chính trực tiếp.
- `ACTIVE/INACTIVE` chỉ cho sửa mô tả hiển thị không làm thay đổi terms; thay rate/method/amount/term phải tạo Product version/mapping mới trong task mở rộng.
- `ARCHIVED` bất biến, không kích hoạt lại.
- Mỗi update dùng optimistic `version`; conflict trả `409 VERSION_CONFLICT`.

### 4.3 Đồng bộ và kích hoạt

- Endpoint sync chuyển `NOT_SYNCED/FAILED → PENDING`; LN-006 thực thi external command.
- Khi LN-006 xác nhận resource Fineract hợp lệ: mapping `SYNCED`, Product `coreSyncStatus=SYNCED`.
- Chỉ Product `DRAFT + SYNCED` mới chuyển `ACTIVE`.
- Sync lỗi không được kích hoạt Product hoặc tự tạo schedule thay thế.

## 5. Entity `LoanProduct`

| Field Java | PostgreSQL | Bắt buộc | Ai ghi | Tác dụng nghiệp vụ |
|---|---|---:|---|---|
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY` | Có | DB | Khóa nội bộ |
| `code` | `VARCHAR(50)` unique | Có | Admin lúc tạo | Business key ổn định, không sửa |
| `name` | `VARCHAR(150)` | Có | Admin | Tên catalog/hợp đồng snapshot |
| `description` | `TEXT` | Không | Admin | Giải thích gói vay |
| `minAmount` | `DECIMAL(18,2)` | Có | Admin | Cận dưới amount borrower được chọn |
| `maxAmount` | `DECIMAL(18,2)` | Có | Admin | Cận trên amount borrower được chọn |
| `minTermMonths` | `SMALLINT` + `CHECK > 0` | Có | Admin | Kỳ hạn nhỏ nhất |
| `maxTermMonths` | `SMALLINT` + range check | Có | Admin | Kỳ hạn lớn nhất |
| `minAnnualInterestRate` | `DECIMAL(7,4)` | Có | Admin | Biên lãi thấp nhất Product cho phép sau thẩm định |
| `annualInterestRate` | `DECIMAL(7,4)` | Có | Admin | Base rate theo năm; `15.0000` nghĩa là 15% |
| `maxAnnualInterestRate` | `DECIMAL(7,4)` | Có | Admin | Biên lãi cao nhất, đồng thời không vượt 20% trong mô hình FINORA hiện tại |
| `repaymentMethod` | `VARCHAR(30)` | Có | Admin | `ANNUITY` hoặc `EQUAL_PRINCIPAL` |
| `status` | `VARCHAR(20)` | Có | Loan state machine | `DRAFT/ACTIVE/INACTIVE/ARCHIVED` |
| `coreSyncStatus` | `VARCHAR(20)` | Có | Loan/LN-006 | `NOT_SYNCED/PENDING/SYNCED/FAILED` |
| `currentCoreMappingId` | `BIGINT` nullable | Không | LN-006 | Mapping Fineract hiện hành |
| `version` | `BIGINT` | Có | JPA | Chặn ghi đè cạnh tranh |
| `createdBy` | `VARCHAR(100)` | Có | Identity provider | Audit actor tạo |
| `updatedBy` | `VARCHAR(100)` | Có | Identity provider | Audit actor cuối |
| `createdAt` | `TIMESTAMP(6) WITH TIME ZONE` | Có | Clock | Audit thời gian tạo UTC |
| `updatedAt` | `TIMESTAMP(6) WITH TIME ZONE` | Có | Clock | Audit thời gian sửa UTC |

## 6. Enum và invariant

```text
RepaymentMethod:
  ANNUITY
  EQUAL_PRINCIPAL

CoreSyncStatus:
  NOT_SYNCED
  PENDING
  SYNCED
  FAILED
```

Invariant:

- `minAmount > 0`, `maxAmount >= minAmount`.
- `minTermMonths > 0`, `maxTermMonths >= minTermMonths`, `maxTermMonths <= 24`.
- `0 < minAnnualInterestRate <= annualInterestRate <= maxAnnualInterestRate <= 20.0000`.
- `repaymentMethod` nằm trong allowlist.
- `ACTIVE` bắt buộc `coreSyncStatus=SYNCED` và `currentCoreMappingId != null`.
- Product đã được Application tham chiếu không hard delete.

## 7. Entity `FineractProductMapping`

### 7.1. Mapping là gì và vì sao không lưu thẳng một Fineract ID?

`LoanProduct` là gói vay FINORA mà admin/borrower nhìn thấy. Product bên Fineract là cấu hình engine
dùng để tính schedule và mở core loan. `FineractProductMapping` là bản nối cho biết **phiên bản cấu hình
FINORA nào đã tạo tài nguyên Fineract nào và lần đồng bộ đó có đáng tin hay không**.

Không đặt duy nhất `fineractProductId` trên Product vì Product có thể đổi cấu hình qua nhiều version:

```text
LoanProduct “Vay tiêu dùng”
    ├── configurationVersion 1 → Mapping 1 → Fineract Product 101 (lịch sử)
    ├── configurationVersion 2 → Mapping 2 → Fineract Product 125 (current, SYNCED)
    └── configurationVersion 3 → Mapping 3 → chưa có Fineract ID (PENDING/FAILED)
```

Nếu ghi đè một ID, hệ thống không còn giải thích được Application cũ đã dùng cấu hình nào, không biết
timeout thuộc lần sync nào và có thể dùng nhầm Product core mới cho hồ sơ lịch sử.

```text
LoanProduct 1 ───── 0..N FineractProductMapping
    │
    └── currentCoreMappingId ─────> 0..1 mapping hiện hành đã SYNCED

FineractProductMapping 1 ───── 0..N FineractCommand
FineractProductMapping 1 ───── 0..N LoanApplication (qua coreMappingIdSnapshot)
```

Mapping cũ không cascade delete. Application giữ `coreMappingIdSnapshot`, nên sửa Product hiện tại
không làm thay đổi lịch/AI/Contract đã dựa trên mapping cũ.

### 7.2. Tác động của từng field trong luồng

| Dữ liệu | Người vận hành hiểu gì | Hệ thống dùng ở bước nào | Nếu thiếu hoặc sai | Kỹ thuật lưu trữ |
|---|---|---|---|---|
| `id` | Mã bản mapping trong Loan DB | Product chọn mapping current; Application đóng băng mapping đã dùng | Không nối được Product/Application với bằng chứng sync | `BIGINT IDENTITY`, PK; không lộ thay external business key |
| `loanProductId` | Mapping thuộc gói vay nào | Kiểm tra ownership/version trước sync, preview và submit | Có thể dùng tài nguyên core của Product khác | FK `loan_products.id`, bắt buộc |
| `finoraProductVersion` | Cấu hình FINORA phiên bản nào được gửi | Không dùng mapping version cũ sau khi admin sửa terms | Hồ sơ mới có thể nhận lãi/kỳ hạn cũ | `BIGINT`; unique cùng `loanProductId` |
| `fineractProductId` | ID Product do core xác nhận | Tính preview và sau này tạo Fineract Loan | Null thì chưa được activate; ID lạ thì contract mismatch | `BIGINT` unique, nullable trước success |
| `externalId` | Mã chung ổn định để FINORA hỏi lại Fineract | Tìm trước POST và reconcile sau timeout/restart | Retry có thể tạo Product core thứ hai | `VARCHAR(100)` unique, dạng `FINORA-LP-{id}-V{version}` |
| `configVersion` | Phiên bản mapper/template dùng khi tạo payload | Điều tra vì sao hai thời điểm ánh xạ khác nhau | Không biết mapping cũ còn tương thích policy hiện tại không | `VARCHAR(30)`, bắt buộc |
| `requestHash` | Dấu vân tay nội dung cấu hình đã gửi | Chặn cùng business key bị dùng cho payload khác | Một command có thể âm thầm đổi nghĩa | SHA-256 `VARCHAR(64)` |
| `status` | Lần đồng bộ đang chờ, thành công hay lỗi | Chặn activate/preview; quyết định worker có tiếp tục không | UI có thể bán Product core chưa sẵn sàng | `PENDING/SYNCED/FAILED`; code hiện chưa có `SUPERSEDED` trong constraint |
| `lastErrorCode/detail` | Lý do rút gọn để admin/vận hành sửa hoặc retry | Phân biệt lỗi tạm thời và lỗi cấu hình | Chỉ thấy “sync thất bại”, không biết hành động tiếp | nullable, đã lọc PII/credential |
| `syncedAt` | Thời điểm Fineract xác nhận mapping | Audit và xác định bằng chứng nào tồn tại lúc Product được kích hoạt | Không chứng minh được thời điểm core sẵn sàng | `TIMESTAMPTZ`, chỉ có khi success |
| audit/`version` | Ai tạo/sửa mapping và bản nào đang được cập nhật | Log/audit và chống hai execution ghi đè | Kết quả worker/request cạnh tranh có thể mất | actor/time + optimistic version |

`FineractCommand` không thay Mapping: Mapping trả lời “hai Product liên kết ra sao”, còn Command trả lời
“lần thực thi external call đang ở bước nào”. Chi tiết command/worker nằm tại LN-006.

## 8. Migration dự kiến

Sau khi LN-003 được Thái duyệt, viết mới `V1__create_loan_products.sql` trực tiếp theo thiết kế cuối:

1. Tạo `loan_products` chỉ có một `annual_interest_rate`, không tạo min/base/max rate cũ.
2. Tạo `repayment_method`, `core_sync_status` và `current_core_mapping_id` đúng invariant.
3. Tạo `fineract_product_mappings` cùng FK, unique, check constraint và index đã giải thích ở mục 10.
4. Dùng PostgreSQL identity, `TIMESTAMP(6) WITH TIME ZONE`, `VARCHAR + CHECK`; không dùng cú pháp MySQL hoặc native enum.
5. Không backfill và không tạo V3 vá schema vì V1 cũ chưa merge, Neon chưa chạy migration và chưa có dữ liệu nghiệp vụ cần giữ.

Migration phải được test từ PostgreSQL 17 database sạch; Flyway tạo đúng schema cuối ngay lần đầu.

## 9. API FINORA

### Tạo Product

`POST /api/v1/admin/loan-products`

```json
{
  "code": "PERSONAL-12M",
  "name": "Vay tiêu dùng linh hoạt",
  "description": "Gói vay cá nhân",
  "minAmount": "10000000",
  "maxAmount": "100000000",
  "minTermMonths": 6,
  "maxTermMonths": 24,
  "minAnnualInterestRate": "12.0000",
  "annualInterestRate": "15.0000",
  "maxAnnualInterestRate": "18.0000",
  "repaymentMethod": "ANNUITY"
}
```

Response `201` trả đầy đủ Product, `coreSyncStatus=NOT_SYNCED`, `version=0`; không trả entity trực tiếp.
Để không phá client cũ trong giai đoạn chuyển đổi, nếu request chưa gửi min/max thì Loan dùng
`min=base=max=annualInterestRate`. Client mới phải gửi đủ ba mức.

### Cập nhật

`PUT /api/v1/admin/loan-products/{id}` với body trên cộng `version`. Chỉ DRAFT được đổi terms.

### Yêu cầu sync

`POST /api/v1/admin/loan-products/{id}/core-sync`

```json
{"version": 1}
```

LN-003 tạo command/state local; LN-006 hoàn thiện external execution. Duplicate idempotency key trả cùng logical command.

### Kích hoạt và lifecycle

- `POST /{id}/activate`
- `POST /{id}/deactivate`
- `POST /{id}/archive`

Mỗi action nhận `version`. `activate` trả `409 CORE_PRODUCT_NOT_SYNCED` nếu mapping chưa `SYNCED`.

## 10. Query và index

Index là “mục lục” giúp PostgreSQL không phải đọc toàn bảng. Thêm index cũng làm insert/update và dung
lượng tốn hơn, vì vậy mỗi mục dưới đây bám một query hoặc invariant thật:

| Use case/query mục tiêu | Index/constraint hiện hành | Vì sao đúng và đánh đổi |
|---|---|---|
| Tạo Product kiểm tra/tìm theo `code` | unique `loan_products.code` | Vừa chống mã trùng khi hai request cạnh tranh, vừa lookup nhanh; Product tạo mới phải cập nhật index |
| Catalog `WHERE status='ACTIVE' ORDER BY created_at DESC,id DESC LIMIT` | `idx_loan_products_catalog(status,created_at DESC,id DESC)` | Filter trước, sort/pagination sau; không cần tải mappings |
| Tìm đúng mapping theo Product + configuration version | unique `(loan_product_id,finora_product_version)` | Bảo đảm một logical mapping cho mỗi phiên bản, không chỉ tối ưu đọc |
| Xem mapping của một Product theo thời gian | `idx_fineract_mappings_product(loan_product_id,created_at DESC)` | Khớp filter Product và timeline; thêm `id` sau này chỉ khi query thực tế cần stable tie-break lớn |
| Reconcile exact theo `externalId` | unique `fineract_product_mappings.external_id` | Tìm đúng tài nguyên sau timeout và chống hai mapping dùng cùng external business key |
| Nhận/đối chiếu `fineractProductId` | unique nullable `fineract_product_id` | Một core Product không được gắn vào hai mapping FINORA |

Chưa thêm index riêng `loan_products.status`: bảng Product nhỏ và selectivity thấp. Catalog dùng query projection, pagination/size limit; không fetch collection mappings khi liệt kê Product.

## 11. Transaction và concurrency

Transaction là nhóm thay đổi cùng thành công hoặc cùng hoàn tác. Concurrency là hai request/worker chạm
cùng dữ liệu gần như đồng thời.

```text
Create/update Product: một local transaction

Sync transaction 1:
Product PENDING + Mapping PENDING + Command PENDING → cùng commit/rollback

Ngoài transaction:
gọi REST Fineract, không giữ DB connection/lock khi chờ mạng

Sync transaction 2:
lock/kiểm tra version → Command + Mapping + Product cùng ghi kết quả
```

| Tình huống cạnh tranh | Nguy cơ | Cơ chế thật | Kết quả |
|---|---|---|---|
| Hai admin sửa Product version 3 | Người sau ghi đè người trước | optimistic `version` | Một request `409`, UI tải bản mới; không tự cộng version |
| Hai request tạo cùng code | Cả hai cùng vượt `existsByCode` | unique constraint + bắt `DataIntegrityViolation` | Chỉ một Product được tạo |
| Hai yêu cầu sync cùng version | Tạo hai mapping/command | unique Product/version + idempotency key | Dùng lại logical command, không POST logic lần hai |
| Product đổi khi sync đang chạy | Mapping cũ bị gắn làm current | `configurationVersion`, lock ngắn và kiểm tra lại khi complete | Kết quả cũ không được dùng cho cấu hình mới |

External timeout không đồng nghĩa thất bại. LN-006 phải reconcile theo `externalId` trước khi POST lại;
unique DB chỉ ngăn bản ghi Loan trùng, không tự ngăn side effect đã xảy ra bên Fineract.

## 12. File dự kiến

- Sửa `domain/LoanProduct.java`, DTO Product, mapper/service/controller/repository và tests hiện có.
- Thêm `domain/RepaymentMethod.java`, `domain/CoreSyncStatus.java`, `domain/FineractProductMapping.java`.
- Thêm repository/migration/test tương ứng.
- Không tạo package rỗng; integration client thật thuộc LN-006.

## 13. Test bắt buộc

- Tạo Product với hai repayment method.
- Reject amount/term/rate/method boundary sai.
- Product mới luôn DRAFT/NOT_SYNCED.
- Không ACTIVE khi NOT_SYNCED/PENDING/FAILED.
- ACTIVE khi mapping SYNCED.
- Không sửa financial terms khi ACTIVE/ARCHIVED.
- Version conflict trả 409.
- Duplicate sync không tạo mapping/command thứ hai.
- Migration V1 tạo đúng schema cuối trên PostgreSQL 17 database sạch.
- Catalog không N+1 và không tải mapping collection.

## 14. Acceptance criteria

- Không còn min/base/max rate hoặc `InterestMethod` trong Product API/domain mới.
- Fixed rate dùng thống nhất `15.0000 = 15%/năm`.
- Hai repayment method có mapper rõ tới LN-006.
- Product không thể ACTIVE trước Fineract sync.
- Migration/test/Postman/documentation pass và có evidence.

## 15. Nghiệm thu

```text
Plan decision: ACCEPTED
Reviewer: Thai
Reviewed at: 2026-08-08
Notes:
```

## 16. Luồng gọi API và thứ tự hàm thực tế

Phần này mô tả code đang chạy, dùng để lần từ request HTTP đến database mà không phải tự đoán.

### 16.1 Admin tạo Product

```text
POST /api/v1/admin/loan-products
→ AdminLoanProductController.create(request)
→ LoanProductServiceImpl.create(request)                    [transaction]
  → LoanProductRepository.existsByCode(normalizedCode)
  → LoanProduct.create(...)                                 [validate invariant, tạo DRAFT/NOT_SYNCED]
  → LoanProductRepository.saveAndFlush(product)             [unique constraint là chốt cạnh tranh]
  → LoanProductMapper.toResponse(product)
→ HTTP 201
```

### 16.2 Admin sửa Product

```text
PUT /api/v1/admin/loan-products/{id}
→ AdminLoanProductController.update(id, request)
→ LoanProductServiceImpl.update(id, request)                 [transaction]
  → getProduct(id) → LoanProductRepository.findById(id)
  → LoanProduct.updateConfiguration(...)                    [check DRAFT + version + rule amount/term/rate]
  → LoanProductRepository.saveAndFlush(product)
  → LoanProductMapper.toResponse(product)
```

`updateConfiguration()` tăng `configurationVersion` và reset core sync để cấu hình mới không dùng nhầm
mapping Fineract cũ. `version` trong request là optimistic version chống hai admin ghi đè nhau.

### 16.3 Đồng bộ và kích hoạt

```text
POST /{id}/core-sync
→ AdminLoanProductController.synchronize(...)
→ CoreProductSyncServiceImpl.synchronize(...)               [chi tiết đầy đủ ở LN-006]

POST /{id}/activate
→ AdminLoanProductController.activate(...)
→ LoanProductServiceImpl.activate(...)
→ transition(id, LoanProduct.activate, "ACTIVE")            [transaction]
  → LoanProduct.activate(...)                               [check DRAFT + core SYNCED + version]
  → LoanProductRepository.saveAndFlush(product)
```

`deactivate` và `archive` đi cùng đường `transition(...)`, nhưng domain method tương ứng tự quyết định
trạng thái nguồn nào được phép chuyển. Controller/service không gán status tùy ý.

### 16.4 Admin xem chi tiết và borrower xem catalog

| API | Chuỗi hàm | Ý nghĩa |
|---|---|---|
| `GET /api/v1/admin/loan-products/{id}` | `getAdminDetail → getProduct → repository.findById → mapper.toResponse` | Admin thấy cả trạng thái sync/audit |
| `GET /api/v1/admin/loan-products` | `list → listAdmin → repository.findAdminProducts(Pageable) → mapper` | Admin thấy cả DRAFT/FAILED/INACTIVE để tiếp tục xử lý |
| `GET /api/v1/loan-products/{id}` | `LoanProductController.activeDetail → getActive → requireAvailable → mapper.toCatalogResponse` | Chỉ trả Product đang bán |
| `GET /api/v1/loan-products` | `activeProducts → listActive → repository.findByStatus(Pageable) → mapper` | Truy vấn phân trang, không N+1 |

### 16.5 Vai trò các hàm chính

| Hàm | Chịu trách nhiệm | Không chịu trách nhiệm |
|---|---|---|
| Controller method | Nhận path/body, validation DTO, chọn HTTP status | Không viết rule nghiệp vụ |
| `LoanProductServiceImpl` | Transaction, orchestration repository/domain, log nghiệp vụ | Không tự tính schedule |
| `LoanProduct` domain method | Invariant, state transition, version và audit timestamp | Không gọi database/Fineract |
| Repository | Query/persist Product | Không quyết định Product có được activate |
| Mapper | Entity → response DTO | Không thay đổi entity |

### 16.6 Ranh giới giữa Product và đồng bộ Fineract

LN-003 chỉ sở hữu cấu hình và vòng đời Product FINORA. Khi admin yêu cầu đồng bộ,
`LoanProduct` không tự gọi HTTP, không tự tạo hash và không biết payload Fineract. Nó chỉ giữ
`coreSyncStatus`, `currentCoreMappingId` và `configurationVersion` để biết cấu hình hiện tại đã có
mapping core hợp lệ hay chưa.

`CoreProductSyncStateService.prepare()` ở LN-006 mới tạo `externalId`, `idempotencyKey` và
`requestHash` từ đúng phiên bản cấu hình. Cách tách này cần thiết để:

- domain Product chỉ bảo vệ rule nghiệp vụ và state transition;
- external call, retry và đối chiếu không bị nhét vào entity;
- sửa Product làm tăng `configurationVersion`, vì vậy không thể dùng nhầm mapping của cấu hình cũ;
- LN-003 không lặp lại chi tiết worker/lease/hash đã có một nguồn đầy đủ tại LN-006.

## 17. Kết quả triển khai hiện tại

- Đã mở rộng Product thành `minAnnualInterestRate`, base `annualInterestRate`,
  `maxAnnualInterestRate` và `RepaymentMethod` mà không đổi tên field base hiện hữu.
- Đã có `CoreSyncStatus`, current mapping, configuration version và audit actor.
- Endpoint admin đã tách khỏi catalog borrower; Product chưa sync không thể activate.
- Flyway V1 tạo Product, mapping, durable command, constraints và index theo query.
- Unit test domain và migration PostgreSQL/Testcontainers đã pass; constraint command type
  đã được kiểm chứng bằng luồng sync/activate trong integration test.

## 18. Bổ sung phục vụ tích hợp giao diện ngày 2026-08-09

### 18.1 Vì sao cần API danh sách riêng cho admin?

Catalog borrower cố ý chỉ trả Product `ACTIVE`, vì người vay không được nhìn thấy sản phẩm còn nháp hoặc lỗi đồng bộ.
Nếu web admin dùng lại catalog này, Product vừa tạo sẽ “biến mất” khỏi màn hình trước khi được sync/activate. Vì vậy hệ thống
bổ sung `GET /api/v1/admin/loan-products` với các tham số tùy chọn:

| Query | Ý nghĩa người dùng | Giới hạn kỹ thuật |
|---|---|---|
| `status` | Lọc theo vòng đời Product như DRAFT/ACTIVE/INACTIVE | Enum `LoanProductStatus` |
| `coreSyncStatus` | Tìm Product chưa sync, đã sync hoặc sync lỗi để xử lý | Enum `CoreSyncStatus` |
| `page` | Mở trang dữ liệu cần xem | Từ 0 trở lên |
| `size` | Số dòng mỗi trang | 1–100, mặc định 20 |

### 18.2 Chuỗi hàm, transaction và chống N+1

```text
GET /api/v1/admin/loan-products?status=DRAFT&page=0&size=20
→ AdminLoanProductController.list(...)
→ LoanProductServiceImpl.listAdmin(...)                 [readOnly transaction]
→ LoanProductRepository.findAdminProducts(...)          [một query data + tối đa một query count]
→ LoanProductMapper.toResponse(...)                      [map scalar, không tải collection]
→ PageResponse
```

`readOnly=true` nói rõ đây là use case chỉ đọc và tránh vô tình thay đổi entity. Query trả `Page`, không tải toàn bộ Product
vào bộ nhớ. `LoanProduct` không có collection lazy cần truy vấn thêm cho từng dòng nên số câu SQL giữ cố định, không tạo N+1.
Integration test bật Hibernate Statistics và khóa ngưỡng tối đa hai query cho một trang.

### 18.3 Vì sao có thêm index V6?

- `idx_loan_products_admin_created(created_at DESC, id DESC)` phục vụ màn hình admin xem toàn bộ Product mới nhất; index
  catalog cũ bắt đầu bằng `status` không tối ưu cho trường hợp không lọc status.
- `idx_loan_products_core_sync_created(core_sync_status, created_at DESC, id DESC)` phục vụ hàng đợi retry/kiểm tra sync.
- Index `idx_loan_products_catalog(status, created_at DESC, id DESC)` từ V4 tiếp tục phục vụ lọc theo vòng đời và catalog.

Không thêm index cho mọi field vì mỗi index làm tăng chi phí ghi. Ba index trên bám đúng ba dạng query list thực tế.
Flyway V6 chỉ thêm index, không sửa/xóa dữ liệu Product hiện có.

### 18.4 Tác động đến web và kiểm thử

- Web gọi admin list phân trang, không còn dùng `/loan-products?size=100` để giả danh màn quản trị.
- Sau create, web tự gọi core-sync bằng đúng `version` backend vừa trả; sync lỗi không xóa Product local.
- Postman có request `01A - Admin xem danh sách Product`.
- `LoanMigrationUpgradeIT` kiểm tra database cũ nâng đến V6 và đủ hai index mới.
- `FinoraLoanApplicationIT.adminProductListSupportsFiltersAndUsesFixedQueryCount` kiểm tra list, hai filter và số query.
