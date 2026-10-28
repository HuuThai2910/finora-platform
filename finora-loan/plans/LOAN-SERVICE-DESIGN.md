---
document_type: SERVICE_DESIGN
owner: Thai
reviewers:
  - Hai
status: DRAFT_FOR_REVIEW
created_at: 2026-08-01
updated_at: 2026-09-05
source_of_truth: true
---

# Thiết kế nghiệp vụ tổng thể FINORA Loan Service

## 1. Mục đích và cách đọc

Đây là **tài liệu thiết kế tổng thể duy nhất** của Loan Service. Tài liệu trả lời các câu hỏi:

- Loan Service phục vụ nghiệp vụ gì và chịu trách nhiệm đến đâu?
- Luồng vay đi từ sản phẩm đến giải ngân, trả nợ như thế nào?
- Dữ liệu nào thuộc Loan, AI, User, Payment và Apache Fineract?
- Những trạng thái và điều kiện chuyển trạng thái bắt buộc là gì?
- Từng phần được triển khai, kiểm thử và nghiệm thu trong task LN nào?

Quy tắc phân tầng tài liệu:

| Tầng | Trả lời | Không chứa |
|---|---|---|
| `PLAN.md` | Task nào đang chờ, đang làm, đã xong; phụ thuộc ai | Thiết kế field/API chi tiết |
| `LOAN-SERVICE-DESIGN.md` | Bức tranh nghiệp vụ và kiến trúc xuyên suốt | JSON/migration/test case đầy đủ của từng task |
| `LN-xxx.md` | Thiết kế triển khai chi tiết của đúng một task | Nội dung lặp lại toàn bộ service |

Nếu Design và một LN mâu thuẫn, phải dừng triển khai, cập nhật quyết định trong Design trước, sau đó đồng bộ LN liên quan.

### 1.1. Thuật ngữ kỹ thuật và cách hiểu dễ

Tài liệu giữ thuật ngữ tiếng Anh để developer tra cứu, nhưng lần đầu đọc có thể hiểu theo bảng sau:

| Thuật ngữ | Cách hiểu trong FINORA |
|---|---|
| Entity | Bản ghi có định danh và vòng đời riêng, ví dụ một hồ sơ vay hoặc một hợp đồng |
| Value object/embedded | Nhóm dữ liệu thuộc hẳn entity cha, không có repository/vòng đời riêng; ví dụ thông tin tài chính tự khai nằm trong Application |
| Snapshot | Bản chụp dữ liệu tại thời điểm giao dịch; Product/User thay đổi sau đó không làm lịch sử đổi theo |
| Projection | Bản sao chỉ đọc tối thiểu để hiển thị/điều phối; nguồn chuẩn vẫn ở service hoặc Fineract sở hữu |
| Mapping | Bảng nối cho biết ID/phiên bản FINORA tương ứng với tài nguyên nào bên hệ thống ngoài |
| Durable command | “Phiếu công việc” lưu trong database; service restart vẫn nhớ phải gửi lệnh gì và đã thử bao nhiêu lần |
| Worker | Tiến trình nền lấy các công việc đến hạn và xử lý theo lô; không phải một người dùng hoặc microservice mới |
| Idempotency | Gửi lại cùng một ý định không tạo thêm hồ sơ, Product core, hợp đồng hoặc giao dịch lần hai |
| Retry | Thử lại lỗi tạm thời có giới hạn; lỗi dữ liệu/quyền/nghiệp vụ không được thử mù |
| Reconciliation | Đối chiếu với nguồn chuẩn khi timeout làm Loan chưa biết dependency đã thành công hay chưa |
| Transaction | Nhóm thay đổi database cùng thành công hoặc cùng hoàn tác |
| Concurrency | Hai request/worker chạm cùng dữ liệu gần như đồng thời và có nguy cơ ghi đè/lặp side effect |
| Optimistic locking | Dùng `version` để từ chối request đang sửa bản dữ liệu cũ, thay vì âm thầm ghi đè thay đổi mới |
| Index | Mục lục giúp database tìm đúng bản ghi mà không đọc toàn bảng; thêm index cũng làm thao tác ghi tốn hơn |
| N+1 query | Một query lấy danh sách rồi vô tình chạy thêm một query cho từng dòng, khiến số query tăng theo số bản ghi |
| Outbox | Sự kiện được lưu cùng transaction với state nguồn để commit rồi mới phát, tránh đổi state nhưng mất event |
| Saga | Quy trình nhiều bước xuyên service có trạng thái bền vững để biết tiếp tục, retry hoặc bù từ bước nào |

## 2. Mục tiêu nghiệp vụ

Loan Service quản lý vòng đời khởi tạo khoản vay của FINORA:

1. Admin tạo và kích hoạt sản phẩm vay.
2. Người vay xem sản phẩm, chọn số tiền và kỳ hạn, xem lịch trả dự kiến.
3. Người vay khai thông tin cần thiết và gửi hồ sơ trực tiếp.
4. Loan lấy hồ sơ người vay/KYC, installment từ Fineract và gửi contract sang AI v17.
5. Loan áp grade pricing và decision: tự duyệt, tự từ chối hoặc đưa admin thẩm định.
6. Khi duyệt, hệ thống tạo một hợp đồng chờ người vay ký.
7. Sau khi ký, khoản vay được đưa sang giai đoạn gọi vốn, giải ngân và trả nợ.
8. Apache Fineract quản lý lịch trả chính thức, dư nợ và quá hạn sau giải ngân.

Mục tiêu không phải là đưa toàn bộ core banking vào Loan Service. Loan giữ quy trình khởi tạo và quyết định kinh doanh của FINORA; Fineract giữ sổ vận hành khoản vay.

## 3. Tác nhân

| Tác nhân | Trách nhiệm |
|---|---|
| Borrower | Xem sản phẩm, mô phỏng lịch trả, gửi hồ sơ, xem quyết định, ký hoặc từ chối hợp đồng |
| Admin/Credit Officer | Cấu hình sản phẩm, xem hồ sơ và kết quả AI, duyệt hoặc từ chối |
| AI Service | Chấm điểm/grade/decision theo model v17; không tính final rate hoặc lịch trả nợ |
| User Service | Nguồn hồ sơ người dùng và KYC khi contract sẵn sàng |
| Investment Service | Quản lý chiến dịch gọi vốn và cam kết đầu tư |
| Payment Service | Thực hiện dòng tiền, ledger và đối soát; chuyển kết quả trả nợ sang Fineract |
| Apache Fineract | Nguồn chuẩn về lịch trả, dư nợ, khoản quá hạn sau khi khoản vay được tạo |
| Loan Service | Điều phối origination, lưu snapshot quyết định, quản lý Application và Contract |

