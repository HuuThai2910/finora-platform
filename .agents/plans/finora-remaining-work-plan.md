---
task_id: FINORA-REMAINING
title: Hoàn thiện FINORA sau vertical slice giải ngân và servicing
owner: Both
status: ACTIVE
depends_on: [P5, P6]
created_at: 2026-10-16
updated_at: 2026-10-17
---

# FINORA-REMAINING — Kế hoạch chi tiết phần việc còn lại

## Bản đọc nhanh theo nghiệp vụ

### Hệ thống đã làm được gì?

FINORA đã có vertical slice thật cho đăng nhập/eKYC, nộp và chấm điểm hồ sơ, duyệt, gọi vốn,
giữ tiền, hợp đồng nhiều bên, SmartCA UAT, giải ngân, tạo Note, trả nợ, trả trước, tất toán,
quá hạn/default/cure và yêu cầu cơ cấu có nhà đầu tư biểu quyết. Web quản trị và mobile mới đã
kết nối các API chính; backend không còn là skeleton.

### Còn thiếu gì để demo ổn định?

Ưu tiên không phải thêm một luồng nghiệp vụ lớn, mà là biến luồng hiện có thành bộ demo có thể
lặp lại: dựng môi trường bằng một quy trình, tạo dữ liệu sạch, chạy từ đầu đến một kỳ trả nợ,
chứng minh retry/restart không làm trùng tiền, sửa projection/Note khi event đến trễ và có màn hình
đối soát rõ ràng.

### Còn thiếu gì nếu tiến gần production?

- Cổng tiền ra/rút tiền thật; hiện top-up có mock/ZaloPay sandbox nhưng disbursement ZaloPay chưa chạy thật.
- Chữ ký PAdES nhúng vào PDF; hiện SmartCA UAT ký hash và lưu bằng chứng rời.
- Fabric adapter/network/chaincode thật và proof cho nhiều mốc ngoài repayment.
- Push notification thật, RBAC/PII/security hardening, observability và deployment hoàn chỉnh.
- Model governance, fraud nâng cao, secondary market/auto-invest hardening.

### Điều đã chốt và không đưa lại vào backlog

- CIC là service Node deploy tại `https://cic-service-node.vercel.app/`; FINORA chỉ cấu hình URL.
- Mobile phải dùng bộ UI mới, không phục hồi màn mock/UI cũ.
- Fineract là nguồn chuẩn lịch trả, nghĩa vụ và breakdown; frontend không tự tính lại tiền.
- Payment ledger là nguồn chuẩn dịch chuyển tiền nội bộ; Investment là nguồn chuẩn Note/ownership.
- Một PDF hợp đồng dùng chung cho các bên; lender ký trước, borrower ký sau.

## 1. Current state và phạm vi

### 1.1 Nguồn sự thật

| Phạm vi | Nguồn sự thật | Không được làm |
|---|---|---|
| Identity/eKYC | User + Keycloak; CIC qua REST ngoài Compose | Không đọc DB User/CIC chéo |
| Hồ sơ, hợp đồng, servicing | Loan + Fineract projection | Không tính lịch/lãi/phí ở web/mobile |
| Tiền, hold, thu/chi | Payment ledger/provider reference | Không sửa số dư trực tiếp |
| Listing, allocation, Note | Investment | Không suy luận ownership từ Loan |
| Bằng chứng | Blockchain hash proof | Không lưu PII/PDF lên chain |
| Thông báo | Notification delivery projection | Không để lỗi thông báo rollback tiền |

### 1.2 Ngoài phạm vi của plan tổng hợp

Plan này không tự thêm entity/API/event. Mỗi workstream khi bắt đầu phải có task plan riêng theo
`09-planning-documentation.md`, mô tả field, ERD/cardinality, query/index, transaction, concurrency,
idempotency và acceptance evidence. CIC Node được xem là dependency bên ngoài, không triển khai lại.

## 2. Thứ tự triển khai bắt buộc

```text
R0 Đồng bộ source và tài liệu
 → R1A chạy baseline E2E, ghi nhận sai lệch và khóa G-POLICY
 → R2 Saga/Fineract/financial recovery || R3 Investment projection/reconciliation
 → R1B chạy lại E2E và toàn bộ regression servicing
 → R8A security gate tối thiểu
 → R4 tiền ngoài hệ thống
 → R5 PDF || R6 Blockchain || R7 Notification
 → R8B security hardening || R9 observability/deployment
 → R10 AI || R11 secondary market/auto-invest nâng cao
```

`||` nghĩa là hai workstream có thể chạy song song nếu không sửa cùng contract/vùng ownership. R1A
không đòi mọi fault test phải xanh: mục tiêu là dựng baseline lặp lại và ghi defect có evidence. R1B chỉ
được nghiệm thu sau R2/R3, nhờ đó không tạo vòng phụ thuộc “phải sửa xong mới được phép tìm lỗi”.

R1B–R3 và G-POLICY là cổng trước khi mở rộng R4–R7. R8A là cổng riêng trước mọi provider tiền/chữ ký
ngoài hệ thống. Không gọi một tính năng là hoàn tất chỉ vì UI trả `200`; phải kiểm tra state tại đúng
SoR, ledger/event và khả năng retry.

## 3. Danh mục tồn đọng

| ID | Ưu tiên | Owner | Phạm vi | Trạng thái | Kết quả cần đạt |
|---|---|---|---|---|---|
| R0 | P0 | Both | Source/docs | `DONE` | Backend, web, mobile cùng mốc code; roadmap/rule khớp code |
| R1 | P0 | Both | E2E | `IN_PROGRESS` | R1A baseline tự động đã chạy thật ngày 2026-10-17; R1B chạy sạch core và toàn bộ servicing sau R2/R3 |
| G-POLICY | P0 | Both | Policy/Contract | `IN_PROGRESS` | Khoản vay cũ/mới dùng đúng policy version và hợp đồng/quote/UI cùng một công thức; Wave 2B xong biểu phí trả trước (`G-POLICY-W2`); Wave 2C xong core V4 + hợp đồng `LOAN_TERMS_V3` (`W2C`) |
| R2 | P0 | Thai | Loan/Payment/Fineract | `IN_PROGRESS` | Restart/timeout/duplicate không giải ngân hoặc thu tiền hai lần; Wave 2 xong B06–B13 (`R2-W2` chờ review); Wave 2C xong B28–B30 và khoản trả kỳ giữ tới ngày đến hạn (`W2C` chờ review) |
| R3 | P0 | Hai | Investment | `IN_PROGRESS` | Order–hold, Note activation, ownership và portfolio tự repair; Wave 2 xong B14–B16 (`R3-W2` chờ Hải review) |
| R4 | P1 | Thai | Payment | `READY_FOR_REVIEW` | Rút tiền và provider disbursement sandbox chạy thật, đối soát được; W3B xong rút tiền qua provider payout giả lập (D41–D44), E2E `w3a-installment-20261017-184734`; provider thật chờ legal review |
| R5 | P1 | Thai + Hai | Loan/Investment/SmartCA | `READY_FOR_REVIEW` | PDF có chữ ký nhúng của nhiều bên hoặc quyết định giữ receipt rời được duyệt; W3C xong PAdES-B-B nối tiếp (D45–D49), MOCK ký bằng chứng thư demo; ký SmartCA UAT thật cần người duyệt trên app |
| R6 | P1 | Thai | Blockchain | `READY_FOR_REVIEW` | Fabric thật + proof/query/reconcile cho các mốc đã duyệt; W3D xong mạng Fabric local 2 tổ chức, chaincode `finora-ledger`, bốn mốc, API quản trị (D50–D54); mặc định vẫn MOCK, bật bằng `-WithFabric` |
| R7 | P1 | Hai | Notification | `READY_FOR_REVIEW` | Template đầy đủ và push thật; lỗi provider không làm sai nghiệp vụ; W3F xong thông báo theo domain event, Expo Push (token, preference, che nội dung màn khóa, retry/DEAD), nhắc lịch tắt mặc định (D60–D64); push thật trên máy thật và producer bổ sung `borrowerId` (P1–P7) còn lại |
| R8 | P1 | Both | Security | `IN_PROGRESS` | RBAC/resource auth, PII/secret/log và abuse controls được kiểm chứng; R8A xong ở W3A (D33–D40, E2E `w3a-installment-20261017-184734`), chặn tự đăng ký `ADMIN` hoãn (D20) |
| R9 | P1 | Both | Ops | `BACKLOG` | Health, metrics, alert, replay/DLT và deployment tái lập được |
| R10 | P2 | Hai | AI | `BACKLOG` | Backtest/champion-challenger, fraud và early warning có metric |
| R11 | P2 | Hai + Thai | Investment/Payment | `IN_PROGRESS` | Secondary market/auto-invest qua concurrency/reconciliation E2E |

## 3a. Quyết định owner ngày 2026-10-17

Thái chốt trong phiên rà soát ngày 2026-10-17 và cho phép agent chọn phương án được khuyến nghị cho các
lựa chọn kỹ thuật còn lại, miễn phù hợp nghiệp vụ và chạy ổn định trong FINORA. Không quyết định nào dưới đây
được mô tả là yêu cầu pháp luật nếu không có dẫn chiếu trong `docs/LEGAL-COMPLIANCE.md`.

