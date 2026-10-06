# Chính sách phục vụ và trả nợ khoản vay FINORA

> Tài liệu này là nguồn nghiệp vụ chung cho Loan, Payment, Investment, CIC, web và mobile.
> Đây là thiết kế kỹ thuật của đồ án, không thay thế ý kiến pháp lý hoặc biểu phí đã được các bên ký.

- Phiên bản: `LOAN_SERVICING_POLICY_V1`
- Ngày cập nhật triển khai: **2026-10-04**
- Phạm vi hiện tại: VND, khoản vay tối đa 24 tháng, Fineract là nguồn chuẩn lịch trả và phân bổ.
- Kiểm soát pháp lý: `LEGAL-PAYMENT-01`, `LEGAL-SERVICING-01`, `LEGAL-DISCLOSURE-01` trong
  [LEGAL-COMPLIANCE.md](LEGAL-COMPLIANCE.md).

## 1. Nguyên tắc sở hữu dữ liệu

| Dữ liệu hoặc hành động | Nguồn chuẩn | FINORA dùng thế nào |
|---|---|---|
| Lịch trả, dư nợ, phân bổ gốc/lãi/phí/phạt, ngày quá hạn | Apache Fineract | Loan giữ projection chỉ đọc có `dataAsOf`; không tự tính một lịch thứ hai |
| Tiền đã thu, số dư ví, bút toán, phân bổ tiền cho nhà đầu tư | Payment | Mọi biến động số dư có ledger cân bằng và idempotency key |
| Vòng đời khoản vay, yêu cầu trả trước/cơ cấu và phê duyệt | Loan | Chỉ Loan đổi trạng thái nghiệp vụ FINORA |
| Chủ sở hữu Note và danh mục đầu tư | Investment | Payment chỉ dùng snapshot/version để trả đúng người đang sở hữu |
| Nhóm nợ nội bộ và lịch sử tín dụng demo | Loan và CIC mock | Loan quyết định theo policy; CIC lưu báo cáo đã phát sinh, không tự đổi khoản vay |

`LoanApplication` kết thúc vai trò sau khi hợp đồng được giải ngân. Khoản vay đang phục vụ phải có
`FinoraLoan` riêng. Không dùng trạng thái hồ sơ vay để thay trạng thái dư nợ.

## 2. Thứ tự triển khai

1. Trả nợ đúng kỳ (happy case), cập nhật lịch, ledger và Note.
2. Đối soát tối thiểu: retry/reconcile khi Payment đã thu nhưng Fineract hoặc consumer lỗi.
3. Trả trước một phần và tất toán trước hạn.
4. Quá hạn, khắc phục quá hạn và phân nhóm nợ nội bộ.
5. Cơ cấu lại thời hạn trả nợ có yêu cầu và phê duyệt.
6. Thu hồi nợ, chuyển nợ xấu và báo cáo CIC mock.
7. UI quản trị đối soát đầy đủ sau khi contract backend ổn định.

## 3. Cơ chế trả nợ

### Cách đọc phần này

Mỗi cơ chế được trình bày theo hai lớp để dùng chung cho Product, Pháp chế, QA và đội kỹ thuật:

1. **Cách hiểu nghiệp vụ:** dùng hoàn toàn tiếng Việt, mô tả người vay phải trả khoản gì và vì sao.
2. **Công thức triển khai:** giữ đúng tên field trong API/mã nguồn để developer và tester đối chiếu.

Người chỉ cần hiểu nghiệp vụ có thể đọc phần “Cách hiểu nghiệp vụ” và “Ví dụ đời thường”; không cần
đọc tên field tiếng Anh. Số tiền cuối cùng vẫn phải lấy từ hệ thống lõi quản lý khoản vay và chính sách
đã được chấp thuận, không dùng ví dụ trong tài liệu làm số thu thực tế.

Thuật ngữ đọc nhanh:

- **Hệ thống lõi/Fineract:** nơi lưu lịch trả, dư nợ và số tiền chính thức của khoản vay.
- **Field:** tên kỹ thuật của một dữ liệu trong API hoặc mã nguồn; phần nghiệp vụ không yêu cầu nhớ tên này.
- **Policy:** quy tắc nghiệp vụ FINORA đã chọn và công bố, ví dụ tỷ lệ phí trả trước.
- **Ví:** tài khoản ghi nhận số dư và giao dịch của người dùng trong Payment; không phải nơi tự tính dư nợ.
- **Note:** phần quyền lợi đầu tư gắn với khoản vay, dùng để phân chia gốc và lãi cho nhà đầu tư.

### 3.1 Trả đúng kỳ

- Người vay trả đúng số tiền đến hạn từ ví khả dụng.
- Payment ghi debit ví người vay và credit tài khoản clearing trong cùng local transaction.
- Payment post đúng một repayment sang Fineract bằng cùng `repaymentReference`.
- Fineract trả breakdown chính thức: `principal`, `interest`, `fee`, `penalty`.
- Payment phân bổ phần gốc/lãi cho chủ sở hữu Note; phí/phạt theo policy được đưa vào ví nền tảng.
- Investment giảm dư nợ Note và tăng lãi đã nhận bằng event idempotent.
- Nếu tổng breakdown khác số tiền đã thu, dừng ở `RECONCILIATION_REQUIRED`; không tự bù và không thu lại.
- Fineract trả lỗi xác định (4xx) trước khi tạo giao dịch: Payment ghi bút toán đảo chiều và hoàn tiền ví.
- Mất kết nối sau khi gửi: worker chỉ tìm giao dịch theo `repaymentReference`; tìm thấy mới tiếp tục phân phối,
  chưa thấy vẫn giữ `RECONCILIATION_REQUIRED`, tuyệt đối không POST lại mù.

**Cách hiểu nghiệp vụ — công thức bằng lời:**

```text
Số tiền trừ ví người vay
= Tiền gốc phải trả trong kỳ
+ Tiền lãi phải trả trong kỳ
+ Phí của khoản vay đã được công bố (nếu có)
+ Tiền phạt đã phát sinh theo hợp đồng (nếu có)
```

Phần **gốc và lãi** được chia cho các nhà đầu tư theo tỷ lệ sở hữu khoản vay. Phần **phí và phạt** được
xử lý theo chính sách của nền tảng; không được cộng thêm lần thứ hai vào số tiền người vay phải trả.

**Ví dụ đời thường:** đến ngày 05 hằng tháng, anh A phải trả 4.000.000 đồng tiền gốc và 500.000 đồng
tiền lãi. Anh A không có phí hoặc phạt nên ví bị trừ 4.500.000 đồng. Sau giao dịch, kỳ đó được ghi nhận
đã trả đủ; anh A không phải thực hiện thêm thao tác nào.

**Công thức triển khai dành cho developer/QA:** FINORA không tự tính lại tiền kỳ. Invariant dùng để
đối soát là:

