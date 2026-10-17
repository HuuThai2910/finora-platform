---
task_id: W3F
title: Thông báo hoàn chỉnh trong app và Expo Push (R7)
owner: Hai
status: READY_FOR_REVIEW
depends_on: [R3, R4-W3]
created_at: 2026-10-17
updated_at: 2026-10-17
---

# W3F — Thông báo trong app và Expo Push (R7)

Plan Wave 3 cho R7 của [`finora-remaining-work-plan.md`](finora-remaining-work-plan.md), theo D19/Q11 (thông báo trong
app + Expo Push) và D23 (agent tự chọn phương án, ghi quyết định). `finora-notification` là module của Hải: **Hải review
trước merge**. Mobile: `finora-mobile/plans/MOBILE-NOTIFY-001-push-preferences.md` (nhánh `feat/w3f-push`).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Trước W3F, Notification chỉ nhận một event (`InvestorNoteServicingChanged`) và chỉ báo cho nhà đầu tư trong app. Người vay
không nhận tin nào: không biết hồ sơ đã đủ vốn, đến lượt ký (B24), tiền đã vào ví, kỳ sắp đến hạn hay khoản vay đã quá
hạn. Không có thông báo đẩy dù có cờ `externalPushRequired`. Nhà đầu tư lại bị nhận một tin "đang quá hạn" mỗi ngày khoản
vay còn quá hạn (B60).

W3F làm ba việc: (1) nhận các event nghiệp vụ đã có của Loan, Investment, Payment và tạo thông báo đúng người, đúng mốc;
(2) gửi Expo Push có che nội dung màn hình khóa, theo lựa chọn của người dùng, có retry/dead-letter; (3) chuẩn bị nhắc kỳ
đến hạn không làm phiền người đã trả trước.

### Ai tham gia và nhìn thấy gì?

| Actor | Nhìn thấy trong app (đầy đủ) | Trên màn hình khóa (push, đã che) |
|---|---|---|
| Người vay | Hồ sơ lên sàn, đủ vốn, hết hạn/hủy gọi vốn, đến lượt ký, hợp đồng hiệu lực, tiền giải ngân, khoản trả đã ghi nhận + kỳ tiếp theo, quá hạn theo mốc, nợ xấu, đã khắc phục, cơ cấu (thẩm định đạt, kết quả biểu quyết, lịch mới), tất toán, rút tiền | "Khoản vay của bạn đã được giải ngân.", "Bạn có thông báo quan trọng về khoản vay…" — không số tiền, không mã khoản vay |
| Nhà đầu tư | Mời ký, hợp đồng hiệu lực, Note phát hành, khoản đầu tư bị hủy (hoàn tiền giữ), tiền trả nợ về (cũ), tất toán, cơ cấu, rủi ro theo mốc (cũ, đã lọc tin hằng ngày), rút tiền | Câu chung tương ứng |
| Admin | Màn vận hành: push DEAD/FAILED (gửi lại), event chờ người nhận đã hết hạn | — |

### Luồng chính và luồng không thành công

```text
Event đã commit ở Loan/Investment/Payment (outbox)
  → Kafka → Notification (group notification-domain-events)
  → đã xử lý eventId? → bỏ qua
  → tìm người nhận (trong event, nếu thiếu thì bảng tra theo khoản vay)
      ├─ đủ  → thông báo trong app + lịch push (theo thiết bị, preference, giờ nghỉ) → commit cùng dấu đã xử lý
      └─ thiếu (event khác topic chưa tới) → bảng chờ, worker thử lại tới 24 giờ
  → worker push: Expo (hoặc bản giả lập LOG) → SENT → receipt → CONFIRMED
      ├─ lỗi tạm thời → thử lại 1, 2, 4, 8, 16 phút → lần 6 vẫn lỗi → DEAD (admin gửi lại)
      └─ DeviceNotRegistered → FAILED + thu hồi thiết bị
```

Push hay Kafka lỗi không bao giờ đổi state nghiệp vụ của service nguồn: Notification chỉ là consumer, mọi thay đổi nằm
trong database của Notification. Payload sai contract vào DLT riêng `<topic>.notification.DLT`, không đoán người nhận.

### Dữ liệu nào ảnh hưởng kết quả?

- `eventId` của envelope: chống xử lý trùng.
- `borrowerId`/`investorId`/`ownerId` (claim `user_id`): người nhận. Event thiếu thì lấy từ bảng tra dựng từ
  `LoanFundingRequested` (người vay) và `LoanFullyFunded.v2` (nhà đầu tư của allocation).
- DPD và nhóm nợ trước/sau (`LoanDelinquencyChanged`): quyết định có báo quá hạn không (chỉ khi đổi đợt/nhóm).
- `externalPushRequired` (Investment): có push tin servicing của nhà đầu tư không.
- `nextDueDate/nextDueAmount` (`RepaymentDistributed`): lịch nhắc; khoản trả kỳ đang giữ (đề xuất `RepaymentCollected`):
  không nhắc kỳ đó.
- Preference theo nhóm + thiết bị đang hoạt động + tuổi event + giờ nghỉ: có tạo/ lúc nào gửi push.

### Ví dụ thực tế

Chị Lan vay 10 triệu. 00:05 ngày 18/11 (job cuối ngày Fineract) khoản vay chuyển DPD 1: Notification tạo tin "Khoản vay
LN-2026-000101 đã quá hạn 1 ngày, số tiền quá hạn 1.726.000 đ" trong app ngay, push "Bạn có thông báo quan trọng về
khoản vay" được hẹn 07:00. Ngày 19–26/11 DPD tăng mỗi ngày: không tin nào. Ngày 27/11 DPD 10, nhóm 2: một tin "chuyển
nhóm nợ 2 (cần chú ý)". Chị trả đủ phần quá hạn: tin "đã hết quá hạn". Cả đợt: 3 tin thay vì 10.

### Điều kiện hoàn thành nghiệp vụ

- Mỗi event chỉ sinh tối đa một thông báo cho mỗi người nhận mỗi loại, kể cả khi Kafka giao lại.
- Không có tin quá hạn hằng ngày; push không lộ số tiền/tên/mã khoản vay.
- Thiết bị đăng xuất hoặc Expo báo `DeviceNotRegistered` không nhận push nữa.
- Push lỗi tạm thời được thử lại có giới hạn rồi vào trạng thái chết có thể gửi lại.

## 1. Current state, phạm vi và ngoài phạm vi

**Trước W3F (đọc code `ca9efa2`):** `InvestorNoteServicingChangedConsumer` → `InvestorServicingNotificationService`
lưu `in_app_notifications` (V1, unique `source_event_id + recipient_id + type`); REST list/unread/read; email nội bộ
(OTP/chào mừng/cảnh báo) với role `notification:internal:send` (D36). Không có processed-event, error handler/DLT,
push, preference, scheduler hay test ngoài một WebMvc test bảo mật.

