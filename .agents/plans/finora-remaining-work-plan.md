---
task_id: FINORA-REMAINING
title: Hoàn thiện FINORA sau vertical slice giải ngân và servicing
owner: Both
status: ACTIVE
depends_on: [P5, P6]
created_at: 2026-10-16
updated_at: 2026-10-17
---

# FINORA-REMAINING — Kế hoạch chi tiết phần việc còn lại

## Bản đọc nhanh theo nghiệp vụ

### Hệ thống đã làm được gì?

FINORA đã có vertical slice thật cho đăng nhập/eKYC, nộp và chấm điểm hồ sơ, duyệt, gọi vốn,
giữ tiền, hợp đồng nhiều bên, SmartCA UAT, giải ngân, tạo Note, trả nợ, trả trước, tất toán,
quá hạn/default/cure và yêu cầu cơ cấu có nhà đầu tư biểu quyết. Web quản trị và mobile mới đã
kết nối các API chính; backend không còn là skeleton.

### Còn thiếu gì để demo ổn định?

Ưu tiên không phải thêm một luồng nghiệp vụ lớn, mà là biến luồng hiện có thành bộ demo có thể
lặp lại: dựng môi trường bằng một quy trình, tạo dữ liệu sạch, chạy từ đầu đến một kỳ trả nợ,
chứng minh retry/restart không làm trùng tiền, sửa projection/Note khi event đến trễ và có màn hình
đối soát rõ ràng.

### Còn thiếu gì nếu tiến gần production?

- Cổng tiền ra/rút tiền thật; hiện top-up có mock/ZaloPay sandbox nhưng disbursement ZaloPay chưa chạy thật.
- Chữ ký PAdES nhúng vào PDF; hiện SmartCA UAT ký hash và lưu bằng chứng rời.
- Fabric adapter/network/chaincode thật và proof cho nhiều mốc ngoài repayment.
- Push notification thật, RBAC/PII/security hardening, observability và deployment hoàn chỉnh.
- Model governance, fraud nâng cao, secondary market/auto-invest hardening.

### Điều đã chốt và không đưa lại vào backlog

- CIC là service Node deploy tại `https://cic-service-node.vercel.app/`; FINORA chỉ cấu hình URL.
- Mobile phải dùng bộ UI mới, không phục hồi màn mock/UI cũ.
- Fineract là nguồn chuẩn lịch trả, nghĩa vụ và breakdown; frontend không tự tính lại tiền.
- Payment ledger là nguồn chuẩn dịch chuyển tiền nội bộ; Investment là nguồn chuẩn Note/ownership.
- Một PDF hợp đồng dùng chung cho các bên; lender ký trước, borrower ký sau.

## 1. Current state và phạm vi

### 1.1 Nguồn sự thật

| Phạm vi | Nguồn sự thật | Không được làm |
|---|---|---|
| Identity/eKYC | User + Keycloak; CIC qua REST ngoài Compose | Không đọc DB User/CIC chéo |
| Hồ sơ, hợp đồng, servicing | Loan + Fineract projection | Không tính lịch/lãi/phí ở web/mobile |
| Tiền, hold, thu/chi | Payment ledger/provider reference | Không sửa số dư trực tiếp |
| Listing, allocation, Note | Investment | Không suy luận ownership từ Loan |
| Bằng chứng | Blockchain hash proof | Không lưu PII/PDF lên chain |
| Thông báo | Notification delivery projection | Không để lỗi thông báo rollback tiền |

### 1.2 Ngoài phạm vi của plan tổng hợp

Plan này không tự thêm entity/API/event. Mỗi workstream khi bắt đầu phải có task plan riêng theo
`09-planning-documentation.md`, mô tả field, ERD/cardinality, query/index, transaction, concurrency,
idempotency và acceptance evidence. CIC Node được xem là dependency bên ngoài, không triển khai lại.

## 2. Thứ tự triển khai bắt buộc

```text
R0 Đồng bộ source và tài liệu
 → R1 E2E demo lặp lại được
 → R2 Saga/Fineract/financial recovery
 → R3 Investment projection và reconciliation
 → R4 Hoàn thiện tiền ngoài hệ thống
 → R5 Chữ ký PDF và blockchain proof
 → R6 Notification, security, observability, deployment
 → R7 AI/secondary market/auto-invest nâng cao
```