```text
coreAmount = principal + interest + coreFee + penalty
walletDebit = coreAmount
investorDistribution = principal + interest
platformDistribution = coreFee + penalty
```

Giải thích bằng tiếng Việt:

- `principal` — **tiền gốc của kỳ**: phần tiền vay người vay hoàn trả trong kỳ này, lấy từ breakdown
  transaction Fineract.
- `interest` — **tiền lãi của kỳ**: lãi phát sinh theo dư nợ và lịch Fineract, không phải mobile tự tính.
- `coreFee` — **phí đã cấu hình trong sản phẩm Fineract**, nếu kỳ đó có; bằng 0 nếu không có.
- `penalty` — **khoản phạt Fineract trả về** theo điều khoản đã công bố, nếu có.
- `coreAmount` — **tổng nghĩa vụ ghi vào khoản vay** tại core.
- `walletDebit` — **số tiền thực tế trừ ví người vay**; trả đúng kỳ không có phí FINORA riêng nên bằng
  `coreAmount`.
- `investorDistribution` — phần gốc và lãi được chia cho các chủ sở hữu Note theo tỷ lệ sở hữu.
- `platformDistribution` — phần phí/phạt thuộc nền tảng theo policy; không được cộng lần nữa vào dư nợ.

Ví dụ đối chiếu field: Fineract trả `principal=4.000.000`, `interest=500.000`, `coreFee=0` và
`penalty=0` thì `walletDebit=4.500.000`. Tổng gốc và lãi được phân phối cho nhà đầu tư theo tỷ lệ Note.

Breakdown và số tiền chính thức luôn lấy từ transaction Fineract. Nếu hai vế không cân thì chuyển
`RECONCILIATION_REQUIRED`, không tự sửa chênh lệch.

### 3.2 Trả trước một phần gốc

- Người vay nhập **phần gốc muốn trả thêm**; giá trị phải dương và nhỏ hơn gốc còn lại. Trả hết gốc
  phải đi luồng tất toán.
- Tổng thu core là `nghĩa vụ đang đến hạn + phần gốc trả thêm`; phí FINORA được snapshot riêng và
  không ghi vào dư nợ Fineract.
- Chỉ khoản vay snapshot `FINORA-FINERACT-V2` được dùng. Product V2 bắt buộc đồng thời có
  `loanScheduleType=PROGRESSIVE`, `advanced-payment-allocation-strategy` và
  `futureInstallmentAllocationRule=REAMORTIZATION`. V1 tiếp tục `mifos-standard-strategy`.
- Quote công bố số đang đến hạn, gốc trả thêm, phí, tổng debit, dư gốc và kỳ kế tiếp **trước giao dịch**.
  Fineract 1.15 không có endpoint giả lập lịch mới theo một số tiền chưa post, nên FINORA không tự dựng
  một lịch “sau trả” để tránh sai khác với core. Sau POST thành công, response/event mang snapshot kỳ mới
  chính thức do Fineract trả về.
- Quote dùng một lần, có TTL, row lock và ownership check; retry cùng `Idempotency-Key` trả lại kết quả cũ.

**Cách hiểu nghiệp vụ — công thức bằng lời:**

```text
Số tiền trừ ví người vay
= Khoản tiền đang phải trả đến hạn
+ Phần tiền gốc người vay muốn trả thêm
+ Phí trả trước

Phí trả trước
= Phần tiền gốc trả thêm × Tỷ lệ phí áp dụng
```

Nếu kết quả phí theo tỷ lệ thấp hơn mức phí tối thiểu thì dùng mức tối thiểu. Nếu thời điểm trả được
miễn phí thì phí bằng 0 và không áp dụng mức tối thiểu. Sau khi trả, số gốc còn lại giảm và hệ thống lõi lập
lại lịch các kỳ sau; người vay không phải tiếp tục trả lãi cho phần gốc đã trả trước kể từ thời điểm hệ
thống lõi ghi nhận giao dịch.

**Ví dụ đời thường:** chị B đang có 2.000.000 đồng đến hạn và muốn trả thêm 5.000.000 đồng tiền gốc.
Phí theo tỷ lệ là 50.000 đồng nhưng chính sách quy định tối thiểu 100.000 đồng. Ví của chị B bị trừ
7.100.000 đồng: 2.000.000 đồng xử lý kỳ đang đến hạn, 5.000.000 đồng làm giảm gốc còn nợ và
100.000 đồng là phí trả trước. Các kỳ tiếp theo được hệ thống tính lại trên số gốc mới.

**Công thức triển khai dành cho developer/QA:** trả trước một phần hiện dùng cùng policy phí với tất
toán sớm, nhưng căn cứ tính phí chỉ là `prepaidPrincipal` do người vay nhập, không phải toàn bộ dư gốc:

```text
coreAmount = scheduledDue + prepaidPrincipal
platformFee = configuredRate == 0
    ? 0
    : max(prepaidPrincipal × configuredRate, minimumFee)
walletDebit = coreAmount + platformFee
```

Giải thích bằng tiếng Việt:

- `scheduledDue` — **nghĩa vụ đang đến hạn tại thời điểm trả trước**, gồm các thành phần gốc/lãi/phí/
  phạt mà Fineract yêu cầu phải xử lý trước hoặc cùng giao dịch.
- `prepaidPrincipal` — **phần gốc người vay tự chọn trả thêm** ngoài nghĩa vụ đang đến hạn; phải lớn
  hơn 0 và nhỏ hơn toàn bộ gốc còn lại.
- `configuredRate` — **tỷ lệ phí trả trước** lấy từ policy version đang hiệu lực: 0%, 0,5% hoặc 1%
  tùy thời hạn và thời điểm trả.
- `minimumFee` — **mức phí tối thiểu** của policy; hiện là 100.000 đồng khi tỷ lệ khác 0.
- `platformFee` — **phí FINORA của hành động trả trước**, tính riêng trên `prepaidPrincipal`.
- `coreAmount` — **số gửi sang Fineract** để ghi nghĩa vụ hiện tại và phần gốc trả thêm.
- `walletDebit` — **tổng số tiền trừ ví**, bằng số gửi core cộng phí FINORA.

Ví dụ đối chiếu field: `scheduledDue=2.000.000`, `prepaidPrincipal=5.000.000`,
`configuredRate=1%` và `minimumFee=100.000` thì `platformFee=100.000`,
`coreAmount=7.000.000` và `walletDebit=7.100.000`.

`configuredRate` được chọn theo thời hạn/thời điểm tại bảng mục 3.3. Chỉ `coreAmount` được gửi sang
Fineract; `platformFee` không làm tăng dư nợ hoặc thay đổi breakdown core.

