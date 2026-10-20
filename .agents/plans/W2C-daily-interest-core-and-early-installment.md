---
task_id: W2C
title: Core V4 tính lãi theo ngày, khoản trả kỳ trước hạn ghi đúng ngày đến hạn và định danh người vay thống nhất
owner: Thai
status: READY_FOR_REVIEW
depends_on: [R1A, G-POLICY-W2]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# W2C — Lãi theo dư nợ thực tế, trả kỳ trước hạn và B26

Thái duyệt D15–D18 và gói Q ngày 2026-10-17, cho phép agent tự chọn phương án tốt nhất (D23). Plan xử lý B26, B28,
B29, B30 trong [`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 3b. Kiểm soát pháp lý:
[`LEGAL-SERVICING-01`](../../docs/LEGAL-COMPLIANCE.md), [`LEGAL-CONTRACT-01`](../../docs/LEGAL-COMPLIANCE.md). Phần sửa
module User (B26) thuộc vùng của Hải: **Hải review trước merge** (D2).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Fixture R1B (`r1b-partialprepayment-20261017-063137`, `r1b-earlysettlement-20261017-063404`) chứng minh sản phẩm
Fineract do Loan tạo đã tính sẵn lãi cho cả kỳ hạn: trả thêm 3.000.000 gốc bị thu 84.000 lãi của kỳ sau (B28), tất
toán ngay ngày giải ngân bị thu 353.000 lãi của sáu tháng (B29). Cả hai trái điều 4 hợp đồng ("không thu lãi của các
tháng tương lai").

Sau task:

1. Sản phẩm mới chạy core V4: lãi tính trên dư nợ thật và số ngày thật. Trả thêm gốc giảm dư nợ ngay; tất toán chỉ thu
   lãi đến ngày tất toán.
2. Người vay trả kỳ trước ngày đến hạn: tiền rời ví ngay nhưng chỉ ghi vào sổ nợ đúng ngày đến hạn, nên lãi kỳ và các
   kỳ sau giữ nguyên như lịch (đúng thực tế: trả sớm tiền của kỳ không làm đổi lãi của kỳ). Trước ngày đó người vay hủy
   được để nhận lại tiền.
3. Khoản vay đã giải ngân ở core cũ (V1–V3) không tự trả trước/tất toán trên app được nữa, thay vì bị thu sai.
4. CIC nhận đúng mã người vay mà Loan/Payment dùng (B26).

### Ví dụ (probe Fineract 1.15 local ngày 2026-10-17, 10.000.000, 6 kỳ, 12%/năm)

- Tất toán ngày 0: 10.000.000, lãi 0. Tất toán ngày 15: 10.049.000, lãi 49.000.
- Trả trước một phần ngày 15 = khoản kỳ gần nhất 1.726.000 + gốc 3.000.000: Fineract ghi lãi 49.000 (đúng lãi đã phát
  sinh), gốc 4.677.000; không có lãi tương lai.
- Trả kỳ 1 (1.726.000) ngay ngày giải ngân: Payment giữ tới 17/11; ngày 17/11 Fineract ghi gốc 1.624.000, lãi 102.000,
  đúng lịch.
- Nếu ghi ngay ngày 15 thay vì giữ: lãi kỳ 1 còn 49.000 nhưng phần lãi còn lại dồn sang kỳ 2 và lịch các kỳ sau đổi. Đây
  là lý do giữ tới ngày đến hạn (D16) và lý do không ghi sớm khoản đang giữ khi người vay chỉ xem báo giá (D22).

### Ai thấy gì?

- Người vay (mobile): màn trả kỳ báo "ghi nhận vào kỳ ngày dd/MM, lãi kỳ giữ nguyên"; sau khi trả thấy thẻ "Đã thanh
  toán, chờ ghi nhận" với nút "Hủy và hoàn tiền về ví" (PIN); màn trả trước/tất toán báo rõ khi khoản vay thuộc sản phẩm
  cũ hoặc còn khoản chờ ghi nhận (có nút mở khoản đó).
- Nhà đầu tư: nhận tiền của kỳ vào ngày đến hạn.
- Hợp đồng mới: `LOAN_TERMS_V3` / `CLICK_WRAP_TEXT_V4` / `CONTRACT_PDF_V3` / `FINORA_SERVICING_TERMS_V3`; hợp đồng
  cũ giữ nguyên văn bản đã ký.

## 1. Tách pháp luật, policy và quyết định

| Nội dung | Loại | Nguồn |
|---|---|---|
| Lãi, phí trả trước phải ghi rõ trong hợp đồng; không thu khoản hợp đồng không ghi | Yêu cầu tham chiếu | `LEGAL-SERVICING-01`, `LEGAL-CONTRACT-01` |
| Lãi theo dư nợ và số ngày thực tế; không thu lãi tháng tương lai khi trả trước/tất toán | Điều khoản hợp đồng FINORA (V2 đã hứa, V3 viết rõ) | `FINORA_SERVICING_TERMS_V2/V3` |
| Trả kỳ trước hạn ghi đúng ngày đến hạn; hủy được trước ngày đó | Policy sản phẩm FINORA (D16, D22) | Hợp đồng V3 điều 4 |
| Khoản vay core V1–V3 không tự trả trước/tất toán | Biện pháp an toàn tạm thời (D17), không phải luật | Fineract không đổi được cấu hình khoản đã tạo |
| Đồng hồ giả chỉ trong stack E2E | Quy định kiểm thử (Q12) | Không có ở demo/production |

## 2. Thiết kế

### Loan (core V4 và hợp đồng)

- `FINERACT_PRODUCT_CONFIG_VERSION` mặc định `FINORA-FINERACT-V4`. Product V4: `PROGRESSIVE`, advanced allocation,
  `NEXT_INSTALLMENT`, `isInterestRecalculationEnabled=true`, `interestRecalculationCompoundingMethod=0`,
  `rescheduleStrategyMethod=4`, `recalculationRestFrequencyType=2` (hằng ngày), interval 1,
  `preClosureInterestCalculationStrategy=1`, `isArrearsBasedOnOriginalSchedule=false`, `interestCalculationPeriodType=0`
  (Fineract 1.15 bắt buộc tính theo ngày khi bật tính lại lãi). Lịch tính thử và booking dùng cùng kiểu tính lãi.
- Đối chiếu Product đã có (`validateReconciledProduct`) từ chối Product V4 tắt tính lại lãi.
- Không nâng cấu hình Product đang bán (D21): Loan chỉ đồng bộ Product `DRAFT`; tạo Product mới theo V4.
- `ContractTextVersion` chọn bộ văn bản theo `coreConfigVersionSnapshot` của hồ sơ: V4 → `LOAN_TERMS_V3`,
  `CLICK_WRAP_TEXT_V4`, `CONTRACT_PDF_V3`, `FINORA_SERVICING_TERMS_V3`; V1–V3 giữ nguyên bộ V2.

### Payment (D16, D17, D22, B30)

- **V15** (chỉ thêm, bản cũ đọc/ghi được): `payment_repayments.post_on DATE`, trạng thái `CANCELLED`, ràng buộc
  `post_on > transaction_date` chỉ cho `SCHEDULED`, index `idx_payment_repayments_loan_in_flight`.
- `scheduledRepaymentQuote` đọc thêm `nextDueDate` (kỳ đầu còn dư nợ) từ `/loans/{id}?associations=repaymentSchedule`.
- Lệnh trả kỳ: khóa dòng `payment_loan_accounts` → kiểm idempotency → `RepaymentInFlightGuard` → nếu khoản vay V4,
  loại `SCHEDULED` và ngày đến hạn sau hôm nay thì `post_on` = ngày đến hạn. Worker chỉ nhận `COLLECTED` có
  `post_on` rỗng hoặc đã tới; `claim()` kiểm lại và gửi Fineract `transactionDate = post_on`.
- Hủy: `POST /api/v1/repayments/{id}/cancel` (PIN `REPAYMENT`), chỉ khi còn trước ngày ghi nhận; khóa tài khoản vay
  rồi giao dịch (cùng thứ tự với lệnh trả); bút toán `REPAYMENT-CANCEL-REFUND:<id>` clearing → ví; gọi lại trả kết quả cũ.
- Báo giá trả trước/tất toán: chỉ core V4 (`409 PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN`); còn khoản đang giữ →
  `409 INSTALLMENT_PENDING_POSTING`; còn khoản đang ghi → `409 REPAYMENT_IN_PROGRESS`. Thực hiện báo giá khóa tài khoản
  vay và từ chối nếu có khoản mới (trừ `FAILED/CANCELLED`) tạo sau lúc lập báo giá (`409 *_QUOTE_STALE`).
- `scheduled-due` trả thêm `dueDate`, `postOn`, `pending{repaymentId, amount, status, postOn, cancellable}`;
  `RepaymentResponse.postOn`.
- Đồng hồ E2E (Q12): `finora.e2e.clock-offset` (`FINORA_E2E_CLOCK_OFFSET`, mặc định 0) chỉ được nhận khi provider giải
  ngân và nạp tiền đều `mock`, nếu không Payment từ chối khởi động. Kiểm PIN vẫn theo đồng hồ UTC thật.

### Mobile / web

- Mobile `LoanPaymentScreen`: thẻ khoản chờ ghi nhận + hủy (Alert xác nhận, PIN), ghi chú trả trước hạn;
  `RepaymentResultCard` trạng thái "Chờ ghi nhận dd/MM" và `CANCELLED`; theo dõi dừng khi khoản đang giữ;
  `PartialPrepaymentScreen`/`EarlySettlementScreen` xử lý `PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN` (không thử lại vô
  ích) và `INSTALLMENT_PENDING_POSTING` (nút mở khoản chờ); câu giải thích phân bổ theo `allocationStrategy` V4.
- Web: không có màn trả nợ người vay; màn admin incident cho `RECONCILIATION_REQUIRED` thuộc R2 (Wave 2D).

### User (B26, Hải review)

`CicMappingWorker` gửi `String.valueOf(profile.getId())` (claim `user_id`) thay cho `keycloakUserId`.

### Harness E2E

- Product `E2E_V4_FIX12_<máy>`; check `fineract.daily-interest-loan`.
- Bước trả kỳ mặc định: trả kỳ 1 ngay sau giải ngân → kiểm giữ, chặn trả hai lần, chặn báo giá, chưa ghi core, hủy hoàn
  tiền, báo giá tất toán theo hợp đồng; trả lại → `Move-E2EClock` (bật business date Fineract = ngày đến hạn, restart
  Payment `-PaymentClockOffset P<n>D`) → COMPLETED → `Reset-E2EClock` trong `finally`; kiểm ghi đúng ngày đến hạn và
  gốc/lãi bằng lịch.
- B28/B29 chuyển từ defect thành check `prepayment.no-future-interest`, `settlement.no-future-interest`.
- `Reset-E2EClock` chạy ở preflight và cuối lượt, nên lượt bị ngắt giữa chừng không để Fineract/Payment ở ngày giả.
- Launcher: `-Action Restart -Services <tên>`, `-PaymentClockOffset P<n>D`, `Status` hiện đồng hồ.

## 3. Vòng đời và ca lỗi

| Ca | Xử lý |
|---|---|
| Trả hai lần cùng kỳ (hai request đồng thời) | Khóa dòng tài khoản vay; request sau thấy khoản đang giữ → 409 |
| Payment dừng qua ngày đến hạn | Worker chạy lại ghi với `transactionDate = post_on` (ngày quá khứ, Fineract nhận) |
| Hủy đúng lúc worker ghi | Từ ngày đến hạn không hủy được; trước đó worker không nhận; hai bên khóa cùng dòng |
| Fineract từ chối khi ghi (4xx) | Hoàn tiền như trước (`FAILED`) |
| Fineract không rõ kết quả | `RECONCILIATION_REQUIRED`, đối soát theo externalId; lệnh mới bị 409 tới khi xong |
| Báo giá cũ sau khi có khoản mới | 409 `*_QUOTE_STALE`, lập lại báo giá |
| Bản Payment cũ trên DB dùng chung đọc dòng `CANCELLED` | Lỗi enum với đúng dòng đó; chỉ dòng do bản mới tạo; ghi chú cho Hải trước khi merge |
| Cơ cấu được duyệt khi còn khoản giữ | Khoản giữ vẫn ghi vào ngày đến hạn cũ theo lịch mới của Fineract; chưa chặn ở Loan (theo dõi ở fixture 5, Wave 2D) |
| Nhắc lịch khi đã trả trước | Notification chưa biết khoản giữ; R7 phải đọc `pending` trước khi nhắc |

## 4. Acceptance và bằng chứng

| Hạng mục | Bằng chứng |
|---|---|
| Loan payload/booking V4, hợp đồng V3 | `FineractPayloadMapperTest`, `FineractCoreLoanBookingGatewayTest`, `LoanServicingContractTermsTest`; Loan verify 240 unit + 17 IT xanh |
| Payment giữ/hủy/chặn/V4-only/stale | `PaymentRepaymentTest` (3 test mới), `FineractRepaymentGatewayTest`, `PaymentTimeConfigurationTest`, IT `earlyInstallmentOnDailyInterestLoanIsHeldUntilItsDueDateThenPostedWithThatDate`, `borrowerCancelsHeldInstallmentForARefundAndCanThenQuoteSettlement`, `legacyCoreLoanBlocksSelfServicePrepaymentButStillPaysInstallmentsImmediately`, `prepaymentQuoteCannotBeExecutedAfterAnotherRepaymentChangedTheLoan`; Payment verify 66 unit + 19 IT xanh |
| B26 | `CicMappingWorkerTest` |
| Mobile | `tsc --noEmit` sạch |
| E2E trên Fineract thật | Xem mục 5 |

## 5. Lượt chạy

Stack local ngày 2026-10-17: Fineract 1.15 thật, AI v17, CIC Node đã deploy, ký `MOCK`, nạp/giải ngân provider `mock`,
proof `MOCK`; jar Loan/Payment của wave này (Payment V15 được Flyway áp lên DB dev dùng chung khi khởi động).

| Run ID | Kiểm tra | Thời gian | Hồ sơ / Contract | Fineract loan / giao dịch | Defect |
|---|---|---|---|---|---|
| `w2c-installment-20261017-110318` | 49/49 | 227 giây | `LA-5C0D930A2B7A4803AA27` / `LC-31AC1AD0439E41088CE3` | 42 / 112 | Không |
| `w2c-partialprepayment-20261017-110733` | 37/37 | 151 giây | `LA-DAD110F036F448DA8229` / `LC-7283915E115046918B28` | 43 / repayment `be29aa03…` | Không |
| `w2c-earlysettlement-20261017-111021` | 37/37 | 142 giây | `LA-D6369F2FE71A432184B0` / `LC-F00A4E62DF6C4285BA94` | 44 / repayment `7f045195…` | Không |
| `w2c-drills-20261017-111258` (`-Drills`) | 53/53 | 286 giây | `LA-D79727713345449988FA` / `LC-ACE2B2BC7AE94B4DB52B` | 45 / 121 | Không |

Lượt `-Drills` chạy lại toàn bộ bước trả kỳ có dời đồng hồ, rồi phát lại `LoanDisbursed` và `RepaymentDistributed`
(sinh khi Payment chạy đồng hồ lệch): không tạo Note, tiền, thông báo hay proof thứ hai.

Trạng thái tại từng SoR:

- **Trả kỳ trước hạn (loan 42):** lệnh đầu `5eb2cee5…` `COLLECTED` + `postOn=2026-11-17`, Fineract chưa có giao dịch;
  trả lần hai `409 INSTALLMENT_ALREADY_PAID_PENDING`; báo giá tất toán `409 INSTALLMENT_PENDING_POSTING`; hủy →
  `CANCELLED`, ví về 10.000.000. Báo giá tất toán sau đó: phí 1% = 100.000 (80.000 nhà đầu tư). Lệnh thứ hai
  `320cb713…` ghi Fineract giao dịch 112 ngày 2026-11-17: gốc 1.624.000, lãi 102.000 (đúng lịch kỳ 1); nhà đầu tư
  nhận 1.726.000; thông báo `REPAYMENT_CREDITED`, proof `CONFIRMED` (MOCK). Sau lượt: business date Fineract tắt,
  Payment chạy đồng hồ thật.
- **Trả trước một phần (loan 43):** báo giá 1.726.000 + 3.000.000 + phí 100.000 = 4.826.000; Fineract ghi gốc
  4.726.000, lãi 0 (lãi đã phát sinh 0); gốc còn 5.274.000; kỳ tới của Loan là 2027-01-17 vì phần gốc trả thêm phủ các
  kỳ gần nhất (`NEXT_INSTALLMENT`); nhà đầu tư nhận 4.806.000 (gốc + 80% phí).
- **Tất toán (loan 44):** 10.100.000 = gốc 10.000.000 + lãi 0 + phí 100.000; Fineract `closed.obligations.met`; Loan
  `SETTLED`; vị thế danh mục `CLOSED`, nhà đầu tư nhận 10.080.000.

So với lượt phát hiện lỗi (`r1b-partialprepayment-20261017-063137`: lãi 184.000; `r1b-earlysettlement-20261017-063404`:
thu 10.453.000), cả hai không còn thu lãi tương lai.

Lượt bị dừng `w2c-installment-20261017-105652`: harness đọc stdout của launcher qua pipe nên treo khi Payment khởi động
lại (JVM giữ handle, đúng triệu chứng runbook mục 8). Đã sửa `Restart-Payment` dùng `Start-Process` ghi file và chỉ chờ
tiến trình launcher; trả đồng hồ thủ công trước khi chạy lại. Trước khi dừng, Fineract đã ghi khoản giữ của lượt này đúng
2026-11-17 (loan 41, gốc 1.624.000, lãi 102.000).

## 6. Giới hạn còn lại

- Chỉ giữ một khoản trả kỳ mỗi khoản vay; muốn trả trước nhiều kỳ thì dùng trả trước một phần.
- Thẻ "Thanh toán và điều chỉnh" ở chi tiết khoản vay vẫn hiện kỳ tới chưa trả cho tới ngày ghi nhận; màn trả kỳ mới
  hiện trạng thái chờ.
- Khoản vay V1–V3 cần quy trình tất toán thủ công qua admin nếu người vay yêu cầu; chưa có màn admin (R2).
- Fineract 1.15 ghi một giao dịch `Accrual` bằng tổng lãi dự kiến (354.000) ngay ngày giải ngân cho khoản vay V4, kể cả
  khoản tất toán với lãi 0 (loan 42–44). Product dùng `accountingRule=1` nên không có bút toán sổ cái; FINORA không đọc
  giao dịch này (Payment đối chiếu theo externalId, Loan đọc summary/lịch). Màn nào hiển thị lịch sử giao dịch Fineract
  phải lọc loại `Accrual`.
- Sản phẩm demo đang bán trên DB Loan dùng chung không được agent tạo/ngừng thay owner: tạo Product V4 tương ứng
  (DRAFT → đồng bộ core → kích hoạt) là thao tác admin trên từng môi trường, mapping chỉ đúng với Fineract của môi trường
  đó (B21).