Trong giai đoạn User/Security chưa hoàn thiện, Loan dùng actor giả lập đã cấu hình sẵn. Đây chỉ là adapter tạm thời, không được rải hard-code trong business service.

## 4. Các quyết định đã chốt

| Mã | Quyết định |
|---|---|
| D01 | Loan Service dùng PostgreSQL 17; Neon Project `finora-loan` riêng, dùng `neondb` và default role lấy từ Connect trong giai đoạn khóa luận. Docker PostgreSQL vẫn dùng DB/role `finora_loan` làm offline/test fallback. |
| D02 | Fineract 1.15 chạy với PostgreSQL/Neon Project riêng; không dùng chung project/schema/database/role với Loan. |
| D03 | Mỗi Product có `minAnnualInterestRate <= annualInterestRate(base) <= maxAnnualInterestRate <= 20%`; field cũ `annualInterestRate` tiếp tục là base rate. |
| D04 | Product hỗ trợ `ANNUITY` và `EQUAL_PRINCIPAL`; admin chọn cách trả khi tạo Product. |
| D05 | Người vay luôn chọn kỳ hạn khi nộp hồ sơ; kỳ hạn phải thuộc danh sách Product cho phép. |
| D06 | Lịch trả hiển thị trước khi duyệt là bản mô phỏng từ Fineract, chưa phải nghĩa vụ chính thức. |
| D07 | Application gửi trực tiếp ở trạng thái `SUBMITTED`; không lưu Draft phía backend. Draft giao diện, nếu cần, thuộc local storage của frontend. |
| D08 | Thông tin thu nhập, việc làm, học vấn và nhà ở hiện do borrower tự khai và được snapshot tại thời điểm nộp. |
| D09 | `BorrowerCreditProfile` vẫn lưu lịch sử FINORA cho admin/LN-014; AI v17 không còn nhận `delinq_2yrs/pub_rec`. |
| D10 | Model chính thức là AI v17, endpoint `/api/v1/ai/credit/explain`; request/response được snapshot cùng model/policy version. |
| D11 | `int_rate` gửi AI là base rate; `installment` lấy từ lịch base do Fineract tính. Loan không tự tính schedule. |
| D12 | AI decision điều khiển ba nhánh: `APPROVED`, `PENDING_REVIEW`, `REJECTED`; Loan thực thi state, admin chỉ quyết định nhánh review. |
| D13 | Không tạo `LoanOffer`. Approve chốt final terms trên Application; Contract chỉ sinh sau terms gate. |
| D14 | Điều khoản không bất lợi dùng pre-authorization disclosure V2; điều khoản bất lợi phải được borrower accept/decline theo version/hash/expiry trước khi tạo Contract. |
| D15 | Hồ sơ giữ lịch ban đầu theo base rate và lịch `CONTRACT` theo final rate; lịch chính thức được xác lập khi giải ngân trong Fineract. |
| D16 | Các bảng nghiệp vụ có `createdAt`, `updatedAt`, `createdBy`, `updatedBy` nếu có hành vi con người hoặc cần truy vết quản trị. |
| D17 | Mọi tích hợp ngoài service phải có timeout, idempotency/correlation key, log có ngữ cảnh và cơ chế retry/reconcile phù hợp. |
| D18 | Grade A/B/C/D/E điều chỉnh rate theo policy Loan có version; principal và term không tự thay đổi. |
| D19 | AI auto-approved vẫn đi qua cùng outcome-based terms gate; không đồng nghĩa borrower đã ký hoặc tiền đã giải ngân. |
| D21 | Contract/PDF trước Investment là artifact demo sau terms gate, không mock lender. Target production lập Contract song phương sau khi Investment xác định bên cho vay. |
| D20 | Ràng buộc pháp lý dùng nguồn chung [`docs/LEGAL-COMPLIANCE.md`](../../docs/LEGAL-COMPLIANCE.md); giới hạn chưa có bản gốc phải ghi rõ chưa xác minh. |

Mọi thay đổi các quyết định trên phải ghi vào Design, LN bị ảnh hưởng và roadmap phối hợp nếu liên quan service của Hải.

### 4.1. Cổng pháp lý theo chức năng

