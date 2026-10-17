---
task_id: LN-005
title: Borrower profile và KYC eligibility boundary
owner: Thai
status: ACCEPTED
roadmap_task: P1-A03
depends_on: [LN-004, USER_PROFILE_KYC_CONTRACT_OR_APPROVED_MOCK]
created_at: 2026-08-02
updated_at: 2026-09-05
approved_by: Thai
approved_at: 2026-08-03
accepted_by: Thai
accepted_at: 2026-08-08
---

# LN-005 — Borrower Profile và KYC eligibility

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

Trước khi chấm điểm tín dụng, Loan cần biết người nộp hồ sơ có đúng danh tính, đã hoàn tất KYC và đủ tuổi theo chính sách hay chưa. Các thông tin gốc thuộc User Service; Loan chỉ xin một kết quả tối thiểu và lưu bản chụp đã dùng cho lần đánh giá đó.

### Ai tham gia và nhìn thấy gì?

- Borrower không nhập tuổi hoặc tự khai “đã KYC” trong hồ sơ vay.
- User Service tương lai cung cấp ngày sinh/KYC từ hồ sơ người dùng.
- Loan tính tuổi tại ngày đánh giá và quyết định có cho hồ sơ đi tiếp tới AI hay không.
- Giai đoạn hiện tại dùng một provider giả lập age 30/KYC verified, có nhãn `MOCK` rõ để không bị hiểu là dữ liệu thật.

### Luồng chính

```text
Hồ sơ SUBMITTED → Loan hỏi BorrowerProfileProvider
    ├── KYC hợp lệ + đủ tuổi: lưu evidence → cho phép chấm điểm
    ├── KYC đang xử lý: giữ chờ và retry
    ├── KYC bị từ chối/hết hạn: không gửi AI
    └── User timeout: báo dependency lỗi, tuyệt đối không giả VERIFIED
```

### Dữ liệu quan trọng và ảnh hưởng

| Dữ liệu | Người đọc hiểu là gì | Nguồn | Ảnh hưởng nghiệp vụ | Vì sao lưu |
|---|---|---|---|---|
| `age` | Tuổi tại đúng ngày chấm điểm | Loan tính từ DOB; local tạm 30 | Kiểm tra điều kiện tuổi và gửi AI | Giải thích vì sao hồ sơ đủ/không đủ điều kiện |
| `kycStatus` | Danh tính đã được User xác minh chưa | User Service/mock | Chặn hoặc cho đi tiếp | Không chấm điểm một danh tính chưa hợp lệ |
| `kycReference/kycVersion` | Lần xác minh cụ thể đã dùng | User Service/mock | Audit và xử lý dữ liệu thay đổi | Quyết định lịch sử không bị đổi theo KYC mới |
| `incomeVerificationStatus` | Thu nhập đã có chứng từ hay chỉ tự khai | User/evidence | Ánh xạ AI và hiển thị admin | Không gọi dữ liệu tự khai là đã xác minh |
| `source/capturedAt` | Dữ liệu đến từ mock hay User, lấy lúc nào | Provider | Kiểm tra độ tin cậy/độ mới | Ngăn mock bị dùng nhầm ở production |

### Ví dụ, lỗi và điều kiện hoàn thành

Local: `BORROWER-001` nhận age 30, `VERIFIED`, source `MOCK_USER_PROFILE`; hồ sơ được đi tiếp nhưng assessment phải ghi nguồn mock. Production: User timeout thì hồ sơ ở trạng thái chờ/lỗi phụ thuộc, không gọi AI. LN hoàn tất khi có một boundary duy nhất, mock không thể chạy production, không lưu ảnh/CCCD/token và mọi kết quả đều có source/version/time.

### Đối chiếu pháp lý

