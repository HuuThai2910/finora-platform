---
task_id: W2-LEGAL
title: Hạn mức dư nợ người vay khi nộp hồ sơ và xác nhận của nhà đầu tư trước lệnh sơ cấp
owner: Both
status: READY_FOR_REVIEW
depends_on: [R1A]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
review_required: Hải review phần Investment và mobile trước merge (D2)
---

# W2-LEGAL — Hạn mức dư nợ (D5) và xác nhận của nhà đầu tư (D6)

Thái chốt D5 và D6 ngày 2026-10-17 (mục 3a của [`finora-remaining-work-plan.md`](finora-remaining-work-plan.md)).
Plan xử lý B18. Kiểm soát pháp lý: [`LEGAL-LIMIT-01`](../../docs/LEGAL-COMPLIANCE.md) và
[`LEGAL-DISCLOSURE-02`](../../docs/LEGAL-COMPLIANCE.md).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **Hạn mức dư nợ (D5).** Trước đây Loan chỉ kiểm số tiền của hồ sơ đang nộp. Người vay có thể nộp năm hồ sơ
   80 triệu song song, hoặc vay tiếp khi khoản cũ còn dư nợ lớn. Giờ Loan cộng dư nợ gốc còn lại của các khoản đang
   phục vụ, số tiền của các hồ sơ còn có thể thành khoản vay và hồ sơ mới; vượt 100.000.000 đồng thì từ chối và cho biết
   còn đề nghị được bao nhiêu.
2. **Xác nhận của nhà đầu tư (D6).** Trước lệnh góp vốn sơ cấp, nhà đầu tư phải tick xác nhận đã nhận đủ thông tin
   khoản vay và hiểu rủi ro mất vốn. Investment lưu version nội dung đã xác nhận và thời điểm trên từng lệnh.

### Ai thấy gì?

- Người vay: hồ sơ vượt hạn mức bị từ chối ngay khi nộp với thông điệp
  “Tổng dư nợ và hồ sơ đang xử lý vượt hạn mức 100.000.000 đồng; số tiền còn có thể đề nghị: 20.000.000 đồng”.
  Rút hồ sơ đang chờ hoặc trả bớt dư nợ thì nộp lại được.
- Nhà đầu tư: ô xác nhận màu hổ phách dưới phần tạm tính; nút “Đầu tư” chỉ bật sau khi tick. Mỗi lần mở màn phải tick
  lại, app không nhớ sẵn.
- Vận hành/kiểm toán: mỗi lệnh sơ cấp mới có `riskAcknowledgedVersion` và `riskAcknowledgedAt`.

### Ví dụ

Người vay đang còn dư nợ 30 triệu ở khoản A, có hồ sơ B 40 triệu đang gọi vốn. Nộp hồ sơ C 40 triệu: 30 + 40 + 40 =
110 triệu > 100 triệu → `422`, còn đề nghị được 30 triệu. Hồ sơ B hết hạn gọi vốn → không còn tính → nộp C được.

## 1. Tách pháp luật, policy và giới hạn demo

| Nội dung | Loại | Nguồn/trạng thái |
|---|---|---|
| Nền tảng phải có biện pháp xác định và quản lý dư nợ tối đa | Yêu cầu pháp luật | Điều 11 Nghị định 94/2025/NĐ-CP, `CURRENT` |
| Mức 100 triệu/giải pháp và 400 triệu/toàn bộ giải pháp | Chưa xác minh | Quyết định 2866/QĐ-NHNN, `NEEDS_PRIMARY_SOURCE` (`LEGAL-OPEN-02`) |
| `finora.loan.borrower-exposure.max-outstanding = 100000000` | Policy demo cấu hình được | D5; không được gọi là hằng số pháp lý |
| Chỉ cộng dư nợ tại FINORA, chưa cộng dư nợ ở nền tảng khác | Giới hạn kỹ thuật demo | Cần dữ liệu CIC thật; chưa có |
| Cung cấp đủ thông tin khoản vay trước khi giao kết và có xác nhận của khách hàng | Yêu cầu pháp luật | Điều 22 khoản 8 điểm c Nghị định 94, `CURRENT` |
| Nội dung câu xác nhận `INVESTOR_RISK_V1` | Policy FINORA | Câu chữ cần legal review trước production |