R1–R3 là cổng trước khi mở rộng R4–R7. Không gọi một tính năng là hoàn tất chỉ vì UI trả `200`;
phải kiểm tra state tại đúng SoR, ledger/event và khả năng retry.

## 3. Danh mục tồn đọng

| ID | Ưu tiên | Owner | Phạm vi | Trạng thái | Kết quả cần đạt |
|---|---|---|---|---|---|
| R0 | P0 | Both | Source/docs | `DONE` | Backend, web, mobile cùng mốc code; roadmap/rule khớp code |
| R1 | P0 | Both | E2E | `READY` | Một script/checklist chạy sạch từ đăng ký đến một kỳ trả nợ |
| R2 | P0 | Thai | Loan/Payment/Fineract | `READY` | Restart/timeout/duplicate không giải ngân hoặc thu tiền hai lần |
| R3 | P0 | Hai | Investment | `READY` | Order–hold, Note activation, ownership và portfolio tự repair |
| R4 | P1 | Thai | Payment | `BACKLOG` | Rút tiền và provider disbursement sandbox chạy thật, đối soát được |
| R5 | P1 | Thai + Hai | Loan/Investment/SmartCA | `BACKLOG` | PDF có chữ ký nhúng của nhiều bên hoặc quyết định giữ receipt rời được duyệt |
| R6 | P1 | Thai | Blockchain | `BACKLOG` | Fabric thật + proof/query/reconcile cho các mốc đã duyệt |
| R7 | P1 | Hai | Notification | `IN_PROGRESS` | Template đầy đủ và push thật; lỗi provider không làm sai nghiệp vụ |
| R8 | P1 | Both | Security | `BACKLOG` | RBAC/resource auth, PII/secret/log và abuse controls được kiểm chứng |
| R9 | P1 | Both | Ops | `BACKLOG` | Health, metrics, alert, replay/DLT và deployment tái lập được |
| R10 | P2 | Hai | AI | `BACKLOG` | Backtest/champion-challenger, fraud và early warning có metric |
| R11 | P2 | Hai + Thai | Investment/Payment | `IN_PROGRESS` | Secondary market/auto-invest qua concurrency/reconciliation E2E |

## 4. R0 — Đồng bộ source, roadmap và tài liệu

### Việc làm

1. Commit/push độc lập ba repository backend, web và mobile; không đưa secret hoặc `.env` thật vào Git.
2. Web/mobile giữ các thay đổi servicing hiện hành: admin thẩm định cơ cấu, investor consent,
   quote trả trước/tất toán và nhãn input tiếng Việt rõ nghĩa.
3. Compose bỏ CIC Java cũ; `CIC_SERVICE_URL` mặc định là endpoint Node đã deploy.
4. Cập nhật `01-project-context.md`, roadmap và link plan này trong cùng commit backend.
5. Chạy validator rules, Docker Compose config, Java verify, web build và mobile TypeScript.

### Acceptance

- Ba repo sạch sau push và `HEAD` đã có trên `origin/main`.
- Không còn `../../cic-service`, `http://cic:9000` hoặc mô tả backend là skeleton trong cấu hình chính.
- Skill `finora-engineering` vẫn pass validator; không thay đổi rule nếu rule đang đúng.

R0 hoàn tất ngày 2026-10-17 tại các commit backend `c1ca1ba`, web `ac4ad2a` và mobile
`531f5ee`; commit tài liệu xác nhận cuối có thể nằm sau SHA backend nêu trên.

## 5. R1 — Bộ E2E vertical slice lặp lại được

### Luồng demo chuẩn

```text
Borrower đăng nhập/eKYC
 → tạo hồ sơ → AI chấm → admin duyệt
 → borrower chấp nhận điều khoản → listing mở
 → investor nạp mock/ZaloPay sandbox → đặt lệnh/hold
 → đủ vốn → lender ký → borrower ký
 → saga giải ngân → borrower nhận tiền → Note active
 → borrower trả một kỳ → investor nhận phân bổ
 → Loan/Investment/Blockchain/Notification cập nhật projection
```

### Dữ liệu và bằng chứng bắt buộc

| Bước | Evidence dễ hiểu | Evidence kỹ thuật |
|---|---|---|
| Hold | Số dư khả dụng giảm, số tiền đang giữ tăng | wallet/hold/ledger cùng reference |
| Ký | Cả hai bên thấy cùng hợp đồng và trạng thái | document hash + signer evidence |
| Giải ngân | Borrower tăng số dư, investor bị capture | balanced ledger + saga step + provider ref |
| Note | Investor thấy khoản đầu tư đang hiệu lực | Note/ownership snapshot/version |
| Trả kỳ | Borrower giảm tiền, investor nhận phần của mình | Fineract transaction + distribution ledger/outbox |

