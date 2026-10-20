---
task_id: W3A
title: Cổng bảo mật R8A — danh tính service, AI/Notification có xác thực, PIN gắn với một thao tác, chặn mock ngoài môi trường demo
owner: Thai
status: READY_FOR_REVIEW
depends_on: [W2D]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# W3A — Cổng bảo mật R8A

Thái duyệt làm từ Wave 2C tới hết Wave 3 và cho agent tự chọn phương án tốt nhất (D23). R8A là cổng tối thiểu phải đạt
trước R4 (tiền ngoài FINORA) và trước khi bật SmartCA/provider ngoài môi trường local
([`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 13). Plan sửa S01 trừ chặn tự đăng ký `ADMIN`
(hoãn theo D20) và lỗi B43 phát hiện khi rà. Module của Hải bị sửa: AI, User, Notification (D2) — Hải review trước merge.
Kiểm soát pháp lý: [`LEGAL-DATA-01`](../../docs/LEGAL-COMPLIANCE.md), [`LEGAL-PAYMENT-01`](../../docs/LEGAL-COMPLIANCE.md),
[`LEGAL-AI-01`](../../docs/LEGAL-COMPLIANCE.md).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **Không ai ngoài FINORA chấm điểm tín dụng hay đọc CCCD qua AI được.** Hôm nay AI mở hoàn toàn: ai gọi được cổng 8000
   là gửi `borrower_id` bất kỳ để AI tự tra CCCD rồi hỏi CIC, hoặc sửa bộ luật chấm điểm đang áp dụng cho mọi hồ sơ mà không
   để lại dấu vết. Sau W3A: chỉ Loan (service account) và quản trị viên đăng nhập mới chấm điểm được; chỉ quản trị viên sửa
   cấu hình, mỗi lần sửa ghi ai sửa; OCR CCCD chỉ nhận từ User.
2. **Không ai mượn hộp thư FINORA gửi OTP giả.** Endpoint nội bộ của Notification đang mở: ai gọi được cổng 8086 là bắt
   FINORA gửi email "mã OTP" hay "cảnh báo đăng nhập" tới địa chỉ bất kỳ. Sau W3A chỉ User (service account) gọi được.
3. **Một lần nhập PIN chỉ duyệt đúng một thao tác.** Hôm nay một mã xác nhận PIN dùng lại được trong 3 phút cho mọi lệnh
   cùng loại: lộ một token là đủ trả nợ/đặt lệnh nhiều lần. Sau W3A token gắn với mã thao tác (`Idempotency-Key`) mà app tạo
   trước khi hỏi PIN; gửi lại đúng thao tác đó (mạng chập chờn) vẫn được, thao tác khác thì bị từ chối.
4. **Nút "Giả lập thanh toán thành công" chỉ tồn tại ở môi trường demo.** Hôm nay nó chỉ phụ thuộc provider của lệnh nạp;
   W3A thêm biến môi trường `FINORA_ENVIRONMENT` và chặn mọi cấu hình giả (provider mock, khoá PIN mặc định, đồng hồ E2E)
   khi khởi động ở `staging`/`production`.
5. **Đoán mật khẩu bị khóa tạm.** Keycloak bật chống dò mật khẩu: 5 lần sai liên tiếp thì chờ tăng dần, tối đa 15 phút.
6. **Không gửi dồn yêu cầu tốn tiền.** OCR CCCD (gọi Gemini), tạo lệnh nạp, báo giá trả nợ có giới hạn số lần theo người
   dùng; vượt thì nhận `429` kèm thời gian chờ.
7. **Admin web gọi AI qua đúng phiên đăng nhập** thay vì gọi thẳng cổng 8000 không xác thực (B43 làm Gateway nuốt body).

### Ai thấy gì?

- Người vay/nhà đầu tư: không đổi màn hình; app gửi mã thao tác khi xác nhận PIN. Lệnh bị chặn vì gửi quá nhanh hiện thông
  báo "Bạn thao tác quá nhanh, thử lại sau N giây".
- Quản trị viên: trang chính sách AI và chấm điểm hoạt động như cũ khi đã đăng nhập; phiên hết hạn thì nhận lỗi đăng nhập.
- Vận hành: service khởi động ở `production` mà còn cấu hình giả thì dừng ngay và nêu đúng khóa sai; log không chứa token.

### Ví dụ

- Kẻ có token PIN của người vay A (scope `REPAYMENT`, cấp cho lệnh trả kỳ có `Idempotency-Key = k1`) gửi lệnh tất toán với
  `Idempotency-Key = k2`: `403 PIN_TOKEN_INVALID`. Gửi lại đúng lệnh `k1`: Payment trả kết quả cũ của `k1`, không trừ tiền
  lần hai.
- `curl -X POST :8000/api/v1/ai/credit/explain -d '{"borrower_id":"..."}'` không token: `401`. Token của người vay: `403`.

## 1. Tách pháp luật, policy và quyết định

| Nội dung | Loại | Nguồn |
|---|---|---|
| Chỉ cung cấp số định danh just-in-time qua contract được bảo vệ; không ghi PII vào log | Kiểm soát dữ liệu | `LEGAL-DATA-01` |
| Mock top-up chỉ là tiền sandbox, không được chạy như tiền thật | Kiểm soát thanh toán | `LEGAL-PAYMENT-01` |
| Quyết định chấm điểm có version và ai đổi cấu hình | Kiểm soát AI | `LEGAL-AI-01` |
| 5 lần sai mật khẩu, khóa tạm tối đa 15 phút; giới hạn tần suất | Policy cấu hình được | Thực tiễn ngân hàng, không phải trích dẫn luật |
| TTL token PIN 3 phút | Policy cấu hình được (`FINORA_PIN_TOKEN_TTL`) | Giữ nguyên |
| Giới hạn tần suất in-memory theo instance | Giới hạn kỹ thuật demo | Nhiều instance cần store chung (R8B/R9) |

## 2. Quyết định (D33–D40, ghi vào plan tổng hợp mục 3a)

| # | Quyết định |
|---|---|
| D33 | Sửa B43: Gateway gọi upstream bằng HTTP/1.1. JDK `HttpClient` mặc định thử nâng cấp `h2c` trên URL `http://`; uvicorn 0.27/h11 0.16 giữ body chờ `101 Switching Protocols` nên AI nhận body rỗng. Sửa ở Gateway vì mọi upstream không phải Tomcat (AI, CIC Node) đều bị |
| D34 | Danh tính service: mỗi service gọi có một client Keycloak confidential riêng (client credentials), service nhận kiểm client role. `finora-loan-client` (`ai:credit:score`), `finora-user-client` thêm `ai:ekyc:ocr`, `notification:internal:send`, `finora-ai-client` (gán `user:admin:read_all` của User, thay cho việc AI giữ secret `finora-user-client` có quyền quản trị Keycloak). Secret chỉ nằm trong `.env` git-ignore |
| D35 | AI xác thực JWT Keycloak (RS256 qua JWKS, kiểm issuer, hạn, `azp` không bắt buộc) từ header `Authorization` hoặc cookie `access_token`; quyền theo bảng mục 3. Swagger/OpenAPI chỉ bật ở `local`/`e2e`/`demo`. Sửa cấu hình ghi log actor |
| D36 | Notification `/api/internal/notifications/**` yêu cầu authority `notification:internal:send`; body có validation |
| D37 | `finora.environment` (`FINORA_ENVIRONMENT`: `local`/`e2e`/`demo`/`staging`/`production`, mặc định `local`). `staging`/`production` từ chối khởi động khi còn khoá PIN mặc định, đồng hồ/điểm dừng E2E, provider mock (Payment), chữ ký `MOCK` (Loan). `mock-complete` chỉ ở `local`/`e2e`/`demo`, provider mock, lệnh chưa hết hạn; ngoài ra `404` |
| D38 | Token PIN gắn với `Idempotency-Key` của thao tác: claim `req` = SHA-256 (base64url) của khóa; endpoint `@RequirePin` bắt buộc header này (thêm cho hủy khoản trả kỳ và `PUT auto-invest`). Không cần bảng chống dùng lại: cùng khóa thì idempotency của service trả kết quả cũ |
| D39 | Loan bỏ URL/username Neon mặc định trong `application.yml`, mặc định localhost như service khác; `.env` của máy dev giữ giá trị cũ |
| D40 | Keycloak bật brute-force detection (5 lần, chờ tăng 60 giây, tối đa 15 phút, không khóa vĩnh viễn); giới hạn tần suất in-memory trong `finora-common` cho OCR CCCD, tạo lệnh nạp và báo giá trả nợ |

## 3. Thiết kế

### Gateway (B43, D33)

Bean `ClientHttpRequestFactory` dùng `JdkClientHttpRequestFactory` với `HttpClient.Version.HTTP_1_1`, connect timeout 5 giây.
Test dựng `com.sun.net.httpserver.HttpServer`, gửi POST có body qua Gateway: upstream nhận đủ body và không có header
`Upgrade`.

### Danh tính service (D34)

- `finora-common`: `ClientCredentialsTokenProvider` (cache tới trước hạn 30 giây, khóa đồng bộ, không log token).
- Loan → AI: interceptor `Authorization: Bearer` trên `aiCreditRestClient`; cấu hình `finora.loan.service-account.*`
  (`KEYCLOAK_LOAN_CLIENT_SECRET`).
- User → AI OCR, User → Notification: `RequestInterceptor` Feign lấy token của `finora-user-client`.
- AI → User: dùng `finora-ai-client` (`KEYCLOAK_AI_CLIENT_ID/SECRET`).
- Keycloak: realm template thêm client/role; script `docker/keycloak/add-service-clients.sh` cho realm đang chạy, chạy lại
  an toàn, ghi secret thẳng vào `.env` git-ignore và không in secret.

### Quyền trên AI (D35)

| Endpoint | Được phép |
|---|---|
| `POST /api/v1/ai/credit/explain` | `ai:credit:score` (Loan) hoặc `ROLE_ADMIN` (trang chấm điểm) |
| `GET/PUT /api/v1/ai/config/product`, `/config/rules` | `ROLE_ADMIN`; PUT log `actor` |
| `POST /api/v1/ai/ekyc/ocr` | `ai:ekyc:ocr` (User) |
| `POST /api/v1/ai/fraud/detect` | `ROLE_ADMIN` (chưa service nào gọi) |
| `GET /health` | Công khai |

Thiếu/sai token `401 AI_UNAUTHENTICATED`, thiếu quyền `403 AI_FORBIDDEN`; JWKS không tải được `503`. Test dùng khóa RSA
sinh trong test và JWKS giả; test cũ dùng dependency override.

### PIN gắn thao tác (D38)

`POST /users/me/pin/verify` nhận thêm `requestKey` (8–150 ký tự, chính là `Idempotency-Key` sẽ gửi). `RequirePinInterceptor`
đọc `Idempotency-Key`; thiếu → `400 IDEMPOTENCY_KEY_REQUIRED`, khác claim → `403 PIN_TOKEN_INVALID`. Mobile tạo khóa trước
khi hỏi PIN; harness E2E truyền khóa khi lấy token (lần gửi lại idempotent dùng lại đúng token và khóa).

### Môi trường (D37)

`FinoraEnvironmentGuard` trong `finora-common` chạy lúc khởi động. Mỗi service khai báo thêm điều kiện riêng (Payment:
provider; Loan: chữ ký). `TopUpResponse.mockCompletionAvailable` tính cả môi trường.

### Giới hạn tần suất (D40)

Annotation `@RateLimited(key, limit, window)` + interceptor trong `finora-common`, đếm theo `(key, user)` cửa sổ trượt
đơn giản trong bộ nhớ. Áp cho: User OCR CCCD 5/10 phút, Payment tạo lệnh nạp 10/giờ, báo giá trả nợ 30/phút. Vượt:
`429 RATE_LIMITED`, header `Retry-After`.

### Kiểm thử phủ quyền (R8A)

Harness E2E thêm bước "quyền chéo": người vay/nhà đầu tư thứ hai đọc/sửa tài nguyên của người thứ nhất ở Loan, Payment,
Investment, Notification phải nhận `403/404`; gọi AI, Notification nội bộ không token nhận `401`; token người vay gọi
endpoint admin nhận `403`.

## 4. Vòng đời và ca lỗi

| Tình huống | Kết quả |
|---|---|
| Keycloak tắt khi Loan cần token | Không gọi AI; scoring giữ hàng đợi retry như AI tạm lỗi (`AI_UNAVAILABLE`) |
| Token service hết hạn giữa chừng | Provider làm mới trước hạn 30 giây; AI trả `401` thì lần sau lấy token mới |
| Notification từ chối vì thiếu quyền | User gửi email best-effort như hiện tại, log mã lỗi, không chặn đăng ký/đăng nhập |
| App cũ không gửi `requestKey` | `400` khi xác nhận PIN; phải cập nhật app cùng backend (một đợt phát hành) |
| Service `production` còn provider mock | Dừng khởi động, nêu tên khóa |

## 5. Acceptance

- Unit/IT xanh cho từng module; Gateway test B43; AI pytest có test xác thực.
- E2E: hồi quy sáu fixture và drill trên stack đã bật xác thực service; bước quyền chéo đạt.
- Negative check: AI/Notification nội bộ không token `401`; PIN token dùng cho khóa khác `403`.
- Không secret trong git; script quét secret chạy sạch.

## 6. Bản đồ code thực tế

| Mục | Điểm vào → xử lý |
|---|---|
| B43 | `finora-gateway` `UpstreamHttpClientConfig.upstreamClientHttpRequestFactory` (JDK `HttpClient` HTTP/1.1, giữ `allowRestrictedHeaders=host`) thay bean `gatewayClientHttpRequestFactory` |
| D34 | `finora-common` `ClientCredentialsTokenProvider` (cache, `bearerInterceptor` bỏ token khi 401, `ServiceTokenException`); Loan `AiCreditClientConfig` (`loanServiceTokenProvider`, `aiCreditRestClient` có interceptor), `AiCreditScoringHttpClient.mapException` (401 retry); User `ServiceAccountConfig`, `client.ServiceAccountFeignConfig` (không `@Configuration`) gắn cho `AiEkycClient`, `NotificationClient`; AI `user_client.py` dùng `finora-ai-client` |
| D35 | AI `app/security.py` (`current_caller`, `require_any`, `authorities_of`, `api_docs_enabled`), `main.py` (quyền theo router), `config_router.py` (log actor khi PUT); web `aiApi.ts` (`withReauth`, `credentials: 'include'`), Vite proxy `/api/v1/ai` → Gateway |
| D36 | Notification `NotificationSecurityConfig` (`hasAuthority(INTERNAL_SEND_AUTHORITY)`), `NotificationController` (`@Valid`), DTO có constraint |
| D37 | `finora-common` `FinoraEnvironment`, `FinoraEnvironmentConfig.finoraEnvironment` (kiểm chung + `SimulatedSettingsCheck`); `PaymentSimulatedSettings`, `LoanSimulatedSettings`, `UserSimulatedSettings`, `InvestmentSimulatedSettings`, `BlockchainSimulatedSettings`; Payment `TopUpService.completeMock`/`mockCompletionEnabled`, `TopUpResponse.from(topUp, enabled, now)`, `PaymentTopUp.isExpired` |
| D38 | `PinTokenService.issue(subject, scope, requestKey)` (claim `req`), `verify(..., requestKey)` (so sánh thời gian hằng), `RequirePinInterceptor` (đọc `Idempotency-Key`, thiếu → 400); User `VerifyPinRequest.requestKey` → `TransactionPinService.verify`; Payment `RepaymentController.cancel`, Investment `AutoInvestController.saveMyConfig` thêm header; mobile `requirePin(scope, requestKey)`, `verifyPin(..., requestKey)`; harness `Get-PinToken -RequestKey` |
| D39 | Loan `application.yml` datasource mặc định `localhost:15433/finora_loan`; `.env.example` có biến (comment) |
| D40 | Keycloak template + `add-service-clients.sh` (brute force, `--skip-brute-force`); `finora-common` `RateLimited`, `RateLimitInterceptor`, `InMemoryRateLimiter`, `RateLimitConfig` (`finora.rate-limit.enabled`); gắn ở User `UserProfileController.verifyEkyc`, Payment `WalletController.createTopUp`, `RepaymentController` hai endpoint báo giá |
| R8A E2E | Harness `Invoke-AccessControlChecks` (người vay/nhà đầu tư thứ hai, người vay gọi API admin, AI/Notification không token, body POST qua Gateway tới AI), check `security.pin-token-bound-to-one-action` |

Khác so với dự kiến: AI cần FastAPI 0.115.6 vì TestClient của Starlette 0.35 không chạy với httpx 0.28 (B44); script provision
không đăng nhập được realm đang chạy vì mật khẩu admin master khác `docker/.env` (DB Keycloak trên Neon) nên Thái tự chạy.

## 7. Bằng chứng

| Hạng mục | Kết quả |
|---|---|
| Maven toàn platform (2026-10-17) | common 52, gateway 3, Loan 262 + 17 IT (1 bỏ qua: AI live), Payment 84 + 24, Blockchain 11 + 4, Investment 75 + 47, User 65 + 1, Notification 5 — BUILD SUCCESS |
| Payment bổ sung | `TopUpServiceMockCompletionTest` 4, `PaymentSimulatedSettingsTest` 2 |
| AI pytest (image mới, FastAPI 0.115.6) | 379 passed, 10 failed — đúng 10 test đỏ có sẵn ở HEAD trước W3A (B44); bản HEAD cùng môi trường: 362 passed, 10 failed |
| AI image smoke | `/health` 200; `POST /credit/explain` không token 401; `/docs` ở `production` 404; token hỏng `401 AI_UNAUTHENTICATED` |
| Mobile / web | `npx tsc --noEmit` sạch; `npm run build` sạch |
| Keycloak realm | `add-service-clients.sh` đã chạy (2026-10-17): token thật của `finora-loan-client` có `ai:credit:score`, `finora-user-client` có `ai:ekyc:ocr`, `notification:internal:send`, `user:admin:read_all`, `finora-ai-client` chỉ có `user:admin:read_all`; container AI/User tạo lại với image mới |
| E2E | `w3a-installment-20261017-184734` (fixture trả kỳ mặc định, `-Database local -WithToxiproxy`, kèm drill): **79/79 PASS**. Chấm điểm AI qua service account thành công; `security.pin-token-bound-to-one-action` 403 `PIN_TOKEN_INVALID`; 15 check `access.*` (người thứ hai cùng vai trò 403/404, người dùng gọi API quản trị 403, AI trực tiếp/qua Gateway và Notification nội bộ không token 401, token người vay chấm điểm 403); `gateway.ai-post-body-forwarded` 422 liệt kê đủ field thiếu (B43) |
| Lượt hỏng trước đó | `w3a-installment-20261017-183146` 78/79: `withdrawal.admin-queue-reachable` 404 vì jar Gateway build trước commit thêm route `/api/v1/admin/withdrawals/**`. Thêm kiểm tra B65: launcher từ chối chạy jar cũ hơn mã nguồn |
