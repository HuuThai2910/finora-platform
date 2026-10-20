---
task_id: LN-002
title: Tích hợp xác thực JWT và phân quyền cho Loan Service
owner: Thai
status: BACKLOG
roadmap_task: P0-C02
depends_on:
  - LN-001A_ACCEPTED
created_at: 2026-08-01
updated_at: 2026-09-05
approved_by:
approved_at:
accepted_by:
accepted_at:
shared_review_by: Hai
shared_review_status: WAITING_FOR_IDENTITY_OWNER
---

# LN-002 — Tích hợp xác thực JWT và phân quyền cho Loan Service

## Bản đọc nhanh theo nghiệp vụ

### Chức năng này giải quyết việc gì?

LN-002 bảo đảm đúng người mới được thực hiện đúng hành động: admin quản lý Product và duyệt hồ sơ; borrower chỉ tạo/xem hồ sơ, hợp đồng và khoản vay của chính mình. Task còn chuẩn hóa mã lỗi, trace và health để tìm lỗi mà không lộ thông tin nhạy cảm.

Hiện chức năng đăng nhập thật đang tạm hoãn. Loan dùng hai actor giả lập tập trung để Thái phát triển nghiệp vụ; đây không phải cơ chế được phép dùng khi demo tích hợp hoặc triển khai cho người dùng thật.

### Người dùng nhìn thấy gì?

| Tình huống | Kết quả người dùng hiểu được |
|---|---|
| Chưa đăng nhập/token hết hạn | `401`: cần đăng nhập lại |
| Đã đăng nhập nhưng không đủ vai trò | `403`: không có quyền thực hiện |
| Borrower truy cập hồ sơ người khác | Bị từ chối mà không làm lộ dữ liệu hồ sơ |
| Request lỗi | Nhận cùng một cấu trúc lỗi và `traceId` để hỗ trợ tra cứu |
| Hệ thống không kết nối DB | Readiness báo chưa sẵn sàng; liveness vẫn phản ánh tiến trình còn sống |

### Luồng chính và quan hệ service

```text
Client đăng nhập Keycloak → nhận JWT → Gateway chuyển request
→ Loan tự kiểm tra chữ ký/claim/role → kiểm tra ownership → chạy nghiệp vụ
```

Keycloak sở hữu danh tính/token; Loan sở hữu quyền trên Product/Application/Contract của mình. Loan không gọi User Service ở mọi request và không tin role/user ID do client tự điền trong body/header.

### Ví dụ, lỗi và điều kiện hoàn thành

Ví dụ: token borrower gọi endpoint tạo Product phải bị `403`; token admin gọi được; token borrower A đọc Application của B phải bị từ chối. LN chỉ hoàn tất khi Hải chốt contract realm/client/claim, mock được xóa, các test 401/403/ownership pass và log không chứa token/PII.

### Đối chiếu pháp lý