**Trạng thái triển khai:** API đã mở cho khoản vay V2. Ngày 2026-10-04, local Fineract 1.15 đã xác minh
khoản vay 50.000.000 đồng trả trước 5.000.000 đồng: core ghi toàn bộ 5.000.000 vào gốc và re-amortize
thành khoảng 833.000 đồng gốc đã trả trên mỗi kỳ trong 6 kỳ. Product V2 cũ từng tạo ở dạng
`CUMULATIVE` phải được sửa/tạo lại thành `PROGRESSIVE`; không sửa ngầm loan account V1 đang hoạt động.

### 3.3 Tất toán trước hạn

- Số phải thanh toán = gốc còn lại + lãi/phí/phạt đã phát sinh đến ngày tất toán + phí trả trước.
- Payment lấy early-settlement quote từ Fineract, snapshot kết quả và thời hạn hiệu lực; Loan chỉ nhận
  kết quả đã phân phối để đóng read model, không tự tính lại số tất toán.
- Sau Payment success và Fineract xác nhận đóng khoản vay, Loan chuyển `SETTLED`, Investment đóng Note.

Policy phí demo tham chiếu duy nhất biểu phí Vietcombank:

| Thời hạn hợp đồng | Thời điểm tất toán | Phí trên gốc trả trước | Tối thiểu |
|---|---|---:|---:|
| Tối đa 12 tháng | Trong nửa đầu thời hạn | 0,5% | 100.000 đồng |
| Tối đa 12 tháng | Sau nửa thời hạn | Miễn phí | 0 đồng |
| 13–24 tháng (giới hạn FINORA) | Trước ngày đáo hạn | 1% | 100.000 đồng |

Đây là **policy FINORA tham chiếu biểu phí Vietcombank**, không phải tỷ lệ pháp luật bắt buộc.
Pháp luật yêu cầu công bố/thỏa thuận phí nhưng không ấn định tỷ lệ chung.

**Cách hiểu nghiệp vụ — công thức bằng lời:**

```text
Số tiền trừ ví để đóng khoản vay
= Toàn bộ tiền gốc còn nợ
+ Tiền lãi đã phát sinh đến ngày tất toán
+ Phí và tiền phạt của khoản vay đã phát sinh (nếu có)
+ Phí tất toán trước hạn

Phí tất toán trước hạn
= Toàn bộ tiền gốc còn nợ × Tỷ lệ phí áp dụng
```

Nếu phí tính theo tỷ lệ thấp hơn mức tối thiểu thì thu mức tối thiểu. Nếu thuộc thời gian được miễn phí
thì phí bằng 0. Khi toàn bộ số tiền được ghi nhận thành công, khoản vay kết thúc và không còn kỳ trả nợ
tương lai.

**Ví dụ đời thường:** anh C vay 24 tháng nhưng muốn đóng khoản vay sớm. Tại ngày yêu cầu, anh còn
20.000.000 đồng tiền gốc và 300.000 đồng lãi đã phát sinh. Phí tất toán là 1% của gốc còn lại, tức
200.000 đồng. Nếu không có phí/phạt khác, anh C cần có 20.500.000 đồng trong ví. Thanh toán thành công
thì khoản vay chuyển sang đã tất toán.

**Công thức triển khai dành cho developer/QA:**

```text
earlySettlementFee = max(outstandingPrincipal × configuredRate, minimumFee)
coreAmount = outstandingPrincipal + accruedInterest + coreFee + penalty
walletDebit = coreAmount + earlySettlementFee
```

Giải thích bằng tiếng Việt:

- `outstandingPrincipal` — **toàn bộ tiền gốc còn nợ** tại ngày tất toán, lấy từ quote Fineract.
- `accruedInterest` — **lãi đã phát sinh đến ngày tất toán** nhưng chưa thanh toán.
- `coreFee` — **phí còn phải trả tại Fineract**, tách biệt với phí tất toán của FINORA.
- `penalty` — **phạt đã phát sinh và được Fineract xác nhận**, nếu có.
- `configuredRate` và `minimumFee` — tỷ lệ/mức tối thiểu được chọn từ bảng policy phía trên.
- `earlySettlementFee` — **phí đóng toàn bộ khoản vay trước ngày đáo hạn**; tính trên toàn bộ gốc còn nợ.
- `coreAmount` — tổng số tiền cần ghi vào Fineract để dư nợ về 0.
- `walletDebit` — tổng tiền trừ ví người vay, gồm nghĩa vụ core và phí tất toán FINORA.

Ví dụ đối chiếu field: `outstandingPrincipal=20.000.000`, `accruedInterest=300.000`,
`coreFee=0`, `penalty=0` và `configuredRate=1%` thì `earlySettlementFee=200.000`,
`coreAmount=20.300.000` và `walletDebit=20.500.000`.

Nếu `configuredRate = 0`, phí bằng 0 và không áp dụng mức tối thiểu.

### 3.4 Khắc phục quá hạn

Khắc phục quá hạn là người vay thanh toán đủ toàn bộ phần đang quá hạn để khoản vay trở lại bình thường;
không phải một lần “gia hạn tự động”. Trả thiếu chỉ làm giảm số tiền quá hạn, không đặt lại số ngày quá hạn.

Thứ tự phân bổ chuẩn do Fineract cấu hình và FINORA đối chiếu:

1. Gốc quá hạn.
2. Lãi trên gốc quá hạn.
3. Gốc đến hạn.
4. Lãi trên gốc đến hạn.
5. Khoản lãi chậm trả/phí khác theo hợp đồng và cấu hình đã công bố.

Mức trần policy/pháp lý cần giữ tách biệt:

- lãi trên dư nợ gốc quá hạn không vượt quá 150% lãi suất trong hạn tương ứng;
- lãi chậm trả trên phần lãi chưa trả không vượt quá 10%/năm trên số lãi chậm trả;
- không cộng hai loại lãi lên cùng một căn cứ tiền nếu hợp đồng/Fineract không cho phép.

**Cách hiểu nghiệp vụ — công thức bằng lời:**

```text
Tổng tiền cần trả để khắc phục quá hạn
= Tiền gốc đã đến hạn nhưng chưa trả
+ Tiền lãi phát sinh trên phần gốc quá hạn
+ Tiền lãi của kỳ đã đến hạn nhưng chưa trả
+ Tiền lãi chậm trả phát sinh trên phần lãi chưa trả
+ Các khoản phí đã được công bố trong hợp đồng (nếu có)
```

Hai khoản lãi quá hạn có căn cứ khác nhau: một khoản tính trên **gốc quá hạn**, khoản còn lại tính trên
**tiền lãi đến hạn chưa trả**. Người vay trả một phần thì hệ thống phân bổ theo thứ tự đã cấu hình, số
tiền quá hạn giảm nhưng khoản vay chưa trở lại bình thường. Chỉ khi trả đủ phần quá hạn và DPD về 0 mới
được xem là đã khắc phục.

