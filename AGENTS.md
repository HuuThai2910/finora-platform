# AGENTS.md — Quy ước dự án FINORA cho Codex

> File này là LUẬT cho Codex khi làm việc trong repo. Claude Code cũng làm việc trong repo này với luật giống hệt (xem `CLAUDE.md` — nội dung 2 file phải luôn đồng bộ; nếu sửa một file, sửa cả file kia trong cùng PR). Hai dev mỗi người dùng một AI agent — quy tắc tồn tại để **hai bên không bao giờ chồng chéo code nhau**.

## 1. Tổng quan hệ thống

**FINORA** — nền tảng cho vay ngang hàng (P2P Lending), Khóa luận Tốt nghiệp IUH 2026. Repo này hiện là **backend-only** (chưa có mobile app / admin web). Gồm: **7 microservice Spring Boot 3.2 (Java 21)** (`finora-gateway`, `finora-loan`, `finora-payment`, `finora-blockchain`, `finora-investment`, `finora-user`, `finora-notification`) + module dùng chung `finora-common`, AI service Python **FastAPI** (`finora-ai` — credit scoring, eKYC, fraud detection), Hyperledger Fabric (channel `finora-channel`, chaincode `finora-ledger`, gọi qua SDK `fabric-gateway` trong `finora-blockchain`), Keycloak (OIDC), Kafka (event bus), MySQL 8 + MongoDB 7, Redis. Kiến trúc & port tổng quan: `README.md`. Chưa có tích hợp Apache Fineract trong code hiện tại.

## 2. Cấu trúc monorepo (KHÔNG tự ý tạo thư mục gốc mới)

```
finora-platform/
├─ pom.xml                        # parent Maven (groupId com.finora, artifactId finora-platform)
├─ finora-common/                 # Java: DTO/enum/exception dùng chung — không phải service chạy độc lập
├─ finora-gateway/  ├─ finora-user/  ├─ finora-payment/  ├─ finora-loan/
├─ finora-investment/  ├─ finora-blockchain/  └─ finora-notification/
├─ finora-ai/                     # Python FastAPI: credit scoring, eKYC, fraud detection (ngoài Maven)
└─ docker/                        # docker-compose.yml, mysql/init.sql
```

Mỗi module `finora-*` (Java) là 1 Maven module đặt **trực tiếp ở gốc repo** — KHÔNG có `services/` bao ngoài. Repo hiện **CHƯA có** `apps/mobile-app`, `apps/admin-web`, `libs/`, `contracts/`, `chaincode/`, `infra/`, `docs/` — nếu task cần tạo, phải cập nhật lại mục 2 này (và CLAUDE.md) cùng PR trước khi thêm code.

Cấu trúc bên trong `finora-ai` (Python, ngoài Maven):

```
finora-ai/
├─ main.py                        # FastAPI app, mount router
├─ app/api/                       # 1 router / 1 bounded context (credit, ekyc, fraud)
├─ app/schemas/                   # Pydantic request/response
├─ app/ml/                        # preprocessing, features, training, evaluation,
│                                 # model_registry, predictor
├─ app/services/                  # cic.py (cổng chặn), rule_engine.py (5C)
├─ scripts/train_final_model.py   # huấn luyện mô hình cuối → models/
├─ models/                        # gói model: model_v<n>.pkl + .json (CÓ commit)
├─ data/                          # dữ liệu huấn luyện — .gitignore, KHÔNG commit
└─ tests/
```

**Gói model là hợp đồng, không phải file phụ.** `model_v<n>.json` chứa đủ thông số để chấm một hồ sơ mới: `feature_names` (đúng thứ tự cột), `median_dien_thieu` (16 cột gốc), `sieu_tham_so`, `cong_thuc_dan_xuat`, `chi_so` từng fold. Trường thiếu trong hồ sơ phải điền bằng `median_dien_thieu` của gói — **CẤM viết hằng số mặc định trong schema Pydantic hay trong code chấm điểm**, vì mô hình học "giá trị thiếu ≈ median", điền số khác là gây train/serve skew (lỗi không crash, chỉ trả về số sai).

Bên trong mỗi service Java, tổ chức **package-by-feature** (nhiều service hiện tại vẫn tối giản hơn — chỉ có `controller/`, `service/` — sẽ hoàn thiện dần):

```
finora-<service>/src/main/java/com/finora/<service>/
├─ config/            ├─ application/<feature>/ (Controller, Service, dto/, event/)
├─ domain/            ├─ repository/            └─ integration/
src/main/resources/db/migration/   # Flyway V<n>__<mo_ta>.sql — cách DUY NHẤT đổi schema
```

Package gốc: `com.finora.<service>` — KHÔNG dùng `vn.vento.*`. Lưu ý: hiện tất cả service đang dùng `ddl-auto: update` (nợ kỹ thuật, xem mục 8), không copy pattern này khi thêm entity mới.