**Trong phạm vi (đã làm):** consumer cho 16 topic đã có + 2 topic rút tiền Wave 3, bảng tra người nhận, event chờ người
nhận, mẫu thông báo có version, preference, vòng đời thiết bị, Expo Push (giả lập mặc định + Expo thật theo cấu hình),
retry/DEAD/receipt, API người dùng và vận hành, lịch nhắc kỳ đến hạn (tắt mặc định, chờ P5), mobile đăng ký push + màn
cài đặt.

**Ngoài phạm vi:** sửa producer Loan/Payment/Investment (liệt kê ở mục 13 cho main agent); email/SMS cho event nghiệp vụ
(Q11 chỉ chọn in-app + push; email cần PII của User); hộp thư admin cho sự cố đối soát (thiết kế ở P9); E2E harness;
gửi push tới thiết bị thật (không có máy).

## 2. Ownership, SoR và dependency

| Dữ liệu | SoR | Notification giữ |
|---|---|---|
| Hồ sơ/khoản vay, người vay, lịch servicing | Loan + Fineract | `loanApplicationId`, `borrowerId` logic, mã công khai (bảng tra, chỉ để báo) |
| Allocation, Note, chủ Note | Investment | `investorId` của allocation đã khóa (chỉ cho bước trước giải ngân) |
| Ví, khoản trả, rút tiền | Payment | Không giữ số dư; kỳ tiếp theo/khoản giữ chỉ để nhắc lịch |
| Delivery (in-app, push), thiết bị, preference | **Notification** | Toàn bộ |

Notification không gọi REST service khác, không đọc DB chéo (07-service-boundaries). Gateway đã route
`/api/v1/notifications/**` về Notification; API vận hành đặt dưới `/api/v1/notifications/admin/**` để không cần route mới
(route `/api/v1/admin/**` thuộc Loan).

## 3. Ma trận actor × event

`Đã làm` = chạy với event hiện hành; `Đã làm*` = chạy được nhờ bảng tra người nhận, sẽ chính xác hơn khi producer bổ sung
field ở mục 13; `Cần producer` = consumer/mẫu chưa thể chạy vì event chưa tồn tại hoặc thiếu người nhận. Cập nhật
2026-10-17: producer đã làm P1–P7 (mục 13), các ô ghi "(Px)" dưới đây không còn phụ thuộc bảng tra; P8, P9 còn thiết kế.

| Mốc | Event (topic) | Người vay | Nhà đầu tư | Admin | Ghi chú |
|---|---|---|---|---|---|
| Lên sàn gọi vốn | `LoanFundingRequested.v1` | Đã làm (`LOAN_FUNDING_OPENED`) | — | — | Nguồn `borrowerId` cho bảng tra; producer cũ không có field → không báo |
| Đủ vốn | `LoanFullyFunded.v2` | Đã làm* (`LOAN_FULLY_FUNDED`) | — (gộp vào mời ký) | — | Nguồn danh sách nhà đầu tư của allocation |
| Hết hạn gọi vốn | `LoanFundingExpired.v1` | Đã làm* | Đã làm (P6: `investorIds`, `INVESTMENT_FUNDING_EXPIRED`) | — | Người vay vẫn qua bảng tra (event không mang `borrowerId`) |
| Hủy sau khi đủ vốn | `LoanFundingCancelled.v1` | Đã làm (P2) | Đã làm* (`INVESTMENT_FUNDING_CANCELLED`) | — | Lý do: từ chối ký / hết hạn ký / giải ngân lỗi |
| Mời nhà đầu tư ký | `InvestorSignatureRequested.v1` | — | Đã làm* (theo `allocationVersion`) | — | |
| Đến lượt người vay ký | `BorrowerSignatureRequested.v1` | Đã làm (sửa B24, P1 `borrowerId`) | — | — | |
| Hợp đồng hiệu lực | `LoanContractActivated.v1` | Đã làm (P2) | Đã làm* | — | |
| Giải ngân / phát hành Note | `LoanDisbursed.v1` | Đã làm (`LOAN_DISBURSED`, P2) | Đã làm* (`NOTES_ISSUED`) | — | P3: kỳ đầu từ Fineract, nhắc được từ kỳ 1 |
| Kỳ sắp đến hạn / đến hạn hôm nay | Worker + `RepaymentDistributed.v1` | Đã làm, **tắt mặc định** (D64) | — | — | P5 đã có; owner quyết định bật `NOTIFICATION_DUE_REMINDER_ENABLED` |
| Đã nhận tiền trả kỳ trước hạn / hủy | `RepaymentCollected.v1`, `RepaymentCancelled.v1` | Đã làm (P5, Payment phát từ 2026-10-17); listener chỉ chạy khi bật nhắc lịch | — | — | `INSTALLMENT_PAYMENT_HELD`/`INSTALLMENT_PAYMENT_CANCELLED` |
| Khoản trả đã ghi nhận | `RepaymentDistributed.v1` | Đã làm (`REPAYMENT_RECORDED`, P4) | Đã có (Investment `REPAYMENT_CREDITED`) | — | Trả hết nợ: trong app, không push (LoanSettled push) |
| Quá hạn theo mốc | `LoanDelinquencyChanged.v1` | Đã làm (D61) | Đã có, lọc tin hằng ngày (B60) | — | |
| Nợ xấu (default) / khắc phục (cure) | `LoanDelinquencyChanged.v1` | Đã làm (`LOAN_DEFAULTED`, `OVERDUE_CURED`) | Đã có (`BAD_DEBT_MILESTONE`, `DELINQUENCY_CURED`) | — | |
| Cơ cấu: thẩm định đạt | `LoanRescheduleConsentRequested.v1` | Đã làm (P2) | Đã làm (P7: Investment phát `RESCHEDULE_CONSENT_REQUESTED`, có push) | — | Mỗi chủ phiếu một tin |
| Cơ cấu: admin từ chối | (chưa có event) | Cần producer (P8) | — | — | |
| Cơ cấu: kết quả biểu quyết | `LoanRescheduleConsentCompleted.v1` | Đã làm* | — | — | |
| Cơ cấu: lịch mới | `LoanRescheduled.v1` | Đã làm | Đã có (`RESCHEDULED`) | — | P3: kỳ tiếp theo của lịch mới |
| Tất toán | `LoanSettled.v1` | Đã làm | Đã có (`SETTLED`/`EARLY_SETTLEMENT`) | — | Đóng lịch nhắc |
| Rút tiền | `WithdrawalCompleted.v1`, `WithdrawalFailed.v1` | Đã làm | Đã làm | — | Producer Wave 3 của main agent |
| Sự cố đối soát cần người | (chưa có event) | — | — | Cần producer (P9) | Thiết kế hộp thư admin |

## 4. Entity, projection và field