**Ví dụ đời thường:** kỳ này chị D còn thiếu 3.000.000 đồng gốc và 500.000 đồng lãi. Đến ngày thanh
toán, hệ thống tính thêm 20.000 đồng lãi trên gốc quá hạn và 2.000 đồng lãi chậm trả trên phần lãi.
Tổng cần trả để khắc phục là 3.522.000 đồng. Nếu chị chỉ trả 1.000.000 đồng thì số nợ quá hạn giảm
nhưng hồ sơ vẫn mang trạng thái quá hạn; nếu trả đủ 3.522.000 đồng và không còn kỳ cũ chưa trả thì DPD
mới về 0.

**Công thức triển khai dành cho developer/QA:** FINORA không tự post một khoản phạt ước lượng. Công
thức đối soát khái niệm, với `dayCountBasis` lấy từ product Fineract, là:

```text
overduePrincipalInterest = overduePrincipal × overdueRate × overdueDays / dayCountBasis
overdueRate <= 150% × inTermRate

lateInterestOnUnpaidInterest = unpaidInterest × lateInterestRate × lateDays / dayCountBasis
lateInterestRate <= 10%/năm

overduePayment = overduePrincipal
    + overduePrincipalInterest
    + unpaidDueInterest
    + lateInterestOnUnpaidInterest
    + disclosedCoreFees
```

Giải thích bằng tiếng Việt:

- `overduePrincipal` — **tiền gốc đã đến hạn nhưng chưa trả**, lấy từ kỳ trả nợ quá hạn trong Fineract.
- `inTermRate` — **lãi suất trong hạn của khoản vay**, là lãi suất đã ghi trong hợp đồng; ví dụ 12%/năm.
- `overdueRate` — **lãi suất áp dụng trên phần gốc quá hạn**. Giá trị này lấy từ cấu hình sản phẩm/
  hợp đồng tại Fineract và không được vượt quá 150% `inTermRate`.
- `overdueDays` — **số ngày phần gốc trên đã bị quá hạn**, tính từ ngày phải trả đến ngày nghiệp vụ.
- `overduePrincipalInterest` — **tiền lãi phát sinh trên gốc quá hạn**, không phải một khoản phí cố định.
- `unpaidInterest` — **phần lãi đến hạn nhưng người vay chưa trả**; đây là căn cứ riêng, không phải gốc
  quá hạn.
- `lateInterestRate` — **lãi suất chậm trả áp dụng trên phần lãi chưa trả**, tối đa 10%/năm.
- `lateDays` — **số ngày phần lãi đến hạn vẫn chưa được thanh toán**.
- `lateInterestOnUnpaidInterest` — **tiền lãi chậm trả trên phần lãi chưa trả**.
- `dayCountBasis` — **mẫu số quy đổi lãi theo ngày**, thường là 360 hoặc 365 tùy sản phẩm/hợp đồng.
  FINORA đọc giá trị đã cấu hình tại Fineract, không để mobile tự chọn.
- `unpaidDueInterest` — **chính số tiền lãi gốc của kỳ đang còn thiếu**, chưa bao gồm lãi chậm trả.
- `disclosedCoreFees` — **các khoản phí đã được công bố và cấu hình trong Fineract**, nếu có.
- `overduePayment` — **tổng số tiền cần trả để xử lý nghĩa vụ quá hạn tại thời điểm tra cứu**. Giá trị
  chính thức phải lấy từ template/breakdown Fineract.

Ví dụ đối chiếu cách tính một field: 1.000.000 đồng gốc quá hạn 30 ngày, lãi suất trong hạn
12%/năm, lãi suất quá hạn 18%/năm và `dayCountBasis=365` thì `overduePrincipalInterest` xấp xỉ
`1.000.000 × 18% × 30 / 365 = 14.795 đồng`. Nếu kỳ đó còn thiếu lãi hoặc có phí đã công bố thì cộng
đúng các thành phần Fineract trả về. Ví dụ chỉ giúp đọc công thức; số thu chính thức không được suy ra
thay cho Fineract.

Số phải thu thật vẫn là template/breakdown Fineract. Công thức trên dùng kiểm tra trần và giải thích
thành phần, không tạo một sổ tính nợ song song trong Loan hoặc Payment.

### 3.5 Cơ cấu lại thời hạn trả nợ

Cơ cấu là một yêu cầu riêng, không phải quyền tự động của người vay. Người vay nộp lý do và phương án;
admin đánh giá rồi `APPROVE` hoặc `REJECT`. Chỉ sau phê duyệt Loan mới gọi Fineract reschedule và phát
event cập nhật lịch. Trong lúc chờ, nghĩa vụ cũ vẫn có hiệu lực; nếu đến hạn mà chưa được duyệt thì vẫn
phát sinh quá hạn theo lịch cũ.

Ba kết quả nghiệp vụ được phân biệt:

- điều chỉnh kỳ hạn trả nợ: thay ngày/số tiền của các kỳ nhưng không vượt thời hạn cuối đã duyệt;
- gia hạn nợ: kéo dài ngày đáo hạn;
- từ chối cơ cấu: giữ nguyên lịch và tiếp tục tính quá hạn nếu người vay không trả.

**Cách hiểu nghiệp vụ — công thức bằng lời:**

```text
Phí gửi yêu cầu cơ cấu hoặc gia hạn = 0 đồng

Lịch trả nợ mới
= Lịch do hệ thống lõi tính sau khi admin phê duyệt yêu cầu
```

Gửi yêu cầu không có nghĩa là đã được gia hạn. Trước khi có kết quả phê duyệt và Fineract xác nhận lịch
mới, người vay vẫn phải trả theo lịch cũ. Nếu bỏ kỳ trong lúc chờ thì vẫn bị tính quá hạn.

**Ví dụ đời thường:** chị E gặp khó khăn và xin kéo ngày đáo hạn thêm ba tháng. Chị không bị thu phí
khi gửi yêu cầu. Nếu admin từ chối, lịch cũ giữ nguyên. Nếu admin đồng ý nhưng hệ thống lõi chưa cập
nhật thành công, lịch cũ vẫn có hiệu lực. Chỉ khi hệ thống lõi trả về lịch mới, ứng dụng mới hiển thị các
ngày và số tiền mới.

**Công thức triển khai dành cho developer/QA:** phiên bản hiện tại không thu một `rescheduleFee` riêng
của FINORA:

```text
rescheduleFee = 0
```

Giải thích bằng tiếng Việt:

- `rescheduleFee` — **phí riêng của FINORA cho việc gửi hoặc duyệt yêu cầu cơ cấu/gia hạn**. Phiên bản
  hiện tại đặt bằng 0, vì vậy thao tác này không tự tạo lệnh trừ ví.
- Số tiền gốc, lãi, ngày đến hạn và ngày đáo hạn trong lịch mới **không nằm trong công thức trên**;
  chúng được Fineract tính lại sau khi yêu cầu được admin duyệt thành công.
- Trong thời gian yêu cầu còn `PENDING` hoặc bị `REJECTED`, hệ thống vẫn dùng lịch cũ. Nếu người vay
  không trả theo lịch cũ thì khoản vay vẫn phát sinh quá hạn bình thường.

