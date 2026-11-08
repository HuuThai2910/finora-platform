# FUNDING-REOPEN-001 — Gọi vốn bổ sung khi nhà đầu tư rút, và lịch sử xử lý đầy đủ

**Owner:** Hải (Loan, Investment, Notification, Web, Mobile — Thái giao làm hết)
**Trạng thái:** IN_PROGRESS (code xong, unit test xanh; IT Testcontainers chưa chạy — 08/11 Hải chọn kiểm thật trên bản deploy)
**Thay thế một phần:** quy tắc "nhà đầu tư chưa ký từ chối → hủy cả hợp đồng" của W4A.

## 1. Lớp nghiệp vụ

**Trước đây:** khoản vay 50 triệu đủ vốn từ A (30 triệu) và B (20 triệu). A đã ký, B bấm "từ chối ký" → cả hợp đồng
`DECLINED`, hồ sơ dừng, tiền giữ của A cũng bị trả lại. Người vay phải nộp hồ sơ mới; A mất vị trí dù đã ký.

**Bây giờ:**

1. B (chưa ký) từ chối, hoặc hết hạn ký mà B vẫn chưa ký → chỉ phần 20 triệu của B rời hợp đồng; tiền giữ của B về ví.
2. Hợp đồng chuyển **"Đang gọi vốn bổ sung"** (`PENDING_REFUNDING`); khoản vay quay lại sàn với 20 triệu còn thiếu. Hạn
   gọi vốn được dời tối thiểu một chu kỳ `funding_days` nếu đã sát hạn.
3. A giữ nguyên: phần vốn, tiền giữ và chữ ký. A không hủy được phần vốn qua "Rút vốn đang chờ" (phần vốn đã gắn hợp
   đồng), app hiển thị "Đang gọi vốn bổ sung".
4. C góp 20 triệu → khoản vay đủ vốn lần nữa. Loan **phát hành lại chính hợp đồng đó** (Phụ lục 02 có A và C, hash mới,
   hạn ký mới). Chữ ký của A được giữ: với PAdES, chữ ký cũ được lưu làm bằng chứng (`SUPERSEDED`) và nhúng lại lên bản
   mới bằng khóa demo của A (provider MOCK). C được mời ký; ký xong → người vay ký → giải ngân chỉ dùng tiền giữ của A và C.
5. Người vay vẫn dừng được lúc đang gọi vốn bổ sung (rút hồ sơ hoặc từ chối hợp đồng); hết hạn gọi vốn bổ sung thì hợp
   đồng đóng và mọi tiền giữ được trả.

**Lịch sử xử lý (web, tab "Lịch sử xử lý" của hồ sơ):** trước đây chỉ có chuyển trạng thái hồ sơ (nộp → chấm điểm →
duyệt). Nay có toàn bộ vòng đời: điều khoản cuối, lên sàn, đủ vốn/lập hợp đồng, từng nhà đầu tư ký/từ chối/không ký
kịp, đưa lại lên sàn, phát hành lại, người vay ký, hiệu lực, giải ngân, gia hạn/điều chỉnh kỳ trả (yêu cầu, duyệt/từ
chối, nhà đầu tư không đồng ý, hoàn tất), thu hồi nợ (mở, ghi nhận, đóng), khoản vay đóng, các nhánh hủy/hết hạn. Có nút
lọc theo giai đoạn; mốc tiêu cực chấm màu cảnh báo.

**Pháp lý (demo):** việc nhúng lại chữ ký của bên đã ký lên văn bản phát hành lại là policy demo với provider MOCK
(khóa do nền tảng giữ, `KHONG CO GIA TRI PHAP LY`), không phải kết luận pháp lý — `NEEDS_LEGAL_REVIEW` trước khi dùng
với chữ ký số thật (`LEGAL-CONTRACT-01`, `LAW-ELECTRONIC-2023` trong `docs/LEGAL-COMPLIANCE.md`). Với SmartCA, bên đã ký
phải ký lại. Bản `SIGNABLE` chỉ được thay khi người vay chưa đồng ý (hợp đồng còn ở bước nhà đầu tư); bytes đã có chữ ký
nhà đầu tư vẫn lưu trong các dòng chữ ký `SUPERSEDED`.

