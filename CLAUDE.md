# CLAUDE.md — Quy ước dự án FINORA (P2P Lending)

> File này là LUẬT cho Claude Code khi làm việc trong repo. Codex cũng làm việc trong repo này với luật giống hệt (xem `AGENTS.md` — **sửa file này thì phải cập nhật AGENTS.md tương ứng trong cùng PR**). Hai dev mỗi người dùng một AI agent — các quy tắc dưới đây tồn tại để **hai bên không bao giờ giẫm chân nhau**.

## 1. Tổng quan hệ thống

**FINORA** — nền tảng cho vay ngang hàng (P2P Lending), Khóa luận Tốt nghiệp IUH 2026. Repo này hiện là **backend-only** (chưa có mobile app / admin web). Gồm: **7 microservice Spring Boot 3.2 (Java 21)** (`finora-gateway`, `finora-loan`, `finora-payment`, `finora-blockchain`, `finora-investment`, `finora-user`, `finora-notification`) + 1 module dùng chung `finora-common`, AI service Python **FastAPI** (`finora-ai` — credit scoring, eKYC, fraud detection), Hyperledger Fabric (channel `finora-channel`, chaincode `finora-ledger`, gọi qua SDK `fabric-gateway` trong `finora-blockchain`), Keycloak (OIDC), Kafka (event bus), MySQL 8 + MongoDB 7, Redis. Kiến trúc & port tổng quan: xem `README.md`. Chưa có tích hợp Apache Fineract trong code hiện tại — đừng giả định service nào gọi Fineract.

## 2. Cấu trúc monorepo (KHÔNG tự ý tạo thư mục gốc mới)

```
finora-platform/
├─ pom.xml                        # parent Maven (groupId com.finora, artifactId finora-platform)
├─ finora-common/                 # Java: DTO/enum/exception dùng chung — KHÔNG phải service chạy độc lập
├─ finora-gateway/                # API Gateway (Spring Cloud Gateway)
├─ finora-user/                   # Identity & eKYC
├─ finora-payment/                # Ví & giao dịch
├─ finora-loan/                   # Vòng đời khoản vay
├─ finora-investment/             # Sàn khớp lệnh P2P / đầu tư
├─ finora-blockchain/             # Gateway gọi Hyperledger Fabric (KHÔNG phải chaincode)
├─ finora-notification/           # Thông báo (Kafka consumer + email)
├─ finora-ai/                     # Python FastAPI: credit scoring, eKYC, fraud detection
└─ docker/                        # docker-compose.yml, mysql/init.sql
```

Cấu trúc bên trong `finora-ai` (Python, ngoài Maven):

```
finora-ai/
├─ main.py                        # FastAPI app, mount router
├─ app/
│  ├─ api/                        # 1 router / 1 bounded context
│  │  ├─ credit_router.py         # /api/v1/ai/credit
│  │  ├─ ekyc_router.py           # /api/v1/ai/ekyc
│  │  └─ fraud_router.py          # /api/v1/ai/fraud
│  ├─ schemas/                    # Pydantic request/response
│  ├─ ml/                         # pipeline ML: preprocessing, features, training,
│  │                              # evaluation, model_registry, predictor
│  └─ services/                   # nghiệp vụ: cic.py (cổng chặn), rule_engine.py (5C)
├─ scripts/train_final_model.py   # huấn luyện mô hình cuối → models/
├─ models/                        # gói model: model_v<n>.pkl + .json (CÓ commit)
├─ data/                          # dữ liệu huấn luyện — .gitignore, KHÔNG commit
└─ tests/
```

