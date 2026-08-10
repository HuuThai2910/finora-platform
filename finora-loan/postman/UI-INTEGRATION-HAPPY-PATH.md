# Luồng kiểm thử đầy đủ trước khi tích hợp UI

## 1. Mục tiêu

Tài liệu này hướng dẫn kiểm thử một vertical slice đang có thật trong Loan Service:

```text
Admin tạo Product
  → đồng bộ Product sang Fineract
  → kích hoạt Product
  → borrower xem lịch trả dự kiến
  → borrower nộp hồ sơ
  → worker kiểm tra điều kiện và gọi AI v10
  → admin xem bằng chứng và phê duyệt
  → hệ thống tạo Contract
  → borrower xem đúng văn bản và ký
```

Khi toàn bộ happy path đạt kết quả mong đợi, UI có thể tích hợp tới trạng thái Contract `SIGNED`.
`SIGNED` chỉ có nghĩa người vay đã chấp thuận hợp đồng; chưa có nghĩa khoản vay đã được giải ngân.

## 2. Thành phần phải chạy

1. PostgreSQL của Loan: Neon hoặc `loan-postgres` local.
2. `fineract-postgres` và `fineract` đang healthy.
3. FINORA AI v10 tại `http://localhost:8000`.
4. `FinoraLoanApplication` chạy bằng JDK 21, profile `local`, port `8081`.

Không cần Keycloak, Kafka, Redis hoặc MongoDB cho luồng này. Profile local đang dùng mock identity:

- admin: `ADMIN-001`;
- borrower: `BORROWER-001`.

Sau khi tạo mới hoặc reset volume Fineract, chạy bootstrap:

```powershell
powershell -ExecutionPolicy Bypass -File docker/smoke-fineract.ps1 -KeepRunning
```

Nếu Loan chạy bằng IntelliJ, working directory phải là `finora-loan` để đọc đúng `finora-loan/.env`.

## 3. Collection và environment

Import hai file:

- `FINORA-Loan-Manual.postman_collection.json`;
- `FINORA-Loan-Local.postman_environment.json`.

Chọn environment `FINORA Loan Local`. Collection không tự chạy và không tự cập nhật biến; người kiểm
thử phải copy đúng giá trị API vừa trả về. Trước khi tạo hồ sơ, đổi `expectedDisbursementDate` trong
request 05 và 06 thành ngày hợp lệ ở tương lai nếu ngày mẫu đã cũ.

## 4. Chuỗi request happy path

Không bấm liên tục toàn bộ request 00–21. Request 10, 14, 17B và 21B là nhánh thay thế hoặc xử lý lỗi,
không thuộc cùng một happy path.

| Thứ tự | Request Postman | HTTP mong đợi | Trạng thái/kết quả bắt buộc | Biến phải cập nhật |
|---:|---|---:|---|---|
| 1 | `00 - Health` | 200 | readiness `UP` | Không |
| 2 | `01 - Admin tạo Product DRAFT` | 201 | `status=DRAFT`, `coreSyncStatus=NOT_SYNCED` | `productId=id`, `productVersion=version` |
| 3 | `02 - Admin đồng bộ Product sang Fineract` | 200 | `commandStatus=SUCCEEDED`, `product.coreSyncStatus=SYNCED` | `productVersion=product.version` |
| 4 | `03 - Admin kích hoạt Product đã sync` | 200 | `status=ACTIVE`, `coreSyncStatus=SYNCED` | `productVersion=version` |
| 5 | `04 - Borrower xem Product ACTIVE` | 200 | Điều khoản hiển thị đúng Product | Không |
| 6 | `05 - Borrower xem lịch trả dự kiến` | 200 | Có tổng tiền và đủ kỳ thanh toán | Không |
| 7 | `06 - Borrower nộp thẳng hồ sơ SUBMITTED` | 201 | Có `applicationNumber`, trạng thái ban đầu `SUBMITTED` | `applicationNumber`, `applicationVersion=version` |
| 8 | `07 - Gửi lại cùng Idempotency-Key` | 201 | Trả cùng hồ sơ, không tạo bản ghi logic thứ hai | Không |
| 9 | `08 - Borrower xem chi tiết hồ sơ` | 200 | Sau khi worker hoàn tất: `status=PENDING_REVIEW` | `applicationVersion=version` |
| 10 | `09 - Borrower xem danh sách hồ sơ` | 200 | `data` chứa đúng `applicationNumber` | Không |
| 11 | `11 - Borrower xem lịch sử trạng thái` | 200 | Có chuỗi transition tới `PENDING_REVIEW` | Không |
| 12 | `12 - Admin xem danh sách AI assessment` | 200 | Assessment mới nhất `SUCCEEDED` | `assessmentId=data[0].id` |
| 13 | `13 - Admin xem chi tiết một AI assessment` | 200 | Có input, nguồn, model, output và hash đã lưu | Không |
| 14A | `14A - Admin xem tất cả hồ sơ vay` | 200 | `data` chứa hồ sơ ở mọi trạng thái và vẫn được phân trang | Không |
| 14 | `15 - Admin xem hàng đợi PENDING_REVIEW` | 200 | `data` chứa hồ sơ cần duyệt | Có thể cập nhật version từ đúng hồ sơ |
| 15 | `16 - Admin xem đủ bằng chứng để duyệt` | 200 | Hồ sơ, lịch trả, eligibility và assessment nhất quán | `applicationVersion=version`, `assessmentId=assessment.assessmentId` |
| 16 | `17A - Admin phê duyệt và tạo Contract` | 200 | Application `APPROVED`, Contract `PENDING_SIGNATURE` | `contractNumber`, `contractVersion`, `documentHash` |
| 17 | `18 - Borrower xem danh sách Contract` | 200 | Có Contract vừa tạo | Không |
| 18 | `19 - Borrower xem nội dung Contract` | 200 | Nội dung/hash/version đúng văn bản sắp ký | Cập nhật lại `contractVersion`, `documentHash` |
| 19 | `20 - Borrower xem lịch sử Contract` | 200 | Có transition tạo `PENDING_SIGNATURE` | Không |
| 20 | `21A - Borrower đồng ý và ký MVP` | 200 | `status=SIGNED`, actor đúng borrower | Lưu version mới nếu UI đọc lại |