## 3. CHỐNG CHỒNG CHÉO — luật sắt cho AI agent

1. **Mỗi service có đúng một owner** (theo `README.md`). Codex chỉ được sửa code trong service mà task hiện tại thuộc về — xác định bằng **tên module thật** (`finora-loan`, `finora-payment`, `finora-ai`, ...), không dùng prefix ID viết tắt kiểu cũ.

| Owner | Services |
|---|---|
| Dev A (Thái) | finora-loan, finora-payment, finora-blockchain |
| Dev B (Hải) | finora-ai, finora-investment, finora-user, finora-notification |

`finora-gateway` chưa được phân công riêng trong `README.md` → vùng chung nhẹ: mỗi người chỉ thêm/sửa route trong `application.yml` trỏ tới service của mình; đổi cấu trúc toàn bộ file cần cả hai đồng ý.

2. **Không bao giờ sửa file trong service của người kia.** Cần bên kia thay đổi (endpoint mới, field event mới) → DỪNG code, ghi TODO rõ trong PR để owner bên kia tự implement (repo hiện chưa có `contracts/` — nếu cần, tạo mới và coi là vùng chung).
3. **`finora-common`** (và `contracts/` nếu tạo sau này) **là vùng chung**: PR đụng vùng chung phải được CẢ HAI dev approve.
4. **Không đọc/ghi database của service khác.** MySQL: `finora_loan`, `finora_payment`, `finora_user`; Mongo: `finora_investment` (service khác thêm DB sau theo mẫu `finora_<service>`). Cần dữ liệu → REST hoặc Kafka event.
5. **Không sửa migration đã merge** — khi service đã chuyển sang Flyway, chỉ thêm `V<n+1>__...`.
6. **Không tự thêm dependency nặng** vào parent pom mà không nêu lý do trong PR.
7. **Không tự đổi port / topic Kafka / client Keycloak** — phải cập nhật registry (mục 7) trong cùng PR.
8. **Trước khi tạo file mới, tìm file tương tự đã tồn tại** — cấm tạo util/chức năng trùng lặp.
9. **Ownership màn admin-web:** repo hiện **CHƯA có** `apps/admin-web` hay `apps/mobile-app` (chỉ backend). Mục này chỉ áp dụng khi các thư mục đó được tạo — lúc đó phải bổ sung lại bảng phân công theo màn và cập nhật mục 2.

## 4. Naming convention (bắt buộc)

**Java**: package `com.finora.<service>.<layer>`; class `PascalCase` với hậu tố cố định `*Controller/*Service/*Repository/*Request/*Response/*Event/*Config/*Exception/*Mapper`; method/biến `camelCase`; hằng `UPPER_SNAKE_CASE`; boolean `is/has/can`; entity số ít không hậu tố (`LoanApplication`); test `*Test` (unit) / `*IT` (Testcontainers). Lombok cho phép `@Getter @Builder @RequiredArgsConstructor @Slf4j`; CẤM `@Data` trên entity JPA.

**MySQL**: bảng `snake_case` số nhiều; PK `id BIGINT AUTO_INCREMENT`; FK `<bang_so_it>_id`; luôn có `created_at/updated_at`; tiền `DECIMAL(18,2)` — CẤM float; enum lưu VARCHAR UPPER_SNAKE; index `idx_<bang>_<cot>`, unique `uq_<bang>_<cot>`.

**MongoDB**: collection `snake_case` số nhiều; field `snake_case`; mọi document có `schema_version`.

**Kafka**: topic `finora.<service>.<su-kien-qua-khu>` (vd `finora.loan.application-submitted`); payload JSON `{eventId, occurredAt, version, data}`; consumer group `<service>-<muc-dich>` (đã dùng: `payment-group`, `blockchain-group`, `notification-group`).

**REST**: `/api/v1/<tai-nguyen-so-nhieu>` kebab-case; lỗi thống nhất `{code, message, details, traceId}` (code UPPER_SNAKE, định nghĩa trong `finora-common`); phân trang `page/size/sort` → response `{data, page, size, totalElements}`.

**Python (finora-ai)**: FastAPI + Pydantic (KHÔNG phải Django). PEP 8, `snake_case`, class `PascalCase`; tổ chức theo bounded context trong `app/api/` (`credit_router.py`, `ekyc_router.py`, `fraud_router.py`); format `ruff format`; type hints ở public function.

**TypeScript (mobile/admin)**: repo hiện CHƯA có `apps/mobile-app`/`apps/admin-web` — quy ước sau áp dụng khi tạo: component `PascalCase.tsx`, hook `useX.ts`, còn lại `kebab-case.ts`; gọi API duy nhất qua lớp `src/api/` (sinh từ OpenAPI khi có thể) — CẤM fetch trong component; server state dùng TanStack Query; CẤM Redux.

