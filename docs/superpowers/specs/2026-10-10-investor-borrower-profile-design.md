# Hồ sơ người vay cho nhà đầu tư — thiết kế

- Ngày: 2026-10-10. Người yêu cầu: Hải.
- Phạm vi: `finora-loan` (API mới), `finora-investment` (thêm 1 field), `finora-gateway` (route),
  `finora-mobile` (thẻ tóm tắt + màn mới).
- Quyết định đã chốt với Hải: **ẩn danh, đủ chỉ số**; **thẻ tóm tắt trên màn khoản vay + màn riêng**.

## 1. Vấn đề

Nhà đầu tư mở "Khoản vay #id" trên Sàn chỉ thấy mục đích, hạng, lãi suất, kỳ hạn. Sàn không có
thông tin nào về người vay, trong khi admin xem được đầy đủ ở trang thẩm định. Nhà đầu tư cần biết
khả năng trả nợ và lịch sử tín dụng của người vay trước khi góp vốn.

## 2. Căn cứ

- NĐ 94/2025/NĐ-CP, **Điều 22 khoản 3**: cung cấp thông tin đầy đủ, minh bạch, chính xác cho khách hàng.
  **Điều 22 khoản 8 điểm c**: cung cấp đầy đủ thông tin khoản vay trước khi khách hàng giao kết thỏa
  thuận cho vay. **Điều 11 khoản 2 điểm d**: dữ liệu được chia sẻ minh bạch giữa các bên tham gia
  nhưng phải bảo mật với bên không liên quan. (Đã đọc trực tiếp bản PDF chính thức, trang 9, 10, 22, 23.)
- Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15: tối thiểu hóa dữ liệu, nên không lộ định danh trực tiếp.
- `docs/integrations/LOAN-INVESTMENT-EVENTS.md` mục 1–2: Kafka không chở thu nhập/KYC; dữ liệu Loan sở
  hữu thì màn nhà đầu tư gọi REST Loan trực tiếp (tiền lệ: PDF hợp đồng).

## 3. Nhà đầu tư thấy gì

Nguyên tắc: **những gì admin thấy ở trang thẩm định, trừ SHAP và trừ định danh.**

| Nhóm | Trường | Nguồn trong Loan |
|---|---|---|
| Đánh giá tín dụng | điểm đánh giá, hạng, xác suất vỡ nợ (PD), điểm luật 0–100, cách duyệt (tự động/chuyên viên), lúc chấm | `credit_scoring_assessments`, `loan_applications.decision_source` |
| Khả năng trả nợ | thu nhập/tháng, nợ phải trả/tháng, DTI, thâm niên, nguồn (tự khai), lúc khai | `ApplicantFinancialSnapshot` |
| Nhân thân (không định danh) | tuổi, eKYC, nguồn hồ sơ (giả lập/User), nhà ở, học vấn | `borrower_eligibility_checks` + snapshot tài chính |
| Lịch sử vay tại FINORA | có lịch sử chưa, số khoản đã tất toán, trễ hạn 2 năm, vỡ nợ | `borrower_credit_profiles` |
| Bảng luật đã chấm | mô tả luật, trường đọc, giá trị đọc được, điểm/tối đa, trọng số, thiếu dữ liệu | `responseSnapshotJson.ruleTrace` |
| Khoản vay đề nghị | mục đích + phương án dùng vốn, số tiền, kỳ hạn, cách trả, lãi suất cuối, kỳ trả đầu/cao nhất, tổng phải trả, ngày giải ngân dự kiến | `loan_applications` + schedule `CONTRACT` (không có thì `SUBMISSION_SCORING`) |

Điểm CIC và số lần tra cứu CIC 6 tháng chỉ có trong bảng luật (Loan không lưu báo cáo CIC); app rút
hai dòng này ra nhóm "Lịch sử tín dụng" giống admin web.

**Không bao giờ trả:** `modelExplanation` (SHAP, kể cả `tom_tat`), `borrowerExplanation` (viết cho
người vay, phần gợi ý xếp theo SHAP), `rejectionReasons`, `inputSnapshot`, `borrowerId`, họ tên, CCCD,
SĐT, email, địa chỉ, ghi chú/lịch sử xử lý của admin, phiên bản mô hình/chính sách.

## 4. Kiến trúc

```text
Mobile ──GET /market/listings/{id}──────────────▶ Investment  (thêm applicationNumber vào response)
Mobile ──GET /investor/loan-applications/{applicationNumber}/borrower-profile──▶ Loan
                                     (JWT, ROLE_INVESTOR, hồ sơ đã lên sàn)
```

- Loan là System of Record của hồ sơ, snapshot tài chính và kết quả chấm điểm, nên Loan cấp API và
  tự kiểm quyền (rule 07, integration-security: "authorize tại service sở hữu dữ liệu").
- Investment không gọi Loan khi người dùng mở màn (đúng LOAN-INVESTMENT-EVENTS mục 2). Investment chỉ
  trả thêm `applicationNumber` (đã lưu sẵn trong `market_listings`), là public ID của hồ sơ.
- Không đổi event Kafka, không migration.

## 5. API mới — `finora-loan`

`GET /api/v1/investor/loan-applications/{applicationNumber}/borrower-profile`

- **Quyền:** service kiểm `ROLE_INVESTOR` và trả 403 `INVESTOR_ROLE_REQUIRED` dạng JSON (giống
  `requireAdmin`). Không chặn bằng matcher URL vì handler mặc định của Spring trả 403 rỗng, app sẽ hiểu
  nhầm thành "phiên đăng nhập hết hạn". App cũng chỉ gọi API khi người dùng có role `INVESTOR`.