## 5. Tiêu chí kiểm tra từng chặng

### 5.1. Product đã sẵn sàng cho borrower

Request 02 chỉ thành công khi response đồng thời thỏa mãn:

```text
commandStatus = SUCCEEDED
product.coreSyncStatus = SYNCED
product.currentCoreMappingId != null
```

HTTP 200 không tự chứng minh sync thành công vì endpoint trả trạng thái durable command trong body.
Request 03 phải dùng đúng `product.version` mới nhất từ request 02. Sau khi activate:

```text
status = ACTIVE
coreSyncStatus = SYNCED
```

### 5.2. Lịch trả dự kiến do Fineract tính

Với dữ liệu mẫu `amount=50.000.000` và `termMonths=12`, kiểm tra:

- `periods` có 12 kỳ thanh toán thực;
- `totalPrincipal = 50.000.000`;
- `totalRepayment = totalPrincipal + totalInterest + totalFees + totalPenalties`;
- kỳ cuối có `outstandingBalance = 0`;
- `firstInstallment`, `maximumInstallment` và `calculationPolicyVersion` không rỗng.

Không hard-code một giá trị `totalInterest` trong UI hoặc Postman. Fineract là nguồn tính lịch và số tiền
có thể thay đổi theo ngày giải ngân, kỳ hạn hoặc repayment method.

### 5.3. Nộp hồ sơ và idempotency

Request 06 phải có header mới cho một hồ sơ logic mới:

```http
Idempotency-Key: loan-submit-001
```

Response phải chứa snapshot Product, snapshot thông tin tài chính và snapshot lịch tính. Request 07 cố
ý gửi lại cùng key và cùng body; kết quả đúng là cùng `id/applicationNumber`, không tạo hồ sơ thứ hai.

Nếu dùng cùng key nhưng đổi body, kết quả đúng là:

```text
HTTP 409
code = IDEMPOTENCY_KEY_REUSED
```

### 5.4. Chờ worker và AI

Worker xử lý bất đồng bộ theo chuỗi:

```text
SUBMITTED
  → ELIGIBILITY_PENDING
  → SCORING
  → PENDING_REVIEW
```

UI không được giả định request tạo hồ sơ sẽ chờ AI trả xong. UI nên poll request 08 với khoảng nghỉ hợp
lý và timeout hữu hạn, hoặc thay bằng notification/event khi kiến trúc đó được triển khai.

Assessment thành công phải có:

- `status=SUCCEEDED`;
- `actualModelVersion` không rỗng và khớp model đang deploy;
- `pdProbability` nằm trong khoảng `0..1`;
- `riskScore`, `creditGrade`, `aiRecommendation` không rỗng;
- `failureCode=null`.