## 2. Lớp kỹ thuật

### State

- `LoanContractStatus` thêm `PENDING_REFUNDING`: `PENDING_LENDER_SIGNATURES → PENDING_REFUNDING` (nhà đầu tư rút/hết hạn ký)
  → `PENDING_LENDER_SIGNATURES` (phát hành lại) hoặc `DECLINED` (người vay dừng) / `EXPIRED` (hết hạn gọi vốn bổ sung).
  Không thuộc danh sách hết hạn của worker (cửa sổ ký dừng trong lúc gọi vốn).
- `LoanFundingStatus`: `FULLY_FUNDED → REQUESTED` khi mở lại; `allocationVersion/Hash` cũ được giữ để event đối chiếu và
  để `markFullyFunded` từ chối allocation không mới hơn (`STALE_FUNDING_ALLOCATION`).
- `ContractParty`: phần vốn rời đi `DECLINED`/`EXPIRED` + `closed_at`; `isActiveLender()` = LENDER chưa rời đi. Mọi chỗ
  đếm/duyệt bên cho vay (đủ chữ ký, PAdES, biên nhận, giải ngân, phản hồi nhà đầu tư) chỉ xét bên còn hiệu lực.
- `ContractPdfSignatureStatus` thêm `SUPERSEDED` (`abandon_reason = DOCUMENT_REISSUED`): giữ bytes/hash/cms, rời chuỗi.
- Investment: listing `FULLY_FUNDED → OPEN`, `contract_status = PENDING_REFUNDING`; commitment `contract_bound_at`.

### Quan hệ dữ liệu (không thêm bảng)

```
LoanApplication 1 ── 0..1 LoanContract (uq application_id, phát hành lại tại chỗ)
LoanContract   1 ── 1..N ContractParty (LENDER: 1 dòng / commitment; dòng rời đi giữ lại làm lịch sử)
LoanContract   1 ── 0..N LoanContractSignature (SIGNED: chuỗi hiện hành; SUPERSEDED: bằng chứng bản trước)
LoanContract   1 ── 1   LoanContractDocument SIGNABLE (uq contract+type: bản cũ bị thay khi phát hành lại)
MarketListing  1 ── 1..N InvestmentCommitment (contract_bound_at khác null = đã nằm trong allocation khóa)
```

### Event

`LoanFundingReopened.v1` / `finora.loan.funding-reopened` (Loan → Investment, Notification). Payload và quy tắc ở
`docs/integrations/LOAN-INVESTMENT-EVENTS.md` §5.3b. `LoanFullyFunded.v2` không đổi schema; `allocationVersion` nay tăng.

### Migration

- Loan `V34__reopen_funding_after_investor_withdrawal.sql`: thêm `PENDING_REFUNDING` vào check status/history/consent,
  `loan_contract_parties.closed_at`, `SUPERSEDED` vào check chữ ký PDF.
- Investment `V21__reopen_funding_after_investor_withdrawal.sql`: check `contract_status` thêm `PENDING_REFUNDING`,
  `investment_commitments.contract_bound_at`.
- Không thêm index: lịch sử xử lý đọc theo khóa sẵn có (`application_id`, `contract_id`, `finora_loan_id`,
  `collection_case_id`) của một hồ sơ, vài chục dòng.

### Transaction và đồng thời

- Nhà đầu tư từ chối: một transaction Loan khóa Contract → khóa party của nhà đầu tư → đổi party, Contract, funding,
  history, outbox. Hai nhà đầu tư cùng từ chối: người thứ hai chờ khóa Contract, thấy `PENDING_REFUNDING` → 409
  `LENDER_SIGNATURE_WINDOW_CLOSED` (phần của họ vẫn giữ, có thể ký sau khi phát hành lại hoặc tự rời khi hết hạn).
- Ký và từ chối cạnh tranh: cùng khóa Contract nên chỉ một bên commit.
- Trong `PENDING_REFUNDING` Loan không nhận chữ ký/từ chối của nhà đầu tư, Investment không cho hủy commitment đã gắn
  hợp đồng → không có thay đổi nào chạy song song với allocation mới đang hình thành.
- Investment xử lý `LoanFundingReopened` dưới khóa listing; listing chỉ có thể đang `FULLY_FUNDED` với đúng allocation
  (không lệnh nào commit được khi listing không `OPEN`).