| Bảng (migration) | Loại | Vai trò |
|---|---|---|
| `in_app_notifications` (V1, V2 thêm `category`, `template_version`, `updated_at`) | Entity, delivery kênh trong app | Hộp thư, bản đầy đủ |
| `notification_processed_events` (V2) | Dấu idempotency | `eventId` đã xử lý + kết quả |
| `notification_deferred_events` (V2) | Command bền vững | Event chờ người nhận, có payload `data` (không PII) |
| `notification_loan_references` (V2) | Read projection | Người vay + mã công khai theo khoản vay |
| `notification_loan_lenders` (V2) | Read projection | Nhà đầu tư của allocation đã khóa |
| `notification_push_devices` (V3) | Entity | Expo token theo người dùng |
| `notification_preferences` (V3) | Entity | Push bật/tắt theo nhóm |
| `notification_push_deliveries` (V3) | Entity, delivery kênh push | Mỗi thông báo × thiết bị |
| `notification_due_schedules` (V4) | Read projection | Kỳ tiếp theo + khoản trả đang giữ |

Field quan trọng (lớp nghiệp vụ):

| Dữ liệu | Người dùng hiểu là | Nguồn | Dùng ở | Nếu thiếu/sai | Vì sao lưu |
|---|---|---|---|---|---|
| `in_app_notifications.type` | Loại tin | Mẫu (`NotificationTemplate`) hoặc `changeType` của Investment | App chọn biểu tượng; khóa chống trùng | — | Không có thì không chặn được tin trùng |
| `category` | Nhóm tin (cài đặt) | Mẫu | Preference push, app | Rơi về `GENERAL` | Áp lựa chọn của người dùng |
| `notification_loan_references.borrower_id` | Người vay của khoản vay | `LoanFundingRequested`, `LoanDelinquencyChanged`, `LoanSettled`, `LoanRescheduled`, field optional P1–P4 | Mọi tin người vay khi event không mang người vay | Event chờ tối đa 24 giờ rồi bỏ | Event Loan/Payment thiếu `borrowerId` (B64) |
| `notification_loan_lenders.investor_id` | Nhà đầu tư đã góp vốn | `LoanFullyFunded.v2` | Mời ký, hiệu lực, hủy, Note phát hành | Event chờ | Event Contract/Disbursed không mang nhà đầu tư |
| `notification_push_devices.token` | (không hiển thị) | App (`expo-notifications`) | Gửi push | Không push | Bắt buộc để gửi; xóa khi thu hồi |
| `token_hash` | — | SHA-256 của token | Log/vận hành (`tokenRef` = 12 ký tự đầu) | — | Đối chiếu mà không lộ token |
| `push_deliveries.title/body` | Câu trên màn hình khóa | Mẫu (đã che) | Gửi push | — | Bằng chứng đã gửi gì; không có PII |
| `due_schedules.held_due_date` | Kỳ đã trả trước, Payment đang giữ | P5 `RepaymentCollected` | Không nhắc kỳ đó | Có thể nhắc nhầm (nên nhắc lịch tắt mặc định) | D16 làm Fineract chưa biết khoản đã trả |

Lớp kỹ thuật chính:

| Field | Kiểu | Constraint | Ghi bởi | Đọc bởi |
|---|---|---|---|---|
| `processed_events.event_id` | UUID | unique | Intake (cùng TX thông báo) | Intake |
| `deferred_events.status/next_attempt_at` | VARCHAR/TIMESTAMPTZ | CHECK `WAITING|EXPIRED`, index | Intake, worker | Worker |
| `push_devices.token` | VARCHAR(255) NULL | unique; CHECK ACTIVE ⇔ token NOT NULL | `PushDeviceService` | Intake, dispatch |
| `push_deliveries (notification_id, device_id)` | UUID, UUID | unique, FK | Intake | Dispatch |
| `push_deliveries.attempt_count` | INTEGER | — | Dispatch (claim token) | Ghi kết quả |
| `due_schedules.version` | BIGINT | `@Version` | Handler | Handler |

## 5. ERD và cardinality

```text
notification_loan_references 1 ── 0..N notification_loan_lenders        (FK, thay cả bộ khi allocation mới)
            │ loan_application_id (logic, không FK sang Loan)
            ▼
notification_due_schedules 0..1 (cùng khóa loan_application_id, logic)

in_app_notifications 1 ── 0..N notification_push_deliveries 0..N ── 1 notification_push_devices
      (FK notification_id)              (FK device_id)        user_id (logic)
notification_preferences: user_id × category  (unique, logic với user)
notification_processed_events / notification_deferred_events: theo event_id (unique), không FK
```

| Quan hệ | Vì sao | Database bảo vệ | Lifecycle |
|---|---|---|---|
| Reference → Lender `1:0..N` | Một khoản vay có nhiều nhà đầu tư, chỉ allocation mới nhất có hiệu lực | FK + unique `(loan, investor)` | Allocation mới xóa bộ cũ |
| Notification → Push delivery `1:0..N` | Một tin gửi tới mọi thiết bị đang hoạt động | FK + unique `(notification, device)` | Không xóa (lịch sử) |
| Device → Push delivery `1:0..N` | Thiết bị nhận nhiều tin | FK | Thiết bị thu hồi giữ dòng (token NULL) |
| User → Device `1:0..5` | Tối đa 5 thiết bị đang hoạt động | Kiểm trong service (khóa dòng) | Vượt thì thu hồi thiết bị cũ nhất |
| Reference ↔ Due schedule | Cùng khoản vay | Logic (cùng khóa) | Đóng khi tất toán |

ID xuyên service (`loan_application_id`, `user_id`) là quan hệ logic qua event, không FK.

## 6. State machine và invariant

```text
Push delivery: PENDING → SENDING → SENT → CONFIRMED
                 ▲          ├→ PENDING (lỗi tạm thời, lùi dần) … lần thứ 6 → DEAD
                 │          ├→ FAILED (DeviceNotRegistered → thu hồi thiết bị; MessageTooBig; HTTP 4xx)
                 │          └→ SKIPPED (thiết bị đã thu hồi lúc claim)
                 └── admin requeue (DEAD/FAILED, thiết bị còn hoạt động)
SENT → (receipt lỗi vượt tần suất) PENDING; (không có receipt sau 6 lần) giữ SENT, thôi hỏi
Device: ACTIVE → REVOKED (LOGOUT | DEVICE_NOT_REGISTERED | REASSIGNED | DEVICE_LIMIT), không quay lại; đăng ký lại = dòng mới
Deferred event: WAITING → (đủ người nhận) xóa dòng; WAITING → (24 giờ) EXPIRED (gửi phần đã biết)
Due schedule: ACTIVE ↔ (kỳ mới) ACTIVE → CLOSED (tất toán/không còn kỳ)
```

Invariant: (1) một event ≤ một thông báo mỗi `(người nhận, loại)`; (2) một thông báo ≤ một push mỗi thiết bị; (3) push không
chứa chữ số (test `NotificationTemplateTest`); (4) thiết bị REVOKED không có token; (5) người vay của một khoản vay không đổi
— event báo người khác thì fail closed (DLT); (6) không xử lý gì làm đổi state của service khác.

## 7. API, event và worker

REST (JWT người dùng; người nhận/chủ thiết bị luôn từ claim `user_id`):

