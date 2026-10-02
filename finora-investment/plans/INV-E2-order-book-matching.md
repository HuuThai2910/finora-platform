---
task_id: INV-E2
roadmap_id: P7-B02
status: IN_PROGRESS
owner: Hai
approved_by:
approved_at:
scope: Chợ thứ cấp Notes — sổ lệnh Ask/Bid khớp liên tục, thay bảng tin của INV-E1
depends_on: INV-E1 (Note, phí 5%, trần giá theo dư nợ), Task #10 (phát hành Note)
supersedes: INV-E1 mục 4–5 (luồng đăng bán/mua trên bảng tin)
note: Backend Investment + Payment + mobile triển khai 2026-10-02 theo yêu cầu owner. Chưa được Thái duyệt (gồm
  phần sửa trong module Payment); web quản trị đã chuyển sang sổ lệnh.
---

# INV-E2 — Sổ lệnh Ask/Bid cho chợ thứ cấp Notes

## Bản đọc nhanh

INV-E1 làm chợ thứ cấp dạng **bảng tin**: người bán treo một Note với một giá, người mua chọn đúng tin
đó. INV-E2 thay bằng **sổ lệnh**: người bán đặt lệnh bán (Ask), người mua đặt lệnh mua (Bid), hệ thống
tự khớp ngay khi giá mua ≥ giá bán và đẩy sổ mới cho mọi người đang xem.

```text
Sổ của khoản vay LN-0042 (lãi 15%, mỗi Note dư nợ 1.000.000đ)

  MUA (Bid)              | BÁN (Ask)
  96,0%   3 Note         | 97,5%   2 Note
  95,5%   5 Note         | 98,0%   4 Note

B đặt mua 3 Note giá 98,0%
→ khớp 2 Note với Ask 97,5% (giá tốt nhất), rồi 1 Note với Ask 98,0%
→ B trả 2 × 975.000 + 1 × 980.000 = 2.930.000đ, dù B chịu được tới 98,0% cho cả 3
→ người bán nhận tiền sau phí 5%; Note đổi chủ sang B ngay lúc khớp
```

Những gì giữ nguyên từ INV-E1: trần giá là dư nợ gốc (giá tối đa 100%), phí 5% trừ của người bán, cho
mua bán Note nợ xấu kèm cảnh báo, chưa kiểm eKYC và trần dư nợ 100tr/400tr (INV-E1 mục 8.1, 8.4).

---

## 1. Lớp nghiệp vụ

### 1.1. Bốn quyết định đã chốt (2026-10-02)

| Quyết định | Chọn | Vì sao |
|---|---|---|
| Quan hệ với bảng tin | **Thay hẳn** | Một cơ chế duy nhất; "mua ngay" chỉ là lệnh Bid đặt bằng giá Ask tốt nhất |
| Đơn vị sổ và cách đặt giá | **Mỗi đợt gọi vốn một sổ, giá theo % dư nợ** | Note cùng đợt cùng mệnh giá, lãi suất, lịch trả nợ — thay thế được cho nhau. Giá % không lệch khi người vay trả bớt nợ |
| Loại lệnh | **Chỉ lệnh giới hạn** (có giá) | Không ai mua nhầm giá xấu trên sổ mỏng; dễ kiểm thử |
| Tiền của lệnh mua | **Giữ ngay khi đặt** | Khớp xong là chắc chắn thanh toán được; sổ không có lệnh "ma" thiếu tiền |
| Kiến trúc | **Khớp trong DB, khóa theo sổ** | Khởi động lại không mất gì; chạy nhiều instance vẫn đúng; đủ nhanh cho sàn P2P |

### 1.2. Người bán đặt lệnh bán

1. Chọn khoản vay mình đang giữ Note, nhập số Note và giá (% dư nợ, bước 0,1%, từ 0,1% tới 100%).
2. Hệ thống chọn đủ số Note **rảnh** của họ trong đợt đó (Note cũ trước) và **khóa** lại. Note khóa
   vẫn thuộc người bán và vẫn nhận gốc lãi, nhưng không đưa vào lệnh bán khác được.