### Ca lỗi tối thiểu

- Gửi lặp cùng idempotency key.
- Kafka delivery trùng hoặc đến trễ.
- Dừng service giữa saga rồi bật lại.
- Fineract timeout sau khi có thể đã xử lý.
- Investment/Notification/Blockchain tạm tắt rồi bật lại.

### Acceptance

- Có script hoặc checklist tạo dữ liệu sạch và kết quả mong đợi theo từng SoR.
- Chạy lại không cần sửa DB tay; duplicate không tăng/giảm tiền lần hai.
- Có trace/saga/event ID để lần theo toàn luồng.

## 6. R2 — Saga, Fineract và financial recovery

### Mục tiêu nghiệp vụ

Nếu mạng lỗi sau khi tiền/core đã xử lý, hệ thống phải hỏi lại nguồn chuẩn và tiếp tục đúng bước,
không POST mù, không chuyển `FAILED` khi kết quả thực tế còn chưa biết.

### Việc còn lại

- Test restart tại từng bước disbursement và repayment với Fineract thật.
- Phân biệt `FAILED` với `UNKNOWN_OUTCOME`; unknown phải reconcile bằng external reference.
- Chứng minh capture, borrower credit, core booking và event publish không bị lặp.
- Hoàn thiện màn/admin action cho incident thật sự cần con người quyết định.
- Chốt auto-debit chỉ sau khi manual repayment/reconciliation đạt phase gate.

### Transaction/concurrency

```text
Transaction A: khóa aggregate + lưu durable command/outbox
Ngoài transaction: gọi Payment/Fineract/provider
Transaction B: đối chiếu external reference + ghi kết quả + outbox tiếp theo
```

Mỗi request có business idempotency key và external reference ổn định. Không giữ DB lock trong lúc
gọi HTTP. Worker dùng processing lease; hết lease phải reconcile trước khi tiếp quản.

### Acceptance

- Testcontainers/integration và smoke Fineract thật cho duplicate, timeout, restart đều xanh.
- Ledger cân bằng và principal/schedule không lệch sau mọi ca lỗi.
- `LEGAL-PAYMENT-01` và `LEGAL-SERVICING-01` được dẫn trong task triển khai.

## 7. R3 — Investment reconciliation và projection rebuild

### Việc còn lại

1. Reconcile `InvestmentOrder` ↔ Payment hold/capture/release bằng reference, không sửa Payment DB.
2. Repair Note activation khi `LoanDisbursed` đến trễ/trùng hoặc consumer restart.
3. Khóa ownership snapshot/version tại thời điểm phân bổ; test transfer cạnh repayment.
4. Rebuild portfolio/cashflow từ event/Note state thay vì sửa số hiển thị bằng tay.
5. Hoàn tất consumer thật của `RepaymentDistributed.v1` và servicing events cho cơ cấu/default/cure.

### Query/index cần chứng minh trong task con

- Queue theo `status + next_retry_at + id`, batch hữu hạn.
- Lookup unique theo `source_event_id`, `payment_hold_reference`, `loan_id/note_id/version`.
- Portfolio phân trang; không REST call theo từng Note và không N+1.

### Acceptance

- Tắt Investment trong lúc giải ngân/trả nợ rồi bật lại vẫn hội tụ đúng.
- Rebuild cho ra tổng principal/interest/cashflow bằng ledger/event nguồn.
- Một event trùng không tạo Note, ownership hoặc portfolio entry thứ hai.

## 8. R4 — Tiền ngoài FINORA

### Current state

- Có mock top-up và ZaloPay sandbox create/callback HMAC.
- Có wallet/ledger/hold/capture/transfer nội bộ.
- ZaloPay disbursement hiện fail-closed; withdrawal/link bank/VietQR chưa có.

### Việc còn lại

- Chọn rõ phạm vi demo: ZaloPay sandbox top-up + mock payout, hoặc provider payout sandbox thật.
- Thêm withdrawal/payout request, provider reference, callback/query status và reconciliation.
- Không cộng/trừ ví chỉ dựa trên redirect mobile; callback phải xác thực chữ ký và idempotent.
- Batch/admin reconciliation: provider amount/status ↔ Payment transaction/ledger.
- Công bố rõ tiền sandbox là giả và không mô tả FINORA đang giữ tiền thật.