- **Điều kiện:** hồ sơ tồn tại **và** `fundingStatus != null` (đã từng được đưa lên sàn). Không thỏa thì
  404 `RESOURCE_NOT_FOUND` giống hồ sơ không tồn tại, để không dò được hồ sơ chưa lên sàn. Hồ sơ đã đủ
  vốn vẫn xem được vì Note còn mua bán trên chợ thứ cấp.
- **Đọc:** 1 application + 1 eligibility + 1 credit profile + 1 assessment + tối đa 2 schedule. Số query
  cố định, không gọi User/AI/Fineract, `@Transactional(readOnly = true)`.
- **Snapshot hỏng:** JSON chấm điểm không đọc được thì `ruleResults = []`, phần còn lại vẫn trả.
- **Bảng luật:** map whitelist từng item (`ma, mo_ta, truong, gia_tri, diem, toi_da, trong_so,
  thieu_du_lieu`); `gia_tri` chỉ nhận số hoặc chuỗi, kiểu khác thành `null`.
- **Đọc snapshot dùng chung:** tách `readSnapshot` của `AdminLoanDecisionServiceImpl` thành component
  `StoredAiCreditResponseReader` để admin và nhà đầu tư dùng một cách đọc.

Response (tiền và tỷ lệ là JSON number như mọi DTO hiện có của Loan):

```json
{
  "applicationNumber": "LA-…",
  "loan": { "purposeCode": "EDUCATION", "purposeLabel": "Chi phí giáo dục", "purposeDetail": "…",
            "requestedAmount": 30000000, "requestedTermMonths": 12, "repaymentMethod": "ANNUITY",
            "finalAnnualInterestRate": 15.5, "firstInstallment": 2730000, "maximumInstallment": 2730000,
            "totalRepayment": 32760000, "expectedDisbursementDate": "2026-10-20" },
  "capacity": { "declaredMonthlyIncome": 30000000, "monthlyDebtObligations": 1500000, "dtiSnapshot": 5.0,
                "employmentLengthMonths": 120, "informationSource": "SELF_DECLARED", "capturedAt": "…" },
  "background": { "age": 30, "kycStatus": "VERIFIED", "profileSource": "MOCK_USER_PROFILE",
                  "homeOwnership": "OWN", "educationLevel": "UNIVERSITY", "checkedAt": "…" },
  "creditHistory": { "hasInternalCreditHistory": false, "completedLoanCount": 0,
                     "internalDelinquenciesLast2Years": 0, "internalDefaultedLoanCount": 0, "source": "NO_HISTORY" },
  "assessment": { "evaluationScore": 68.92, "creditGrade": "C", "pdProbability": 0.3568, "riskScore": 95,
                  "decisionSource": "ADMIN", "scoredAt": "…" },
  "ruleResults": [ { "code": "CHARACTER_CIC_HISTORY", "description": "Điểm tín dụng CIC — …",
                     "field": "cic_score", "value": 712, "points": 15, "maxPoints": 20, "weight": 1.0,
                     "missingData": false } ]
}
```

`creditHistory`, `assessment` có thể `null` (hồ sơ cũ thiếu bản ghi); các field trong `background` lấy từ
eligibility có thể `null`.

## 6. Mobile

- `MarketLoan.applicationNumber` (null với listing cũ). Không có thì ẩn thẻ hồ sơ người vay.
- **Thẻ "Hồ sơ người vay"** trên màn "Khoản vay #id", ngay dưới "Thông tin khoản vay": lưới 2×2 (điểm đánh
  giá + hạng, xác suất vỡ nợ, thu nhập/tháng, nợ trên thu nhập), một dòng eKYC · tuổi · điểm CIC, nút
  "Xem hồ sơ đầy đủ". Thẻ tự lo trạng thái tải/lỗi (có "Thử lại"), không chặn việc đặt lệnh.
- **Màn "Hồ sơ người vay"** (MarketStack `BorrowerProfile`, param `applicationNumber`): ghi chú ẩn danh;
  các thẻ Đánh giá tín dụng, Khả năng trả nợ, Lịch sử tín dụng, Nhân thân, Bảng luật đã chấm, Khoản vay đề
  nghị. Cùng khung `LoanDetailFrame`, cùng kiểu thẻ trắng `SoftShadow.card`.
- Hồ sơ giả lập (`profileSource = MOCK_USER_PROFILE`) thì ghi rõ "Tuổi và eKYC lấy từ hồ sơ giả lập của
  môi trường thử nghiệm" (không trình bày dữ liệu giả như thật).
- Giá trị luật định dạng theo đơn vị của danh mục trường AI (`truong_du_lieu.py`); trường lạ hiện số/chuỗi
  thô để luật admin mới thêm vẫn hiển thị được.
- Mock miền `market` có hồ sơ mẫu cho 4 khoản vay giả.

## 7. Kiểm thử

- Loan: unit test service (đủ quyền → đúng dữ liệu; hồ sơ chưa lên sàn → 404; snapshot hỏng → luật rỗng;
  response không chứa SHAP/borrowerId), test mapper bảng luật, WebMvc test 403 khi không phải INVESTOR.
- Investment: test mapper có `applicationNumber`.
- Mobile: `npx tsc --noEmit`; chụp màn bằng build web + server giả.
- Chạy thật: build lại Docker loan/investment/gateway, gọi API bằng tài khoản `investor@finora.vn`.