3. Nếu sổ có lệnh mua giá ≥ giá bán, khớp ngay; phần còn lại nằm chờ trong sổ.

Không đủ Note rảnh → từ chối `INSUFFICIENT_FREE_NOTES`, báo còn bao nhiêu Note rảnh.

### 1.3. Người mua đặt lệnh mua

1. Nhập số Note và giá.
2. Hệ thống tính **tiền cần giữ** = giá × dư nợ lớn nhất của Note trong đợt × số Note, rồi gọi Payment
   giữ số tiền đó. Ví dụ 3 Note giá 97% dư nợ 1.000.000đ → giữ 2.910.000đ.
3. Giữ được tiền thì lệnh vào sổ và khớp ngay với lệnh bán giá ≤ giá mua; phần còn lại nằm chờ.
4. Khớp ở giá thấp hơn giá đặt thì phần giữ thừa được nhả về ví khi lệnh kết thúc.

Ví không đủ tiền → lệnh `REJECTED` kèm mã lỗi của Payment. Payment không phản hồi → lệnh nằm
`PENDING_FUNDS`, gửi lại cùng `Idempotency-Key` sẽ làm tiếp, không giữ tiền hai lần.

### 1.4. Quy tắc khớp

- **Ưu tiên giá rồi thời gian.** Lệnh bán giá thấp hơn khớp trước; cùng giá thì lệnh vào sổ trước khớp
  trước. Lệnh mua đối xứng (giá cao hơn trước).
- **Giá khớp là giá của lệnh đang nằm chờ.** Ví dụ trong Bản đọc nhanh: B đặt 98,0% nhưng 2 Note khớp
  ở 97,5%.
- **Khớp từng phần.** Một lệnh có thể khớp với nhiều lệnh đối ứng; được bao nhiêu khớp bấy nhiêu.
- **Tiền mỗi Note** = giá × dư nợ của chính Note đó **lúc khớp**, làm tròn HALF_UP tới đồng lẻ. Phí 5%
  tính trên từng Note, làm tròn xuống.
- **Chặn tự khớp.** Lệnh mới chạm lệnh của chính người đặt thì dừng; phần còn lại của lệnh mới bị huỷ
  (`SELF_TRADE_PREVENTED`), lệnh cũ giữ nguyên. Lý do như INV-E1 mục 5: tự mua bán với mình là đường làm
  giả khối lượng giao dịch.

### 1.5. Huỷ lệnh

- Người đặt huỷ được lệnh của mình khi lệnh `OPEN` hoặc `PARTIALLY_FILLED`. Phần đã khớp giữ nguyên,
  chỉ phần chưa khớp bị huỷ.
- Huỷ lệnh bán: Note chưa bán mở khóa ngay.
- Huỷ lệnh mua: phần tiền giữ còn lại được worker nhả về ví sau khi các lần khớp trước đó đã thanh toán.
- Huỷ lại lệnh đã huỷ trả kết quả cũ (người dùng có thể bấm hai lần).
- Lệnh đã khớp hết → `ORDER_ALREADY_FILLED`. Lệnh mua đang chờ giữ tiền → `ORDER_NOT_CANCELLABLE`, thử lại
  sau vài giây.
- Hệ thống tự huỷ phần còn lại của lệnh bán khi Note trong đó không còn giao được (đã tất toán hoặc đổi
  chủ bằng đường khác): `NOTE_UNAVAILABLE`.

### 1.6. Note nợ xấu

Sổ của khoản vay có Note `DEFAULTED` mang cờ `defaulted` và câu cảnh báo. Đặt lệnh vào sổ đó phải gửi
`acknowledgeDefault=true`, thiếu thì backend từ chối `DEFAULT_NOT_ACKNOWLEDGED` — cảnh báo không chỉ
phụ thuộc vào việc giao diện có hiện hay không (INV-E1 mục 8.2).

