---
task_id: INV-E1
roadmap_id: P7-B02
status: IN_PROGRESS
owner: Hai
approved_by:
approved_at:
scope: Chợ thứ cấp Notes — luồng nghiệp vụ
depends_on: Task #10 (Gọi vốn + Notes) đã phát hành Note
note: Backend đã triển khai 2026-09-22 theo yêu cầu của owner, plan chưa được Thái duyệt
---

# INV-E1 — Chợ thứ cấp Notes: luồng nghiệp vụ

> Plan này **chỉ mô tả luồng nghiệp vụ và quy tắc**. Chưa đặc tả entity, API contract hay
> cấu trúc code — phần đó viết sau khi flow được duyệt.

## Bản đọc nhanh

Nhà đầu tư đang giữ Note nhưng cần tiền trước hạn. Thay vì đợi người vay trả hết, họ treo
Note lên bảng tin để nhà đầu tư khác mua lại.

```text
Nhà đầu tư A giữ Note (dư nợ 1.000.000đ, còn 6 tháng)
→ A treo bán, tự nhập giá 950.000đ
→ Nhà đầu tư B thấy trên bảng tin, bấm mua
→ B trả 950.000đ; nền tảng thu phí 5% (47.500đ); A nhận 902.500đ
→ Note đổi chủ: từ giờ B nhận gốc và lãi
→ Tin đăng bán đóng lại
```

A mất 97.500đ so với dư nợ, đổi lấy tiền ngay. B trả 950.000đ để nhận về 1.000.000đ gốc
cộng toàn bộ lãi 6 tháng còn lại. Cả hai đều có lý do đồng ý.

---

## 1. Vấn đề cần giải

Note là khoản đầu tư có kỳ hạn. Sau khi phát hành, nhà đầu tư bị giữ vốn tới khi người vay
trả hết — 6 tháng, 12 tháng, hoặc lâu hơn. Không có cách nào lấy tiền ra sớm.

Chợ thứ cấp giải đúng chuyện đó: tạo **thanh khoản** cho một tài sản vốn không có thanh khoản.
Đây cũng là lý do task #10 xé vốn thành Note mệnh giá cố định thay vì một khối lớn — Note nhỏ
thì dễ tìm người mua hơn.

---

## 2. Bốn khái niệm

| Khái niệm | Nghĩa |
|---|---|
| **Note** | Một phần vốn đã phát hành, có dư nợ gốc riêng và chủ sở hữu riêng |
| **Tin đăng bán** | Lời chào bán một Note cụ thể, kèm giá người bán muốn |
| **Người bán** | Nhà đầu tư đang sở hữu Note, muốn thoát trước hạn |
| **Người mua** | Nhà đầu tư khác, chấp nhận giá và nhận quyền sở hữu Note |

Chợ này là **bảng tin**, không phải sổ lệnh: mỗi tin là một Note cụ thể với một giá cụ thể.
Không có khớp lệnh tự động, không có giá thị trường chung. Người mua chọn đúng tin mình muốn.

---

## 3. Quy tắc định giá — phần quan trọng nhất

Người bán tự nhập giá, nhưng hệ thống **chặn trên ở mức dư nợ gốc còn lại của Note**.

### Vì sao phải chặn

Nếu để người bán tự do đặt giá, họ có thể treo Note dư nợ 1.000.000đ với giá 1.200.000đ.
Người mua bỏ 1.200.000đ để nhận về dòng tiền gốc chỉ 1.000.000đ — lỗ thẳng. Chợ như vậy
không ai mua, và nó cho phép một người dùng đặt bẫy người dùng khác.

### Vì sao trần là dư nợ gốc

Note còn mang **lãi tương lai** mà người mua sẽ nhận. Với dư nợ 1.000.000đ, lãi 15%/năm,
còn 6 tháng, người mua nhận về khoảng 1.075.000đ. Nếu chặn trần ở đúng dư nợ gốc
(1.000.000đ) thì:

- Người mua **luôn có lợi**: trả tối đa bằng gốc, nhận gốc cộng toàn bộ lãi còn lại.
- Người bán **luôn chịu lỗ phần lãi** đã tích lũy — đó là giá của việc lấy tiền ngay.

Chọn trần này thay vì "gốc + lãi còn lại" vì mức sau đòi hỏi tự tính lãi tương lai, tức tự
đặt ra một công thức tài chính. Rule `01` của repo cấm frontend và service tự phát minh công
thức tín dụng khi chưa có căn cứ nghiệp vụ được duyệt. Trần bằng dư nợ gốc không cần công
thức nào — nó là con số đã có sẵn trên Note.