Ví dụ đối chiếu field: khi request đang `PENDING` hoặc `REJECTED`, `rescheduleFee=0` và hệ thống vẫn
đọc lịch cũ. Chỉ sau kết quả `APPROVED` và Fineract reschedule thành công mới lưu, phát event và hiển
thị lịch mới.

Nếu sau này áp dụng phí, phải có policy version, điều khoản đã công bố/chấp thuận và quote riêng trước
khi debit ví; không được tự suy ra phí từ số kỳ được gia hạn.

### 3.6 Thu hồi và nợ xấu

Thu hồi là quy trình sau vi phạm nghĩa vụ, không đồng nghĩa “đủ 90 ngày mới bắt đầu”. FINORA tăng cấp
nhắc nợ/thu hồi theo DPD; nhóm 3–5 được xem là nợ xấu trong policy demo. Không xóa khoản vay hoặc Note.
Mọi lần liên hệ, khoản thu hồi và điều chỉnh phải có audit.

**Cách hiểu nghiệp vụ — quy tắc bằng lời:**

```text
Quá hạn từ 0 đến 9 ngày   → Nhóm 1
Quá hạn từ 10 đến 90 ngày → Nhóm 2
Quá hạn từ 91 đến 180 ngày → Nhóm 3, bắt đầu được xem là nợ xấu nội bộ
Quá hạn từ 181 đến 360 ngày → Nhóm 4
Quá hạn trên 360 ngày      → Nhóm 5

Không còn bất kỳ khoản tiền nào phải trả → Đã tất toán
Quá hạn từ 91 ngày trở lên               → Chuyển trạng thái nợ xấu
Đã chuyển nợ xấu nhưng vẫn còn quá hạn   → Tiếp tục giữ trạng thái nợ xấu
Đã trả hết phần quá hạn nhưng vẫn còn các kỳ tương lai → Trở lại đang hoạt động
```

Chuyển nợ xấu không làm phát sinh một “phí nợ xấu” riêng. Người vay vẫn phải thanh toán các khoản gốc,
lãi, phí và phạt thực tế hệ thống lõi xác nhận. Việc giữ trạng thái nợ xấu cho đến khi khắc phục hết quá hạn
ngăn trường hợp chỉ trả một khoản nhỏ rồi được xem như đã phục hồi.

**Ví dụ đời thường:** anh F không thanh toán kỳ đến hạn. Đến ngày quá hạn thứ 90, khoản vay thuộc nhóm
2. Sang ngày thứ 91, khoản vay chuyển nhóm 3 và trạng thái nợ xấu nội bộ. Anh F trả một phần làm số ngày
quá hạn quy đổi giảm còn 50 nhưng vẫn còn một kỳ cũ chưa trả đủ, vì vậy trạng thái nợ xấu vẫn được giữ.
Khi anh trả hết toàn bộ phần quá hạn, khoản vay trở lại đang hoạt động nếu còn các kỳ tương lai; nếu trả
hết toàn bộ khoản vay thì chuyển sang đã tất toán.

**Công thức triển khai dành cho developer/QA:** `DEFAULTED` là trạng thái rủi ro, không phải một khoản
phí mới và không tự xóa nghĩa vụ hợp đồng:

```text
debtGroup =
    DPD <= 9   ? 1 :
    DPD <= 90  ? 2 :
    DPD <= 180 ? 3 :
    DPD <= 360 ? 4 : 5

loanStatus =
    totalOutstanding == 0                  ? SETTLED :
    DPD >= 91                              ? DEFAULTED :
    previousStatus == DEFAULTED && DPD > 0 ? DEFAULTED :
                                             ACTIVE
```

Giải thích bằng tiếng Việt:

- `DPD` (`Days Past Due`) — **số ngày quá hạn**, tính từ nghĩa vụ cũ nhất đã đến hạn nhưng chưa được
  thanh toán đủ; lấy từ servicing projection đồng bộ từ Fineract.
- `debtGroup` — **nhóm nợ nội bộ 1–5 của FINORA/CIC mock**, dẫn xuất từ DPD theo các mốc trong công
  thức. Đây là chỉ báo phục vụ đồ án, không phải kết luận CIC thật.
- `totalOutstanding` — **tổng nghĩa vụ còn lại của khoản vay**, gồm các thành phần Fineract xác nhận.
- `previousStatus` — **trạng thái FINORA trước lần đồng bộ hiện tại**, dùng để tránh tự động xóa trạng
  thái nợ xấu chỉ vì người vay trả được một phần.
- `SETTLED` — **đã tất toán**: tổng nghĩa vụ bằng 0.
- `DEFAULTED` — **đã chuyển trạng thái nợ xấu/vi phạm nghiêm trọng**: DPD từ 91 ngày trở lên. Đây là
  trạng thái quản lý rủi ro, không phải một giao dịch trừ tiền.
- `ACTIVE` — **khoản vay vẫn đang hoạt động**. Khoản từng `DEFAULTED` chỉ trở lại `ACTIVE` khi đã trả
  hết phần quá hạn để DPD về 0 nhưng vẫn còn dư nợ tương lai.

Ví dụ đối chiếu field: tại `DPD=90`, khoản vay thuộc `debtGroup=2` và vẫn `ACTIVE`; sang `DPD=91`
thì thành `debtGroup=3` và
`DEFAULTED`. Nếu người vay chỉ trả một phần làm DPD giảm còn 50 thì khoản vay vẫn `DEFAULTED` vì chưa
khắc phục hết quá hạn. Khi DPD về 0, khoản vay trở lại `ACTIVE` nếu vẫn còn dư nợ; nếu
`totalOutstanding=0` thì chuyển `SETTLED`.

Khoản vay chỉ `cureDefault` về `ACTIVE` khi `DPD=0`, `overdueAmount=0` và vẫn còn dư nợ. Giảm từ nhóm
3 xuống nhóm 2 nhưng chưa trả hết quá hạn không đóng collection case. `defaultFee=0`; số phải trả tiếp
tục là breakdown gốc/lãi/phí/phạt chính thức từ Fineract, tránh cộng thêm một “phí nợ xấu” trùng lặp.

### 3.7 Bảng tra nhanh nghiệp vụ và kỹ thuật

**Bảng tra nhanh cho người đọc nghiệp vụ:**