- Áp dụng `LEGAL-DATA-01` trong [Sổ đối chiếu pháp lý FINORA](../../docs/LEGAL-COMPLIANCE.md): authorization, log redaction và audit là kiểm soát truy cập dữ liệu cá nhân theo [Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15](https://vanban.chinhphu.vn/?docid=214590&pageid=27160&typegroupid=3) và [Nghị định 356/2025/NĐ-CP](https://vanban.chinhphu.vn/default.aspx?docid=216387&pageid=27160).
- Token, số CCCD, raw KYC/AI payload và secret không được ghi log. `traceId` dùng để truy vết kỹ thuật, không thay thế identity đã xác thực hoặc audit actor.
- Mock identity chỉ dành cho local; bật production khi còn mock là điều kiện fail deployment, không phải cảnh báo có thể bỏ qua.

## 0. Quyết định tạm hoãn ngày 2026-08-02

Thái quyết định chưa tích hợp Spring Security/JWT/Keycloak vào Loan Service để tránh chặn tiến độ nghiệp vụ trong lúc phần Identity do Hải phụ trách chưa sẵn sàng. Phần code Resource Server, JWT validator, role converter, security test và realm import thử nghiệm đã được gỡ khỏi nhánh làm việc.

Trạng thái hiện tại:

- Loan khởi động và gọi API mà không cần đăng nhập.
- `MockCurrentUserProvider` cung cấp đồng thời hai actor cố định: `ADMIN-001` cho Product và `BORROWER-001` cho Application; không cần đổi profile, biến môi trường hoặc khởi động lại service.
- Không controller/service nào được tự hard-code identity thêm ở nơi khác. Khi cần actor, code nghiệp vụ chỉ lấy qua provider tạm này.
- Chuẩn lỗi REST, trace ID, log JSON và Actuator/readiness đã làm độc lập nên được giữ lại; chúng không tạo yêu cầu đăng nhập.
- Toàn bộ thiết kế JWT/Keycloak bên dưới là **kế hoạch tương lai**, không mô tả chức năng đang chạy.

LN-002 chỉ được mở lại sau khi Hải cung cấp hoặc xác nhận realm/client/role/claim contract. Khi đó phải xóa `MockCurrentUserProvider`, thay bằng identity đã xác thực và chạy lại toàn bộ security test trước khi triển khai.

## 1. Kết quả cần đạt

Khi LN-002 được kích hoạt lại, Loan Service phải đạt:

1. Keycloak local có realm, role và client tối thiểu, tái tạo được từ cấu hình trong Git nhưng không chứa user hoặc secret thật.
2. Loan tự xác minh chữ ký, issuer, audience và thời hạn JWT; không gọi User Service ở mỗi request.
3. Endpoint quản trị yêu cầu role `admin`; endpoint mới mặc định bị chặn nếu chưa khai báo quyền.
4. Mọi lỗi REST, kể cả `401/403`, có cùng dạng `{code, message, details, traceId}`.
5. Mỗi request có `X-Trace-Id`, log không lộ token/PII và môi trường triển khai ghi log JSON.
6. Actuator cung cấp liveness/readiness đúng ý nghĩa; readiness kiểm tra database nhưng liveness không phụ thuộc database/Keycloak.
7. Có test tự động cho claim, role, audience, authorization, error envelope, trace và health.

LN-002 hiện chỉ theo dõi P0-C02. Phần error envelope và HTTP observability đã tách khỏi điều kiện triển khai xác thực.

## 2. Ai làm và ai quyết định

| Phần | Người thực hiện | Người cần xác nhận | Lý do |
|---|---|---|---|
| Code/config/test trong `finora-loan` | Thái | Thái nghiệm thu | Thuộc module Loan |
| Realm, role, client và JWT claims dùng chung | Hải/owner Identity cung cấp contract | Thái xác nhận khả năng dùng từ Loan | User/Gateway và các service khác phải dùng cùng contract |
| Loan Resource Server, role và ownership guard | Thái triển khai khi contract sẵn sàng | Thái nghiệm thu, Hải review contract | Thuộc biên bảo vệ của Loan |
| Error envelope trong `finora-common` | Đã triển khai độc lập | Hải review trước merge | Không còn bị chặn bởi LN-002 |
| Docker Keycloak và tài liệu chạy chung | Hải/owner Identity | Cả hai review | Là vùng hạ tầng dùng chung |
| User profile, đồng bộ Keycloak–User, cấp role tự động | Hải ở task User sau | Không thuộc LN-002 | Keycloak giữ credential/realm role; User giữ profile/KYC |

Quy trình mới từ quyết định ngày 2026-08-02:

```text
Hải cung cấp/xác nhận contract Identity
    → Thái cập nhật lại plan và duyệt mở LN-002
    → Thái triển khai xác thực ở Loan
    → Hải review phần contract dùng chung
    → Thái nghiệm thu LN-002
```

Trong lúc chờ, LN-003/LN-004 được phép dùng identity giả lập. Identity này không phải bằng chứng xác thực và không được dùng làm phương án production.

## 3. Vì sao LN-002 tạm thời không chặn LN-003

LN-003 tạo API Loan Product. Ở giai đoạn hiện tại, Thái ưu tiên kiểm chứng entity, migration, validation, query và nghiệp vụ trước; actor quản trị được lấy từ `MockCurrentUserProvider`.

- API admin không có cách chứng minh người gọi thật sự là admin.
- Mỗi controller có thể tự đọc JWT theo một kiểu, dễ bỏ sót chữ ký/audience/hết hạn.
- Lỗi `401`, `403`, validation và business error có format khác nhau.
- Khi lỗi xảy ra khó nối response với log vì không có trace ID.
- Health endpoint hiện tại chỉ trả chuỗi “service is running”, chưa phân biệt tiến trình sống với khả năng nhận traffic.

Các rủi ro ở trên được chấp nhận có thời hạn để tránh phụ thuộc tiến độ Identity. Trước khi demo tích hợp hoặc triển khai ra môi trường dùng chung, LN-002 vẫn là cổng bắt buộc: API quản trị phải xác thực thật, ownership phải kiểm tra bằng identity thật và mock provider phải bị xóa.

## 4. Hiện trạng đã kiểm tra từ code

- Parent đang dùng Java 21, Spring Boot `3.2.5`, Spring Cloud `2023.0.1`.
- Keycloak hiện chạy image `quay.io/keycloak/keycloak:23.0`, lệnh `start-dev`, chưa import realm từ Git.
- Keycloak có PostgreSQL riêng và không publish cổng database ra host.
- Loan chưa có Spring Security hoặc OAuth2 Resource Server; đã có Actuator cho liveness/readiness.
- `LoanController` chỉ có `GET /api/v1/loans/health` trả `BaseResponse<String>`.
- Loan quét cả `com.finora.loan` và `com.finora.common`, nên `GlobalExceptionHandler` trong Common đang có hiệu lực.
- Response thành công vẫn dùng `BaseResponse`; response lỗi đã có `{code, message, details, traceId}`.
- Loan đã có request filter/MDC, JSON log cho non-local và liveness/readiness; chưa có security test vì security đang hoãn.
- Loan đang dùng một identity giả lập cấu hình tập trung; request từ client chưa được xác thực.
- Loan dùng PostgreSQL 17/Flyway/Testcontainers theo LN-001A và `open-in-view: false`.

## 5. Bức tranh hoạt động tương lai sau khi hoàn tất LN-002

```text
Người dùng đăng nhập qua Keycloak
        ↓
Keycloak phát access token JWT có issuer, subject, audience và realm role
        ↓
Client gửi Authorization: Bearer <token> + X-Trace-Id (nếu có)
        ↓
TraceIdFilter kiểm tra/tạo trace ID và đặt vào MDC
        ↓
Spring Security xác minh chữ ký + iss + aud + exp/nbf
        ↓
JwtRoleConverter chỉ ánh xạ role FINORA được cho phép
        ↓
Security rule kiểm tra endpoint/role
        ↓
Controller → Service → Repository
        ↓
Response hoặc ApiErrorResponse đều mang trace ID khi có lỗi
```

Loan chỉ dùng public key/JWK để kiểm tra token. Loan không nhận password và không truy vấn database Keycloak.

## 6. Thuật ngữ cần hiểu

| Tên | Giải thích ngắn | Dùng để làm gì |
|---|---|---|
| Realm | Không gian xác thực riêng trong Keycloak | Nhóm user, role, client của FINORA |
| Client | Ứng dụng/API được đăng ký với Keycloak | Xác định ai xin token và token dành cho API nào |
| JWT | Access token đã được ký | Service kiểm tra token cục bộ, không gọi Keycloak mỗi request |
| `iss` | Issuer — nơi phát token | Chặn token do realm khác phát |
| `sub` | Subject — định danh identity trong Keycloak | Biết identity nào đang gọi; chưa mặc định là ID bảng User |
| `aud` | Audience — API đích của token | Chặn token hợp lệ nhưng được phát cho hệ thống khác |
| JWK/JWKS | Public key của Keycloak | Loan dùng để xác minh chữ ký JWT |
| Role | Nhóm quyền cấp cao | `admin`, `borrower`, `investor` |
| Resource ownership | Quyền trên đúng dữ liệu của mình | Ví dụ borrower A không được sửa application của borrower B |
| Trace ID | Mã nối một request với log | Tra lỗi mà không cần log payload/token |
| Liveness | Tiến trình còn sống hay bị kẹt | Chỉ nên restart khi chính tiến trình hỏng |
| Readiness | Service hiện có nhận request được không | Loan phải từ chối traffic nếu DB bắt buộc đang hỏng |

## 7. Hợp đồng Keycloak và JWT đề xuất

Phần này là đề xuất đầy đủ để Thái duyệt. Hải chỉ cần xác nhận bảng dưới đây trước khi merge, không phải đọc toàn bộ chi tiết Loan.

### 7.1 Realm, client và role

| Thành phần | Giá trị | Quy tắc |
|---|---|---|
| Realm | `finora` | Bật; chỉ dùng cho FINORA |
| Realm roles | `admin`, `borrower`, `investor` | Tên chữ thường trong token; Loan đổi thành `ROLE_ADMIN`, `ROLE_BORROWER`, `ROLE_INVESTOR` |
| Resource client | `finora-api` | Đại diện các backend API và là audience bắt buộc |
| Local interactive client | `finora-local` | Public client, Authorization Code + PKCE S256; dùng Swagger/frontend local |
| Password grant | Tắt | Không khuyến khích client giữ mật khẩu người dùng |
| Service account cho `finora-local` | Tắt | Đây là client dành cho người dùng đăng nhập, không phải machine account |
| User mẫu | Không commit | Tạo thủ công/local script từ secret ngoài Git khi cần test |
| Client secret | Không commit | Public client không cần secret; client bí mật tương lai đọc từ secret store |

Realm import phải tạo audience mapper để access token dùng cho API chứa `aud = finora-api`.

### 7.2 Claim Loan bắt buộc kiểm tra

| Claim | Kiểu | Điều kiện | Ý nghĩa trong Loan |
|---|---|---|---|
| `iss` | String URL | Khớp chính xác `FINORA_JWT_ISSUER_URI` | Token đúng realm FINORA |
| `sub` | String | Có và không rỗng | `identitySubject`; không tự coi là PK của bảng User |
| `aud` | String hoặc array theo JWT | Phải chứa `finora-api` | Token thật sự dành cho FINORA API |
| `exp` | NumericDate | Chưa hết hạn | Chặn token cũ |
| `nbf` | NumericDate, optional | Nếu có thì token đã tới thời điểm hiệu lực | Chặn token dùng quá sớm |
| `iat` | NumericDate | Có | Hỗ trợ kiểm tra/audit token; không dùng làm business time |
| `realm_access.roles` | Array String | Chỉ lấy role nằm trong allowlist FINORA | Authorization cấp cao |

`jti`, `azp`, email, username có thể có nhưng LN-002 không dùng làm authorization. Loan không tin role do client tự gửi qua header.

### 7.3 Ý nghĩa của `sub`

- `sub` là định danh identity do Keycloak phát.
- User Service sau này lưu `keycloakUserId` để ánh xạ identity sang hồ sơ FINORA.
- LN-002 đặt tên trong code là `identitySubject`, tránh đặt nhầm là `userId` hoặc `borrowerId`.
- LN-002 **không chốt User ID contract cho LN-004**. LN-004 vẫn phải chờ Hải xác nhận Loan lưu internal User ID hay Keycloak subject làm logical borrower reference.

### 7.4 Vì sao Loan tự kiểm tra JWT

- Gateway chỉ là lớp ngoài; request nội bộ hoặc cấu hình routing sai vẫn có thể chạm trực tiếp Loan.
- Chữ ký JWT cho phép Loan xác minh cục bộ bằng public key, giảm một REST call ở mỗi request.
- Không tạo N+1 kiểu “mỗi API Loan lại gọi User Service để hỏi token/role”.
- User Service giữ profile/KYC, không phải authority phát credential hoặc xác minh password.

## 8. Ma trận quyền endpoint

LN-002 cấu hình theo nguyên tắc “chưa khai báo thì từ chối”.

| Endpoint/nhóm endpoint | Quyền sau LN-002 | Ghi chú |
|---|---|---|
| `GET /actuator/health/liveness` | Public | Không lộ chi tiết dependency |
| `GET /actuator/health/readiness` | Public | Chỉ trả trạng thái cần cho probe, không lộ credential |
| `GET /api/v1/loans/health` | Public tạm thời | Giữ tương thích smoke script hiện tại; Actuator là nguồn health chuẩn |
| Swagger UI và `/api-docs/**` | Chỉ bật/public ở profile `local` | Môi trường triển khai mặc định tắt |
| `/actuator/info`, `/actuator/metrics/**` | `admin` | Không public metric vận hành |
| `POST/PUT/PATCH/DELETE /api/v1/admin/loan-products/**` | `admin` | Endpoint thật được tạo ở LN-003 |
| `GET /api/v1/loan-products/**` | Public, nhưng LN-003 chỉ trả product `ACTIVE` | Cho phép xem gói vay trước đăng nhập; dữ liệu không chứa PII |
| `/api/v1/loan-applications/**` | Chưa mở ở LN-002 | LN-004 phải yêu cầu borrower/admin và kiểm tra đúng owner |
| Endpoint khác | `denyAll` | Task tạo endpoint mới phải khai báo quyền rõ |

Role không thay thế ownership. Có role `borrower` nhưng sai chủ hồ sơ vẫn phải nhận `403` ở LN-004.

## 9. Chuẩn response lỗi

### 9.1 Dạng JSON

```json
{
  "code": "ACCESS_DENIED",
  "message": "Bạn không có quyền thực hiện thao tác này",
  "details": null,
  "traceId": "0f7339de-8dc7-4f46-a705-a461d46f3f31"
}
```

Quy tắc:

- `code`: String `UPPER_SNAKE_CASE`, ổn định để frontend xử lý; không dùng HTTP status number làm business code.
- `message`: thông báo dễ hiểu bằng tiếng Việt; không chứa SQL, stack trace hoặc chi tiết xác minh token.
- `details`: `null` hoặc object đã lọc; validation dùng danh sách `{field, reason}` có thứ tự ổn định.
- `traceId`: luôn có nếu request đã đi vào HTTP filter, kể cả `401`, `403`, `400`, `500`.
- HTTP status vẫn đúng chuẩn và không bị thay thế bởi field `code`.

Success response hiện tại tiếp tục dùng `BaseResponse`; LN-002 chỉ chuẩn hóa response lỗi bằng `ApiErrorResponse` để không phá contract success không liên quan.

### 9.2 Mapping tối thiểu

| HTTP | `code` | Trường hợp |
|---:|---|---|
| 400 | `VALIDATION_FAILED` | DTO/field không hợp lệ |
| 400 | `MALFORMED_REQUEST` | JSON sai cú pháp/kiểu |
| 400 | `BUSINESS_RULE_VIOLATION` | BusinessException mặc định hiện tại |
| 401 | `AUTHENTICATION_REQUIRED` | Thiếu token, token sai chữ ký/issuer/audience hoặc hết hạn |
| 403 | `ACCESS_DENIED` | Token hợp lệ nhưng thiếu role/quyền |
| 404 | `RESOURCE_NOT_FOUND` | Resource không tồn tại |
| 409 | `CONFLICT` | Optimistic lock/unique conflict ở task nghiệp vụ sau |
| 503 | `AUTHENTICATION_SERVICE_UNAVAILABLE` | Không lấy được signing key do Keycloak/JWKS tạm thời không sẵn sàng |
| 500 | `INTERNAL_ERROR` | Lỗi không dự kiến; response không lộ exception |

### 9.3 Cách xử lý xung đột với Common hiện tại

`BaseResponse` và `GlobalExceptionHandler` hiện chưa đúng contract mới. Kế hoạch chọn:

1. Thêm `ApiErrorResponse` và `FieldViolation` vào `finora-common`.
2. Mở rộng `BusinessException` có machine code nhưng giữ constructor cũ để không làm hỏng source hiện tại.
3. Cập nhật `GlobalExceptionHandler` trả `ApiErrorResponse` và không log lặp.
4. Security filter dùng custom `AuthenticationEntryPoint`/`AccessDeniedHandler` để `401/403` có đúng JSON, vì hai lỗi này xảy ra trước controller advice.
5. Chạy verify các Java module chịu ảnh hưởng, không chỉ Loan.

Đây là thay đổi vùng chung. Thái đã cho phép triển khai trước; Hải review lại contract trong mục 9.1–9.2 ở cổng hậu kiểm trước merge.

## 10. Trace và logging

### 10.1 Quy tắc trace ID

- Header chuẩn: `X-Trace-Id`.
- Nếu client gửi giá trị hợp lệ, Loan giữ lại; hợp lệ khi dài 16–64 ký tự và chỉ gồm chữ, số, `.`, `_`, `-`.
- Nếu thiếu hoặc sai, Loan tạo UUID mới; không đưa giá trị không kiểm soát vào log để tránh log injection.
- Filter chạy trước Spring Security để lỗi `401/403` cũng có trace ID.
- Đặt `traceId` vào MDC, thêm vào response header và error body, rồi xóa MDC trong `finally` để thread khác không dùng nhầm.
- Không lấy trace ID từ JWT và không coi trace ID là idempotency key.

### 10.2 Nội dung log

| Mức | Dùng cho | Ví dụ field an toàn |
|---|---|---|
| `INFO` | Request hoàn tất/sự kiện nghiệp vụ quan trọng | traceId, method, route template, status, durationMs |
| `WARN` | Lỗi dự kiến hoặc có thể phục hồi | traceId, errorCode, dependency name |
| `ERROR` | Lỗi không dự kiến cần can thiệp | traceId, exception type; stack trace chỉ ở nơi xử lý cuối |
| `DEBUG` | Chẩn đoán local | Không log token hoặc toàn DTO |

Cấm log Authorization header, access/refresh token, password, OTP, client secret, CCCD, email/phone đầy đủ hoặc toàn bộ request/response body.

### 10.3 Định dạng log

- Profile `local`: console dễ đọc cho developer.
- Profile triển khai/container: JSON một dòng, có timestamp UTC, level, service, traceId, logger và message.
- Dùng Logback encoder tương thích Spring Boot 3.2; version phải được pin và kiểm tra dependency tree vì Spring Boot BOM không quản lý thư viện này.
- Không thêm Elasticsearch/Logstash server trong LN-002; JSON chỉ là format để nền tảng thu log sau này đọc được.

LN-002 hoàn thành trace qua HTTP trong Loan. Trace Kafka được thực hiện khi Loan có producer/consumer thật ở P0-A02; không tạo Kafka code giả chỉ để đánh dấu P0-C03 hoàn tất.

## 11. Liveness, readiness và metric

Thêm Spring Boot Actuator với chính sách:

- Liveness chỉ phản ánh tiến trình/JVM/application state; **không** đưa PostgreSQL, Keycloak, Kafka vào liveness để tránh restart loop khi dependency ngoài bị lỗi.
- Readiness gồm `readinessState` và `db`, vì Loan không thể xử lý nghiệp vụ nếu PostgreSQL không dùng được.
- Keycloak không nằm trong readiness: Loan xác minh JWT bằng JWK cache; không nên làm mọi probe phụ thuộc một network call tới Keycloak.
- Kafka chưa là dependency bắt buộc của API hiện tại; bổ sung vào readiness khi có flow event bắt buộc và đã xác định degraded mode.
- Public probe không trả chi tiết credential/URL/database error.
- `info` và `metrics` chỉ cho admin hoặc mạng quản trị; chưa thêm Prometheus/Grafana trong LN-002.

## 12. Cấu hình runtime dự kiến

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${FINORA_JWT_ISSUER_URI:http://localhost:8180/realms/finora}
          jwk-set-uri: ${FINORA_JWT_JWK_SET_URI:http://localhost:8180/realms/finora/protocol/openid-connect/certs}

finora:
  security:
    audience: ${FINORA_JWT_AUDIENCE:finora-api}

management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

Khi Loan chạy trong Docker local:

- `FINORA_JWT_ISSUER_URI=http://localhost:8180/realms/finora` để khớp `iss` của token mà browser nhận qua host.
- `FINORA_JWT_JWK_SET_URI=http://keycloak:8080/realms/finora/protocol/openid-connect/certs` để container Loan lấy public key qua Docker network.
- Tách hai URL giải quyết khác biệt host nhìn thấy (`localhost:8180`) và container nhìn thấy (`keycloak:8080`) nhưng vẫn bắt buộc validate issuer.
- Production dùng một HTTPS issuer ổn định; không sao chép cấu hình HTTP local lên production.

Audience được kiểm tra bằng validator tường minh để chắc chắn hoạt động với Spring Boot 3.2.5, không phụ thuộc một property chỉ có ở version mới hơn.

## 13. Vì sao chọn các công nghệ/cách làm này

| Quyết định | Vấn đề giải quyết | Vì sao phù hợp | Đánh đổi/điểm phải giữ |
|---|---|---|---|
| Keycloak | Login, credential, token, realm role | Dự án đã có; không tự viết hệ thống password/token | Phải quản lý realm config và nâng version bảo mật riêng |
| Authorization Code + PKCE | Login local/frontend | Không đưa password/client secret vào frontend | Cần browser redirect; không tiện như password grant nhưng an toàn hơn |
| Spring OAuth2 Resource Server | Xác minh JWT chuẩn | Dùng decoder/validator/filter chuẩn, hỗ trợ JWK rotation | Phải map role Keycloak và custom error response |
| JWT validation cục bộ | Tránh gọi auth/User mỗi request | Ít latency, không tạo N+1 REST, giảm coupling | Token chỉ cập nhật role khi token mới được phát; dùng access token ngắn hạn |
| Kiểm tra `aud` | Ngăn dùng token sai API | Chữ ký đúng chưa đủ nếu token dành cho hệ thống khác | Keycloak phải cấu hình audience mapper đúng |
| Fail-closed `denyAll` | Endpoint mới không vô tình public | Buộc task sau khai báo quyền | Mỗi endpoint mới phải cập nhật security test |
| `ApiErrorResponse` riêng | Error ổn định cho frontend | Không phá success envelope hiện tại | Là common contract nên Hải phải review |
| MDC + `X-Trace-Id` | Nối response với log | Nhẹ, phù hợp HTTP hiện tại | Phải validate input và cleanup thread-local |
| JSON log khi deploy | Máy/log collector parse được | Phù hợp rule vận hành | Thêm encoder và phải kiểm soát field/PII |
| Actuator | Probe/metric chuẩn | Tích hợp sẵn với Spring Boot | Chỉ expose endpoint cần thiết, bảo vệ metric |

### Vì sao chưa dùng phương án khác

- Không gọi User Service để xác minh token: sai ownership và tăng latency/điểm lỗi.
- Không chỉ tin Gateway: resource service vẫn phải tự bảo vệ dữ liệu.
- Không introspect token qua Keycloak mỗi request: không cần với signed JWT và gây phụ thuộc mạng.
- Không tự parse JWT bằng thư viện tiện ích: parse payload không đồng nghĩa đã xác minh chữ ký/issuer/audience.
- Không bật CORS `*` tại Loan: browser production đi qua Gateway; nếu cần gọi trực tiếp ở local thì chỉ mở allowlist origin local bằng profile/cấu hình, không mở kèm credential cho mọi origin.
- Không dùng Redis để lưu session/role/cache JWT trong giai đoạn này: Keycloak và JWK cache của Resource Server đã giải quyết nhu cầu; Redis làm tăng consistency và vận hành mà chưa có bottleneck.
- Không thêm OpenTelemetry/collector/Prometheus trong LN-002: chưa có hạ tầng thu thập chung; trace ID, JSON log, Actuator là baseline đủ để task nghiệp vụ bắt đầu.
- Không nâng Keycloak/Spring Boot trong cùng task: nâng framework là thay đổi chung có rủi ro tương thích, phải có task bảo trì riêng. LN-002 ghi nhận version hiện tại, không coi đó là khuyến nghị production dài hạn.

## 14. Các bước triển khai sau khi plan được duyệt

### Bước 1 — Chốt phần dùng chung

- Ghi nhận baseline: realm `finora`, ba role, audience `finora-api`, nghĩa của `sub`, error envelope và header `X-Trace-Id`.
- Thái triển khai luôn theo baseline đã duyệt; Hải hậu kiểm trước merge.
- Nếu Hải yêu cầu thay đổi sau review, cập nhật chính file LN-002 bằng `Plan amendment`; không tạo plan phụ.

### Bước 2 — Keycloak local tái tạo được

- Thêm realm import JSON không chứa user/secret.
- Mount vào `/opt/keycloak/data/import` và chạy `start-dev --import-realm`.
- Cấu hình role, clients, PKCE, redirect local và audience mapper.
- Cập nhật `.env.example`/README nếu có biến mới.
- Kiểm tra trên Keycloak database sạch. Keycloak bỏ qua import nếu realm đã tồn tại, nên file import không phải cơ chế migration realm đang chạy.

### Bước 3 — Chuẩn lỗi chung

- Thêm DTO lỗi/field violation vào Common.
- Cập nhật GlobalExceptionHandler và BusinessException theo hướng tương thích source.
- Viết test serialization/mapping; verify các module Java bị ảnh hưởng.

### Bước 4 — Loan Resource Server và authorization

- Thêm Security/OAuth2 Resource Server/Actuator/security-test dependencies trong POM Loan.
- Cấu hình JwtDecoder gồm issuer + JWK URL + audience validator.
- Ánh xạ allowlist realm role sang `ROLE_*`.
- Cấu hình stateless session, CSRF tắt cho bearer-token API, endpoint matrix và deny-by-default.
- Thêm custom `AuthenticationEntryPoint`/`AccessDeniedHandler` dùng chung error envelope.

### Bước 5 — Trace, log và health

- Thêm filter `X-Trace-Id` trước security, MDC cleanup và response header.
- Thêm request completion log đã lọc dữ liệu.
- Thêm local/deployed Logback profile và Actuator health groups.
- Giữ health endpoint cũ để smoke script hiện tại chưa bị gãy; ghi rõ Actuator là probe chuẩn.

### Bước 6 — Test, tài liệu và bàn giao

- Chạy unit/slice/integration test và verify module.
- Smoke realm import từ Keycloak database sạch.
- Chạy rule validator.
- Ghi command/exit code/evidence vào mục 20.
- Chuyển `READY_FOR_REVIEW`; Thái tự nghiệm thu rồi mới chuyển `ACCEPTED`.

## 15. File dự kiến thay đổi khi triển khai

### Vùng Loan do Thái sở hữu

- `finora-loan/pom.xml`
- `finora-loan/src/main/resources/application.yml`
- `finora-loan/src/main/resources/application-local.yml`
- `finora-loan/src/main/resources/logback-spring.xml`
- `finora-loan/src/main/java/com/finora/loan/config/SecurityConfig.java`
- `finora-loan/src/main/java/com/finora/loan/config/JwtAudienceValidator.java`
- `finora-loan/src/main/java/com/finora/loan/config/JwtRoleConverter.java`
- `finora-loan/src/main/java/com/finora/loan/config/TraceIdFilter.java`
- `finora-loan/src/main/java/com/finora/loan/config/ApiAuthenticationEntryPoint.java`
- `finora-loan/src/main/java/com/finora/loan/config/ApiAccessDeniedHandler.java`
- Test tương ứng phản chiếu package production trong `finora-loan/src/test/java/...`

Tên file có thể điều chỉnh để tránh class quá lớn, nhưng vẫn giữ cấu trúc `controller/service/domain/repository/dto/config/exception` đã thống nhất; không tạo package rỗng.

### Vùng chung cần Hải review trước merge

- `finora-common/src/main/java/com/finora/common/dto/ApiErrorResponse.java`
- `finora-common/src/main/java/com/finora/common/dto/FieldViolation.java`
- `finora-common/src/main/java/com/finora/common/exception/BusinessException.java`
- `finora-common/src/main/java/com/finora/common/exception/GlobalExceptionHandler.java`
- `docker/keycloak/finora-realm.json`
- `docker/docker-compose.yml`
- `docker/.env.example` nếu phát sinh biến cấu hình
- `docker/README.md`
- `.agents/plans/finora-team-roadmap.md`

Không sửa module User/Gateway/Investment/AI/Notification của Hải trong LN-002.

## 16. Test plan bắt buộc

### 16.1 Unit test

- Audience đúng được nhận; thiếu/sai audience bị từ chối.
- Issuer, token hết hạn và token chưa hiệu lực được Spring validator từ chối.
- Role converter chỉ lấy `admin/borrower/investor`, bỏ role Keycloak nội bộ hoặc role lạ.
- Claim role thiếu/null không làm lỗi và không cấp quyền.
- Trace ID hợp lệ được giữ; thiếu/sai được tạo mới; MDC luôn cleanup.
- Error response serialize đúng bốn field và validation details ổn định.

### 16.2 Security/controller slice test

- Không token vào endpoint bảo vệ → `401`, code `AUTHENTICATION_REQUIRED`, có body/header trace ID.
- Token hợp lệ nhưng sai role → `403`, code `ACCESS_DENIED`, có trace ID.
- Role admin vào admin matcher → được qua security.
- Active product GET matcher là public; endpoint thật/test nghiệp vụ được bổ sung ở LN-003.
- Endpoint chưa khai báo → bị deny.
- Swagger chỉ mở khi profile local.
- Liveness/readiness public; metrics không public.

### 16.3 Integration test

- `@SpringBootTest`/MockMvc khởi động cùng PostgreSQL 17 Testcontainer nhưng không phụ thuộc Neon/mạng công cộng/Keycloak thật.
- Test dùng JWT/JWK fixture nội bộ để deterministic; không hard-code token production.
- Database down làm readiness `DOWN/OUT_OF_SERVICE` nhưng không làm liveness `DOWN`.
- Context hiện tại của LN-001A vẫn migrate/validate thành công trên PostgreSQL.

### 16.4 Smoke Keycloak local

- Trên Keycloak database sạch, realm import thành công.
- Discovery và JWKS endpoint của realm truy cập được.
- Có đúng client/role/audience mapper đã định nghĩa.
- Authorization Code + PKCE local login được kiểm tra thủ công qua Swagger hoặc client local; user/password test không commit.
- Restart cùng volume không phá realm/user hiện có; ghi rõ import bị skip khi realm đã tồn tại.

### 16.5 Command bàn giao dự kiến

```powershell
.\mvnw.cmd -pl finora-loan -am verify
powershell -ExecutionPolicy Bypass -File .agents/scripts/validate-rules.ps1
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Loan -KeepRunning
```

Nếu sửa `finora-common`, phải compile/verify mọi Java module chịu ảnh hưởng hoặc ghi rõ module nào chưa chạy và lý do.

## 17. Acceptance criteria khi mở lại LN-002

- [ ] Thái duyệt mở lại LN-002 sau khi contract Identity sẵn sàng.
- [ ] Hải đã hậu kiểm phần JWT/role/error/trace dùng chung trước merge.
- [ ] Realm import không chứa user, password, token hoặc client secret thật.
- [ ] Loan xác minh signature, issuer, audience, expiry/not-before bằng Spring Resource Server.
- [ ] Loan không gọi User/Keycloak introspection ở mỗi request.
- [ ] Chỉ allowlist ba role FINORA; endpoint mới fail closed.
- [ ] `401/403` sử dụng cùng error envelope đã có cho `400/404/409/500` và không lộ nội bộ.
- [x] Trace ID có ở response header, error body và MDC; invalid input không gây log injection; MDC được cleanup.
- [x] Log triển khai là JSON và không chứa secret/PII/token.
- [x] Liveness không phụ thuộc DB/Keycloak; readiness phản ánh DB.
- [ ] Test không phụ thuộc public network, clock/order hoặc token thật.
- [ ] Loan verify, security test, Keycloak smoke và rule validator đều pass hoặc limitation được ghi rõ.
- [ ] `MockCurrentUserProvider` đã được xóa.
- [ ] Không thêm Redis, Entity, migration nghiệp vụ, User sync hoặc Gateway change ngoài phạm vi.

## 18. Ngoài phạm vi và task vẫn còn bị chặn

- Không đăng ký người dùng, reset password, MFA hoặc social login.
- Không đồng bộ Keycloak identity sang bảng User; không tạo User profile/KYC.
- Không tự động cấp/thu hồi role theo nghiệp vụ.
- Không triển khai resource ownership của Loan Application trước LN-004.
- Không chốt `borrowerId` bằng `sub`; User ID contract vẫn cần Hải xác nhận.
- Không thêm rate limit/CORS ở Gateway; Loan chỉ giữ security boundary của chính nó.
- Không làm Kafka trace/outbox khi chưa có producer/consumer thật.
- Không thêm Redis/session store/revocation list.
- Không nâng Spring Boot, Keycloak hoặc toàn bộ dependency platform trong cùng task.
- Không triển khai LN-003 Product hoặc LN-004 Application.

LN-003/LN-004 có thể phát triển bằng mock identity theo quyết định tạm thời, nhưng không được coi là đạt yêu cầu security để deploy/demo tích hợp trước khi LN-002 hoàn tất.

## 19. Failure path và rollback

| Tình huống | Hành vi mong đợi | Cách xử lý |
|---|---|---|
| Keycloak chưa sẵn sàng lúc Loan start | Loan vẫn có thể start nếu dùng JWK URL trực tiếp; request token đầu tiên có thể fail nếu chưa lấy được key | Lỗi lấy JWK trả `503 AUTHENTICATION_SERVICE_UNAVAILABLE`; token bản thân không hợp lệ mới trả `401`; không bypass security |
| Keycloak down sau khi Loan đã cache key | Token ký bằng key đã biết vẫn kiểm tra được tới khi cần refresh/rotate | Theo dõi lỗi JWK; không gọi User thay thế |
| Token sai issuer/audience/signature/hết hạn | `401 AUTHENTICATION_REQUIRED` | Không nói chi tiết xác minh ra client |
| Role đúng nhưng endpoint/owner sai | `403 ACCESS_DENIED` | Ownership thật được thêm ở task nghiệp vụ |
| Client gửi trace ID độc hại/quá dài | Bỏ giá trị và sinh UUID | Không đưa input thô vào log |
| DB hỏng | Readiness xuống, liveness vẫn sống | Ngừng nhận traffic, không restart loop |
| Realm đã tồn tại | Startup import bỏ qua | Test thay đổi trên DB sạch; không tự xóa volume chứa dữ liệu |
| Contract chung bị Hải yêu cầu sửa | Dừng merge phần chung | Cập nhật cùng plan bằng `Plan amendment`, giữ code Loan tương thích sau khi chốt |

Rollback code dùng revert change thông thường. Không xóa volume/realm tự động. Nếu cần tái tạo Keycloak local từ đầu, phải sao lưu hoặc được xác nhận chấp nhận mất dữ liệu trước khi xóa volume.

## 20. Bằng chứng bàn giao

Chỉ điền khi triển khai, không điền dự đoán.

| Hạng mục | Kết quả thực tế |
|---|---|
| File đã thay đổi | Đã gỡ Resource Server/JWT/role/realm import; thêm `MockCurrentUserProvider`; giữ error/trace/log/Actuator |
| Maven unit test | `mvn -pl finora-loan -am test`: exit `0`, 10/10 test pass (Common 6, Loan 4) |
| Endpoint không đăng nhập | `LoanControllerTest`: `GET /api/v1/loans/health` không có token trả `200` |
| Security dependency | Spring condition report xác nhận `SecurityAutoConfiguration` và `OAuth2ResourceServerAutoConfiguration` không hoạt động vì class tương ứng không còn trong classpath |
| Keycloak clean import smoke | Không áp dụng trong giai đoạn hoãn; realm import thử nghiệm đã gỡ |
| Error/trace tests | Error 6/6, trace 2/2 và mock identity 1/1 pass |
| Liveness/readiness integration test | Chưa chạy lại: Docker daemon đang tắt nên Testcontainers không tìm được Docker environment |
| Compose validation | `docker compose ... config --quiet`: exit `0` |
| Rule validator | `.agents/scripts/validate-rules.ps1`: `FINORA rules validation: OK` |
| Known limitation | Mọi request hiện chưa được xác thực; chỉ phù hợp phát triển local. Full `verify` phải chạy lại khi Docker hoạt động |

## 21. Nguồn kỹ thuật dùng để kiểm chứng plan

- [Keycloak — Importing and exporting realms](https://www.keycloak.org/server/importExport): startup import đọc JSON tại `/opt/keycloak/data/import`, realm đã tồn tại sẽ bị bỏ qua.
- [Keycloak — Running Keycloak in a container](https://www.keycloak.org/server/containers): mount import file và dùng `--import-realm` cho container.
- [Spring Security — OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/): starter và mô hình bảo vệ API bằng bearer JWT.
- [Spring Security — Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html): issuer/JWK validation, role converter và custom audience validation.
- [Spring Boot 3.2.5 — Actuator](https://docs.spring.io/spring-boot/docs/3.2.5/reference/html/actuator.html): ý nghĩa liveness/readiness và health probe.

## 22. Câu xác nhận ngắn cho Hải

Hải chỉ cần đọc và trả lời phần này:

> Thái đề xuất Keycloak realm `finora`, realm role `admin/borrower/investor`, API audience `finora-api`; Loan kiểm tra `iss/sub/aud/exp/nbf` và lấy role từ `realm_access.roles`. `sub` chỉ là Keycloak identity, chưa mặc định là User DB ID. Lỗi REST dùng `{code, message, details, traceId}`, trace header là `X-Trace-Id`. Hải xác nhận các contract này dùng chung được không? Nếu chưa, vui lòng chỉ rõ field/role nào cần đổi và giá trị đề xuất.