### Acceptance

- Callback trùng không cộng tiền hai lần; callback giả/sai HMAC bị từ chối.
- Timeout query lại provider bằng reference; kết quả unknown không tự retry chuyển tiền mù.
- Tổng provider settlement khớp ledger; mismatch vào queue đối soát.
- Tuân `LEGAL-PAYMENT-01` và phải legal review trước mọi môi trường tiền thật.

## 9. R5 — Chữ ký PDF nhiều bên

### Current state

SmartCA UAT tạo giao dịch ký hash, poll `signature_value` và lưu receipt/evidence. PDF signable giữ
nguyên bytes; trạng thái “đã ký” hiện được chứng minh bằng hash + receipt, chưa phải PDF PAdES nhúng.

### Hai hướng phải chốt

1. **Demo receipt rời:** giữ nguyên, UI/PDF cover công bố rõ bằng chứng tách rời.
2. **PAdES:** dùng SDK/contract chính thức để nhúng chữ ký theo incremental update; lender ký trước,
   mỗi lender tiếp theo ký bản mới, borrower ký cuối và hash/version phải theo chuỗi tài liệu.

### Acceptance nếu chọn PAdES

- PDF mở bằng trình đọc chuẩn thấy chữ ký/chứng thư và kiểm tra được integrity.
- Nhiều chữ ký không làm hỏng chữ ký trước.
- Retry cùng signer/document version không tạo hai chữ ký.
- Contract state chỉ active khi signer bắt buộc hoàn tất; `LEGAL-CONTRACT-01` được review.

## 10. R6 — Blockchain proof và Fabric

### Current state

Đã có proof store hash-only, idempotency, lease/retry/DLT, mock receipt và repayment consumer.
Fabric adapter thật cố ý fail-closed; contract/disbursement consumer và query/explorer chưa có.

### Mốc đề xuất neo hash

| Mốc | Payload tối thiểu | Không đưa lên chain |
|---|---|---|
| Hợp đồng ký đủ | document hash, contract/version, signer evidence hash | PDF, CCCD, tên/địa chỉ |
| Giải ngân | loan/saga/provider reference hash, amount hash | tài khoản ngân hàng |
| Repayment | source event, core transaction, distribution hash | chi tiết PII |
| Note | issuance/transfer/version hash | hồ sơ người vay |
| Servicing | settlement/reschedule/default decision hash | lý do nhạy cảm dạng thô |

### Việc còn lại

- Duyệt event contract từng mốc; thêm consumer idempotent.
- Dựng Fabric network/chaincode/identity, submit/query adapter và receipt thật.
- Explorer/query theo business reference và integrity reconciliation DB ↔ chain.
- Fabric outage chỉ retry/DLT proof, không rollback tiền/hợp đồng đã commit.

### Acceptance

- Hash đối chiếu lại được từ artifact nguồn; mock và Fabric receipt không bị nhầm.
- Duplicate/restart không tạo proof logic thứ hai.
- `LEGAL-DATA-01` và `LEGAL-CONTRACT-01` được kiểm tra trước rollout.

## 11. R7 — Notification hoàn chỉnh

### Current state

In-app delivery, unread/read và một số template servicing đã có. Chưa có vòng đời device token,
preference, provider push/SMS/email đầy đủ và bounded retry/DLT cho mọi sự kiện.

### Việc còn lại

- Ma trận actor × event: funding, ký, giải ngân, kỳ đến hạn, paid, overdue transition,
  reschedule request/decision, settlement, default/cure và reconciliation cần can thiệp.
- Chỉ gửi quá hạn khi chuyển stage/episode, không spam mỗi ngày.
- Push provider adapter, token lifecycle, opt-in/preference và redaction nội dung màn khóa.
- Dedup theo source event + recipient + channel; delivery lỗi không rollback nghiệp vụ.

## 12. R8 — Security, privacy và audit

- Chốt JWT claim/role/resource authorization xuyên Gateway và service; test borrower/investor/admin.
- Admin RBAC chi tiết cho duyệt hồ sơ, servicing, reconcile và replay.
- Không log token, secret, CCCD, document URL, signature value hoặc provider payload nhạy cảm.
- Rotate secret, tách dev/demo/prod; quét repository và image trước release.
- CORS, rate limit, brute-force protection và audit append-only cho hành động nhạy cảm.
- Review retention/access/export/delete theo `LEGAL-DATA-01`.