| Cơ chế | Người vay cần thanh toán hoặc thực hiện gì? | Kết quả mong đợi |
|---|---|---|
| Trả đúng kỳ | Trả gốc, lãi và khoản phí/phạt của kỳ nếu có | Kỳ hiện tại được ghi nhận đã trả; lịch tương lai giữ nguyên |
| Khắc phục quá hạn | Trả toàn bộ gốc/lãi còn thiếu và khoản phát sinh do chậm trả | Chỉ khi hết phần quá hạn thì số ngày quá hạn mới về 0 |
| Trả trước một phần | Trả kỳ đang đến hạn, một phần gốc tự chọn và phí trả trước | Gốc còn nợ giảm; hệ thống tính lại các kỳ tương lai |
| Tất toán sớm | Trả toàn bộ gốc còn lại, lãi/phí/phạt đã phát sinh và phí tất toán | Khoản vay kết thúc, không còn kỳ tương lai |
| Cơ cấu/gia hạn | Gửi yêu cầu và chờ duyệt; hiện không thu phí yêu cầu | Lịch chỉ thay đổi sau khi admin duyệt và hệ thống lõi xác nhận |
| Nợ xấu/thu hồi | Tiếp tục trả nghĩa vụ quá hạn; không có phí nợ xấu riêng | Trả hết quá hạn mới phục hồi; trả hết toàn bộ thì tất toán |

**Bảng đối chiếu dành cho developer/QA:**

| Cơ chế | Số debit ví người vay | Phí FINORA hiện tại | Kết quả chính |
|---|---|---|---|
| Trả đúng kỳ | `principal + interest + coreFee + penalty` | `0` | Fineract giảm nghĩa vụ kỳ |
| Khắc phục quá hạn | `overduePayment` từ template Fineract | `0` ngoài khoản đã công bố trong core | Trả đủ mới đưa DPD về 0 |
| Trả trước một phần | `scheduledDue + prepaidPrincipal + platformFee` | Theo cùng bảng phí mục 3.3, tính trên `prepaidPrincipal` | Re-amortize lịch V2 |
| Tất toán sớm | `outstandingPrincipal + accruedInterest + coreFee + penalty + earlySettlementFee` | 0%/0,5%/1% theo policy version | Dư nợ 0, Loan/Note `SETTLED` |
| Cơ cấu/gia hạn | Không debit khi gửi hoặc duyệt yêu cầu | `0` | Chỉ đổi lịch sau khi Fineract xác nhận |
| Default/thu hồi | Không có giao dịch debit riêng; trả theo nghĩa vụ Fineract | `defaultFee=0` | DPD ≥ 91 → `DEFAULTED`; trả đủ quá hạn → `ACTIVE` |

Các tên tiếng Anh trong bảng được giữ nguyên để khớp API và mã nguồn. Khi đọc hoặc kiểm thử, dùng phần
“Giải thích bằng tiếng Việt” ngay dưới từng công thức làm định nghĩa chuẩn; không tự diễn giải một field
thành khoản phí mới. Các số tiền do Fineract cung cấp và các khoản phí policy FINORA phải luôn được hiển
thị tách biệt.

## 4. Nhóm nợ nội bộ tham chiếu

| DPD | Nhóm nội bộ | Ý nghĩa |
|---:|---|---|
| 0 | 1 — Đủ tiêu chuẩn | Không có nghĩa vụ quá hạn |
| 1–9 | 1 — Có dấu hiệu chậm | Có thể vẫn nhóm 1 nếu đánh giá có khả năng thu đủ; vẫn tính khoản quá hạn theo hợp đồng |
| 10–90 | 2 — Cần chú ý | Bắt đầu kiểm soát/nhắc nợ tăng cường |
| 91–180 | 3 — Dưới tiêu chuẩn | Nợ xấu nội bộ |
| 181–360 | 4 — Nghi ngờ | Nợ xấu nội bộ |
| Trên 360 | 5 — Có khả năng mất vốn | Nợ xấu nội bộ/thu hồi đặc biệt |

Các mốc này là benchmark phân loại nội bộ/CIC mock cho đồ án. Việc áp dụng trực tiếp quy định phân loại
nợ của tổ chức tín dụng cho pháp nhân P2P FINORA vẫn cần `LEGAL_REVIEW`; UI và báo cáo không được gọi
đây là kết luận CIC thật.

## 5. Field và trạng thái bắt buộc

### 5.1 `FinoraLoan`

| Field | Kiểu | Ý nghĩa/invariant |
|---|---|---|
| `loanNumber` | string unique | Mã khoản vay công khai, không tái sử dụng |
| `loanApplicationId` | bigint unique | Truy vết hồ sơ nguồn, không phải trạng thái khoản vay |
| `contractNumber` | string unique | Hợp đồng đã đủ chữ ký |
| `borrowerId` | string | Chủ sở hữu được phép xem/trả |
| `fineractLoanId` | bigint unique | Mapping core; không đọc DB Fineract |
| `principalAmount` | decimal(18,2) | Gốc giải ngân |
| `currency` | char(3) | MVP chỉ `VND` |
| `status` | enum | `ACTIVE`, `RESTRUCTURING`, `SETTLED`, `DEFAULTED`, `WRITTEN_OFF` |
| `disbursedAt/closedAt` | instant | Mốc vòng đời UTC |
| `version` | bigint | Chống ghi đè đồng thời |

### 5.2 `LoanServicingProjection`

| Field | Ý nghĩa |
|---|---|
| `principalDisbursed/principalPaid/principalOutstanding` | Tổng gốc theo Fineract |
| `interestCharged/interestPaid/interestOutstanding` | Tổng lãi theo Fineract |
| `feeOutstanding/penaltyOutstanding/totalOutstanding` | Phí, phạt và tổng còn phải trả |
| `overdueAmount/overdueSince/daysPastDue` | Tình trạng quá hạn |
| `nextDueDate/nextDueAmount/maturityDate` | Mốc hiển thị và nhắc nợ |
| `fineractStatusCode` | Trạng thái core cuối đã đọc |
| `source/dataAsOf/lastSyncedAt/stale` | Nguồn và độ mới bắt buộc hiển thị |
| `projectionVersion` | Bỏ update/event cũ hoặc trùng |

Worker Loan đồng bộ các khoản `ACTIVE` và `DEFAULTED` theo projection được cập nhật cũ nhất trước, không luôn lấy
20 ID nhỏ nhất. Cả lần sync thành công và lần đánh dấu stale đều cập nhật `updatedAt`, nên một khoản
Fineract đang lỗi không được chiếm batch và làm các khoản còn lại không bao giờ tăng DPD.

### 5.3 `LoanCollectionCase` và `LoanCollectionAction`

| Field | Ý nghĩa/invariant |
|---|---|
| `caseId/finoraLoanId` | Định danh episode và khoản vay; tối đa một case `OPEN` cho mỗi loan |
| `stage` | `EARLY_REMINDER`, `ATTENTION`, `NPL`, `INTENSIVE`, `LOSS`, dẫn xuất duy nhất từ DPD |
| `status` | `OPEN`, `CURED`, `SETTLED`, `WRITTEN_OFF` |
| `daysPastDue/debtGroup` | Snapshot DPD và nhóm nội bộ 1–5 từ Fineract |
| `overdueAmount/totalOutstanding/overdueSince` | Snapshot tiền/ngày từ servicing projection |
| `openedAt/lastObservedAt/closedAt/version` | Vòng đời, freshness và optimistic concurrency |
| `actionType/note/promiseDate/promiseAmount` | Nhật ký append-only; thông tin cam kết chỉ hợp lệ với `PROMISE_TO_PAY` |
| `actorId/idempotencyKey/requestHash/createdAt` | Audit người thao tác và chống gửi lặp/đổi payload |

