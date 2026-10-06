# Runbook E2E — giải ngân và phục vụ khoản vay FINORA

## 1. Mục tiêu

Runbook này kiểm tra một business key xuyên Loan, Payment, Fineract, Kafka và Investment. Không kiểm
tra bằng cách nhìn riêng từng màn hình. Mỗi case phải lưu lại `loanApplicationId`, `loanNumber`,
`contractNumber`, `fineractLoanId`, `repaymentId`, `repaymentReference` và các event ID liên quan.

## 2. Điều kiện bắt đầu

- Fineract, Kafka, Keycloak và database đang healthy; Loan/Payment/Investment chạy đúng cấu hình local.
- Product mới dùng `coreConfigVersion=FINORA-FINERACT-V2`. Khoản vay V1 cũ không bị đổi strategy.
- Product V2 trên Fineract phải có `advanced-payment-allocation-strategy`, `DEFAULT`, đủ 12 rule và
  `futureInstallmentAllocationRule=REAMORTIZATION`; `loanScheduleType` bắt buộc là `PROGRESSIVE`.
- Borrower và investor có ví đúng currency; Note ownership ở Payment khớp Investment.
- Không ghi token, secret, CCCD hoặc raw provider response vào evidence/log.

## 3. Happy case trả đúng kỳ

1. Hoàn tất funding, hai bên ký và giải ngân; xác nhận Loan `ACTIVE`, Payment loan account `ACTIVE`,
   Fineract loan active và Investment Note đã phát hành.
2. Borrower đọc `GET /api/v1/loans/{loanNumber}`. Response phải có `source`, `dataAsOf`,
   `lastSyncedAt`, `stale=false` sau lần sync thành công.
3. Nạp đủ tiền mock/sandbox vào ví borrower.
4. Gọi `POST /api/v1/repayments` với một `Idempotency-Key` mới, đúng số tiền template Fineract.
5. Chờ worker hoàn tất `COLLECTED → CORE_POSTING → CORE_POSTED → COMPLETED`.
6. Xác nhận đúng một Fineract transaction có external ID `FINORA-REPAY-{repaymentId}`.
7. Kiểm tra ledger cân: borrower debit = investor credits + platform charges; clearing về 0 cho lần trả.
8. Kiểm tra `RepaymentDistributed.v1` đúng một lần, Loan projection giảm dư nợ và Investment Note giảm
   principal đúng breakdown.
9. Gửi lại cùng key/body: trả kết quả cũ; cùng key khác body: `409`; không có ledger/event thứ hai.

## 4. Kết quả core chưa xác định

1. Mô phỏng mất kết nối sau khi Fineract có thể đã nhận lệnh.
2. Repayment phải ở `RECONCILIATION_REQUIRED`; tiền ở clearing, borrower không bị debit lần hai.
3. Admin xem `GET /api/v1/admin/repayment-reconciliation` và gọi
   `POST /api/v1/admin/repayment-reconciliation/{repaymentId}/reconcile`.
4. Endpoint chỉ GET/tra external reference. Nếu tìm thấy transaction thì tiếp tục phân phối; nếu chưa
   tìm thấy vẫn giữ trạng thái cần đối soát, tuyệt đối không POST repayment mới.

## 5. Projection stale và đối soát Loan

1. Dừng Fineract hoặc trả lỗi GET servicing; worker phải giữ số cũ, đặt `stale=true`.
2. Admin xem `GET /api/v1/admin/loan-servicing-reconciliation`.
3. Khôi phục Fineract và gọi
   `POST /api/v1/admin/loan-servicing-reconciliation/{loanNumber}/reconcile`.
4. Xác nhận endpoint chỉ đọc Fineract, projection có `source=FINERACT_SERVICING_SYNC`, thời gian mới và
   `stale=false`; không tạo repayment/disbursement/event tiền ngoài transition thật.

### Sai lệch định danh/tài chính

1. Cho fixture Fineract trả sai `externalId`, principal giải ngân hoặc tổng breakdown.
2. Gọi reconcile phải trả `SERVICING_RECONCILIATION_REQUIRED`; projection vẫn `stale=true` và không
   nhận số tiền sai.
3. Admin xem `GET /api/v1/admin/loan-servicing-reconciliation/incidents`; gọi lặp cùng lỗi chỉ tăng
   `occurrenceCount`.
4. Sửa fixture nguồn rồi reconcile lại; incident chuyển `RESOLVED/CORE_SNAPSHOT_VALIDATED` và projection
   mới được refresh.

## 6. Các cơ chế nghiệp vụ

### Khắc phục quá hạn

- Tạo kỳ quá hạn trên core; sync phải mở/cập nhật đúng một collection episode.
- Trả thiếu chỉ giảm overdue, không đặt DPD về 0.
- Trả đủ phần quá hạn phát `LoanDelinquencyChanged.v1` về DPD 0; CIC mock thêm version nhóm 1.

### Tất toán sớm toàn bộ

- Tạo quote bằng GET template `prepayLoan`; thao tác này không được tạo Fineract transaction.
- Quote hết hạn/đã dùng hoặc ví thiếu tiền không debit.
- Xác nhận thu `coreAmount + platformFee`, chỉ gửi `coreAmount` vào Fineract.
- Khi `totalOutstanding=0`: Payment account, Loan và Note đóng idempotently.

### Cơ cấu/gia hạn

- Request borrower chỉ `PENDING_REVIEW`, lịch cũ tiếp tục hiệu lực.
- Admin từ chối không đổi lịch. Admin duyệt mới chạy durable command Fineract; chỉ khi core xác nhận mới
  phát `LoanRescheduled.v1` và quay khoản vay về `ACTIVE`.