### 1.7. Xem sổ theo thời gian thực

Client mở stream của một sổ, nhận ngay **ảnh chụp** (snapshot — trạng thái sổ tại một thời điểm: 20 mức
giá mỗi phía, 20 lần khớp gần nhất) và nhận ảnh mới mỗi khi có lệnh đặt, khớp hoặc huỷ. Ảnh chỉ có độ sâu
gộp theo mức giá, **không** có ai đặt lệnh nào.

---

## 2. Ownership và ranh giới

| Việc | Ai làm | Căn cứ |
|---|---|---|
| Sổ lệnh, lệnh, khớp, khóa Note, đổi chủ Note | `finora-investment` | Rule 07: Investment sở hữu matching, Note ownership, secondary market |
| Quyết định số tiền giữ, số tiền mỗi lần khớp, phí | `finora-investment` | Phí là policy của chợ thứ cấp |
| Giữ tiền, chuyển từ tiền giữ sang người bán, thu phí, nhả tiền | `finora-payment` | Rule 07: Investment không tự trừ tiền |

Không đọc/ghi database service khác. Không thêm Kafka topic: stream là sự kiện trong process.

---

## 3. Dữ liệu (migration `V9__create_order_book.sql`)

### 3.1. ERD

```text
market_listings 1 ──── 0..1 order_books            (sổ tạo lần đầu có lệnh; unique listing_id)
order_books     1 ──── 0..N order_book_orders      (FK order_book_id)
order_book_orders(ASK) 1 ── 0..N order_book_note_locks  (FK ask_order_id; xoá khi bán/huỷ)
investment_notes 1 ──── 0..1 order_book_note_locks (unique note_id — chốt "một Note một lệnh bán")
order_book_orders(BID) 1 ── 0..N order_book_trades (FK bid_order_id)
order_book_orders(ASK) 1 ── 0..N order_book_trades (FK ask_order_id)
order_book_trades 1 ──── 1..N note_transfers       (FK trade_id; mỗi Note đổi chủ một dòng)
```

`order_books` 0..1 vì sổ chỉ được tạo khi có lệnh đầu tiên. Một lần khớp 1..N `note_transfers` vì khớp 3
Note là 3 Note đổi chủ, mỗi Note cần lịch sử riêng để tra "Note này đã qua tay ai".

### 3.2. Bảng và trường quan trọng

**`order_books`** — một dòng mỗi sổ, là **điểm khóa**: mọi thao tác đặt, khớp, huỷ của cùng sổ khóa dòng
này trước (`SELECT … FOR UPDATE`), nên chạy lần lượt.
- `last_sequence`: bộ đếm tăng mỗi lần sổ đổi. Vừa là thứ tự thời gian của lệnh, vừa là phiên bản ảnh
  chụp gửi client. Thiếu nó thì không xếp được "ai vào trước" và client không biết ảnh nào mới hơn.
- `last_trade_permille`, `last_trade_at`: giá khớp gần nhất để hiển thị.

**`order_book_orders`** — lệnh. Không xoá; hết hiệu lực thì đổi trạng thái.
- `price_permille`: giá theo phần nghìn (975 = 97,5%). Lưu số nguyên để so sánh và sắp xếp chính xác.
- `quantity`, `filled_quantity`: số Note đặt và đã khớp.
- `sequence`: thứ tự thời gian, null khi lệnh mua chưa giữ được tiền — chưa vào sổ thì chưa xếp hàng.
- `hold_amount`, `hold_consumed`, `hold_released_at`: tiền đã giữ, đã dùng cho các lần khớp, và lúc nhả
  phần còn lại. CHECK `hold_consumed <= hold_amount` là bất biến tiền: không bao giờ chi quá số đã giữ.