| Chức năng Loan | Mã đối chiếu bắt buộc |
|---|---|
| Product rate/term/limit | [`LEGAL-RATE-01`](../../docs/LEGAL-COMPLIANCE.md#legal-rate-01--khung-minbasemax-và-trần-lãi-suất), [`LEGAL-TERM-01`](../../docs/LEGAL-COMPLIANCE.md#legal-term-01--thời-hạn-product-và-hợp-đồng), [`LEGAL-LIMIT-01`](../../docs/LEGAL-COMPLIANCE.md#legal-limit-01--dư-nợ-người-vay) |
| Catalog, preview, submit và final schedule | [`LEGAL-DISCLOSURE-01`](../../docs/LEGAL-COMPLIANCE.md#legal-disclosure-01--công-bố-lãi-và-lịch-trả) |
| AI scoring/auto decision/risk pricing | [`LEGAL-AI-01`](../../docs/LEGAL-COMPLIANCE.md#legal-ai-01--chấm-điểm-tự-động-duyệt-và-khả-năng-giải-trình), [`LEGAL-DATA-01`](../../docs/LEGAL-COMPLIANCE.md#legal-data-01--dữ-liệu-hồ-sơ-và-cccd) |
| Contract/read/sign/decline | [`LEGAL-CONTRACT-01`](../../docs/LEGAL-COMPLIANCE.md#legal-contract-01--hợp-đồng-và-xác-nhận-điện-tử) |
| Giải ngân và repayment | [`LEGAL-PAYMENT-01`](../../docs/LEGAL-COMPLIANCE.md#legal-payment-01--giải-ngân-và-thanh-toán) |

Mã pháp lý là cổng review, không phải bằng chứng hệ thống đã được cấp phép. Các mục
`NEEDS_LEGAL_REVIEW/NEEDS_PRIMARY_SOURCE` phải được xử lý trước production.

## 5. Ranh giới sở hữu dữ liệu

| Dữ liệu | Nguồn chuẩn | Loan lưu gì |
|---|---|---|
| Product nghiệp vụ FINORA | Loan | Bản đầy đủ và trạng thái sync Fineract |
| Product engine | Fineract | `fineractProductId` và snapshot/request phục vụ đối soát |
| Application | Loan | Bản đầy đủ, snapshot Product và thông tin borrower lúc submit |
| Hồ sơ/KYC hiện hành | User | Snapshot dùng cho quyết định tại thời điểm submit |
| Điểm và kết quả AI | AI | Request/response snapshot, model version, trạng thái chấm |
| Quyết định approve/reject | Loan | Người quyết định, thời điểm, lý do và version |
| Contract/consent | Loan | Điều khoản, hash tài liệu, lịch sử ký/từ chối |
| Lịch trả chính thức/dư nợ/quá hạn | Fineract | ID tham chiếu và snapshot cần hiển thị/đối soát |
| Dòng tiền/ledger | Payment | Payment reference và trạng thái đồng bộ |
| Hồ sơ gọi vốn | Investment | Reference và trạng thái tổng hợp cần thiết |

Không service nào truy cập trực tiếp database của service khác. Mọi trao đổi dùng API/event contract đã thống nhất.

## 6. Luồng nghiệp vụ tổng thể

### 6.1. Admin tạo và kích hoạt Product

1. Admin gửi cấu hình Product gồm min/base/max rate, số tiền, kỳ hạn và phương thức trả nợ.
2. Loan kiểm tra thứ tự rate, trần 20%, kỳ hạn tối đa 24 tháng, amount range và mã Product duy nhất.
3. Code hiện hành lưu Product ở `DRAFT/NOT_SYNCED`; API tạo không giữ request chờ Fineract.
4. Admin/API gọi `core-sync`; Loan lưu Mapping và durable command trước khi adapter gọi REST Fineract.
5. Khi nhận thành công, Loan lưu `fineractProductId`, chuyển Mapping/Product sang `SYNCED`.
6. Chỉ Product đã sync mới được chuyển sang `ACTIVE` để borrower nhìn thấy.

Mục tiêu UI là một thao tác tạo Product có thể tự enqueue sync và hiển thị “đang đồng bộ”; việc bỏ nút
`core-sync` khỏi UI **chưa triển khai**. Command/worker hiện có vẫn là nền để tự động hóa mà không cần
đưa Kafka vào chỉ để Loan tự gửi rồi tự nhận message của chính mình.

Nếu Fineract timeout, không gọi lặp mù. Yêu cầu được ghi nhận bằng command/idempotency key để retry hoặc reconcile.

Chi tiết domain, field và migration: [LN-003](LN-003-loan-product.md).
Chi tiết API Fineract: [LN-006](LN-006-fineract-product-schedule-integration.md).

### 6.2. Borrower xem Product và mô phỏng lịch trả

1. Borrower lấy danh sách Product `ACTIVE`.
2. Borrower chọn số tiền và một kỳ hạn hợp lệ.
3. Loan gửi Product mapping, số tiền và kỳ hạn sang Fineract để tính lịch.
4. Loan trả tổng tiền, tiền mỗi kỳ và danh sách kỳ dự kiến cho UI.
5. UI phải ghi rõ đây là **lịch dự kiến**, chỉ có hiệu lực sau khi hồ sơ được duyệt, hợp đồng được ký và khoản vay được tạo.

Không lưu mỗi lần người dùng kéo thanh số tiền. Chỉ snapshot kết quả được dùng khi submit Application.

### 6.3. Borrower gửi Application

1. Frontend có thể giữ form chưa hoàn tất ở local storage.
2. Khi borrower bấm gửi, Loan xác thực Product, số tiền, kỳ hạn và dữ liệu tự khai.
3. Loan lấy/kiểm tra lại schedule từ Fineract để không tin dữ liệu tiền do client gửi.
4. Loan tạo Application ở `SUBMITTED` trong một transaction.
5. Loan lưu snapshot Product, schedule summary, thông tin tài chính và mục đích vay.
6. Sau commit, workflow KYC và AI được kích hoạt bất đồng bộ.

Không có endpoint backend `save draft` trong phạm vi hiện tại.

Chi tiết field và API: [LN-004](LN-004-loan-application.md).

### 6.4. Lấy hồ sơ borrower và kiểm tra KYC

1. Loan gọi `BorrowerProfileProvider`.
2. Giai đoạn hiện tại, provider cục bộ trả age 30 và KYC VERIFIED để phát triển độc lập.
3. Khi User contract sẵn sàng, thay provider bằng HTTP client mà không đổi business service.
4. Loan lưu snapshot nguồn, version và thời điểm lấy dữ liệu.
5. Hồ sơ không đạt điều kiện bắt buộc được từ chối hoặc chuyển manual review theo policy đã chốt.

Chi tiết: [LN-005](LN-005-borrower-profile-kyc.md).

### 6.5. Gửi AI v17, định giá và tạo lịch cuối

Loan tạo payload AI từ ba nhóm nguồn:

| Nhóm | Ví dụ | Nguồn |
|---|---|---|
| Application tự khai | income, employment length, home ownership, DTI | Snapshot Application |
| Product/Fineract | amount, term, base `int_rate`, `installment`, `interest_method` | Product và lịch `SUBMISSION_SCORING` |
| User/KYC | age, verification status, `so_cccd` khi contract thật cho phép | Borrower profile provider |

AI v17 không nhận `delinq_2yrs/pub_rec/effective_apr/suggested_rate`. `so_cccd` hiện là null cho tới khi User Service có contract bảo vệ dữ liệu; Loan không lưu số CCCD thô.

Sau response, Loan thực hiện một chiều, không lặp lại chấm điểm:

1. Grade → adjustment → `finalRate = clamp(baseRate + adjustment, minRate, maxRate, 20%)`.
2. Nếu decision khác `REJECTED`, Loan gửi final rate cùng principal/term/method cũ sang Fineract để tạo lịch `CONTRACT`.
3. `APPROVED` đi qua terms gate; `PENDING_REVIEW` vào hàng admin; `REJECTED` đóng hồ sơ mà không tính final rate/lịch cuối.

Chi tiết mapping, entity, API và retry: [LN-007](LN-007-credit-profile-ai-assessment.md).

### 6.6. Admin duyệt hoặc từ chối

Admin chỉ xử lý `PENDING_REVIEW` và nhìn một màn hình tổng hợp:

- dữ liệu Application và snapshot borrower;
- Product, số tiền, kỳ hạn, min/base/max/final rate;
- lịch ban đầu và lịch cuối;
- kết quả AI và cảnh báo dữ liệu;
- lịch sử thao tác.

Approve/reject phải dùng Application version và `Idempotency-Key`. Nếu version cũ, API trả conflict để UI tải lại bản mới; frontend không tự đoán version bằng cách cộng số. Cùng idempotency key nhưng payload khác cũng bị từ chối.

Khi approve thành công:

1. Application chuyển `APPROVED`.
2. Loan đóng băng evidence `termsVersion/hash/expiry` và so sánh lịch ban đầu/lịch cuối.
3. Nếu không bất lợi, Loan ghi `LoanFundingRequested` ngay; nếu bất lợi, chờ borrower accept rồi mới ghi yêu cầu gọi vốn. Không gọi lại Fineract ở bước xác nhận.
4. Chỉ sau `LoanFullyFunded` hợp lệ, Loan mới render một Contract/PDF chung từ exact terms và allocation bất biến; Contract khởi tạo ở `PENDING_LENDER_SIGNATURES`.
5. Nhà đầu tư ký trước; sau đủ chữ ký lender, borrower mới thấy yêu cầu ký. Mọi bên ký cùng document hash.

Khi reject, Application chuyển `REJECTED` cùng reason; không tạo Contract.

### 6.7. Borrower ký hoặc từ chối Contract

Borrower mở một tài liệu hợp đồng, không phải ba “hợp đồng” khác nhau. UI có thể đã hiển thị ước tính lúc tạo hồ sơ, nhưng chỉ Contract là tài liệu pháp lý.

- `sign` lưu actor, thời điểm, version điều khoản, hash tài liệu, idempotency evidence và bằng chứng consent.
- `decline` lưu lý do và đóng quy trình theo policy.
- Ký phải idempotent: gửi lại cùng request không tạo Contract hoặc event trùng.
- Không cho ký Contract đã hết hạn, đã thay đổi hoặc không thuộc borrower.
- Application giữ quyết định `APPROVED` nếu Contract bị `DECLINED/EXPIRED`; Contract là nguồn trạng thái consent duy nhất.
- `CLICK_WRAP_MVP` chỉ là consent MVP, không được mô tả như chữ ký số SmartCA.
- Từ R5 ([W3C](../../.agents/plans/W3C-pades-contract-signing.md)), hợp đồng nhiều bên mới mang chữ ký PAdES-B-B ẩn
  nhúng vào PDF: lender ký nối tiếp, borrower ký cuối, mỗi chữ ký là một incremental update; trang biên nhận chữ ký số
  là tài liệu riêng. Chứng thư demo (MOCK) và chứng thư VNPT UAT không có giá trị pháp lý production.

Chi tiết: [LN-008](LN-008-approval-loan-contract.md).

### 6.8. Gọi vốn, giải ngân và tạo khoản vay chính thức

Sau khi Contract được ký:

1. Loan phát event yêu cầu Investment mở chiến dịch gọi vốn.
2. Investment là nguồn chuẩn về cam kết và tỷ lệ funded.
3. Khi đủ vốn, Saga bảo đảm Fineract Client rồi tạo và approve core Loan bằng đúng Contract/Product mapping; **chưa ghi disbursement**.
4. Loan gửi Payment command giải ngân; Payment ghi ledger và trả kết quả bằng business reference idempotent.
5. Chỉ sau khi Payment xác nhận tiền đã chuyển, Saga gọi Fineract disburse với cùng bằng chứng ngày/số tiền/reference và lưu `fineractLoanId`.
6. Lịch Fineract sau disbursement là lịch trả chính thức; Loan refresh projection để đối chiếu với lịch dự kiến đã ký.
7. Loan chuyển `FinoraLoan → ACTIVE` và `Contract → EFFECTIVE` chỉ khi core xác nhận disbursement.

Đây là thứ tự mục tiêu của LN-011; endpoint/payload và compensation cuối vẫn cần contract Payment/Fineract fixture trước khi task được `APPROVED`.

### 6.9. Trả nợ và cập nhật lịch sử tín dụng nội bộ

1. Payment nhận/thực hiện thanh toán và ghi ledger.
2. Payment gửi repayment sang Fineract bằng idempotency key.
3. Fineract phân bổ gốc/lãi/phí và cập nhật dư nợ.
4. Loan đọc trạng thái cần hiển thị qua adapter/API; không tự tái tạo sổ dư nợ.
5. Job/event cập nhật `BorrowerCreditProfile` từ dữ liệu quá hạn/charge-off đã xác nhận.
6. Profile này trở thành đầu vào cho hồ sơ tiếp theo.

## 7. Mô hình domain mức tổng thể

### 7.1. ERD hiện hành theo migration V1–V8

```text
LoanProduct 1 ───────────── 0..N FineractProductMapping
    │                              │
    │ currentCoreMappingId         └──────── 0..N FineractCommand
    │ chọn 0..1 mapping hiện hành                 │
    │                                             └── cùng tham chiếu Product
    └────────────────────── 0..N LoanApplication
                                      │
FineractProductMapping 1 ─────── 0..N │ (Application giữ coreMappingIdSnapshot)
                                      │
                                      ├──── 1..2 ScheduleCalculationSnapshot
                                      ├──── 0..N LoanApplicationStatusHistory
                                      ├──── 0..N BorrowerEligibilityCheck
                                      │                │
                                      │                └──── 0..N CreditScoringAssessment
                                      │                                │
                                      │                                └──── 0..N CreditScoringRetryRequest
                                      └──── 0..1 LoanContract
                                                       │
                                                       └──── 0..N LoanContractStatusHistory

ScheduleCalculationSnapshot(CONTRACT) 1 ───── 0..1 LoanContract

Borrower từ User Service 1 ───── 0..N LoanApplication       (quan hệ logic bằng borrowerId, không FK chéo)
Borrower từ User Service 1 ───── 0..1 BorrowerCreditProfile (quan hệ logic bằng borrowerId, không FK chéo)
```

`1`, `0..1` và `0..N` lần lượt nghĩa là bắt buộc đúng một, có thể chưa có/tối đa một và có thể có nhiều.
Code chủ động lưu scalar ID thay vì JPA association graph; quan hệ vẫn được FK/unique bảo vệ trong
PostgreSQL nhưng endpoint không vô tình tải cả collection và gây N+1.

| Quan hệ | Vì sao cần | Database/invariant bảo vệ |
|---|---|---|
| Product → Mapping `1:0..N` | Mỗi phiên bản cấu hình có một mapping lịch sử riêng; không ghi đè ID core cũ | FK Product, unique `(loan_product_id, finora_product_version)` |
| Product → current Mapping `1:0..1` | Chọn mapping `SYNCED` dùng cho preview/hồ sơ mới | FK `current_core_mapping_id` + service kiểm tra mapping thuộc đúng Product/version |
| Mapping/Product → Command `1:0..N` | Giữ từng lần thực thi/khôi phục external side effect | Hai FK + unique idempotency key; lịch sử không cascade delete |
| Product/Mapping → Application `1:0..N` | Nhiều hồ sơ có thể dùng cùng Product/mapping đã công bố | FK Product và FK mapping snapshot |
| Application → Schedule snapshot `1:1..2` | Một lịch base cho submit/scoring và tối đa một lịch final cho Contract | unique `(application_id,purpose)` + FK; remote call ngoài transaction |
| Application → history/check/assessment `1:0..N` | Một hồ sơ có nhiều lần chuyển trạng thái, kiểm tra và scoring attempt | FK + index timeline/worker; Application trỏ assessment mới nhất |
| Application → Contract `1:0..1` | Chỉ hồ sơ approved mới có một hợp đồng pháp lý trong MVP | unique `loan_contracts.application_id` |
| Schedule snapshot `CONTRACT` → Contract `1:0..1` | Contract đóng băng đúng lịch final đã công bố | FK `calculation_snapshot_id`; service kiểm tra purpose/Application |
| Contract → history `1:0..N` | Mỗi ký/từ chối/hết hạn cần timeline audit | FK + index theo `contract_id, occurred_at` |

Thông tin tài chính, Product terms và pricing disclosure trong `LoanApplication` là embedded columns/
immutable snapshot, **không phải bảng độc lập**. `BorrowerCreditProfile` không có FK sang User vì cấm
foreign key xuyên service; `borrowerId` chỉ là logical reference có source/version.

### 7.2. Mô hình mục tiêu chưa triển khai (LN-009 trở đi)

```text
LoanContract SIGNED
    └──── 0..1 MarketListingIntent/Outbox ──event──> Investment Listing
                         │
                         └──── 0..1 FinoraLoan/FundingProjection
                                      │
                                      ├──── 0..1 DisbursementSaga
                                      ├──── 0..1 FineractClient/LoanMapping
                                      └──── 0..1 LoanServicingProjection
```

Các entity trong sơ đồ này là thiết kế planned của LN-009–LN-012, chưa tồn tại trong migration V1–V8.
Cardinality/field cuối chỉ được khóa trong LN tương ứng sau khi contract Investment/Payment được duyệt.

Vai trò từng aggregate:

| Aggregate/Entity | Vai trò |
|---|---|
| `LoanProduct` | Quy tắc kinh doanh mà borrower có thể chọn |
| `FineractProductMapping` | Lưu từng phiên bản liên kết Product FINORA ↔ Fineract; cho biết mapping nào được dùng và mapping cũ nào phải giữ để giải thích hồ sơ lịch sử |
| `FineractCommand` | Phiếu việc bền vững để worker gọi/retry/reconcile Fineract mà restart không quên và timeout không tạo Product trùng |
| `LoanApplication` | Hồ sơ xin vay và state machine phê duyệt |
| Các snapshot Application | Đóng băng dữ liệu đã dùng để thẩm định |
| `BorrowerCreditProfile` | Tổng hợp lịch sử tín dụng nội bộ dùng cho lần chấm tiếp theo |
| `CreditScoringAssessment` | Bằng chứng request/response và kết quả AI |
| `LoanContract` | Điều khoản pháp lý và consent |
| `FinoraLoan` | Entity planned sau gọi vốn/giải ngân; chưa có trong V1–V8 và không thay thế Fineract ledger |
| Outbox | Entity planned để phát domain event xuyên service sau local commit; không thay `FineractCommand` nội bộ |

Không thêm entity chỉ để “đẹp mô hình”. Một bảng mới chỉ hợp lý khi có vòng đời, truy vấn, audit hoặc tính nhất quán độc lập.

## 8. State machine

### 8.1. Product

Product có hai state machine độc lập, không dùng `SYNCED` như một `ProductStatus`:

```text
ProductStatus:  DRAFT ──activate khi core SYNCED──> ACTIVE ──deactivate──> INACTIVE ──archive──> ARCHIVED

CoreSyncStatus: NOT_SYNCED ──enqueue──> PENDING ──success──> SYNCED
                                      └── lỗi cuối──> FAILED ──retry/reconcile──> PENDING/SYNCED
```

Sync Fineract chỉ đổi `coreSyncStatus` và mapping/command. Activate mới đổi trạng thái Product; vì vậy UI
có thể hiển thị “bản nháp đã đồng bộ” trước khi admin quyết định công bố.

### 8.2. Application

```text
SUBMITTED
   ├── borrower withdraw ───────────────> WITHDRAWN
   └── bắt đầu kiểm tra ────────────────> ELIGIBILITY_PENDING
ELIGIBILITY_PENDING
   ├── đủ điều kiện chấm điểm ──────────> SCORING
   └── cần con người xem xét ───────────> PENDING_REVIEW
SCORING
   ├── lỗi tạm thời ────────────────────> SCORING_RETRY_PENDING
   ├── AI APPROVED + final schedule ────> APPROVED
   ├── AI PENDING_REVIEW + final schedule > PENDING_REVIEW
   └── AI REJECTED ─────────────────────> REJECTED
SCORING_RETRY_PENDING
   ├── retry ───────────────────────────> SCORING
   └── hết retry/cần người xử lý ──────> PENDING_REVIEW
PENDING_REVIEW
   ├── admin approve (LN-008) ─────────> APPROVED
   └── admin reject (LN-008) ──────────> REJECTED
```

Không quay lại Draft. Mọi transition kiểm tra trạng thái hiện tại và version.

### 8.3. Contract

```text
PENDING_SIGNATURE
   ├── borrower sign ────> SIGNED
   ├── borrower decline ─> DECLINED
   └── hết hạn ──────────> EXPIRED
```

### 8.4. Khoản vay FINORA

```text
PENDING_LISTING -> ON_MARKET -> FUNDED -> DISBURSEMENT_PENDING -> ACTIVE
ACTIVE -> CLOSED/COMPLETED
ACTIVE -> DEFAULTED
```

Các trạng thái này là **planned từ LN-009**, chưa có trong V1–V8. Tên `CLOSED` hay `COMPLETED` phải chọn
đúng một tên trước migration; không tạo đồng thời hai enum đồng nghĩa ở Loan, Investment và Fineract mapper.

## 9. Dữ liệu phải được snapshot

Snapshot bảo đảm một quyết định lịch sử không thay đổi khi Product hoặc hồ sơ User thay đổi sau đó.

| Thời điểm | Snapshot bắt buộc |
|---|---|
| Submit Application | Product min/base/max rate/repayment method, amount/term, initial schedule, dữ liệu tự khai, purpose |
| Gọi AI | Đúng contract v17, nguồn từng field, model contract version |
| Nhận AI | Response explain, model/decision policy version, latency/correlation ID |
| Định giá | credit grade, configured/applied adjustment, final rate và pricing policy version |
| Admin quyết định | actor, reason, decision policy, assessment evidence, Application version và idempotency hash |
| Tạo Contract | final terms/schedule reference, Product/Application/AI references, nội dung UTF-8, terms/document version và document hash |
| Ký Contract | document hash, consent version, signed time, actor/device evidence theo policy |
| Tạo Fineract loan | command/response, Fineract ID, schedule chính thức hoặc reference |

Không chỉ lưu “kết quả cuối”; phải đủ dữ liệu để trả lời vì sao hệ thống ra quyết định đó.

## 10. Request, response và persistence xuyên luồng

| Tương tác | Request chính | Response chính | Nơi lưu |
|---|---|---|---|
| Loan -> Fineract create product | Cấu hình Product đã ánh xạ | command/resource ID | Mapping + integration command |
| Loan -> Fineract calculate schedule | Product ID, amount, term, base hoặc final rate, ngày dự kiến | installments/totals | `SUBMISSION_SCORING` và `CONTRACT` snapshots |
| Loan -> User | borrower ID | profile/KYC/version | Borrower profile snapshot |
| Loan -> AI | Contract v17 với installment/base rate | score/grade/decision/explanation/model/policy | CreditScoringAssessment |
| Loan -> Investment | Contract/loan funding request | campaign/reference/state | FinoraLoan/integration state |
| Loan -> Payment | disbursement request | payment reference/result | FinoraLoan/integration state |
| Payment -> Fineract | repayment transaction | allocation/result | Payment ledger; Loan chỉ giữ reference cần thiết |

Payload exact, timeout, error mapping và test contract nằm trong LN sở hữu integration.

## 11. Nhất quán dữ liệu và xử lý lỗi

Không dùng distributed transaction giữa Loan, AI, User, Payment, Investment và Fineract.

Transaction là “nhóm thay đổi cùng thành công hoặc cùng hoàn tác”. Ví dụ đồng bộ Product được tách:

```text
Transaction 1: Product PENDING + Mapping PENDING + Command PENDING → cùng commit/rollback
Ngoài transaction: worker/request gọi REST Fineract, không giữ DB connection/row lock khi chờ mạng
Transaction 2: Command + Mapping + Product → cùng ghi SUCCEEDED/SYNCED hoặc RETRY_PENDING/FAILED
```

Nếu đặt HTTP trong Transaction 1, một lần Fineract chậm 10 giây sẽ giữ connection/lock lâu, làm admin
khác khó sửa Product. Nếu không lưu command trước khi gọi, service restart có thể quên một side effect đã
được Fineract thực hiện.

Mẫu bắt buộc:

1. Cập nhật dữ liệu Loan trong local transaction.
2. Ghi outbox/integration command cùng transaction nếu cần phát lệnh sau commit.
3. Gọi ngoài với idempotency/correlation key.
4. Lưu kết quả hoặc trạng thái retry.
5. Có job/reconcile cho trạng thái không chắc chắn do timeout.
6. Không giữ database transaction trong lúc chờ HTTP lâu.

### 11.1. Concurrency và request cạnh tranh

Concurrency là hai request/worker chạm cùng dữ liệu gần như đồng thời. FINORA không chỉ “hy vọng” chúng
chạy lần lượt:

| Tình huống | Nếu không bảo vệ | Cơ chế hiện hành | Kết quả request thua |
|---|---|---|---|
| Hai admin sửa cùng Product | Người lưu sau ghi đè cấu hình mới | `@Version`/request `version` | `409`, UI tải lại bản mới; không tự cộng version |
| Hai request tạo cùng Product code | Có hai Product logic trùng | unique `loan_products.code` là chốt cuối | Một request conflict |
| API và retry worker cùng chạy command | Có thể tạo Product Fineract hai lần | row lock ngắn + processing lease + external ID reconcile | Worker/request sau bỏ qua hoặc tiếp quản sau lease |
| Hai lần submit cùng ý định | Có hai Application | unique `(borrower_id,idempotency_key)` + request hash | Trả Application cũ nếu cùng payload; conflict nếu khác payload |
| Hai request ký/từ chối Contract | Contract có hai kết quả trái nhau | version + consent idempotency unique + state allowlist | Chỉ một transition commit |

Processing lease là quyền xử lý có thời hạn, không phải khóa database hai phút. Khi instance chết, worker
khác chỉ tiếp quản sau lease và phải reconcile theo external ID trước khi POST lại.

### 11.2. Command, worker và Kafka khác nhau thế nào?

- `FineractCommand` + worker xử lý việc nội bộ Loan gọi REST Fineract. Loan không cần tự phát Kafka rồi
  tự consume chỉ để làm cùng một việc.
- Outbox + Kafka dùng khi state đã commit phải đi qua service boundary, ví dụ Contract signed sang
  Investment hoặc fully-funded từ Investment về Loan.
- Kafka chỉ kích hoạt/đưa sự kiện; adapter/worker vẫn gọi Fineract bằng REST. Webhook là chiều Fineract
  chủ động gọi callback FINORA, hiện chưa phải cách Product sync đang dùng.

Các thao tác bắt buộc idempotent:

- submit Application từ một ý định người dùng;
- tạo Product Fineract;
- tạo scoring assessment;
- approve/reject;
- tạo và ký Contract;
- mở funding;
- giải ngân;
- tạo Fineract loan;
- ghi repayment.

## 12. Hiệu năng và tránh N+1

N+1 là tình trạng một query lấy danh sách rồi thêm một query cho từng dòng. Ví dụ 20 Application mà gọi
repository/Fineract 20 lần sẽ thành 21+ query/call; trang lớn hơn làm chi phí tăng tuyến tính.

| Use case/query thật | Index/chiến lược | Vì sao và đánh đổi |
|---|---|---|
| Catalog lọc `status`, sắp `created_at DESC,id DESC`, phân trang | `idx_loan_products_catalog(status,created_at,id)` + DTO mapping | Tránh đọc toàn bảng theo catalog; index tốn thêm chi phí khi Product đổi trạng thái |
| Lấy mapping lịch sử của Product theo thời gian | `idx_fineract_mappings_product(loan_product_id,created_at)` | Bám filter Product và sort timeline; không fetch mapping collection khi chỉ liệt kê Product |
| Worker lấy command theo từng trạng thái và thời điểm đến hạn | Ba partial index cho `PENDING`, `RETRY_PENDING`, `PROCESSING` mất lease | Mỗi “mục lục” chỉ chứa hàng của đúng trạng thái; cột đầu là thời gian worker so sánh, sau đó `id` để batch ổn định |
| Borrower xem Application của mình theo thời gian | `idx_loan_applications_borrower_created` | Khớp authorization filter + sort; luôn giới hạn page size |
| Admin xem hồ sơ `PENDING_REVIEW` | partial index `idx_loan_applications_pending_review` | Chỉ index hàng cần màn review, giảm kích thước so với index mọi status |
| Xem lịch sử Application/Contract | index `(parent_id,time DESC,id DESC)` | Tránh quét toàn bộ history và cho timeline ổn định |

- API danh sách dùng projection/DTO và phân trang; không serialize entity graph.
- Code hiện lưu scalar FK ID thay vì JPA collection, nên service phải query đúng dữ liệu cần và không gọi
  repository/integration theo từng dòng.
- Preview schedule là request đơn; cache chỉ xem xét sau khi đo tải và phải có key gồm Product version,
  amount, term, repayment method. Chưa đo thì không thêm cache để che dependency chậm.
- Unique constraint bảo vệ tính đúng business key/idempotency; index thường bảo vệ tốc độ đọc. Một unique
  index có thể làm cả hai, nhưng plan phải nói mục đích chính thay vì gọi mọi constraint là “tối ưu”.

## 13. Bảo mật, audit và quan sát

- Hiện dùng actor giả lập để phát triển Loan độc lập; Security/Keycloak thuộc task riêng và không chặn core domain.
- API quản trị và borrower vẫn phải đi qua một abstraction lấy actor; không đọc header tùy ý trong service implementation.
- Không log PII, access token, toàn bộ AI payload hoặc contract document ở mức INFO.
- Log có `traceId/correlationId`, application/product/contract ID và integration target.
- Audit business event cần biết ai làm, làm gì, khi nào, từ trạng thái nào sang trạng thái nào.
- Error response theo chuẩn chung; không lộ stack trace hoặc lỗi database ra client.
- Comment tiếng Việt đặt ở implementation cho quy tắc nghiệp vụ khó hiểu; interface mô tả contract ngắn gọn. Không viết comment lặp lại tên hàm.

## 14. Ranh giới với Apache Fineract

Fineract không thay Loan Service:

| Loan Service | Apache Fineract |
|---|---|
| Product hiển thị theo ngôn ngữ FINORA | Product engine tương ứng |
| Application, thẩm định, AI, admin decision | Không sở hữu |
| Contract và consent | Không sở hữu |
| Funding orchestration | Không sở hữu |
| Trạng thái khoản vay ở cấp trải nghiệm FINORA | Lịch trả, phân bổ, dư nợ, arrears |
| Snapshot/audit quyết định | Giao dịch core lending |

Loan và Fineract cùng dùng PostgreSQL nhưng ở hai Neon Project/database/credential độc lập. Hai hệ thống chỉ giao tiếp qua API; không join chéo database và không đồng bộ bằng truy vấn trực tiếp.

## 15. Bản đồ triển khai

| Task | Nội dung chi tiết | Trạng thái được theo dõi tại |
|---|---|---|
| LN-001 | Baseline MySQL lịch sử, đã bị thay thế | [LN-001](LN-001-loan-foundation.md) |
| LN-001A | PostgreSQL 17/Neon/Flyway/Testcontainers | [LN-001A](LN-001A-postgresql-neon-migration.md) |
| LN-002 | Security tạm, error, log, audit foundation | [LN-002](LN-002-security-error-observability.md) |
| LN-003 | Product min/base/max rate, repayment method, Fineract mapping | [LN-003](LN-003-loan-product.md) |
| LN-004 | Submit trực tiếp và snapshots | [LN-004](LN-004-loan-application.md) |
| LN-005 | Borrower profile/KYC provider | [LN-005](LN-005-borrower-profile-kyc.md) |
| LN-006 | Fineract product/schedule adapter | [LN-006](LN-006-fineract-product-schedule-integration.md) |
| LN-007 | AI v17 assessment, risk pricing, final schedule và automated decision | [LN-007](LN-007-credit-profile-ai-assessment.md) |
| LN-008 | Admin decision, Contract và ký | [LN-008](LN-008-approval-loan-contract.md) |
| LN-009 | Listing/outbox sang Investment | [LN-009](LN-009-market-listing-outbox.md) |
| LN-010 | Nhận kết quả fully funded | [LN-010](LN-010-fully-funded-consumer.md) |
| LN-011 | Fineract booking và Disbursement Saga | [LN-011](LN-011-disbursement-fineract-booking-saga.md) |
| LN-012 | Servicing projection và reconciliation | [LN-012](LN-012-fineract-servicing-reconciliation.md) |
| LN-013 | Repayment boundary và read schedule | [LN-013](LN-013-repayment-schedule-boundary.md) |
| LN-014 | Delinquency/default và credit profile update | [LN-014](LN-014-delinquency-credit-profile.md) |
| LN-015+ | Early settlement, restructuring, NPL, SmartCA và hardening | Tạo LN chi tiết trước khi code |

Trạng thái `BACKLOG/READY/IN_PROGRESS/DONE` chỉ cập nhật tại [`PLAN.md`](../PLAN.md). Mỗi LN chỉ được đánh `DONE` sau khi code, migration, test, tài liệu API và acceptance criteria đều đạt.

### 15.1. Cấu trúc package hiện hành

Loan vẫn dùng layered architecture, nhưng mỗi layer được chia tiếp theo nhóm nghiệp vụ để người đọc
không phải tìm Product, Application, Scoring và Core trong cùng một danh sách dài:

~~~text
com.finora.loan
├── domain/{product,application,scoring,core}
├── service/{product,application,scoring,core}
├── repository/{product,application,scoring,core}
├── dto/{product,application,scoring,core,common}
├── mapper/{product,application,scoring,core}
├── support                         # JSON canonical/hash dùng xuyên use case
└── integration
    ├── ai/{client,contract}
    ├── fineract/{client,contract,mapper}
    └── profile/{provider,contract}
~~~

Tên lớp và package vẫn thể hiện layer; subpackage chỉ giúp gom theo use case. Enum nằm cạnh aggregate
sử dụng nó, không gom vào một thư mục enums tổng. Request/response của HTTP Loan nằm trong dto,
còn request/response gửi hệ thống ngoài nằm trong integration/<dependency>/contract.

Các package chính có package-info.java mô tả trách nhiệm. Test phải phản chiếu package production.
Khi LN mới thêm file, phải đặt vào nhóm nghiệp vụ tương ứng, không đưa trở lại thư mục gốc của layer.

Ranh giới trách nhiệm đã được khóa thêm bằng architecture test:

- `domain` không phụ thuộc controller, DTO, repository, service hoặc integration contract. Entity schedule
  nhận dữ liệu nguyên thủy đã chuẩn hóa, không nhận `ScheduleCalculationResult` của Fineract.
- `mapper` chỉ chuyển đổi dữ liệu, không query repository hoặc điều phối use case. Nội dung công bố lãi suất
  lấy từ `LoanPricingDisclosureProperties`, không hard-code trong mapper.
- Fineract được tách thành `FineractProductHttpClient`, `FineractScheduleHttpClient` và
  `FineractRequestExecutor`; mỗi adapter chỉ chịu một nhóm endpoint, executor dùng chung resilience/error mapping.
- Mã Fineract cho `ANNUITY/EQUAL_PRINCIPAL`, dư nợ giảm dần và chu kỳ tính lãi được tập trung tại
  `FineractRepaymentPolicy`. `ANNUITY` V2–V4 dùng `PROGRESSIVE`/Advanced; `EQUAL_PRINCIPAL` dùng
  `CUMULATIVE`/Standard vì Fineract 1.15 vẫn tạo EMI gốc tăng dần nếu ghép `amortizationType=0` với
  `PROGRESSIVE`. V4 tính lãi theo ngày (`interestCalculationPeriodType=0`), V1–V3 theo kỳ trả nợ
  (`interestCalculationPeriodType=1`); mọi phiên bản giữ dư nợ giảm dần và không thêm Flat.
- Durable command Fineract dùng processing lease. Worker quét cả `PENDING`, retry đến hạn và
  `PROCESSING` mất lease để không mắc kẹt sau khi instance dừng giữa external call.
- Hash idempotency dùng JSON canonical; response Fineract thiếu field tiền/ngày bắt buộc bị coi là
  contract mismatch, không được tự chuyển thành `0`.

## 16. Các điểm cần Hải rà soát

Hải cần xác nhận các contract thuộc service mình sở hữu:

1. AI v17 dùng trực tiếp `installment` từ request và không tự tạo lịch trả nợ thay Fineract.
2. Fixture request/response `/explain`, grade, decision, explanation và model/policy version.
3. User profile/KYC contract và version để thay local provider.
4. Contract cung cấp `so_ccdd`/`so_cccd` đúng tên, mục đích và cơ chế bảo vệ dữ liệu; hiện Loan gửi null.
5. ID/correlation/idempotency convention dùng chung và event với Investment khi Contract đã ký.

Contract v17 hiện hành nằm tại [LN-007](LN-007-credit-profile-ai-assessment.md). File câu hỏi cũ chỉ còn giá trị lịch sử và không phải nguồn triển khai.

## 17. Điều kiện bắt đầu và hoàn thành một LN

Trước khi code:

- LN có scope, dependency, business flow, field/type/source/use.
- API/migration/index/transaction/failure đã được mô tả.
- Contract ngoài service đã được xác nhận hoặc đánh dấu rõ mock/chờ contract.
- Thái duyệt LN thuộc Loan; Hải duyệt phần contract service Hải sở hữu.

Trước khi đánh DONE:

- Migration chạy được trên database mới và database đã có dữ liệu mẫu phù hợp.
- Unit/integration/contract test đúng acceptance criteria.
- Không có N+1 trong các endpoint danh sách chính.
- Log/audit/error không rò dữ liệu nhạy cảm.
- `PLAN.md`, Design (nếu quyết định tổng thể thay đổi), LN và roadmap liên quan được cập nhật.
- Thái kiểm tra và xác nhận kết quả.