| Method | Path | Kết quả |
|---|---|---|
| GET | `/api/v1/notifications?limit=1..100` | Mảng như cũ + `category` (E2E/mobile tương thích) |
| GET | `/api/v1/notifications/unread-count` | Như cũ |
| POST | `/api/v1/notifications/{id}/read` | Như cũ |
| POST | `/api/v1/notifications/read-all` | `{updated}` |
| POST | `/api/v1/notifications/devices` | Body `{token, platform, appVersion?}` → `{deviceId, status, platform, tokenRef, lastRegisteredAt}`; rate limit 20/10 phút; lỗi validation không lặp token |
| DELETE | `/api/v1/notifications/devices/{deviceId}` | 204 luôn; chỉ gỡ thiết bị của mình |
| GET/PUT | `/api/v1/notifications/preferences` | `{categories:[{category,pushEnabled,inAppEnabled}]}`; PUT `{preferences:[{category,pushEnabled}]}` |
| GET | `/api/v1/notifications/admin/push-deliveries?status=DEAD&page&size≤100` | ADMIN |
| POST | `/api/v1/notifications/admin/push-deliveries/{id}/requeue` | ADMIN; 409 nếu không DEAD/FAILED hoặc thiết bị đã thu hồi |
| GET | `/api/v1/notifications/admin/deferred-events?status=EXPIRED` | ADMIN, không trả payload |

Kafka (envelope `{eventId, occurredAt, version, data}`, header `finora-event-*` nếu có phải khớp; mỗi topic một loại event):

| Group | Topic | Event/version |
|---|---|---|
| `notification-domain-events` (mới) | `finora.loan.funding-requested`, `finora.investment.loan-fully-funded` (v2), `finora.investment.loan-funding-expired`, `finora.loan.funding-cancelled`, `finora.loan.investor-signature-requested`, `finora.loan.borrower-signature-requested`, `finora.loan.contract-activated`, `finora.loan.disbursed`, `finora.payment.repayment-distributed`, `finora.loan.delinquency-changed`, `finora.loan.settled`, `finora.loan.reschedule-consent-requested`, `finora.investment.reschedule-consent-completed`, `finora.loan.rescheduled`, `finora.payment.withdrawal-completed`, `finora.payment.withdrawal-failed` | v1 trừ ghi chú |
| `notification-domain-events` (chỉ khi `NOTIFICATION_DUE_REMINDER_ENABLED=true`) | `finora.payment.repayment-collected`, `finora.payment.repayment-cancelled` | P5, đề xuất |
| `notification-investor-servicing` (giữ nguyên) | `finora.investment.note-servicing-changed` | v1 |

DLT: `<topic>.notification.DLT` (riêng Notification). Version cũ của loại đã biết (ví dụ `LoanFullyFunded` v1) được bỏ qua
có log, không DLT.

Worker (tắt toàn bộ bằng `NOTIFICATION_WORKERS_ENABLED=false` chỉ trong test): gửi push 5 giây/lần, receipt 60 giây,
event chờ 30 giây, nhắc lịch cron `0 5 8-20 * * *` giờ Việt Nam (chỉ khi bật). Scheduler 2 luồng.

Cấu hình chính: `NOTIFICATION_PUSH_PROVIDER=log|expo|disabled` (mặc định `log`, giả lập), `EXPO_ACCESS_TOKEN` (secret,
tùy chọn), `NOTIFICATION_PUSH_MAX_ATTEMPTS=6`, `..._RETRY_BACKOFF=1m`, `..._MAX_EVENT_AGE=24h`, `..._QUIET_HOURS_*`
(22:00–07:00), `..._MAX_DEVICES_PER_USER=5`, `NOTIFICATION_DUE_REMINDER_ENABLED=false`, `NOTIFICATION_DOMAIN_EVENTS_GROUP`.

## 8. Query và index

| Use case | Query | Index | Ghi chú |
|---|---|---|---|
| Hộp thư | `recipient_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ≤100` | `idx_notification_recipient_time` (V1) | Không đổi |
| Chưa đọc / đọc hết | `recipient_id = ? AND read_at IS NULL` | `idx_notification_recipient_unread` (V1) | `read-all` một câu UPDATE |
| Chống trùng thông báo | `source_event_id = ?` | prefix unique `uq_notification_delivery` | Một câu cho cả lô người nhận |
| Fan-out push | `user_id IN (...) AND status='ACTIVE'`; preference `user_id IN (...) AND category=?` | `idx_notification_push_devices_user_status`, `uq_notification_preferences_user_category` | Hai câu cho cả lô, không N+1 |
| Claim gửi | `status IN ('PENDING','SENDING') AND next_attempt_at <= now ORDER BY next_attempt_at, id LIMIT 100 FOR UPDATE SKIP LOCKED` | `idx_notification_push_deliveries_dispatch (status, next_attempt_at, id)` | Hai trạng thái = hai range scan |
| Claim receipt | `status='SENT' AND receipt_check_after <= now … LIMIT 300 SKIP LOCKED` | `idx_notification_push_deliveries_receipt` | |
| Vận hành push | `status=? ORDER BY updated_at DESC, id DESC` phân trang | `idx_notification_push_deliveries_status_updated` | |
| Event chờ | `status='WAITING' AND next_attempt_at <= now ORDER BY next_attempt_at, id LIMIT 50` | `idx_notification_deferred_events_status_next_attempt` | Màn vận hành deferred không thêm index: số dòng nhỏ |
| Nhắc lịch | `status='ACTIVE' AND next_due_date=? AND loan_application_id > ? ORDER BY loan_application_id LIMIT 200` | `idx_notification_due_schedules_status_due` | Keyset, nhãn khoản vay đọc một câu `findAllById` |
| Danh sách nhà đầu tư | `loan_application_id = ?` | prefix unique `uq_notification_loan_lenders_loan_investor` | |

## 9. Transaction, concurrency và idempotency

```text
TX intake (consumer hoặc worker event chờ):
  processed_events? → handler cập nhật bảng tra/lịch nhắc → in_app + push_deliveries + processed_events → commit
  (thiếu người nhận: deferred_events, chưa ghi processed) — không gọi mạng
TX claim push → commit; gọi Expo ngoài TX; TX ghi kết quả (so claim token)
TX đăng ký thiết bị: khóa dòng theo token → thu hồi/làm mới/chèn → giới hạn thiết bị (khóa dòng theo user)
TX nhắc lịch: mỗi lô 200 khoản vay một TX
```

