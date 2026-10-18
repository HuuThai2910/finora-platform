# Nâng cấp máy dev lên Wave 3 (sau khi pull `main`)

Áp dụng cho người pull `main` của finora-platform, finora-web, finora-mobile và cic-service-node sau ngày 2026-10-17.
Không dán secret vào chat, ảnh hay commit; chỉ điền vào các file `.env` (git-ignore).

## 1. Keycloak — làm trước tiên

Keycloak của nhóm dùng DB Neon chung (`finora-keycloak`). Script `docker/keycloak/add-service-clients.sh` đặt secret của client
theo `.env` của **máy chạy script**; nếu `.env` chưa có secret thì script **sinh secret mới và ghi đè lên Keycloak**, làm máy
khác mất xác thực service (401).

1. Vào Keycloak Admin Console → Clients → `finora-loan-client` và `finora-ai-client` → tab **Credentials**: chép secret hiện
   có. **Không bấm Regenerate.**
2. Điền vào `.env` của mình:
   - `KEYCLOAK_LOAN_CLIENT_SECRET` trong `docker/.env` và `finora-loan/.env`;
   - `KEYCLOAK_AI_CLIENT_SECRET` trong `docker/.env`;
   - `KEYCLOAK_CLIENT_ID=finora-ai-client` và `KEYCLOAK_CLIENT_SECRET` (của `finora-ai-client`) trong `finora-ai/.env`.
3. Sau bước 2, chạy lại script cũng an toàn (dùng lại giá trị đã có). Nếu Keycloak của bạn là DB riêng thì chạy script bình
   thường.

## 2. Biến môi trường mới

| File | Biến |
|---|---|
| `docker/.env` | `KEYCLOAK_LOAN_CLIENT_SECRET`, `KEYCLOAK_AI_CLIENT_SECRET`, `FINORA_BUSINESS_TIME_ZONE`, `FINORA_BORROWER_PROFILE_PROVIDER`, `USER_CIC_MAPPING_ENABLED` (mặc định `false`), `BLOCKCHAIN_POSTGRES_ADMIN_PASSWORD`, `BLOCKCHAIN_DB_PASSWORD` (đặt giá trị ngẫu nhiên trước lần đầu bật `blockchain-postgres`); tùy chọn `APPS_*` của profile `apps` (mặc định giả lập, D85) |
| `finora-loan/.env` | `LOAN_DB_URL/USERNAME/PASSWORD` (Loan không còn mặc định trỏ Neon, D39), `KEYCLOAK_LOAN_CLIENT_SECRET`; `FINORA_SIGNATURE_PADES_*` có mặc định |
| `finora-ai/.env` | `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`, `FINORA_ENVIRONMENT` |
| `finora-notification/.env` | `NOTIFICATION_PUSH_PROVIDER` (mặc định `log`), `NOTIFICATION_DUE_REMINDER_ENABLED`, `EXPO_ACCESS_TOKEN` (chỉ khi push thật) |
| `finora-mobile/.env` | `EXPO_PUBLIC_EAS_PROJECT_ID` (chỉ khi push thật), `EXPO_PUBLIC_MOCK_DOMAINS` |

## 3. Build và chạy

```powershell
# Image User và AI đổi code (W3A, W3E)
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ai up -d --build --no-deps ai
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build --no-deps user
# Jar của sáu service Java. Nếu sáu container app (profile apps) đang chạy thì dừng trước: container giữ khóa file jar
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile blockchain --profile apps stop loan payment investment notification blockchain gateway
mvn -B -ntp -pl finora-loan,finora-payment,finora-investment,finora-notification,finora-blockchain,finora-gateway -am package -DskipTests
# Chạy hằng ngày bằng Docker với DB Neon (docker/README.md mục 1b), hoặc chạy service trong IntelliJ như trước
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile blockchain --profile apps up -d
```

- **DB Neon dùng chung:** lần đầu chạy code mới, Flyway áp migration còn thiếu (Loan đến V32, Payment V18, Notification V2–V4,
  Investment đến V18); migration đã áp không gỡ được. Thống nhất trong nhóm ai chạy trước.
- **Mobile:** `npm install` một lần (thêm `expo-notifications` 57.0.17), rồi `npx expo start`. Web không thêm thư viện.
- **CIC (repo của Hải):** `node_modules` đã commit, không cần cài. Test B66 trong `tich-hop-finora-domain.test.js` cần sửa CCCD.
  Bản Vercel cần biến `CIC_INTERNAL_API_KEY` trùng với `docker/.env` của FINORA (đã cài ngày 2026-10-17).
- **Test tự động và dời đồng hồ:** launcher E2E `docker/e2e/start-local-services.ps1 -Database local` (runbook
  `docs/testing/E2E-VERTICAL-SLICE.md`). Test tay trên UI: `docs/testing/UI-MANUAL-TEST-GUIDE.md`.