### Sàn giá

Không đặt sàn. Người bán muốn bán rẻ đến đâu là quyền của họ; bán rẻ chỉ làm họ mất nhiều
hơn, không gây hại cho người mua.

### Phí chuyển nhượng 5%, trừ của người bán

Nền tảng thu 5% trên giá bán, **trừ vào số tiền người bán nhận được**. Người mua trả đúng
giá treo, không phát sinh thêm đồng nào.

```text
Giá treo            950.000đ
Người mua trả       950.000đ
Phí 5%               47.500đ   → nền tảng
Người bán nhận      902.500đ
```

Trừ của người bán chứ không phải người mua vì hai lý do:

- Người bán là bên cần dịch vụ — họ muốn thoát trước hạn, nền tảng cung cấp chỗ để làm điều đó.
- Nếu trừ của người mua thì tổng tiền họ bỏ ra là *giá treo cộng 5%*, và con số đó có thể
  vượt dư nợ gốc — phá đúng nguyên tắc ở trên. Ví dụ Note dư nợ 1.000.000đ treo giá 980.000đ,
  người mua phải trả 1.029.000đ để nhận về 1.000.000đ gốc: lỗ. Muốn giữ nguyên tắc thì phải
  hạ trần giá xuống khoảng 952.000đ, tức trần phụ thuộc vào mức phí — phức tạp mà không cần thiết.

Phí tính trên **giá bán**, không phải trên dư nợ gốc: nền tảng ăn theo giá trị giao dịch thật.

Mức 5% là policy nghiệp vụ của bản demo, không phải quy định pháp luật. Ghi rõ để không lẫn
với các trần lãi và trần dư nợ có căn cứ văn bản.

---

## 4. Luồng đăng bán

1. Nhà đầu tư mở danh mục, chọn một Note đang sở hữu.
2. Nhập giá mong muốn. Giao diện hiện sẵn dư nợ gốc làm mốc và chặn ngay nếu nhập vượt.
3. Giao diện hiện **số tiền thực nhận sau phí 5%**, không để người bán tự tính.
4. Nếu Note đang nợ xấu, cảnh báo rõ trước khi xác nhận (mục 8.2).
5. Xác nhận treo bán.

Sau bước này Note **vẫn thuộc người bán** và **vẫn nhận gốc lãi bình thường**. Treo bán chỉ
là lời chào, không phải chuyển quyền. Nếu người vay trả nợ trong lúc Note đang treo, dư nợ
gốc giảm — nên giá đang treo có thể vượt trần mới.

### Xử lý khi dư nợ giảm dưới giá đang treo

Hệ thống kiểm tra lại trần **tại thời điểm mua**, không chỉ lúc đăng. Nếu giá treo đã vượt
dư nợ hiện tại, giao dịch bị từ chối và tin đăng được đánh dấu cần cập nhật giá. Không tự
hạ giá thay người bán — giá là quyết định của họ.

---

## 5. Luồng mua

1. Người mua xem bảng tin: Note nào đang bán, dư nợ bao nhiêu, giá bao nhiêu, lãi suất và
   kỳ hạn còn lại của khoản vay gốc, và **Note có đang nợ xấu hay không** — phân biệt được
   ngay trên danh sách, không phải mở từng tin.
2. Bấm mua một tin cụ thể. Nếu Note đang nợ xấu, cảnh báo rõ trước khi xác nhận (mục 8.2).
3. Hệ thống kiểm tra theo thứ tự:
   - Tin còn mở (chưa ai mua, chưa bị huỷ)
   - Người mua **không phải** người bán
   - Giá treo không vượt dư nợ gốc hiện tại
   - Ví người mua đủ tiền
4. Chuyển tiền: trừ ví người mua đúng giá treo, cộng vào ví người bán phần sau khi trừ phí 5%.
5. Đổi chủ sở hữu Note.
6. Đóng tin đăng bán.

Ghi nhận phí phải nằm cùng chỗ với việc chuyển tiền, để không có trạng thái tiền đã trừ mà phí
chưa ghi hoặc ngược lại.

### Vì sao không cho tự mua Note của mình

Mua Note của chính mình không đổi gì về quyền sở hữu nhưng vẫn tạo một giao dịch tiền và một
bản ghi lịch sử. Đó là đường để làm giả khối lượng giao dịch trên sàn.

---

## 6. Ranh giới trách nhiệm