- Phát hành lại chạy trong transaction consumer `LoanFullyFunded` (đã khóa hồ sơ), khóa Contract; MOCK ký đồng bộ
  trong transaction như luồng ký MOCK hiện có.

## 3. Lớp code thực tế

| Bước | Đường đi |
|---|---|
| Nhà đầu tư từ chối | `POST /api/v1/investor/loan-contracts/{n}/decline` → `InvestorContractServiceImpl.decline` → `LoanContractStateServiceImpl.declineByInvestor` → `ContractParty.decline` → `reopenForRefunding` → `LoanContract.reopenForRefunding` + `LoanFundingReopenServiceImpl.reopen` (`LoanApplication.reopenFunding`, outbox) |
| Hết hạn ký ở bước nhà đầu tư | `LoanContractExpiryWorker` → `LoanContractStateServiceImpl.expireOne` → `expireOrReopenIfDue` → `ContractParty.expireUnsigned` → `reopenForRefunding` |
| Investment mở lại | `LoanFundingReopenedConsumer` → `LoanFundingReopenedHandlerImpl.handle`; nhả tiền: `FundingMaintenanceService.releasePendingHolds` |
| Chặn hủy phần vốn đã gắn | `OrderTransactionServiceImpl.cancelOrder` (`COMMITMENT_BOUND_TO_CONTRACT`), `PortfolioServiceImpl` (`cancelAllowed`) |
| Đủ vốn lại | `OrderTransactionServiceImpl.confirmCommitment` (version kế tiếp, gắn `contract_bound_at`) → `LoanFullyFundedHandlerImpl` → `LoanContractCreationServiceImpl.createFunded` → `reissueFunded` → `carryOverPadesSignatures` |
| Hết hạn gọi vốn bổ sung | `LoanFundingExpiredHandlerImpl` → `LoanContractStateServiceImpl.closeRefundingOnFundingExpired` |
| Người vay rút khi gọi vốn bổ sung | `LoanApplicationServiceImpl.withdraw` → `closeRefundingOnBorrowerWithdrawal` + `LoanFundingCancellationService.cancel(BORROWER_WITHDREW)`; từ chối Contract → `LoanContractStateServiceImpl.decline` → `recordClosedWithoutSignature(whileRefunding=true)` |
| Thông báo | `FundingEventsHandler.fundingReopened` (3 template mới) |
| Lịch sử xử lý | `GET /api/v1/admin/loan-applications/{n}/review` → `AdminLoanDecisionServiceImpl.reviewDetail` → `LoanApplicationTimelineServiceImpl.timeline` (trường `timeline`); web `HistoryTab` + `mappers/timelineDisplay.ts` |
| Mobile | `PENDING_REFUNDING` trong `types/contract.ts`, `CONTRACT_STATUS`, thẻ/ màn hợp đồng nhà đầu tư, `PendingCommitmentCard` |

## 4. Kiểm chứng

- Unit: Loan 481+ test xanh (thêm `LoanContractTest`, `ContractPartyTest`, `LoanApplicationDecisionTest`); Investment
  126 test xanh; Notification xanh. `npx tsc --noEmit` web và mobile sạch.
- IT đã viết, **chưa chạy** (cần Docker): `FinoraLoanApplicationIT.expiredLenderWindowReleasesUnsignedLendersAndReopensFundingOnce`,
  `investorDeclineKeepsSignedLenderAndRefundingReissuesContract`; `FundingFlowIT.investorWithdrawalReopensListingAndKeepsOtherCommitments`;
  `NotificationFlowIT` (ca `LoanFundingReopened`).

## 5. Giới hạn

- Lịch sử xử lý không có sự kiện chỉ nằm ở Investment (từng lệnh góp vốn mới trong đợt bổ sung) vì Loan không đọc DB
  service khác; mốc "phát hành lại" và chữ ký của nhà đầu tư mới thể hiện đợt bổ sung.
- Yêu cầu cơ cấu chỉ lưu trạng thái hiện tại: mốc kết quả lấy `updated_at` của yêu cầu.
- Nhà đầu tư dùng SmartCA đã ký phải ký lại sau khi phát hành lại.