| Đồng thời | Nguy cơ | Cơ chế | Kết quả |
|---|---|---|---|
| Kafka giao lại khi rebalance | Hai thông báo | unique `processed_events.event_id` + unique hộp thư | Lần sau vỡ unique → rollback → retry thấy DUPLICATE |
| Hai instance gửi push | Gửi trùng | `FOR UPDATE SKIP LOCKED` + lease 2 phút + claim token | Hai lô rời nhau (`concurrentDispatchWorkersClaimDisjointBatches`) |
| Worker chết sau khi Expo nhận | Push trùng | Lease hết → claim lại | Có thể trùng một lần (at-least-once, chấp nhận) |
| Hai lần đăng ký cùng token | Vỡ unique token | Khóa dòng theo token; vỡ unique thì thử lại một lần trong TX mới | Cùng `deviceId` |
| Hai listener sửa cùng khoản vay | Ghi đè bảng tra/lịch | `findForUpdate` (PESSIMISTIC_WRITE), `@Version` lịch nhắc | Một bên chờ hoặc lỗi lạc quan → Kafka retry |
| Hai worker event chờ | Xử lý đôi | `lockWaiting` SKIP LOCKED | Worker sau bỏ qua |
| Nhắc lịch chạy lại/hai instance | Hai lời nhắc | `sourceEventId` cố định theo (khoản vay, ngày, số ngày) + unique hộp thư | Một tin |

## 10. Lỗi ngoài hệ thống, retry và DLT

| Lỗi | Phân loại | Xử lý |
|---|---|---|
| Payload/envelope/header sai, người vay mâu thuẫn | Contract (IllegalArgumentException) | DLT `<topic>.notification.DLT` ngay, partition chạy tiếp (`NotificationKafkaIT`) |
| DB tạm lỗi trong consumer | Tạm thời | Lùi dần 1–30 giây, tối đa 3 phút, rồi DLT |
| Thiếu người nhận | Thứ tự event | Bảng chờ, 30 giây nhân đôi tới 30 phút, hết 24 giờ → EXPIRED |
| Expo 429/5xx/timeout/mất kết nối, `MessageRateExceeded`, `InvalidCredentials` | Tạm thời | 1, 2, 4, 8, 16 phút; lần 6 → DEAD |
| `DeviceNotRegistered` (ticket hoặc receipt) | Vĩnh viễn với thiết bị | FAILED + thu hồi thiết bị |
| `MessageTooBig`, HTTP 4xx khác | Vĩnh viễn | FAILED |
| Event cũ hơn 24 giờ (đọc dồn) | Chính sách | Chỉ hộp thư, không push |

Replay DLT: đọc `<topic>.notification.DLT`, sửa nguyên nhân, phát lại record vào topic gốc (eventId giữ nguyên nên an toàn).

## 11. Bảo mật, audit, logging và PII

- [`LEGAL-DATA-01`](../../docs/LEGAL-COMPLIANCE.md) (CURRENT): Notification chỉ giữ ID logic và mã công khai; event theo
  contract không có PII. Push không có số tiền/tên/mã khoản vay; dữ liệu đi kèm push chỉ `notificationId/type/category`.
  Android channel `PRIVATE` ẩn nội dung trên màn hình khóa bảo mật. Số tài khoản rút tiền chỉ ở dạng đã che từ Payment.
- Token: không log, không trả qua API (chỉ `tokenRef`), xóa khi thu hồi, `PushMessage.toString` và `Expo.toString` che giá
  trị. `EXPO_ACCESS_TOKEN` chỉ qua biến môi trường. Nên bật "enhanced push security" của Expo trước production (token
  Expo đơn lẻ đủ để gửi push nếu không bật).
- Phân quyền: API người dùng lấy chủ thể từ JWT; xóa thiết bị người khác không có tác dụng và không lộ tồn tại; API vận
  hành `ROLE_ADMIN`; requeue ghi log `actorId`.
- Cổng môi trường D37: `NOTIFICATION_PUSH_PROVIDER=log` là cấu hình giả lập, `staging/production` từ chối khởi động.
- Chưa có: retention cho hộp thư/thiết bị thu hồi/payload event chờ (R8B), mã hóa token khi lưu (R8B).
- Không có kết luận pháp luật mới; ngưỡng nhắc lịch, giờ nghỉ, số thiết bị là policy demo FINORA.

## 12. Migration, file và rollout

Migration: V2 (category/template_version/updated_at + processed/deferred/loan references/lenders), V3 (devices,
preferences, push deliveries), V4 (due schedules). Test nâng cấp từ V1 có dữ liệu (`legacyInboxRowsAreUpgradedByTheNewMigrations`).

Rollout: deploy với `NOTIFICATION_PUSH_PROVIDER=log` (mặc định) → group mới đọc topic từ đầu trong thời gian Kafka còn giữ
(dựng bảng tra; push của event cũ hơn 24 giờ bị chặn) → khi có EAS project và máy thật: `expo` → khi Payment có P5: bật
`NOTIFICATION_DUE_REMINDER_ENABLED`. Rủi ro: môi trường từng xóa DB Loan nhưng giữ Kafka có thể có `loanApplicationId` lặp
với người vay khác → event mâu thuẫn vào DLT (fail closed), không gửi nhầm; nên reset topic cùng DB ở stack E2E.

Cập nhật tài liệu chung cho main agent (Notification không sửa ngoài module): `05-registry.md` (group
`notification-domain-events`, consumer của 16 topic, DLT riêng), `docs/integrations/LOAN-INVESTMENT-EVENTS.md` (Notification
là consumer; `BorrowerSignatureRequested` hết "chưa đăng ký"), `08-cross-service-flows.md` F09 current state, plan tồn đọng
R7 + D60–D64 + B60–B64 và B24 Fixed, `finora-mobile/PLAN.md` dòng MOBILE-NOTIFY-001.

## 13. Thay đổi producer cần main agent làm

Tất cả là field **optional** (tương thích ngược, consumer cũ bỏ qua) hoặc event mới; Notification đã đọc sẵn (trừ P8, P9).

**Trạng thái 2026-10-17:** P1–P7 và P10 đã làm trên nhánh producer của main agent (commit `3d7bf82` Loan, `a0f3c0d` Payment,
`1c03819` Investment, `90763d5` test Notification, tài liệu chung cùng nhánh); P8, P9 giữ ở mức thiết kế. Mọi field mới giữ
version v1, ghi outbox cùng transaction với thay đổi nghiệp vụ, không PII. Quyết định D76–D78, defect B76 ở mục 14–15.

