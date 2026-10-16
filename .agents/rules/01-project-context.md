# Bối cảnh và trạng thái dự án

## Tổng quan

FINORA là khóa luận IUH 2026 về nền tảng P2P Lending. Backend là monorepo, còn web và mobile
là hai repository riêng nằm cùng workspace.

- Spring Boot 3.2, Java 21: `finora-gateway`, `finora-loan`, `finora-payment`,
  `finora-blockchain`, `finora-investment`, `finora-user`, `finora-notification`.
- Thư viện Java dùng chung: `finora-common`.
- FastAPI: `finora-ai` cho credit scoring, eKYC/OCR và explainability.
- Frontend: `finora-web` cho quản trị; `finora-mobile` cho người vay và nhà đầu tư.
- Core/identity/integration: Apache Fineract 1.15, Keycloak, Kafka, Redis, PostgreSQL,
  SmartCA UAT, ZaloPay sandbox/mock và CIC Node deploy ngoài Compose.

## CURRENT STATE — cập nhật 2026-10-17

- Luồng chính đã chạy xuyên service: đăng nhập/eKYC, tạo và chấm điểm hồ sơ, admin duyệt,
  niêm yết gọi vốn, nhà đầu tư giữ tiền, hợp đồng nhiều bên, SmartCA UAT, giải ngân ví,
  Fineract booking, Note, trả nợ/phân bổ và các projection phục vụ web/mobile.
- Loan servicing đã có kỳ thường, quá hạn/default/cure, tất toán sớm, trả trước một phần V3,
  yêu cầu cơ cấu, admin thẩm định và biểu quyết của chủ Note. Các thay đổi web/mobile tương ứng
  đang ở trạng thái `READY_FOR_REVIEW` và phải giữ bộ UI mobile mới.
- Payment có wallet, immutable balanced ledger, hold/release/capture, chuyển tiền nội bộ,
  mock top-up và ZaloPay sandbox callback. Luồng rút tiền/thanh toán ra ngoài thật và
  ZaloPay disbursement vẫn chưa hoàn thiện.
- SmartCA hiện ký hash PDF và lưu bằng chứng chữ ký rời. File PDF chưa nhúng chữ ký PAdES;
  nhiều bên ký cần incremental signing nếu triển khai giai đoạn sau.
- Blockchain có proof store hash-only, idempotency, lease/retry/DLT và consumer repayment.
  Fabric adapter thật, query/explorer và proof cho Contract/disbursement/Note/servicing còn thiếu.
- CIC dùng bản Node đã deploy tại `https://cic-service-node.vercel.app/`. Docker Compose không
  build CIC Java cũ; AI/User chỉ gọi qua `CIC_SERVICE_URL`. CIC không thuộc backlog triển khai
  nội bộ của FINORA trong giai đoạn hiện tại.
- PostgreSQL 17/Neon là persistence chính theo database-per-service; Docker PostgreSQL là
  fallback offline/test. Fineract có hai database riêng và chỉ được truy cập qua API.
- Code đã có Flyway, Testcontainers/integration tests, outbox/idempotent consumer ở các luồng
  tài chính chính. Bộ E2E R1A (`docker/e2e/`) chạy tự động từ đăng ký tới phân bổ một kỳ trả nợ trên
  Fineract thật, nhưng với KYC người vay mock trong Loan, chữ ký `MOCK`, nạp/giải ngân provider `mock`
  và proof `MOCK`. Ca restart/unknown outcome, sáu fixture servicing và test clock vẫn thuộc R1B.
- Trả kỳ với Fineract thật chỉ chạy được từ Wave 1 (2026-10-17) sau khi sửa field repayment template;
  còn defect P0 mở về ngày nghiệp vụ UTC và ngày giải ngân dự kiến khi booking (B06, B07 trong plan tồn đọng).

Nguồn tổng hợp việc còn lại là
[`../plans/finora-remaining-work-plan.md`](../plans/finora-remaining-work-plan.md). Roadmap đội
vẫn là bảng phân công; plan tồn đọng `ACTIVE` là thứ tự hoàn thiện và acceptance evidence hiện hành.
R1 được chạy hai vòng: baseline để phát hiện lỗi, rồi acceptance sau R2/R3; policy của hợp đồng cũ/mới
phải được khóa bằng version trước khi nghiệm thu toàn bộ servicing.

## TARGET STATE

- Database-per-service, giao tiếp qua REST/Kafka, không truy cập DB chéo.
- Flyway là cách duy nhất thay đổi schema.
- Event publication dùng outbox; consumer idempotent; mọi side effect tiền có reconciliation.
- Giải ngân dùng Saga orchestration do `finora-loan` điều phối; restart tiếp tục đúng bước.
- Fabric chỉ lưu hash/bằng chứng/audit, không lưu PII; database service vẫn là nguồn state nghiệp vụ.
- Loan, Payment, Blockchain, User, Notification và Investment dùng PostgreSQL 17/Neon Project
  riêng; PostgreSQL Docker chỉ là offline/test fallback.
- Apache Fineract 1.15.0 là core lending/servicing với PostgreSQL riêng; Loan/Payment chỉ tích hợp
  qua REST/reliable event, không đọc DB Fineract.
- Web/mobile chỉ hiển thị projection từ backend, không tự tính tiền, lãi, phí, DPD hoặc state machine.

## Phạm vi thư mục gốc

Các thư mục gốc mới như `apps/`, `contracts/`, `chaincode/`, `infra/`, `docs/` chỉ được tạo khi task
yêu cầu hoặc người dùng chấp thuận. Khi tạo, MUST cập nhật file này, `03-architecture-structure.md`,
ownership và registry nếu liên quan.

`docs/ui/` lưu visual reference dùng chung cho `finora-web` và `finora-mobile`. Nội dung tại đây chỉ
định hướng giao diện; contract backend, DTO/event hiện hành và Service Design là nguồn sự thật nghiệp vụ.

Gói model AI là hợp đồng deploy. `model_v<n>.json` MUST chứa thứ tự feature, median điền thiếu,
siêu tham số, công thức dẫn xuất và metric. MUST NOT hard-code giá trị điền thiếu trong schema hoặc
predictor vì gây train/serve skew.