| Việc | Ai làm | Vì sao |
|---|---|---|
| Bảng tin, tin đăng bán, quyền sở hữu Note | **`finora-investment`** | Rule `07` ghi Investment sở hữu "Note ownership và secondary market" |
| Quyết định **mức phí** và tính số tiền | **`finora-investment`** | Phí là policy của sàn thứ cấp, thuộc nghiệp vụ của service này |
| **Chuyển tiền giữa hai ví và thu phí** | `finora-payment` | Rule `07`: *"Investment MUST NOT tự trừ tiền. Mọi hold/release/capture đi qua Payment"* — Investment nói số tiền, Payment thực hiện |
| Dòng tiền gốc lãi của khoản vay | `finora-loan` + `finora-payment` | Chuyển nhượng Note không đổi nghĩa vụ của người vay |

Người vay **không liên quan** tới giao dịch này. Họ vẫn trả đúng lịch, đúng số tiền; chỉ đích
đến của phần tiền đó đổi sang người mua.

---

## 7. Giới hạn của bản demo

Việc chuyển tiền giữa hai ví cần khả năng `capture` bên Payment mà hiện chưa có: cổng giao
tiếp hiện chỉ có `hold` (giữ tiền) và `release` (nhả về đúng người đó), không có đường đưa
tiền từ ví A sang ví B.

Phương án cho bản demo, giữ nguyên tinh thần task #10 đã làm:

| Thành phần | Bản demo | Bản thật |
|---|---|---|
| Chuyển tiền giữa hai ví | Ví giả lập trong bộ nhớ | `finora-payment` qua cùng cổng giao tiếp |
| Thu phí 5% | Ví giả lập ghi nhận, chưa có sổ thu của nền tảng | `finora-payment` hạch toán vào sổ |
| Quyền sở hữu Note | **Ghi database thật** | Không đổi |
| Tin đăng bán | **Ghi database thật** | Không đổi |
| Kiểm eKYC người mua | **Chưa kiểm** (mục 8.4) | Đọc trạng thái từ `finora-user` |
| Kiểm trần dư nợ 100tr/400tr | **Chưa kiểm** (mục 8.1) | Khi có bản gốc văn bản |

Chỉ phần tiền là giả. Quyền sở hữu và tin đăng bán lưu thật, nên bán xong khởi động lại
service thì Note vẫn thuộc người mua — đây là điều kiện để chứng minh nghiệp vụ chuyển nhượng
hoạt động, không chỉ là hiệu ứng trên màn hình.

Ví giả lập phải tuân thủ **đúng hợp đồng chống trùng lặp** mà ví thật sẽ phải tuân thủ: gọi
lại cùng một mã giao dịch chỉ chuyển tiền một lần. Nếu không, bản demo sẽ chạy đúng mà bản
thật sai.

---

## 8. Bốn quyết định nghiệp vụ đã chốt

Chốt ngày 2026-09-22.

### 8.1. Chưa kiểm trần dư nợ theo pháp luật

Mua Note làm tăng dư nợ của người mua trên nền tảng, và Quyết định 2866/QĐ-NHNN đặt mức 100
triệu trên một giải pháp, 400 triệu toàn bộ. Bản này **chưa kiểm** mức đó.

Lý do: `docs/LEGAL-COMPLIANCE.md` ghi `LEGAL-OPEN-02` — đội dự án chưa có bản gốc quyết định
và chưa có cách kiểm dư nợ toàn hệ thống. Kiểm theo một con số chưa truy được nguồn thì tệ hơn
là ghi rõ chưa kiểm.

**Nợ kỹ thuật:** khi có bản gốc văn bản và cách đọc dư nợ toàn nền tảng, bổ sung chốt chặn này
vào cả đặt lệnh sơ cấp lẫn mua thứ cấp — hiện cả hai đều chưa có.

### 8.2. Cho bán Note nợ xấu, nhưng phải cảnh báo rõ ở cả hai phía

Note trạng thái `DEFAULTED` **được phép** treo bán. Đây chính là lúc người bán cần thoát nhất.

Kèm điều kiện bắt buộc: giao diện phải nói rõ tình trạng nợ xấu ở **cả hai** thời điểm —

- **Lúc đăng bán:** cảnh báo cho người bán biết Note này đang nợ xấu, giá bán sẽ phản ánh điều đó.
- **Lúc mua:** cảnh báo cho người mua trước khi xác nhận, không chỉ là một nhãn nhỏ trong bảng.

Người mua phải hiểu mình đang mua một khoản đang có vấn đề thu hồi, không phải một Note bình
thường giá rẻ. Không được để trạng thái này chỉ hiện bằng màu sắc — rule về giao diện cấm dùng
màu làm tín hiệu duy nhất.