- `acknowledged_default`: người đặt đã xác nhận cảnh báo nợ xấu.
- `idempotency_key` + unique `(investor_id, idempotency_key)`: gửi lại cùng khóa trả về lệnh cũ.

**`order_book_note_locks`** — Note đang nằm trong lệnh bán. Unique `note_id` để một Note không bị bán qua
hai lệnh. Đây là dữ liệu khóa, không phải lịch sử, nên xoá khi Note bán xong hoặc lệnh huỷ.

**`order_book_trades`** — một lần khớp giữa một lệnh mua và một lệnh bán. Nội dung bất biến; chỉ cột
thanh toán đổi.
- `amount`, `platform_fee`, `seller_proceeds`, `outstanding_total`: chụp lại lúc khớp. CHECK
  `seller_proceeds + platform_fee = amount` và `amount <= outstanding_total` (trần giá).
- `aggressor_side`: bên chủ động (lệnh vừa vào sổ). Hiển thị trên băng giá.
- `settlement_status` (`PENDING`/`SETTLED`/`FAILED`), `settlement_attempts`, `next_settlement_at`: trạng
  thái thanh toán, đóng vai **outbox** — ghi cùng transaction với việc khớp, worker đọc và gọi Payment
  sau. Thiếu nó thì service chết ngay sau khớp sẽ quên trả tiền cho người bán.

**`note_transfers`** (đổi) — `note_listing_id` cho phép null, thêm `trade_id`. CHECK đúng một trong hai
có giá trị. Unique `payment_reference` cũ chỉ còn áp cho dòng bảng tin (một trade gồm nhiều Note chung
một mã); thêm unique `(trade_id, note_id)`.

Migration rút mọi tin bảng tin đang mở (`note_listings.status = 'CANCELLED'`); lịch sử tin đã bán giữ
nguyên.

### 3.3. Query và index

| Index | Query dùng | Vì sao |
|---|---|---|
| `idx_order_book_orders_book_side_price_sequence` (partial, chỉ lệnh còn hiệu lực) | Tìm lệnh đối ứng tốt nhất khi khớp; gộp độ sâu theo mức giá | Lọc theo sổ + phía, sắp theo giá rồi sequence đúng thứ tự cột. Partial vì lệnh đã kết thúc chiếm đa số theo thời gian nhưng không bao giờ khớp nữa |
| `uq_order_book_orders_investor_id_idempotency_key` | Phát hiện gửi lại | Unique là chốt chống đặt trùng khi hai request cùng khóa đến cùng lúc |
| `idx_order_book_orders_investor_id_created_at` | "Lệnh của tôi" phân trang | Lọc theo người, sắp mới nhất |
| `idx_order_book_orders_release_pending` (partial) | Worker nhả tiền | Chỉ chứa lệnh mua đã kết thúc chưa nhả — gần như rỗng |
| `uq_order_book_note_locks_note_id` | Khóa Note; kiểm Note rảnh (`NOT EXISTS`) | Chốt một Note một lệnh bán |
| `idx_order_book_trades_book_executed_at` | 20 lần khớp gần nhất của sổ | Sắp theo thời gian trong một sổ |
| `idx_order_book_trades_settlement_pending` (partial) | Worker thanh toán | Chỉ lần khớp chờ thanh toán |

Danh sách sổ không N+1: một truy vấn lấy trang `listing_id`, rồi mỗi loại dữ liệu (listing, sổ, giá tốt
nhất, cờ nợ xấu) một truy vấn `IN` cho cả trang.

---

## 4. Transaction và concurrency

**Thứ tự khóa luôn là sổ trước.** Mọi đường ghi vào sổ (đặt, khớp, huỷ) khóa dòng `order_books` trước rồi
mới chạm lệnh và Note, nên không có hai transaction giữ khóa theo thứ tự ngược nhau — không deadlock.