Acceptance: negative authorization tests, secret scan và PII/log review có evidence; người dùng không
đọc/sửa tài nguyên của người khác chỉ bằng cách đổi ID.

## 13. R9 — Observability, vận hành và deployment

- Một lệnh dựng dependency cần thiết; health/readiness không chỉ kiểm tra mở cổng.
- Trace ID đi qua HTTP, outbox/Kafka, saga và provider; dashboard cho DLT, outbox lag,
  saga stuck, reconciliation incident và wallet invariant.
- Runbook cho restart, replay, manual reconcile và rollback migration tương thích.
- Backup/restore test cho từng database; không dùng chung credential giữa service.
- CI chạy validator rules, Java verify, web build, mobile typecheck và contract tests.

## 14. R10/R11 — Phần mở rộng sau phase gate

### AI

- Backtest/champion-challenger và model drift có dataset/version/metric.
- Fraud mở rộng bằng payment behavior contract; document forgery chỉ khi có dữ liệu hợp pháp.
- Early warning là tín hiệu hỗ trợ, không tự động xử phạt người vay không có policy duyệt.

### Investment

- Auto-invest: concurrency, on-behalf authorization, hold/release và giới hạn rủi ro.
- Secondary market: settlement/ownership atomicity, self-trade prevention, cancel race,
  SSE reconnect và reconciliation Payment ↔ Note transfer.
- Đây không phải blocker cho demo primary-market repayment.

## 15. Bản đồ dữ liệu hiện hành ở mức service

Plan này không thêm bảng. Quan hệ logic cần giữ khi viết task con:

```text
User/Keycloak identity
        │ borrowerId/investorId (logical ID, không FK chéo)
        ▼
LoanApplication 1 ── 0..1 LoanContract ── 1..N ContractSignerEvidence
        │ loanId/event                              │ document hash
        ▼                                           ▼
InvestmentListing 1 ── 1..N Commitment ── 1..N Note/OwnershipVersion
        │ hold/payment reference                    │ ownership snapshot
        ▼                                           ▼
PaymentTransaction 1 ── 2..N LedgerEntry      RepaymentDistribution
        │ core/provider reference                   │ event reference
        ▼                                           ▼
Fineract loan/transaction                    BlockchainProof/NotificationDelivery
```

Các mũi tên chéo service là quan hệ bằng external/business ID và event, không phải foreign key và
không cho phép join database chéo.

## 16. Test matrix tổng hợp

| Nhóm | Happy | Validation | Duplicate | Concurrency | Timeout/restart | Reconcile |
|---|---:|---:|---:|---:|---:|---:|
| Application/scoring | Có | Có | Có | — | AI timeout | Có |
| Funding/hold | Có | Có | Có | Bắt buộc | Payment timeout | Bắt buộc |
| Contract/signing | Có | Có | Có | signer song song | SmartCA poll/restart | Bắt buộc |
| Disbursement | Có | Có | Bắt buộc | Bắt buộc | Bắt buộc | Bắt buộc |
| Repayment/prepayment | Có | Có | Bắt buộc | Bắt buộc | Bắt buộc | Bắt buộc |
| Reschedule/default | Có | Có | Có | consent race | event trễ | Bắt buộc |
| Blockchain/notification | Có | Có | Bắt buộc | worker lease | provider outage | Bắt buộc |

## 17. Definition of Done cuối hệ thống

- R1–R3 đạt trước khi gọi luồng core là ổn định.
- Không có chỉnh DB tay trong kịch bản demo chuẩn.
- Mọi thay đổi tiền cân bằng và truy được bằng business/external reference.
- Fineract, Payment, Investment và Loan hội tụ cùng một trạng thái sau restart/event trễ.
- Web/mobile dùng API thật, có loading/empty/error/success và không hiển thị terminal giả.
- Tài liệu, event catalog, env example và UI test guide khớp code cùng commit.
- Các hạng mục production chưa làm được ghi rõ là mock/sandbox/backlog, không mô tả quá khả năng.

## 18. Nghiệm thu plan

Plan ở trạng thái `DRAFT` cho tới khi Thái/Hải chốt owner và thứ tự R1–R11. Khi bắt đầu workstream,
tạo task plan cụ thể và liên kết vào roadmap; khi hoàn tất, ghi command/test evidence và commit SHA
thay vì chỉ đổi trạng thái bằng mô tả.
