# Sổ đối chiếu pháp lý FINORA

> Tài liệu dùng chung cho thiết kế, code review và viết báo cáo khóa luận. Đây là bản đối chiếu kỹ thuật, không thay thế ý kiến pháp lý của luật sư/cơ quan có thẩm quyền.

- Ngày kiểm tra nguồn gần nhất: **2026-10-03**.
- Chỉ coi một yêu cầu là “đã xác minh” khi có đường dẫn tới văn bản chính thức và chỉ rõ điều/khoản liên quan.
- Mỗi service chỉ dẫn chiếu mã kiểm soát trong file này; không sao chép luật sang nhiều plan vì dễ lệch phiên bản.
- Trước khi triển khai thật hoặc khi văn bản thay đổi, owner nghiệp vụ phải rà soát lại trạng thái hiệu lực.

## 1. Nguồn chính thức đang áp dụng

| Mã nguồn | Văn bản và phần cần đọc | Hiệu lực | Link chính thức | FINORA dùng để đối chiếu |
|---|---|---|---|---|
| `LAW-CIVIL-2015` | Bộ luật Dân sự 91/2015/QH13, **Điều 468** | 01-01-2017 | [Cổng TTĐT Chính phủ](https://vanban.chinhphu.vn/default.aspx?docid=183188&pageid=27160) | Trần lãi suất theo thỏa thuận dân sự; phải xét ngoại lệ khi luật khác điều chỉnh. |
| `LAW-CREDIT-2024` | Luật Các tổ chức tín dụng 32/2024/QH15, **Điều 100** | 01-07-2024; đã được sửa đổi một phần | [Cổng TTĐT Chính phủ](https://vanban.chinhphu.vn/?classid=1&docid=211190&pageid=27160&typegroupid=3) | Phân biệt cơ chế lãi suất của tổ chức tín dụng với mô hình P2P của FINORA. |
| `LAW-P2P-2025` | Nghị định 94/2025/NĐ-CP, **Điều 1, Điều 3, Điều 11 và Điều 22** | 01-07-2025 | [Trang văn bản](https://vanban.chinhphu.vn/?classid=1&docid=213519&pageid=27160), [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2025/5/94-ndcp.signed.pdf) | Phạm vi sandbox, định nghĩa P2P, kiểm soát dư nợ, CIC, kênh thanh toán và thời hạn hợp đồng không quá 02 năm; chia sẻ dữ liệu giữa các bên tham gia và cung cấp thông tin khoản vay cho khách hàng (Điều 11 khoản 2 điểm d, Điều 22 khoản 3 và khoản 8 điểm c, đọc trên PDF ngày 2026-10-10). |
| `CIRCULAR-P2P-2026` | Thông tư 19/2026/TT-NHNN | 30-06-2026 | [Cổng TTĐT Chính phủ](https://vanban.chinhphu.vn/?classid=1&docid=218234&pageid=27160) | Phân cấp thực hiện thủ tục hành chính của Nghị định 94; đã kiểm tra là văn bản mới liên quan nhưng hiện không tạo thêm công thức lãi, field hay state cho Loan Service. |
| `LAW-CONSUMER-2023` | Luật Bảo vệ quyền lợi người tiêu dùng 19/2023/QH15, **Điều 4, 10, 23, 25, 37, 38** | 01-07-2024 | [Trang văn bản](https://vanban.chinhphu.vn/?docid=208363&pageid=27160), [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2023/7/luat19_2023.pdf) | Minh bạch thông tin, không gây nhầm lẫn, hợp đồng rõ ràng bằng tiếng Việt, giao kết từ xa và quyền xem/tải hợp đồng. |
| `LAW-ELECTRONIC-2023` | Luật Giao dịch điện tử 20/2023/QH15, **Điều 8–11, 13, 22–23, 38** | 01-07-2024 | [Trang văn bản](https://vanban.chinhphu.vn/?classid=1&docid=208421&pageid=27160&typegroupid=3), [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2023/8/luat20-2023-qh15..pdf) | Giá trị thông điệp dữ liệu, tính toàn vẹn/lưu trữ, chữ ký điện tử và thông báo điện tử. |
| `LAW-DATA-2025` | Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15 | 01-01-2026 | [Trang văn bản](https://vanban.chinhphu.vn/?docid=214590&pageid=27160&typegroupid=3), [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2025/7/91qh.signed.pdf) | Mục đích xử lý, tối thiểu hóa dữ liệu, quyền của chủ thể dữ liệu, trách nhiệm của bên xử lý dữ liệu. |
| `DECREE-DATA-2025` | Nghị định 356/2025/NĐ-CP hướng dẫn Luật Bảo vệ dữ liệu cá nhân | 01-01-2026 | [Cổng TTĐT Chính phủ](https://vanban.chinhphu.vn/default.aspx?docid=216387&pageid=27160) | Biện pháp và hồ sơ tuân thủ chi tiết cho xử lý dữ liệu cá nhân. |
| `CIRCULAR-LENDING-CONSOLIDATED-2026` | Văn bản hợp nhất 06/VBHN-NHNN về hoạt động cho vay, **Điều 13, 14, 18, 19 và 23** | Kiểm tra 03-10-2026 | [PDF chính thức](https://datafiles.chinhphu.vn/cpp/files/vbpq/2026/01/06-vbhn-nhnn.pdf) | Tham chiếu cách công bố lãi/phí, lãi quá hạn/chậm trả, thứ tự thu nợ, cơ cấu thời hạn và nội dung thỏa thuận. Phạm vi áp dụng trực tiếp cho FINORA cần legal review theo mô hình pháp nhân. |

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
- Hiện trạng 2026-10-17 (quyết định D5): khi nộp hồ sơ, Loan cộng gốc còn lại của các khoản đang phục vụ, số tiền của
  các hồ sơ còn có thể thành khoản vay và hồ sơ mới; vượt `finora.loan.borrower-exposure.max-outstanding` (mặc định
  100.000.000 đồng) thì trả `422 BORROWER_EXPOSURE_LIMIT_EXCEEDED`. Đây là policy demo cấu hình được, chỉ tính dư nợ
  tại FINORA; chưa cộng dư nợ ở nền tảng khác nên chưa đáp ứng mức toàn thị trường (cần CIC thật, `LEGAL-OPEN-02`).
  Thiết kế: [`W2-LEGAL`](../.agents/plans/W2-LEGAL-borrower-exposure-and-investor-acknowledgement.md).

### `LEGAL-DISCLOSURE-01` — Công bố lãi và lịch trả

- Catalog phải ghi rõ `minRate`, `baseRate`, `maxRate` và giải thích `baseRate` là mức dự kiến trước thẩm định, không phải cam kết lãi suất cuối.
- Khi nộp hồ sơ, borrower xem lịch ban đầu tính theo `baseRate`. Sau AI, Loan tính `finalRate` rồi yêu cầu Fineract tạo lại lịch cuối.
- Disclosure `RATE_DISCLOSURE_V2` ghi nhận trước rằng hồ sơ được tự tiếp tục **chỉ khi** điều khoản cuối không bất lợi hơn. Loan so sánh lãi suất, phí, phạt, tổng phải trả, kỳ đầu và kỳ cao nhất bằng immutable schedule snapshots; không suy diễn từ grade hoặc quyết định AI.
- Nếu có bất kỳ chỉ tiêu bất lợi hơn, Loan lưu `PENDING` và phải có hành động chấp nhận rõ ràng của borrower gắn với `termsVersion + termsHash + expiry` trước khi tạo Contract. Im lặng/hết hạn không được xem là đồng ý.
- Trước khi ký, borrower phải xem được lãi suất cuối, tổng gốc/lãi/phí, từng kỳ trả, điều khoản và quyền từ chối. Số tiền vay và kỳ hạn đã yêu cầu không được tự đổi trong luồng hiện tại.
- Không tự giải ngân chỉ vì AI trả `APPROVED`, borrower chấp nhận điều khoản hoặc ký Contract.

Các kiểm soát trên triển khai Điều 4, 10, 23, 25, 37 và 38 của `LAW-CONSUMER-2023`, đặc biệt yêu cầu thông tin chính xác/đầy đủ, hợp đồng rõ ràng và người dùng xem lại, tải hợp đồng trong giao dịch từ xa.

### `LEGAL-DISCLOSURE-02` — Hồ sơ người vay cho nhà đầu tư

- Điều 22 khoản 3 Nghị định 94/2025/NĐ-CP yêu cầu cung cấp thông tin đầy đủ, minh bạch, chính xác cho khách hàng; khoản 8 điểm c yêu cầu công ty P2P cung cấp đầy đủ thông tin khoản vay trước khi khách hàng giao kết thỏa thuận cho vay. Nhà đầu tư vì vậy xem được hồ sơ người vay trước khi đặt lệnh: khả năng trả nợ tự khai, nhân thân không định danh (tuổi, eKYC, nhà ở, học vấn), lịch sử vay tại FINORA, điểm CIC và số lần tra cứu đọc lúc chấm, điểm/hạng/xác suất vỡ nợ, bảng luật đã chấm và khoản vay đề nghị.
- Điều 11 khoản 2 điểm d: dữ liệu khách hàng được chia sẻ minh bạch giữa các bên tham gia nhưng phải bảo mật với bên không liên quan. API `GET /api/v1/investor/loan-applications/{applicationNumber}/borrower-profile` của Loan chỉ trả cho tài khoản `ROLE_INVESTOR` và chỉ với hồ sơ đã được đưa lên sàn; hồ sơ khác trả 404 như không tồn tại.
- `LAW-DATA-2025` (tối thiểu hóa): không trả họ tên, CCCD, số điện thoại, email, địa chỉ, `borrowerId`, ghi chú/lịch sử xử lý của admin hay request/response AI thô. Phương án sử dụng vốn vẫn là chữ người vay tự nhập (đã hiện trên listing từ trước).
- Giải thích SHAP (`modelExplanation`, kể cả bản gộp `tom_tat`) và thông điệp gửi người vay chỉ dành cho thẩm định viên. Đây là quyết định nghiệp vụ của nhóm ngày 2026-10-10, không phải yêu cầu của văn bản: SHAP thô có đặc trưng rò rỉ nhãn (`int_rate`) dễ đọc sai, còn phần gợi ý viết cho chính người vay.
- Tuổi/eKYC lấy từ User khi người vay nộp hồ sơ (W2-KYC, ngày 2026-10-17): Loan chuyển tiếp token của chính người vay tới `GET /api/v1/users/me/kyc-summary`, chỉ nhận trạng thái eKYC và tuổi, lưu snapshot trên hồ sơ; chỉ người vay `VERIFIED` được nộp. Provider giả lập chỉ còn cho test và stack E2E; khi đó `profileSource = MOCK_USER_PROFILE` và app phải ghi rõ "hồ sơ giả lập".
- Xác nhận của nhà đầu tư (khoản 8 điểm c Điều 22, quyết định D6 ngày 2026-10-17): lệnh góp vốn sơ cấp mới phải mang
  `riskAcknowledgementVersion` khớp bản hiện hành (`INVESTOR_RISK_V1`); Investment lưu version và thời điểm trên lệnh
  (migration V16), thiếu thì `400`, version cũ thì `409`. Câu xác nhận trên app gồm việc đã được cung cấp và đã xem
  thông tin khoản vay cùng hồ sơ người vay ẩn danh, và rủi ro không được bảo đảm vốn. Câu chữ là policy FINORA, cần
  legal review trước production.
- Auto-Invest (Q5, Wave 2D): worker đặt lệnh thay nhà đầu tư nên xác nhận gắn với quy tắc. Bật quy tắc phải gửi đúng
  bản công bố hiện hành (`400 RISK_ACKNOWLEDGEMENT_REQUIRED`, `409 RISK_DISCLOSURE_OUTDATED`); Investment lưu version và
  thời điểm trên quy tắc (migration V17). Chỉ bật quy tắc đang tắt hoặc xác nhận bản công bố khác mới ghi thời điểm mới,
  sửa tiêu chí không ghi đè bằng chứng. Đổi bản công bố thì quy tắc chưa xác nhận lại bị tạm dừng (worker ghi
  `RISK_ACKNOWLEDGEMENT_OUTDATED`, không đặt lệnh); lệnh tự động mang version đã xác nhận như lệnh tự đặt. Xác nhận một
  lần cho cả quy tắc thay cho từng lệnh là quyết định của owner (gói Q), cần legal review trước production.

### `LEGAL-AI-01` — Chấm điểm, tự động duyệt và khả năng giải trình

- AI trả `evaluation_score`, `credit_grade`, `decision`, `model_version`, `decision_policy_version` và phần giải thích. Loan lưu immutable request/response snapshot và policy version.
- Ngưỡng `auto_approve/auto_reject` thuộc policy cấu hình của AI; grade thuộc policy giá. Hai khái niệm không được nhập làm một.
- `APPROVED` → Loan tính giá/lịch cuối, lưu nguồn quyết định `AI_POLICY`, rồi áp cùng policy xác nhận điều khoản như nhánh admin. `PENDING_REVIEW` → admin xem cả dữ liệu ban đầu, dữ liệu cuối và bằng chứng AI. `REJECTED` → đóng hồ sơ với bằng chứng policy; không tính final rate và không tạo lịch cuối/hợp đồng.
- A/B/C/D/E **không phải phân loại do pháp luật quy định**. Mức `-0.5/0/+0.5/+1.0/+1.0` điểm phần trăm là policy demo có version, phải được người có thẩm quyền nghiệp vụ phê duyệt trước production và kiểm tra nguy cơ phân biệt đối xử.
- Principal/term không tự thay đổi theo grade. Nếu tương lai muốn đề nghị hạn mức hoặc kỳ hạn khác, phải tạo offer riêng và lấy lại sự đồng ý của borrower.

### `LEGAL-CONTRACT-01` — Hợp đồng và xác nhận điện tử

- Contract phải là tài liệu tiếng Việt dễ đọc, có thể xem/tải, chứa điều khoản cuối và dấu vết version/hash để phát hiện thay đổi.
- Loan sinh và lưu nguyên bytes PDF `SIGNABLE` trong cùng transaction tạo Contract; endpoint tải chỉ trả lại bytes đã lưu, không render lại theo thiết bị. Hash PDF được đối chiếu khi consent.
- Sau click-wrap, Loan tạo thêm `SIGNED_RECEIPT` chứa bằng chứng actor/time/method; không ghi đè PDF `SIGNABLE` mà borrower đã đọc.
- Loan lưu riêng `signatureProvider`, `signatureTransactionId` và `signatureEvidenceHash`. Với local/dev, provider phải ghi rõ `MOCK` và method `CLICK_WRAP_MVP`; không được hiển thị hoặc báo cáo nó như chữ ký số SmartCA.
- Adapter VNPT SmartCA UAT chỉ đọc credential từ biến môi trường, kiểm tra chứng thư, gửi hash PDF và polling trạng thái. Contract chỉ thành `SIGNED` sau khi VNPT trả chữ ký đúng `transactionId + docId`; không lưu OTP/token/private key hoặc raw signature. Fixed signer bị khóa vào host UAT và không được bật production.
- Từ W3C (R5), hợp đồng mới có chữ ký PAdES-B-B nhúng nối tiếp (bên cho vay trước, người vay cuối) và biên nhận PDF riêng ghi SHA-256 bản ký đủ; hợp đồng trước V32 giữ bằng chứng chữ ký tách rời. Chứng thư MOCK demo (`CN=FINORA DEMO - KHONG CO GIA TRI PHAP LY`) và chứng thư VNPT UAT **không phải chữ ký số có giá trị pháp lý**; app, trang ký và biên nhận ghi rõ. Click-wrap hay chữ ký số bắt buộc cho loại hợp đồng này vẫn `LEGAL-OPEN-03`; chứng thư production theo từng bên, dịch vụ tin cậy và dấu thời gian `NEEDS_LEGAL_REVIEW`. Cấu hình và giới hạn được ghi tại `docs/integrations/VNPT-SMARTCA-UAT.md`.
- `documentHash` legacy và hash PDF là bằng chứng toàn vẹn kỹ thuật; chỉ đặt trong vùng đối chiếu, không dùng thay điều khoản chính và **không tự biến thao tác click thành chữ ký số**.
- Contract nhiều bên chỉ được lập sau `LoanFullyFunded.v2`, khi danh sách nhà đầu tư, phần vốn,
  `allocationVersion` và `allocationHash` đã khóa. Mỗi bên cho vay ký cùng PDF/hash trước, sau đó người
  vay ký; receipt phải hiển thị bằng chứng riêng của từng bên, không dựng chữ ký giả.
- PDF/text hợp đồng mới dùng `LOAN_TERMS_V2`, `CLICK_WRAP_TEXT_V3`, `CONTRACT_PDF_V2` và công bố chính
  sách servicing `FINORA_SERVICING_TERMS_V2` cùng biểu phí `AGRIBANK_REFERENCE_FINORA_2026_V2`:
  không thu lãi tương lai, bảng phí theo thời hạn, mức tối thiểu 100.000 đồng, cách chia 80/20 và cổng
  đồng thuận cơ cấu. Artifact cũ giữ nguyên bytes/hash và
  không tự nhận điều khoản mới.
- Từ Wave 2C (2026-10-17), hồ sơ trên sản phẩm core V4 nhận bộ văn bản `LOAN_TERMS_V3`, `CLICK_WRAP_TEXT_V4`,
  `CONTRACT_PDF_V3`, `FINORA_SERVICING_TERMS_V3`: điều 4 viết rõ lãi tính trên dư nợ và số ngày thực tế, khoản trả kỳ
  trước hạn được ghi nhận đúng ngày đến hạn (hủy được trước ngày đó), trả trước một phần gồm khoản của kỳ gần nhất với
  lãi chỉ tính đến ngày giao dịch. Hồ sơ core V1–V3 vẫn nhận bộ V2 nguyên văn, để hợp đồng không hứa một cách tính khác
  cách Fineract của khoản vay đó thực sự áp dụng.
- Cơ chế click-wrap vẫn là phương án mock local/dev; SmartCA hiện đủ cho UAT nhưng tài khoản test có thể
  đang dùng chung cho nhiều vai trò. Trước production phải legal review hình thức ký, xác minh đúng từng
  borrower/lender, quản trị chứng thư và bằng chứng kiểm tra chữ ký.
- Căn cứ đối chiếu: `LAW-CONSUMER-2023` Điều 23, 38 và `LAW-ELECTRONIC-2023` Điều 10, 11, 13, 22, 23, 38.

### `LEGAL-DATA-01` — Dữ liệu hồ sơ và CCCD

- Chỉ thu thập dữ liệu cần cho eligibility/scoring/contract; phải có mục đích, thời hạn lưu, phân quyền và cơ chế đáp ứng quyền của chủ thể dữ liệu.
- Loan không lưu ảnh CCCD hoặc số CCCD thô, không ghi PII vào log. Khi User Service hoàn thiện, số định danh chỉ được cung cấp just-in-time qua contract được bảo vệ nếu AI thực sự cần `so_cccd`.
- Loan đọc eKYC từ User qua `/api/v1/users/me/kyc-summary` (W2-KYC): contract cố ý chỉ có `userId`, trạng thái eKYC, cờ xác nhận giấy tờ, thời điểm hoàn tất và tuổi; không có họ tên, ngày sinh hay CCCD.
- Local mock gửi `so_cccd=null`; không được tạo số CCCD giả trông như dữ liệu thật rồi lưu vào assessment.
- Request/response AI, schedule và contract cần được phân loại dữ liệu, mã hóa khi lưu/truyền, giới hạn quyền đọc và có retention policy trước production.
- Từ W3A, AI chỉ nhận request có access token (Loan bằng service account, admin bằng phiên đăng nhập) và chỉ tra CCCD qua
  client riêng `finora-ai-client` có đúng quyền đọc hồ sơ, đáp ứng yêu cầu cung cấp số định danh just-in-time qua contract
  được bảo vệ.
- Số tài khoản ngân hàng nhận tiền rút (W3B) lưu dạng AES-256-GCM, API chỉ trả bản che `******1234`; event và log không
  mang số đầy đủ.
- Chứng thư SmartCA thật có thể chứa CCCD trong subject; khi ký PAdES, chứng thư nằm trong PDF và DB Loan. FINORA không lưu
  subject riêng, biên nhận chỉ in CN/serial/tổ chức cấp/vân tay. Dùng chứng thư thật cần legal review (`NEEDS_LEGAL_REVIEW`).
- Push (W3F) không mang số tiền, tên hay số khoản vay; nội dung chi tiết chỉ trong app sau đăng nhập. Token thiết bị chưa
  mã hóa khi lưu và chưa có retention (R8B).
- Outbox Contract chỉ chứa business ID, hash/version, trạng thái ký và thời gian; không chứa CCCD, thu nhập, raw AI payload, OTP hoặc secret provider. Kafka adapter đã có allowlist theo exact event/version nhưng mặc định tắt và chưa có route/topic hoạt động; event listing chỉ được mở sau khi Loan–Investment duyệt payload tối thiểu.
- Event servicing `LoanDelinquencyChanged.v1` chỉ chứa business ID, DPD, nhóm nợ nội bộ và
  breakdown tiền cần thiết; không chứa CCCD. Mapping `borrowerId ↔ CCCD` được bảo vệ bằng
  khóa nội bộ và chỉ lưu ở CIC mock. Đây vẫn là dữ liệu demo, không phải báo cáo CIC thật.
- B70 (kiểm 2026-10-17): bản CIC Node deploy (`cic-service-node.vercel.app`) là mock công khai — API liệt kê hồ sơ
  và nhật ký tra cứu (`/api/v1/ho-so`, `/api/v1/nhat-ky`) không xác thực, code dùng Vercel Blob `public` đường dẫn cố định khi
  có `BLOB_READ_WRITE_TOKEN` (bản deploy hiện dùng tệp tạm `/tmp`, mất khi instance khởi động lại), tra một CCCD chưa có thì CIC lưu hồ sơ mới và nhật ký kèm số CCCD. Lúc kiểm, kho chỉ có 1.000 hồ sơ mẫu và 2
  dòng nhật ký (chưa có CCCD thật). Vì vậy bản deploy chỉ dùng với CCCD tổng hợp; worker mapping của User mặc định tắt;
  dùng CCCD thật cần CIC có xác thực và kho riêng tư (owner CIC: Hải) — `NEEDS_OWNER_DECISION`.
- Owner quyết định ngày 2026-10-17 (D84): môi trường dev chỉ có dữ liệu mock nên tạm bật mapping sang bản deploy, chấp nhận dữ liệu nằm trong kho công khai; **trước khi có CCCD thật phải tắt mapping hoặc chuyển CIC sang kho riêng tư có xác thực**. Không eKYC bằng CCCD thật khi mapping đang bật.
- Blockchain proof foundation chỉ lưu/gửi SHA-256, schema version và business reference; không nhận raw PDF, CCCD, AI payload hoặc dữ liệu thanh toán chi tiết. Receipt `MOCK` phải luôn được trình bày là dữ liệu demo, không phải bằng chứng đã ghi Hyperledger Fabric.
- Từ W3D (2026-10-17) bốn mốc (hợp đồng đủ chữ ký, giải ngân, trả nợ, phát hành/chuyển nhượng Note) được neo lên Fabric local khi bật `HYPERLEDGER_FABRIC`: on-chain chỉ có proofId, loại proof, mã aggregate (chaincode chặn khoảng trắng/chữ có dấu nên không chèn được họ tên), SHA-256, version, service nguồn và metadata giao dịch. Blockchain không lưu `data` của event (số tiền, investorId); người kiểm toán lấy artifact ở service nguồn để tính lại hash. API explorer chỉ cho `ROLE_ADMIN`, receipt MOCK trả kèm cảnh báo không nằm trên chain. Xem [`W3D`](../.agents/plans/W3D-fabric-proofs.md).

### `LEGAL-PAYMENT-01` — Giải ngân và thanh toán

- Theo điểm b khoản 1 Điều 11 Nghị định 94, giải ngân và thanh toán khoản vay, lãi, phí phải đi qua tài khoản thanh toán tại tổ chức tín dụng/chi nhánh ngân hàng nước ngoài hoặc ví điện tử tại tổ chức trung gian thanh toán.
- FINORA không triển khai “ví nội bộ tự giữ tiền” như tài khoản pháp lý độc lập. Payment/Fineract chỉ điều phối và đối soát với nhà cung cấp được phép.
- Payment có operational ledger local bất biến/cân bằng, API nạp tiền và hold/capture. Provider `mock`
  chỉ mô phỏng tiền sandbox; adapter ZaloPay chỉ ghi có sau callback HMAC hợp lệ. Ledger này không phải
  tài khoản pháp lý giữ tiền và ZaloPay sandbox không chứng minh readiness tiền thật. Production vẫn
  cần hợp đồng với tổ chức cung ứng dịch vụ thanh toán, callback/reconciliation và quy trình vận hành được duyệt.
- Đây là cổng bắt buộc trước khi bật giải ngân production; không ảnh hưởng demo Loan đến trạng thái `PENDING_SIGNATURE`.
- Rút tiền (W3B, R4): chỉ có provider payout `mock` — nhận lệnh rồi báo kết quả bằng callback ký HMAC, không chuyển tiền
  thật; app ghi rõ môi trường demo. Policy tối thiểu 50.000 đ, tối đa 50.000.000 đ/ngày, phí 0 là policy demo cấu hình
  được (Q4), không phải trích dẫn luật. Cổng môi trường (D37) chặn provider mock ở staging/production; payout thật cần hợp
  đồng với tổ chức cung ứng dịch vụ thanh toán và legal review trước khi mở.

### `LEGAL-SERVICING-01` — Trả nợ, quá hạn, cơ cấu và phí trả trước

- Điều 14 và Điều 23 của `CIRCULAR-LENDING-CONSOLIDATED-2026` cho phép các bên thỏa thuận phí trả nợ trước hạn và yêu cầu ghi rõ loại/mức phí trong hợp đồng; văn bản không ấn định một tỷ lệ phí trả trước chung.
- FINORA V2 tham chiếu cấu trúc/tỷ lệ tại [biểu phí tín dụng Agribank](https://www.agribank.com.vn/wcm/connect/05371a26-a0b1-4670-a5db-15046d1517f4/2020-Bieu%2Bphi%2Btin%2Bdung%2Bkem%2Btheo%2BQD%2B738%2Bcap%2Bnhat%2BQD%2B09.pdf?MOD=AJPERES), sau đó điều chỉnh thành các dải thời gian phù hợp khoản vay tối đa 24 tháng. Mức tối thiểu 100.000 đồng, dải thời gian và cách chia phí 80% nhà đầu tư/20% FINORA là **policy demo FINORA**, không phải mức pháp luật bắt buộc; phải snapshot version/rate/minimum fee/cách chia vào hợp đồng và quote.
- Snapshot biểu phí (G-POLICY-W2, 2026-10-17): mỗi hợp đồng ghi phiên bản biểu phí trả trước đã in trong văn bản; Payment tính phí theo đúng phiên bản đó từ danh mục bất biến, không theo biến môi trường hiện hành. Hợp đồng `LOAN_TERMS_V1` (trước 2026-10-16) không có điều khoản phí trả trước nên không bị thu phí này; đây là lựa chọn thận trọng theo yêu cầu phí phải ghi rõ trong hợp đồng, cần owner xác nhận.
- Hiện trạng sau Wave 2C (2026-10-17, B28, B29): khoản vay mới trên core V4 **đáp ứng** câu dưới đây trên Fineract thật: E2E `w2c-earlysettlement-20261017-111021` tất toán ngày giải ngân thu lãi 0; E2E `w2c-partialprepayment-20261017-110733` trả trước một phần thu lãi không vượt lãi đã phát sinh. Khoản vay đã giải ngân ở core V1–V3 **vẫn không đáp ứng** nên bị chặn tự trả trước/tất toán trên app (D17); nếu người vay cần tất toán các khoản này phải xử lý thủ công, không được mô tả là tuân thủ.
- Khoản trả kỳ trước ngày đến hạn được ghi nhận đúng ngày đến hạn (D16, E2E `w2c-installment-20261017-110318`: Fineract ghi gốc/lãi đúng lịch kỳ 1), nên trả sớm tiền của kỳ không làm đổi lãi của kỳ hay các kỳ sau; người vay hủy được trước ngày đó và tiền về ví. Đây là thỏa thuận sản phẩm FINORA ghi trong hợp đồng V3, không phải yêu cầu pháp luật.
- Trả trước một phần và tất toán toàn bộ chỉ thu lãi, phí và phạt đã phát sinh đến ngày giao dịch; không thu lãi của các tháng tương lai. Đây là thỏa thuận sản phẩm FINORA chọn để khuyến khích trả sớm, không phải cách duy nhất Bộ luật Dân sự cho phép; mẫu hợp đồng phải diễn đạt nhất quán với cách tính thực tế.
- Theo Điều 13, lãi trên dư nợ gốc quá hạn không vượt 150% lãi suất trong hạn tương ứng; lãi chậm trả trên tiền lãi chưa trả không vượt 10%/năm. Cách tính phải dùng đúng căn cứ tiền, số ngày và day-count đã công bố; không cộng trùng trên cùng căn cứ.
- Điều 18 là căn cứ tham chiếu thứ tự thu nợ: gốc quá hạn, lãi trên gốc quá hạn, gốc đến hạn, lãi trên gốc đến hạn. Phí/lãi chậm trả bổ sung chỉ được thu theo contract/Fineract policy đã công bố và phải giữ breakdown.
- Cơ cấu lại thời hạn trả nợ phải có yêu cầu, đánh giá và quyết định; trạng thái chờ duyệt không tự dừng nghĩa vụ theo lịch cũ. FINORA V2 thêm cổng toàn bộ nhà đầu tư đang nắm Note đồng ý sau thẩm định nội bộ; đây là kiểm soát hợp đồng/sản phẩm nhiều bên của FINORA, không được diễn giải thành một thủ tục pháp luật mặc định cho mọi khoản vay. Mọi lịch mới lấy từ Fineract và lưu audit/version.
- Các mốc nhóm nợ/DPD trong [LOAN-SERVICING-POLICY.md](LOAN-SERVICING-POLICY.md) là benchmark nội bộ/CIC mock. Chưa được mô tả là phân loại CIC thật hoặc quy định áp dụng trực tiếp cho pháp nhân P2P cho tới khi `LEGAL-OPEN-05` được duyệt.
- CIC/AI tách `nhomNoHienTai` khỏi `nhomNoCaoNhat` lịch sử. Mốc từ chối 12 tháng và bắt buộc
  thẩm định đến 24 tháng sau khi khắc phục là policy demo cấu hình của FINORA, không phải thời hạn
  cấm vay do pháp luật ấn định. Xem [CIC-RISK-NOTIFICATION.md](integrations/CIC-RISK-NOTIFICATION.md).
- Việc thông tin tín dụng tiêu cực có thể còn được lưu/cung cấp sau khi nghĩa vụ kết thúc không được
  diễn giải thành cấm cấp tín dụng trong toàn bộ thời gian lưu. Quyết định cấp tín dụng còn phụ thuộc
  chính sách và đánh giá của bên cho vay; cần legal/policy sign-off trước production.

### `LEGAL-CHAT-01` — Trợ lý FINORA (chatbot chỉ đọc)

Phạm vi: `POST /api/v1/ai/assistant/chat`, `GET /suggestions`, `POST /feedback` của `finora-ai` (task `AI-CHAT-001`,
[spec](superpowers/specs/2026-10-15-tro-ly-finora-design.md)), màn chat trên mobile cho người vay/nhà đầu tư và ngăn chat
trên web quản trị. Trợ lý chỉ giải thích và chỉ đường; mọi thao tác có tiền, mã PIN, đồng ý điều khoản hoặc quyết định
của admin chỉ là nút mở màn hình có sẵn, nơi các control khác (`LEGAL-DISCLOSURE-01`, `LEGAL-CONTRACT-01`,
`LEGAL-PAYMENT-01`) vẫn áp dụng như cũ.

- **Thông tin đầy đủ, chính xác** (`LAW-P2P-2025` Điều 22 khoản 3; khoản 8 điểm c). Mọi số liệu trong câu trả lời và thẻ
  số liệu đọc từ API của service sở hữu bằng chính token người hỏi, ngay trong lượt đó; thẻ do code định dạng, không do mô
  hình AI viết. Bộ kiểm đầu ra so từng con số trong câu trả lời với dữ kiện của lượt; lệch thì thay bằng câu mẫu dựng từ
  dữ kiện. Không lấy được dữ liệu thì nói rõ là không lấy được, không ước đoán.
- **Không gây nhầm lẫn** (`LAW-CONSUMER-2023` Điều 10). Nội dung được gắn một trong bốn nhãn: `LUAT` (văn bản trong bảng
  nguồn ở mục 1, có link chính thức), `CHINH_SACH` (chính sách FINORA: phí tham chiếu Vietcombank, hạng A–E, ngưỡng tự
  duyệt, kỳ hạn tối thiểu 3 tháng), `CHUA_XAC_MINH` (ví dụ hạn mức 100/400 triệu của `LEGAL-OPEN-02`), `HUONG_DAN`. Căn cứ
  do code gắn từ đoạn kiến thức đã dùng; mô hình không được tự viết số điều/văn bản, câu có "Điều …", "Nghị định …" không
  nằm trong căn cứ của lượt bị thay. Trợ lý không khuyến nghị đầu tư (không xếp hạng "tốt nhất", "an toàn nhất"), mọi câu
  trả lời cho nhà đầu tư và mọi câu có căn cứ `LUAT` kèm câu miễn trừ.
- **Dữ liệu cá nhân** (`LAW-DATA-2025`, `DECREE-DATA-2025`; tối thiểu hóa theo `LEGAL-DATA-01`). Chỉ trường trong danh sách
  cho phép của từng công cụ được gửi tới mô hình; trường tự do (`purposeDetail`, ghi chú) chỉ hiện trên thẻ. Câu hỏi được
  che CCCD, số điện thoại, email, số tài khoản trước khi gửi; payload gửi ra ngoài được quét lại, còn sót thì không gửi.
  Hai làn: dữ liệu người dùng chỉ đi qua nhà cung cấp cam kết không huấn luyện trên dữ liệu (Cloudflare Workers AI, Groq,
  OpenRouter tới Google AI Studio), Gemini API gói miễn phí chỉ nhận kiến thức chung và số liệu tổng hợp. Không lưu nội dung
  hội thoại; log chỉ có mã lượt, mã băm người dùng (SHA-256 có salt), vai trò, màn, chế độ, công cụ, nhà cung cấp, model,
  số token, độ trễ, mã lỗi. Lần đầu mở chat, người dùng được báo dữ liệu đã lược định danh sẽ tới nhà cung cấp ở nước ngoài
  (có tên từng nhà) và không nhập CCCD, mật khẩu, PIN, OTP. Hồ sơ đánh giá tác động chuyển dữ liệu cá nhân ra nước ngoài
  chưa lập: `LEGAL-OPEN-06` (`NEEDS_LEGAL_REVIEW`).
- **Công bố đang trò chuyện với AI.** Thông báo lần đầu và tiêu đề màn chat ghi rõ đây là trợ lý AI, không phải nhân viên.
  Nghĩa vụ công bố theo văn bản pháp luật về trí tuệ nhân tạo chưa được đối chiếu với toàn văn chính thức:
  `LEGAL-OPEN-07` (`NEEDS_PRIMARY_SOURCE`). FINORA công bố như một biện pháp tự nguyện, không mô tả là "đã tuân thủ luật AI".
- **Giữ nguyên `LEGAL-DISCLOSURE-02` và `LEGAL-AI-01`.** Trợ lý không đưa giải thích SHAP/`dien_giai` cho người vay hay
  nhà đầu tư, không chấm lại hồ sơ từ khung chat; bản tóm tắt hồ sơ cho admin đọc snapshot chấm điểm đã lưu, đổi thu nhập
  và nợ thành khoảng, bỏ mọi định danh.

Tác động hệ thống:

| Lớp | Kiểm soát |
|---|---|
| UI | Thông báo lần đầu (AI + tên nhà cung cấp + không nhập CCCD/PIN/OTP); dòng "Căn cứ" theo nhãn; dòng "Trợ lý đang ở chế độ rút gọn" khi `mode=fallback`; câu miễn trừ; nút chat ẩn khi `GET /suggestions` không trả 200 |
| API | JWT bắt buộc; `surface=web` chỉ cho `ROLE_ADMIN`, `surface=mobile` chỉ cho `ROLE_BORROWER`/`ROLE_INVESTOR` (403 `ASSISTANT_ROLE_NOT_SUPPORTED`); `citations[].url` bắt buộc với `LUAT`; client relay chỉ gọi cặp method + đường dẫn đã khai báo |
| Log | Chỉ siêu dữ liệu nêu trên; không log câu hỏi, câu trả lời, token |
| Test | `finora-ai/tests/assistant/test_knowledge_content.py` (mọi `legal_refs` có trong bảng nguồn ở mục 1), `test_legal.py`, test của bộ lọc/bộ kiểm đầu ra, bộ đánh giá `finora-ai/tests/assistant_eval/`; nhóm ca `AI_Assistant` trong `FINORA_Test_Cases_v1.1.xlsx` |

Còn thiếu trước production: hồ sơ chuyển dữ liệu ra nước ngoài (`LEGAL-OPEN-06`); đối chiếu nghĩa vụ công bố AI
(`LEGAL-OPEN-07`); hợp đồng/điều khoản xử lý dữ liệu với nhà cung cấp trả phí thay cho gói miễn phí; quy trình rà soát nội
dung kho kiến thức mỗi khi văn bản trong mục 1 thay đổi.

## 3. Ma trận service và điểm kiểm soát

| Chức năng | Service sở hữu | Mã pháp lý | Kiểm soát đang có | Còn thiếu trước production |
|---|---|---|---|---|
| Cấu hình Product và nộp hồ sơ | Loan | `LEGAL-RATE-01`, `LEGAL-TERM-01`, `LEGAL-LIMIT-01` | Domain + Flyway chặn thứ tự rate, 20% và 24 tháng; hạn mức dư nợ tại FINORA khi nộp hồ sơ (policy demo 100 triệu) | Primary source giới hạn dư nợ; dư nợ liên nền tảng qua CIC thật; legal sign-off cho mô hình pháp nhân |
| Catalog/preview/nộp hồ sơ | Loan + Web/Mobile | `LEGAL-DISCLOSURE-01` | Base rate và initial schedule snapshot; disclosure version | UI hiển thị đủ min/base/max, phí và câu chữ đã được duyệt |
| Chấm điểm AI v17 | AI + Loan | `LEGAL-AI-01`, `LEGAL-DATA-01` | Versioned decision, snapshot/hash, bounded retry | User contract cho identity; retention/access review; kiểm thử fairness |
| Định giá sau scoring | Loan + Fineract | `LEGAL-RATE-01`, `LEGAL-AI-01` | Grade adjustment, clamp, final schedule snapshot | Admin UI hiển thị so sánh base/final; quy trình phê duyệt policy |
| Hồ sơ người vay cho nhà đầu tư | Loan + Investment + Mobile | `LEGAL-DISCLOSURE-02`, `LEGAL-DATA-01` | API chỉ đọc, `ROLE_INVESTOR`, chỉ hồ sơ đã lên sàn, allowlist không định danh và không SHAP; app ghi rõ hồ sơ giả lập; xác nhận của nhà đầu tư lưu version/thời điểm trên lệnh sơ cấp | Legal review câu xác nhận, kể cả xác nhận một lần cho quy tắc Auto-Invest (Q5) |
| Duyệt, xác nhận điều khoản và ký | Loan + Investment + Mobile | `LEGAL-DISCLOSURE-01`, `LEGAL-CONTRACT-01`, `LEGAL-SERVICING-01` | Outcome-based non-worsening gate; PDF nhiều bên bất biến; từng bên ký cùng hash; hợp đồng V2 công bố policy trả trước/cơ cấu; event qua outbox | Legal review hình thức ký và biểu phí production; danh tính/chứng thư SmartCA riêng từng bên; PAdES nếu yêu cầu |
| Bằng chứng toàn vẹn | Blockchain + service nguồn | `LEGAL-CONTRACT-01`, `LEGAL-DATA-01` | Durable hash-only proof bốn mốc, idempotency theo event và theo mốc, retry/DLT; mock được phân biệt (định dạng receipt + check constraint); Fabric local 2 tổ chức, endorsement MAJORITY, đối chiếu DB ↔ chain (W3D) | Fabric production (CA/HSM thay cryptogen, tổ chức kiểm toán thật), access/retention và legal review cách trình bày bằng chứng (`NEEDS_LEGAL_REVIEW`) |
| Giải ngân/trả nợ | Loan + Payment + Fineract + Investment | `LEGAL-PAYMENT-01`, `LEGAL-SERVICING-01` | Wallet/top-up/hold/capture, ledger cân bằng, quote trả trước có snapshot policy, phí chia 80/20, Fineract là nguồn breakdown; ZaloPay top-up sandbox và disbursement mock | Đối tác tài khoản/ví được phép, provider giải ngân/repayment thật, legal review phạm vi áp dụng và biểu phí |
| Trợ lý FINORA (chatbot chỉ đọc) | AI (đọc Loan/Investment/Payment/User/Notification bằng token người hỏi) + Web/Mobile | `LEGAL-CHAT-01`, `LEGAL-DATA-01`, `LEGAL-DISCLOSURE-02`, `LEGAL-AI-01` | JWT + vai trò theo nơi gửi; công cụ chỉ đọc theo allowlist; lọc trường và che PII trước khi gửi; hai làn dữ liệu; căn cứ do code gắn; kiểm số liệu/trích dẫn/khuyến nghị ở đầu ra; log chỉ siêu dữ liệu; câu mẫu khi mọi nhà cung cấp lỗi | Hồ sơ chuyển dữ liệu ra nước ngoài; đối chiếu nghĩa vụ công bố AI; điều khoản xử lý dữ liệu với nhà cung cấp trả phí |

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
| `LEGAL-OPEN-05` | Phạm vi áp dụng trực tiếp quy định phân loại nợ/cơ cấu của tổ chức tín dụng cho FINORA P2P và cách báo cáo CIC production | `NEEDS_LEGAL_REVIEW` |
| `LEGAL-OPEN-06` | Hồ sơ đánh giá tác động chuyển dữ liệu cá nhân ra nước ngoài khi trợ lý gửi dữ kiện đã lược định danh tới nhà cung cấp mô hình AI (Cloudflare, Groq, OpenRouter/Google) theo `LAW-DATA-2025`, `DECREE-DATA-2025` | `NEEDS_LEGAL_REVIEW` |
| `LEGAL-OPEN-07` | Nghĩa vụ công bố cho người dùng biết đang tương tác với hệ thống AI theo văn bản pháp luật về trí tuệ nhân tạo; chưa lưu được toàn văn chính thức để đối chiếu | `NEEDS_PRIMARY_SOURCE` |