Admin API backend:

- `GET /api/v1/admin/collection-cases?status=&stage=&page=&size=` lấy hàng đợi có phân trang;
- `GET /api/v1/admin/collection-cases/{caseId}/actions` lấy timeline hành động;
- `POST /api/v1/admin/collection-cases/{caseId}/actions` ghi hành động với `Idempotency-Key`.

Các API này không cho sửa DPD, số dư hoặc stage. Polling Fineract và `RepaymentDistributed.v1` là hai
đầu vào hợp lệ, cùng gọi một policy trong local transaction để tránh lệch trạng thái.

### 5.4 `PaymentRepayment` phía Payment

| Field | Ý nghĩa |
|---|---|
| `repaymentId/idempotencyKey` | UUID nghiệp vụ và khóa chống thu tiền hai lần; external reference là `FINORA-REPAY-<repaymentId>` |
| `loanApplicationId/fineractLoanId/borrowerId` | Khóa tương quan và quyền sở hữu |
| `repaymentType` | Hiện hành: `SCHEDULED`, `OVERDUE_CURE`, `PARTIAL_PREPAYMENT`, `EARLY_SETTLEMENT` |
| `amount/coreAmount/platformFee/currency/transactionDate` | Tổng thu, phần ghi core, phí nền tảng và ngày nghiệp vụ |
| `status` | `COLLECTED`, `CORE_POSTING`, `CORE_POSTED`, `COMPLETED`, `RECONCILIATION_REQUIRED`, `FAILED` |
| `walletLedgerTransactionId/distributionLedgerTransactionId/fineractTransactionId` | Bằng chứng thu ví, phân phối ledger và transaction core |
| `principalAmount/interestAmount/feeAmount/penaltyAmount` | Breakdown chính thức từ Fineract; tổng phải cân với amount |
| `outstanding*`, `overdueAmount`, `nextDue*` | Snapshot core sau giao dịch để phát event và cập nhật projection |
| `attemptCount/errorCode/errorDetail` | Số lần ghi core và thông tin sự cố đã lọc |
| `createdAt/updatedAt/completedAt/version` | Audit và concurrency |

`RECOVERY` là loại mục tiêu cho phase thu hồi sau này; chưa phải loại Payment API hiện hành tạo ra.

### 5.5 `EarlySettlementQuote` phía Payment

| Field | Ý nghĩa/invariant |
|---|---|
| `quoteId` | UUID dùng đúng một lần; unique với repayment |
| `loanApplicationId/borrowerId/fineractLoanId` | Ownership và tương quan với core |
| `transactionDate/expiresAt/status` | Báo giá chỉ dùng trong ngày nghiệp vụ và trước hạn; `ACTIVE → CONSUMED` |
| `principal/interest/coreFee/penalty/coreAmount` | Snapshot trả về từ `prepayLoan`; tổng các phần phải bằng `coreAmount` |
| `feeRate/platformFee/policyVersion` | Snapshot policy FINORA đã công bố, không ghi vào dư nợ Fineract |
| `totalAmount` | `coreAmount + platformFee`, đúng số tiền debit ví borrower |

API backend hiện hành:

- `POST /api/v1/repayments/early-settlement-quotes` với `loanApplicationId` tạo báo giá 15 phút;
- `GET /api/v1/repayments/early-settlement-quotes/{quoteId}` đọc báo giá của chính borrower;
- `POST /api/v1/repayments/early-settlement` với `Idempotency-Key` và `quoteId` xác nhận thu tiền.

Mọi remote call Fineract nằm ngoài transaction DB. Xác nhận báo giá, debit ví và tạo repayment nằm trong
một local transaction có row lock; báo giá hết hạn/đã dùng hoặc ví thiếu tiền không để lại trạng thái nửa vời.

### 5.6 `PartialPrepaymentQuote` phía Payment

| Field | Ý nghĩa/invariant |
|---|---|
| `quoteId/status/expiresAt/consumedAt` | UUID dùng đúng một lần; `ACTIVE → CONSUMED`, hiển thị `EXPIRED` khi quá TTL |
| `loanApplicationId/borrowerId/fineractLoanId/coreConfigVersion` | Ownership và snapshot đúng khoản vay V2 |
| `transactionDate/scheduledDue/prepaidPrincipal` | Ngày core, nghĩa vụ hiện tại và phần gốc trả thêm do borrower chọn |
| `coreAmount` | `scheduledDue + prepaidPrincipal`, là số duy nhất gửi sang Fineract |
| `feeRate/platformFee/policyVersion` | Snapshot biểu phí đã công bố; không cộng vào dư nợ core |
| `totalAmount` | `coreAmount + platformFee`, là số debit ví |
| `outstandingPrincipalBefore/nextDueDateBefore/nextDueAmountBefore` | Bằng chứng core trước khi xác nhận |
| `allocationStrategy` | Cố định `advanced-payment-allocation-strategy/REAMORTIZATION` cho V2 |

API hiện hành:

- `POST /api/v1/repayments/partial-prepayment-quotes` với `loanApplicationId`, `prepaidPrincipal`;
- `GET /api/v1/repayments/partial-prepayment-quotes/{quoteId}`;
- `POST /api/v1/repayments/partial-prepayment` với `Idempotency-Key`, `quoteId`.

## 6. Event bắt buộc

Contract chi tiết nằm tại [LOAN-INVESTMENT-EVENTS.md](integrations/LOAN-INVESTMENT-EVENTS.md).

| Event | Producer | Consumer chính | Ý nghĩa |
|---|---|---|---|
| `LoanDisbursed.v1` | Loan | Payment, Investment | Mở servicing account sau khi tiền và core đều thành công |
| `RepaymentCollected.v1` | Payment | vận hành/Loan | Tiền đã rời ví borrower, chưa khẳng định core/distribution xong |
| `RepaymentDistributed.v1` | Payment | Loan, Investment | Core đã xác nhận breakdown và ledger đã phân bổ |
| `LoanDelinquencyChanged.v1` | Loan | CIC mock hiện có; Investment/Notification dự kiến | DPD/nhóm nợ nội bộ thật sự đổi |
| `LoanRescheduled.v1` | Loan | Investment, Notification | Yêu cầu cơ cấu đã duyệt và Fineract đã trả lịch mới |
| `LoanSettled.v1` | Loan | lifecycle consumer dự kiến; Investment hiện đóng Note từ `RepaymentDistributed` | Dư nợ core bằng 0 và khoản vay đã đóng |