Không assert một điểm AI cố định. Request 13 phải chứng minh Loan đã lưu `inputSnapshot`,
`inputSources`, `inputHash`, version model, kết quả, `responseSnapshot`, `responseHash` và policy version.
`suggested_rate` từ AI không được dùng để thay đổi lãi suất Product.

### 5.5. Admin phê duyệt đúng bằng chứng

Request 16 là nguồn chuẩn cho màn hình review của admin. Trước khi approve, kiểm tra:

```text
status = PENDING_REVIEW
assessment.status = SUCCEEDED
assessment.assessmentId = assessmentId sẽ gửi ở request 17A
version = applicationVersion sẽ gửi ở request 17A
```

Request 17A phải dùng một `approvalIdempotencyKey` mới. Response thành công phải có:

```text
applicationStatus = APPROVED
contractStatus = PENDING_SIGNATURE
contractNumber != null
documentHash != null
expiresAt > thời điểm hiện tại
```

### 5.6. Borrower xem đúng văn bản trước khi ký

Request 19 là nguồn chuẩn cho màn hình chi tiết Contract. UI phải hiển thị ít nhất:

- số tiền gốc, kỳ hạn, lãi suất và phương thức trả;
- tổng lãi, phí, phạt và tổng phải trả;
- lịch trả nợ;
- nội dung và phiên bản hợp đồng;
- hạn ký;
- trạng thái hiện tại.

Khi borrower bấm ký, UI gửi lại đúng `version` và `documentHash` vừa đọc từ request 19:

```json
{
  "version": 0,
  "documentHash": "<hash lấy từ request 19>",
  "signatureMethod": "CLICK_WRAP_MVP"
}
```

Không tự tăng version ở UI và không tự tính document hash. Nếu Contract đã đổi hoặc hết hạn, backend
phải từ chối thay vì ký một văn bản khác với văn bản borrower đã xem.

## 6. Các nhánh phải kiểm tra bằng dữ liệu riêng

| Request | Khi nào dùng | Vì sao không chạy chung happy path |
|---|---|---|
| `10 - Borrower rút hồ sơ` | Borrower hủy hồ sơ còn được phép rút | Hồ sơ `WITHDRAWN` không thể tiếp tục duyệt |
| `14 - Admin yêu cầu chấm điểm lại` | Assessment `FAILED` cần mở lại | HTTP `202` chỉ là biên nhận; UI poll `resultPath` tới `SUCCEEDED`/`FAILED` |
| `17B - Admin từ chối` | Dùng thay request 17A | Hồ sơ đã `APPROVED` không thể reject lại |
| `21B - Borrower từ chối Contract` | Dùng thay request 21A | Contract đã `SIGNED` không thể decline lại |

Mỗi nhánh nên tạo hồ sơ hoặc Contract mới, đồng thời dùng idempotency key mới.

## 7. Quy tắc UI phải tuân thủ

1. `version` là dữ liệu ẩn phục vụ optimistic locking; UI lấy từ response mới nhất và gửi nguyên giá trị,
   không tự cộng.
2. `Idempotency-Key` đại diện cho một thao tác logic. Retry cùng thao tác dùng lại key và body; thao tác
   mới phải tạo key mới.
3. Không coi HTTP 200 của core-sync là thành công nếu `commandStatus` chưa phải `SUCCEEDED`.
4. Không tự tính lãi hoặc lịch trả trong UI; chỉ trình bày dữ liệu backend/Fineract trả về.
5. Không cho admin approve khi hồ sơ chưa `PENDING_REVIEW` hoặc assessment chưa `SUCCEEDED`.
6. Không cho borrower ký khi Contract không còn `PENDING_SIGNATURE`, đã hết hạn hoặc hash đã thay đổi.
7. Mọi danh sách dùng response phân trang `data`, `page`, `size`, `totalElements`.

## 8. Ranh giới hoàn thành hiện tại

Luồng hiện có kết thúc tại:

```text
LoanApplication = APPROVED
LoanContract = SIGNED
```

Các chức năng sau chưa thuộc vertical slice này:

- tạo Loan Account thật trong Fineract;
- chuyển Contract `SIGNED → EFFECTIVE`;
- giải ngân;
- lịch thu nợ thực tế và đối soát Payment;
- servicing khoản vay trong core banking.

Vì vậy UI hiện tại chỉ được hiển thị “Hợp đồng đã ký”, chưa được hiển thị “Đã giải ngân” hoặc “Khoản vay
đang hoạt động”.