| Thao tác | Transaction | Gọi mạng |
|---|---|---|
| Lệnh mua bước 1 | Ghi lệnh `PENDING_FUNDS` + số tiền cần giữ | — |
| Lệnh mua bước 2 | — | Payment `hold`, **ngoài** transaction |
| Lệnh mua bước 3 | Khóa sổ → vào sổ → khớp (đổi chủ Note, ghi trade, ghi lịch sử, mở khóa Note) | — |
| Lệnh bán | Khóa sổ → chọn và khóa Note → vào sổ → khớp | — |
| Huỷ | Khóa sổ → đọc lại lệnh → huỷ, mở khóa Note | — |
| Thanh toán (worker) | Đọc trade chờ → **gọi Payment ngoài transaction** → transaction ngắn ghi kết quả | `settleFromHold`, `release` |

Khớp không chờ mạng vì tiền đã giữ và Note đã khóa từ trước; Note đổi chủ ngay lúc khớp. Thanh toán chỉ
có thể chậm, không thể thiếu tiền.

**Kịch bản tranh chấp:** 4 người cùng đặt mua 1 Note đang bán. Cả 4 giữ tiền song song (ngoài khóa), rồi
xếp hàng ở khóa sổ. Người đầu tiên khớp hết lệnh bán; 3 người sau đọc lại sổ dưới khóa, không còn lệnh
bán nào khớp được, lệnh của họ nằm chờ `OPEN`. Kiểm chứng: `OrderBookFlowIT#concurrentBidsForSingleNote`.

**Không dùng `@Version` trên `order_books`:** khóa bi quan đã xếp hàng; khóa lạc quan chỉ làm người đến
sau thất bại vô ích. **Bẫy đã tránh:** không nạp entity sổ trước khi khóa — Hibernate sẽ trả bản đã nạp
(cũ) thay vì bản đọc dưới khóa và ghi đè `last_sequence`.

---

## 5. API

Tất cả dưới `/api/v1/investments/order-books`, **yêu cầu đăng nhập**. Giá là chuỗi % một chữ số thập phân
(`"97.5"`), tiền là chuỗi decimal.

| Method | Path | Việc |
|---|---|---|
| GET | `?page&size` | Danh sách khoản vay còn Note lưu hành, kèm giá mua/bán tốt nhất, giá khớp gần nhất, cờ nợ xấu |
| GET | `/{listingId}` | Ảnh chụp sổ, kèm `referenceOutstanding` (dư nợ lớn nhất của một Note) để client hiện số tạm tính |
| GET | `/{listingId}/stream` | SSE: sự kiện `snapshot` ngay khi mở và mỗi lần sổ đổi; chú thích `keepalive` mỗi 20 giây |
| GET | `/{listingId}/me` | Số Note rảnh, số Note đang khóa, lệnh còn hiệu lực của tôi |
| POST | `/{listingId}/orders` + header `Idempotency-Key` | Đặt lệnh `{side, pricePercent, quantity, acknowledgeDefault}` → 201 |
| GET | `/orders/mine?active&page&size` | Lệnh của tôi |
| GET | `/admin/order-books/summary` (dưới `/investments`, chỉ ADMIN) | Số liệu toàn chợ: đã thanh toán, phí, chờ thanh toán, lỗi, Note đang chờ khớp |
| GET | `/admin/order-books/trades?settlement&page&size` (chỉ ADMIN) | Lần khớp kèm mã hai bên và trạng thái thanh toán, để đối soát |
| DELETE | `/orders/{orderReference}` | Huỷ phần chưa khớp |

Lệnh trả về (`BookOrderResponse`) có `loanId` để danh sách lệnh trên nhiều sổ gọi đúng tên khoản vay. API bảng tin cũ `/api/v1/investments/secondary/**` đã **gỡ**.