### Default/thu hồi

- DPD 1 mở collection; stage chỉ dẫn xuất từ DPD.
- DPD 91 chuyển `DEFAULTED`; cure về 0 chuyển lại `ACTIVE`; dư nợ 0 chuyển `SETTLED`.
- Action admin có audit/idempotency, không có API sửa tay balance, DPD hoặc debt group.

### Trả trước một phần

- Tạo quote qua `POST /api/v1/repayments/partial-prepayment-quotes`; V1 phải bị từ chối trước debit ví.
- Xác nhận qua `POST /api/v1/repayments/partial-prepayment` với key mới; gửi lại cùng key không thu lần hai.
- Xác nhận ví debit `scheduledDue + prepaidPrincipal + platformFee`, nhưng Fineract chỉ nhận
  `scheduledDue + prepaidPrincipal`.
- Đọc lại core: principal giảm đúng phần trả thêm, schedule được `REAMORTIZATION`; event phân phối làm
  Note giảm đúng principal. Quote không tuyên bố lịch giả định “sau trả” vì Fineract 1.15 không cung cấp.

## 7. CIC, AI và thời gian phục hồi nợ xấu

Mỗi ca phải lưu `sourceEventId`, borrower ID, CIC profile version và response AI; không chỉnh sửa dòng
lịch sử CIC đã tồn tại để ép kết quả.

1. Phát `LoanDelinquencyChanged.v1` đưa khoản vay từ nhóm 1 lên nhóm 3. CIC phải tạo đúng một version,
   đặt cả `nhomNoHienTai=3` và `nhomNoCaoNhat>=3`.
2. Giao lại cùng event ID: không tạo version thứ hai.
3. Phát event mới cure về nhóm 1: `nhomNoHienTai=1`, nhưng `nhomNoCaoNhat` không giảm; ghi ngày khắc
   phục, ngày hết khóa và ngày hết giai đoạn thẩm định thủ công.
4. Chấm hồ sơ tại bốn thời điểm: đang nhóm 3–5, trước ngày hết khóa, sau ngày hết khóa nhưng trước ngày
   hết thẩm định và sau toàn bộ giai đoạn phục hồi. Kết quả lần lượt phải là `REJECTED`, `REJECTED`,
   `PENDING_REVIEW` và chấm bình thường.
5. Hồ sơ có lịch sử nhưng thiếu `nhomNoHienTai` phải `PENDING_REVIEW`, không tự phê duyệt.
6. Kiểm tra `review_reasons` tách khỏi `rejection_reasons`; Loan không biến lý do thẩm định thủ công
   thành lý do từ chối.

Mốc 12/24 tháng lấy từ env CIC và là policy nội bộ FINORA. Evidence phải ghi giá trị env thực tế thay
vì giả định mọi môi trường đều dùng mặc định.

## 8. Investment risk projection và Notification

1. Với một investor đang sở hữu Note, phát event DPD 1 rồi đọc portfolio: Investment phải trả đúng DPD,
   nhóm, số tiền quá hạn và `riskDataAsOf` trên đúng loan.
2. Phát một event mới hơn rồi giao event cũ: snapshot không được lùi; `riskDataAsOf` giữ mốc mới nhất.
3. Đổi nhóm và cure: Investment cập nhật projection trong cùng transaction với `processed_events`, sau
   đó outbox có `InvestorNoteServicingChanged.v1`.
4. Giao lại event nguồn: không có side effect/outbox/notification thứ hai.
5. Một investor giữ nhiều Note của cùng loan: Notification chỉ lưu một bản ghi cho cùng
   `sourceEventId + recipientId + type`.
6. DPD 1 không đổi nhóm: có in-app notification, `externalPushRequired=false`. Đổi nhóm, cure, cơ cấu,
   tất toán hoặc tất toán sớm: `externalPushRequired=true`.
7. Dùng JWT investor A gọi list/count/read: chỉ thao tác bản ghi của A. Dùng JWT A với ID của B phải bị
   từ chối hoặc không tìm thấy; bản ghi B không đổi.
8. Event/payload không được chứa CCCD, OTP, email, token hoặc tài liệu hợp đồng.

Đối chiếu UI tương ứng tại
[`finora-mobile/docs/LOAN-SERVICING-UI-TEST.md`](../../../finora-mobile/docs/LOAN-SERVICING-UI-TEST.md),
TC09–TC14. `externalPushRequired` chỉ là intent; push hệ điều hành không được tính pass cho tới khi có
device token, provider receipt và cơ chế retry riêng.

## 9. Evidence local 2026-10-04

- Fineract Product ID 2 đã đọc lại với strategy advanced, 12 rule, `PROGRESSIVE` và `REAMORTIZATION`.
- Loan ID 1: giải ngân 50.000.000 VND, repayment external ID `FINORA-V2-E2E-PARTIAL-01` là
  5.000.000 VND; core trả principal portion 5.000.000, outstanding principal 45.000.000 và phân bổ
  khoảng 833.000 VND gốc vào từng kỳ còn lại.
- Kafka local topic `finora.e2e.smoke.20261004` publish/consume thành công envelope v1 có business key
  `V2-PARTIAL-PREPAYMENT`.

## 10. Lệnh regression

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\plugins\maven\lib\maven3\bin\mvn.cmd' -pl finora-loan verify
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\plugins\maven\lib\maven3\bin\mvn.cmd' -pl finora-payment verify
```

Pass khi unit, integration, Flyway empty-schema và upgrade-schema đều xanh; live Kafka/Fineract evidence
phải dùng cùng business keys ở mục 1.
