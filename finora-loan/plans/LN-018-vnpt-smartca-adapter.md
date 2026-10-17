---
task_id: LN-018
title: Tích hợp VNPT SmartCA UAT cho các bên ký Contract
owner: Thai
status: IN_PROGRESS
depends_on: [LN-008]
created_at: 2026-09-21
updated_at: 2026-09-27
approved_by: Thai
approved_at: 2026-09-21
---

# LN-018 — VNPT SmartCA UAT

## Bản đọc nhanh theo nghiệp vụ

Borrower hoặc investor mở đúng PDF bất biến, bấm ký, FINORA gửi SHA-256 của PDF tới SmartCA và chuyển
đúng Contract/ContractParty sang `SIGNING`. Người ký xác nhận trong ứng dụng SmartCA; FINORA chủ động
tra cứu kết quả. Chỉ khi VNPT trả chữ ký cho đúng `transactionId + docId`, bên ký mới thành `SIGNED`.
Gửi yêu cầu thành công không đồng nghĩa đã ký.

```text
PENDING_SIGNATURE -> SIGNING -> SIGNED
                              -> PENDING_SIGNATURE (VNPT từ chối/hết hạn, cho phép thử lại)
```

## 1. Current state, phạm vi và ngoài phạm vi

- Provider mock đồng bộ vẫn dùng cho local/dev; adapter VNPT UAT đã có submit/status và fail-closed khi
  thiếu cấu hình hoặc dùng host ngoài UAT.
- Thực hiện Web API v1 UAT: lấy chứng thư, gửi yêu cầu ký hash và tra cứu trạng thái.
- Credential và định danh thuê bao test chỉ đọc từ biến môi trường; không log/lưu CCCD, password hoặc
  chứng thư thô.
- Giai đoạn này lưu detached signature evidence và receipt. Không tuyên bố PDF có chữ ký PAdES nhúng;
  việc nhúng cần bộ PDF HashSigner/SDK chính thức tương thích từ VNPT.
- Không webhook ở bước đầu vì tài liệu công khai chưa mô tả cơ chế xác thực webhook. Polling có giới hạn
  là nguồn hoàn tất UAT.

## 2. Ownership và pháp lý

- Loan là SoR của Contract/signature state; VNPT là nguồn kết quả chữ ký.
- Tuân theo `LEGAL-CONTRACT-01` và `LEGAL-DATA-01`: ký đúng bytes/hash đã xem, không log PII/secret,
  không gọi click-wrap hoặc receipt là chữ ký số nhúng PDF.
- Fixed signer chỉ được phép khi `sandbox-fixed-signer-enabled=true` và base URL là UAT; production phải
  lấy signer identity just-in-time từ User contract đã được Hải duyệt.

## 3. Dữ liệu và migration

`loan_contracts` và `loan_contract_parties` có `signature_document_id` và `signature_requested_at`.
Trạng thái `SIGNING` giữ provider/method/transaction/docId/idempotency nhưng chưa có
`signedBy/signedAt/evidenceHash`. Mỗi investor có transaction riêng dù ký cùng PDF/hash.

```text
LoanContract 1 ---- 0..N LoanContractStatusHistory
```

Không tạo bảng chứng thư hoặc lưu raw signature. `signatureEvidenceHash` là SHA-256 của provider,
transaction, docId và giá trị chữ ký đã nhận.

## 4. API và failure path

- `POST /api/v1/loan-contracts/{number}/sign`: tạo hoặc tiếp tục cùng yêu cầu idempotent; mock hoàn tất
  ngay, SmartCA trả `SIGNING`.
- `POST /api/v1/loan-contracts/{number}/signature/refresh`: tra cứu VNPT; pending giữ nguyên, success
  hoàn tất, reject/expire trả Contract về `PENDING_SIGNATURE`.
- `POST /api/v1/investor/loan-contracts/{number}/signature/refresh`: cùng nguyên tắc nhưng cập nhật
  đúng lender parties của investor hiện tại; đủ lender mới mở lượt ký borrower.
- HTTP timeout không tự tạo yêu cầu mới. Retry cùng idempotency key dùng lại transaction ID đã lưu.
- Mobile chỉ poll khi `SIGNING`, dừng ở background/terminal và cho phép người dùng bấm kiểm tra lại.

## 5. Security, transaction và kiểm thử

- External HTTP luôn nằm ngoài DB transaction/row lock; transition được kiểm tra lại dưới pessimistic lock.
- Timeout kết nối/đọc cấu hình; response giới hạn và validate field bắt buộc; log chỉ operation/latency/result.
- Unit test request/response mapping, provider pending/completed/error, domain transition/idempotency; integration
  test migration và API state. Live UAT chỉ chạy thủ công với secret từ `.env`, không nằm trong test suite.

## 6. Điều kiện hoàn thành

- Không có secret/CCCD/serial thật trong Git hoặc log.
- Mobile hiển thị rõ đang chờ SmartCA và không báo đã ký sớm.
- Contract chỉ `SIGNED` sau khi VNPT trả chữ ký cho đúng tài liệu.
- Maven verify, migration PostgreSQL và TypeScript type-check đạt; live UAT được ghi riêng, không trộn với test tự động.

## 7. Kết quả kiểm tra 2026-09-21

- `mvn -pl finora-loan -am test`: 83 test đạt.
- `LoanMigrationUpgradeIT`: V4 → V13 đạt trên PostgreSQL 17.5 Testcontainers.
- `FinoraLoanApplicationIT`: 12 test, 0 lỗi, 1 test bị skip theo điều kiện sẵn có.
- `npx tsc --noEmit` tại mobile: đạt.
- Rule validation: đạt.
- Còn chờ: điền credential vào môi trường cục bộ và chạy live UAT với chứng thư test; không đưa secret
  vào tài liệu hoặc Git.