| # | Quyết định | Loại | Wave |
|---|---|---|---|
| D1 | CIC theo bản Node đã deploy; không phát triển lại CIC trong repo | Phạm vi | Đã áp dụng |
| D2 | Sửa lỗi hiện tại và làm luôn backlog theo wave; được sửa module của Hải để đồng bộ, đánh dấu “Hải review trước merge” | Ownership | 1–3 |
| D3 | Security (R8) tạm hoãn, chỉ sửa khi gắn trực tiếp với contract giữa service | Phạm vi | Sau wave 3 |
| D4 | Loan dùng adapter KYC thật của User; chỉ borrower `VERIFIED` được nộp hồ sơ; mock chỉ còn cho test/E2E | Nghiệp vụ | 2B (xong) |
| D5 | Hạn mức dư nợ của một borrower 100.000.000 VND, cấu hình được, nhãn `NEEDS_PRIMARY_SOURCE` (policy demo, chưa phải luật) | Policy demo | 2 (xong) |
| D6 | Nhà đầu tư tick xác nhận rủi ro (lưu version + thời điểm) trước lệnh sơ cấp (`LEGAL-DISCLOSURE-02`) | Nghiệp vụ | 2 (xong) |
| D7 | Ngày nghiệp vụ của Loan và Payment là `Asia/Ho_Chi_Minh` | Nghiệp vụ | 2 (xong) |
| D8 | Hợp đồng bị từ chối/hết hạn ký hoặc giải ngân thất bại → event bù trừ, Investment hủy cam kết và nhả hold | Contract | 1 (xong) |
| D9 | Phạt chậm trả giữ 0 cho hợp đồng hiện hành; chưa có write-off | Policy | Giữ nguyên |
| D10 | Trả trước một phần thu `scheduledDue + prepaidPrincipal`; quote cũ giữ snapshot | Policy | Giữ nguyên |
| D11 | E2E tự động: tài khoản mới mỗi lần, OTP đọc Redis User local, eKYC investor tắt chỉ trong stack E2E, ký MOCK, Auto-Invest tắt, admin demo, ghi dữ liệu test lên Neon/Fineract/Kafka dev | Kiểm thử | 1 (xong) |
| D12 | Commit theo wave trên nhánh `feat/finora-remaining-wave-<n>`, không push khi chưa được yêu cầu | Quy trình | 1–3 |
| D13 | R5 dùng PAdES-B-B với VNPT SmartCA UAT (ký hash, nhúng CMS bằng incremental update) | Kỹ thuật | 3 |
| D14 | R6 dựng Hyperledger Fabric local thật thay ledger MOCK | Kỹ thuật | 3 |
| D15 | Sản phẩm mới dùng cấu hình Fineract V4: lãi tính trên dư nợ và số ngày thực tế; trả thêm gốc giảm dư nợ ngay, tất toán chỉ thu lãi đến ngày tất toán (sửa B28, B29) | Nghiệp vụ | 2C (xong) |
| D16 | Trả kỳ trước ngày đến hạn trên khoản vay V4: trừ ví ngay, ghi Fineract đúng ngày đến hạn nên lãi kỳ giữ đúng lịch, các kỳ sau không đổi; nhà đầu tư nhận tiền vào ngày đến hạn. Trả từ ngày đến hạn trở đi thì ghi ngay. Trước ngày đến hạn người vay hủy được (PIN, tiền về ví) | Nghiệp vụ | 2C (xong) |
| D17 | Khoản vay đã giải ngân theo core V1–V3: chặn tự trả trước một phần và tất toán trên app (`409 PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN`); trả kỳ và khắc phục quá hạn vẫn bình thường | Nghiệp vụ | 2C (xong) |
| D18 | `borrowerId` thống nhất là ID hồ sơ (claim `user_id`); bỏ đường lùi `sub` trong finora-common để sang R8 | Contract | 2C (xong, B26) |
| D19 | Gói Q ngày 2026-10-17 được duyệt: Q1 hợp đồng `LOAN_TERMS_V1` không thu phí trả trước; Q3 hợp đồng `LOAN_TERMS_V3` (ba câu: lãi theo dư nợ và ngày thực tế, khoản trả kỳ trước hạn ghi đúng ngày đến hạn, muốn giảm lãi thì trả trước một phần có phí); Q4 policy rút tiền demo cấu hình được (tối thiểu 50.000 đ, không phí, tối đa 50 triệu/ngày, chỉ rút số dư khả dụng, số tài khoản che và mã hóa, provider giả lập có callback HMAC); Q5 xác nhận rủi ro khi tạo/bật Auto-Invest, đổi công bố thì quy tắc tạm dừng; Q6 chấp nhận CIC chỉ tra điểm (B27), ghi rõ trong báo cáo; Q7 dừng container `finora-cic`; Q8 R8A trừ mục chặn tự đăng ký ADMIN (D20); Q9 PAdES ẩn, ký nối tiếp nhà đầu tư trước người vay cuối, trang biên nhận; Q10 Fabric 2 tổ chức, mỗi tổ chức 1 peer, 1 orderer, không CouchDB, bật bằng `-WithFabric`, mặc định MOCK, neo 4 mốc; Q11 thông báo trong app + Expo Push; Q12 đồng hồ giả chỉ trong stack E2E; Q13 Toxiproxy + điểm dừng profile E2E; Q14 GitHub Actions; Q15 được push nhánh `feat/finora-remaining-wave-*` lên origin cho Hải review, không merge; Q16 commit plan Loan sau khi quét secret; Q17 ngoài phạm vi: R10, R11 nâng cao, auto-debit, R8B, tiền thật và chữ ký thật ở production | Tổng hợp | 2C–3 |
| D20 | Hoãn chặn tự đăng ký `ADMIN` (một mục của Q8). Local/E2E rủi ro thấp vì chỉ người có máy dev tạo tài khoản; phải làm trước khi mở hệ thống ra mạng công khai | Security | Sau wave 3 |
| D21 | Thay Q2: không thêm thao tác "Nâng cấu hình core" cho sản phẩm đang bán; tạo sản phẩm mới theo V4 vì Loan chỉ đồng bộ Product `DRAFT`. Sản phẩm và khoản vay cũ giữ nguyên | Nghiệp vụ | 2C (xong) |
| D22 | Còn khoản trả kỳ đang giữ mà người vay muốn trả trước/tất toán: hủy khoản giữ (PIN, tiền về ví) rồi mới lập báo giá, thay cho "ghi khoản đó trước rồi báo giá" trong gói Q. Lý do: ghi trước ngay lúc xem báo giá không hoàn tác được; người vay bỏ báo giá thì kỳ đã bị ghi sớm và các kỳ sau đổi (probe P3 ngày 2026-10-17: lãi kỳ 1 còn 49.000, phần lãi còn lại dồn sang kỳ 2), trái D16. Cách hủy rồi báo giá giống ngân hàng coi tiền chờ ngày đến hạn là số dư khả dụng; phí tất toán tính trên toàn bộ gốc còn nợ như khi chưa trả sớm | Nghiệp vụ | 2C (xong) |
| D23 | Từ 2026-10-17 agent tự chọn phương án tốt nhất không chờ duyệt, ghi quyết định vào bảng này và báo cáo sau mỗi wave; các ràng buộc an toàn (secret, PII, tiền thật, merge) giữ nguyên | Quy trình | 2C–3 |
| D24 | Stack E2E mặc định chạy trên PostgreSQL local riêng cho Loan/Payment/Investment/Notification (`-Database local`); Loan đặt namespace Product Fineract `E2E` (`FINERACT_PRODUCT_NAMESPACE`: externalId `FINORA-E2E-LP-*`, tên và shortName riêng) nên không trùng Product của môi trường khác trên cùng Fineract. Dời đồng hồ chỉ được phép trên DB local. Lý do: ở Wave 2C Payment chạy lệch +31 ngày khoảng 1–2 phút mỗi lượt trên DB Neon dùng chung, worker có thể xử lý dòng đến hạn của máy khác (cùng lớp B21). `-Database shared` vẫn còn cho lượt cần dữ liệu dev | Kiểm thử | 2D (xong) |
| D25 | Đồng hồ E2E giả lập EOD của Fineract theo đúng thứ tự cron 00:01 của production: dời ngày tới thì chạy "Recalculate Interest For Loans" (`LA_RINT`) rồi "Update Loan Arrears Ageing" (`LA_ARAG`); trả về hôm nay chỉ chạy `LA_ARAG` vì khoản vay E2E còn giao dịch đề ngày tương lai. Fineract chỉ cập nhật `summary.totalOverdue` khi `LA_ARAG` chạy (không chạy thì DPD đứng ở 0), và chỉ đưa lãi trên gốc quá hạn vào lịch khi `LA_RINT` chạy (thiếu thì khoản vay quá ngày đáo hạn còn 49.000 lãi sau đáo hạn chưa vào nghĩa vụ, lượt `w2d-defaultcure-20261017-135019`) | Kiểm thử | 2D (xong) |
| D26 | Sửa B31: khắc phục quá hạn mặc định là toàn bộ phần quá hạn (`amount = max(template, totalOverdue)`), đúng LOAN-SERVICING-POLICY §3.4 và contract `scheduled-due`. Người vay quá hạn nhiều kỳ được chọn trả bớt đúng kỳ quá hạn cũ nhất (`partialOverdueAmount`); Payment chỉ nhận một trong hai số đó. Trả bớt không đưa khoản vay về bình thường: Loan chỉ hồi phục `DEFAULTED → ACTIVE` khi DPD và tiền quá hạn đều về 0. Giữ lựa chọn trả bớt vì trước sửa người vay vẫn trả được từng kỳ; bỏ đi sẽ làm người không đủ tiền trả một lần không trả được gì | Nghiệp vụ | 2D (xong) |
| D27 | Sửa B35: khoản vay đang quá hạn không lập được báo giá trả trước một phần (`409 PARTIAL_PREPAYMENT_LOAN_OVERDUE`, kiểm theo kỳ chưa trả cũ nhất của lịch Fineract); tất toán toàn bộ vẫn được. Theo thực tiễn ngân hàng chỉ cho trả nợ trước hạn khi không có nợ quá hạn; không phải trích dẫn luật | Nghiệp vụ | 2D (xong) |
| D29 | Sửa B36: trong một đợt quá hạn (hồ sơ thu hồi còn mở) nhóm nợ không giảm khi người vay chỉ trả bớt; nhóm là nhóm cao nhất đã chạm và về 1 khi trả đủ phần quá hạn. Hồ sơ thu hồi, `LoanDelinquencyChanged`, thống kê hiện tại và lịch sử nhóm nợ dùng chung quy tắc; Investment giữ quy tắc nhóm 3–5 là Note `DEFAULTED` nên không đổi contract. Chọn vì khớp trạng thái `DEFAULTED` của Loan và LOAN-SERVICING-POLICY ("đã chuyển nợ xấu nhưng còn quá hạn thì giữ nợ xấu"), và thực tiễn ngân hàng không hạ nhóm khi chưa trả đủ nợ quá hạn. Chưa áp dụng thời gian theo dõi sau khi trả đủ | Nghiệp vụ | 2D (xong) |
| D30 | R3 lệnh góp vốn ↔ khoản giữ (sửa B38–B41): Payment có `GET /transactions/holds?orderReference=` không side effect; Investment chỉ chốt khi khoản giữ `HELD` đúng số tiền, coi 401/403/404/408/429 là chưa biết (không từ chối lệnh), nhả tiền sau khi transaction của lệnh commit, worker lùi dần và dừng cho vận hành khi Payment trả lỗi chắc chắn; admin xem và hẹn thử lại. Chọn đối soát kéo (Investment hỏi Payment) thay vì sửa DB Payment hay thêm event, vì Payment đã có khóa idempotency theo mã lệnh và Investment là bên biết lệnh nào còn sống | Nghiệp vụ | 2D (xong) |
| D31 | Q13: drill lỗi thật trong stack E2E: Toxiproxy (profile `e2e-faults`, chỉ Payment → Fineract đi qua) cho mất phản hồi/mất request; điểm dừng `finora.e2e.pause-point` (Loan `core-booked-before-save`, Payment `provider-disbursed-before-save`) chỉ nhận khi chữ ký MOCK/provider mock như đồng hồ E2E, harness tắt hẳn JVM ở điểm dừng rồi bật lại với lease 30 giây. Không có API hay cấu hình nào ở demo/production bật được điểm dừng | Kiểm thử | 2D (xong) |
| D32 | Sửa B42: lỗi I/O (timeout, reset, đóng giữa chừng) ở bất kỳ đâu trong cause chain sau khi request có thể đã tới đích là "chưa biết kết quả", không phải lỗi không xác định: Payment ghi `FINERACT_REPAYMENT_OUTCOME_UNKNOWN` và đối soát theo externalId, Loan `FINERACT_CONNECTION_LOST` retry (tra theo số hợp đồng trước khi ghi), AI scoring `AI_UNAVAILABLE`. Lỗi đọc JSON vẫn là lỗi không xác định. Chọn sửa phân loại thay vì đổi HTTP client vì client khác cũng cần phân loại như vậy và đổi client làm đổi hành vi timeout của mọi adapter | Kỹ thuật | 2D (xong) |
| D33 | Sửa B43: Gateway gọi upstream bằng HTTP/1.1 (bean `ClientHttpRequestFactory` thay bean mặc định HTTP/2 của Gateway MVC). JDK `HttpClient` gửi `Upgrade: h2c` tới upstream `http://`; uvicorn 0.27/h11 giữ body chờ `101` nên AI nhận body rỗng. Sửa ở Gateway vì mọi upstream không phải Tomcat đều bị | Kỹ thuật | 3 (W3A) |
| D34 | Danh tính service bằng client credentials Keycloak, mỗi service gọi một client riêng: `finora-loan-client` (`ai:credit:score`), `finora-user-client` thêm `ai:ekyc:ocr`, `notification:internal:send`, `finora-ai-client` (chỉ `user:admin:read_all`, thay cho việc AI giữ secret có quyền quản trị Keycloak). Secret chỉ ở `.env` git-ignore, script `add-service-clients.sh` ghi thẳng không in | Security | 3 (W3A) |
| D35 | AI kiểm JWT Keycloak (RS256/JWKS, issuer, hạn; header hoặc cookie `access_token`): chấm điểm `ai:credit:score` hoặc `ROLE_ADMIN`, cấu hình `ROLE_ADMIN` (log actor khi sửa), OCR `ai:ekyc:ocr`, fraud `ROLE_ADMIN`; Swagger chỉ ở local/e2e/demo. Web admin gọi AI cùng origin qua Gateway bằng cookie phiên | Security | 3 (W3A) |
| D36 | Notification `/api/internal/notifications/**` cần authority `notification:internal:send`; body có validation (OTP đúng 6 chữ số) | Security | 3 (W3A) |
| D37 | `finora.environment` (`FINORA_ENVIRONMENT`, mặc định `local`): staging/production từ chối khởi động khi còn khóa PIN mặc định, đồng hồ/điểm dừng E2E, provider mock, chữ ký MOCK, khóa mặc định của User, eKYC nhà đầu tư tắt, proof MOCK. Nút giả lập nạp tiền chỉ ở local/e2e/demo, provider mock, lệnh chưa hết hạn | Security | 3 (W3A) |
| D38 | Token PIN gắn với `Idempotency-Key` của đúng thao tác (claim `req` = SHA-256 base64url); endpoint `@RequirePin` bắt buộc header; gửi lại cùng thao tác vẫn qua nhờ idempotency, không cần bảng chống dùng lại. App và harness gửi `requestKey` khi xác nhận PIN | Security | 3 (W3A) |
| D39 | Loan bỏ URL/username Neon mặc định trong `application.yml`, mặc định PostgreSQL local; máy dev giữ giá trị cũ trong `finora-loan/.env` | Security | 3 (W3A) |
| D40 | Keycloak bật chống dò mật khẩu (5 lần, chờ tăng 60 giây, tối đa 15 phút, không khóa vĩnh viễn; bỏ được bằng `--skip-brute-force` khi realm dùng chung); giới hạn tần suất in-memory: OCR 5/10 phút, tạo lệnh nạp 10/giờ, báo giá 30/phút, rút tiền 10/giờ | Security | 3 (W3A) |
| D41 | Rút tiền trừ ví ngay lúc nhận lệnh (`AVAILABLE` → clearing `PAYOUT_PROVIDER_CLEARING`) trong transaction khóa dòng ví cùng lúc kiểm hạn mức ngày; thất bại hoàn bằng bút toán ngược trong cùng transaction chuyển `FAILED`; không dùng `HELD` | Nghiệp vụ | 3 (W3B) |
| D42 | Kết quả chi tiền chưa rõ không bao giờ gửi lệnh chi mới: tra cứu provider theo `withdrawalId`; chỉ gửi lại với đúng mã khi provider khẳng định chưa nhận | Nghiệp vụ | 3 (W3B) |
| D43 | Provider payout mock gọi callback HTTP thật, ký HMAC-SHA256 trên body; tài khoản kết thúc `0000` mô phỏng tài khoản sai; endpoint callback 404 khi provider không phải mock | Kỹ thuật | 3 (W3B) |
| D44 | Số tài khoản nhận tiền mã hóa AES-256-GCM, chỉ trả bản che; hash idempotency dùng HMAC của số tài khoản để không dò ngược được | Security | 3 (W3B) |
| D45 | Biên nhận chữ ký là PDF riêng (`PADES_RECEIPT_V1`) lập sau chữ ký cuối, ghi SHA-256 bản ký đủ; `/document` trả bản ký đủ khi hoàn tất, biên nhận ở `/receipt`. Chèn trang sau khi ký làm trình đọc coi chữ ký trước bị sửa | Kỹ thuật | 3 (W3C) |
| D46 | Chuỗi bản ký lưu ở bảng mới `loan_contract_signatures` (PREPARED/SIGNED/ABANDONED); giữ `UNIQUE(contract_id, artifact_type)` của V9, chỉ thêm loại `SIGNED_PDF` | Kỹ thuật | 3 (W3C) |
| D47 | Bên cho vay ký theo thứ tự hoàn tất, mỗi lúc một phiên ký trên một hợp đồng (khóa + partial unique), người vay ký cuối; consent nhận hash SIGNABLE nên client không đổi request | Nghiệp vụ | 3 (W3C) |
| D48 | PAdES-B-B (`ETSI.CAdES.detached`, SHA-256, RSA PKCS#1 v1.5, content-type/message-digest/signing-certificate-v2, không signing-time), chữ ký ẩn, chừa 16 KiB, kiểm chữ ký của provider trước khi nhúng; worker hỏi SmartCA 15 giây/lần, đóng phiên sau 10 phút | Kỹ thuật | 3 (W3C) |
| D49 | Hồ sơ ký chốt lúc lập hợp đồng; hợp đồng trước V32 giữ luồng bằng chứng rời; `FINORA_SIGNATURE_PADES_ENABLED=false` là đường lùi; MOCK ký PAdES thật bằng chứng thư tự ký `CN=FINORA DEMO - KHONG CO GIA TRI PHAP LY`; không lưu subject chứng thư | Kỹ thuật | 3 (W3C) |
| D50 | Mạng Fabric 2.5.16 LTS local: một orderer Raft, Org1MSP (FINORA ghi) và Org2MSP (kiểm toán), mỗi tổ chức một peer LevelDB, TLS, endorsement MAJORITY (cả hai tổ chức ký); crypto cryptogen git-ignore; chỉ mở gateway peer0.org1 tại `127.0.0.1:7051`; kiểm ≥ 768 MiB RAM Docker trước khi bật | Kỹ thuật | 3 (W3D) |
| D51 | Chaincode `finora-ledger` (Go, chaincode-as-a-service): ghi một lần, cùng id cùng hash trả bản cũ, hash khác hoặc mốc thứ hai bị từ chối, chỉ Org1MSP ghi, allowlist đầu vào; `RegisterProof`, `GetProof`, `GetProofsByAggregate` | Kỹ thuật | 3 (W3D) |
| D52 | Bốn mốc neo hash: hợp đồng đủ chữ ký (`LoanContractActivated`), giải ngân (`LoanDisbursed`), trả nợ (`RepaymentDistributed`), phát hành/chuyển nhượng Note (`NoteOwnershipChanged`); hash v2 = SHA-256 JSON chuẩn hóa của `data` (khóa sắp xếp, bỏ null, chỉ số nguyên), chỉ lưu hash; quyết định servicing hoãn | Nghiệp vụ | 3 (W3D) |
| D53 | Adapter `fabric-gateway` 1.4.0, danh tính `User1@org1` đọc từ file PEM; timeout evaluate 5 s, endorse 15 s, submit 5 s, commit 60 s; lỗi phân loại retry (unavailable, timeout, commit chưa rõ, MVCC) và vĩnh viễn (chaincode từ chối, danh tính, giao dịch sai) | Kỹ thuật | 3 (W3D) |
| D54 | API proof chỉ `ROLE_ADMIN`: tra theo tham chiếu nghiệp vụ hoặc trạng thái, verify (artifact + DB ↔ chain), retry `DEAD_LETTER`; worker đối soát định kỳ opt-in; lỗi consume vào `<topic>.DLT` | Security | 3 (W3D) |
| D60 | Người nhận thông báo lấy từ event, thiếu thì từ bảng tra dựng từ `LoanFundingRequested`/`LoanFullyFunded.v2`; event mâu thuẫn người vay đã lưu thì vào DLT, không gửi ai; group mới `notification-domain-events` | Nghiệp vụ | 3 (W3F) |
| D61 | Quá hạn chỉ báo khi bắt đầu quá hạn, lên nhóm nợ, thành nợ xấu hoặc khắc phục; bỏ thông báo hằng ngày, cả phía nhà đầu tư | Nghiệp vụ | 3 (W3F) |
| D62 | Expo Push: provider `log` (giả lập, mặc định, cổng môi trường chặn ở staging/production), `expo`, `disabled`; tối đa 5 thiết bị mỗi người, token chuyển chủ khi tài khoản khác đăng ký, thu hồi khi đăng xuất/`DeviceNotRegistered`; 6 lần thử rồi `DEAD`; không push event cũ hơn 24 giờ, 22:00–07:00 hoãn tới 07:00 (trừ rút tiền) | Kỹ thuật | 3 (W3F) |
| D63 | Nội dung push không có số tiền, tên, số khoản vay (test kiểm mọi template không có chữ số); push quá hạn chung chung; in-app luôn bật, push bật mặc định và tắt được theo nhóm | Security | 3 (W3F) |
| D64 | Nhắc lịch trước 3 ngày và đúng ngày đến hạn, bỏ kỳ Payment đang giữ (D16); **tắt mặc định** tới khi Payment phát event giữ/hủy khoản trả kỳ (P5), nếu không người trả sớm vẫn bị nhắc | Nghiệp vụ | 3 (W3F) |
| D65 | CIC nhận nợ quá hạn: hoàn thiện Task 2–4 trong plan của Hải (`cic-service-node`) trên nhánh `feat/finora-delinquency-intake`, không deploy, Hải review trước merge. FINORA giữ nguyên producer (`LoanDelinquencyChanged.v1`, mapping `X-Finora-Internal-Key` của User). E2E chạy CIC Node local có Kafka consumer; mặc định vẫn là bản deploy (D1). Bản Vercel không giữ được Kafka consumer nên chỉ nhận mapping | Phạm vi | 3 |
| D75 | Không thêm `signedPdfHash` vào `LoanContractActivated.v1`: proof `CONTRACT_DOCUMENT` neo `pdfReceiptHash`, biên nhận `PADES_RECEIPT_V1` ghi SHA-256 bản ký đủ, nên chuỗi proof → biên nhận → PDF ký đủ kiểm được; harness kiểm `proof.contract-anchors-signed-receipt` | Kỹ thuật | 3 |
| D28 | Sửa B32: admin "Ghi lại vào hệ thống lõi" cho khoản trả nợ `RECONCILIATION_REQUIRED` sau khoảng an toàn (`PAYMENT_CORE_REPOST_SAFE_AFTER`, mặc định 15 phút): tra cứu theo externalId trước, chỉ POST khi Fineract chưa có, dùng đúng externalId/số tiền/ngày ghi nhận ban đầu; Fineract từ chối thì tra cứu lại rồi mới hoàn tiền. Chọn thao tác thủ công có điều kiện thay cho tự động ghi lại vì đây là ngoại lệ tài chính cần người chịu trách nhiệm; mỗi lần ghi lại lưu ai và lúc nào | Nghiệp vụ | 2D (xong) |

## 3b. Defect register

Nguồn: rà soát code ngày 2026-10-16/17 và evidence R1A (`docker/e2e/out/r1a-*.json`, không commit).
`Fixed` chỉ dùng khi có test hoặc evidence chạy thật đi kèm.

| ID | Mức | Workstream | Vấn đề và bằng chứng | Trạng thái |
|---|---|---|---|---|
| B01 | P0 | R2 | Payment đọc `total.amount` của repayment template; Fineract 1.15 trả `amount` cấp gốc nên mọi lệnh trả kỳ bị từ chối. Test xanh vì IT mock gateway | Fixed Wave 1 — unit test payload thật + E2E trả kỳ `COMPLETED` |
| B02 | P0 | R2 | Đối soát dò `reversed`, Fineract dùng `manuallyReversed` | Fixed Wave 1 — unit test |
| B03 | P0 | R9 | Gateway thiếu route `/api/v1/repayments/**`; admin repayment reconciliation bị route sang Loan; profile `docker` thiếu route Notification | Fixed Wave 1 — E2E trả nợ qua Gateway |
| B04 | P0 | R3 | `market_listings.purpose` VARCHAR(100) trong khi Loan gửi nhãn + 500 ký tự; listing vào DLT | Fixed Wave 1 — Investment V15 + IT |
| B05 | P0 | R2/R3 | Không có bù trừ khi đã đủ vốn mà hợp đồng bị từ chối/hết hạn hoặc giải ngân thất bại; tiền nhà đầu tư bị giữ vô thời hạn | Fixed Wave 1 — `LoanFundingCancelled` v1, Loan V27, IT Loan/Investment |
| B06 | P0 | G-POLICY | Loan/Payment dùng `Clock.systemUTC()`: từ 00:00–07:00 giờ VN, Fineract ghi giải ngân/trả nợ vào ngày hôm trước; hợp đồng ghi kỳ đầu 17/11 còn Fineract 16/11 (evidence R1A-BIZDATE) | Fixed Wave 2 — [`R2-W2`](R2-W2-business-date-and-financial-recovery.md); E2E 04:45 giờ VN: Fineract giải ngân và trả nợ 2026-10-17, kỳ đầu 2026-11-17 khớp hợp đồng |
| B07 | P0 | R2 | Booking dùng `expectedDisbursementDate` của lúc nộp hồ sơ, `submittedOnDate` = ngày booking. Fineract từ chối khi ngày booking muộn hơn (`error.msg.loan.submittal.cannot.be.after.expected.disbursement.date`, kiểm bằng `calculateLoanSchedule`) → luồng thật (gọi vốn tới 14 ngày, ký tới 7 ngày) sẽ `REPAIR_REQUIRED` sau khi tiền đã chuyển | Fixed Wave 2 — [`R2-W2`](R2-W2-business-date-and-financial-recovery.md); mọi ngày gửi Fineract là ngày giải ngân thực tế, unit test + E2E |
| B08 | P0 | R2 | Saga `CORE_BOOKING` không được nhận lại sau khi service chết giữa chừng | Fixed Wave 2 — lease + claim token, Loan V28; unit test. Restart JVM thật thuộc R1B |
| B09 | P0 | R2 | Payment disbursement `PROCESSING`/`RECONCILIATION_REQUIRED` không có worker phục hồi; truy vấn top-20 có thể bỏ đói | Fixed Wave 2 — lease → đối soát `lookup(sagaId)`, Payment V12–V13; IT PostgreSQL. Restart JVM thật thuộc R1B |
| B10 | P1 | R2 | Payment POST Fineract thành công nhưng GET sau đó lỗi HTTP bị coi là thất bại → hoàn tiền + `FAILED` | Fixed Wave 2 — 4xx/5xx/GET lỗi tách mã; unit test |
| B11 | P1 | R2 | Outbox Payment `send().get()` không timeout | Fixed Wave 2 — `outbox-publish-timeout`; `PaymentOutboxPublisherTest` |
| B12 | P1 | R2 | `DisbursementSaga.paymentFailed` ghi đè mọi trạng thái trừ `COMPLETED` (bù trừ Wave 1 đã chặn ở `WAITING_PAYMENT`) | Fixed Wave 2 — chỉ `WAITING_PAYMENT → PAYMENT_FAILED`, mâu thuẫn vào DLT; unit test |
| B13 | P1 | R2 | Lỗi Fineract khi đọc nghĩa vụ trả nợ trả `500 INTERNAL_ERROR` thay vì mã nghiệp vụ | Fixed Wave 2 — 422/503/502 qua `FineractQuoteErrors`; unit test |
| B14 | P1 | R3 | Investment `LoanDisbursed` yêu cầu listing `EFFECTIVE`, sai thứ tự event vào DLT và không tự replay | Fixed Wave 2 — [`R3-W2`](R3-W2-investment-event-order-and-portfolio.md); `LoanDisbursed` là bằng chứng Contract hiệu lực, IT |
| B15 | P1 | R3 | Admin `finalize`/`activate-notes` không có guard trạng thái; web hiện nút nguy hiểm | Fixed Wave 2 — `409 LISTING_NOT_DISBURSED`, web chỉ hiện nút sửa lỗi sau giải ngân; IT + web build |
| B16 | P1 | R3 | Portfolio chỉ đọc Note `ACTIVE`, khoản `DEFAULTED`/`CLOSED` biến mất khỏi danh mục | Fixed Wave 2 — vị thế `CLOSED/DEFAULTED`, mobile ẩn nút bán; IT |
| B17 | P1 | G-POLICY | Cấu hình phí tất toán hiện hành áp ngược cho hợp đồng cũ (chưa có policy version snapshot) | Fixed Wave 2B — [`G-POLICY-W2`](G-POLICY-W2-early-settlement-policy-version.md): hợp đồng ghi biểu phí (Loan V30), `LoanDisbursed` mang sang Payment (V14), danh mục bất biến; hợp đồng `LOAN_TERMS_V1` không thu phí (chờ owner xác nhận); E2E `r1a-20261017-060853` |
| B18 | P1 | Legal | Thiếu kiểm hạn mức dư nợ (`LEGAL-LIMIT-01`) và xác nhận rủi ro nhà đầu tư (`LEGAL-DISCLOSURE-02`) | Fixed Wave 2 — [`W2-LEGAL`](W2-LEGAL-borrower-exposure-and-investor-acknowledgement.md): hạn mức dư nợ nội bộ (policy demo, `NEEDS_PRIMARY_SOURCE`) + xác nhận lưu version/thời điểm; IT Loan/Investment |
| B19 | P1 | Loan/User | Loan chỉ có `BorrowerProfileProvider` mock (VERIFIED, 30 tuổi) | Fixed Wave 2B — [`W2-KYC`](W2-KYC-borrower-kyc-from-user.md): Loan chuyển tiếp token người vay tới User `/users/me/kyc-summary`, chặn người chưa `VERIFIED`, snapshot trên hồ sơ (V29); IT + kiểm thật `kyc-20261017-054750` |
| B20 | P1 | FE | Mobile tự tính số tiền trả kỳ; web gọi API duyệt listing không còn tồn tại và hiện thông điệp sai | Fixed Wave 2 — `GET /repayments/scheduled-due` (IT), mobile dùng số backend, web bỏ form duyệt |
| B21 | P1 | R9/R2 | DB Loan dùng chung chứa khoản vay trỏ tới Fineract local của máy khác; worker sync log lỗi 404 kèm stack trace ở mọi chu kỳ 10 giây, mapping Product↔Fineract không dùng chung được giữa máy | Partly fixed Wave 2 — log một dòng `warn`, không stack trace; tách DB theo môi trường vẫn thuộc R9 |
| B22 | P2 | R9 | DB dev dùng chung chậm 1–3 migration so với `main` (Loan V26, Investment V13–V14, Payment V11 chỉ được áp khi chạy E2E) | Open → R9 (CI/migration discipline) |
| B23 | P2 | R9 | Payment khai báo `spring-boot-starter-data-redis` nhưng không dùng Redis | Open → R9 |
| B24 | P2 | R7 | `BorrowerSignatureRequested` chưa có consumer; người vay không nhận thông báo đến lượt ký | Open → Wave 3 (R7) |
| B25 | P2 | DOC | `finora-loan/plans/` bị git-ignore nên link LN-009/010/015 trong tài liệu chung không mở được ở máy khác | Fixed Wave 2D (Q16) — bỏ dòng `/finora-loan/plans/` trong `.gitignore`, commit 24 plan Loan sau khi quét secret/PII |
| B26 | P2 | User/Loan | Hai định danh người vay: Loan/Investment/Payment/AI dùng claim `user_id` (ID hồ sơ, ví dụ `borrowerId=553` trong log E2E `r1a-20261017-052157`), còn `CicMappingWorker` của User đăng ký mapping CIC theo `keycloakUserId` (test `guiKeycloakUserIdLamBorrowerIdVaCccdQuaKenhNoiBo`). `SecurityUtils.getCurrentUserId` còn rơi về `sub` khi token thiếu claim. Nếu bật mapping, event `LoanDelinquencyChanged.v1` (mang `borrowerId` của Loan) không bao giờ khớp mapping | Fixed Wave 2C (D18) — `CicMappingWorker` gửi `user_profiles.id` (= claim `user_id`); unit test `guiUserIdCuaHoSoLamBorrowerIdVaCccdQuaKenhNoiBo`. Bỏ fallback `sub` sang R8. Worker vẫn tắt vì CIC Node chưa có endpoint mapping (B27) |
| B30 | P1 | R2 | Payment cho tạo lệnh trả kỳ thứ hai khi lệnh trước còn `COLLECTED/CORE_POSTING/RECONCILIATION_REQUIRED`: Fineract chưa ghi khoản trước nên template vẫn báo kỳ cũ và người vay trả hai lần một kỳ. Báo giá trả trước/tất toán cũng thực hiện được dù khoản vay đã có giao dịch sau lúc lập báo giá (thu thừa). Phát hiện khi thiết kế D16 | Fixed Wave 2C — mỗi khoản vay tối đa một khoản đang ghi core (khóa `payment_loan_accounts`), `409 REPAYMENT_IN_PROGRESS` / `INSTALLMENT_ALREADY_PAID_PENDING`, báo giá `409 INSTALLMENT_PENDING_POSTING`, thực hiện báo giá `409 *_QUOTE_STALE`; IT `prepaymentQuoteCannotBeExecutedAfterAnotherRepaymentChangedTheLoan` |
| B27 | P2 | DOC/CIC | Tài liệu F06 và registry mô tả CIC Node có consumer `LoanDelinquencyChanged`, processed-event và hàng đợi chờ mapping. Kiểm ngày 2026-10-17: bản deploy (`/api/v1/suc-khoe`: kho tệp, 1000 hồ sơ) và source `cic-service-node` chỉ có API tra cứu điểm theo CCCD; `POST /api/v1/internal/borrower-mappings` không tồn tại trong source | Docs sửa ngày 2026-10-17 cho đúng hiện trạng. Theo D1 không phát triển CIC trong repo: lịch sử quá hạn của FINORA chưa được ghi sang CIC; owner quyết định có cần trong phạm vi demo |
| B28 | P0 | R2/G-POLICY | Trả trước một phần thu lãi của kỳ tương lai. Fixture `r1b-partialprepayment-20261017-063137` (Fineract loan 25): trả kỳ 1.725.000 + 3.000.000 gốc trả thêm ngay ngày giải ngân, Fineract ghi gốc 4.541.000, lãi 184.000 = 100.000 lãi kỳ 1 + 84.000 toàn bộ lãi kỳ 2; chỉ 2.916.000/3.000.000 vào gốc. Trái điều 4 hợp đồng V2 ("không thu lãi của các tháng tương lai trên phần gốc đã trả") và D10. Nguyên nhân: sản phẩm Fineract do Loan tạo đặt `isInterestRecalculationEnabled: false` từ 2026-08-04; với `NEXT_INSTALLMENT`, khoản trả thêm trả trọn kỳ tương lai kể cả lãi. IT Payment không thấy vì mock Fineract | Fixed Wave 2C cho khoản vay mới (D15, [`W2C`](W2C-daily-interest-core-and-early-installment.md)) — E2E `w2c-partialprepayment-20261017-110733` (Fineract loan 43): khoản kỳ 1 1.726.000 + 3.000.000 ngay ngày giải ngân → gốc 4.726.000, lãi 0, check `prepayment.no-future-interest`. Khoản vay core V1–V3 bị chặn tự trả trước (D17) |
| B29 | P0 | R2/G-POLICY | Tất toán trước hạn thu toàn bộ lãi của các tháng còn lại. Fixture `r1b-earlysettlement-20261017-063404` (Fineract loan 26): tất toán ngay ngày giải ngân, báo giá Payment lấy từ `prepayLoan` của Fineract gồm lãi 353.000 (toàn bộ lãi 6 tháng), người vay trả 10.453.000 thay vì 10.100.000 (gốc + phí). Trái điều 4 hợp đồng V2 ("lãi/phí/phạt đã phát sinh đến ngày giao dịch") và `LEGAL-SERVICING-01`. Cùng nguyên nhân B28 | Fixed Wave 2C cho khoản vay mới (D15) — E2E `w2c-earlysettlement-20261017-111021` (Fineract loan 44): tất toán ngày giải ngân 10.100.000 = gốc 10.000.000 + phí 100.000, lãi 0, Fineract `closed.obligations.met`, check `settlement.no-future-interest`. Khoản vay core V1–V3 bị chặn tự tất toán (D17) |
| B31 | P1 | R2/G-POLICY | Khắc phục quá hạn chỉ thu kỳ quá hạn cũ nhất khi quá hạn nhiều kỳ. Fixture `w2d-defaultcure-20261017-122503` (Fineract loan 54, DPD 91, ba kỳ quá hạn): `scheduled-due` trả `amount=1.726.000`, `overdueAmount=5.178.000` (trái chính contract: "phần quá hạn nằm trong amount"); người vay trả xong khoản vay vẫn `DEFAULTED`, DPD 61, quá hạn 3.452.000, app vẫn ghi "Khắc phục quá hạn". Nguyên nhân: Payment lấy `amount` của repayment template, Fineract chỉ báo kỳ chưa trả cũ nhất; quy tắc có từ `b2a7bc9` (2026-10-06). Fixture OverdueCure (một kỳ quá hạn) không thấy vì hai số bằng nhau | Fixed Wave 2D (D26) — `ScheduledRepaymentCoreQuote.payableAmount/partialOverdueAmount/accepts`, `ScheduledDueResponse.partialOverdueAmount`; mobile chọn "Toàn bộ quá hạn"/"Chỉ kỳ cũ nhất"; `ScheduledRepaymentCoreQuoteTest`, IT `overdueSeveralInstallmentsQuotesTheFullCureAndOnlyAcceptsTheOldestInstallmentAsPartial`; E2E DefaultCure chạy lại ở mục W2D |
| B32 | P0 | R2 | Khoản trả nợ `RECONCILIATION_REQUIRED` mà Fineract chưa từng nhận (mất kết nối trước khi request tới Fineract, hoặc worker chết trước khi gửi) không có đường xử lý: worker tra cứu mỗi 30 giây không thấy thì giữ nguyên mãi, admin chỉ có "đối soát" (tra cứu). Hậu quả: tiền của người vay nằm ở `CLEARING:REPAYMENT`, chặn B30 khóa mọi lệnh trả mới của khoản vay, quá hạn và thu hồi vẫn chạy với người đã trả. Phát hiện khi làm màn sự cố R2 (đọc `reconcileCorePosting` và `RepaymentInFlightGuard`) | Fixed Wave 2D (D28) — Payment V17, `POST /api/v1/admin/repayment-reconciliation/{id}/repost`, web menu "Ghi lại vào hệ thống lõi"; `PaymentRepaymentTest`, `AdminRepaymentReconciliationServiceTest`, ba IT. Lượt E2E cần tiêm lỗi mạng (Q13) |
| B33 | P3 | Contract | Hai kiểu trang danh sách: Loan trả `PageResponse` riêng (`data`), Investment và Payment trả bản chung của finora-common (`content`). Harness đọc nhầm `data` của Investment nên không thấy consent cơ cấu (lượt `w2d-reschedule-20261017-120916`) | Ghi nhận — không đổi API đang dùng; web có `PaymentPage` riêng, harness ghi chú. Thống nhất ở R9 khi có version API |
| B35 | P1 | R2/G-POLICY | Trả trước một phần được báo giá khi khoản vay đang quá hạn. Fineract phân bổ khoản trả vào các kỳ quá hạn trước, nên "gốc trả thêm" chỉ trả nợ quá hạn trong khi người vay vẫn bị tính phí trả trước (và 80% phí chia cho nhà đầu tư). `PartialPrepaymentQuoteService` không kiểm quá hạn; `scheduledDue` của báo giá cũng chỉ là kỳ cũ nhất (cùng gốc B31). Phát hiện khi rà B31 | Fixed Wave 2D (D27) — `409 PARTIAL_PREPAYMENT_LOAN_OVERDUE`, mobile báo trước và dẫn sang màn thanh toán quá hạn; IT `overdueLoanCannotQuotePartialPrepaymentSoNoFeeIsChargedOnMoneyThatOnlyCuresArrears` |
| B36 | P1 | R2/R3 | Nợ xấu trả bớt: Loan giữ khoản vay `DEFAULTED` (đúng policy) nhưng event `LoanDelinquencyChanged` tính nhóm nợ lại từ DPD, nên Investment đưa Note về `ACTIVE` nhóm 2. Lượt `w2d-defaultcure-20261017-131232` (Fineract loan 55): sau khi trả bớt 1.726.000 ở DPD 91, Loan `DEFAULTED` DPD 61 còn danh mục nhà đầu tư `ACTIVE` nhóm 2; người mua trên chợ Notes không còn bị yêu cầu xác nhận rủi ro nợ xấu. Thống kê nhóm nợ của admin cũng tính khoản này là nợ trong hạn | Fixed Wave 2D (D29) — `LoanCollectionCase` giữ nhóm cao nhất của đợt, `CollectionCaseService.apply` trả nhóm cho event, thống kê đọc nhóm của hồ sơ mở, `LoanServicingHistory.debtGroupAt`; test `LoanCollectionCaseTest`, `CollectionCaseServiceTest`, `AdminLoanStatisticsServiceTest`, `LoanServicingHistoryTest`; E2E DefaultCure check `delinquency.partial-cure-stays-defaulted` |
| B37 | P2 | R2/R3 | Cơ cấu làm đổi DPD (dời ngày đến hạn) nhưng `LoanRescheduleExecutionService` không phát `LoanDelinquencyChanged`; lần đồng bộ sau thấy projection đã cập nhật nên cũng không phát. Investment/CIC giữ DPD và trạng thái Note cũ tới lần DPD đổi kế tiếp. Phát hiện khi sửa B36 | Fixed Wave 2D — phát event ngay khi hoàn tất cơ cấu nếu DPD đổi; E2E Reschedule chạy lại |
| B34 | P2 | R3 | Vị thế danh mục của nhà đầu tư có `maturityDate = null` cho tới khi khoản vay được cơ cấu: Investment chỉ ghi ngày đáo hạn từ `LoanRescheduled`, còn `LoanDisbursed.v1` không mang ngày đáo hạn | Fixed Wave 2D — `LoanDisbursed.v1` thêm field optional `loanNumber`, `maturityDate` (Fineract `timeline.expectedMaturityDate` của lần ghi core); Investment tạo/ghi `investment_loan_servicing_states.maturity_date` lúc giải ngân, ngày do cơ cấu đặt luôn được giữ; `FineractCoreLoanBookingGatewayTest`, `FundingFlowIT.loanDisbursedFinalizesInvestmentExactlyOnce` |
| B38 | P1 | R3 | Investment bỏ qua `status` khi Payment trả lại khoản giữ cũ của cùng mã lệnh: lệnh chờ được làm tiếp có thể `COMMITTED` trên khoản đã `RELEASED` (ví dụ: lần chốt trước từ chối và nhả rồi mới rollback), rồi `requireMatchingHolds` của Payment làm hỏng giải ngân của cả khoản vay. Phát hiện khi rà R3 | Fixed Wave 2D (D30) — `RestPaymentClientTest`, `FundingFlowIT.pendingOrderWhoseHoldWasAlreadyReleasedIsRejectedNotCommitted` |
| B39 | P1 | R3 | Mọi 4xx khi giữ tiền đều kết luận `REJECTED` với `payment_hold_reference` rỗng. 401/403 (token, quyền) hoặc 429 không chứng minh lần gọi trước chưa giữ tiền; nếu lần trước timeout mà Payment đã giữ, khoản giữ nằm `HELD` mãi vì worker nhả chỉ quét lệnh có hold reference | Fixed Wave 2D (D30) — chỉ 400/409/422 là từ chối; còn lại giữ `PENDING_FUNDS`, worker hỏi `GET` khoản giữ rồi chốt; `FundingFlowIT.pendingOrderWhoseHoldExistsIsCommittedWithoutHoldingTwice` |
| B40 | P1 | R3 | Người dùng hủy lệnh và lệnh bị từ chối sau khi giữ tiền gọi Payment `release` bên trong transaction đang khóa listing. Payment nhả xong mà Investment rollback (mất phản hồi, lỗi flush/optimistic lock) thì Investment vẫn `COMMITTED`/`ACTIVE` trên khoản đã nhả, không ai phát hiện | Fixed Wave 2D (D30) — nhả sau khi commit, lỗi thì worker; `FundingFlowIT.userCancelReleasesTheHoldAfterTheOrderIsCancelled` |
| B41 | P2 | R3 | Lỗi nhả tiền không phân loại: khoản đã capture trả 500, không tìm thấy/lệch mã trả 404/409, worker thử lại mãi; quét 50 dòng đầu theo id nên dòng lỗi chặn dòng mới; không có màn cho vận hành | Fixed Wave 2D (D30) — Payment `409 PAYMENT_HOLD_NOT_RELEASABLE`, Investment V18 lùi lịch/dừng, admin `order-incidents`; `FundingFlowIT.releaseThatPaymentRefusesStopsForOperationsAndCanBeRetried`, `retryableReleaseFailureBacksOffInsteadOfBlockingTheQueue` |
| B42 | P1 | R2 | Spring 6.1 `RestClient` + `SimpleClientHttpRequestFactory` báo mất phản hồi/ngắt kết nối khi đang chờ phản hồi của POST bằng `RestClientException`, không phải `ResourceAccessException`. Payment ghi repayment đó là `FINERACT_REPAYMENT_UNEXPECTED` (vẫn đối soát, không hoàn tiền, nhưng sai nhãn cho vận hành); Loan coi reset khi ghi khoản vay là `FINERACT_UNEXPECTED_ERROR` không retry → saga dừng `REPAIR_REQUIRED` dù tra theo số hợp đồng là an toàn; AI scoring tương tự. Phát hiện ở drill Q13 `FineractResponseLost`/`FineractRequestLost` | Fixed Wave 2D (D32) — phân loại theo cause chain; `FineractRepaymentConnectionLossTest` (socket thật), `FineractHttpClientTest`, `AiCreditScoringHttpClientTest`; drill chạy lại `w2d-drill-responselost-20261017-154203`, `w2d-drill-requestlost-20261017-154552`, `w2d-drill-paymentcrash-20261017-155545` |
| B43 | P1 | R8 | Gateway nuốt body POST tới AI (422 "body: Field required"): JDK `HttpClient` của Gateway MVC gửi `Upgrade: h2c`, uvicorn 0.27 giữ body chờ nâng cấp. Vì vậy web admin gọi thẳng AI cổng 8000 không xác thực | Fixed Wave 3 (W3A, D33) — `UpstreamHttpClientConfigTest`, `UpstreamHttpClientWiringTest` |
| B44 | P2 | AI | AI test suite: TestClient của Starlette 0.35 (FastAPI 0.109) không chạy với httpx 0.28 mà google-genai bắt buộc, nên mọi test dùng TestClient lỗi khi tạo client; thiếu `pytest-asyncio`/`pytest-httpx`. Sau khi sửa môi trường còn 10 test đỏ từ trước W3A: thứ tự/ngưỡng luật trong `config/product_config.json` lệch test (`test_rule_engine`, `test_config_rules_api::test_xoa_luat_mac_dinh`) và payload cũ của `TestRouterV14` | Partly fixed Wave 3 — FastAPI 0.115.6 + `requirements-dev.txt`; 10 test lệch cấu hình chờ Hải quyết cấu hình nào là chuẩn (đổi bên nào cũng đổi điểm tín dụng đang chạy) |
| B45 | P1 | R5 | SmartCA ký SHA-256 của cả tệp (`data_to_be_signed = pdfDocumentHash`), giá trị chữ ký chỉ được băm rồi bỏ: PDF không có chữ ký nhúng, không ai kiểm được chữ ký từ tệp | Fixed W3C — ký hash signed attributes và nhúng CMS; `PadesSignatureChainTest`, IT PAdES |
| B46 | P1 | R5 | `get_certificate` bỏ qua `cert_data`/`chain_data`, không kiểm hạn/khóa, chỉ đọc `cert_status_code` (đặc tả 4.1 dùng `cert_status = "Đang hoạt động"`) | Fixed W3C — `VnptSmartCaSignatureProviderTest` |
| B47 | P2 | R5 | Không có worker hỏi SmartCA: người dùng xác nhận trên app mà không quay lại FINORA thì chữ ký không được ghi; phiên bỏ dở treo `SIGNING` | Fixed W3C — `ContractPdfSignatureWorker`; IT bất đồng bộ |
| B48 | P2 | R5 | Ràng buộc V9 `UNIQUE(contract_id, artifact_type)` không chứa được các bản ký nối tiếp | Fixed W3C bằng bảng riêng (D46) |
| B49 | P3 | R5 | Hết hạn trong lúc ký ghi history `from_status = PENDING_SIGNATURE` cố định, sai với hợp đồng nhiều bên | Fixed W3C |
| B50 | P1 | R6 | Worker gửi proof tới ledger đang cấu hình: đổi provider thì proof Fabric có thể nhận receipt MOCK (và ngược lại), DB không chặn | Fixed W3D — định dạng receipt tách biệt, worker và check constraint theo provider |
| B51 | P1 | R6 | Hash proof repayment cũ phụ thuộc thứ tự khóa JSON; outbox Payment lưu JSONB nên đảo khóa, auditor không tính lại được | Fixed W3D — hash v2 JSON chuẩn hóa; proof v1 vẫn kiểm được |
| B52 | P1 | R6 | Consumer Blockchain không có error handler: DB lỗi khi consume làm mất yêu cầu proof sau 10 lần thử liền, không có DLT | Fixed W3D — `<topic>.DLT` |
| B53 | P2 | R6 | Proof id ngẫu nhiên: cùng mốc phát lại với eventId mới tạo proof logic thứ hai | Fixed W3D — UUIDv5 theo mốc + unique index + index trong chaincode |
| B54 | P2 | R6 | Blockchain không cấu hình JWT resource server: mọi endpoint, kể cả health, rơi về user Basic auth do Spring sinh | Fixed W3D — `BlockchainSecurityConfig` |
| B60 | P2 | R7 | Nhà đầu tư nhận một thông báo mới mỗi ngày khoản vay còn quá hạn | Fixed W3F (D61) |
| B61 | P3 | R7 | Loại thay đổi servicing chưa biết bị hiện thành "quá hạn" | Fixed W3F |
| B62 | P1 | R7 | Consumer Notification không có error handler, DLT hay theo dõi trùng: event lỗi bị bỏ qua sau 10 lần thử | Fixed W3F — `<topic>.notification.DLT`, xử lý một lần theo `eventId` |
| B63 | P3 | R7 | Template có thể in `null`; `.env.example` của mobile còn mock miền thông báo | Fixed W3F |
| B64 | P2 | R7 | Event Loan/Payment không mang id người nhận | Workaround W3F bằng bảng tra; sửa thật ở producer (P1–P7 trong W3F §13) |
| B65 | P2 | R1 | Launcher E2E chạy jar có sẵn dù mã nguồn đã sửa sau lần build: lượt `w3a-installment-20261017-183146` báo `withdrawal.admin-queue-reachable` 404 vì jar Gateway build lúc 16:31, trước commit thêm route `/api/v1/admin/withdrawals/**` lúc 17:25. Evidence trên jar cũ cho kết luận sai về code | Fixed Wave 3 — `Assert-JarFresh` so jar với file mới nhất trong `src/main`/`pom.xml` của module, `finora-common` và pom gốc, từ chối kèm hướng dẫn `-Build` (`-AllowStaleJar` khi cố ý); chạy lại `w3a-installment-20261017-184734` 79/79 |
| B70 | P1 | R8/CIC | Rủi ro lộ CCCD qua CIC Node công khai: bản deploy không xác thực API đọc (`/api/v1/ho-so`, `/api/v1/nhat-ky`), lưu toàn bộ dữ liệu trong Vercel Blob `public` (`cic/du-lieu.json`, không hậu tố ngẫu nhiên), tra CCCD lạ thì lưu hồ sơ mới + nhật ký kèm CCCD. Trong khi đó `docker-compose*.yml` và `.env.example` để `USER_CIC_MAPPING_ENABLED` mặc định `true` trỏ tới bản này, trái với registry ("mặc định tắt"). Lúc kiểm (2026-10-17) kho chỉ có 1.000 hồ sơ mẫu, 2 dòng nhật ký: chưa lộ thật | Fixed phía FINORA Wave 3 — mặc định `false` ở hai compose và `.env.example`, README và `LEGAL-DATA-01` ghi chỉ dùng CCCD tổng hợp với bản deploy. Phía CIC: nhánh `feat/finora-delinquency-intake` chặn lưu mapping khi kho là Blob public; xác thực API đọc và kho riêng tư chờ Hải quyết |
| S01 | P1 | R8 | Tự đăng ký `ADMIN`; AI không xác thực (kể cả `PUT config`); endpoint nội bộ Notification `permitAll`; `mock-complete` không gắn môi trường; PIN token dùng lại được trong TTL; URL Neon mặc định trong `application.yml` của Loan | W3A (D33–D40): AI/Notification xác thực, danh tính service, cổng môi trường, PIN gắn thao tác, URL Neon bỏ khỏi `application.yml`, chống dò mật khẩu và giới hạn tần suất; unit/IT xanh. E2E `w3a-installment-20261017-184734` 79/79 sau khi realm có client/role mới. Chặn tự đăng ký `ADMIN` vẫn hoãn (D20) |

## 4. R0 — Đồng bộ source, roadmap và tài liệu

### Việc làm

1. Commit/push độc lập ba repository backend, web và mobile; không đưa secret hoặc `.env` thật vào Git.
2. Web/mobile giữ các thay đổi servicing hiện hành: admin thẩm định cơ cấu, investor consent,
   quote trả trước/tất toán và nhãn input tiếng Việt rõ nghĩa.
3. Compose bỏ CIC Java cũ; `CIC_SERVICE_URL` mặc định là endpoint Node đã deploy.
4. Cập nhật `01-project-context.md`, roadmap và link plan này trong cùng commit backend.
5. Chạy validator rules, Docker Compose config, Java verify, web build và mobile TypeScript.

### Acceptance

- Ba repo sạch sau push và `HEAD` đã có trên `origin/main`.
- Không còn `../../cic-service`, `http://cic:9000` hoặc mô tả backend là skeleton trong cấu hình chính.
- Skill `finora-engineering` vẫn pass validator; không thay đổi rule nếu rule đang đúng.

R0 hoàn tất ngày 2026-10-17 tại các commit backend `c1ca1ba`, web `ac4ad2a` và mobile
`531f5ee`; commit tài liệu xác nhận cuối có thể nằm sau SHA backend nêu trên.

## 5. R1 — Bộ E2E vertical slice lặp lại được

### Hai checkpoint

- **R1A — Baseline/discovery:** dựng môi trường và dữ liệu xác định, chạy happy path một lần, ghi lại
  mọi sai lệch bằng trace/saga/event/business reference. Fault case được phép đỏ nhưng phải tái hiện được
  và được chuyển thành defect thuộc R2 hoặc R3.
- **R1B — Regression/acceptance:** sau khi R2/R3 hoàn tất, chạy lại cùng dữ liệu và tất cả fault case.
  Chỉ R1B xanh mới được gọi core flow là ổn định.

### Trạng thái R1A ngày 2026-10-17

Bộ chạy tự động nằm ở `docker/e2e/` (task plan
[`R1A-e2e-vertical-slice-baseline.md`](R1A-e2e-vertical-slice-baseline.md), runbook
[`E2E-VERTICAL-SLICE.md`](../../docs/testing/E2E-VERTICAL-SLICE.md)). Trên stack local với Fineract thật,
AI v17, CIC Node đã deploy, ký `MOCK`, nạp/giải ngân provider `mock` và proof `MOCK`, kịch bản đi hết từ đăng ký
tới phân bổ một kỳ trả nợ; kết quả từng lần chạy và defect phát hiện được ghi trong task plan R1A và defect
register mục 3b. R1A chưa thay R1B: restart giữa saga, timeout Fineract, sáu fixture servicing và test clock
vẫn chờ R2/R3.

### Trạng thái R1B ngày 2026-10-17

Harness có tham số `-Servicing Installment|PartialPrepayment|EarlySettlement`, mỗi lần chạy một khoản vay riêng trên stack thật. Fixture 3 (trả trước một phần) và 4 (tất toán) chạy hết luồng tiền và đối soát đúng một lần (Fineract, ví, Loan `SETTLED`, Note và vị thế `CLOSED`, biểu phí V2); lần chạy đầu phát hiện B28/B29 (Fineract thu lãi tương lai).

Wave 2C (core V4, [`W2C`](W2C-daily-interest-core-and-early-installment.md)) xanh cả ba fixture trên Fineract thật: `w2c-installment-20261017-110318` (49/49: trả kỳ 1 trước hạn bị giữ, chặn trả hai lần và báo giá, hủy hoàn tiền, dời đồng hồ tới ngày đến hạn, Fineract ghi 17/11 gốc 1.624.000 + lãi 102.000 đúng lịch), `w2c-partialprepayment-20261017-110733` (37/37, lãi 0) và `w2c-earlysettlement-20261017-111021` (37/37, lãi 0). Đồng hồ E2E (Q12) đã có, nên fixture 2 (trả thiếu rồi khắc phục), 5 (cơ cấu), 6 (default/CIC/cure) và restart giữa saga, timeout Fineract chuyển sang Wave 2D.

Wave 2D ([`W2D`](W2D-incident-operations-overdue-cure-and-auto-invest-acknowledgement.md)) chạy fixture 2, 5, 6 trên Fineract thật với đồng hồ E2E và DB local riêng (D24, D25); lượt đầu của fixture 6 phát hiện B31. Phần 2 thêm năm drill lỗi thật (Q13, D31): mất phản hồi và mất request giữa Payment và Fineract (lượt "Ghi lại" B32 trên Fineract thật), tắt hẳn Loan sau khi Fineract ghi khoản vay, tắt Payment sau khi provider chuyển tiền, tắt Investment khi có `LoanDisbursed`; sáu fixture hồi quy chạy lại qua Toxiproxy đều xanh; drill phát hiện B42. Kết quả từng lượt ở mục 5 của W2D. R1B còn: drill hết hạn ký trên stack thật (đồng hồ Loan đã có), drill lỗi mạng Loan → Fineract và Investment → Payment, chuyển nhượng Note cạnh trả nợ (R11).

### Luồng demo chuẩn

```text
Borrower đăng nhập/eKYC
 → tạo hồ sơ → AI chấm → admin duyệt
 → borrower chấp nhận điều khoản → listing mở
 → investor nạp mock/ZaloPay sandbox → đặt lệnh/hold
 → đủ vốn → lender ký → borrower ký
 → saga giải ngân → borrower nhận tiền → Note active
 → borrower trả một kỳ → investor nhận phân bổ
 → Loan/Investment/Blockchain/Notification cập nhật projection
```

### Dữ liệu và bằng chứng bắt buộc

| Bước | Evidence dễ hiểu | Evidence kỹ thuật |
|---|---|---|
| Hold | Số dư khả dụng giảm, số tiền đang giữ tăng | wallet/hold/ledger cùng reference |
| Ký | Cả hai bên thấy cùng hợp đồng và trạng thái | document hash + signer evidence |
| Giải ngân | Borrower tăng số dư, investor bị capture | balanced ledger + saga step + provider ref |
| Note | Investor thấy khoản đầu tư đang hiệu lực | Note/ownership snapshot/version |
| Trả kỳ | Borrower giảm tiền, investor nhận phần của mình | Fineract transaction + distribution ledger/outbox |

### Bộ servicing bắt buộc sau happy path

Mỗi cơ chế dùng một khoản vay/fixture độc lập để kết quả không phụ thuộc thứ tự chạy:

1. Trả đúng một kỳ.
2. Trả thiếu rồi khắc phục nghĩa vụ quá hạn.
3. Trả trước một phần gốc, không thu lãi tương lai và áp đúng policy fee/version.
4. Tất toán toàn bộ, đóng Loan/Note và phân bổ tiền đúng ownership snapshot.
5. Cơ cấu kỳ hạn: admin thẩm định, toàn bộ chủ Note biểu quyết, chỉ khi đồng thuận mới đổi lịch Fineract.
6. Default/CIC/cure: chuyển nhóm theo DPD liên tục, không cộng dồn các episode đã chữa; trả đủ quá hạn
   tạo episode phục hồi nhưng không xóa lịch sử.

### Fixture thời gian và reset

- Có lệnh seed/reset dữ liệu test theo namespace/run ID; không sửa tay database và không dùng PII thật.
- Test quá hạn 10/91/181/361 ngày dùng test clock hoặc fixture Fineract riêng chỉ bật trong profile test;
  tuyệt đối không cho API “đổi ngày” xuất hiện ở demo/prod. Wave 2C dựng đồng hồ E2E (Q12): harness bật business date
  của Fineract local và chạy lại Payment với `-PaymentClockOffset P<n>D` (Payment chỉ nhận khi provider tiền là mock),
  rồi luôn trả về ngày thật; fixture DPD của Wave 2D cần thêm offset tương tự cho Loan.
- Mỗi fixture ghi rõ Product/servicing policy version, borrower, investor, số dư đầu, ngày nghiệp vụ và
  expected schedule/ledger/Note/CIC result.
- CIC Node là dependency ngoài FINORA: baseline phải kiểm tra contract/version, timeout và lỗi không
  sẵn sàng; không phát triển lại CIC trong repository này.

### Ca lỗi tối thiểu

- Gửi lặp cùng idempotency key.
- Kafka delivery trùng hoặc đến trễ.
- Dừng service giữa saga rồi bật lại.
- Fineract timeout sau khi có thể đã xử lý.
- Investment/Notification/Blockchain tạm tắt rồi bật lại.

### Acceptance

- R1A có script/checklist tạo dữ liệu sạch, kết quả mong đợi theo từng SoR và defect list gán đúng R2/R3.
- R1B chạy lại không cần sửa DB tay; core happy path và sáu cơ chế servicing đều xanh.
- Duplicate/restart/event trễ không tăng/giảm tiền lần hai; có trace/saga/event ID để lần theo toàn luồng.
- Báo cáo tách rõ dependency nào thật, sandbox, mock hoặc fail-closed.

## 6. G-POLICY — Phiên bản chính sách và điều khoản hợp đồng

### Cách hiểu nghiệp vụ

Khoản vay đã ký phải giữ nguyên cách tính đã cam kết. Khi FINORA đổi phí hoặc công thức, chỉ hợp đồng
mới sau ngày hiệu lực dùng phiên bản mới; không lấy cấu hình hiện tại áp ngược cho khoản vay cũ.

### Việc phải chốt trong task con

- Một policy version bất biến gồm ngày hiệu lực, dải kỳ hạn, rate/minimum fee, cách chia 80/20,
  quy tắc không thu lãi tương lai và lựa chọn rút ngắn kỳ/giảm tiền kỳ.
- Product/Contract lưu hoặc tham chiếu snapshot policy đã công bố; PDF/text/hash và borrower consent
  dùng cùng version. Artifact đã ký không được render lại bằng policy mới.
- Quote trả trước/tất toán trả về policy version, thời điểm tính, principal, accrued interest, từng phí,
  phần investor/FINORA và expiry; web/mobile chỉ hiển thị breakdown backend trả về.
- Migration/rollout nêu rõ khoản vay legacy dùng công thức nào; không “nâng cấp” hợp đồng đang hiệu lực
  bằng update hàng loạt.
- Dẫn `LEGAL-DISCLOSURE-01`, `LEGAL-CONTRACT-01`, `LEGAL-SERVICING-01`; tách điều pháp luật yêu cầu,
  policy demo FINORA và giới hạn kỹ thuật.

### Acceptance

- Test cùng ngày giao dịch trên hai hợp đồng có policy version khác nhau cho đúng hai kết quả đã cam kết.
- PDF, quote, Payment/Fineract breakdown, mobile và event evidence cùng version/công thức.
- Thay cấu hình hiện tại không làm đổi quote hợp lệ hoặc nghĩa vụ của hợp đồng cũ.

## 7. R2 — Saga, Fineract và financial recovery

### Mục tiêu nghiệp vụ

Nếu mạng lỗi sau khi tiền/core đã xử lý, hệ thống phải hỏi lại nguồn chuẩn và tiếp tục đúng bước,
không POST mù, không chuyển `FAILED` khi kết quả thực tế còn chưa biết.

### Việc còn lại

- Test restart tại từng bước disbursement và repayment với Fineract thật.
- Phân biệt `FAILED` với `UNKNOWN_OUTCOME`; unknown phải reconcile bằng external reference.
- Chứng minh capture, borrower credit, core booking và event publish không bị lặp.
- Hoàn thiện màn/admin action cho incident thật sự cần con người quyết định.
- Chốt auto-debit chỉ sau khi manual repayment/reconciliation đạt phase gate.

### Trạng thái Wave 2 ngày 2026-10-17

Task plan [`R2-W2`](R2-W2-business-date-and-financial-recovery.md) (`READY_FOR_REVIEW`): ngày nghiệp vụ Việt Nam,
booking theo ngày giải ngân thực tế, lease/claim token cho saga `CORE_BOOKING`, lease → đối soát cho khoản giải ngân
Payment, phân loại lỗi repayment/quote và timeout outbox. Còn lại của R2: restart/timeout trên stack thật (R1B), màn
admin cho incident cần người quyết định, auto-debit sau phase gate.

Wave 2D ([`W2D`](W2D-incident-operations-overdue-cure-and-auto-invest-acknowledgement.md)): màn "Sự cố giao dịch" trên web
cho ba hàng đợi (trả nợ chờ đối soát/ghi lại B32, giải ngân chờ kết quả provider, saga `REPAIR_REQUIRED` ghi core lại có
ghi chú); Loan V31, Payment V16–V17. Phần 2: restart/timeout trên stack thật (Q13, D31) xanh, sửa phân loại lỗi mạng
B42 (D32), admin tra một lệnh giải ngân theo `sagaId`. Còn lại: duyệt hai người và phân quyền chi tiết cho thao tác sự cố
(R8), auto-debit sau phase gate.

### Transaction/concurrency

```text
Transaction A: khóa aggregate + lưu durable command/outbox
Ngoài transaction: gọi Payment/Fineract/provider
Transaction B: đối chiếu external reference + ghi kết quả + outbox tiếp theo
```

Mỗi request có business idempotency key và external reference ổn định. Không giữ DB lock trong lúc
gọi HTTP. Worker dùng processing lease; hết lease phải reconcile trước khi tiếp quản.

### Acceptance

- Testcontainers/integration và smoke Fineract thật cho duplicate, timeout, restart đều xanh.
- Ledger cân bằng và principal/schedule không lệch sau mọi ca lỗi.
- `LEGAL-PAYMENT-01` và `LEGAL-SERVICING-01` được dẫn trong task triển khai.

## 8. R3 — Investment reconciliation và projection rebuild

### Việc còn lại

1. Reconcile `InvestmentOrder` ↔ Payment hold/capture/release bằng reference, không sửa Payment DB.
2. Repair Note activation khi `LoanDisbursed` đến trễ/trùng hoặc consumer restart.
3. Khóa ownership snapshot/version tại thời điểm phân bổ; test transfer cạnh repayment.
4. Rebuild portfolio/cashflow từ event/Note state thay vì sửa số hiển thị bằng tay.
5. Hoàn tất consumer thật của `RepaymentDistributed.v1` và servicing events cho cơ cấu/default/cure.

Trạng thái Wave 2 ngày 2026-10-17: task plan [`R3-W2`](R3-W2-investment-event-order-and-portfolio.md)
(`READY_FOR_REVIEW`, Hải review trước merge) đã làm mục 2 cho trường hợp event lệch thứ tự, guard thao tác sửa lỗi
và giữ vị thế đã kết thúc trong danh mục. Mục 1, 3, 4, 5 còn nguyên.

Wave 2D ([`W2D`](W2D-incident-operations-overdue-cure-and-auto-invest-acknowledgement.md), Hải review trước merge): mục 1
xong (D30, B38–B41: đối soát lệnh ↔ khoản giữ, nhả sau commit, lùi lịch và màn sự cố); mục 5 xong phần servicing
(quá hạn/nợ xấu/cơ cấu/tất toán có fixture E2E, ngày đáo hạn từ lúc giải ngân B34, nhóm nợ theo đợt B36); drill tắt
Investment khi `LoanDisbursed` được phát chứng minh consumer hội tụ sau khi bật lại. Mục 3 chưa có version sở hữu nghiêm
ngặt: Payment khóa dòng sở hữu lúc phân phối, bỏ cập nhật cũ theo `changedAt`, Investment chuyển lại tiền trả nhầm chủ cũ
(`note_repayment_redirects`, unit test); thiếu E2E chuyển nhượng cạnh trả nợ (cần chợ Notes trong harness, R11). Mục 4:
danh mục tính lúc đọc từ Note và trạng thái servicing nên không có bảng cộng dồn để dựng lại; trạng thái servicing chỉ
dựng từ event (Kafka giữ offset), chưa có API kéo lại từ Loan khi mất event (R9 replay/DLT).

### Query/index cần chứng minh trong task con

- Queue theo `status + next_retry_at + id`, batch hữu hạn.
- Lookup unique theo `source_event_id`, `payment_hold_reference`, `loan_id/note_id/version`.
- Portfolio phân trang; không REST call theo từng Note và không N+1.

### Acceptance

- Tắt Investment trong lúc giải ngân/trả nợ rồi bật lại vẫn hội tụ đúng.
- Rebuild cho ra tổng principal/interest/cashflow bằng ledger/event nguồn.
- Một event trùng không tạo Note, ownership hoặc portfolio entry thứ hai.

## 9. R4 — Tiền ngoài FINORA

### Current state

- Có mock top-up và ZaloPay sandbox create/callback HMAC.
- Có wallet/ledger/hold/capture/transfer nội bộ.
- ZaloPay disbursement hiện fail-closed; withdrawal/link bank/VietQR chưa có.

### Việc còn lại

- Chỉ bắt đầu sau G-POLICY, R1B–R3 và security gate R8A.
- Chọn rõ phạm vi demo: ZaloPay sandbox top-up + mock payout, hoặc provider payout sandbox thật.
  Phương án khuyến nghị đã chọn ngày 2026-10-17: ZaloPay sandbox top-up + mock payout có callback HMAC và
  đối soát; payout thật chỉ mở sau legal review `LEGAL-PAYMENT-01`.
- Thêm withdrawal/payout request, provider reference, callback/query status và reconciliation.
- Không cộng/trừ ví chỉ dựa trên redirect mobile; callback phải xác thực chữ ký và idempotent.
- Batch/admin reconciliation: provider amount/status ↔ Payment transaction/ledger.
- Công bố rõ tiền sandbox là giả và không mô tả FINORA đang giữ tiền thật.

Trạng thái Wave 3 ([`W3B`](W3B-wallet-withdrawal-mock-payout.md)): rút tiền về tài khoản ngân hàng qua provider payout
giả lập (D41–D44) — policy Q4, trừ ví một lần dưới khóa dòng ví, callback HMAC, hoàn tiền, tra cứu khi mất callback hoặc
worker chết, hàng đợi sự cố admin; Payment V18. E2E `w3a-installment-20261017-184734`: 8 check `withdrawal.*` xanh. Còn lại: đối soát tổng theo lô provider ↔ ledger (cần sao kê của provider
thật), payout thật sau legal review.

### Acceptance

- Callback trùng không cộng tiền hai lần; callback giả/sai HMAC bị từ chối.
- Timeout query lại provider bằng reference; kết quả unknown không tự retry chuyển tiền mù.
- Tổng provider settlement khớp ledger; mismatch vào queue đối soát.
- Tuân `LEGAL-PAYMENT-01` và phải legal review trước mọi môi trường tiền thật.

## 10. R5 — Chữ ký PDF nhiều bên

### Current state

SmartCA UAT tạo giao dịch ký hash, poll `signature_value` và lưu receipt/evidence. PDF signable giữ
nguyên bytes; trạng thái “đã ký” hiện được chứng minh bằng hash + receipt, chưa phải PDF PAdES nhúng.

### Hai hướng phải chốt

1. **Demo receipt rời:** giữ nguyên, UI/PDF cover công bố rõ bằng chứng tách rời.
2. **PAdES:** dùng SDK/contract chính thức để nhúng chữ ký theo incremental update; lender ký trước,
   mỗi lender tiếp theo ký bản mới, borrower ký cuối và hash/version phải theo chuỗi tài liệu.

Quyết định 2026-10-17 (D13): chọn hướng 2 ở mức PAdES-B-B với VNPT SmartCA UAT — PDFBox chuẩn bị chữ ký
external theo incremental update, SmartCA ký hash (`sign_type: hash`), BouncyCastle đóng gói CMS. Trước khi
code phải xác nhận `get_certificate` trả chứng thư DER đầy đủ; chứng thư UAT không phải chữ ký số có giá trị
pháp lý production.

Trạng thái Wave 3 ([`W3C`](W3C-pades-contract-signing.md), Hải review phần Investment/hợp đồng trước merge):
hợp đồng mới ký PAdES-B-B nối tiếp, bản SIGNABLE là tiền tố byte của bản ký đủ, biên nhận riêng (D45–D49, B45–B49),
Loan V32. Loan 283 unit + 19 IT xanh; pyHanko 0.25.1 xác nhận ba chữ ký hợp lệ đúng thuộc tính PAdES-B-B. Còn lại: ký
SmartCA UAT thật (cần người duyệt trên app, W3C §13), kiểm bằng Acrobat; chứng thư MOCK/UAT không có giá trị pháp lý.

### Acceptance nếu chọn PAdES

- PDF mở bằng trình đọc chuẩn thấy chữ ký/chứng thư và kiểm tra được integrity.
- Nhiều chữ ký không làm hỏng chữ ký trước.
- Retry cùng signer/document version không tạo hai chữ ký.
- Contract state chỉ active khi signer bắt buộc hoàn tất; `LEGAL-CONTRACT-01` được review.

## 11. R6 — Blockchain proof và Fabric

### Current state

Đã có proof store hash-only, idempotency, lease/retry/DLT, mock receipt và repayment consumer.
Fabric adapter thật cố ý fail-closed; contract/disbursement consumer và query/explorer chưa có.

### Mốc đề xuất neo hash

| Mốc | Payload tối thiểu | Không đưa lên chain |
|---|---|---|
| Hợp đồng ký đủ | document hash, contract/version, signer evidence hash | PDF, CCCD, tên/địa chỉ |
| Giải ngân | loan/saga/provider reference hash, amount hash | tài khoản ngân hàng |
| Repayment | source event, core transaction, distribution hash | chi tiết PII |
| Note | issuance/transfer/version hash | hồ sơ người vay |
| Servicing | settlement/reschedule/default decision hash | lý do nhạy cảm dạng thô |

### Việc còn lại

- Quyết định 2026-10-17 (D14): dựng Hyperledger Fabric local thật; kiểm RAM máy dev trước khi bật cùng
  stack E2E, ledger `MOCK` vẫn là mặc định khi Fabric tắt.
- Duyệt event contract từng mốc; thêm consumer idempotent.
- Dựng Fabric network/chaincode/identity, submit/query adapter và receipt thật.
- Explorer/query theo business reference và integrity reconciliation DB ↔ chain.
- Fabric outage chỉ retry/DLT proof, không rollback tiền/hợp đồng đã commit.

Trạng thái Wave 3 ([`W3D`](W3D-fabric-proofs.md)): mạng Fabric local thật, chaincode `finora-ledger`, bốn mốc, API
proof và worker đối soát (D50–D54, B50–B54). Blockchain 49 unit + 11 IT xanh; chạy thật: bốn mốc ghi lên block 4–7,
Org2 đọc độc lập cùng hash, hash giả cùng id bị từ chối, sửa hash trong PostgreSQL bị phát hiện `MISMATCHED`, drill
dừng `peer0.org2` giữ `PENDING` rồi ghi đúng một lần khi peer quay lại. Mặc định vẫn MOCK; harness kiểm proof qua API
cho cả hai ledger. Còn lại: trang explorer web, chứng cứ pháp lý `NEEDS_LEGAL_REVIEW`, Fabric CA và nhiều orderer cho
production.

### Acceptance

- Hash đối chiếu lại được từ artifact nguồn; mock và Fabric receipt không bị nhầm.
- Duplicate/restart không tạo proof logic thứ hai.
- `LEGAL-DATA-01` và `LEGAL-CONTRACT-01` được kiểm tra trước rollout.

## 12. R7 — Notification hoàn chỉnh

### Current state

In-app delivery, unread/read và một số template servicing đã có. Chưa có vòng đời device token,
preference, provider push/SMS/email đầy đủ và bounded retry/DLT cho mọi sự kiện.

### Việc còn lại

- Ma trận actor × event: funding, ký, giải ngân, kỳ đến hạn, paid, overdue transition,
  reschedule request/decision, settlement, default/cure và reconciliation cần can thiệp.
- Chỉ gửi quá hạn khi chuyển stage/episode, không spam mỗi ngày.
- Push provider adapter, token lifecycle, opt-in/preference và redaction nội dung màn khóa.
- Dedup theo source event + recipient + channel; delivery lỗi không rollback nghiệp vụ.

Trạng thái Wave 3 ([`W3F`](W3F-notifications-expo-push.md), Hải review trước merge): Notification xử lý mọi event
Loan/Investment/Payment hiện có và event rút tiền, Expo Push (D60–D64, B60–B64; B24 đã sửa), Notification V2–V4.
134 unit + 18 IT xanh; mobile đăng ký thiết bị và màn "Cài đặt thông báo". Còn lại: push trên máy thật (cần EAS
projectId và development build), producer bổ sung `borrowerId`/event giữ khoản trả kỳ (P1–P7), mã hóa token khi lưu (R8B).

## 13. R8 — Security, privacy và audit

### R8A — Gate tối thiểu trước provider bên ngoài

- Negative authorization test cho borrower/investor/admin/service account trên resource thật.
- Callback/webhook bắt buộc xác thực chữ ký, chống replay, idempotent và không tin redirect từ client.
- Secret scan, tách credential dev/demo, redaction token/CCCD/document/signature/provider payload.
- Rate limit cho login, eKYC, top-up, quote và mutation tiền/chữ ký.

R8A phải đạt trước R4 và trước khi bật SmartCA/provider ngoài môi trường local. Không cần chờ toàn bộ
hardening production mới chạy R5–R7 bằng mock/fail-closed.

Trạng thái Wave 3 ([`W3A`](W3A-security-gate-service-identity-and-pin-binding.md), Hải review AI/User/Notification trước
merge): xong phần code của các mục trên (D33–D40); bước "quyền chéo" (người dùng thứ hai đổi ID không đọc được tài nguyên,
người vay không gọi được API quản trị, AI/Notification từ chối lời gọi không token) đã vào harness E2E. E2E `w3a-installment-20261017-184734` 79/79
(fixture trả kỳ mặc định kèm drill). Còn lại: redaction log và quét secret trong CI thuộc R9 (W3E).

### R8B — Hardening trước release

- Chốt JWT claim/role/resource authorization xuyên Gateway và service; test borrower/investor/admin.
- Admin RBAC chi tiết cho duyệt hồ sơ, servicing, reconcile và replay.
- Không log token, secret, CCCD, document URL, signature value hoặc provider payload nhạy cảm.
- Rotate secret, tách dev/demo/prod; quét repository và image trước release.
- CORS, rate limit, brute-force protection và audit append-only cho hành động nhạy cảm.
- Review retention/access/export/delete theo `LEGAL-DATA-01`.

Acceptance: negative authorization tests, secret scan và PII/log review có evidence; người dùng không
đọc/sửa tài nguyên của người khác chỉ bằng cách đổi ID.

## 14. R9 — Observability, vận hành và deployment

- Một lệnh dựng dependency cần thiết; health/readiness không chỉ kiểm tra mở cổng.
- Trace ID đi qua HTTP, outbox/Kafka, saga và provider; dashboard cho DLT, outbox lag,
  saga stuck, reconciliation incident và wallet invariant.
- Runbook cho restart, replay, manual reconcile và rollback migration tương thích.
- Backup/restore test cho từng database; không dùng chung credential giữa service.
- CI chạy validator rules, Java verify, web build, mobile typecheck và contract tests.
- Có environment matrix local/test/demo: dependency nào Docker, IDE, external URL, sandbox hoặc mock;
  env bắt buộc/tuỳ chọn và health check nghiệp vụ tương ứng.
- Pin/kiểm tra compatibility PostgreSQL 17 với Flyway/driver và nâng version có migration test, không bỏ
  qua cảnh báo compatibility chỉ vì ứng dụng vẫn khởi động.
- Monitor CIC Node như dependency ngoài: timeout/error-rate/contract smoke; outage không được tạo điểm
  mặc định hoặc đánh dấu hồ sơ đạt.
- Giám sát job cuối ngày của Fineract (`LA_RINT`, `LA_ARAG`, cron 00:01): DPD, nhóm nợ và số tiền khắc phục quá hạn của
  FINORA lấy từ `summary.totalOverdue` và lịch Fineract, nên job không chạy thì DPD đứng yên và khoản vay quá ngày đáo hạn
  thiếu phần lãi sau đáo hạn (D25). Health nghiệp vụ phải báo khi lần chạy thành công gần nhất cũ hơn 26 giờ.

## 15. R10/R11 — Phần mở rộng sau phase gate

### AI

- Backtest/champion-challenger và model drift có dataset/version/metric.
- Fraud mở rộng bằng payment behavior contract; document forgery chỉ khi có dữ liệu hợp pháp.
- Early warning là tín hiệu hỗ trợ, không tự động xử phạt người vay không có policy duyệt.

### Investment

- Auto-invest: concurrency, on-behalf authorization, hold/release và giới hạn rủi ro.
- Secondary market: settlement/ownership atomicity, self-trade prevention, cancel race,
  SSE reconnect và reconciliation Payment ↔ Note transfer.
- Đây không phải blocker cho demo primary-market repayment.

## 16. Bản đồ dữ liệu hiện hành ở mức service

Plan này không thêm bảng. Quan hệ logic cần giữ khi viết task con:

```text
User/Keycloak identity
        │ borrowerId/investorId (logical ID, không FK chéo)
        ▼
LoanApplication 1 ── 0..1 LoanContract ── 1..N ContractSignerEvidence
        │ loanId/event                              │ document hash
        ▼                                           ▼
InvestmentListing 1 ── 1..N Commitment ── 1..N Note/OwnershipVersion
        │ hold/payment reference                    │ ownership snapshot
        ▼                                           ▼
PaymentTransaction 1 ── 2..N LedgerEntry      RepaymentDistribution
        │ core/provider reference                   │ event reference
        ▼                                           ▼
Fineract loan/transaction                    BlockchainProof/NotificationDelivery
```

Các mũi tên chéo service là quan hệ bằng external/business ID và event, không phải foreign key và
không cho phép join database chéo.

## 17. Test matrix tổng hợp

| Nhóm | Happy | Validation | Duplicate | Concurrency | Timeout/restart | Reconcile |
|---|---:|---:|---:|---:|---:|---:|
| Application/scoring | Có | Có | Có | — | AI timeout | Có |
| Funding/hold | Có | Có | Có | Bắt buộc | Payment timeout | Bắt buộc |
| Contract/signing | Có | Có | Có | signer song song | SmartCA poll/restart | Bắt buộc |
| Disbursement | Có | Có | Bắt buộc | Bắt buộc | Bắt buộc | Bắt buộc |
| Repayment/prepayment | Có | Có | Bắt buộc | Bắt buộc | Bắt buộc | Bắt buộc |
| Reschedule/default | Có | Có | Có | consent race | event trễ | Bắt buộc |
| Blockchain/notification | Có | Có | Bắt buộc | worker lease | provider outage | Bắt buộc |

Mỗi ô “Có/Bắt buộc” phải trỏ tới test ID/evidence cụ thể. Repayment/prepayment bao gồm đủ sáu fixture
ở R1; overdue/default dùng test clock thay vì thay ngày trực tiếp trong database.

## 18. Definition of Done cuối hệ thống

- G-POLICY, R1B–R3 đạt trước khi gọi luồng core là ổn định.
- Không có chỉnh DB tay trong kịch bản demo chuẩn.
- Mọi thay đổi tiền cân bằng và truy được bằng business/external reference.
- Fineract, Payment, Investment và Loan hội tụ cùng một trạng thái sau restart/event trễ.
- Web/mobile dùng API thật, có loading/empty/error/success và không hiển thị terminal giả.
- Tài liệu, event catalog, env example và UI test guide khớp code cùng commit.
- Các hạng mục production chưa làm được ghi rõ là mock/sandbox/backlog, không mô tả quá khả năng.

## 19. Nghiệm thu plan

Plan tổng hợp đang `ACTIVE`; đây là thứ tự điều phối, không thay thế task plan chi tiết. Mỗi workstream
mới vẫn bắt đầu bằng task plan `DRAFT`, chỉ chuyển `APPROVED` khi owner chốt phạm vi/contract. Khi hoàn
tất, ghi command/test evidence, commit SHA và bản đồ code thực tế thay vì chỉ đổi trạng thái bằng mô tả.

Trạng thái trong `finora-web/PLAN.md` và `finora-mobile/PLAN.md` là trạng thái nghiệm thu task, không phải
kết luận rằng toàn bộ code của luồng chưa tồn tại. `READY_FOR_REVIEW` nghĩa là code/build đã có nhưng vẫn
chờ owner/UAT; `IN_PROGRESS` hoặc `DRAFT` phải ghi rõ phần hiện hành và phần hardening còn dự kiến.
