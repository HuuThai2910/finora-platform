# Bối cảnh và trạng thái dự án

## Tổng quan

FINORA là khóa luận IUH 2026 về nền tảng P2P Lending. Repository hiện là backend monorepo:

- Spring Boot 3.2, Java 21: `finora-gateway`, `finora-loan`, `finora-payment`, `finora-blockchain`, `finora-investment`, `finora-user`, `finora-notification`.
- Thư viện Java: `finora-common`.
- FastAPI: `finora-ai` cho credit scoring, eKYC và fraud detection.
- Hạ tầng định hướng: PostgreSQL cho mọi service có persistence quan hệ và Keycloak/Fineract; Redis, Kafka và Hyperledger Fabric là hạ tầng hỗ trợ theo đúng boundary.

## CURRENT STATE

- Code chủ yếu là skeleton; nhiều endpoint nghiệp vụ, entity, repository, migration và test chưa tồn tại.
- Chưa có mobile app, admin web, Fineract integration, chaincode hoặc Fabric network trong repository.
- Mọi JPA service hiện dùng `ddl-auto: validate`; User/Investment còn là skeleton nên chưa có migration nghiệp vụ cho tới entity đầu tiên.
- Một số tài liệu/prototype mô tả NestJS, Fineract hoặc tính năng “đã chạy”; không được giả định chúng tồn tại trong code.

## TARGET STATE

- Database-per-service, giao tiếp qua REST/Kafka, không truy cập DB chéo.
- Flyway là cách duy nhất thay đổi schema.
- Event publication dùng outbox; consumer idempotent.
- Giải ngân dùng Saga orchestration do `finora-loan` điều phối.
- Fabric lưu bằng chứng/hash/audit; database của service vẫn là nguồn state nghiệp vụ.
- Loan, Payment, Blockchain, User và Investment dùng PostgreSQL 17, ưu tiên Neon Project riêng từng service; PostgreSQL Docker chỉ là offline/test fallback.
- Apache Fineract 1.15.0 là core lending/servicing dự kiến với PostgreSQL/Neon Project riêng và hai database `fineract_tenants`, `fineract_default`; Loan/Payment chỉ tích hợp qua REST/reliable event và không đọc DB Fineract.
- Thái và Hải đã thống nhất ngày 2026-08-08 chuyển User, Investment và Keycloak sang PostgreSQL; không còn MySQL/MongoDB trong runtime active của FINORA.

## Phạm vi thư mục gốc

Các thư mục gốc mới như `apps/`, `contracts/`, `chaincode/`, `infra/`, `docs/` chỉ được tạo khi task yêu cầu hoặc người dùng chấp thuận. Khi tạo, MUST cập nhật file này, `03-architecture-structure.md`, ownership và registry nếu liên quan.

`docs/ui/` đã được Thái chấp thuận ngày 2026-08-09 để lưu một bản visual reference dùng chung cho `finora-web` và `finora-mobile`. Nội dung tại đây chỉ định hướng giao diện; contract backend và Service Design vẫn là nguồn sự thật nghiệp vụ.

Gói model AI là hợp đồng deploy. `model_v<n>.json` MUST chứa thứ tự feature, median điền thiếu, siêu tham số, công thức dẫn xuất và metric. MUST NOT hard-code giá trị điền thiếu trong schema hoặc predictor vì gây train/serve skew.