- Áp dụng `LEGAL-DATA-01` và `LEGAL-AI-01` trong [Sổ đối chiếu pháp lý FINORA](../../docs/LEGAL-COMPLIANCE.md). DOB, trạng thái KYC, thu nhập và CCCD là dữ liệu có mục đích xử lý giới hạn; nguồn đối chiếu là [Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15](https://vanban.chinhphu.vn/?docid=214590&pageid=27160&typegroupid=3) và [Nghị định 356/2025/NĐ-CP](https://vanban.chinhphu.vn/default.aspx?docid=216387&pageid=27160).
- Loan chỉ lưu snapshot tối thiểu đã dùng cho eligibility, source/version/time và hash cần thiết để giải trình. Ảnh giấy tờ, token và số CCCD thô thuộc User/KYC boundary, không sao chép vào Loan.
- `so_cccd=null` là trạng thái contract hiện tại, không được tạo CCCD giả để gọi AI. Việc truyền định danh thật sau này cần contract bảo vệ dữ liệu, phân quyền và retention được duyệt.

## 1. Mục tiêu nghiệp vụ

Loan cần tuổi/profile/KYC để quyết định hồ sơ có được chấm điểm hay không, nhưng User Service chưa hoàn thành. Task tạo một integration boundary ổn định:

```text
Loan use case → BorrowerProfileProvider
                   ├── LocalMockBorrowerProfileProvider (hiện tại)
                   └── UserServiceBorrowerProfileProvider (tương lai)
```

Local dùng age 30 và KYC đủ điều kiện với source rõ. Khi Hải hoàn thành User Service, thay implementation chứ không sửa logic LN-007 hoặc rải HTTP call khắp service.

## 2. Phạm vi

- Tạo provider contract, result DTO nội bộ và eligibility policy.
- Local mock một chỗ duy nhất; không env/profile phức tạp để đổi user liên tục.
- Lưu identity/KYC/profile snapshot tối thiểu được dùng khi scoring.
- Định nghĩa remote contract tương lai, timeout/failure/cache policy.
- Test mock, eligibility và nguồn dữ liệu.
- Không sửa `finora-user` hoặc `finora-ai`.

## 3. Provider contract

```text
getBorrowerProfile(borrowerId, asOf)
    → BorrowerProfileResult
```

| Field result | Nguồn local | Nguồn thật tương lai | Dùng ở đâu |
|---|---|---|---|
| `borrowerId` | Current user mock | JWT/User logical ID | Ownership/correlation |
| `dateOfBirth` | null | User profile/eKYC | Tính age tại scoring |
| `age` | 30 | Loan derive từ DOB/asOf | AI + age/employment rule |
| `kycStatus` | `VERIFIED` mock | User SoR | Eligibility gate |
| `kycReference` | `MOCK-KYC-BORROWER-001` | User KYC ID | Audit |
| `kycVersion` | `MOCK-V1` | User contract version | Snapshot |
| `incomeVerificationStatus` | `NOT_VERIFIED` | User/evidence | AI verification status |
| `source` | `MOCK_USER_PROFILE` | `USER_SERVICE` | Không nhầm mock với thật |
| `capturedAt` | Clock | Response timestamp | Staleness/audit |

Tuổi không lấy từ body Application. Khi dùng DOB thật: `age = số năm tròn tại scoringAt`, timezone/business date được inject và test boundary sinh nhật.

## 4. Local mock bắt buộc

- Constant duy nhất: borrower `BORROWER-001`, age `30`, KYC `VERIFIED`.
- Bean chỉ active ở local/test; production startup phải fail hoặc không có mock bean.
- Không dùng header do client tự gửi để thay user/role.
- `source=MOCK_USER_PROFILE` phải đi vào Assessment input-source snapshot.

## 5. Eligibility policy

| Điều kiện | Kết quả |
|---|---|
| KYC VERIFIED, age trong range policy | `ELIGIBLE` |
| KYC PENDING/PROCESSING | `RETRY_PENDING` |
| KYC REJECTED/EXPIRED | `INELIGIBLE` với reason |
| Profile/User timeout | `DEPENDENCY_UNAVAILABLE`, không giả VERIFIED |
| Age thiếu ở production | `MANUAL_REVIEW/INVALID_PROFILE` theo policy |

LN-005 không tự approve/reject credit. Nó trả evidence/eligibility; LN-007 quyết định Application transition.

## 6. Snapshot persistence

Có thể dùng embedded snapshot trong Assessment hoặc bảng `borrower_eligibility_checks` khi cần nhiều attempt. Đích LN-005 đề xuất bảng riêng vì một Application có thể retry User/KYC:

| Field | PostgreSQL | Tác dụng |
|---|---|---|
| `id`, `application_id` | BIGINT | Liên kết |
| `request_id` | VARCHAR(100) unique | Idempotency/correlation |
| age/DOB-derived result | SMALLINT/DATE nullable | Evidence |
| KYC status/reference/version | varchar | Gate/source |
| income verification | varchar | AI mapper |
| source | varchar | MOCK/USER_SERVICE |
| result/reason | varchar | Eligibility |
| request/response hash | varchar | Audit không lưu PII thừa |
| checked_at, audit/version | datetime | Timeline/concurrency |

Không lưu ảnh giấy tờ, CCCD đầy đủ, token hoặc toàn bộ User response.

### 6.1 Ý nghĩa nghiệp vụ và quan hệ dữ liệu

`BorrowerEligibilityCheck` không phải hồ sơ KYC thứ hai. User Service vẫn là nơi sở hữu KYC; bảng này chỉ là **phiếu bằng chứng** ghi rằng Loan đã dùng trạng thái KYC/tuổi nào, từ nguồn nào và theo policy nào để cho phép một hồ sơ đi tiếp.

```text
User/Borrower (service khác)
        │ logical borrowerId, không FK
        ▼
LoanApplication (1) ──────── (N) BorrowerEligibilityCheck
                                      │
                                      └──────── (N) CreditScoringAssessment [LN-007]
```

- Một Application có thể có nhiều check vì User Service có thể timeout hoặc KYC đang `PENDING`, sau đó worker thử lại.
- Một check có thể được tham chiếu bởi assessment retry của cùng một lần đánh giá; LN-007 phải luôn lưu `eligibilityCheckId` đã dùng.
- `requestId` unique chặn cùng một lần kiểm tra bị ghi hai lần. Nó không có nghĩa một Application chỉ được kiểm tra một lần.
- Không cascade-delete evidence khi xóa dữ liệu thử nghiệm bằng nghiệp vụ; hồ sơ đã tham gia scoring cần được giữ để audit.

| Field | Nếu thiếu/sai thì ảnh hưởng gì? |
|---|---|
| `age`/DOB-derived result | Không được tự đoán ở production; dừng hoặc manual review theo policy |
| KYC status/reference/version | Không chứng minh được borrower đã qua bước định danh nào; không gọi AI |
| `source` | Không phân biệt mock với dữ liệu thật, làm bằng chứng chấm điểm mất giá trị |
| `result/reason` | Worker sau không biết đi tiếp, chờ lại hay dừng |
| request/response hash | Không đối chiếu được dữ liệu bằng chứng có bị thay đổi ngoài ý muốn |
| `policyVersion` | Khi cấu hình tuổi/KYC đổi, không giải thích được quyết định cũ |

## 7. Remote User contract dự kiến

- REST theo borrower logical ID; exact endpoint/JSON do Hải xác nhận.
- Loan không gọi User cho từng item danh sách; chỉ trong use case một hồ sơ hoặc batch contract.
- Connect/read timeout, circuit breaker; retry chỉ lỗi tạm thời.
- KYC response mang source/version/checkedAt.
- Không đọc User DB, không FK.

## 8. Transaction/failure

- Gọi provider **ngoài transaction Application** để không giữ DB connection/lock trong khi đợi mạng.
- Sau response, mở transaction ngắn lưu eligibility check và transition do state service thực hiện. `check + trạng thái Application + status history` phải cùng thành công hoặc cùng rollback.
- Duplicate request ID không tạo check lần hai; unique constraint là lớp bảo vệ cuối nếu hai worker cùng chạy.
- User timeout không đổi hồ sơ thành eligible và không gọi AI. Chỉ lỗi tạm thời mới được retry; KYC `REJECTED/EXPIRED` là kết quả nghiệp vụ, không retry mù.
- Response stale/version thiếu không được coi là verified production.

`Concurrency` là hai request/worker chạm cùng dữ liệu gần như cùng lúc:

| Tình huống | Cách xử lý |
|---|---|
| Hai worker cùng lấy Application `SUBMITTED` | state service khóa/kiểm tra version; chỉ một worker chuyển sang `ELIGIBILITY_PENDING` |
| Hai lần persist cùng `requestId` | unique constraint + đọc lại kết quả cũ, không tạo hai evidence |
| Profile trả về sau khi borrower đã withdraw | kiểm tra lại state trong transaction; không áp kết quả lên hồ sơ đã rút |
| User Service chậm/timeout | giải phóng transaction từ trước; đánh dấu retry/dependency failure có kiểm soát |

Worker hiện tại là scheduled worker nội bộ quét batch ID đến lượt xử lý. Đây không phải Kafka consumer và cũng không phải webhook; khi User contract thật xuất hiện chỉ thay provider adapter, không đổi ownership của orchestration.

## 9. File dự kiến

```text
integration/profile/BorrowerProfileProvider
integration/profile/LocalMockBorrowerProfileProvider
integration/profile/UserServiceBorrowerProfileProvider (chỉ khi contract READY)
service/BorrowerEligibilityService
domain/BorrowerEligibilityCheck
repository/BorrowerEligibilityCheckRepository
```

Provider là interface hợp lý vì có local mock và remote implementation.

## 10. Test/acceptance

- Local trả đúng age 30/source mock/KYC verified.
- Production không dùng mock.
- DOB boundary tính age đúng khi remote contract có.
- KYC pending/rejected/timeout không gọi AI.
- Duplicate check idempotent.
- Không log/persist PII thừa.
- Contract test User được thêm khi Hải cung cấp fixture.

## 11. Nghiệm thu

```text
Plan decision: ACCEPTED
Reviewer: Thai
Cross-service reviewer: Hai (khi dùng User contract thật)
Reviewed at: 2026-08-08
Notes: Local dùng mock age 30/KYC VERIFIED; User contract thật chưa nằm trong task này.
```

## 12. Hiện thực đã hoàn thành và thứ tự hàm được gọi

### Khi worker nhận một hồ sơ `SUBMITTED`

| Thứ tự | Hàm | Vai trò dễ hiểu |
|---:|---|---|
| 1 | `CreditScoringWorker.processDueScoring()` | Lấy một batch hồ sơ đến lượt xử lý; không quét vô hạn và không gọi theo từng dòng của API danh sách. |
| 2 | `CreditScoringOrchestrator.processApplication(applicationId)` | Điều phối toàn bộ một lần eligibility rồi mới cho phép chấm AI. |
| 3 | `CreditScoringStateService.startEligibility()` | Khóa hồ sơ và đổi `SUBMITTED → ELIGIBILITY_PENDING` trong transaction ngắn. |
| 4 | `BorrowerEligibilityService.evaluate()` | Gọi provider ngoài transaction, đánh giá policy và tạo hash bằng chứng. |
| 5 | `LocalMockBorrowerProfileProvider.getBorrowerProfile()` | Ở profile `local`, trả borrower hiện tại với age 30, KYC `VERIFIED`, nguồn `MOCK_USER_PROFILE`. |
| 6 | `BorrowerEligibilityPolicy.evaluate()` | Kiểm tra KYC và tuổi theo cấu hình; không quyết định duyệt tín dụng. |
| 7 | `BorrowerEligibilityStateService.persist()` | Lưu `borrower_eligibility_checks` để lần sau biết chính xác dữ liệu nào đã được dùng. |
| 8 | `CreditScoringStateService.applyEligibility()` | Eligible thì chuẩn bị LN-007; không hợp lệ thì dừng hoặc chuyển hàng chờ thủ công theo policy. |

External provider không được gọi khi đang giữ transaction/DB lock. Mock chỉ có bean khi
`finora.borrower-profile.provider=mock`, hiện được đặt trong `application-local.yml`; môi trường
không khai báo provider thật sẽ fail startup thay vì âm thầm dùng danh tính giả.

### File code chính

- `integration/profile/*`: boundary và provider mock, sau này thay bằng User Service adapter.
- `service/BorrowerEligibilityService`: use case lấy profile và đánh giá.
- `service/BorrowerEligibilityPolicy`: quy tắc tuổi/KYC thuần, có unit test.
- `service/BorrowerEligibilityStateService`: transaction lưu evidence.
- `domain/BorrowerEligibilityCheck`: snapshot đã dùng cho đúng lần xử lý.
- `repository/BorrowerEligibilityCheckRepository`: truy vấn theo request/application, không đọc DB User.

### Vì sao phải lưu source, version và hash?

| Dữ liệu | Hàm tạo/lưu | Hệ thống dùng để làm gì |
|---|---|---|
| `requestId` | `BorrowerEligibilityService.evaluate()` | Correlation cho đúng một lần lấy profile; không dùng borrower ID làm mã request ngoài hệ thống |
| `requestHash` | `HashingService.sha256(EligibilityProfileRequest)` | Ghi dấu application, borrower và ngày hiệu lực đã được hỏi ở lần kiểm tra đó |
| `responseHash` | `HashingService.sha256(BorrowerProfileResult)` | Phát hiện evidence profile bị đổi so với dữ liệu đã dùng để quyết định eligibility |
| `profileSource`, `kycVersion` | Provider trả và `BorrowerEligibilityStateService.persist()` lưu | Phân biệt mock/User Service và biết chính xác phiên bản KYC đã được tham chiếu |
| `policyVersion` | `BorrowerEligibilityProperties.policyVersion` | Tái hiện bộ quy tắc tuổi/KYC đã đưa ra kết luận; policy đổi sau này không viết lại lịch sử |

Hash được tạo từ JSON canonical để cùng nội dung cho cùng kết quả bất kể thứ tự field/key. Hash không
thay thế snapshot/source/version, không phải mã hóa và không cho phép Loan nhận quyền sở hữu trạng thái
KYC của User Service.

### Failure và retry của profile provider

Hiện tại profile `local` dùng `LocalMockBorrowerProfileProvider`, nên chưa có HTTP timeout/circuit breaker
với User Service. Nếu provider ném lỗi, transaction `ELIGIBILITY_PENDING` đã commit; worker bắt lỗi và
hồ sơ vẫn được `findApplicationCandidates()` lấy lại ở chu kỳ sau. Không có Application giả được duyệt
và không gọi AI khi chưa có eligibility evidence hợp lệ. Nhánh provider hiện chưa có bộ đếm attempt,
backoff hoặc trạng thái terminal riêng; điều này chấp nhận được cho mock local nhưng chưa đủ để bật remote
User adapter trong môi trường triển khai.

Khi Hải cung cấp User contract thật, adapter mới phải chịu trách nhiệm timeout, phân loại lỗi tạm thời và
không retry lỗi contract/authorization. Khi đó LN-005 phải bổ sung đúng tên client, error code, giới hạn
attempt/backoff và trạng thái terminal theo implementation thật; không ghi trước tên hàm chưa tồn tại.

### Trạng thái nghiệm thu hiện tại

- Hoàn thành cho luồng local và integration test.
- Chưa thay mock bằng User Service thật; khi Hải cung cấp contract chỉ thêm adapter, không sửa
  orchestration và không đọc chéo database.
