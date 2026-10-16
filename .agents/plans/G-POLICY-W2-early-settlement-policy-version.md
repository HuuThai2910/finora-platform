---
task_id: G-POLICY-W2
title: Phí trả trước và tất toán tính theo đúng biểu phí hợp đồng đã công bố
owner: Thai
status: READY_FOR_REVIEW
depends_on: [R1A]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# G-POLICY-W2 — Snapshot phiên bản biểu phí trả trước (B17)

Thái ủy quyền chọn phương án khuyến nghị ngày 2026-10-17. Plan xử lý B17 trong
[`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 3b và một phần mục 6 (G-POLICY). Kiểm soát pháp
lý: [`LEGAL-SERVICING-01`](../../docs/LEGAL-COMPLIANCE.md), [`LEGAL-CONTRACT-01`](../../docs/LEGAL-COMPLIANCE.md).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Payment tính phí trả trước một phần và phí tất toán bằng các biến môi trường hiện hành
(`PAYMENT_EARLY_SETTLEMENT_*`) cho mọi khoản vay. Hai hậu quả:

1. Vận hành đổi một biến (ví dụ 1% thành 2%) thì mọi khoản vay đã ký bị tính theo mức mới, trái điều khoản người vay
   đã ký.
2. Hợp đồng ký trước 2026-10-16 22:43 (`LOAN_TERMS_V1`, commit `e5ac375`) không có điều khoản phí trả trước nào, nhưng
   vẫn bị tính theo biểu phí `AGRIBANK_REFERENCE_FINORA_2026_V2` của hợp đồng mới.

Sau task: mỗi hợp đồng ghi phiên bản biểu phí nó đã công bố; Payment tra phiên bản đó trong danh mục bất biến; hợp đồng
không công bố biểu phí thì không thu phí trả trước.

### Ví dụ

- Hợp đồng `LOAN_TERMS_V2`, 6 tháng, tất toán khi mới qua 30% thời hạn, gốc còn 8.375.000: 1% = 83.750 nhỏ hơn mức tối
  thiểu nên phí 100.000 (80.000 cho nhà đầu tư, 20.000 cho FINORA). Đổi biến môi trường không làm đổi kết quả.
- Hợp đồng `LOAN_TERMS_V1` cùng ngày, cùng số tiền: phí 0, vì hợp đồng không có điều khoản phí trả trước.

### Ai thấy gì?

Người vay thấy báo giá có `policyVersion` của chính hợp đồng mình; nhà đầu tư nhận phần phí theo cách chia của phiên bản
đó; vận hành không đổi được biểu phí của khoản vay đã ký bằng cấu hình.

## 1. Tách pháp luật, policy và quyết định

| Nội dung | Loại | Nguồn |
|---|---|---|
| Phí trả nợ trước hạn do các bên thỏa thuận và phải ghi rõ loại/mức phí trong hợp đồng | Yêu cầu tham chiếu | `LEGAL-SERVICING-01` (Điều 14, 23 `CIRCULAR-LENDING-CONSOLIDATED-2026`) |
| Dải thời gian, tỷ lệ 1%/0,5%/0%/2%/1,5%, tối thiểu 100.000, chia 80/20 | Policy demo FINORA | `AGRIBANK_REFERENCE_FINORA_2026_V2`, ghi trong hợp đồng V2 |
| Hợp đồng không công bố biểu phí thì phí 0 | Quyết định chọn theo ủy quyền ngày 2026-10-17, cần owner xác nhận | Không thu khoản phí hợp đồng không ghi |

## 2. Thiết kế

- **Loan V30:** `loan_contracts.early_settlement_policy_version VARCHAR(50)`; hợp đồng mới ghi
  `LoanServicingContractTerms.EARLY_SETTLEMENT_POLICY_VERSION` lúc tạo (mọi hợp đồng mới đều in điều khoản này).
  Backfill: `terms_version = 'LOAN_TERMS_V2'` → `AGRIBANK_REFERENCE_FINORA_2026_V2`; hợp đồng khác để NULL (không công
  bố biểu phí). Không sửa nội dung hay hash hợp đồng.
- **Event:** `LoanDisbursed.v1` thêm field optional `earlySettlementPolicyVersion` (NULL với hợp đồng không công bố);
  consumer cũ bỏ qua field lạ. Không đổi nghĩa field cũ (`04-conventions-contracts.md`).
- **Payment V14:** `payment_loan_accounts.early_settlement_policy_version VARCHAR(50)`; consumer `LoanDisbursed` ghi
  một lần, không cập nhật. Tài khoản mở trước V14 để NULL → coi như không công bố biểu phí.
- **Danh mục bất biến** `EarlySettlementPolicyCatalog` trong code Payment: `AGRIBANK_REFERENCE_FINORA_2026_V2` đúng như
  văn bản hợp đồng; NULL dùng phiên bản `NO_FEE_DISCLOSED` (mọi tỷ lệ 0). Bỏ các biến tỷ lệ/tối thiểu/cách chia khỏi
  cấu hình; giữ `quote-ttl`. Đổi biểu phí = thêm phiên bản mới vào code, triển khai Payment trước Loan.
- Phiên bản lạ (Loan đã ký theo biểu phí Payment chưa biết): báo giá `409 EARLY_SETTLEMENT_POLICY_UNKNOWN`, không đoán;
  trả kỳ thường không bị ảnh hưởng.
- Báo giá vẫn lưu `policy_version` (cột có sẵn từ V7/V9), nay là phiên bản của chính khoản vay.

## 3. Transaction, concurrency và rollout

Không có tranh chấp mới: phiên bản biểu phí ghi một lần cùng transaction tạo hợp đồng (Loan) và mở tài khoản thu nợ
(Payment), sau đó chỉ đọc. Rollout: Payment V14 + danh mục triển khai trước hoặc cùng Loan V30; event đến trước khi
Payment nâng cấp chỉ mất field mới, tài khoản đó rơi vào nhánh không công bố biểu phí (thiên về người vay).

## 4. Test và acceptance

- Unit: calculator V2 đúng các dải và mức tối thiểu; `NO_FEE_DISCLOSED` luôn 0; phiên bản lạ bị từ chối.
- Payment IT: hai tài khoản cùng ngày giao dịch, một V2 và một không công bố, cho hai báo giá khác nhau; consumer lưu
  phiên bản từ event.
- Loan: hợp đồng mới ghi phiên bản; event mang phiên bản; migration upgrade lên V30.
- E2E R1A vẫn xanh.

## 5. Bản đồ code thực tế

```text
Loan: LoanContractCreationServiceImpl.create | createFunded
  → LoanContract.create(...) → recordEarlySettlementPolicy(LoanServicingContractTerms.EARLY_SETTLEMENT_POLICY_VERSION)
  → saveAndFlush (cột early_settlement_policy_version, updatable = false)
Loan: DisbursementSagaService.complete → LoanDisbursedEventData(..., contract.getEarlySettlementPolicyVersion())
  → outbox LoanDisbursed v1
Payment: LoanDisbursedConsumer → PaymentServicingProjectionService.activateLoan
  → PaymentLoanAccount.activate(..., earlySettlementPolicyVersion, ...)   ghi một lần
Payment: EarlySettlementQuoteService.create | PartialPrepaymentQuoteService.create
  prepare (Tx đọc tài khoản) → QuotePreparation(fineractLoanId, policyVersion)
  → Fineract quote ngoài transaction
  → EarlySettlementFeeCalculator.calculate(policyVersion, ...) → EarlySettlementPolicyCatalog.forAccount
  → lưu quote với policy_version của khoản vay
```

- `EarlySettlementPolicyProperties` chỉ còn `quoteTtl`; `application.yml` và `docker/.env.example` bỏ các biến tỷ lệ.
- Mobile `loan-servicing/mappers/fee.ts`: tỷ lệ chia phí lấy từ số backend trả về thay vì ghi cứng 80/20; ghi chú
  biểu phí theo `policyVersion`, hợp đồng `NO_FEE_DISCLOSED` hiện câu “không quy định phí trả trước”.
- Harness E2E `Test-SettlementQuotePolicy`: lập báo giá tất toán (không chuyển tiền) và kiểm phiên bản/phí.

## 6. Bằng chứng kiểm thử

| Lớp | Test | Kết quả |
|---|---|---|
| Payment unit | `EarlySettlementFeeCalculatorTest` (8: các dải V2, mức tối thiểu, hợp đồng không công bố → 0, phiên bản lạ → lỗi) | 62 unit PASS |
| Payment IT | `prepaymentFeeFollowsTheContractPolicyNotTheCurrentOne` (cùng ngày, cùng số tiền: V2 phí 100.000, không công bố phí 0; tài khoản lưu đúng phiên bản), `partialPrepaymentQuotesCollectsPostsAndDistributesWithV2CoreAccount` | 15 IT PASS |
| Loan unit | `DisbursementSagaServiceTest.completedCoreBookingPublishesLoanDisbursedWithContractFeePolicy` | 235 unit PASS |
| Loan IT | `FinoraLoanApplicationIT` (hợp đồng nhiều bên lưu `AGRIBANK_REFERENCE_FINORA_2026_V2`), `LoanMigrationUpgradeIT` lên V30 | 17 IT PASS (1 skip có sẵn) |
| Mobile | `npx tsc --noEmit` | PASS |
| E2E R1A | `r1a-20261017-060853`: Flyway áp Loan V30, Payment V14 trên DB dùng chung; hợp đồng `LC-0343421602FF47F68462`, Fineract loan 17. Báo giá tất toán thật sau kỳ 1: `policyVersion = AGRIBANK_REFERENCE_FINORA_2026_V2`, gốc 8.375.000, tỷ lệ 1% (83.750) → phí tối thiểu 100.000, nhà đầu tư 80.000, FINORA 20.000 | 44/44, `defects = []` |

Nhánh hợp đồng `LOAN_TERMS_V1` không được chạy trên stack thật vì hợp đồng mới đều là V2; nhánh này có ở unit test
và integration test của Payment.

Phí đúng biểu phí, nhưng tổng báo giá 8.728.000 của lần chạy trên gồm 253.000 lãi các kỳ chưa đến (B29): Fineract
tắt tính lại lãi nên `prepayLoan` trả toàn bộ lãi còn lại. Task này chỉ sửa phần phí; phần lãi theo B28/B29.