**Gói model là hợp đồng, không phải file phụ.** `model_v<n>.json` chứa đủ thông số để
chấm một hồ sơ mới: `feature_names` (đúng thứ tự cột), `median_dien_thieu` (16 cột
gốc), `sieu_tham_so`, `cong_thuc_dan_xuat`, `chi_so` từng fold. Trường thiếu trong hồ
sơ phải điền bằng `median_dien_thieu` của gói — **CẤM viết hằng số mặc định trong
schema Pydantic hay trong code chấm điểm**, vì mô hình học "giá trị thiếu ≈ median",
điền số khác là gây train/serve skew (lỗi không crash, chỉ trả về số sai).

Mỗi module `finora-*` (Java) là **1 Maven module đặt trực tiếp ở gốc repo** — KHÔNG có thư mục `services/` bao ngoài. Repo hiện **CHƯA có** `apps/mobile-app`, `apps/admin-web`, `libs/`, `contracts/`, `chaincode/`, `infra/`, `docs/` — nếu task cần tạo các thư mục này, phải cập nhật lại mục 2 này (và AGENTS.md) trong cùng PR trước khi thêm code.

Cấu trúc bên trong mỗi service Java (package-by-feature — áp dụng cho code mới; nhiều service hiện tại vẫn còn tối giản hơn (chỉ có `controller/`, `service/`), sẽ hoàn thiện dần theo chuẩn này):

```
finora-loan/src/main/java/com/finora/loan/
├─ FinoraLoanApplication.java
├─ config/                       # Bean config, security, Kafka config
├─ application/<feature>/        # 1 thư mục / 1 nghiệp vụ, ví dụ application/apply/
│  ├─ <Feature>Controller.java
│  ├─ <Feature>Service.java      # interface (chỉ tạo khi có ≥2 impl, nếu không dùng class luôn)
│  ├─ dto/                       # <Ten>Request.java, <Ten>Response.java
│  └─ event/                     # <Ten>Event.java + publisher/consumer
├─ domain/                       # entity JPA / document Mongo + enum + domain logic
├─ repository/                   # Spring Data interfaces
└─ integration/                  # Feign client gọi service khác / AI service
src/main/resources/db/migration/ # Flyway V<n>__<mo_ta>.sql — cách DUY NHẤT đổi schema
```

Package gốc: `com.finora.<service>` (ví dụ `com.finora.loan`, `com.finora.user`) — KHÔNG dùng `vn.vento.*`. Lưu ý: hiện tất cả service đang dùng `spring.jpa.hibernate.ddl-auto: update` (nợ kỹ thuật, xem mục 8) — KHÔNG copy pattern này khi thêm entity mới, ưu tiên chuyển sang Flyway nếu task liên quan đến schema.

## 3. CHỐNG CHỒNG CHÉO — luật sắt cho AI agent

1. **Mỗi service có đúng một owner** (bảng dưới, theo `README.md`). Agent chỉ được sửa code trong service mà task hiện tại thuộc về — xác định bằng **tên module thật** (`finora-loan`, `finora-payment`, `finora-ai`, ...), không dùng prefix ID viết tắt kiểu cũ.

| Owner | Services |
|---|---|
| Dev A (Thái) | finora-loan, finora-payment, finora-blockchain |
| Dev B (Hải) | finora-ai, finora-investment, finora-user, finora-notification |

`finora-gateway` chưa được phân công riêng cho ai trong `README.md` → coi là **vùng chung nhẹ**: mỗi người chỉ thêm/sửa route trong `application.yml` trỏ tới service của chính mình, không sửa route của service người kia; đổi cấu trúc/toàn bộ file gateway cần cả hai đồng ý.