## 2. Dữ liệu và quan hệ

### D5

Không thêm entity hay cột. Dư nợ được tính tại thời điểm nộp từ hai nguồn có sẵn của Loan:

- `finora_loans` (trạng thái `ACTIVE`, `RESTRUCTURING`, `DEFAULTED`, `WRITTEN_OFF`) nối `loan_servicing_projections`
  để lấy `principal_outstanding`. Projection được tạo cùng transaction với `FinoraLoan` lúc giải ngân
  (`ServicingActivationService.activate`), nên khoản vừa giải ngân luôn được tính.
- `loan_applications` còn có thể thành khoản vay: trạng thái `SUBMITTED`, `ELIGIBILITY_PENDING`, `SCORING`,
  `SCORING_RETRY_PENDING`, `PENDING_REVIEW`, `APPROVED`; trừ hồ sơ có funding `EXPIRED/CANCELLED`, điều khoản
  `DECLINED/EXPIRED`, hoặc đã có `FinoraLoan` (khi đó dư nợ đã nằm ở nguồn thứ nhất, không cộng hai lần).

### D6

`investment_orders` thêm hai cột (Investment V16), là value object nhúng của lệnh, không phải entity riêng:

| Cột | Người dùng hiểu | Nguồn | Dùng ở đâu | Thiếu/sai thì |
|---|---|---|---|---|
| `risk_acknowledged_version` VARCHAR(50) | Bản xác nhận đã tick | Mobile gửi `riskAcknowledgementVersion` | Bằng chứng khi tranh chấp/kiểm toán | Thiếu → `400 RISK_ACKNOWLEDGEMENT_REQUIRED`; khác bản hiện hành → `409 RISK_DISCLOSURE_OUTDATED` |
| `risk_acknowledged_at` TIMESTAMPTZ | Lúc xác nhận | Server ghi lúc tạo lệnh | Cùng trên | — |

CHECK `ck_investment_order_risk_acknowledgement`: hai cột cùng có hoặc cùng null. Null hợp lệ cho lệnh trước V16 và lệnh
Auto-Invest (không có màn đặt lệnh từng khoản). Quan hệ: một lệnh `1 — 0..1` một xác nhận; không có FK.

## 3. API và lỗi

| API | Thay đổi |
|---|---|
| `POST /api/v1/loan-applications` (Loan) | `422 BORROWER_EXPOSURE_LIMIT_EXCEEDED` khi vượt hạn mức; không tạo hồ sơ |
| `POST /api/v1/investments/listings/{listingId}/orders` (Investment, PIN `INVEST`) | Body thêm `riskAcknowledgementVersion`; lệnh mới thiếu → `400`, version cũ → `409`; gửi lại cùng `Idempotency-Key` của lệnh đã tạo trả lệnh cũ, không kiểm lại version |

## 4. Query, index, transaction và concurrency

- Hai truy vấn tổng của D5 lọc theo `borrower_id` và dùng index có sẵn `idx_finora_loans_borrower_status`
  (`finora_loans (borrower_id, status, disbursed_at DESC, id DESC)`, V19) và `idx_loan_applications_borrower_created`
  (`loan_applications (borrower_id, created_at DESC, id DESC)`, V2). Mỗi người vay chỉ có vài hồ sơ nên không thêm index.
- `BorrowerExposureService.requireWithinLimit` chạy trong transaction ghi hồ sơ (`Propagation.MANDATORY`) và lấy
  `pg_advisory_xact_lock(hashtext('finora-loan-exposure:' + borrowerId))` trước khi đọc tổng.

```text
Hai hồ sơ 60 triệu của cùng người vay nộp cùng lúc, hạn mức 100 triệu:
  T1 lấy advisory lock → tổng 0 + 60 ≤ 100 → ghi hồ sơ → commit (nhả lock)
  T2 chờ lock → đọc tổng 60 + 60 > 100 → 422, không ghi
```