| ID | Producer (class) | Event | Field/việc | Lý do | Trạng thái và bằng chứng |
|---|---|---|---|---|---|
| P1 | Loan `InvestorContractServiceImpl.advanceContractIfAllLendersSigned` (`BorrowerSignatureRequestedEventData`) | `BorrowerSignatureRequested.v1` | `borrowerId` = `application.getBorrowerId()` | Người nhận nằm trong event, không phụ thuộc bảng tra (B24, B64) | **Đã làm** — `borrowerId` (String). `FinoraLoanApplicationIT` (payload outbox thật), `ProducerFieldAdditionsContractTest` |
| P2 | Loan `LoanContractStateService.activateMultiParty`, `DisbursementSagaService.complete`, `LoanFundingCancellationService.cancel`, `LoanRescheduleService.approve` | `LoanContractActivated.v1`, `LoanDisbursed.v1`, `LoanFundingCancelled.v1`, `LoanRescheduleConsentRequested.v1` | `borrowerId` (application/loan) | Như P1 | **Đã làm** — `borrowerId` (String) ở cả bốn event. `FinoraLoanApplicationIT` (activated, cancelled), `DisbursementSagaServiceTest`, `LoanRescheduleProducerFieldsTest` |
| P3 | Loan `DisbursementSagaService.complete` (cùng snapshot core đọc `maturityDate`, `FineractCoreLoanBookingGateway`); `LoanRescheduleExecutionService` (snapshot sau cơ cấu) | `LoanDisbursed.v1`, `LoanRescheduled.v1` | `nextDueDate` (date), `nextDueAmount` (decimal string) từ Fineract | Nhắc từ kỳ 1 và đúng kỳ sau cơ cấu | **Đã làm** (D76) — `nextDueDate` (ngày ISO), `nextDueAmount` (chuỗi decimal hai số lẻ), vắng mặt khi core không trả lịch. Booking đọc `GET /loans/{id}?associations=repaymentSchedule`. `FineractCoreLoanBookingGatewayTest`, `DisbursementSagaServiceTest`, `LoanRescheduleProducerFieldsTest`, `ProducerFieldAdditionsContractTest` |
| P4 | Payment `PaymentRepaymentService` (chỗ tạo `RepaymentDistributedEventData`, ~dòng 495) | `RepaymentDistributed.v1` | `borrowerId` = `repayment.getBorrowerId()` | Như P1 | **Đã làm** — `borrowerId` (String). `FinoraPaymentApplicationIT` |
| P5 | Payment `PaymentRepaymentService.collect` (khi `postOn != null`) và `cancelPending`; route trong `PaymentOutboxPublisher` + `finora.payment.repayment-collected-topic`/`-cancelled-topic` | Mới `RepaymentCollected.v1` (`finora.payment.repayment-collected`): `repaymentId, loanApplicationId, borrowerId, repaymentType, amount, currency, dueDate(=postOn), collectedAt`; `RepaymentCancelled.v1` (`finora.payment.repayment-cancelled`): `repaymentId, loanApplicationId, borrowerId, dueDate, cancelledAt` | Key = `loanApplicationId`, cùng outbox transaction | Không nhắc người đã trả trước (D16, D64); báo đã nhận/hủy khoản giữ | **Đã làm** (D77) — đúng danh sách field; allowlist loại + v1 trong publisher. `FinoraPaymentApplicationIT` (một event mỗi chuyển trạng thái, không phát khi gửi lại/hủy lại/ghi ngay), `PaymentOutboxPublisherTest`. Nhắc lịch vẫn tắt mặc định (D64): bật là quyết định của owner |
| P6 | Investment `MarketListingServiceImpl.closeExpiredListings` | `LoanFundingExpired.v1` | `investorIds` (danh sách distinct nhà đầu tư có commitment bị hủy) | Báo nhà đầu tư tiền giữ sẽ về ví | **Đã làm** — `investorIds` (List<String>, không trùng, sắp theo chữ, rỗng khi không ai góp). `FundingFlowIT` |
| P7 | Investment handler `LoanRescheduleConsentRequested` (lúc tạo phiếu) | `InvestorNoteServicingChanged.v1` | `changeType = RESCHEDULE_CONSENT_REQUESTED`, recipients = chủ phiếu, `externalPushRequired = true` | Mời biểu quyết; không cần topic mới | **Đã làm** (D78) — một recipient mỗi chủ phiếu (`noteId`/`noteNumber`/`amount` null), chỉ khi phiếu tạo lần đầu. `LoanRescheduleConsentRequestedHandlerTest`, `FundingFlowIT` |
| P8 | Loan `LoanRescheduleService.reject` | Mới `LoanRescheduleRequestReviewed.v1` (`finora.loan.reschedule-request-reviewed`): `requestId, loanApplicationId, loanNumber, borrowerId, decision=REJECTED, reviewedAt` (không lý do tự do) | Outbox cùng TX | Báo người vay bị từ chối; cần consumer Notification (chưa làm) | Thiết kế, chưa làm (theo phạm vi) |
| P9 | Payment (`RECONCILIATION_REQUIRED` repayment/disbursement), Loan (`REPAIR_REQUIRED` saga, servicing incident), Investment (`release_blocked_at`) | Mới `OperationsIncidentRaised.v1` (`finora.<service>.operations-incident-raised`): `incidentId, incidentType, severity, businessReference, sourceService, raisedAt` | Outbox khi vào trạng thái cần người | Hộp thư admin trong Notification (recipient `ROLE_ADMIN`, chỉ in-app); chưa làm | Thiết kế, chưa làm (theo phạm vi) |
| P10 | Docs/registry (mục 12) | — | — | Đồng bộ hợp đồng chung | **Đã làm** — `05-registry.md`, `LOAN-INVESTMENT-EVENTS.md`, `08-cross-service-flows.md` (F04–F07, F09) |

Tương thích consumer (kiểm trước khi dựa vào "optional"): Loan, Investment, Payment, Notification đọc bằng ObjectMapper của
Spring Boot (`FAIL_ON_UNKNOWN_PROPERTIES` tắt; không service nào tự dựng mapper khác), test `ProducerFieldAdditionsCompatibilityTest`
(Loan, Investment) và assertion trên ObjectMapper thật trong IT của ba service; Blockchain (`MilestoneProofConsumer`, hash v2)
chỉ từ chối số thực, field mới là chuỗi/ngày chuỗi/mảng chuỗi. Không consumer nào phải sửa. Ánh xạ field phía Notification:
`NotificationFlowIT` hai test mới (khoản vay không có bảng tra vẫn báo đúng người vay; `investorIds`; lời mời P7).

## 14. Quyết định