2. **Không bao giờ sửa file trong service của người kia.** Nếu task cần thay đổi bên đó (thêm endpoint, thêm field event): DỪNG code, ghi chú TODO rõ ràng trong PR để owner bên kia implement (repo hiện chưa có thư mục `contracts/` đặc tả OpenAPI/event — nếu cần, tạo mới và coi là vùng chung).
3. **`finora-common`** (và `contracts/` nếu được tạo sau này) **là vùng chung**: chỉ được sửa khi thật cần, PR đổi vùng chung phải được CẢ HAI dev approve.
4. **Không đọc/ghi database của service khác.** Mỗi service sở hữu database riêng: MySQL `finora_loan`, `finora_payment`, `finora_user`; Mongo `finora_investment` (và các service khác khi thêm DB sau này theo mẫu `finora_<service>`). Cần dữ liệu bên kia → gọi REST hoặc consume Kafka event.
5. **Không đổi migration đã merge.** Khi service đã chuyển sang Flyway, chỉ thêm file `V<n+1>__...`, không sửa file cũ.
6. **Không tự thêm dependency nặng** (thư viện mới vào parent pom, framework mới) mà không ghi rõ lý do trong PR description.
7. **Không tự đổi port, topic Kafka, tên client Keycloak** — phải cập nhật registry (mục 7) trong cùng PR.
8. **Trước khi tạo file mới, tìm file tương tự đã tồn tại** (`Grep`/glob theo feature) — cấm tạo bản sao chức năng trùng (ví dụ 2 class util format tiền).
9. **Ownership màn admin-web:** repo hiện **CHƯA có** `apps/admin-web` hay `apps/mobile-app` (chỉ có backend). Mục này chỉ áp dụng khi các thư mục đó được tạo — lúc đó phải bổ sung lại bảng phân công theo màn và cập nhật mục 2.

## 4. Naming convention (bắt buộc)

### Java (services)
- Package: `com.finora.<service>.<layer>` — chữ thường, không gạch.
- Class/Interface/Enum: `PascalCase`. Hậu tố cố định: `*Controller`, `*Service`, `*Repository`, `*Request`, `*Response`, `*Event`, `*Config`, `*Exception`, `*Mapper` (MapStruct).
- Method/biến: `camelCase`; hằng số: `UPPER_SNAKE_CASE`; boolean bắt đầu `is/has/can`.
- Entity JPA: tên class số ít (`LoanApplication`), KHÔNG hậu tố Entity.
- Test: `<ClassName>Test` (unit), `<ClassName>IT` (integration/Testcontainers).
- Lombok cho phép: `@Getter @Builder @RequiredArgsConstructor @Slf4j`. CẤM `@Data` trên entity JPA.

### MySQL (Flyway)
- Bảng: `snake_case` số nhiều (`loan_applications`); cột `snake_case`; PK `id BIGINT AUTO_INCREMENT`; FK `<bang_so_it>_id`; luôn có `created_at`, `updated_at`; tiền tệ dùng `DECIMAL(18,2)` — CẤM float; enum lưu `VARCHAR` giá trị UPPER_SNAKE.
- Index đặt tên `idx_<bang>_<cot>`, unique `uq_<bang>_<cot>`.

### MongoDB
- Collection: `snake_case` số nhiều (`scoring_results`, `kyc_documents`); field `snake_case`; mỗi document có `schema_version` (int).

### Kafka
- Topic: `finora.<service>.<su-kien-qua-khu>` kebab-case, ví dụ `finora.loan.application-submitted`, `finora.investment.order-matched`.
- Payload: JSON có `eventId` (UUID), `occurredAt` (ISO-8601), `version`, `data`. Consumer group: `<service>-<muc-dich>` (đã dùng thực tế: `payment-group`, `blockchain-group`, `notification-group`).

### REST API
- Đường dẫn: `/api/v1/<tai-nguyen-so-nhieu>` kebab-case (`/api/v1/loan-applications/{id}/approve`).
- Response lỗi thống nhất (định nghĩa trong `finora-common`): `{ "code": "LOAN_LIMIT_EXCEEDED", "message": "...", "details": [], "traceId": "..." }` — code dạng UPPER_SNAKE.
- Phân trang: query `page` (từ 0), `size`, `sort=field,desc`; response bọc `{ data, page, size, totalElements }`.

