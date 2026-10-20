---
task_id: R3-W2
title: Investment nhận event giải ngân không phụ thuộc thứ tự, khóa thao tác sửa lỗi và giữ vị thế đã kết thúc
owner: Hai
status: READY_FOR_REVIEW
depends_on: [R1A]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
review_required: Hải review trước merge (D2 — module Investment và web quản trị Investment do Hải sở hữu)
---

# R3-W2 — Thứ tự event, thao tác sửa lỗi và danh mục đầu tư (Wave 2, phần R3)

Thái ủy quyền làm luôn các defect R3 trong Wave 2 và cho phép sửa module của Hải để đồng bộ (D2, ngày
2026-10-17). Plan xử lý B14, B15, B16 và phần web của B20 trong
[`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 3b.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **Note không bị kẹt khi event đến lệch thứ tự (B14).** Loan phát ba loại event về hợp đồng và giải ngân trên ba topic
   Kafka khác nhau; Kafka chỉ giữ thứ tự trong một topic. Nếu `LoanDisbursed` đến trước `LoanContractActivated`,
   Investment cũ từ chối vì listing chưa `EFFECTIVE`, event vào DLT và Note không bao giờ được phát hành: nhà đầu tư đã
   bị trừ tiền mà không thấy khoản đầu tư.
2. **Nút sửa lỗi của admin không được chạy sớm (B15).** Web quản trị có “khóa vốn” và “phát hành Note” để sửa tay khi
   consumer lỗi. Backend cũ không kiểm khoản vay đã giải ngân chưa, nên bấm nhầm khi đang ký có thể phát hành Note cho
   khoản vay chưa nhận tiền. Web còn gọi API “duyệt listing” đã bị xóa khỏi backend.
3. **Khoản đã tất toán hoặc nợ xấu không biến mất khỏi danh mục (B16).** Portfolio cũ chỉ đọc Note `ACTIVE`: tất toán
   xong, vị thế và tổng gốc/lãi đã nhận biến mất như chưa từng đầu tư.

### Ai thấy gì?

- Nhà đầu tư: Note xuất hiện sau giải ngân dù event đến lệch thứ tự; danh mục vẫn hiện khoản đã kết thúc với nhãn
  “Đã tất toán”/“Nợ xấu”, không hiện nút bán cho vị thế đã kết thúc; lãi suất bình quân chỉ tính phần vốn còn sinh lãi.
- Admin: hai nút sửa lỗi chỉ dùng được sau khi Loan xác nhận giải ngân; trước đó web giải thích đang chờ giải ngân.

### Điều kiện hoàn thành nghiệp vụ

- `LoanDisbursed` đến trước mọi event Contract vẫn phát hành đủ Note; event Contract đến muộn không làm trạng thái lùi.
- Hai thao tác sửa lỗi trả `409 LISTING_NOT_DISBURSED` khi chưa có bằng chứng giải ngân.
- Danh mục giữ vị thế `CLOSED`/`DEFAULTED` và tổng đã nhận.

## 1. Current state trước Wave 2

| Defect | Code | Hậu quả |
|---|---|---|
| B14 | `LoanDisbursedEventHandler` yêu cầu `contractStatus = EFFECTIVE` và `contractNumber` đã gắn; `ContractLifecycleEventHandler` ghi đè trạng thái về `PENDING_LENDER_SIGNATURES` | Event lệch thứ tự vào DLT, không tự replay |
| B15 | `InvestmentAdminController` gọi thẳng `finalizeCommitments`/`activateNotes` | Phát hành Note trước khi có tiền |
| B16 | `PortfolioServiceImpl` đọc `findByInvestorIdAndStatus(ACTIVE)` | Vị thế đã kết thúc biến mất |
| B20 (web) | `ApproveForm`, `investmentApi.approveListing` gọi endpoint đã xóa | Lỗi 404 và thông điệp sai |

## 2. Thiết kế

### 2.1 `LoanDisbursed` là bằng chứng Contract đã hiệu lực

Loan chỉ phát `LoanDisbursed` sau khi Contract `EFFECTIVE` và Payment đã chuyển tiền (F05 bước 6). Investment vì vậy
coi chính event này là bằng chứng: listing chưa gắn Contract thì nhận `contractNumber` từ event và đặt `EFFECTIVE`.
Chỉ từ chối (`LOAN_DISBURSED_MISMATCH`) khi tham chiếu đã gắn mà khác, tức là dữ liệu mâu thuẫn thật.

Event Contract đến muộn:

- `LoanBorrowerSignatureRequested`/yêu cầu ký lender: chỉ bổ sung tham chiếu; listing đã `EFFECTIVE` hoặc đã giải ngân
  thì không đưa trạng thái về chờ ký. Hash văn bản đã gắn mà khác → `CONTRACT_DOCUMENT_MISMATCH`.
- `LoanContractActivated`: nhận `contractNumber/documentHash` khi listing chưa có; có rồi mà khác → conflict.

### 2.2 Guard thao tác sửa lỗi

`finalizeCommitmentsAfterDisbursement`/`activateNotesAfterDisbursement` khóa listing (`SELECT … FOR UPDATE`) và chỉ cho
chạy khi listing `FULLY_FUNDED` và `disbursedAt` khác null (do `LoanDisbursed` ghi). Thao tác vẫn idempotent như bản cũ:
Note/commitment đã xử lý không bị tạo lại.

### 2.3 Danh mục giữ vị thế đã kết thúc

- Đọc Note `ACTIVE`, `DEFAULTED`, `CLOSED` của nhà đầu tư; trạng thái vị thế theo khoản vay: còn Note `ACTIVE` →
  `ACTIVE`; không còn mà có `DEFAULTED` → `DEFAULTED`; còn lại → `CLOSED`.
- Vốn còn nằm trong khoản vay gồm cả dư nợ của Note nợ xấu (tiền chưa thu hồi). Lãi suất bình quân gia quyền và
  `activeNoteCount` chỉ tính Note `ACTIVE` (phần đang sinh lãi).
- Mobile `PositionCard` hiện nhãn kết thúc và ẩn nút bán khi vị thế khác `ACTIVE`.

## 3. Dữ liệu, query và index

Không thêm entity, cột hay migration.

- Danh mục: `findByInvestorIdAndStatusIn(investorId, [ACTIVE, DEFAULTED, CLOSED])` dùng index
  `ix_note_investor_status (investor_id, status)` (V1). Danh mục đọc Note một lần rồi gom theo khoản vay trong bộ nhớ,
  không gọi REST theo từng Note (không N+1).
- Listing REST thêm `cancelledAt`, `cancellationReason`, `disbursedAt` (đọc từ cột có sẵn) để web biết listing đã giải
  ngân hay chưa mà không phải đoán.

## 4. Transaction và concurrency

```text
Consumer LoanDisbursed: một transaction
  processed_events.existsByEventId → bỏ qua event trùng
  khóa listing FOR UPDATE → kiểm tham chiếu/tài chính → finalize commitment → phát hành Note
  → outbox NoteOwnershipChanged → ghi disbursedAt → processed_events
Admin repair: một transaction, khóa cùng listing FOR UPDATE trước khi kiểm disbursedAt
```

Admin bấm “phát hành Note” đúng lúc consumer đang xử lý `LoanDisbursed`: cả hai khóa cùng listing nên chạy tuần tự.
Nếu admin chạy trước, `disbursedAt` còn null → 409, không có thay đổi. Nếu consumer chạy trước, admin thấy Note đã có và
thao tác idempotent không tạo Note thứ hai.

## 5. Bản đồ code thực tế

| Luồng | Hàm |
|---|---|
| Consumer `LoanDisbursed` | `LoanDisbursedEventHandler.handle` → `requireFinancialResult` → `NoteIssuanceService.finalizeCommitments`/`activateNotes` → `InvestmentOutboxService.record(NoteOwnershipChanged)` |
| Consumer Contract | `ContractLifecycleEventHandler` (`requireSameDocument`, không lùi trạng thái khi đã `EFFECTIVE`/đã giải ngân; `handleActivated` nhận tham chiếu khi listing chưa có) |
| Admin repair | `InvestmentAdminController` → `NoteIssuanceServiceImpl.finalizeCommitmentsAfterDisbursement`/`activateNotesAfterDisbursement` → `requireDisbursed` |
| Danh mục | `PortfolioServiceImpl` → `InvestmentNoteRepository.findByInvestorIdAndStatusIn`, `positionStatus` |
| Listing REST | `MarketListingResponse` + `InvestmentMapper` (thêm `cancelledAt`, `cancellationReason`, `disbursedAt`) |
| Web | Xóa `ApproveForm.tsx`, `approveListing`, `ApproveListingRequest`; `stage.ts` (`NextAction = FINALIZE \| ACTIVATE_NOTES`, `awaitingDisbursement`), `useListingStage` truyền `listing.disbursedAt != null`, `FundingFlow` gắn nhãn “(sửa lỗi)” và gợi ý chờ giải ngân |
| Mobile | `types/invest.ts` (`PortfolioPositionStatus`), `investment/mapper.ts`, `PositionCard` (nhãn kết thúc, ẩn nút bán) |

## 6. Bằng chứng kiểm thử

| Test (`FundingFlowIT`, PostgreSQL Testcontainers) | Chứng minh |
|---|---|
| `loanDisbursedBeforeContractEventsStillIssuesNotesAndLateEventsDoNotRegress` | `LoanDisbursed` đến trước, Note phát hành; event yêu cầu ký và kích hoạt đến muộn không đưa trạng thái lùi |
| `adminRepairActionsRequireDisbursementEvidence` | Hai thao tác sửa lỗi trả `409 LISTING_NOT_DISBURSED` trước giải ngân (phần vốn vẫn `ACTIVE`); sau `LoanDisbursed` chạy được, trả 0 và không tạo Note thứ hai |
| `portfolioKeepsSettledPositionsAndReceivedTotals` | Vị thế `CLOSED` còn trong danh mục với tổng đã nhận; lãi suất bình quân chỉ tính Note `ACTIVE` |
| Toàn module Investment | 68 unit + 41 IT PASS ngày 2026-10-17 |
| Web | `npm run build` PASS |
| Mobile | `npx tsc --noEmit` PASS |
| E2E R1A `r1a-20261017-044507`, `r1a-20261017-044938`, `r1a-20261017-052157` | 10 Note `ACTIVE`, drill phát lại `LoanDisbursed` không tạo Note thứ hai; lần cuối chạy jar Investment cuối của Wave 2 |

## 7. Sai khác và việc còn lại

- Không thêm worker tự replay DLT: sửa gốc ở consumer làm event lệch thứ tự không vào DLT nữa. Event vào DLT vì dữ liệu
  mâu thuẫn thật vẫn cần vận hành xử lý (R9 replay/DLT).
- Rebuild portfolio từ event/ledger và consumer `RepaymentDistributed` cho cơ cấu/default/cure thuộc phần R3 còn lại.
