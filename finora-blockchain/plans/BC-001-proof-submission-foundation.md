---
document_type: TASK_PLAN
task_id: BC-001
owner: Thai
reviewers:
  - Hai
status: READY_FOR_REVIEW
approved_by: Thai
approved_at: 2026-09-21
updated_at: 2026-09-21
---

# BC-001 — Nền lưu và xử lý bằng chứng hash

## Bản đọc nhanh

Blockchain Service cần nhận một yêu cầu ghi bằng chứng, giữ yêu cầu đó bền vững rồi thử gửi đến ledger mà không làm rollback nghiệp vụ Loan/Payment đã hoàn tất. BC-001 xây phần độc lập trước khi Hải chốt event Investment: chỉ lưu business ID, SHA-256 và version; không lưu PDF, CCCD, hồ sơ AI hoặc payload gốc.

Local/test dùng `MOCK`. Receipt luôn có tiền tố `MOCK-` và block reference `MOCK-BLOCK-NOT-ON-CHAIN`, nên UI/báo cáo không thể mô tả nhầm là đã ghi Hyperledger. Nếu operator chọn `HYPERLEDGER_FABRIC` khi adapter thật chưa sẵn sàng, hệ thống fail-closed thay vì tạo giao dịch giả.

## 1. Phạm vi và ngoài phạm vi

Đã làm:

- Đăng ký proof idempotent theo `source_service + source_event_id`.
- Queue trạng thái `PENDING → PROCESSING → CONFIRMED/DEAD_LETTER`.
- Claim token, processing lease, exponential backoff và giới hạn số lần thử.
- Provider port, mock xác định và Fabric placeholder fail-closed.
- Worker opt-in; mặc định tắt.
- Flyway PostgreSQL 17, constraint/index và test integration.

Chưa làm:

- Không tạo Kafka listener/topic/event schema khi Loan–Investment contract chưa chốt.
- Không kết nối Fabric Gateway, không submit/query chaincode thật.
- Không cung cấp public API; entry point hiện là application service nội bộ để contract sau gắn vào.
- Không canonicalize payload gốc trong service này. Producer phải tạo hash theo schema/version đã duyệt.

## 2. Dữ liệu và quan hệ

```text
Source domain event (logical 1)
        │ source_service + source_event_id (unique)
        ▼
BlockchainProofSubmission (1)
        │ 0..N processing attempts được thể hiện bằng attempt_count/lease
        ▼
Provider receipt (0..1, chỉ có khi CONFIRMED)
```

Chỉ có một bảng vì attempt hiện không cần audit từng lần riêng; trạng thái cuối, số lần thử và lỗi cuối đủ cho worker/reconcile MVP. Nếu production yêu cầu lịch sử từng attempt, thêm bảng append-only bằng migration mới.

| Field | Ý nghĩa | Ràng buộc |
|---|---|---|
| `proof_id` | Public ID của bằng chứng | UUID unique |
| `source_service`, `source_event_id` | Nguồn tạo yêu cầu và khóa chống trùng | composite unique |
| `aggregate_type`, `aggregate_id` | Business record cần đối chiếu | không phải FK chéo DB |
| `proof_type` | Contract/disbursement/repayment | enum/check constraint |
| `payload_hash`, `payload_version` | Nội dung đã canonicalize ở producer | lowercase SHA-256 64 hex, version dương |
| `provider` | MOCK hay Fabric | bất biến sau khi tạo |
| `status`, `attempt_count`, `available_at` | Điều phối retry | check + partial index |
| `processing_token`, `processing_started_at` | Lease chống worker cũ ghi đè | nullable ngoài PROCESSING |
| `provider_transaction_id`, `provider_block_reference` | Receipt ledger | transaction unique khi có |
| `last_error_*` | Chẩn đoán đã giới hạn độ dài, không chứa payload | tối đa 60/500 ký tự |

## 3. Transaction, concurrency và failure

- Register dùng PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`, rồi đọc record theo source event. Hai request đồng thời không tạo hai proof; cùng key nhưng khác hash/version trả `PROOF_SOURCE_EVENT_CONFLICT`.
- `claim`, `confirm`, `fail` khóa pessimistic đúng một row và commit nhanh.
- Lời gọi `ProofLedger.submit` nằm **ngoài** transaction DB.
- Claim token ngăn worker cũ confirm sau khi lease đã được node khác tiếp quản.
- Lỗi retryable quay về `PENDING` với backoff có trần; lỗi permanent hoặc hết attempt vào `DEAD_LETTER`.
- Fabric được chọn trước khi adapter hoàn chỉnh trả permanent `FABRIC_INTEGRATION_NOT_READY`; không fallback sang mock.

## 4. Query và index

- `uq(source_service, source_event_id)` phục vụ idempotent register.
- Partial index `(available_at, id) WHERE status='PENDING'` phục vụ batch due.
- Partial index `(processing_started_at, id) WHERE status='PROCESSING'` phục vụ reclaim lease.
- `(aggregate_type, aggregate_id, created_at, id)` phục vụ query/reconcile theo aggregate sau này.
- Unique `(provider, provider_transaction_id)` khi receipt tồn tại chống lưu một giao dịch provider cho hai proof.

Worker lấy danh sách ID có giới hạn `batchSize`, sau đó xử lý từng ID; không load payload lớn và không có N+1 trên màn hình vì BC-001 chưa có query UI.

## 5. Cấu hình an toàn

- `BLOCKCHAIN_PROOF_PROVIDER=MOCK` mặc định.
- `BLOCKCHAIN_PROOF_WORKER_ENABLED=false` mặc định.
- Kafka listener mặc định không auto-start; deserializer là string, đã bỏ wildcard trusted packages.
- Chỉ bật worker mock khi chạy test/demo có chủ đích. Production không được bật mock.

## 6. Bản đồ code

```text
ProofSubmissionService.register
  → ProofSubmission.register (validate invariant)
  → ProofSubmissionRepository.insertIfAbsent/findBySource...

ProofSubmissionWorker
  → ProofSubmissionStateService.claim (transaction + row lock)
  → ProofSubmissionProcessor
  → ProofLedger.submit (ngoài transaction)
  → ProofSubmissionStateService.markConfirmed/markFailed (transaction mới)
```

Migration: `V1__create_proof_submissions.sql`.

## 7. Pháp lý và dữ liệu

Áp dụng `LEGAL-CONTRACT-01` và `LEGAL-DATA-01` trong [sổ đối chiếu pháp lý](../../docs/LEGAL-COMPLIANCE.md): proof kỹ thuật hỗ trợ kiểm tra toàn vẹn, không tự làm click-wrap thành chữ ký số và không thay thế legal review. Ledger chỉ nhận hash/version/business reference; raw contract và PII ở đúng service nguồn với phân quyền/retention riêng.

## 8. Kiểm thử và bằng chứng

- Unit: validation hash, claim token, retry/dead-letter, config, deterministic mock, processor success/failure.
- Integration PostgreSQL 17.5: migration sạch, Hibernate validate, duplicate idempotency/conflict, mock confirmation.
- Lệnh: `mvn -pl finora-blockchain -am verify`.
- Kết quả 2026-09-21: Common 6 unit; Blockchain 9 unit + 4 integration; toàn bộ pass.

## 9. Điều kiện mở BC-002/BC-003

- BC-002 chỉ bắt đầu khi producer/event envelope, topic, partition key, PII allowlist và processed-event strategy được Thái–Hải duyệt.
- BC-003 chỉ bắt đầu sau phase gate P3 và có Fabric network profile, chaincode contract, certificate/key secret handling, timeout/retry và test environment thật.