### Python (finora-ai)
- FastAPI + Pydantic (KHÔNG phải Django). PEP 8: module/hàm/biến `snake_case`, class `PascalCase`.
- Tổ chức theo bounded context trong `finora-ai/app/api/`: `credit_router.py` (credit scoring), `ekyc_router.py` (eKYC & face match), `fraud_router.py` (fraud detection) — mỗi context 1 router riêng, mount ở `main.py` với prefix `/api/v1/ai/<context>`.
- Format bằng `ruff format`; type hints bắt buộc ở public function.

### TypeScript (mobile-app, admin-web)
- Repo hiện **CHƯA có** `apps/mobile-app` hay `apps/admin-web`. Mục này là quy ước áp dụng khi các thư mục đó được tạo trong tương lai:
- File component `PascalCase.tsx`, hook `useTenHook.ts`, còn lại `kebab-case.ts`; component + type `PascalCase`, biến/hàm `camelCase`, hằng `UPPER_SNAKE_CASE`.
- Gọi API duy nhất qua lớp `src/api/` (client sinh từ OpenAPI khi có thể) — CẤM fetch trực tiếp trong component.
- State server dùng TanStack Query; state cục bộ dùng hook; CẤM Redux.

### Tiền tệ & thời gian (bắt buộc cho mọi service)
- Tiền: `BigDecimal` scale 2, `RoundingMode.HALF_UP`; đơn vị VND; hiển thị không có phần thập phân (`1.500.000 đ`). API/event truyền số dạng chuỗi hoặc số nguyên đồng — KHÔNG dùng float/double ở bất kỳ tầng nào.
- Thời gian: DB lưu **UTC** (`DATETIME`); API/event dùng ISO-8601 có offset; hiển thị cho người dùng theo `Asia/Ho_Chi_Minh`. Java dùng `Instant`/`OffsetDateTime` — cấm `java.util.Date`.

### Logging (fintech — có eKYC nên đặc biệt nghiêm ngặt)
- Log JSON có `traceId` (Micrometer) trên mọi service; log nghiệp vụ mức INFO, lỗi mức ERROR kèm context đủ để tái hiện.
- **CẤM log PII/secret dưới mọi hình thức**: số CCCD đầy đủ, ảnh giấy tờ, OTP, mật khẩu, token, số tài khoản ngân hàng đầy đủ. Khi cần đối chiếu: mask (`038094******`, `***4589`).
- Payload event Kafka chứa PII phải tối giản (chỉ id tham chiếu) — dữ liệu nhạy cảm lấy qua REST khi cần.

### Git
- Branch: `feat/<task-id>-<slug>` / `fix/<task-id>-<slug>` (vd `feat/LN-03-tao-ho-so-vay`).
- Commit: Conventional Commits — `feat(loan): tạo hồ sơ vay + validate B4`. Scope = tên service ngắn (`loan`, `payment`, `blockchain`, `investment`, `user`, `notification`, `ai`, `gateway`, `common`, `infra`).
- PR nhỏ (< ~500 dòng diff), 1 task = 1 PR, người còn lại review. CẤM push thẳng `main`.

## 5. Design pattern bắt buộc

- **Layered per feature**: Controller (validate + map DTO) → Service (nghiệp vụ, transaction) → Repository (Spring Data). Controller KHÔNG chứa nghiệp vụ, Repository KHÔNG bị gọi từ Controller.
- **DTO tại biên**: entity không bao giờ ra khỏi service layer; map bằng MapStruct.
- **Outbox pattern** cho mọi event publish (ghi DB cùng transaction, relay sang Kafka) — dùng module trong `finora-common`.
- **Idempotent consumer**: mọi Kafka consumer kiểm tra `eventId` đã xử lý (bảng/collection `processed_events`).
- **Saga orchestration** (không choreography) cho giải ngân: orchestrator nằm trong `finora-loan`, mỗi bước có compensating action tương ứng.
- **Strategy pattern** cho rule engine (mỗi khối chấm điểm là 1 strategy trong `finora-ai`) và notification channel (FCM/email/SMS là các `NotificationChannel` implementation trong `finora-notification`).
- **Circuit breaker + retry (Resilience4j)** cho mọi call sang AI service (`finora-ai`), Hyperledger Fabric (`finora-blockchain`), hoặc service khác qua Feign.
- Tiền bạc: mọi thao tác đổi số dư phải nằm trong transaction + có bút toán (`wallet_transactions` trong `finora-payment`) — không bao giờ update số dư "chay".

