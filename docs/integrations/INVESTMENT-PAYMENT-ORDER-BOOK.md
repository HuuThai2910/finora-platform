# Hợp đồng Investment → Payment cho sổ lệnh Notes

**Trạng thái:** `IMPLEMENTED`, chờ Thái review — 2026-10-02. Hải triển khai cả hai phía theo yêu cầu
của chính Hải (Payment là module của Thái): `HoldTransferService#settleFromHold`, `PaymentHold.settle`,
migration `V4__add_hold_settlement.sql`. Kiểm chứng: `HoldSettlementTest` 8/8 và
`FinoraPaymentApplicationIT#holdIsSettledInPartsThenRemainderReleased` trên PostgreSQL 17. Plan nghiệp vụ: [`INV-E2`](../../finora-investment/plans/INV-E2-order-book-matching.md).

## Vì sao cần

Chợ thứ cấp chuyển từ bảng tin sang sổ lệnh Ask/Bid. Lệnh mua **giữ tiền ngay khi đặt** (dùng lại
`POST /transactions/holds` hiện có) và có thể khớp **nhiều lần, từng phần** với nhiều người bán. Mỗi lần
khớp phải chuyển một phần tiền đang giữ sang người bán, phần còn lại vẫn giữ cho lần khớp sau.

Payment hiện có ba thao tác nhưng không đủ:

| Thao tác hiện có | Vì sao không dùng được |
|---|---|
| `POST /transactions/transfers` | Trừ từ số dư **khả dụng** chứ không từ tiền giữ; bắt buộc JWT của chính người mua, trong khi lần khớp có thể do người bán kích hoạt hoặc do worker chạy nền |
| `POST /transactions/holds/{ref}/release` | Nhả **toàn bộ** số giữ ban đầu, không biết phần đã thanh toán |
| capture trong Saga giải ngân | Gắn với luồng giải ngân khoản vay, không phải chuyển cho nhà đầu tư khác |

Investment **không** dùng cách `release` rồi `transfer`: giữa hai bước người mua có thể tiêu số tiền đó,
và lần khớp đã đổi chủ Note sẽ không thu được tiền.

## Thao tác mới: thanh toán từ tiền giữ

```http
POST /api/v1/transactions/holds/{holdReference}/settlements
Authorization: Bearer <service account finora-investment-client>
Content-Type: application/json

{
  "orderReference": "OB-3F9A0C1D2E4B5A6978C1",
  "sellerId": "investor-uuid-cua-nguoi-ban",
  "amount": "2910000.00",
  "platformFee": "145500.00",
  "settlementReference": "TRD-8C21D0E9F7A64B3C2D10"
}
```

Phản hồi `200`: `{ "paymentReference": "<mã giao dịch ledger>", "replayed": false }`.

| Trường | Nghĩa |
|---|---|
| `holdReference` | Khoản giữ của lệnh mua (Payment trả về lúc `hold`) |
| `orderReference` | Mã lệnh mua đã dùng khi `hold`; Payment đối chiếu khớp với khoản giữ như `release` đang làm |
| `sellerId` | Ví nhận tiền |
| `amount` | Số tiền lấy ra khỏi phần đang giữ, scale 2 |
| `platformFee` | Phần phí trong `amount`, về `PLATFORM_FEE`. Người bán nhận `amount − platformFee` |
| `settlementReference` | Khóa chống trùng, duy nhất mỗi lần khớp |

**Bút toán đề xuất** (một transaction, cân bằng):

```text
DEBIT  ví người mua  HELD       amount
CREDIT ví người bán  AVAILABLE  amount − platformFee
CREDIT PLATFORM_FEE  CLEARING   platformFee
```

**Bất biến Payment cần giữ:**

- Tổng `amount` đã thanh toán của một khoản giữ không vượt số đã giữ → vượt thì `422 PAYMENT_HOLD_INSUFFICIENT`.
- Gọi lại cùng `settlementReference` trả cùng kết quả (`replayed: true`), không ghi sổ lần hai. Cùng mã
  nhưng khác nội dung → `409`.
- `sellerId` khác chủ khoản giữ → nếu trùng thì `422 PAYMENT_SELF_TRANSFER`.
- Khoản giữ đã nhả → `409`.
- Quyền: service account mang role `payment:hold:on_behalf` (đã có cho Auto-Invest) hoặc một role mới
  riêng cho thanh toán sổ lệnh — Payment quyết định.

## Đổi hành vi `release`

`POST /transactions/holds/{holdReference}/release` phải nhả **phần còn lại** (`số giữ − tổng đã thanh
toán`), không phải toàn bộ số giữ ban đầu. Còn lại bằng 0 thì vẫn trả thành công và đánh dấu khoản giữ đã
đóng. Investment chỉ gọi `release` sau khi mọi lần thanh toán của lệnh mua đã thành công.

Với các luồng hiện có (đặt vốn sơ cấp, Auto-Invest) chưa từng thanh toán một phần nên phần còn lại bằng
toàn bộ — hành vi không đổi.

## Phía Investment xử lý kết quả thế nào

| Kết quả | Investment làm gì |
|---|---|
| `200` | Đánh dấu lần khớp `SETTLED` |
| 5xx, timeout, mất kết nối | Thử lại theo backoff 5s → tối đa 5 phút, cùng `settlementReference` |
| `404`/`405` không có mã lỗi nghiệp vụ (endpoint chưa triển khai) | Coi là tạm thời, thử lại — lần khớp tự thanh toán khi Payment lên bản mới |
| 4xx có mã nghiệp vụ | Đánh dấu `FAILED`, dừng, đối soát tay. Không tự đảo chủ Note |

Cài đặt: `finora-investment/.../client/impl/RestPaymentClient#settleFromHold`, worker
`service/orderbook/OrderBookSettlementService`.

## Đã triển khai thế nào

- `payment_holds.settled_amount` (CHECK `0 ≤ settled_amount ≤ amount`) ghi tổng đã thanh toán; khoản giữ
  vẫn `HELD` cho tới khi nhả.
- Bút toán dùng loại `CAPTURE`, `referenceType = NOTE_TRADE`, idempotency key `HOLD_SETTLE:{settlementReference}`.
  `settled_amount` chỉ tăng khi ledger không phải lần phát lại, trong cùng transaction với bút toán.
- `release` nhả `amount − settled_amount`; còn 0 thì chỉ đóng khoản giữ, không ghi bút toán 0 đồng.
- Khoản giữ đã thanh toán một phần không `reserveCapture` được — capture giải ngân lấy trọn số giữ ban
  đầu, và khoản giữ của sổ lệnh không bao giờ nằm trong allocation giải ngân.
- Quyền: dùng lại client role `payment:hold:on_behalf` của service account `finora-investment-client`
  (đã cấu hình cho Auto-Invest). Token nhà đầu tư thường bị từ chối `PAYMENT_SETTLE_FORBIDDEN`.

## Việc Thái cần xem

1. Có muốn tách role riêng cho thanh toán sổ lệnh thay vì dùng chung `payment:hold:on_behalf` không.
2. Loại bút toán `CAPTURE` hay `DISTRIBUTION` hợp với cách đối soát của Payment hơn.
3. Luồng gọi vốn sơ cấp không đổi hành vi: `settled_amount` luôn 0, `release` vẫn nhả trọn số giữ.