Bảng tin cũng phải cho phép phân biệt Note bình thường và Note nợ xấu khi xem danh sách, để
người mua không phải mở từng tin mới biết.

### 8.3. Phí chuyển nhượng 5%, trừ của người bán

Xem mục 3. Tóm lại: người mua trả đúng giá treo, nền tảng thu 5% trên giá bán, người bán nhận
phần còn lại.

### 8.4. Người mua phải đã xác minh danh tính — ghi yêu cầu, triển khai sau

Về nghiệp vụ, chỉ nhà đầu tư đã hoàn tất eKYC mới được mua Note.

Bản demo **chưa kiểm** điều kiện này, vì `finora-investment` hiện không có đường đọc trạng thái
eKYC từ `finora-user`. Đáng chú ý: đặt lệnh sơ cấp ở task #10 cũng không kiểm eKYC, nên việc
chưa kiểm ở đây là **nhất quán** với hệ thống hiện tại, không phải bỏ sót riêng của E1.

**Nợ kỹ thuật:** thêm cổng đọc trạng thái eKYC và áp cho cả hai luồng — đặt lệnh sơ cấp và mua
thứ cấp — trong cùng một lần, để hai đường mua vốn không có hai chuẩn khác nhau.

---

## 9. Điều kiện để bắt đầu

| Điều kiện | Trạng thái |
|---|---|
| Task #10 phát hành được Note | Có, nhưng còn 3 blocker (migration rỗng, thiếu endpoint duyệt lên sàn, chưa có đường tạo niêm yết) |
| Note có dòng tiền chạy qua | **Chưa** — bản ghi Note chưa có phần ghi nhận gốc lãi thu được |
| Chuyển tiền giữa hai ví | **Chưa** — dùng ví giả lập cho bản demo |

Mục 2 đáng lưu ý: chợ thứ cấp bán lại Note để lấy tiền trước hạn, nhưng nếu Note chưa bao giờ
thu được đồng nào thì giá trị của nó chưa thay đổi kể từ lúc phát hành. Demo vẫn chạy được,
song phần "vì sao cần thanh khoản" sẽ thiếu sức thuyết phục.

---

## 10. Cách kiểm chứng

Luồng phải chứng minh được, không chỉ chạy:

| Tình huống | Kết quả mong đợi |
|---|---|
| Bán rồi khởi động lại service | Note vẫn thuộc người mua |
| Treo giá vượt dư nợ gốc | Bị từ chối ngay khi đăng |
| Dư nợ giảm xuống dưới giá đang treo, rồi có người mua | Bị từ chối tại thời điểm mua |
| Hai người mua cùng một tin cùng lúc | Đúng một người thành công |
| Tự mua Note của mình | Bị từ chối |
| Gọi lại cùng mã giao dịch sau lỗi mạng | Tiền chỉ chuyển một lần, phí chỉ thu một lần |
| Ví người mua không đủ tiền | Từ chối, Note không đổi chủ, tin vẫn mở |
| Bán Note giá 950.000đ | Người mua trừ đúng 950.000đ; người bán nhận 902.500đ; phí ghi nhận 47.500đ |
| Treo bán Note đang nợ xấu | Cho phép, nhưng cảnh báo hiện ở cả màn đăng bán và màn xác nhận mua |
| Xem bảng tin có lẫn Note nợ xấu | Phân biệt được ngay trên danh sách, không chỉ bằng màu sắc |

---

## 11. Bản đồ code thực tế — backend, ngày 2026-09-22

### Cơ sở dữ liệu

| Thành phần | Vị trí |
|---|---|
| Schema 5 bảng của task #10 | `db/migration/V1__create_funding_and_notes.sql` |
| Schema chợ thứ cấp | `db/migration/V2__create_secondary_market.sql` |

`V1` không thuộc E1 — trước đây thư mục migration rỗng trong khi cấu hình bật Flyway và đặt
`ddl-auto: validate`, nên service không khởi động được trên database trắng. Phải có `V1` mới có
chỗ đặt `V2`.

Các chốt chặn nằm ở tầng dữ liệu, không chỉ ở code: giá bán không vượt dư nợ chụp lúc đăng, tiền
người bán cộng phí luôn bằng giá bán, người mua khác người bán, một Note chỉ có một tin đang mở
(index một phần theo `status = 'OPEN'`, nên Note bán rồi vẫn treo lại được), một tin chỉ sinh một
lần chuyển nhượng, một mã thanh toán chỉ ghi một lần.

### Mã nguồn