## 6. Quy trình khi Claude Code nhận task

1. Xác định service theo tên module thật (`finora-<ten>`) → nếu service không thuộc owner của dev đang làm, TỪ CHỐI và đề nghị đổi task hoặc chờ owner kia implement.
2. Nếu task cần đổi hợp đồng API/event dùng chung, ghi rõ trong PR description (repo chưa có `contracts/` riêng).
3. Viết test cùng PR (unit cho service layer, `*IT` cho endpoint chính).
4. Chạy `mvn -pl finora-<service> verify` (module đặt trực tiếp ở gốc, không có `services/`) — hoặc `ruff`/`pytest` cho `finora-ai` — trước khi báo hoàn thành.
5. Không refactor ngoài phạm vi task; thấy nợ kỹ thuật (vd `ddl-auto: update`, thiếu Flyway) thì ghi vào PR description mục "Đề xuất", không tự sửa nếu ngoài phạm vi task.

## 7. Registry cố định (cập nhật tại đây khi thay đổi — cùng PR, và sửa cả AGENTS.md)

| Thành phần | Giá trị |
|---|---|
| finora-common | thư viện dùng chung — KHÔNG phải service chạy độc lập, không có port |
| finora-gateway | :8080 |
| finora-loan | :8081 · MySQL `finora_loan` |
| finora-payment | :8082 · MySQL `finora_payment` · dùng Redis (`localhost:6379`) |
| finora-blockchain | :8083 · gọi Hyperledger Fabric qua SDK `fabric-gateway` (channel `finora-channel`, chaincode `finora-ledger`) — không có DB riêng hiện tại |
| finora-investment | :8084 · MongoDB `finora_investment` |
| finora-user | :8085 · MySQL `finora_user` |
| finora-notification | :8086 · Kafka consumer + SMTP (Gmail) — chưa cấu hình DB riêng hiện tại |
| finora-ai | :8000 · Python FastAPI — chưa cấu hình DB riêng hiện tại |
| Keycloak :8180 · Kafka :9092 (+ Zookeeper :2181) · MySQL :3306 · MongoDB :27017 · Redis :6379 | (theo `docker/docker-compose.yml` — chưa có Fineract/MinIO/MailHog trong repo) |

## 8. Điều CẤM tuyệt đối

- Commit secret/token/password (kể cả file .env thật) — chỉ `.env.example`.
- Tự sync schema ngoài Flyway. **Lưu ý:** toàn bộ service hiện đang dùng `ddl-auto: update` (chưa có Flyway) — đây là nợ kỹ thuật đã biết, không phải lý do để thêm entity mới cũng dùng `ddl-auto`; nếu task đụng tới schema, ưu tiên đề xuất chuyển service đó sang Flyway.
- Float/double cho tiền. `System.out.println` thay cho logger. Nuốt exception (`catch` rỗng).
- Sửa code service của owner khác, sửa migration cũ, đổi hợp đồng API/event dùng chung mà không ghi rõ trong PR.
- Merge PR chưa có approve của dev còn lại.
- **Agent tự ý `git commit` hoặc `git push`.** Mọi commit/push chỉ được thực hiện khi người dùng yêu cầu rõ ràng trong lượt trao đổi hiện tại; không tự động commit/push sau khi hoàn thành task dù task đã xong hay tests đã pass.