**Vì sao SSE (Server-Sent Events) thay vì WebSocket:** dữ liệu chỉ chảy một chiều server → client; đặt và
huỷ lệnh vẫn đi REST có idempotency. SSE là HTTP thường nên dùng lại xác thực bằng cookie/header, CORS và
route Gateway sẵn có. Mỗi lần đẩy là cả ảnh chụp chứ không phải phần chênh lệch: sổ P2P nhỏ, gửi cả ảnh
rẻ hơn cái giá phải bảo đảm thứ tự và bù gói mất. Client giữ ảnh có `sequence` lớn nhất.

---

## 6. Thanh toán sau khớp (worker)

`OrderBookSettlementWorker` chạy mỗi 2 giây:

1. **Thanh toán**: lấy trade `PENDING` tới hạn, gọi `PaymentClient.settleFromHold(holdReference,
   orderReference, sellerId, amount, platformFee, tradeReference)`. Thành công → `SETTLED`. Lỗi tạm thời
   → hẹn lại theo backoff 5s, 10s, 20s… tối đa 5 phút. Payment từ chối hẳn → `FAILED`, log lỗi, dừng để
   **đối soát tay** (reconciliation — đối chiếu sổ Investment với sổ Payment rồi sửa bằng nghiệp vụ bù),
   không tự đảo chủ Note.
2. **Nhả tiền**: lệnh mua `FILLED`/`CANCELLED` chưa nhả, **và mọi trade của nó đã `SETTLED`**, thì gọi
   `release`. Phải đợi vì Payment nhả toàn bộ phần còn giữ; nhả sớm thì lần thanh toán đang chờ hết tiền.

Mọi lời gọi idempotent theo mã (`tradeReference`, mã lệnh), nên chạy lại sau khi service chết hay hai
instance cùng xử lý một dòng đều không chuyển tiền hai lần.

---

## 7. Giới hạn và việc còn chờ

| Hạng mục | Trạng thái |
|---|---|
| Payment `POST /transactions/holds/{holdReference}/settlements` | **Đã có 2026-10-02** (Hải viết trong module Payment theo yêu cầu, chờ Thái review) — [`docs/integrations/INVESTMENT-PAYMENT-ORDER-BOOK.md`](../../docs/integrations/INVESTMENT-PAYMENT-ORDER-BOOK.md) |
| Payment `release` sau khi đã thanh toán một phần | **Đã sửa**: nhả `amount − settled_amount` |
| Mobile | **Xong 2026-10-02** (`finora-mobile/src/features/secondary-market`): danh sách sổ, sổ lệnh có thang giá và SSE (rơi về hỏi lại 5 giây khi luồng đứt), đặt lệnh, lệnh của tôi, huỷ lệnh. Lối vào: Ví › Danh mục, và Sàn › Chợ Notes |
| Web quản trị | **Xong 2026-10-02**: dải số liệu (phí đã thu, chờ thanh toán, cần đối soát, Note đang chờ khớp), bảng sổ lệnh có hộp thoại thang giá tự cập nhật (SSE qua cookie), bảng giao dịch lọc theo trạng thái thanh toán kèm tên hai bên. API quản trị mới: `GET /investments/admin/order-books/summary`, `GET /investments/admin/order-books/trades?settlement=` |
| SSE qua Gateway MVC | Chưa kiểm. Gateway MVC có thể đệm response; nếu ảnh bị dồn thì cho client gọi thẳng service hoặc chuyển sang hỏi định kỳ |
| Nhiều instance | Danh sách người nghe stream nằm trong bộ nhớ từng instance; cần kênh phát chung khi mở rộng |
| Tự huỷ lệnh khi khoản vay tất toán | Chưa có luồng tất toán; khi khớp, Note đã tất toán làm lệnh bán bị huỷ `NOTE_UNAVAILABLE` |
| Lệnh mua kẹt `PENDING_FUNDS` khi Payment không phản hồi | Như đặt vốn sơ cấp: người dùng gửi lại cùng khóa; chưa có worker đối soát |
| eKYC người mua, trần dư nợ 100tr/400tr | Chưa kiểm — giữ nguyên INV-E1 mục 8.1, 8.4 (`LEGAL-OPEN-02`) |

