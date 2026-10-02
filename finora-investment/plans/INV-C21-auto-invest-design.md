# INV-C2.1 — Auto-Invest: thiết kế

> Owner: Hải (finora-investment). Có sửa nhỏ finora-payment và realm Keycloak — Thái cần review.
> Roadmap: P7-B01. Ngày chốt thiết kế: 2026-09-30.

## 1. Mục tiêu

Nhà đầu tư bật Auto-Invest với một bộ tiêu chí; khi một khoản vay mới mở gọi vốn (`OPEN`) và khớp tiêu chí,
hệ thống tự đặt lệnh, giữ tiền và tạo cam kết cho họ — đúng như lệnh đặt tay, không cần mở app.

Ngoài phạm vi: nhiều chiến lược cho một người, trần tỷ trọng danh mục (`maxPortfolioSharePercent` trong mock mobile),
tự đầu tư vào chợ thứ cấp, tự ký hợp đồng thay nhà đầu tư.

## 2. Quyết định đã chốt

| Vấn đề | Quyết định |
|---|---|
| Giữ tiền khi không có JWT nhà đầu tư | Service account Keycloak `finora-investment-client` mang client role `payment:hold:on_behalf`; Payment cho phép role này hold/release thay nhà đầu tư |
| Nhiều nhà đầu tư cùng khớp một khoản vay | Ai bật trước đi trước (theo `enabled_at`), không có trần tỷ lệ cho Auto-Invest |
| Kích hoạt | Cờ `auto_invest_processed_at` trên listing + worker `@Scheduled` quét listing `OPEN` chưa xử lý |
| Listing cũ | Một cấu hình chỉ xét listing có `funding_opened_at >= enabled_at` — bật Auto-Invest không quét ngược các khoản đã mở trước đó |

Vì sao không dùng `@TransactionalEventListener` + `@Async`: service restart giữa chừng là mất lượt khớp.
Vì sao không bảng job riêng: cờ trên listing đủ bền và tự xử lý cả listing do admin mở, không cần thêm bảng.

## 3. Dữ liệu — Flyway `V8__create_auto_invest.sql`

**`auto_invest_configs`** — một dòng mỗi nhà đầu tư.

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `id` | bigserial PK | |
| `investor_id` | varchar(100) unique not null | `user_id` claim |
| `enabled` | boolean not null | |
| `grades` | varchar(100) not null | CSV hạng tín dụng, ví dụ `A,B`; không rỗng |
| `min_annual_rate` | numeric(7,4) not null | %/năm, cùng đơn vị `market_listings.annual_interest_rate` |
| `max_term_months` | int not null | > 0 |
| `amount_per_loan` | numeric(19,2) not null | > 0 |
| `enabled_at` | timestamptz null | đặt lại mỗi lần chuyển tắt → bật; null khi chưa từng bật |
| `version`, `created_at`, `updated_at` | | optimistic lock + audit |

Index: `(enabled, enabled_at)`.

**`auto_invest_matches`** — nhật ký mỗi lần một cấu hình được xét cho một listing.

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `id` | bigserial PK | |
| `investor_id` | varchar(100) not null | |
| `listing_id` | bigint not null FK `market_listings` | |
| `outcome` | varchar(20) not null | `MATCHED` / `SKIPPED` |
| `reason` | varchar(50) null | mã lý do khi `SKIPPED` |
| `amount` | numeric(19,2) null | số tiền đã đặt khi `MATCHED` |
| `order_reference` | varchar(50) null | |
| `created_at` | timestamptz not null | |

Unique `(investor_id, listing_id)` — không bao giờ xét một cấu hình hai lần cho cùng khoản vay.
Index `(investor_id, created_at desc)` cho API lịch sử.

**`market_listings`**: thêm `auto_invest_processed_at timestamptz null` + partial index
`where status = 'OPEN' and auto_invest_processed_at is null`.

## 4. Luồng khớp

`AutoInvestWorker` (`@Scheduled(fixedDelay = finora.investment.auto-invest.delay, mặc định 5s)`, bật/tắt bằng
`finora.investment.auto-invest.enabled`, mặc định `true`) lấy tối đa `batch-size` listing `OPEN` có
`auto_invest_processed_at is null`, gọi `AutoInvestService.processListing(listingId)` cho từng cái. Không giữ
transaction trong lúc gọi Payment — mỗi bước ghi là một transaction ngắn.

`processListing`:

