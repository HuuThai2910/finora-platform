# Sổ đối chiếu pháp lý FINORA

> Tài liệu dùng chung cho thiết kế, code review và viết báo cáo khóa luận. Đây là bản đối chiếu kỹ thuật, không thay thế ý kiến pháp lý của luật sư/cơ quan có thẩm quyền.

- Ngày kiểm tra nguồn gần nhất: **2026-09-05**.
- Chỉ coi một yêu cầu là “đã xác minh” khi có đường dẫn tới văn bản chính thức và chỉ rõ điều/khoản liên quan.
- Mỗi service chỉ dẫn chiếu mã kiểm soát trong file này; không sao chép luật sang nhiều plan vì dễ lệch phiên bản.
- Trước khi triển khai thật hoặc khi văn bản thay đổi, owner nghiệp vụ phải rà soát lại trạng thái hiệu lực.

## 1. Nguồn chính thức đang áp dụng

| Mã nguồn | Văn bản và phần cần đọc | Hiệu lực | Link chính thức | FINORA dùng để đối chiếu |
|---|---|---|---|---|
| `LAW-CIVIL-2015` | Bộ luật Dân sự 91/2015/QH13, **Điều 468** | 01-01-2017 | [Cổng TTĐT Chính phủ](https://vanban.chinhphu.vn/default.aspx?docid=183188&pageid=27160) | Trần lãi suất theo thỏa thuận dân sự; phải xét ngoại lệ khi luật khác điều chỉnh. |
| `LAW-CREDIT-2024` | Luật Các tổ chức tín dụng 32/2024/QH15, **Điều 100** | 01-07-2024; đã được sửa đổi một phần | [Cổng TTĐT Chính phủ](https://vanban.chinhphu.vn/?classid=1&docid=211190&pageid=27160&typegroupid=3) | Phân biệt cơ chế lãi suất của tổ chức tín dụng với mô hình P2P của FINORA. |
| `LAW-P2P-2025` | Nghị định 94/2025/NĐ-CP, **Điều 1, Điều 3 và Điều 11** | 01-07-2025 | [Trang văn bản](https://vanban.chinhphu.vn/?classid=1&docid=213519&pageid=27160), [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2025/5/94-ndcp.signed.pdf) | Phạm vi sandbox, định nghĩa P2P, kiểm soát dư nợ, CIC, kênh thanh toán và thời hạn hợp đồng không quá 02 năm. |
| `CIRCULAR-P2P-2026` | Thông tư 19/2026/TT-NHNN | 30-06-2026 | [Cổng TTĐT Chính phủ](https://vanban.chinhphu.vn/?classid=1&docid=218234&pageid=27160) | Phân cấp thực hiện thủ tục hành chính của Nghị định 94; đã kiểm tra là văn bản mới liên quan nhưng hiện không tạo thêm công thức lãi, field hay state cho Loan Service. |
| `LAW-CONSUMER-2023` | Luật Bảo vệ quyền lợi người tiêu dùng 19/2023/QH15, **Điều 4, 10, 23, 25, 37, 38** | 01-07-2024 | [Trang văn bản](https://vanban.chinhphu.vn/?docid=208363&pageid=27160), [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2023/7/luat19_2023.pdf) | Minh bạch thông tin, không gây nhầm lẫn, hợp đồng rõ ràng bằng tiếng Việt, giao kết từ xa và quyền xem/tải hợp đồng. |
| `LAW-ELECTRONIC-2023` | Luật Giao dịch điện tử 20/2023/QH15, **Điều 8–11, 13, 22–23, 38** | 01-07-2024 | [Trang văn bản](https://vanban.chinhphu.vn/?classid=1&docid=208421&pageid=27160&typegroupid=3), [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2023/8/luat20-2023-qh15..pdf) | Giá trị thông điệp dữ liệu, tính toàn vẹn/lưu trữ, chữ ký điện tử và thông báo điện tử. |
| `LAW-DATA-2025` | Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15 | 01-01-2026 | [Trang văn bản](https://vanban.chinhphu.vn/?docid=214590&pageid=27160&typegroupid=3), [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2025/7/91qh.signed.pdf) | Mục đích xử lý, tối thiểu hóa dữ liệu, quyền của chủ thể dữ liệu, trách nhiệm của bên xử lý dữ liệu. |
| `DECREE-DATA-2025` | Nghị định 356/2025/NĐ-CP hướng dẫn Luật Bảo vệ dữ liệu cá nhân | 01-01-2026 | [Cổng TTĐT Chính phủ](https://vanban.chinhphu.vn/default.aspx?docid=216387&pageid=27160) | Biện pháp và hồ sơ tuân thủ chi tiết cho xử lý dữ liệu cá nhân. |

## 2. Kết luận pháp lý được chuyển thành kiểm soát hệ thống

### `LEGAL-RATE-01` — Khung min/base/max và trần lãi suất

- FINORA hiện được thiết kế như nền tảng P2P, **không tự nhận mình là tổ chức tín dụng**. Vì vậy Product áp dụng trần kỹ thuật bảo thủ `maxAnnualInterestRate <= 20%/năm` theo Điều 468 Bộ luật Dân sự.
- `minAnnualInterestRate` không phải mức tối thiểu do pháp luật ấn định. Đây là biên kinh doanh của từng Product và phải lớn hơn 0 trong thiết kế hiện tại.
- Quan hệ bắt buộc: `0 < minRate <= baseRate <= maxRate <= 20`.
- Grade AI chỉ xác định mức điều chỉnh so với `baseRate`; Loan luôn chặn kết quả trong `[minRate, maxRate]` và trần pháp lý cấu hình.
- Nếu trần tuân thủ được hạ xuống thấp hơn `baseRate` của Product cũ, Loan dừng định giá và yêu cầu rà soát Product; không âm thầm đổi điều khoản cơ sở đã công bố.
- Nếu pháp nhân vận hành sau này là tổ chức tín dụng hoặc sản phẩm chịu luật chuyên ngành khác, không được mặc định dùng kết luận 20%; phải tạo legal review mới dựa trên `LAW-CREDIT-2024` và giấy phép thực tế.

### `LEGAL-TERM-01` — Thời hạn Product và hợp đồng

- Theo điểm c khoản 1 Điều 11 Nghị định 94/2025/NĐ-CP, hợp đồng giữa bên vay và bên cho vay trong giải pháp P2P sandbox không vượt quá 02 năm.
- FINORA chặn `maxTermMonths <= 24` tại domain và database. Mốc tối thiểu 3 tháng hiện là chính sách Product, không được mô tả là mức tối thiểu theo luật.

### `LEGAL-LIMIT-01` — Dư nợ người vay

- Điều 11 Nghị định 94 yêu cầu nền tảng có biện pháp xác định/quản lý dư nợ tối đa và báo cáo, khai thác dữ liệu CIC.
- Các mức **100 triệu đồng trên một giải pháp và 400 triệu đồng trên toàn bộ giải pháp** được [Tạp chí Ngân hàng thuộc hệ thống NHNN](https://tapchinganhang.gov.vn/dam-bao-an-toan-va-hieu-qua-giao-dich-cho-vay-tren-nen-tang-so-16569.html) dẫn lại theo Quyết định 2866/QĐ-NHNN ngày 22-07-2025, nhưng đội dự án chưa lưu được bản toàn văn quyết định gốc có thể đối chiếu trực tiếp. Link này là nguồn ngành để truy vết, không thay thế văn bản gốc.
- Trạng thái: `NEEDS_PRIMARY_SOURCE`. Không được gọi hai con số này là constraint pháp lý đã xác minh trong code/plan. Có thể giữ `100 triệu` như giới hạn nghiệp vụ demo của Product cho tới khi bổ sung nguồn gốc.
- Trước production phải có User/CIC adapter và phép kiểm tra **dư nợ**, không chỉ kiểm tra số tiền của hồ sơ mới.

### `LEGAL-DISCLOSURE-01` — Công bố lãi và lịch trả

- Catalog phải ghi rõ `minRate`, `baseRate`, `maxRate` và giải thích `baseRate` là mức dự kiến trước thẩm định, không phải cam kết lãi suất cuối.
- Khi nộp hồ sơ, borrower xem lịch ban đầu tính theo `baseRate`. Sau AI, Loan tính `finalRate` rồi yêu cầu Fineract tạo lại lịch cuối.
- Trước khi ký, borrower phải xem được lãi suất cuối, tổng gốc/lãi/phí, từng kỳ trả, điều khoản và quyền từ chối. Số tiền vay và kỳ hạn đã yêu cầu không được tự đổi trong luồng hiện tại.
- Không tự giải ngân chỉ vì AI trả `APPROVED`; kết quả này chỉ tạo hợp đồng `PENDING_SIGNATURE` để borrower đọc và xác nhận.

Các kiểm soát trên triển khai Điều 4, 10, 23, 25, 37 và 38 của `LAW-CONSUMER-2023`, đặc biệt yêu cầu thông tin chính xác/đầy đủ, hợp đồng rõ ràng và người dùng xem lại, tải hợp đồng trong giao dịch từ xa.

### `LEGAL-AI-01` — Chấm điểm, tự động duyệt và khả năng giải trình

- AI trả `evaluation_score`, `credit_grade`, `decision`, `model_version`, `decision_policy_version` và phần giải thích. Loan lưu immutable request/response snapshot và policy version.
- Ngưỡng `auto_approve/auto_reject` thuộc policy cấu hình của AI; grade thuộc policy giá. Hai khái niệm không được nhập làm một.
- `APPROVED` → Loan tính giá/lịch cuối, lưu nguồn quyết định `AI_POLICY`, tạo hợp đồng chờ chữ ký. `PENDING_REVIEW` → admin xem cả dữ liệu ban đầu, dữ liệu cuối và bằng chứng AI. `REJECTED` → đóng hồ sơ với bằng chứng policy; không tính final rate và không tạo lịch cuối/hợp đồng.
- A/B/C/D/E **không phải phân loại do pháp luật quy định**. Mức `-0.5/0/+0.5/+1.0/+1.0` điểm phần trăm là policy demo có version, phải được người có thẩm quyền nghiệp vụ phê duyệt trước production và kiểm tra nguy cơ phân biệt đối xử.
- Principal/term không tự thay đổi theo grade. Nếu tương lai muốn đề nghị hạn mức hoặc kỳ hạn khác, phải tạo offer riêng và lấy lại sự đồng ý của borrower.

### `LEGAL-CONTRACT-01` — Hợp đồng và xác nhận điện tử

- Contract phải là tài liệu tiếng Việt dễ đọc, có thể xem/tải, chứa điều khoản cuối và dấu vết version/hash để phát hiện thay đổi.
- `documentHash` là bằng chứng toàn vẹn kỹ thuật; không nên hiển thị như nội dung chính cho borrower và **không tự biến thao tác click thành chữ ký số**.
- Cơ chế click-wrap hiện tại chỉ là MVP ghi nhận sự chấp thuận điện tử. Trước production phải được legal review về hình thức chữ ký phù hợp và tích hợp chữ ký điện tử/chữ ký số nếu loại hợp đồng hoặc mô hình vận hành yêu cầu.
- Căn cứ đối chiếu: `LAW-CONSUMER-2023` Điều 23, 38 và `LAW-ELECTRONIC-2023` Điều 10, 11, 13, 22, 23, 38.

### `LEGAL-DATA-01` — Dữ liệu hồ sơ và CCCD

- Chỉ thu thập dữ liệu cần cho eligibility/scoring/contract; phải có mục đích, thời hạn lưu, phân quyền và cơ chế đáp ứng quyền của chủ thể dữ liệu.
- Loan không lưu ảnh CCCD hoặc số CCCD thô, không ghi PII vào log. Khi User Service hoàn thiện, số định danh chỉ được cung cấp just-in-time qua contract được bảo vệ nếu AI thực sự cần `so_cccd`.
- Local mock gửi `so_cccd=null`; không được tạo số CCCD giả trông như dữ liệu thật rồi lưu vào assessment.
- Request/response AI, schedule và contract cần được phân loại dữ liệu, mã hóa khi lưu/truyền, giới hạn quyền đọc và có retention policy trước production.

### `LEGAL-PAYMENT-01` — Giải ngân và thanh toán

- Theo điểm b khoản 1 Điều 11 Nghị định 94, giải ngân và thanh toán khoản vay, lãi, phí phải đi qua tài khoản thanh toán tại tổ chức tín dụng/chi nhánh ngân hàng nước ngoài hoặc ví điện tử tại tổ chức trung gian thanh toán.
- FINORA không triển khai “ví nội bộ tự giữ tiền” như tài khoản pháp lý độc lập. Payment/Fineract chỉ điều phối và đối soát với nhà cung cấp được phép.
- Đây là cổng bắt buộc trước khi bật giải ngân production; không ảnh hưởng demo Loan đến trạng thái `PENDING_SIGNATURE`.

## 3. Ma trận service và điểm kiểm soát

| Chức năng | Service sở hữu | Mã pháp lý | Kiểm soát đang có | Còn thiếu trước production |
|---|---|---|---|---|
| Cấu hình Product | Loan | `LEGAL-RATE-01`, `LEGAL-TERM-01`, `LEGAL-LIMIT-01` | Domain + Flyway chặn thứ tự rate, 20% và 24 tháng | Primary source giới hạn dư nợ; legal sign-off cho mô hình pháp nhân |
| Catalog/preview/nộp hồ sơ | Loan + Web/Mobile | `LEGAL-DISCLOSURE-01` | Base rate và initial schedule snapshot; disclosure version | UI hiển thị đủ min/base/max, phí và câu chữ đã được duyệt |
| Chấm điểm AI v17 | AI + Loan | `LEGAL-AI-01`, `LEGAL-DATA-01` | Versioned decision, snapshot/hash, bounded retry | User contract cho identity; retention/access review; kiểm thử fairness |
| Định giá sau scoring | Loan + Fineract | `LEGAL-RATE-01`, `LEGAL-AI-01` | Grade adjustment, clamp, final schedule snapshot | Admin UI hiển thị so sánh base/final; quy trình phê duyệt policy |
| Duyệt và ký | Loan + Web/Mobile | `LEGAL-DISCLOSURE-01`, `LEGAL-CONTRACT-01` | Contract version/hash/expiry và trạng thái ký; mobile có phòng đọc và PDF trình bày để lưu/chia sẻ | Legal review hình thức ký; artifact PDF bất biến phía server nếu loại hợp đồng yêu cầu |
| Giải ngân/trả nợ | Loan + Payment + Fineract | `LEGAL-PAYMENT-01` | Mới ở thiết kế saga | Đối tác tài khoản/ví được phép, reconciliation và bằng chứng giao dịch |

## 4. Quy tắc cập nhật nguồn pháp lý

1. Chỉ dùng Cổng TTĐT Chính phủ, Cơ sở dữ liệu quốc gia về VBPL, Công báo hoặc cơ quan ban hành làm nguồn kết luận chính.
2. Nguồn báo chí/bài phân tích chỉ dùng để tìm văn bản; nếu chưa có toàn văn gốc thì ghi `NEEDS_PRIMARY_SOURCE`.
3. Mỗi thay đổi pháp lý phải ghi ngày kiểm tra, điều/khoản, ảnh hưởng code/API/schema và owner review.
4. Rà soát lại trước mỗi release production và tối thiểu mỗi quý; link hỏng phải được sửa trong cùng PR.
5. Thay đổi legal cap/policy không sửa migration cũ; thêm migration mới, cập nhật validation/config/test và disclosure cùng lúc.

## 5. Các quyết định cần người có thẩm quyền xác nhận

| ID | Quyết định | Trạng thái |
|---|---|---|
| `LEGAL-OPEN-01` | Pháp nhân FINORA khi vận hành là công ty P2P sandbox, tổ chức tín dụng hay đối tác của tổ chức tín dụng? | `NEEDS_LEGAL_REVIEW` |
| `LEGAL-OPEN-02` | Bản gốc Quyết định 2866/QĐ-NHNN và cách kiểm tra dư nợ 100/400 triệu | `NEEDS_PRIMARY_SOURCE` |
| `LEGAL-OPEN-03` | Click-wrap có đủ cho loại hợp đồng cụ thể hay bắt buộc tích hợp chữ ký điện tử/chữ ký số? | `NEEDS_LEGAL_REVIEW` |
| `LEGAL-OPEN-04` | Bộ reason/feature AI có tạo phân biệt đối xử hoặc dùng dữ liệu vượt mục đích đã thông báo không? | `NEEDS_POLICY_AND_PRIVACY_REVIEW` |