Phí 5% là policy demo, không phải quy định pháp luật (INV-E1 mục 3).

---

## 8. Bản đồ code thực tế — 2026-10-02

| Lớp | Vị trí |
|---|---|
| Migration | `db/migration/V9__create_order_book.sql` |
| Entity | `domain/orderbook/OrderBook`, `BookOrder` (chuyển trạng thái whitelist), `BookTrade`, `NoteLock`; `domain/secondary/NoteTransfer` (thêm `tradeId`) |
| Enum | `OrderSide`, `BookOrderStatus`, `CancelReason`, `SettlementStatus` |
| Repository | `OrderBookRepository` (khóa sổ, tạo sổ `ON CONFLICT DO NOTHING`), `BookOrderRepository`, `BookTradeRepository`, `NoteLockRepository`, `InvestmentNoteRepository` (Note rảnh/khóa, dư nợ lớn nhất) |
| Thuật toán khớp thuần | `service/orderbook/OrderMatcher` |
| Công thức tiền | `service/orderbook/OrderBookPricing` |
| Thực hiện khớp (trong transaction người gọi) | `service/orderbook/MatchingEngine` |
| Transaction ngắn | `service/orderbook/OrderBookTransactionService`: `createPendingBid`, `activateBid`, `rejectBid`, `placeAsk`, `cancel` |
| Điều phối ba bước lệnh mua | `service/orderbook/OrderBookCommandService` |
| Đọc sổ | `service/orderbook/OrderBookQueryService` |
| Stream SSE | `service/orderbook/OrderBookStreamService` (`@TransactionalEventListener(AFTER_COMMIT)`, một luồng đẩy) |
| Thanh toán, nhả tiền | `service/orderbook/OrderBookSettlementService`, `OrderBookSettlementWorker` |
| API | `controller/OrderBookController` |
| Cổng Payment | `client/PaymentClient#settleFromHold` (thay `transfer`), `RestPaymentClient`, `StubPaymentClient` |
| Bảo mật | `config/SecurityConfig` cho phép async dispatch để SSE không bị cắt |
| Đã gỡ | `SecondaryMarketController`, `SecondaryMarketService(Impl)`, `SecondaryMarketTransactionService(Impl)`, `NoteListing`, `NoteListingRepository`, DTO bảng tin |

### Kiểm chứng đã chạy

`mvn -pl finora-investment -am verify` trên PostgreSQL 17 (Testcontainers): 37 unit test, 25 integration
test, tất cả đạt.

| Bài | Chứng minh |
|---|---|
| `OrderMatcherTest` | Ưu tiên giá–thời gian, khớp từng phần, chặn tự khớp |
| `OrderBookPricingTest` | Đổi giá, làm tròn, tiền giữ là trần của mọi lần khớp |
| `BookOrderTest` | Chuyển trạng thái whitelist, không chi quá tiền giữ |
| `StubPaymentClientTest` | Thanh toán từ tiền giữ idempotent, không vượt phần giữ |
| `OrderBookFlowIT` | Khớp + thanh toán + nhả tiền với số dư cụ thể; giá tốt hơn và nhả phần thừa; ưu tiên giá–thời gian; chặn tự khớp; quy tắc huỷ; Note không nằm hai lệnh; idempotency; nợ xấu bắt buộc xác nhận; 4 lệnh mua tranh 1 Note |
| `OrderBookStreamIT` | SSE qua HTTP thật và Spring Security: ảnh đầu tiên, đặt lệnh qua REST rồi nhận ảnh mới, ảnh không lộ mã nhà đầu tư, không token thì 401 |

Chưa kiểm: nâng cấp migration từ database đã có tin bảng tin đang mở; SSE qua Gateway.