1. Đọc listing. Nếu không còn `OPEN`, đã quá `funding_closes_at` hoặc vốn còn lại < `min_investment_amount`
   → đánh dấu processed, dừng.
2. Lấy cấu hình ứng viên: `enabled = true`, `enabled_at <= funding_opened_at`, hạng listing nằm trong `grades`,
   `annual_interest_rate >= min_annual_rate`, `term_months <= max_term_months`, chưa có dòng
   `auto_invest_matches` cho listing này. Sắp xếp `enabled_at asc, id asc`.
3. Với từng ứng viên, theo thứ tự:
   - Nhà đầu tư đã có lệnh (bất kỳ trạng thái nào trừ `REJECTED`/`CANCELLED`) trên listing → `SKIPPED ALREADY_INVESTED`.
   - `amount = floor(min(amount_per_loan, còn lại) / note_denomination) * note_denomination`.
     `amount < min_investment_amount` → `SKIPPED BELOW_MINIMUM` (nếu do vốn còn lại quá nhỏ thì dừng cả listing).
   - Đặt lệnh qua `FundingService.placeOrderFor(investorId, listingId, idempotencyKey = "AUTO-" + listingId, amount, HoldMode.ON_BEHALF)`
     — cùng ba bước ghi lệnh → giữ tiền → chốt cam kết với lệnh tay.
   - Kết quả:

| Kết quả đặt lệnh | Ghi nhật ký | Tiếp tục? |
|---|---|---|
| Cam kết thành công | `MATCHED`, amount, order_reference | Có |
| Payment từ chối (4xx, ví dụ không đủ số dư) | `SKIPPED INSUFFICIENT_FUNDS` hoặc `PAYMENT_REJECTED` | Có |
| Listing hết vốn/đóng ở bước chốt | `SKIPPED LISTING_FULL` | Dừng, đánh dấu processed |
| Payment không phản hồi (retryable) | Không ghi | Dừng, **không** đánh dấu — lần quét sau thử lại; idempotency key giữ nguyên nên không tạo lệnh trùng |
| Lỗi không mong đợi | Không ghi, log error | Dừng, không đánh dấu |

4. Hết ứng viên → đánh dấu `auto_invest_processed_at = now`.

Chống chạy trùng: unique `(investor_id, listing_id)` trên nhật ký + khóa idempotency `(investor_id, "AUTO-{listingId}")`
trên `investment_orders`. Hai worker cùng xử lý một listing tối đa chỉ lãng phí một lượt gọi, không tạo lệnh trùng.

Sau khi cam kết, lệnh tự động đi đúng luồng hiện có: đủ vốn → `LoanFullyFunded` → hợp đồng → nhà đầu tư vẫn **tự ký**.
Nhà đầu tư hủy lệnh tự động bằng API hủy lệnh hiện có (JWT của họ).

## 5. Giữ tiền thay nhà đầu tư

**Keycloak** (`docker/keycloak/template/realm-finora.json`): client confidential `finora-investment-client`,
`serviceAccountsEnabled`, client role `payment:hold:on_behalf` gán cho service account. Secret qua
`KEYCLOAK_INVESTMENT_CLIENT_SECRET`.

**Payment** (`HoldTransferService`): nếu token có authority `payment:hold:on_behalf` thì:
- `hold`: bỏ `requireInvestor()`/`requireActor()`, dùng `request.investorId()` làm chủ ví; kiểm tra idempotency so với
  `request.investorId()` thay vì actor.
- `release`: truyền `hold.getOwnerId()` làm actor cho `hold.release(...)`.
- `transfer` và mọi API khác giữ nguyên. Token người dùng thường vẫn bị kiểm tra như cũ.

**Investment**:
- `ServiceTokenProvider`: gọi `POST {keycloak}/realms/finora/protocol/openid-connect/token` với
  `grant_type=client_credentials`, cache access token tới 30 giây trước `expires_in`.
- `RestPaymentClient.authorize()`: có JWT người dùng trong SecurityContext thì chuyển tiếp JWT đó như
  cũ; không có (worker Auto-Invest) thì dùng service token. Không thêm method `holdOnBehalf` hay cờ
  `HoldMode`: bước nhả tiền bù trong `confirmCommitment` tự đi đúng token mà không phải truyền cờ
  qua ba tầng. Quyền vẫn do Payment quyết — token người dùng không bao giờ mang role on_behalf.