### 6.1 Payload `LoanDelinquencyChanged.v1`

| Field | Ý nghĩa |
|---|---|
| `loanApplicationId`, `loanNumber`, `fineractLoanId` | Định danh khoản vay và nguồn core |
| `borrowerId` | Định danh logic; không phải CCCD |
| `previousDaysPastDue`, `daysPastDue` | DPD trước/sau snapshot Fineract |
| `previousDebtGroup`, `debtGroup` | Nhóm 1–5 benchmark nội bộ, dẫn xuất từ DPD |
| `overdueAmount`, `overdueSince` | Số tiền/ngày bắt đầu quá hạn theo core |
| `totalOutstanding`, `dataAsOf` | Dư nợ của riêng khoản vay và thời điểm snapshot |

CIC mock nhận event theo group `cic-loan-delinquency`, ghi `eventId` để chống lặp và thêm
phiên bản hồ sơ thay vì sửa lịch sử. Nếu chưa có mapping `borrowerId ↔ CCCD`, event được lưu
vào hàng chờ cục bộ rồi replay khi mapping được đăng ký. `totalOutstanding` chỉ được dùng
khởi tạo hồ sơ mới; không được ghi đè `tongDuNo/tongHanMuc` của hồ sơ CIC đã tổng hợp từ
nguồn khác. Consumer lỗi dữ liệu retry hữu hạn rồi chuyển sang topic `.DLT`.

Sau khi người dùng xác nhận eKYC, User ghi hồ sơ và một `user_cic_mapping_tasks` trong cùng
transaction. Worker User gửi `keycloakUserId` làm `borrowerId` cùng CCCD đã giải mã qua HTTP nội bộ
có `X-Finora-Internal-Key`; task có lease, retry lũy tiến và backfill người dùng đã xác minh. CCCD
không được sao chép vào bảng task, Kafka, log hoặc error detail. Khi repayment đưa DPD từ giá trị dương
về `0`, Loan phải phát tiếp `LoanDelinquencyChanged.v1`; không được chỉ sửa projection vì CIC sẽ giữ
nhóm nợ cũ.

## 7. Idempotency, retry và đối soát

- Cùng `repaymentReference` với cùng request hash trả kết quả cũ; khác payload trả `409`.
- Ghi ledger và state local cùng transaction; không giữ transaction khi gọi Fineract/Kafka.
- Timeout sau thu tiền hoặc post core là kết quả **chưa xác định**, chuyển `RECONCILIATION_REQUIRED`.
- Nếu process dừng sau khi claim `CORE_POSTING`, worker đánh dấu lệnh quá lease thành
  `RECONCILIATION_REQUIRED` rồi chỉ tra cứu Fineract bằng external ID; không POST lại mù.
- Retry không thu tiền lại. Repayment có kết quả core chưa xác định chỉ được GET/reconcile bằng external
  ID; không tự POST lại.
- Consumer ghi `processed_events` cùng transaction với cập nhật Note/projection.
- Sai tổng tiền, sai currency, sai borrower, ownership stale hoặc event cũ đều fail closed và có incident.
- Admin dùng `GET /api/v1/admin/repayment-reconciliation` để xem hàng đợi và
  `POST /api/v1/admin/repayment-reconciliation/{repaymentId}/reconcile` để query Fineract theo external
  reference. Endpoint chỉ nhận `RECONCILIATION_REQUIRED`, yêu cầu `ROLE_ADMIN` và không POST repayment.
- Admin dùng `GET /api/v1/admin/loan-servicing-reconciliation` để xem projection stale và
  `POST /api/v1/admin/loan-servicing-reconciliation/{loanNumber}/reconcile` để refresh read-only từ
  Fineract. API borrower trả `dataAsOf`, `lastSyncedAt`, `stale`.
- Admin dùng `GET /api/v1/admin/loan-servicing-reconciliation/incidents` để xem sai lệch định danh/
  tài chính. Hệ thống không ghi đè projection khi `coreLoanId`, `externalId`, principal giải ngân hoặc
  tổng breakdown không khớp; incident lặp tăng `occurrenceCount` và chỉ tự đóng sau snapshot hợp lệ.

## 8. Acceptance test tối thiểu

- Trả đúng kỳ một lần: ví borrower giảm, Fineract có một transaction, ví investor tăng, Note giảm đúng gốc.
- Gửi lại cùng idempotency key không tạo ledger/core/event lần hai.
- Hai request đồng thời không làm ví âm hoặc tạo hai repayment.
- Payment đã thu nhưng Fineract timeout: tiền nằm clearing, không thu lại, state cần đối soát.
- Fineract success nhưng local save lỗi: tra cứu theo external ID rồi hoàn tất, không POST mới.
- Event `RepaymentDistributed` trùng không tăng tiền/giảm Note lần hai.
- Khắc phục đủ phần quá hạn phải phát một event đổi DPD về `0` để CIC thêm phiên bản mới nhóm 1.
- CIC tắt trong lúc eKYC không làm eKYC thất bại; khi CIC hoạt động lại, task User retry và replay
  các event quá hạn đã đến trước mapping.
- Borrower khác không xem hoặc trả khoản vay không thuộc mình.
- Tổng `principal + interest + fee + penalty` phải bằng số tiền phân bổ; phần làm tròn được ghi rõ.
- Tất toán: quote hết hạn/đã dùng không debit ví; phí platform không gửi vào Fineract; khi
  `totalOutstanding=0`, Payment account, Loan và mọi Note liên quan đều đóng qua cùng kết quả/event.
- Trả trước một phần V2: Product phải là `PROGRESSIVE`; quote chỉ dùng một lần; core chỉ nhận
  `coreAmount`; response/event dùng lịch sau giao dịch từ Fineract; V1 bị từ chối trước khi debit ví.

## 9. Nguồn tham chiếu

Các bước nghiệm thu chạy được nằm tại [LOAN-SERVICING-E2E.md](testing/LOAN-SERVICING-E2E.md).

- [Thông tư 39/2016/TT-NHNN, bản hợp nhất cập nhật 2026](https://datafiles.chinhphu.vn/cpp/files/vbpq/2026/01/06-vbhn-nhnn.pdf): Điều 13, 14, 18, 19, 23 dùng để đối chiếu lãi/phí, thứ tự thu nợ và công bố hợp đồng.
- [Biểu phí trả nợ trước hạn Vietcombank](https://www.vietcombank.com.vn/-/media/Project/VCB-Sites/VCB/KHCN/Bieu-mau-Bieu-phi-KHCN/Bieu-phi/Vay/San-pham-vay/Bieu-Phi-Tra-No-Truoc-Han-updated.pdf?ts=20240221071228): nguồn duy nhất cho policy phí demo V1.
- [Apache Fineract 1.15.0](https://fineract.apache.org/docs/1.15.0/): nguồn kỹ thuật cho core loan/schedule/transaction.