**Tiền tệ & thời gian**: tiền dùng `BigDecimal` scale 2 + `RoundingMode.HALF_UP`, đơn vị VND, không float/double ở bất kỳ tầng nào; DB lưu UTC, API/event ISO-8601, hiển thị theo `Asia/Ho_Chi_Minh`; Java dùng `Instant`/`OffsetDateTime` — cấm `java.util.Date`.

**Logging**: JSON + `traceId` mọi service. **CẤM log PII/secret**: số CCCD đầy đủ, ảnh giấy tờ, OTP, mật khẩu, token, số tài khoản đầy đủ — bắt buộc mask (`038094******`, `***4589`). Event Kafka chứa PII tối giản, chỉ id tham chiếu.

**Git**: branch `feat/<task-id>-<slug>` (vd `feat/IV-03-matching-engine`); Conventional Commits `feat(investment): khớp lệnh một phần`; scope = tên service ngắn (`loan`, `payment`, `blockchain`, `investment`, `user`, `notification`, `ai`, `gateway`, `common`, `infra`); 1 task = 1 PR nhỏ (<~500 dòng), review chéo bắt buộc; CẤM push thẳng `main`.

## 5. Design pattern bắt buộc

- **Layered per feature**: Controller (validate/map DTO) → Service (nghiệp vụ + transaction) → Repository. Controller không chứa nghiệp vụ; Repository không bị gọi từ Controller.
- **DTO tại biên** (MapStruct); entity không thoát khỏi service layer.
- **Outbox pattern** cho mọi event publish (module trong `finora-common`); **idempotent consumer** (kiểm tra `eventId` trong `processed_events`).
- **Saga orchestration** cho giải ngân — orchestrator trong `finora-loan`, mỗi bước có compensating action.
- **Strategy** cho rule engine (`finora-ai`) và notification channel (`finora-notification`); **Resilience4j** (circuit breaker + retry) cho call sang AI service (`finora-ai`) / Hyperledger Fabric (`finora-blockchain`) / service khác qua Feign.
- Tiền: mọi thay đổi số dư phải trong transaction + kèm bút toán `wallet_transactions` (trong `finora-payment`).

## 6. Quy trình khi Codex nhận task

1. Xác định service theo tên module thật (`finora-<ten>`) → nếu không thuộc owner của dev đang dùng Codex, TỪ CHỐI và đề nghị chờ owner kia hoặc đổi task.
2. Nếu cần đổi hợp đồng API/event dùng chung, ghi rõ TODO trong PR (repo chưa có `contracts/` riêng).
3. Viết test cùng PR; chạy `mvn -pl finora-<service> verify` (module ở gốc, không có `services/`) — hoặc `ruff`/`pytest` cho `finora-ai` — trước khi báo xong.
4. Không refactor ngoài phạm vi task — nợ kỹ thuật (vd `ddl-auto: update`, thiếu Flyway) ghi vào mục "Đề xuất" trong PR, không tự sửa nếu ngoài phạm vi task.

## 7. Registry cố định (đổi giá trị = phải sửa bảng này cùng PR, và sửa cả CLAUDE.md)

finora-common: thư viện dùng chung, không có port · finora-gateway :8080 · finora-loan :8081 (`finora_loan` MySQL) · finora-payment :8082 (`finora_payment` MySQL, dùng Redis) · finora-blockchain :8083 (gọi Fabric qua `fabric-gateway`, channel `finora-channel`, chaincode `finora-ledger` — chưa có DB riêng) · finora-investment :8084 (`finora_investment` MongoDB) · finora-user :8085 (`finora_user` MySQL) · finora-notification :8086 (Kafka consumer + SMTP — chưa có DB riêng) · finora-ai :8000 (Python FastAPI — chưa có DB riêng) · Keycloak :8180 · Kafka :9092 (+ Zookeeper :2181) · MySQL :3306 · MongoDB :27017 · Redis :6379 — theo `docker/docker-compose.yml` (chưa có Fineract/MinIO/MailHog trong repo)

## 8. Điều CẤM tuyệt đối

- Commit secret (chỉ `.env.example`). Tự sync schema ngoài Flyway — **lưu ý:** toàn bộ service hiện đang dùng `ddl-auto: update` (nợ kỹ thuật đã biết), không phải lý do để thêm entity mới cũng dùng `ddl-auto`. Float cho tiền. `System.out.println`. Catch rỗng nuốt exception.
- Sửa code service của owner khác. Sửa migration cũ. Đổi hợp đồng API/event dùng chung mà không ghi rõ trong PR. Merge PR khi chưa có approve của dev còn lại.
- **Agent tự ý `git commit` hoặc `git push`.** Mọi commit/push chỉ được thực hiện khi người dùng yêu cầu rõ ràng trong lượt trao đổi hiện tại; không tự động commit/push sau khi hoàn thành task dù task đã xong hay tests đã pass.