- `FundingService.placeOrderFor(investorId, listingId, idempotencyKey, request)`: lõi đặt lệnh;
  `placeOrder` của API cũ gọi lõi với `SecurityUtils.getCurrentUserId()`.
- Gửi lại cùng khóa mà lệnh cũ còn `PENDING_FUNDS` (lần trước Payment không phản hồi) thì làm tiếp từ
  bước giữ tiền thay vì trả nguyên lệnh treo. Áp dụng cho cả lệnh đặt tay.

**Realm đang chạy**: Keycloak bỏ qua import khi realm đã tồn tại, nên client mới trong template không
tự xuất hiện. `docker/keycloak/add-investment-client.sh` tạo client, role và gán role bằng `kcadm`,
chạy lại an toàn, in secret để chép vào `finora-investment/.env`.

## 6. API

Tất cả dưới JWT investor, `investorId` lấy từ token.

| Method | Path | Mô tả |
|---|---|---|
| `GET` | `/api/v1/investments/auto-invest` | Cấu hình hiện tại; chưa có thì trả mặc định `enabled=false` (không tạo dòng) |
| `PUT` | `/api/v1/investments/auto-invest` | Lưu `{enabled, grades, minAnnualRate, maxTermMonths, amountPerLoan}`; tắt → bật thì đặt `enabled_at = now` |
| `GET` | `/api/v1/investments/auto-invest/matches?limit=20` | Lịch sử mới nhất trước, `limit` tối đa 100; kèm `applicationNumber`, `creditGrade`, `annualInterestRate` của listing |

Validation `PUT`: `grades` có 1–10 phần tử, mỗi phần tử khớp `^[A-Z][A-Z0-9+-]{0,7}$` — cùng ràng buộc
`credit_grade` bên finora-ai. Không so với danh sách cố định vì bảng hạng là cấu hình động admin sửa được
(`PUT /api/v1/ai/config/product`); hạng không còn tồn tại chỉ đơn giản là không bao giờ khớp. `minAnnualRate` 0–100;
`maxTermMonths` 1–120; `amountPerLoan > 0`. Không bắt `amountPerLoan` chia hết mệnh giá — worker làm tròn xuống.

## 7. Mobile

- `features/investment/api.ts`: `getAutoInvest`, `getAutoInvestMatches` gọi API thật; thêm `saveAutoInvest`.
- `AutoInvestScreen`: công tắc gọi `PUT` (hoàn tác nếu lỗi); form sửa hạng/lãi suất/kỳ hạn/số tiền; bỏ dòng
  "Trần danh mục/khoản"; lịch sử hiển thị lý do `SKIPPED` bằng tiếng Việt.
- `types/invest.ts`: bỏ `maxPortfolioSharePercent`, `AutoInvestMatch` thêm `reason`.

## 8. Kiểm thử

- Unit: bộ lọc tiêu chí (hạng, lãi suất, kỳ hạn, `enabled_at` so với `funding_opened_at`); tính số tiền làm tròn mệnh giá.
- IT Investment (Testcontainers + `StubPaymentClient`):
  - Hai nhà đầu tư khớp, khoản vay chỉ đủ một người → người bật trước `MATCHED`, người sau `SKIPPED LISTING_FULL`/`BELOW_MINIMUM`.
  - Chạy worker hai lần → không lệnh trùng, không dòng nhật ký trùng.
  - Payment từ chối → `SKIPPED`, người tiếp theo vẫn được xét.
  - Payment không phản hồi → listing chưa đánh dấu, lần sau khớp thành công với cùng order.
  - Listing mở trước khi bật Auto-Invest → không khớp.
- Payment (unit test `HoldTransferServiceOnBehalfTest`): token có `payment:hold:on_behalf` hold/release được
  cho investor khác; token investor thường giữ tiền cho người khác vẫn `403 PAYMENT_OWNER_MISMATCH`.

## 9. Trạng thái (2026-09-30)

- Đã có: V8 migration, entity/repository, `AutoInvestMatcher` + worker, API cấu hình/lịch sử, service token,
  sửa Payment hold/release, realm template + script, mobile nối API thật.
- Đã chạy: unit test Investment (`AutoInvestConfigTest`, `AutoInvestMatcherTest`) và Payment
  (`HoldTransferServiceOnBehalfTest`) pass; `tsc --noEmit` mobile pass.
- Chưa chạy: `AutoInvestFlowIT` và `FundingFlowIT` (cần Docker cho Testcontainers); E2E với Keycloak + Payment thật.