| # | Quyết định | Lý do |
|---|---|---|
| D60 | Người nhận lấy từ event, thiếu thì từ bảng tra do Notification dựng (`LoanFundingRequested` → người vay, `LoanFullyFunded.v2` → nhà đầu tư); event chưa đủ người nhận chờ trong DB tối đa 24 giờ (30 giây nhân đôi tới 30 phút) thay vì chặn partition hay DLT; group mới `notification-domain-events`, DLT riêng `<topic>.notification.DLT`; mâu thuẫn người vay thì fail closed; cờ `externalPushRequired` của Investment quyết định push tin servicing nhà đầu tư | Làm được ngay với event hiện có, không phụ thuộc thứ tự giữa topic, không gửi nhầm; DLT chung `<topic>.DLT` sẽ lẫn lỗi của Investment/Payment |
| D61 | Quá hạn người vay chỉ báo khi mở đợt, tăng nhóm (giai đoạn thu hồi), chạm nhóm 3 (nợ xấu) hoặc khắc phục; tin hằng ngày bị lọc cả trong app; tin rủi ro hằng ngày của nhà đầu tư không có cờ mốc cũng bị lọc | Đúng R7 "không spam mỗi ngày"; nhóm theo đợt (D29) nên tăng nhóm đúng là đổi stage |
| D62 | Expo Push: provider `log` (giả lập, mặc định, bị cổng D37 chặn ở staging/production) / `expo` / `disabled`; mỗi thông báo × thiết bị một dòng; claim SKIP LOCKED + lease 2 phút; 6 lần thử lùi dần 1 phút–1 giờ rồi DEAD; receipt sau 15 phút; DeviceNotRegistered thu hồi token; event cũ hơn 24 giờ không push; giờ nghỉ 22:00–07:00 (trừ rút tiền); tối đa 5 thiết bị; token chuyển chủ khi đăng nhập tài khoản khác cùng máy; at-least-once | Thực tiễn Expo (batch 100, receipt, DeviceNotRegistered); không có máy thật nên mặc định giả lập nhưng vẫn chạy đủ luồng |
| D63 | Push chỉ câu chung theo mẫu (không chữ số, tên, mã); tin quá hạn/nợ xấu hoàn toàn chung chung; trong app đầy đủ, luôn bật; push bật mặc định sau khi hệ điều hành cho phép, tắt theo 8 nhóm; số tiền trong câu giữ dạng `<decimal> VND` để app định dạng | Tình trạng nợ là thông tin nhạy cảm trên màn hình khóa; mapper mobile đọc đúng dạng này |
| D64 | Nhắc kỳ đến hạn 3 ngày trước và đúng ngày (08:05–20:05 giờ Việt Nam, idempotent) từ `nextDueDate` của `RepaymentDistributed` (+ P3); không nhắc kỳ Payment đang giữ (P5). **Tắt mặc định** tới khi P5 có | Thiếu P5, người trả trước kỳ (D16) bị nhắc nhầm — trái yêu cầu |
| D76 | (Producer, main agent 2026-10-17) P3: kỳ tiếp theo lấy từ đúng lần đọc Fineract đã cho ngày đáo hạn — booking đọc `GET /loans/{id}?associations=repaymentSchedule` (lần đọc sau duyệt, hoặc lần đọc đầu nếu khoản vay đã active khi reconcile), chọn dòng có số kỳ, `totalOutstandingForPeriod > 0`, ngày đến hạn sớm nhất; cơ cấu dùng snapshot servicing đọc sau khi duyệt lịch. Thiếu lịch thì field vắng mặt (`@JsonInclude(NON_NULL)`); số tiền không đúng hai số lẻ thì bỏ, không làm tròn; lỗi đọc field phụ không làm hỏng lần ghi core | Không tự tính lịch (yêu cầu); cùng ngày giải ngân, lịch đã duyệt trùng lịch sau disburse; field chỉ phục vụ thông báo nên không được chặn tiền |
| D77 | P5: `RepaymentCollected` chỉ phát khi khoản trả được hẹn ghi core (`postOn` khác null) — khoản ghi ngay đã có `RepaymentDistributed`; `RepaymentCancelled` chỉ phát khi chuyển thật `COLLECTED → CANCELLED` trong `cancelPending`; cùng transaction với bút toán ví; key `loanApplicationId`; topic cấu hình được; publisher Payment chặn mọi event/version ngoài allowlist v1 | Đúng mốc nghiệp vụ D16, không trùng với `RepaymentDistributed`; replay idempotent không sinh event thứ hai |
| D78 | P7: lời mời biểu quyết đi trên đường servicing sẵn có (`InvestorNoteServicingChanged.v1`, không topic mới), một event cho cả lô, mỗi chủ phiếu một recipient không gắn Note và không mang số dư; phát cùng transaction tạo phiếu và chỉ khi phiếu tạo lần đầu cho `requestId`; `dpd 0`, nhóm 1, `amount "0.00"` như `RESCHEDULED`, `maturityDate` hiện tại | Giữ quy tắc chống trùng của đường servicing (processed event + unique `sourceEventId + recipientId + type` ở Notification); phiếu là theo nhà đầu tư chứ không theo Note |

## 15. Defect

| ID | Mức | Vấn đề | Trạng thái |
|---|---|---|---|
| B60 | P2 | Investment phát `DELINQUENCY_CHANGED`/`BAD_DEBT_MILESTONE` mỗi lần DPD đổi; Notification tạo một tin "đang quá hạn" mỗi ngày cho mỗi nhà đầu tư | Fixed W3F — lọc tin không có cờ mốc; `investorDailyDelinquencyTicksStayOutOfTheInbox…` |
| B61 | P2 | `changeType` lạ rơi vào nhánh mặc định "Khoản vay đang quá hạn 0 ngày" | Fixed W3F — mẫu trung tính `INVESTOR_LOAN_UPDATE`, giữ `type` |
| B62 | P1 | Consumer không có error handler/DLT/processed-event: lỗi bị thử lại 10 lần liền rồi bỏ qua lặng lẽ | Fixed W3F — `NotificationKafkaConfig`, `notification_processed_events`; `NotificationKafkaIT` |
| B63 | P3 | Mẫu có thể ghép `null`/ngày ISO vào câu; `.env.example` mobile để miền `notification` chạy dữ liệu giả dù backend đã thật | Fixed W3F — mẫu ném lỗi khi thiếu giá trị (test), ngày `dd/MM/yyyy`; mobile bỏ `notification` khỏi mock |
| B64 | P2 | Event Loan/Payment phục vụ người vay không mang `borrowerId` (`BorrowerSignatureRequested`, `LoanContractActivated`, `LoanDisbursed`, `LoanFundingCancelled`, `RepaymentDistributed`); `LoanFundingExpired` không mang nhà đầu tư | Fixed 2026-10-17 — bảng tra người nhận (D60) cho event cũ; producer P1–P4, P6 (`3d7bf82`, `a0f3c0d`, `1c03819`) |
| B76 | P3 | Khoản trả kỳ đang giữ (D16) mà Fineract từ chối lúc tới ngày ghi (`recordCoreFailure`: hoàn tiền, `FAILED`) không phát event nào: Notification vẫn giữ cờ `held` của kỳ đó nên không gửi tin "đến hạn hôm nay" (khi đã bật nhắc lịch). `RepaymentCancelled` theo yêu cầu chỉ phát từ `cancelPending` | Open — đề xuất Payment phát `RepaymentCancelled` (hoặc event `RepaymentFailed` riêng) khi khoản có `postOn` bị hoàn tiền; hiếm (cần Fineract 4xx đúng ngày đến hạn) |
| B24 | P2 | `BorrowerSignatureRequested` chưa có consumer | Fixed W3F (`ContractEventsHandler`) |

## 16. Test và bằng chứng

`mvn -B -ntp -pl finora-notification -am verify` (2026-10-17, Testcontainers `postgres:17.5-alpine`, broker Kafka trong JVM):
finora-common 52 test, finora-notification 134 unit + 18 integration, BUILD SUCCESS (mục 19 ghi lần chạy cuối).