Không có advisory lock, cả hai cùng đọc tổng 0 và cùng được nhận. Lock chỉ chặn hồ sơ của cùng người vay, không khóa
bảng. D6 không có tranh chấp: kiểm xác nhận chạy trước khi ghi lệnh và giữ tiền.

## 5. Bản đồ code thực tế

| Luồng | Hàm |
|---|---|
| D5 nộp hồ sơ | `LoanApplicationController.submit` → `LoanApplicationSubmissionStateService.persist` → `BorrowerExposureService.requireWithinLimit` → `LoanApplicationRepository.lockBorrowerExposure`, `FinoraLoanRepository.sumOutstandingPrincipal`, `LoanApplicationRepository.sumOpenRequestedAmount` |
| D5 cấu hình | `BorrowerExposureProperties` (`finora.loan.borrower-exposure.enabled`, `max-outstanding`), đăng ký trong `LoanTimeConfig` |
| D6 đặt lệnh | `InvestmentOrderController.placeOrder` → `FundingServiceImpl.placeOrder` (`requireRiskAcknowledgement` cho lệnh mới) → `OrderTransactionServiceImpl.createPendingOrder` ghi version + thời điểm |
| D6 cấu hình | `InvestorRiskDisclosureProperties` (`finora.investment.risk-disclosure.version`, mặc định `INVESTOR_RISK_V1`) |
| D6 mobile | `market/constant.ts` (`RISK_DISCLOSURE_VERSION`, `RISK_DISCLOSURE_TEXT`), `market/api.ts` gửi version, `useInvestForm` (`riskAccepted`), `LoanDetailScreen` (ô xác nhận, nút bị khóa tới khi tick) |
| E2E | `docker/e2e/run-vertical-slice.ps1` gửi `riskAcknowledgementVersion = INVESTOR_RISK_V1` |

## 6. Bằng chứng kiểm thử

| Test | Chứng minh |
|---|---|
| `BorrowerExposureServiceTest` (4) | Nhận đúng bằng hạn mức; cộng dư nợ + hồ sơ đang mở, thông điệp có số tiền dạng 100.000.000; khóa trước khi đọc tổng; tắt policy thì bỏ qua |
| `FinoraLoanApplicationIT.borrowerExposureLimitCountsOpenApplicationsAndRejectsOverLimit` | Trên PostgreSQL: 3 hồ sơ × 50 triệu với hạn mức 150 triệu được nhận, hồ sơ thứ tư `422`; rút một hồ sơ qua API thì nộp được hồ sơ mới |
| `FundingFlowIT.primaryOrderRequiresCurrentRiskAcknowledgement` | Thiếu xác nhận `400`, version cũ `409`, hai lệnh bị từ chối không tạo lệnh hay giữ tiền; lệnh hợp lệ lưu version + thời điểm; gửi lại cùng khóa không kèm xác nhận trả đúng lệnh cũ |
| E2E `r1a-20261017-052157` (jar Investment có D6, V16 áp khi khởi động) | Lệnh 10.000.000 `IO-1792189342080-3e2d2` `COMMITTED` qua Gateway với version `INVESTOR_RISK_V1`; hồ sơ người vay mới không vượt hạn mức. Hai lần chạy 04:45/04:49 dùng jar Investment trước D6 nên không tính làm bằng chứng D6 |
| Mobile | `npx tsc --noEmit` PASS |

## 7. Việc còn lại và quyết định cần owner

- Kiểm dư nợ toàn thị trường (400 triệu trên mọi nền tảng) cần CIC thật; chờ `LEGAL-OPEN-02`.
- Auto-Invest (R11) đặt lệnh thay nhà đầu tư nên không có xác nhận từng lệnh. Trước khi bật Auto-Invest ngoài demo,
  owner phải quyết định: lấy xác nhận khi tạo quy tắc Auto-Invest (khuyến nghị, lưu version trên quy tắc) hay cấm
  Auto-Invest cho tới khi có legal review. Hiện Auto-Invest tắt trong E2E (D11).
- Nội dung câu xác nhận đang nằm ở mobile; backend chỉ giữ version. Đổi nội dung phải tăng version ở cả hai nơi. Phát
  hành nội dung từ backend (kèm hash) là cải tiến sau, chưa làm.