| Lớp | Vị trí |
|---|---|
| Entity tin đăng bán | `domain/secondary/NoteListing.java` |
| Trạng thái tin đăng bán | `domain/secondary/NoteListingStatus.java` — `OPEN`, `SOLD`, `CANCELLED` |
| Entity lịch sử chuyển nhượng (bất biến) | `domain/secondary/NoteTransfer.java` |
| Repository | `repository/NoteListingRepository.java`, `repository/NoteTransferRepository.java` |
| Điều phối, tính phí | `service/impl/SecondaryMarketServiceImpl.java` |
| Hai transaction ngắn | `service/impl/SecondaryMarketTransactionServiceImpl.java` |
| API | `controller/SecondaryMarketController.java` |
| Cổng chuyển tiền | `client/PaymentClient.java` thêm `transfer`, kết quả ở `client/PaymentTransferResult.java` |
| Ví giả lập | `client/impl/StubPaymentClient.java` |

### API

Tất cả dưới `/api/v1/investments/secondary`, **yêu cầu đăng nhập** — khác sàn sơ cấp vốn mở cho
khách chưa đăng nhập, vì tin đăng bán mang mã nhà đầu tư của người bán.

| Method | Path | Việc |
|---|---|---|
| GET | `/listings` | Bảng tin, tin đang mở |
| GET | `/my-listings` | Tin của chính mình |
| POST | `/notes/{noteId}/listings` | Treo bán |
| POST | `/listings/{listingReference}/buy` | Mua |
| DELETE | `/listings/{listingReference}` | Rút tin |

Việc mua không cần `Idempotency-Key` từ phía gọi: mã tin đã là khóa tự nhiên — một tin chỉ bán
được một lần — nên mã chống trùng lặp suy ra từ đó (`NTRF-{listingReference}`).

### Ba bước của việc mua

Giữ nguyên nguyên tắc của task #10: không giữ database transaction trong lúc chờ mạng.

1. `lockAndValidateForPurchase` — khóa bi quan tin đăng bán, kiểm 6 điều kiện, chưa chạm tiền.
2. `paymentClient.transfer` — **ngoài transaction**.
3. `recordPurchase` — đổi `investorId` của Note, đóng tin, ghi lịch sử, trong một transaction.

Bước 1 kiểm lại trần giá theo dư nợ **hiện tại** của Note, không tin con số chụp lúc đăng: người
vay có thể đã trả nợ trong lúc tin treo.

Nếu bước 3 hỏng sau khi tiền đã chuyển thì **không** hoàn tiền — chuyển nhượng là dứt điểm, không
có bước nhả như giữ chỗ. Lần gọi lại nhận ra mã thanh toán đã dùng và ghi nhận tiếp phần còn thiếu.

### Một quyết định đổi so với dự kiến

Ban đầu định thêm `LISTED_FOR_SALE` vào `NoteStatus`. **Đã bỏ.** Danh mục đầu tư lọc Note theo
`status = ACTIVE`, nên đổi trạng thái Note khi treo bán sẽ làm Note biến mất khỏi danh mục người
bán — sai, vì họ vẫn sở hữu và vẫn nhận gốc lãi cho tới khi có người mua. Trạng thái đăng bán vì
vậy chỉ nằm trên `note_listings`, đúng như mục 4 của plan đã ghi.

### Kiểm chứng đã chạy

| Hạng mục | Kết quả |
|---|---|
| Biên dịch | Sạch |
| Ràng buộc dữ liệu của `V1` + `V2` | 17 tình huống, chạy trên PostgreSQL thật qua một database tạm rồi xoá — tất cả đúng |
| Ví giả lập, gồm 4 bài mới cho `transfer` | 6/6 đúng |
| Các bài kiểm thử đơn vị còn lại | 3/3 đúng |
| `FundingFlowIT`, `FinoraInvestmentApplicationIT` | **Chưa chạy được** — Testcontainers cần Docker, máy phát triển chưa bật |

### Còn thiếu

- Bài kiểm thử tích hợp cho chợ thứ cấp: hai người mua cùng lúc, bán rồi khởi động lại, dư nợ
  giảm dưới giá treo. Cần Docker.
- Giao diện web và mobile.
- Cảnh báo nợ xấu ở tầng giao diện. Backend đã trả cờ `defaulted` và câu lý do; phần hiển thị
  chưa làm.
- `PaymentClient` là cổng giáp ranh với `finora-payment`. Việc thêm `transfer` nằm trong
  `finora-investment` nên không sửa module của owner khác, nhưng hợp đồng này cần owner Payment
  xem trước khi nối bản thật.