| Nhóm | Test |
|---|---|
| Mẫu/che màn hình khóa | `NotificationTemplateTest` (77, mọi mẫu) |
| Quá hạn theo mốc | `DelinquencyNotificationPolicyTest`, `overdueNotifiesTheBorrowerOnlyWhenTheEpisodeOrStageChanges` |
| Expo adapter | `ExpoPushSenderConfigTest` (định dạng, header, ticket, HTTP, receipt) |
| Envelope/contract | `InboundEventReaderTest`, `conflictingBorrowerFailsClosedInsteadOfNotifyingSomeone` |
| Bảo mật API | `NotificationApiSecurityTest`, `NotificationControllerSecurityTest` |
| Domain | `PushDeliveryTest`, `DueScheduleTest`, `QuietHoursPolicyTest`, `PushTokensTest` |
| Luồng đầy đủ | `borrowerAndInvestorsFollowTheLoanFromFundingToDisbursement` |
| Trùng/thứ tự | `sameEventIdDeliveredTwiceCreatesNothingTheSecondTime`, `eventWaitsWhileItsRecipientsAreStillOnAnotherTopic`, `recipientsThatNeverArriveExpireIntoADeadStateForOperations` |
| Push | `pushIsSentConfirmedAndRevokedWhenExpoSaysDeviceNotRegistered`, `transientProviderFailuresRetryWithBackoffThenDeadLetterAndCanBeRequeued`, `quietHoursDelayPushStaleEventsSkipPushAndOptOutKeepsOnlyTheInbox`, `concurrentDispatchWorkersClaimDisjointBatches` |
| Thiết bị | `deviceRegistrationIsIdempotentMovesSharedPhonesAndCapsDevicesPerUser` |
| Nhắc lịch | `dueRemindersFollowTheCoreScheduleAndSkipInstallmentsPaidEarly`, `settlementClosesTheReminderScheduleAndPaidOffRepaymentDoesNotPushTwice` |
| Tương thích | `investorRepaymentCreditKeepsTheExistingContractForMobileAndE2e`, `legacyInboxRowsAreUpgradedByTheNewMigrations` |
| Kafka thật | `NotificationKafkaIT` (trùng eventId, DLT, partition chạy tiếp) |

Mobile: `npx tsc --noEmit` xanh. Chưa chạy: E2E harness (ngoài phạm vi), push tới thiết bị thật (không có máy).

## 17. Bản đồ code thực tế

```text
Kafka → messaging.consumer.{DomainEventConsumer, InvestorServicingConsumer, HeldInstallmentConsumer}
      → KafkaEventProcessor → InboundEventReader (envelope/header) → NotificationTopicRoutes (topic→type)
      → service.intake.NotificationIntakeService.accept  [@Transactional]
           → service.handler.{Funding, Contract, Disbursement, Repayment, Delinquency, Restructuring,
                              InvestorServicing, Withdrawal}EventsHandler
                → service.recipient.LoanRecipientService (bảng tra) · service.reminder.DueScheduleService (lịch)
           → service.delivery.NotificationDeliveryService.deliver [MANDATORY] → NotificationTemplate (render)
                → QuietHoursPolicy → repositories (in_app, push_deliveries)
           → ProcessedEvent | DeferredEvent
Worker: service.intake.DeferredEventWorker → NotificationIntakeService.retryDeferred/abandonDeferred
        service.push.PushDispatchWorker → PushDispatchService.claimDue [TX] → PushSender.send → recordTickets [TX]
        service.push.PushReceiptWorker → claimReceiptChecks [TX] → PushSender.receipts → recordReceipts [TX]
        service.reminder.DueReminderWorker → DueReminderService.remind (TX mỗi lô)
REST: controller.{InAppNotification, PushDevice, NotificationPreference, NotificationOperations}Controller
      → InAppNotificationQueryService · service.push.PushDeviceService · service.preference.* · service.operations.*
Provider: integration.push.{PushSender, LoggingPushSender (giả lập), ExpoPushSender}; config.PushSenderConfig
Config: NotificationProperties, NotificationKafkaConfig (DLT), NotificationSchedulingConfig, NotificationSecurityConfig
```

## 18. Sai khác so với yêu cầu và giới hạn

- Push thật chưa được gửi tới thiết bị nào: mặc định `log` là giả lập (provider `LOG_MOCK` trên từng dòng, ticket
  `mock-*`). `ExpoPushSender` chỉ được kiểm bằng mock HTTP server theo tài liệu Expo.
- Nhắc kỳ đến hạn tắt mặc định (D64). Từ 2026-10-17 Payment đã phát P5 và Loan đã phát kỳ đầu/kỳ sau cơ cấu (P3); bật
  `NOTIFICATION_DUE_REMINDER_ENABLED` (cũng là điều kiện để listener `RepaymentCollected`/`RepaymentCancelled` chạy và tin
  `INSTALLMENT_PAYMENT_HELD`/`INSTALLMENT_PAYMENT_CANCELLED` xuất hiện) là quyết định của owner. Còn B76.
- Tin cho nhà đầu tư khi hết hạn gọi vốn (P6) và mời biểu quyết cơ cấu (P7) đã có; tin admin từ chối cơ cấu (P8) và hộp thư
  sự cố admin (P9) còn thiết kế.
- Bảng tra người nhận chỉ dựng lại được trong thời gian Kafka còn giữ event; event phát sau P1–P4 mang người vay nên không
  cần bảng tra, chỉ khoản vay có event cũ hơn thế còn phụ thuộc.
- At-least-once: timeout sau khi Expo đã nhận có thể gây push trùng một lần (Expo không có idempotency key).
- Mobile chưa dùng `read-all`; `PLAN.md` mobile chưa thêm dòng task (tránh xung đột nhánh rút tiền).

## 19. Nghiệm thu

- [x] Current state khớp code; planned (P1–P10) tách riêng.
- [x] Actor/luồng/ví dụ; field quan trọng có nguồn và lý do lưu; ERD có cardinality, FK và quan hệ logic tách bạch.
- [x] Query có index hoặc lý do không index; worker có lô hữu hạn.
- [x] Transaction không bao lời gọi Expo; kịch bản đồng thời có cơ chế và test.
- [x] Retry có điểm dừng (DEAD/EXPIRED/DLT) và thao tác vận hành.
- [x] `LEGAL-DATA-01` dẫn chiếu; không đưa policy demo thành luật.
- [x] Main agent làm P1–P7 và P10, cập nhật tài liệu chung (2026-10-17, mục 13); P8, P9 giữ thiết kế.
- [ ] Hải review module Notification (gồm hai test mới `90763d5`) và phần Investment của P6/P7 (`1c03819`).

Lần chạy cuối (2026-10-17, code commit `1897f4a` trên nhánh `feat/W3F-notifications-expo-push`):
`mvn -B -ntp -pl finora-notification -am verify` → finora-common 52/52, finora-notification surefire 134/134, failsafe
18/18 (`NotificationFlowIT` 17, `NotificationKafkaIT` 1), BUILD SUCCESS. Mobile `feat/w3f-push`: `npx tsc --noEmit` exit 0.
