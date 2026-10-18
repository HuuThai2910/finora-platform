# Hướng dẫn kiểm thử thủ công giao diện FINORA (web quản trị và mobile) trên stack E2E local

> Cập nhật: 2026-10-18 · Nhánh: `feat/finora-remaining-wave-3` của `finora-platform`, `finora-web`, `finora-mobile` ·
> Người duyệt: Thái (owner) · Người kiểm: Hải và các thành viên.
> Chỉ dùng trên máy dev (profile `apps` với DB Neon dev, hoặc stack E2E DB local, mục 2.1a): không tiền thật, không CCCD
> thật, không production.

## 0. Cách đọc tài liệu

- Mỗi luồng ở mục 4 gồm: điều kiện, ca chính (bước đánh số), kết quả trên màn hình, kiểm tra backend (tùy chọn) và khối
  **Ca lỗi và ca biên**.
- Nhãn cách kích hoạt ca:
  - **[UI]**: chỉ thao tác trên app hoặc web.
  - **[UI + công cụ]**: cần công cụ phụ ở mục 2.10 (dời đồng hồ, Toxiproxy, tắt service, Kafka console…); lệnh và shell
    ghi ngay trong ca.
  - **[API]**: không có đường đi trên giao diện, phải gọi API (mục 2.10, công cụ T7). Ghi rõ là không kiểm được bằng UI.
- **Bất biến tiền** ghi theo hai số của ví: khả dụng / đang giữ (`available` / `held` của `GET /api/v1/wallets/me`).
  "Không trừ hai lần" nghĩa là chênh lệch khả dụng trước/sau đúng bằng một lần số tiền của thao tác.
- Chuỗi trong ngoặc kép là nhãn đọc từ code. Nhãn chưa xác minh được trong code ghi kèm "(tên nút cần kiểm lại trên app)";
  câu lỗi không đọc được từ code (do Keycloak hay khung lỗi chung tạo ra) ghi "ghi lại câu thực tế" để người kiểm chép vào phiếu.
- Shell:
  - **PowerShell** = Windows PowerShell 5.1 mở tại `C:\IUH\Nam4\KhoaLuan\finora-platform` (script stack).
  - **Git Bash** = Git Bash ở cùng thư mục. Lệnh `docker exec` có đường dẫn `/opt/...` trong Git Bash phải có tiền tố
    `MSYS_NO_PATHCONV=1`, nếu không Git Bash đổi đường dẫn thành đường dẫn Windows.
- Mã ca: `AUTH`, `LOAN`, `ADM`, `FUND`, `CTR`, `DISB`, `REP`, `PRE`, `OVD`, `RSC`, `WDR`, `INC`, `BC`, `NTF`, `CIC`,
  `SEC`, `OPS`. Ca lỗi có chữ `N` (ví dụ `REP-N03`).

## 1. Mục đích, phạm vi, đối tượng

### 1.1 Mục đích

Harness `docker/e2e/run-vertical-slice.ps1` đã kiểm bằng API cả vòng đời: đăng ký, hồ sơ, AI, duyệt, gọi vốn, ký,
giải ngân, Note, trả nợ, quá hạn, cơ cấu, rút tiền, proof, thông báo. Tài liệu này kiểm cùng các luồng đó **bằng tay trên
giao diện**: màn hình, nút, câu chữ, thông báo lỗi, số hiển thị và các ca biên mà người dùng thật gặp. Số liệu mong đợi lấy
từ evidence harness gần nhất (`docker/e2e/out/w3r-*-20261017-*.json`) và từ code.

### 1.2 Đối tượng

Thái (duyệt kết quả), Hải và thành viên nhóm (người kiểm). Người đọc biết nghiệp vụ P2P lending của FINORA nhưng không cần
thuộc tên màn hình hay lệnh.

### 1.3 Phạm vi

| Trong phạm vi | Ngoài phạm vi |
|---|---|
| Mobile (`finora-mobile`): người vay, nhà đầu tư | Hiệu năng, tải, bảo mật hạ tầng |
| Web quản trị (`finora-web`): admin | Demo Compose, production |
| Backend chạy bằng profile `apps` (Docker + DB Neon dev) hoặc launcher E2E với DB local riêng (`-Database local`), mục 2.1a | Tiền thật, CCCD thật, chữ ký số có giá trị pháp lý |
| Kiểm tra phụ bằng API khi UI không có đường đi | Chợ Notes thứ cấp (R11, chưa có kịch bản nghiệm thu) |

### 1.4 Cái gì thật, cái gì giả lập

| Thành phần | Trên stack E2E | Không được hiểu là |
|---|---|---|
| User/Keycloak, Loan, Investment, Payment ledger, Notification, Blockchain proof store, Kafka | Code hiện hành, dữ liệu thật trên DB local của máy | Môi trường dùng chung |
| Fineract | Fineract 1.15 local thật (lịch trả, booking, repayment) | Core dùng chung |
| AI | Container AI v17 thật | Có dữ liệu CIC: người vay E2E không eKYC nên AI không tra CIC, hồ sơ thường về `PENDING_REVIEW` |
| Nạp ví | Provider `mock`, nút giả lập hoàn tất nạp | ZaloPay, tiền thật |
| Giải ngân ra ngoài | Provider `mock` | Ngân hàng thật |
| Rút tiền (W3B) | Provider payout `mock`: nhận lệnh, gọi callback HTTP ký HMAC tới Payment; số tài khoản kết thúc `0000` mô phỏng tài khoản sai | Chuyển khoản thật |
| Chữ ký hợp đồng | `MOCK` + `CLICK_WRAP_MVP`; PDF ký PAdES-B-B nối tiếp bằng chứng thư demo tự ký `CN=FINORA DEMO - KHONG CO GIA TRI PHAP LY` | Chữ ký số có giá trị pháp lý, SmartCA |
| Bằng chứng blockchain | Ledger `MOCK` (receipt `MOCK_NOT_ON_CHAIN`); chỉ khi bật `-WithFabric` mới ghi lên mạng Hyperledger Fabric local 2 tổ chức | Bằng chứng pháp lý (`NEEDS_LEGAL_REVIEW`), Fabric production |
| Thông báo đẩy | Provider `log` (`LOG_MOCK`): tạo bản ghi gửi nhưng không gửi tới máy | Push thật; chỉ có khi development build + EAS projectId + `NOTIFICATION_PUSH_PROVIDER=expo` |
| KYC người vay trong Loan | Profile mock `VERIFIED`, 30 tuổi, nguồn `MOCK_USER_PROFILE` (nhà đầu tư thấy nhãn hồ sơ giả lập) | eKYC thật |
| eKYC nhà đầu tư | Tắt (`INVESTMENT_REQUIRE_EKYC=false`) chỉ trong stack E2E | Bỏ kiểm eKYC ở môi trường khác |
| Auto-Invest | Worker tắt (`INVESTMENT_AUTO_INVEST_ENABLED=false`): lưu được quy tắc, không có lệnh tự động | Auto-Invest chạy thật |
| CIC | Mặc định: bản Node deploy trên Vercel (mock công khai, chỉ tra điểm, không nhận Kafka). Với `-WithLocalCic`: CIC Node local cổng 9000, kho tệp `docker/e2e/out/cic/du-lieu.json`, consumer Kafka nhận quá hạn | Báo cáo CIC thật |
| OTP đăng ký | Đọc key `reg_otp:<email>` trong Redis `finora-user-redis` | Email OTP |
| Đồng hồ | Chỉ Loan và Payment dời được ngày (launcher `-LoanClockOffset`/`-PaymentClockOffset`) cùng business date của Fineract | Tính năng của demo/production (không có API đổi ngày) |

Cách A (profile `apps`, mục 2.1a) mặc định dùng cùng các giả lập trên (D85): chữ ký `MOCK`, KYC người vay mock, tắt eKYC
nhà đầu tư và worker Auto-Invest, dù `.env` của module ghi gì. Khác stack E2E: dữ liệu User, Loan, Payment, Investment,
Notification nằm trên Neon dùng chung; CIC luôn là bản Vercel; không dời đồng hồ.

Trên mọi màn hình liên quan, app phải ghi rõ là môi trường demo/giả lập (ví nạp mock, rút tiền, chữ ký demo, proof MOCK).
Thiếu nhãn đó là lỗi (`docs/LEGAL-COMPLIANCE.md`: `LEGAL-PAYMENT-01`, `LEGAL-CONTRACT-01`, `LEGAL-DATA-01`).

## 2. Chuẩn bị môi trường

### 2.1 Quy tắc an toàn

1. **Không kiểm tay khi harness đang chạy trên cùng stack.** Harness tự trả đồng hồ về ngày thật ở preflight và cuối lượt,
   dừng/bật lại service khi dời đồng hồ hoặc drill; thao tác tay sẽ làm hỏng lượt tự động và ngược lại.
2. Không dùng ảnh CCCD giả, không chụp CCCD thật. Không gửi CCCD thật tới bản CIC deploy công khai (B70).
3. Không dán token, PIN, OTP, mật khẩu vào báo cáo, ảnh chụp hay chat. Che token trong ảnh DevTools.
4. Không sửa dữ liệu bằng SQL; mọi thay đổi tiền đi qua API/UI (runbook `docs/ops/RUNBOOKS.md`).
5. Không pipe output của launcher (`| Select-Object`, `| Tee-Object`): lệnh sẽ treo (runbook E2E mục 8).

### 2.1a Chọn cách bật stack theo nhóm ca

Hai cách chạy **không bật cùng lúc** (trùng cổng 8080–8086). Đổi cách thì tắt cách đang chạy trước.

| Nhóm ca | Cách bật | Ghi chú |
|---|---|---|
| Phần lớn ca gắn **[UI]**: đăng ký, hồ sơ vay, duyệt, gọi vốn, ký MOCK, giải ngân, trả kỳ, trả trước, tất toán, rút tiền, sự cố, proof MOCK, thông báo, bảo mật | **Cách A — profile `apps`** (Docker + DB Neon, mục dưới) | Dữ liệu ở Neon như khi chạy IntelliJ |
| Ký **SmartCA UAT thật** (mục 4.5) | Cách A, thêm `APPS_SIGNATURE_PROVIDER=VNPT_SMART_CA` vào `docker/.env` rồi tạo lại container Loan: `docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile blockchain --profile apps up -d loan`. Thông tin UAT (`VNPT_SMARTCA_*`) đọc từ `finora-loan/.env`. Xong thì xóa biến và chạy lại lệnh để về `MOCK` | Không cần IntelliJ: SmartCA không gọi ngược vào Loan (chưa dùng webhook), Loan đối soát khi người dùng bấm kiểm tra. Khi bật, mọi chữ ký đều cần duyệt trên app SmartCA (một signer test cố định) |
| Ca cần **dời đồng hồ** (quá hạn, nợ xấu, ghi kỳ đúng ngày đến hạn — nhãn [UI + công cụ] dùng T2) | **Cách B — launcher E2E** `-Database local` (mục 2.2–2.3) | DB local tự tạo lại; chữ ký luôn MOCK; không dùng chung với SmartCA |
| **Fabric thật** (`BC-06`, `BC-N08`, `BC-N09`) | Cách B thêm `-WithFabric` | Thêm khoảng 200–400 MB RAM |
| **CIC local** (mục 4.15) | Cách B thêm `-WithLocalCic` | CIC trên Vercel không nhận được quá hạn (không đọc được Kafka local) |

**Cách A — profile `apps` (Docker + DB Neon).** PowerShell trong `finora-platform`. Jar đã build sẵn. Mọi lệnh `docker compose`
của cách A dùng đủ bốn profile như lệnh bật dưới đây (chỉ `--profile apps` thì Compose báo `invalid compose project`). Sửa
code một service: `stop <service>` trước (container giữ khóa file jar trên Windows nên Maven không ghi đè được), Maven
`package`, rồi `up -d <service>`. Đổi biến `APPS_*` (D85) cũng dùng `up -d <service>` để tạo lại container; `restart` giữ
biến cũ. Chi tiết: `docker/README.md` mục 1b.

```powershell
# Bật tất cả: Kafka, Keycloak, User, AI, Fineract, DB Blockchain/Fineract và sáu service Java (Loan, Payment, Investment,
# Notification, Blockchain với ledger MOCK, Gateway)
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile blockchain --profile apps up -d
# Đợi mọi container báo healthy (lần đầu khoảng 2–3 phút; Flyway áp migration còn thiếu lên Neon dùng chung)
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile blockchain --profile apps ps
# Một service không lên healthy: xem log
docker logs finora-loan --tail 100
# Tắt khi xong (container và dữ liệu giữ nguyên)
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile blockchain --profile apps stop
```

Sau đó chạy web (mục 2.4) và mobile (mục 2.5). Cổng 8080–8086 mở cho mạng LAN nên điện thoại thật gọi được qua IP máy tính
(Windows có thể hỏi quyền firewall).

**Cách B — launcher E2E (DB local).** Các mục 2.2–2.3 dưới đây. Trước khi bật, tắt sáu container app của cách A (giữ
hạ tầng): `docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract --profile blockchain --profile apps stop loan payment investment notification blockchain gateway`. Quay lại cách A: `start-local-services.ps1 -Action Stop` rồi lệnh bật cách A.

### 2.2 Dựng hạ tầng Docker (cách B)

PowerShell:

```powershell
# Kafka, Keycloak, Redis User, User, AI (profile demo) và Fineract
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo --profile fineract up -d
# Khi finora-user hoặc finora-common đổi code (launcher chặn nếu container User cũ hơn commit)
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build --no-deps user
```

Một lần cho mỗi realm Keycloak (W3A, cần mật khẩu admin master; script tự ghi secret vào `.env` git-ignore, không in ra),
Git Bash:

```bash
KEYCLOAK_ADMIN_PASSWORD='<mật khẩu admin master>' bash docker/keycloak/add-service-clients.sh   # thêm --skip-brute-force nếu realm dùng chung
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile ai up -d --build --no-deps ai
docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build --no-deps user
```

Thiếu bước này thì AI trả `401/403` cho Loan (chấm điểm đứng ở hàng đợi retry) và User không gửi được email qua
Notification. `--skip-brute-force` tắt chống dò mật khẩu của Keycloak: khi đó phần Keycloak của ca `AUTH-N06` không kiểm được.

### 2.3 Bật sáu service Java bằng launcher (cách B)

PowerShell:

```powershell
# Lần đầu hoặc khi mã nguồn đổi (launcher từ chối jar cũ hơn mã nguồn, B65)
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Start -Build
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Status
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Stop
# Chạy lại đúng một service, các service khác giữ nguyên
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Restart -Services payment
```

| Cờ của `-Action Start` | Dùng cho ca | Ghi chú |
|---|---|---|
| `-Database local` (mặc định) | Mọi ca, bắt buộc cho ca dời đồng hồ | Loan/Payment/Investment/Notification trên PostgreSQL local riêng (15433/15434/15437/15438) |
| `-WithToxiproxy` | `REP-N07`, `REP-N08`, `INC-02`, `INC-03` | Payment → Fineract đi qua `finora-toxiproxy:18444`; các lần `Restart` Payment giữ đường này |
| `-WithFabric` | `BC-06`, `BC-N08`, `BC-N09` | Dựng mạng Fabric local (cần ≥ 768 MiB RAM Docker trống, thêm ~200 MiB); tắt: `powershell -File docker/fabric-network/fabric-network.ps1 -Action Down` |
| `-WithLocalCic` (`-CicPath <thư mục>`) | Mục 4.15 | CIC Node local cổng 9000; cần nhánh có `src\messaging` (worktree `feat/finora-delinquency-intake`) |
| `-BorrowerProfileProvider user` | `LOAN-N05` | Loan đọc eKYC thật của User: người vay chưa eKYC bị chặn khi nộp |

`Status` in mỗi service với `clock` (`thật` hoặc độ lệch như `P41D`) và `db`. Log ở `docker/e2e/out/logs/<service>.log`;
log của lần chạy trước chuyển sang `docker/e2e/out/logs/archive/`.

Launcher ghi đè cho stack E2E: `FINORA_SIGNATURE_PROVIDER=MOCK`, `FINERACT_BOOKING_PROVIDER=fineract`,
`PAYMENT_DISBURSEMENT_PROVIDER=mock`, `PAYMENT_TOPUP_PROVIDER=mock`, `INVESTMENT_AUTO_INVEST_ENABLED=false`,
`INVESTMENT_REQUIRE_EKYC=false`, `FINORA_BORROWER_PROFILE_PROVIDER=mock`, Blockchain listener/worker bật với provider
`MOCK`. Các biến khác đọc từ `.env` của từng module như khi chạy IntelliJ. Lúc `Start`, nếu Fineract local chưa có reason code
`LoanRescheduleReason` đang bật, launcher in cảnh báo và worker cơ cấu của Loan chạy fail-closed: ca `RSC-04` không hoàn tất (`RSC-N09`).

### 2.4 Chạy web quản trị

PowerShell:

```powershell
cd C:\IUH\Nam4\KhoaLuan\finora-web
npm ci          # chỉ khi chưa có node_modules hoặc package-lock.json đổi
npm run dev     # Vite, http://localhost:5173
```

- Không cần file `.env`: code gọi đường tương đối `/api/v1/...` và Vite proxy theo `vite.config.ts`:
  `/api/v1/auth`, `/api/v1/users`, `/api/v1/admin/users`, `/api/v1/ai`, `/api/v1/blockchain` → Gateway 8080;
  `/api/v1/loan*`, `/api/v1/admin` → Loan 8081; `/api/v1/admin/repayment-reconciliation`,
  `/api/v1/admin/disbursement-reconciliation`, `/api/v1/admin/withdrawals` → Payment 8082 (đứng trước `/api/v1/admin`);
  `/api/v1/market`, `/api/v1/investments` → Investment 8084.
- Muốn trỏ khác thì đặt `VITE_LOAN_API_URL`, `VITE_PAYMENT_API_URL`, `VITE_INVESTMENT_API_URL`, `VITE_AI_API_URL`,
  `VITE_BLOCKCHAIN_API_URL`. Các biến trong `finora-web/.env.example` (`VITE_API_URL`, `VITE_WS_URL`, `VITE_APP_NAME`) không
  được code đọc (mục 7).
- Web nhận token qua cookie HttpOnly của User Service (đi qua proxy cùng origin).
- Khối tài khoản demo trên trang đăng nhập chỉ hiện khi chạy dev (`import.meta.env.DEV`).

### 2.5 Chạy mobile

1. Sao chép `finora-mobile/.env.example` thành `finora-mobile/.env` (file git-ignore) rồi thay `192.168.1.141` bằng IP của
   máy chạy stack:
   - điện thoại thật: IPv4 LAN của máy Windows (`ipconfig`), điện thoại cùng Wi-Fi; mở Windows Firewall cho TCP 8080–8086
     trong mạng Private;
   - Android emulator: `10.0.2.2` (địa chỉ của máy host trong emulator).
2. Ý nghĩa các biến (chỉ tên, không có secret):

   | Biến | Trỏ tới |
   |---|---|
   | `EXPO_PUBLIC_API_URL` | Loan `http://<IP>:8081/api/v1` |
   | `EXPO_PUBLIC_AUTH_API_URL` | Gateway `http://<IP>:8080/api/v1` (đăng nhập, hồ sơ người dùng, PIN, thông báo) |
   | `EXPO_PUBLIC_INVESTMENT_API_URL` | Investment `http://<IP>:8084/api/v1` (bỏ trống thì dùng Gateway) |
   | `EXPO_PUBLIC_PAYMENT_API_URL` | Payment `http://<IP>:8082/api/v1` |
   | `EXPO_PUBLIC_MOCK_DOMAINS` | Giữ `home` (chỉ thẻ tóm tắt trang chủ dùng dữ liệu giả). Không thêm `auth` hay `notification` |
   | `EXPO_PUBLIC_EAS_PROJECT_ID` | Bỏ trống: app báo thông báo đẩy chưa cấu hình, chỉ có thông báo trong app |
   | `EXPO_PUBLIC_MOCK_FAIL` | Để trống; đặt tên miền (ví dụ `wallet`) để xem màn lỗi giả lập (`OPS-N04`) |
   | `EXPO_PUBLIC_PRICING_DISCLOSURE_VERSION` | `RATE_DISCLOSURE_V2`, phải khớp `finora.loan.pricing-disclosure-version` của Loan |

   Không để trống `EXPO_PUBLIC_API_URL` hay `EXPO_PUBLIC_AUTH_API_URL`: code dùng `??` nên chuỗi rỗng không rơi về mặc định và
   mọi lời gọi hỏng. `EXPO_PUBLIC_INVESTMENT_API_URL` và `EXPO_PUBLIC_PAYMENT_API_URL` dùng `||` nên bỏ trống thì đi qua Gateway.
3. PowerShell: `cd C:\IUH\Nam4\KhoaLuan\finora-mobile; npm ci; npx expo start` rồi mở bằng Expo Go hoặc emulator. Đổi `.env`
   thì khởi động lại Expo với `npx expo start -c`.
4. Push thật cần development build trên máy thật và EAS projectId (Expo Go không nhận push từ xa), backend Notification phải
   chạy `NOTIFICATION_PUSH_PROVIDER=expo` (mục 4.14, `NTF-05`).

### 2.6 Tài khoản

| Vai trò | Cách có tài khoản |
|---|---|
| Admin | Tài khoản demo có sẵn: biến `FINORA_E2E_ADMIN_EMAIL`/`FINORA_E2E_ADMIN_PASSWORD` hoặc chip "Quản trị" của khối "Tài khoản dùng thử" trên trang đăng nhập web (`finora-web/src/features/auth/components/DemoAccounts.tsx`, chỉ khi `npm run dev`). Không tự đăng ký admin (D20) |
| Người vay | Đăng ký trên mobile ("Tạo tài khoản"). **Form mobile không có lựa chọn vai trò: mọi tài khoản đăng ký trên app là người vay** |
| Nhà đầu tư | Đăng ký bằng API với `role = INVESTOR` như harness (lệnh dưới), rồi đăng nhập mobile. Chip "Nhà đầu tư" của tài khoản demo dùng chung cho cả nhóm nên số dư và lịch sử không sạch; chỉ dùng để xem nhanh. Ca nhiều bên cho vay cần 2 nhà đầu tư |
| Người dùng thứ hai | Một người vay và một nhà đầu tư khác cho mục 4.16 |

Cách nhanh nhất để có đủ người vay và nhà đầu tư (đã có PIN, ví nhà đầu tư đã nạp): script `new-test-accounts.ps1` ở cuối
mục này.

Đặt email theo mẫu `qa.<tên>.<vai trò>.<yyyyMMddHHmm>@finora.test` (domain không có hộp thư thật). Form mobile bắt buộc số
điện thoại 10 chữ số bắt đầu bằng 0 và **không được trùng** với tài khoản khác ("Số điện thoại này đã được đăng ký trong hệ
thống"): dùng số giả theo lượt, ví dụ `09` + 8 chữ số của ngày giờ. Mật khẩu tối thiểu 8 ký tự, lưu trong trình quản lý mật
khẩu riêng, không ghi vào báo cáo. Mỗi email chỉ được 3 lần gửi OTP mỗi giờ (dùng chung với quên mật khẩu).

Đăng ký nhà đầu tư bằng API (PowerShell, module ở mục 2.10; mật khẩu nhập qua hộp thoại, không vào lịch sử lệnh):

```powershell
Import-Module .\docker\e2e\finora-e2e.psm1 -Force -DisableNameChecking
$inv = Get-Credential -Message 'Email (@finora.test) và mật khẩu cho nhà đầu tư mới'
$r = Invoke-E2EHttp -Method POST -Uri 'http://localhost:8080/api/v1/auth/register' -Body @{ email = $inv.UserName; password = $inv.GetNetworkCredential().Password; fullName = 'QA Nha dau tu'; role = 'INVESTOR' }
$r.Status                                       # 202: đã tạo challenge OTP
$otp = Get-E2ERedisValue -Container finora-user-redis -Key "reg_otp:$($inv.UserName)"
$v = Invoke-E2EHttp -Method POST -Uri 'http://localhost:8080/api/v1/auth/verify-registration' -Headers @{ 'X-Client-Type' = 'mobile' } -Body @{ email = $inv.UserName; otp = $otp }
$v.Status                                       # 201: tài khoản đã tạo; đăng nhập mobile bằng email/mật khẩu này rồi tạo PIN
```

Lấy OTP đăng ký (key hết hạn sau 5 phút; đọc ngay sau khi bấm gửi):

PowerShell:

```powershell
$email = 'qa.lan.borrower.202610171030@finora.test'
docker exec finora-user-redis sh -c 'if [ -z ${USER_REDIS_PASSWORD:+x} ]; then redis-cli --raw GET $0; else REDISCLI_AUTH=$USER_REDIS_PASSWORD redis-cli --raw GET $0; fi' "reg_otp:$email"
```

Git Bash:

```bash
email='qa.lan.borrower.202610171030@finora.test'
docker exec finora-user-redis sh -c 'if [ -z "${USER_REDIS_PASSWORD:+x}" ]; then redis-cli --raw GET "$0"; else REDISCLI_AUTH="$USER_REDIS_PASSWORD" redis-cli --raw GET "$0"; fi' "reg_otp:$email"
```

Lệnh trả 6 chữ số; rỗng là OTP chưa được ghi, đã hết hạn hoặc đã dùng. PIN giao dịch: 6 chữ số, tránh dãy lặp, tăng, giảm và
PIN phổ biến (User từ chối; harness sinh PIN có ít nhất 4 chữ số khác nhau).

#### Tạo tài khoản bằng script `new-test-accounts.ps1`

Script đi lại đúng luồng API của harness (đăng ký → OTP trong Redis → xác minh → tạo PIN), đăng nhập lại qua Gateway bằng
mật khẩu vừa lưu rồi nạp ví mock. Mặc định tạo 2 người vay và 2 nhà đầu tư (đủ cho ca nhiều bên cho vay và mục 4.16), nạp
mỗi nhà đầu tư tới 100.000.000 đ, người vay không nạp. Cần Gateway, User, container `finora-user-redis` và Payment (khi có
nạp). Dữ liệu giả: email `qa.<borrower|investor>.<yyyyMMddHHmmss>.<n>@finora.test`, họ tên `QA Người vay …`/`QA Nhà đầu
tư …`, mật khẩu và PIN ngẫu nhiên riêng từng tài khoản; không có số điện thoại, không eKYC.

PowerShell:

```powershell
# 2 người vay + 2 nhà đầu tư (mỗi nhà đầu tư có 100.000.000 đ trong ví)
powershell -ExecutionPolicy Bypass -File docker/e2e/new-test-accounts.ps1
# Chọn số lượng và mức nạp (đồng; 0 = không nạp; tối đa 100.000.000 đ)
powershell -ExecutionPolicy Bypass -File docker/e2e/new-test-accounts.ps1 -Borrowers 1 -Investors 3 -TopUpAmount 50000000 -BorrowerTopUpAmount 5000000
```

- Kết quả ghi vào `docker/e2e/out/test-accounts.json` (git-ignore, UTF-8): mảng `accounts`, mỗi phần tử có `createdAt`,
  `role`, `email`, `userId`, `fullName`, `password`, `pin`. Mỗi lần chạy chỉ nối thêm, không ghi đè tài khoản cũ. Mở file
  bằng editor để lấy mật khẩu và PIN; không dán nội dung file vào báo cáo hay chat (mục 2.1). Đổi đường dẫn bằng `-OutFile`;
  script từ chối file nằm trong repo mà không bị git-ignore.
- Console chỉ in email, vai trò, userId, kết quả đăng nhập và nạp ví; không in mật khẩu, PIN, OTP hay token.
- Thiếu phụ thuộc thì script dừng kèm lý do: Gateway/User/Payment không phản hồi, container Redis chưa chạy, Payment không
  cho nạp giả lập (D37, mục 2.8). Tài khoản tạo xong trước chỗ lỗi vẫn nằm trong file.
- Gateway hoặc Payment ở địa chỉ khác: `-GatewayUrl`, `-PaymentUrl` (mặc định `http://localhost:8080`, `http://localhost:8082`).

### 2.7 Sản phẩm vay đang bán

Người vay chỉ nộp được hồ sơ vào Product `ACTIVE` đã đồng bộ Fineract. Harness ngừng Product E2E của nó
(`E2E_V4_FIX12_<6 hex>`) sau mỗi lượt, trừ khi chạy với `-KeepProductActive`. Trước khi kiểm, admin tạo hoặc bật lại một
Product trên web (mục 4.3, `ADM-05`). Để số liệu khớp mục 4, dùng cấu hình như harness: lãi tối thiểu = cơ sở = tối đa
`12%`, số tiền 1.000.000–100.000.000 đ, kỳ hạn 1–12 tháng, trả góp đều (`ANNUITY`). Lãi cố định làm điều khoản cuối không đổi
theo hạng AI nên điều khoản tự xác nhận (`AUTO_AUTHORIZED`). Chỉ Product `DRAFT` mới đồng bộ được sang Fineract (D21); Product
mới dùng core V4 (lãi theo ngày).

**Stack Neon (nhóm A): chỉ nộp hồ sơ vào Product vừa tạo trên chính máy mình.** DB Loan dùng chung nên app liệt kê cả Product
mà máy khác đã đồng bộ vào Fineract của họ, trong khi Fineract chạy riêng trên từng máy (B21). Ngày 2026-10-18, Product
`ACTIVE` 3–6 (`VAY_TIEU_DUNG_NHANH`, `VAY_HOC_PHI`, `VAY_KINH_DOANH_NHO`, `VAY_SUA_NHA`) trỏ tới Product Fineract 501–504,
không có trên Fineract local của máy Thái: hồ sơ vào các Product này đi hết gọi vốn và ký rồi mới hỏng ở bước ghi khoản vay
vào Fineract. Product 1 và 2 khớp Fineract local nhưng là core V1/V2 (số liệu khác mục 4). Không tạm dừng Product 3–6 vì là
dữ liệu chung của nhóm. Log Loan có dòng `WARN … FINERACT_REQUEST_REJECTED … Loan with identifier … does not exist` cho khoản
vay cũ của máy khác: bỏ qua.

Kịch bản chuẩn của tài liệu: vay 10.000.000 đ, 6 tháng, 12%/năm. Số liệu mong đợi (evidence `w3r-installment-20261017-204753`):
kỳ 1 = 1.726.000 đ (gốc 1.624.000 + lãi 102.000), tổng phải trả theo lịch ban đầu 10.354.000 đ, mệnh giá Note 1.000.000 đ,
góp tối thiểu 1.000.000 đ, 10 Note sau giải ngân, kỳ đầu đến hạn đúng một tháng sau ngày giải ngân.

### 2.8 Nạp ví giả lập

Nhà đầu tư (và người vay khi cần trả thêm) nạp ví trên mobile:

1. "Trang chủ" → nút nhanh "Nạp" → màn "Nạp tiền vào ví" (màn gốc của tab "Ví" là "Lịch sử ví", không có nút nạp trừ khi ví
   trống: nút "Nạp tiền").
2. "Số tiền nạp" (chip 100.000 … 5.000.000; trợ giúp "Mỗi lần nạp từ 10.000 đ đến 100.000.000 đ.") → "Tạo giao dịch nạp tiền".
3. Thẻ chờ thanh toán: cổng "Giả lập (demo)", gợi ý "Mã giả lập chỉ để nhận diện giao dịch. Bấm “Giả lập thanh toán thành công”
   thay cho bước quét mã.", đồng hồ "Hết hạn sau {m:ss}".
4. Bấm "Giả lập thanh toán thành công" (chỉ hiện ở `local`/`e2e`/`demo`, provider `mock`, lệnh chưa hết hạn; D37) → "Đã nạp
   {số tiền}" / "Tiền đã vào Ví Finora lúc …", nút "Về ví", "Nạp thêm". App không tự hỏi trạng thái: dùng "Kiểm tra trạng thái".

API nạp chỉ nhận số nguyên đồng, 10.000–100.000.000 đ mỗi lần; tạo lệnh nạp giới hạn 10 lệnh/giờ mỗi người (D40). Hoàn tất một
lệnh hai lần không cộng tiền hai lần.

**Nạp lại bằng script (`-TopUpOnly`).** Ví nằm trong DB của Payment: đổi giữa cách A (Neon) và cách B (DB local) thì tài
khoản trong `docker/e2e/out/test-accounts.json` (mục 2.6) vẫn đăng nhập được nhưng ví trống. Chạy lại script với
`-TopUpOnly`: không tạo tài khoản, đăng nhập từng tài khoản trong file và nạp mock tới mức cần (`-TopUpAmount` cho nhà đầu
tư, mặc định 100.000.000 đ; `-BorrowerTopUpAmount` cho người vay, mặc định 0 = bỏ qua). Mức nạp là số dư khả dụng cần đạt:
ví đã đủ thì không tạo lệnh, nên chạy lại không cộng thêm tiền; ví thiếu thì nạp phần còn thiếu (tối thiểu 10.000 đ).

PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File docker/e2e/new-test-accounts.ps1 -TopUpOnly
# Nạp cả người vay trong file tới 5.000.000 đ
powershell -ExecutionPolicy Bypass -File docker/e2e/new-test-accounts.ps1 -TopUpOnly -BorrowerTopUpAmount 5000000
```

Trước lệnh nạp đầu tiên, script hỏi Payment có chức năng giả lập không (gọi `mock-complete` với một mã lệnh không tồn tại,
không tạo gì). Payment không chạy `PAYMENT_TOPUP_PROVIDER=mock` với `FINORA_ENVIRONMENT` là `local`/`e2e`/`demo` (D37; cách
A đọc `finora-payment/.env`, launcher cách B tự đặt `mock`) thì script dừng và không tạo lệnh nạp nào. Một tài khoản đăng
nhập lỗi (ví dụ tài khoản không còn trên Keycloak) chỉ bị đánh dấu `LỖI`, các tài khoản khác vẫn được nạp; mỗi tài khoản
tạo tối đa 10 lệnh nạp mỗi giờ (D40).

### 2.9 eKYC người vay

- Stack E2E và cách A (D85) chạy `FINORA_BORROWER_PROFILE_PROVIDER=mock`: Loan coi người vay là `VERIFIED`, 30 tuổi, nguồn
  `MOCK_USER_PROFILE`; nhà đầu tư thấy nhãn hồ sơ giả lập. Đây là cách duy nhất đi hết luồng mà không dùng CCCD.
- Không làm eKYC trên app bằng ảnh CCCD giả hay thật. OCR gọi Gemini (`GEMINI_API_KEY`), và nếu
  `USER_CIC_MAPPING_ENABLED=true` thì User gửi mapping CCCD sang CIC; bản CIC deploy trên Vercel không xác thực API đọc và
  lưu hồ sơ kèm số CCCD ở `/tmp` (B70). Repo mặc định `false`; máy Thái đang bật theo D84 vì chỉ có dữ liệu giả, nên càng
  không làm eKYC bằng CCCD thật.
- Kiểm cổng eKYC (`LOAN-N05`): chạy lại Loan với `-BorrowerProfileProvider user` (mục 2.3); người vay mới chưa eKYC bị chặn.
  Cách A: `APPS_BORROWER_PROFILE_PROVIDER=user` trong `docker/.env` rồi `docker compose ... up -d loan`; xong xóa biến và
  chạy lại lệnh.

### 2.10 Công cụ phụ

Mọi đoạn PowerShell dưới đây import module helper của harness (chỉ đọc cấu hình, không in secret):

```powershell
Import-Module .\docker\e2e\finora-e2e.psm1 -Force -DisableNameChecking
```

**T1 — OTP**: mục 2.6.

**T2 — Dời đồng hồ E2E bằng tay** (tái hiện `Move-E2EClock`/`Reset-E2EClock` của harness). Chỉ khi stack chạy
`-Database local`, không có harness chạy cùng lúc, tối đa 999 ngày. Harness làm ba việc theo thứ tự: bật business date của
Fineract và đặt ngày; giả lập job cuối ngày của Fineract (dời tới: `LA_RINT` "Recalculate Interest For Loans" rồi `LA_ARAG`
"Update Loan Arrears Ageing"; trả về hôm nay: chỉ `LA_ARAG`); chạy lại Loan/Payment với `-LoanClockOffset`/`-PaymentClockOffset`
bằng số ngày lệch. Thiếu `LA_ARAG` thì DPD đứng ở 0; thiếu `LA_RINT` thì khoản quá ngày đáo hạn thiếu phần lãi sau đáo hạn.

PowerShell, chuẩn bị (một lần mỗi phiên):

```powershell
Import-Module .\docker\e2e\finora-e2e.psm1 -Force -DisableNameChecking
$d = Read-E2EDotEnv -Path .\docker\.env; $l = Read-E2EDotEnv -Path .\finora-loan\.env
$fx = @{
  BaseUrl  = 'http://localhost:18443/fineract-provider/api/v1'; Tenant = 'default'
  Username = $(if ($d['FINERACT_API_USERNAME']) { $d['FINERACT_API_USERNAME'] } else { $l['FINERACT_API_USERNAME'] })
  Password = $(if ($d['FINERACT_API_PASSWORD']) { $d['FINERACT_API_PASSWORD'] } else { $l['FINERACT_API_PASSWORD'] })
}
function Invoke-FxJob([string]$ShortName) {
  $job = @(Invoke-E2EFineractGet @fx -Path '/jobs') | ForEach-Object { $_ } | Where-Object { $_.shortName -eq $ShortName } | Select-Object -First 1
  $before = $job.lastRunHistory.jobRunEndTime
  $null = Invoke-E2EFineractWrite @fx -Method POST -Path "/jobs/$($job.jobId)?command=executeJob" -Body @{}
  do { Start-Sleep -Seconds 2; $run = (Invoke-E2EFineractGet @fx -Path "/jobs/$($job.jobId)").lastRunHistory } while ($run.jobRunEndTime -eq $before)
  "$ShortName -> $($run.status)"   # phải là success
}
$cfg = Invoke-E2EFineractGet @fx -Path '/configurations/name/enable-business-date'
```

Dời tới ngày `$target` (ví dụ ngày đến hạn kỳ 1 cộng 10):

```powershell
$target = '2026-11-27'
$days = ([datetime]$target - (Get-Date).Date).Days      # phải từ 1 tới 999
$null = Invoke-E2EFineractWrite @fx -Method PUT -Path "/configurations/$($cfg.id)" -Body @{ enabled = $true }
$null = Invoke-E2EFineractWrite @fx -Method POST -Path '/businessdate' -Body ([ordered]@{ type = 'BUSINESS_DATE'; date = $target; dateFormat = 'yyyy-MM-dd'; locale = 'en' })
Invoke-FxJob 'LA_RINT'; Invoke-FxJob 'LA_ARAG'
# Ca chỉ cần Payment ghi kỳ đúng ngày đến hạn (REP-05): bỏ -Services loan và -LoanClockOffset
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Restart -Services loan,payment -Database local -LoanClockOffset "P$($days)D" -PaymentClockOffset "P$($days)D"
```

Trả về ngày thật (luôn làm khi xong ca, kể cả khi ca hỏng):

```powershell
$today = (Get-Date).ToString('yyyy-MM-dd')
$null = Invoke-E2EFineractWrite @fx -Method POST -Path '/businessdate' -Body ([ordered]@{ type = 'BUSINESS_DATE'; date = $today; dateFormat = 'yyyy-MM-dd'; locale = 'en' })
Invoke-FxJob 'LA_ARAG'
$null = Invoke-E2EFineractWrite @fx -Method PUT -Path "/configurations/$($cfg.id)" -Body @{ enabled = $false }
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Restart -Services loan,payment -Database local
powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Status    # clock = thật
```

Lưu ý khi dời đồng hồ:

- Ảnh hưởng **mọi** khoản vay trên DB local: khoản vay của ca khác cũng quá hạn theo, sinh thông báo/event quá hạn.
- Chỉ Loan và Payment lệch ngày; Investment, Notification, Blockchain, User giữ giờ thật. PIN vẫn kiểm theo giờ UTC thật.
- Sau khi trả đồng hồ, khoản vay vừa dùng có giao dịch đề ngày tương lai: không dùng nó cho ca khác (runbook E2E 4a).
- Sau mỗi lần dời, chờ worker đồng bộ của Loan (10 giây/lần trên stack E2E) rồi kéo để làm mới màn hình.

**T3 — Lấy `data` của event Kafka** (artifact để kiểm proof, mục 4.13). Proof cho biết `source.eventId`; topic theo loại
proof: `CONTRACT_DOCUMENT` → `finora.loan.contract-activated`, `DISBURSEMENT` → `finora.loan.disbursed`, `REPAYMENT` →
`finora.payment.repayment-distributed`, `NOTE_OWNERSHIP` → `finora.investment.note-ownership-changed`. Consumer console dùng
group tạm, không đụng offset của service.

PowerShell (chép `data` vào clipboard):

```powershell
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
$eventId = '<source.eventId>'; $topic = 'finora.loan.contract-activated'
$hit = docker exec finora-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $topic --from-beginning --timeout-ms 10000 2>$null | Select-String -SimpleMatch $eventId | Select-Object -First 1
($hit.Line | ConvertFrom-Json).data | ConvertTo-Json -Depth 20 -Compress | Set-Clipboard
```

Git Bash (chép tay phần `"data":{...}`):

```bash
MSYS_NO_PATHCONV=1 docker exec finora-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 \
  --topic finora.loan.contract-activated --from-beginning --timeout-ms 10000 2>/dev/null | grep -F '<source.eventId>'
```

**T4 — Toxiproxy** (stack bật bằng `-WithToxiproxy`). Toxiproxy chặn User-Agent bắt đầu bằng "Mozilla" nên luôn đặt
`-UserAgent`.

PowerShell:

```powershell
$tp = 'http://localhost:8474/proxies/fineract-payment/toxics'
# Mất phản hồi: Fineract nhận và ghi, Payment không nhận được phản hồi
Invoke-RestMethod -Method Post -Uri $tp -UserAgent 'finora-qa' -ContentType 'application/json' -Body '{"name":"q13-drill","type":"timeout","stream":"downstream","toxicity":1.0,"attributes":{"timeout":0}}'
# Mất request: kết nối bị reset ngay khi Payment gửi, Fineract không nhận gì
Invoke-RestMethod -Method Post -Uri $tp -UserAgent 'finora-qa' -ContentType 'application/json' -Body '{"name":"q13-drill","type":"reset_peer","stream":"upstream","toxicity":1.0,"attributes":{"timeout":0}}'
# Gỡ lỗi (bắt buộc sau ca)
Invoke-RestMethod -Method Delete -Uri "$tp/q13-drill" -UserAgent 'finora-qa'
Invoke-RestMethod -Method Get -Uri $tp -UserAgent 'finora-qa'    # phải rỗng
```

Git Bash: `curl -s -X DELETE http://localhost:8474/proxies/fineract-payment/toxics/q13-drill` (curl không bị chặn
User-Agent).

**T5 — Tắt và bật service.**

- Service Java của launcher: lấy `pid` bằng `-Action Status`, tắt hẳn bằng PowerShell `Stop-Process -Id <pid> -Force`, bật
  lại bằng `-Action Restart -Services <tên>`.
- Điểm dừng drill (chỉ nhận khi chữ ký `MOCK`/provider `mock`): chạy lại service với `-DrillEnv` trong danh sách cho phép,
  ví dụ PowerShell:
  `powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Restart -Services loan -DrillEnv 'loan:FINORA_E2E_PAUSE_POINT=core-booked-before-save,loan:FINORA_E2E_PAUSE_DURATION=PT240S,loan:LOAN_DISBURSEMENT_CORE_BOOKING_LEASE=PT30S'`.
  Service ghi dòng `E2E_PAUSE point=<điểm>` vào log khi tới điểm dừng.
- Container: `docker stop finora-ai` / `docker start finora-ai`; tương tự `finora-fineract`, `finora-fabric-peer0-org2`.

**T6 — Đọc Fineract (chỉ đọc)**: `Invoke-E2EFineractGet @fx -Path '/loans/<fineractLoanId>?associations=repaymentSchedule,transactions'`
(`fineractLoanId` lấy từ chi tiết hợp đồng của người vay, `GET /api/v1/loan-contracts/{contractNumber}`). Giao dịch trả nợ
có externalId `FINORA-REPAY-<repaymentId>`; không có giao dịch nào được tạo bằng tay.

**T7 — Gọi API cho ca [API]** (dùng `Invoke-E2EHttp`: luôn trả mã HTTP, không ném lỗi). PowerShell:

```powershell
$cred = Get-Credential -Message 'Email và mật khẩu tài khoản test'
$r = Invoke-E2EHttp -Method POST -Uri 'http://localhost:8080/api/v1/auth/login' -Headers @{ 'X-Client-Type' = 'mobile' } -Body @{ email = $cred.UserName; password = $cred.GetNetworkCredential().Password }
$token = $r.Json.accessToken; $r.Status        # 200; không in $token
# Token PIN cho đúng một thao tác: requestKey chính là Idempotency-Key sẽ gửi (D38)
$key = "qa-$([guid]::NewGuid().ToString('N'))"
$pin = (Get-Credential -UserName 'PIN' -Message 'Nhập PIN vào ô mật khẩu').GetNetworkCredential().Password
$pinToken = (Invoke-E2EHttp -Method POST -Uri 'http://localhost:8080/api/v1/users/me/pin/verify' -Token $token -Body @{ pin = $pin; scope = 'REPAYMENT'; requestKey = $key }).Json.pinToken
# Ví dụ đọc kết quả
$x = Invoke-E2EHttp -Method GET -Uri 'http://localhost:8082/api/v1/wallets/me' -Token $token; "$($x.Status) $(Get-E2EErrorCode $x)"
```

Scope PIN: `REPAYMENT`, `INVEST`, `ORDER`, `AUTO_INVEST`, `SIGN_CONTRACT`, `WITHDRAW`. Cổng: Gateway 8080, Loan 8081, Payment 8082, Blockchain
8083, Investment 8084, User 8085, Notification 8086, AI 8000, Fineract 18443, Kafka 9092, CIC local 9000.

**T8 — Health** (mục 4.17): không cần token để xem trạng thái; chi tiết cần token admin.

### 2.11 Bắt đầu sạch

- Không có công cụ reset dữ liệu theo lượt; cách sạch là **tài khoản mới + khoản vay mới** cho mỗi luồng. Khoản vay đã dùng
  cho ca dời đồng hồ thì bỏ.
- Trước khi bắt đầu: `-Action Status` phải có `clock=thật`; nếu còn lệch (lượt trước bị ngắt), làm phần "trả về ngày thật"
  của T2. Toxiproxy không còn toxic (T4). Fabric chỉ bật khi cần.
- Không xóa volume DB local hay topic Kafka để "làm sạch": Notification dựng bảng tra người nhận từ Kafka, Fineract local giữ
  khoản vay mà DB mới không biết (B21). Reset toàn bộ là việc của owner.
- Sau khi kiểm xong: ngừng Product đã bật cho đợt kiểm (nếu không còn dùng), gỡ toxic, `fabric-network.ps1 -Action Down`.

## 3. Ma trận ca kiểm thử

Tóm tắt: 83 ca chính và 174 ca lỗi/biên (85 [UI], 44 [UI + công cụ], 42 [API], 3 không kích hoạt được trên stack MOCK hoặc
không có trong code). Ca lỗi gom theo khối; chi tiết ở mục 4. "NĐT" = nhà đầu tư.

| Mã | Luồng | Vai trò | Nền tảng | Điều kiện | Kết quả mong đợi | Quyết định/defect |
|---|---|---|---|---|---|---|
| AUTH-01 | Đăng ký + OTP | Người vay | Mobile | Email, số điện thoại mới | Tài khoản người vay, vào app ngay | D11 |
| AUTH-02 | Tạo PIN | Mọi vai trò | Mobile | AUTH-01 | PIN 6 số | D38 |
| AUTH-03 | Tạo nhà đầu tư | NĐT | API + mobile | Redis OTP | Vai trò "Nhà đầu tư" | D11 |
| AUTH-04 | Đăng nhập/đăng xuất | Mọi vai trò | Mobile | Có tài khoản | Đăng xuất gỡ thiết bị push | D62 |
| AUTH-05 | Đăng nhập quản trị | Admin | Web | Tài khoản demo | "Tổng quan", đủ menu | D20 |
| AUTH-06 | Quên mật khẩu | Người vay | Mobile + công cụ | Redis | Đổi mật khẩu | — |
| AUTH-N01–N14 | OTP sai/hết hạn/quá lượt, khóa đăng nhập, PIN yếu/sai/khóa, token PIN hết hạn, phiên hết hạn, người dùng vào web | Mọi vai trò | Mobile, web, API | — | Câu lỗi đúng, không có tiền di chuyển | D38, D40, D20 |
| LOAN-01 | Xem trước lịch | Người vay | Mobile | Product `ACTIVE` 12% | Kỳ đầu 1.726.000, tổng 10.354.000 | `LEGAL-DISCLOSURE-01` |
| LOAN-02 | Nộp hồ sơ, AI chấm | Người vay | Mobile | LOAN-01 | "Chờ duyệt", assessment `SUCCEEDED` | D4, D11 |
| LOAN-03 | Điều khoản cuối bất lợi hơn | Người vay | Mobile + web | Product 10/12/15% | Phải chấp nhận hoặc từ chối lại | `LEGAL-DISCLOSURE-01` |
| LOAN-04 | Rút hồ sơ | Người vay | Mobile | Hồ sơ `SUBMITTED` | "Đã rút" (rút muộn: `409`) | — |
| LOAN-N01–N12 | Validation, hạn mức dư nợ `422`, eKYC `409`, AI tắt, idempotency, công bố lãi, mệnh giá Note, quyết định AI theo điểm | Người vay | Mobile, API | — | Không tạo hồ sơ sai | D5, D4, B18, B19 |
| ADM-01 | Trang thẩm định | Admin | Web | LOAN-02 | Đủ thẻ, so sánh lãi, lịch | `LEGAL-AI-01` |
| ADM-02 | Duyệt | Admin | Web | ADM-01 | `APPROVED`, listing `OPEN` | — |
| ADM-03 | Từ chối có lý do | Admin | Web | Hồ sơ chờ duyệt | "Đã từ chối hồ sơ." | — |
| ADM-04 | Chấm thử AI | Admin | Web | — | Có kết quả, không nhập CCCD | B70, D35 |
| ADM-05 | Tạo/mở bán Product | Admin | Web | Fineract chạy | Đồng bộ rồi kích hoạt | D21 |
| ADM-06 | Chính sách AI | Admin | Web | — | Chỉ xem | D35 |
| ADM-N01–N08 | Phiên bản cũ `409`, chấm lại, assessment `FAILED`, validation Product, đồng bộ lỗi | Admin | Web, API | — | Không có quyết định thứ hai | — |
| FUND-01 | Listing, hồ sơ ẩn danh | NĐT | Mobile | ADM-02 | Nhãn hồ sơ giả lập, không PII | `LEGAL-DISCLOSURE-02` |
| FUND-02 | Nạp ví mock | NĐT | Mobile | — | Khả dụng +10.000.000 một lần | D37 |
| FUND-03 | Đặt lệnh + xác nhận rủi ro | NĐT | Mobile | FUND-02 | `COMMITTED`; khả dụng → đang giữ | D6 |
| FUND-04 | Đủ vốn | NĐT, người vay | Mobile | FUND-03 | `FULLY_FUNDED`, lập hợp đồng | D47 |
| FUND-05 | Hai nhà đầu tư | 2 NĐT | Mobile | — | Đủ vốn bằng hai lệnh | — |
| FUND-06 | Auto-Invest | NĐT | Mobile | — | Lưu được, có xác nhận rủi ro | Q5 |
| FUND-07 | Gọi vốn trên web | Admin | Web | — | Tiến trình, nút sửa lỗi chỉ sau giải ngân | B15 |
| FUND-N01–N19 | Thiếu số dư, xác nhận rủi ro thiếu/cũ, listing đóng, hết hạn gọi vốn, hủy lệnh, Payment tắt, người vay đặt lệnh | NĐT, admin | Mobile, web, API | — | Không giữ/nhả tiền sai | D6, D30, B38–B41 |
| CTR-01 | Nhà đầu tư ký trước | NĐT | Mobile | FUND-04 | 1 chữ ký nhúng, nhãn chứng thư demo | D47, D49 |
| CTR-02 | Người vay ký cuối, biên nhận | Người vay | Mobile | CTR-01 | `EFFECTIVE`, 2 chữ ký, biên nhận chung | D45, D75 |
| CTR-03 | Hai nhà đầu tư ký nối tiếp | 2 NĐT + người vay | Mobile | FUND-05 | 3 chữ ký | D47 |
| CTR-04 | Hợp đồng kiểu cũ | Người vay | Mobile + công cụ | `FINORA_SIGNATURE_PADES_ENABLED=false` | Không có thẻ chữ ký | D49 |
| CTR-N01–N11 | Ký sai thứ tự, hash/phiên bản sai, từ chối, hết hạn ký, `409`/`503` SmartCA, gửi lại | Người vay, NĐT | Mobile, API | — | Không chữ ký thừa; hủy thì nhả tiền | D8, D73, B71, B72 |
| DISB-01 | Giải ngân, phát hành Note | Cả hai | Mobile, web | CTR-02 | +10.000.000 một lần, 10 Note | D52 |
| DISB-N01–N04 | Drill tắt Loan/Payment/Investment, saga cần sửa | — | Công cụ | `-DrillEnv` | Không trùng tiền, khoản vay, Note | Q13, D31 |
| REP-01 | Xem khoản vay và lịch | Người vay | Mobile | DISB-01 | "Lịch trả từng kỳ (6 kỳ)" | — |
| REP-02 | Trả kỳ trước hạn bị giữ | Người vay | Mobile | REP-01 | `COLLECTED` + ngày ghi nhận; −1.726.000 | D16 |
| REP-03 | Chặn báo giá khi đang giữ | Người vay | Mobile | REP-02 | `409 INSTALLMENT_PENDING_POSTING` | D22 |
| REP-04 | Hủy khoản giữ | Người vay | Mobile | REP-02 | `CANCELLED`, hoàn tiền | D16 |
| REP-05 | Ghi đúng ngày đến hạn | Cả hai | Mobile + công cụ | REP-04 | `COMPLETED`, NĐT +1.726.000 | D16, Q12 |
| REP-06 | Trả từ ngày đến hạn | Người vay | Mobile + công cụ | Khoản vay mới | Ghi ngay | D16 |
| REP-N01–N12 | Thiếu tiền, sai số tiền, số lẻ, trả hai lần, đang ghi core, Toxiproxy, PIN, số liệu cũ | Người vay, admin | Mobile, web, API | — | Trừ ví đúng một lần | B30, B32, B42, D28 |
| PRE-01 | Trả trước một phần | Người vay | Mobile | Không quá hạn, không có khoản giữ | −4.826.000; NĐT +4.806.000 | D10, D15 |
| PRE-02 | Tất toán sớm | Người vay | Mobile | — | −10.100.000; `SETTLED` | D15, G-POLICY |
| PRE-N01–N10 | Báo giá cũ/hết hạn, đang quá hạn, khoản giữ, số tiền, khoản vay core cũ | Người vay | Mobile, API | — | Không thu theo báo giá sai | B30, B35, D17, D27 |
| OVD-01 | DPD 10 | Cả hai | Mobile + công cụ | Khoản vay mới | Nhóm 2 | D25 |
| OVD-02 | Khắc phục toàn bộ | Cả hai | Mobile + công cụ | OVD-01 | DPD 0, nhóm 1 | D26 |
| OVD-03 | Nợ xấu DPD 91 | Cả hai | Mobile + công cụ | Khoản vay mới | `DEFAULTED`, nhóm 3 | D29 |
| OVD-04 | Trả kỳ cũ nhất | Cả hai | Mobile + công cụ | OVD-03 | Vẫn `DEFAULTED`, nhóm 3 | D26, D29, B36 |
| OVD-05 | Khắc phục từ nợ xấu | Cả hai | Mobile + công cụ | OVD-04 | `ACTIVE`, nhóm 1 | D26 |
| OVD-06 | DPD 181, 361, trả hết | Cả hai | Mobile + công cụ | OVD-05 | Nhóm 4, 5 rồi `SETTLED` | D25 |
| OVD-N01–N08 | Thiếu job EOD, số tiền sai, không tin hằng ngày, trả trước khi quá hạn | Người vay | Mobile, API | — | Không đổi nhóm sai | D25, D61, B31 |
| RSC-01 | Gửi đề nghị cơ cấu | Người vay | Mobile | Khoản vay `ACTIVE` | "Chờ thẩm định" | — |
| RSC-02 | Thẩm định | Admin | Web | RSC-01 | "Chờ nhà đầu tư đồng ý" | — |
| RSC-03 | Biểu quyết | NĐT | Mobile | RSC-02 | "Đã đồng ý" | D78 |
| RSC-04 | Lịch mới | Cả hai | Mobile + API | RSC-03 | 8 kỳ, đáo hạn +2 tháng, không phí | B37 |
| RSC-N01–N12 | Đề nghị trùng, form sai, admin/NĐT từ chối, điều khoản đổi, thiếu reason code, không có hạn biểu quyết | Người vay, admin, NĐT | Mobile, web, API | — | Lịch cũ giữ nguyên khi bị từ chối | P8 |
| WDR-01 | Chính sách rút | Mọi vai trò | Mobile | Ví có tiền | 50.000 tối thiểu, 50 triệu/ngày, miễn phí, nhãn demo | Q4 |
| WDR-02 | Rút thành công | Người vay | Mobile | WDR-01 | `COMPLETED` qua callback HMAC | D41, D43 |
| WDR-03 | Lịch sử rút | Người vay | Mobile | WDR-02 | Số tài khoản che | D44 |
| WDR-04 | Tài khoản `…0000` | Người vay | Mobile | — | `FAILED`, hoàn tiền | D43 |
| WDR-05 | Mất callback, hàng đợi admin | Admin | Web + công cụ | Tắt callback mock | Tự tra cứu, không chi lại | D42 |
| WDR-N01–N12 | Dưới tối thiểu, quá số dư, quá hạn mức cộng dồn, gửi lại, `429`, tên/số tài khoản sai | Mọi vai trò | Mobile, API | — | Ví chỉ trừ lệnh hợp lệ, một lần | D40, Q4 |
| INC-01 | Tab sự cố khi trống | Admin | Web | — | Năm bảng trống | W2D |
| INC-02 | Trả nợ mất phản hồi | Admin | Web + công cụ | REP-N07 | "Đối soát" → hoàn tất | B42 |
| INC-03 | Trả nợ mất request | Admin | Web + công cụ | REP-N08 | "Ghi lại vào hệ thống lõi" sau khoảng an toàn | B32, D28 |
| INC-04 | Giải ngân chờ kết quả | Admin | Web + công cụ | DISB-N02 | "Tra cứu lại" chỉ hẹn tra cứu | B09 |
| INC-05 | Saga cần ghi lại | Admin | Web | Saga `REPAIR_REQUIRED` | "Ghi lại" có ghi chú | W2D |
| INC-06 | Lệnh góp vốn chưa khớp ví | Admin | Web + công cụ | FUND-N19 | "Thử lại", giữ tiền một lần | D30 |
| INC-07 | Đối soát servicing | Admin | Web + công cụ | Fineract tắt rồi bật | "Đối soát lại" khớp | — |
| INC-08 | Quá hạn & thu hồi | Admin | Web + công cụ | OVD-01/03 | Ghi nhận thu hồi | — |
| INC-N01–N07 | Ghi lại sớm, thao tác sai trạng thái, form thu hồi | Admin | Web, API | — | `409` đúng mã, không ghi hai lần | B32 |
| BC-01 | Tra cứu proof | Admin | Web | DISB-01 | Mỗi mốc một proof, nhãn MOCK | D71 |
| BC-02 | Chi tiết proof MOCK | Admin | Web | BC-01 | Cảnh báo không trên Fabric | D54 |
| BC-03 | Kiểm tra không artifact | Admin | Web | BC-01 | "MOCK, không có trên Fabric" | D54 |
| BC-04 | Kiểm tra có artifact | Admin | Web + công cụ | Kafka console | "Khớp hash đã neo" | D52 |
| BC-05 | Neo biên nhận hợp đồng | Admin | Web + công cụ | CTR-02 | Hash biên nhận khớp | D75 |
| BC-06 | Ledger Fabric | Admin | Web + công cụ | `-WithFabric` | "Trên Fabric", "Khớp sổ cái" | D50–D54 |
| BC-N01–N11 | JSON sai, artifact bị sửa, tra cứu sai, thử lại `DEAD_LETTER`, peer tắt, MOCK không như on-chain | Admin | Web, API | — | Phát hiện sửa đổi, không nhầm MOCK | B50–B53 |
| NTF-01 | Hộp thư, đã đọc | Mọi vai trò | Mobile | Có tin | Badge khớp số chưa đọc | W3F |
| NTF-02 | Tin theo mốc | Cả hai | Mobile | Các luồng 4.4–4.11 | Mỗi mốc một tin | D60, D61 |
| NTF-03 | Cài đặt thông báo | Mọi vai trò | Mobile | — | 8 nhóm, lưu từng nhóm | D63, D74 |
| NTF-04 | Push giả lập `LOG_MOCK` | Mọi vai trò | API + web | Token Expo giả | Lần gửi `SENT`, nội dung không số | D62, D63 |
| NTF-05 | Push thật | Mọi vai trò | Development build | EAS projectId | Nhận push câu chung | D62 |
| NTF-N01–N14 | Không projectId, Expo Go, từ chối quyền, tắt nhóm, giờ nghỉ, chuyển chủ token, đăng xuất, tin hằng ngày, lỗi mạng | Mọi vai trò | Mobile, API | — | Hộp thư luôn có tin, push đúng chính sách | D61–D64, B60 |
| CIC-01 | Mapping CCCD tổng hợp | Người kiểm | API | `-WithLocalCic` | `201`, CCCD đã che | D65, B70 |
| CIC-02 | Hồ sơ CIC theo mốc | Người kiểm | API | 4.9 | Nhóm hiện tại/cao nhất, ngày phục hồi | D65, D69 |
| CIC-N01–N07 | Sai khóa, xung đột mapping, event trùng, event trước mapping | Người kiểm | API, công cụ | — | Không lộ CCCD, không phiên bản thừa | D66–D69 |
| SEC-01 | Dữ liệu riêng từng người | Người dùng B | Mobile | Có A, B | Không thấy dữ liệu của A | R8A |
| SEC-02 | Đổi ID | Người dùng B | API | SEC-01 | `403`/`404` đúng mã | R8A |
| SEC-03 | Token người dùng gọi API admin | Người vay, NĐT | API | — | `403` | R8A |
| SEC-04 | Gọi không token | — | API | — | `401`; Gateway chuyển đủ body | B43 |
| SEC-05 | Token PIN gắn một thao tác | Người vay | API | — | `403 PIN_TOKEN_INVALID` | D38 |
| SEC-06 | Người dùng mở web quản trị | Người vay, NĐT | Web | — | "Không có quyền truy cập" | — |
| SEC-07 | Người vay đặt lệnh | Người vay | Mobile | — | `403`, không có lệnh | — |
| SEC-08 | Hồ sơ chưa lên sàn | NĐT | Mobile/API | — | `404` | `LEGAL-DISCLOSURE-02` |
| SEC-N01–N07 | Giới hạn tần suất nạp/rút/báo giá/thiết bị, push màn khóa, số tài khoản che | Mọi vai trò | Mobile, API | — | `429` có `Retry-After` | D40, D44, D63 |
| OPS-01 | Health trước đợt kiểm | Vận hành | Công cụ | Stack chạy | Readiness `UP` | D56 |
| OPS-02 | Job cuối ngày Fineract | Vận hành | Công cụ | — | `fineractEndOfDay` `UP` | D57 |
| OPS-03 | Chi tiết chỉ admin | Vận hành | Công cụ | — | Metric `401/403/200` | B56 |
| OPS-N01–N04 | Kafka tắt, AI tắt, Investment tắt, lỗi giả lập mobile | — | Công cụ, mobile | — | Readiness đúng, UI báo lỗi có "Thử lại" | D56 |

## 4. Ca kiểm thử chi tiết

Điều kiện chung cho mọi ca: mục 2 đã xong; `-Action Status` có đủ `loan`, `payment`, `investment`, `notification`,
`blockchain`, `gateway` với `clock=thật`, `db=local`; web mở ở `http://localhost:5173`; mobile trỏ đúng IP.

### 4.1 Đăng ký, OTP, đăng nhập, PIN

**AUTH-01 [UI] Đăng ký người vay trên mobile.**

1. Màn đăng nhập ("FINORA", "Cho vay ngang hàng minh bạch") → "Tạo tài khoản" → màn "Tạo tài khoản" / "Miễn phí · chỉ mất 3
   phút": điền "Email", "Số điện thoại", "Mật khẩu" (trợ giúp "Tối thiểu 8 ký tự"), "Nhập lại mật khẩu"; tick "Tôi đồng ý với
   Điều khoản sử dụng và Chính sách bảo vệ dữ liệu cá nhân." → "Tạo tài khoản → nhận mã".
2. Màn "Xác thực email" / "Mã 6 số đã gửi tới {email đã che}", ghi chú "Mã có hiệu lực {N} phút…", đồng hồ "Gửi lại sau 01:00"
   kèm " · sai 5 lần phải yêu cầu mã mới".
3. Lấy OTP bằng T1 (mục 2.6), nhập 6 số → "Xác nhận" → vào thẳng app (đã đăng nhập).

Kết quả: tab "Hồ sơ" có huy hiệu "CHƯA ĐỊNH DANH"; "Thông tin tài khoản" ghi "Vai trò" = "Người vay". Backend:
`POST /api/v1/auth/register` `202`, `POST /api/v1/auth/verify-registration` `201`.

**AUTH-02 [UI] Tạo PIN giao dịch.** Tab "Hồ sơ" → dòng "Tạo mã PIN" / "Cần có trước khi thanh toán, đầu tư, ký hợp đồng" →
bảng "Tạo mã PIN" ("Mã PIN gồm 6 chữ số, dùng để xác nhận các giao dịch.") → nhập 6 số (tự gửi khi đủ) → "Nhập lại mã PIN" →
hộp thoại "Mã PIN giao dịch" / "Đã tạo mã PIN. Từ giờ các giao dịch sẽ cần mã này để xác nhận." Dòng đổi thành "Đổi mã PIN" /
"Mã 6 số xác nhận giao dịch". Nếu chưa có PIN mà làm thao tác cần PIN, bảng tự chuyển sang "Tạo mã PIN" với câu "Giao dịch này
cần mã PIN 6 số. Tạo mã ngay để tiếp tục."

**AUTH-03 [UI + công cụ] Nhà đầu tư.** Đăng ký bằng API (mục 2.6), đăng nhập mobile: "Thông tin tài khoản" ghi "Nhà đầu tư";
tab "Ví" → "Lịch sử ví" có khối "Dành cho nhà đầu tư". Tạo PIN như AUTH-02.

**AUTH-04 [UI] Đăng nhập và đăng xuất mobile.** "Email", "Mật khẩu", "Đăng nhập". Đăng xuất: tab "Hồ sơ" → "Đăng xuất" →
hộp thoại "Đăng xuất?" / "Bạn sẽ cần đăng nhập lại ở lần mở ứng dụng sau." → "Đăng xuất" → về màn đăng nhập (app gỡ thiết bị
push và thu hồi refresh token, best effort).

**AUTH-05 [UI] Đăng nhập web quản trị.** `http://localhost:5173/login`: "Đăng nhập" / "Dùng tài khoản quản trị viên được cấp.",
"Email", "Mật khẩu"; khối "Tài khoản dùng thử" (bấm chip chỉ điền sẵn, không gửi) → "Đăng nhập" → `/dashboard` "Tổng quan";
góc phải ghi "Quản trị viên"; sidebar: "Tổng quan", "Tín dụng" ("Quản lý khoản vay", "Vận hành khoản vay"), "Đầu tư" ("Gọi
vốn & Notes", "Chợ thứ cấp Notes"), "Sản phẩm" ("Sản phẩm vay"), "Người dùng" ("Khách hàng eKYC", "Người dùng & phân
quyền"), "Kiểm toán" ("Bằng chứng blockchain"), "Cấu hình" ("Chính sách đánh giá AI", "Chấm điểm & giải thích AI"). Web không
tự làm mới dữ liệu: sau mỗi việc chạy nền (chấm lại, đồng bộ, proof) bấm "Làm mới" hoặc tải lại trang.

**AUTH-06 [UI + công cụ] Quên mật khẩu (mobile).** "Quên mật khẩu?" → nhập email → "Gửi mã xác thực" (luôn báo đã gửi, kể cả
email không tồn tại) → đọc mã: Git Bash
`docker exec finora-user-redis sh -c 'if [ -z "${USER_REDIS_PASSWORD:+x}" ]; then redis-cli --raw KEYS "reset_otp:*"; else REDISCLI_AUTH="$USER_REDIS_PASSWORD" redis-cli --raw KEYS "reset_otp:*"; fi'`
rồi `GET` đúng key (`reset_otp:<userId>`, sống 5 phút) bằng lệnh mục 2.6 → "Nhập mã xác thực" → "Tiếp tục" → "Mật khẩu mới",
"Nhập lại mật khẩu mới" → "Đổi mật khẩu" → hộp thoại "Đã đổi mật khẩu" / "Đăng nhập lại bằng mật khẩu mới.".

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả mong đợi |
|---|---|---|---|
| AUTH-N01 | [UI] | Nhập sai OTP | "Mã không hợp lệ hoặc đã hết hạn"; sai 5 lần thì mã bị xóa, phải "Gửi lại mã" |
| AUTH-N02 | [UI] | Chờ quá 5 phút rồi nhập mã; hoặc "Gửi lại mã" ở phút thứ 4 rồi nhập mã mới ở phút thứ 6 | "Phiên đăng ký đã hết hạn, vui lòng đăng ký lại" (phiên đăng ký sống 5 phút tính từ lúc tạo, gửi lại mã không gia hạn phiên); mã hết hạn trong phiên còn sống: "Mã không hợp lệ hoặc đã hết hạn" |
| AUTH-N03 | [UI] | Bấm "Gửi lại mã" (chỉ bật sau 60 giây, đếm trên máy); lần gửi thứ 4 trong một giờ cho cùng email | "Bạn đã yêu cầu mã quá nhiều lần, vui lòng thử lại sau một giờ" (`429`); giới hạn dùng chung với quên mật khẩu |
| AUTH-N04 | [UI] | Đăng ký lại email hoặc số điện thoại đã có | "Email đã được sử dụng" / "Số điện thoại này đã được đăng ký trong hệ thống" |
| AUTH-N05 | [UI] | Form đăng ký sai | "Email chưa đúng định dạng.", "Mật khẩu tối thiểu 8 ký tự.", "Hai lần nhập mật khẩu chưa khớp.", "Số điện thoại gồm 10 chữ số và bắt đầu bằng 0.", "Cần đồng ý điều khoản trước khi tạo tài khoản." |
| AUTH-N06 | [UI] | Sai mật khẩu (dùng tài khoản thử, không dùng admin demo); lặp 6 lần trong 5 phút; rồi nhập đúng | Lần 1–5: "Email hoặc mật khẩu không đúng" (`401`); từ lần 6, kể cả mật khẩu đúng: "Tài khoản tạm khóa, vui lòng thử lại sau 15 phút" (`429`, đếm theo email trong Redis User). Keycloak còn chống dò riêng (5 lần, chờ tăng từ 60 giây, tối đa 15 phút, D40): mã HTTP Keycloak trả khi khóa chưa được kiểm, ghi lại câu thực tế. Web hiện nguyên câu backend; body rỗng thì "Lỗi hệ thống (429)" |
| AUTH-N07 | [UI] | Tạo PIN `111111`, `123456`; rồi `123123` | Trên máy: "Mã PIN quá dễ đoán. Tránh các số giống nhau hoặc dãy liên tiếp như 123456."; `123123` chỉ bị chặn sau bước nhập lại: "Mã PIN quá dễ đoán, tránh dãy số liên tiếp hoặc lặp lại"; nhập lại khác: "Mã PIN nhập lại không khớp. Vui lòng tạo lại." |
| AUTH-N08 | [UI] | Nhập sai PIN ở một thao tác cần PIN; sai 5 lần | "Mã PIN không đúng, bạn còn {N} lần thử"; lần 5: "Mã PIN tạm khoá do nhập sai nhiều lần. Thử lại sau {M} phút hoặc chọn Quên mã PIN" (`423`, khóa 15 phút); mở lại bảng: "Mã PIN đang tạm khóa đến {ngày giờ}…"; dòng PIN ở "Hồ sơ": "Đang tạm khóa do nhập sai nhiều lần". Không có tiền di chuyển |
| AUTH-N09 | [UI] | "Quên mã PIN?" → nhập sai mật khẩu; đổi PIN trùng PIN cũ | "Mật khẩu không đúng"; "Mã PIN mới phải khác mã PIN hiện tại" |
| AUTH-N10 | [API] | Lấy token PIN rồi chờ quá 3 phút (`FINORA_PIN_TOKEN_TTL=PT3M`) mới gửi lệnh | `403 PIN_TOKEN_INVALID` "Xác nhận mã PIN đã hết hạn hoặc không hợp lệ, vui lòng nhập lại"; không trừ tiền |
| AUTH-N11 | [UI] | Admin khóa tài khoản đang đăng nhập trên mobile (web "Người dùng & phân quyền" → "Khóa tài khoản": "Mọi phiên đăng nhập của tài khoản bị đăng xuất ngay…"); dùng app tiếp | Lần làm mới phiên tiếp theo thất bại → app về màn đăng nhập **không có thông báo**. Đăng nhập lại: theo code nhiều khả năng là "Lỗi hệ thống xác thực, vui lòng thử lại sau" (`500`, Keycloak báo tài khoản bị vô hiệu): câu gây hiểu nhầm, ghi lại câu thực tế. "Mở khóa tài khoản" thì đăng nhập lại được |
| AUTH-N12 | [UI] | Web: để rảnh hơn 5 phút (cookie access 5 phút) rồi bấm một trang; mobile/web: đóng hoặc để rảnh hơn 30 phút (phiên Keycloak hết khi rảnh 30 phút) | Sau 5 phút: web tự gọi `POST /api/v1/auth/refresh` và chạy tiếp. Sau 30 phút: refresh bị từ chối ("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại", `401`) và app/web về màn đăng nhập **không có thông báo** |
| AUTH-N13 | [UI] | Đăng nhập web bằng người vay hoặc nhà đầu tư | Đăng nhập "thành công" nhưng mọi trang hiện "Không có quyền truy cập" / "Tài khoản {email} là người vay. Trang này chỉ dành cho quản trị viên." và link "Đăng nhập bằng tài khoản khác"; không có sidebar |
| AUTH-N14 | [UI] | Mở `http://localhost:5173/loans?status=APPROVED` khi chưa đăng nhập, đăng nhập | Quay lại `/loans` nhưng mất query string (ghi nhận, P3) |

### 4.2 Hồ sơ vay: xem trước, nộp, AI chấm điểm, hạn mức dư nợ

Điều kiện: Product `ACTIVE` lãi cố định 12% (mục 2.7); người vay mới đã tạo PIN. Lối vào: "Trang chủ" → nút nhanh "Vay", hoặc
tab "Sàn" → chân trang "Sản phẩm vay". Chuyển sang tab khác giữa chừng làm mất dữ liệu đang nhập (tab bị dựng lại): không
phải lỗi.

**LOAN-01 [UI] Xem sản phẩm và lịch trả dự kiến.**

1. "Sản phẩm vay": thẻ sản phẩm có "Khung lãi suất" ("12% – 12%/năm"), "Khoản vay", "Kỳ hạn", "Kiểu tính lãi" = "Trả góp đều".
2. Chọn sản phẩm → "Nhập khoản vay" (1/3): thẻ "SẢN PHẨM VAY" với "Lãi suất cơ sở", "Khung có thể áp dụng", "Kỳ hạn tối đa",
   "Hạn mức tối đa"; "Số tiền muốn vay" = 10.000.000; "Kỳ hạn vay" = 6; "Ngày giải ngân dự kiến *" = hôm nay → "Tiếp tục".
3. "Xác nhận khoản vay" (2/3) "Xem lịch trả dự kiến": "Tổng số tiền vay" 10.000.000, "6 tháng · lãi suất cơ sở 12,00%/năm",
   "Kỳ đầu trả" 1.726.000, "Tổng trả" 10.354.000, "Tổng lãi" 354.000; "Lịch trả từng kỳ" có "Kỳ 1"…"Kỳ 6" với "Tiền gốc",
   "Tiền lãi", "Phí", "Phạt dự kiến", "Dư nợ sau kỳ". Lịch do backend/Fineract tính, app không tự tính.

**LOAN-02 [UI] Nộp hồ sơ, AI chấm điểm.**

1. Bước 3/3 "Xác nhận khoản vay": tóm tắt chỉ đọc ("Sản phẩm", "Số tiền vay", "Kỳ hạn", "Lãi suất cơ sở ban đầu", "Ngày giải
   ngân dự kiến").
2. "Mục đích vay" (danh sách từ backend, ví dụ mục đích giáo dục như harness); "Phương án sử dụng vốn" (bắt buộc với một số
   mục đích, tối đa 500 ký tự); "Thu nhập hàng tháng" 30.000.000; "Nghĩa vụ nợ hàng tháng" 3.000.000; "Thâm niên làm việc
   (tháng)" 48; "Trình độ học vấn" = "Đại học"; "Tình trạng nhà ở" = "Trả góp".
3. Tick câu công bố lãi suất "Tôi đã xem lãi suất cơ sở, khung lãi suất, phí và lịch trả ban đầu. Tôi đồng ý hồ sơ tự tiếp tục
   nếu điều khoản cuối không bất lợi hơn; nếu lãi, phí hoặc nghĩa vụ trả tăng, FINORA phải hỏi lại trước khi lập hợp đồng."
4. "Nộp hồ sơ" → hộp thoại "Đã nộp hồ sơ" / "Mã LA-…. Hệ thống đang kiểm tra điều kiện và đánh giá hồ sơ." → "Xem hồ sơ".
5. "Chi tiết hồ sơ vay": trạng thái đi "Đã nộp" → "Đang kiểm tra điều kiện" → "Đang chấm điểm" → "Chờ duyệt" (kéo để làm mới;
   app không tự hỏi lại).

Kết quả mong đợi trên stack E2E: hồ sơ dừng ở "Chờ duyệt" (`PENDING_REVIEW`). AI không tra CIC (người vay không có CCCD) nên
hai luật dùng dữ liệu CIC lấy điểm trung tính; với dữ liệu mẫu trên, điểm khoảng 66,79 nằm giữa ngưỡng tự từ chối (< 65) và tự
duyệt (≥ 75). Evidence: assessment `SUCCEEDED`, model `17.0.0`, hạng C, khuyến nghị `PENDING_REVIEW`. Backend: `201`,
`applicationNumber` dạng `LA-…`, snapshot KYC nguồn `MOCK_USER_PROFILE`. Số tiền vay phải chia hết mệnh giá Note 1.000.000
(LOAN-N11).

**LOAN-03 [UI] Điều khoản cuối bất lợi hơn thì người vay phải chấp nhận lại.** Tạo Product khung lãi 10% – 12% – 15% (ADM-05),
nộp hồ sơ như LOAN-02, admin duyệt (ADM-02). Hạng C cộng 0,5 điểm % (policy demo `LEGAL-AI-01`) nên lãi cuối 12,5% > 12%:
"Chi tiết hồ sơ vay" hiện thẻ "Cần quyết định của bạn" / "Điều khoản cuối bất lợi hơn lúc nộp hồ sơ" / "Hạn phản hồi: {ngày
giờ}", thẻ "Kết quả điều khoản" ("Lãi suất khi đăng ký" → "Lãi suất được duyệt", "Đã cập nhật").
- "Chấp nhận điều khoản mới" → hộp thoại "Đã chấp nhận điều khoản" → hồ sơ lên sàn.
- Hoặc "Từ chối điều khoản" → chọn lý do ("Không đồng ý điều khoản" / "Thay đổi nhu cầu" / "Lý do khác") → "Xác nhận từ chối" →
  "Đã từ chối điều khoản"; không lập hợp đồng. Form ghi tiêu đề "Từ chối hợp đồng này" (lỗi câu chữ, mục 7.1).
- Web (trang thẩm định) ghi "Đang chờ người vay xác nhận điều khoản cuối trước khi tạo hợp đồng." kèm hạn.

**LOAN-04 [UI] Rút hồ sơ (chỉ khi còn "Đã nộp").** "Chi tiết hồ sơ vay" → thẻ "Không còn nhu cầu vay?" → "Rút hồ sơ vay" → (tùy
chọn) "Lý do rút hồ sơ" → "Xác nhận rút hồ sơ" → hộp thoại "Rút hồ sơ vay?" → "Rút hồ sơ" → "Đã rút". Backend chỉ cho rút khi
hồ sơ còn `SUBMITTED`; worker chuyển hồ sơ sang bước sau trong khoảng 5 giây nên phải bấm ngay sau khi nộp. Rút muộn hơn: câu
lỗi `409 INVALID_APPLICATION_TRANSITION` (ghi lại câu hiển thị thực tế), hồ sơ giữ nguyên.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả trên UI | Backend |
|---|---|---|---|---|
| LOAN-N01 | [UI] | Số tiền ngoài khung Product (ví dụ 500.000 hoặc 150.000.000) | "Số tiền phải nằm trong khoảng {min – max}."; nút kỳ hạn không vượt khung | Không gọi API. Qua API: `400 LOAN_TERMS_OUT_OF_RANGE` "Số tiền yêu cầu nằm ngoài giới hạn của sản phẩm" / "Kỳ hạn yêu cầu nằm ngoài giới hạn của sản phẩm" |
| LOAN-N02 | [UI] | Ngày giải ngân dự kiến ở quá khứ | "Ngày giải ngân dự kiến phải là hôm nay hoặc một ngày trong tương lai." | Không gọi API |
| LOAN-N03 | [UI] | Bỏ trống mục đích, thu nhập, nhà ở; phương án sử dụng vốn trên 500 ký tự (ô không chặn khi gõ); bỏ tick | "Chọn mục đích vay.", "Mục đích này cần mô tả phương án sử dụng vốn.", "Tối đa 500 ký tự.", "Nhập thu nhập hàng tháng.", "Chọn tình trạng nhà ở.", "Cần xác nhận đã xem điều khoản và lịch trả dự kiến." | Không gọi API; qua API: `400 VALIDATION_FAILED` (`purposeDetail` > 500); mục đích "khác" không có mô tả: `400 LOAN_PURPOSE_DETAIL_REQUIRED` "Phải mô tả chi tiết khi chọn mục đích khác" |
| LOAN-N04 | [UI] | Hạn mức dư nợ (D5): nộp hồ sơ 60.000.000 (để ở "Chờ duyệt"), rồi nộp hồ sơ 50.000.000; sau đó nộp 40.000.000 | Lần 50 triệu: khung đỏ "Tổng dư nợ và hồ sơ đang xử lý vượt hạn mức 100.000.000 đồng; số tiền còn có thể đề nghị: 40.000.000 đồng"; lần 40 triệu: nộp được (đúng bằng hạn mức là qua) | `422 BORROWER_EXPOSURE_LIMIT_EXCEEDED`, không tạo hồ sơ. Admin từ chối hồ sơ 60 triệu (ADM-03) thì hồ sơ đó thôi tính. Hạn mức: `LOAN_BORROWER_MAX_OUTSTANDING` (mặc định 100.000.000), policy demo |
| LOAN-N05 | [UI + công cụ] | Cổng eKYC: PowerShell `powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Restart -Services loan -BorrowerProfileProvider user`; người vay mới (chưa eKYC) nộp hồ sơ; xong Restart lại không có cờ (về `mock`) | Khung đỏ "Bạn cần hoàn tất xác minh eKYC trước khi nộp hồ sơ vay" và link "Xác minh eKYC ngay" (**không** bấm tiếp, không chụp CCCD); danh sách "Hồ sơ vay" vẫn trống | `409 BORROWER_KYC_REQUIRED` (check harness `kyc.unverified-submit-rejected`) |
| LOAN-N06 | [UI + công cụ] | AI tắt: `docker stop finora-ai`, nộp hồ sơ, chờ khoảng 2 phút, `docker start finora-ai` | Trong lúc thử lại: mobile "Chờ chấm điểm lại", web "Chờ chấm lại". Hết lượt: mobile "Chờ duyệt"; web hiện "Yêu cầu chấm lại", nút duyệt/từ chối khóa ("Cần có kết quả chấm điểm trước khi duyệt hoặc từ chối.") | Lỗi `AI_UNAVAILABLE` (retry được): tối đa 3 lần (`AI_MAX_ATTEMPTS`), lùi 30 giây × số lần; trong lúc chờ assessment `RETRY_PENDING`, hồ sơ `SCORING_RETRY_PENDING`; hết lượt: assessment `FAILED`, hồ sơ `PENDING_REVIEW`, không có điểm mặc định. Bật AI rồi chấm lại ở ADM-N02 |
| LOAN-N07 | [API] | Gửi lại `POST :8081/api/v1/loan-applications` cùng `Idempotency-Key`: cùng nội dung, rồi khác số tiền | — | Cùng nội dung: trả lại đúng `applicationNumber`; khác nội dung: `409 IDEMPOTENCY_KEY_REUSED` "Idempotency-Key đã được dùng cho nội dung hồ sơ khác" |
| LOAN-N08 | [UI] | Đặt `EXPO_PUBLIC_PRICING_DISCLOSURE_VERSION=RATE_DISCLOSURE_V1`, `npx expo start -c`, nộp hồ sơ; xong trả lại | "Nội dung công bố lãi suất đã thay đổi, vui lòng xem lại" | `409 PRICING_DISCLOSURE_OUTDATED`, không tạo hồ sơ; gửi `pricingDisclosureAccepted=false` qua API: `400 PRICING_DISCLOSURE_NOT_ACCEPTED` |
| LOAN-N09 | [UI] | Không còn Product `ACTIVE` (admin "Tạm dừng") | "Chưa có sản phẩm nào đang mở" | Qua API nộp vào Product đó: `409 LOAN_PRODUCT_NOT_AVAILABLE` "Sản phẩm vay hiện không sẵn sàng nhận hồ sơ" |
| LOAN-N10 | [UI] | Admin từ chối (ADM-03) | Trạng thái "Không được duyệt" | Hồ sơ `REJECTED` không tính vào hạn mức dư nợ |
| LOAN-N11 | [UI] | Nộp số tiền không chia hết mệnh giá Note (ví dụ 10.500.000), admin duyệt | Hồ sơ được duyệt nhưng không bao giờ lên "Sàn"; mobile người vay đứng ở trạng thái gọi vốn | Loan không kiểm bội số mệnh giá; Investment từ chối tạo listing (`LISTING_TARGET_NOT_DIVISIBLE`), event vào DLT, funding kẹt `REQUESTED`. Ghi thành defect đề xuất (mục 7.1) |
| LOAN-N12 | [UI] | Khai thông tin khác dữ liệu mẫu (ví dụ thu nhập thấp, nợ hằng tháng cao) | Tùy điểm AI: ≥ 75 tự "Đã duyệt" (không qua admin), < 65 tự "Không được duyệt", ở giữa "Chờ duyệt"; hạng và điểm hiện ở web | `AI_POLICY_AUTO_APPROVED` / `AI_POLICY_AUTO_REJECTED` / `AI_POLICY_REQUIRES_REVIEW` trong lịch sử hồ sơ. Không đoán trước kết quả: ghi lại điểm và quyết định |

### 4.3 Admin: thẩm định, duyệt, từ chối, sản phẩm (web)

Điều kiện: đăng nhập web bằng admin (AUTH-05); có hồ sơ "Chờ duyệt" từ LOAN-02.

**ADM-01 [UI] Danh sách và trang thẩm định.**

1. Sidebar "Quản lý khoản vay" → trang "Quản lý hồ sơ vay": thẻ "Giá trị hồ sơ nộp 30 ngày", "Đang chờ thẩm định"; tab "Tất
   cả", "Chờ thẩm định", "Đã duyệt", "Đã từ chối"; bảng "Hồ sơ", "Trạng thái", "Người vay", "Số tiền vay", "Điểm".
2. Tab "Chờ thẩm định" → nút "Thẩm định" ở dòng hồ sơ `LA-…` → trang thẩm định: thanh "Chờ thẩm định {i}/{N}", tab "Thẩm
   định", "Chi tiết chấm điểm", "Lịch sử xử lý".
3. Tab "Thẩm định": thẻ "Khả năng trả nợ" (thu nhập 30.000.000, nợ 3.000.000, DTI), "Lịch sử tín dụng", "Nhân thân và điều kiện
   vay" ("Nguồn hồ sơ" là hồ sơ giả lập, nhãn cụ thể cần kiểm lại trên web), "Khoản vay" với bảng so sánh "Khi nộp hồ sơ" / "Sau định giá" / "Chênh lệch" (Product
   lãi cố định: "Giữ nguyên lãi suất", các dòng "Không đổi") và "Lịch trả 6 kỳ".
4. "Kết quả chấm điểm": "{điểm} điểm đánh giá", nhãn "Cần chuyên viên thẩm định", "Xác suất vỡ nợ (PD)", "Hạn mức gợi ý".
5. Tab "Chi tiết chấm điểm": "Bảng luật đã chấm" (luật thiếu dữ liệu CIC ghi "Thiếu dữ liệu, dùng điểm trung tính"), "Yếu tố ảnh
   hưởng theo mô hình", "Thông điệp gửi người vay". Hai dòng "Điểm tín dụng CIC", "Tra cứu CIC trong 6 tháng" ở tab "Thẩm định"
   chỉ hiện sau khi đã mở tab này.

**ADM-02 [UI] Duyệt hồ sơ.** Khay quyết định: ghi chú "Duyệt theo điều khoản sau định giá…" → "Duyệt hồ sơ" (không có hộp thoại
xác nhận; web luôn gửi lý do `POLICY_APPROVED`, không có ô hạn hợp đồng) → banner kết quả (câu chính xác cần kiểm lại trên
web), trạng thái "Đã duyệt", câu "Tự tiếp
tục vì điều khoản cuối không bất lợi hơn…". Mobile người vay: hồ sơ sang trạng thái gọi vốn ("Đang gọi vốn"); nhà đầu tư thấy
listing trên "Sàn". Backend: `POST /api/v1/admin/loan-applications/{n}/approve` `200`, điều khoản `AUTO_AUTHORIZED`, listing
`OPEN`, target 10.000.000, lãi 12%.

**ADM-03 [UI] Từ chối có lý do.** Trên một hồ sơ "Chờ duyệt" khác: "Từ chối" → form "Lý do từ chối" ("Khả năng trả nợ chưa
đạt", "eKYC không đủ điều kiện", "Thông tin khai báo không nhất quán", "Không đạt chính sách", "Lý do thẩm định khác"), "Giải
thích thêm" (không bắt buộc, tối đa 1000 ký tự) → "Xác nhận từ chối" → "Đã từ chối hồ sơ."; tab "Lịch sử xử lý" có dòng chuyển
trạng thái. Mobile: "Không được duyệt".

**ADM-04 [UI] Chấm điểm thử AI (không lưu).** Sidebar "Chấm điểm & giải thích AI" → "Kịch bản mẫu" (ví dụ "Hồ sơ sạch", "Nợ xấu
nhóm 5") chấm ngay → "Quyết định", "Hạng tín dụng", "Điểm tổng hợp", "Xác suất vỡ nợ (PD)", "Điểm luật", "Phiên bản mô hình …".
Để trống "Số CCCD" ("bỏ trống thì không tra CIC"): AI tra CIC theo `CIC_SERVICE_URL` của AI (mặc định bản Vercel công khai),
nên không nhập CCCD nào, kể cả số giả (B70).

**ADM-05 [UI] Tạo và mở bán Product.** Sidebar "Sản phẩm vay" → "Tạo sản phẩm" → modal "Tạo sản phẩm vay": "Tên sản phẩm *",
"Mã sản phẩm *" (tự viết hoa), "Hạn mức tối thiểu *"/"tối đa *", "Kỳ hạn tối thiểu *"/"tối đa *", lãi "Tối thiểu *", "Cơ sở *",
"Tối đa *", "Cách trả nợ" = "Khoản trả đều" → "Tạo và đồng bộ" → toast "Đã tạo sản phẩm và đồng bộ sang Fineract." → menu "⋯"
→ "Kích hoạt" → "Đã kích hoạt, khách hàng có thể chọn sản phẩm này." Mobile "Sản phẩm vay" thấy sản phẩm. "Tạm dừng" thì
mobile không còn thấy. Không có màn sửa Product.

**ADM-06 [UI] Xem chính sách AI (chỉ xem).** "Chính sách đánh giá AI": "Trọng số và ngưỡng duyệt", "Trần theo quy định pháp
luật" ("Chỉ đọc"), "Phân hạng và hạn mức gợi ý", "Bộ luật chấm điểm rủi ro". Không bấm "Lưu thay đổi"/"Lưu bộ luật" trên stack
dùng chung container AI: đổi luật làm đổi điểm của mọi hồ sơ sau đó.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả mong đợi |
|---|---|---|---|
| ADM-N01 | [UI] | Mở cùng một hồ sơ ở hai tab trình duyệt; duyệt ở tab 1; ở tab 2 bấm "Duyệt hồ sơ" hoặc "Từ chối" | Tab 2: khối lỗi "Hồ sơ đã được cập nhật bởi yêu cầu khác" (`409 LOAN_APPLICATION_VERSION_CONFLICT`, kiểm phiên bản trước trạng thái) kèm "mã truy vết" và nút "Tải lại hồ sơ"; không có quyết định thứ hai |
| ADM-N02 | [UI + công cụ] | Hồ sơ có assessment `FAILED` (LOAN-N06, AI đã bật lại) | Nút duyệt/từ chối khóa: "Cần có kết quả chấm điểm trước khi duyệt hoặc từ chối."; "Yêu cầu chấm lại" → "Đã gửi yêu cầu chấm lại. Kết quả sẽ cập nhật khi hệ thống chấm xong." → tải lại trang: có kết quả. Backend `POST /api/v1/admin/loan-applications/{n}/scoring-retry` `202`; mỗi lần chấm lại thủ công chỉ một lần gọi AI (AI còn lỗi thì về `FAILED` ngay). Web trước `554522e` gửi version của lần chấm thay vì version hồ sơ nên trả `409 LOAN_APPLICATION_VERSION_CONFLICT` "Hồ sơ đã được cập nhật bởi yêu cầu khác" (B84); còn gặp là web chưa cập nhật |
| ADM-N02b | [UI + công cụ] | Bấm "Yêu cầu chấm lại" khi assessment còn `RETRY_PENDING` (AI đang tắt, chưa hết lượt) | Nút hiện nhưng khóa, rê chuột thấy "Hệ thống đang tự chấm lại, chưa cần yêu cầu thêm." (B83). Lần chấm `FAILED` mà hồ sơ không còn chờ thẩm định: "Chỉ hồ sơ chờ thẩm định mới yêu cầu chấm lại được." Gọi API vẫn trả `409 CREDIT_ASSESSMENT_RETRY_NOT_ALLOWED` "Chỉ assessment FAILED mới được retry thủ công" |
| ADM-N02c | [API] | Duyệt hồ sơ có assessment `FAILED`; từ chối hồ sơ có assessment `FAILED` | Duyệt: `409 ASSESSMENT_NOT_ELIGIBLE_FOR_DECISION`. Từ chối: backend cho phép, nhưng web khóa nút từ chối khi chưa có kết quả chấm (UI chặt hơn backend) |
| ADM-N03 | [UI] | Mở trang thẩm định của hồ sơ đã duyệt/đã từ chối/đã rút | Không có khay quyết định; câu "Hồ sơ đang ở bước {x}. Chỉ hồ sơ chờ thẩm định mới duyệt hoặc từ chối được." hoặc "Người vay đã rút hồ sơ." |
| ADM-N04 | [API] | Gửi lại lệnh duyệt cùng `Idempotency-Key` | `200`, hồ sơ vẫn `APPROVED`, không có quyết định thứ hai (`decision.idempotent-replay`) |
| ADM-N05 | [UI] | Modal Product: lãi tối đa 21%, thứ tự lãi sai, kỳ hạn tối đa 25, mã có dấu cách đầu | "Lãi suất tối đa không được vượt quá 20%/năm.", "Lãi suất phải theo thứ tự: tối thiểu ≤ cơ sở ≤ tối đa.", "Kỳ hạn tối đa của FINORA hiện không vượt quá 24 tháng.", "Mã phải bắt đầu bằng chữ, chỉ chứa chữ, số và dấu _, dài 3 đến 50 ký tự." (`LEGAL-RATE-01`, `LEGAL-TERM-01`) |
| ADM-N06 | [UI] | Product chưa đồng bộ: menu "⋯" → "Kích hoạt" | Mục bị khóa, tooltip "Cần đồng bộ sang Fineract thành công trước khi kích hoạt" |
| ADM-N07 | [UI + công cụ] | `docker stop finora-fineract`, tạo Product; `docker start finora-fineract` | Toast "Đã tạo sản phẩm; Fineract chưa đồng bộ xong, có thể đồng bộ lại trong ngăn chi tiết."; trạng thái đồng bộ "Lỗi đồng bộ"/"Chờ thử lại"; sau khi bật Fineract: "Đồng bộ lại" → "Đã đồng bộ" |
| ADM-N08 | [UI] | "Lưu trữ" một Product | "Lưu trữ {tên}? Sản phẩm sẽ không mở bán lại được." → "Xác nhận lưu trữ" → "Sản phẩm đã lưu trữ, không mở bán lại được." (chỉ làm với Product tạo riêng cho ca này) |

### 4.4 Gọi vốn, đặt lệnh, xác nhận rủi ro, Auto-Invest

Điều kiện: hồ sơ đã `APPROVED` và điều khoản đã xác nhận (mục 4.2–4.3); Loan phát `LoanFundingRequested`, Investment mở
listing `OPEN`. Nhà đầu tư có ví đủ tiền (mục 2.8). Mobile của nhà đầu tư có bốn tab "Trang chủ", "Sàn", "Ví", "Hồ sơ".

**FUND-01 [UI] Xem listing và hồ sơ người vay ẩn danh (nhà đầu tư).**

1. Tab "Sàn" → màn "Sàn khoản vay". Thẻ khoản vay hiện mã listing, số tiền cần gọi, "{hạng} - {lãi}", "{n} tháng",
   "{p}% vốn". Cuối trang: "Người vay ẩn danh. Tiền góp bị phong tỏa trong ví — chỉ chuyển đi khi gọi đủ 100% vốn và người
   vay ký số."
2. Bấm thẻ → "Khoản vay #{listingId}": trạng thái "Đang gọi vốn"; thẻ "Số tiền cần gọi vốn" (10.000.000 đ); "Tiến độ gọi
   vốn" với "Đã gọi được", "Còn thiếu" và hạn gọi vốn ("Hạn gọi vốn {ngày}, còn {n} ngày"); "Thông tin khoản vay" có "Cách
   trả nợ" = "Trả góp đều", "Người vay" = "Ẩn danh", "Mệnh giá Note" = 1.000.000 đ, "Đầu tư tối thiểu" = 1.000.000 đ.
3. Thẻ "Hồ sơ người vay": "Điểm đánh giá", "Xác suất vỡ nợ", "Thu nhập hằng tháng", "Nợ trên thu nhập", dòng "Tuổi, eKYC từ
   hồ sơ giả lập". Bấm "Xem hồ sơ đầy đủ" → màn "Hồ sơ người vay" có câu ẩn danh ("FINORA không hiển thị họ tên, số CCCD,
   số điện thoại, email và địa chỉ…"), các thẻ "Đánh giá tín dụng", "Khả năng trả nợ", "Lịch sử tín dụng", "Nhân thân",
   "Bảng luật đã chấm", "Khoản vay người vay đề nghị" và chú thích "Tuổi và trạng thái eKYC đang lấy từ hồ sơ giả lập của
   môi trường thử nghiệm, chưa đọc từ hồ sơ định danh thật."
4. Kiểm không có họ tên, CCCD, số điện thoại, email, địa chỉ, `borrowerId`, ghi chú admin hay giải thích SHAP
   (`LEGAL-DISCLOSURE-02`). Lý do không có dữ liệu CIC thì bảng luật ghi "thiếu dữ liệu, tính điểm trung tính".

**FUND-02 [UI] Nạp ví giả lập (nhà đầu tư).** Mục 2.8 với 10.000.000 đ. Thẻ "Tài khoản ví": "Số dư khả dụng" +10.000.000;
dòng "Đang giữ" không hiện (chỉ hiện khi lớn hơn 0); "Lịch sử ví" có một dòng "+10.000.000 đ" dưới "Tháng {MM}/{yyyy}".

**FUND-03 [UI] Đặt lệnh góp vốn có xác nhận rủi ro.**

1. Ở "Khoản vay #{id}", ô "Số tiền đầu tư" (đơn vị "đ") điền sẵn 5.000.000 đ; trợ giúp "Từ {min} đến {max}, bội số của
   {step}."; chọn nhanh có "Tối đa". Nhập 10.000.000.
2. Thẻ "Tạm tính": "Số Note nhận" = 10, "Tỷ lệ trong khoản vay" = 100%, "Lãi dự kiến 6 tháng", "Giữ tạm trong ví".
3. Nút "Đầu tư 10.000.000 đ" bị khóa cho tới khi tick ô xác nhận. Đọc đúng câu: "Tôi xác nhận đã được cung cấp và đã xem
   thông tin khoản vay (số tiền, kỳ hạn, lãi suất, lịch trả) cùng hồ sơ người vay ẩn danh trước khi góp vốn. Tôi hiểu khoản
   đầu tư không được bảo đảm vốn: người vay có thể trả chậm hoặc không trả, khi đó tôi chỉ nhận phần tiền thu hồi được theo
   tỷ lệ Note đang nắm."
4. Tick, bấm "Đầu tư …" → bảng PIN "Nhập mã PIN" / "Xác nhận đặt lệnh đầu tư" → nhập PIN.
5. Kết quả "Đã đặt lệnh đầu tư": "10.000.000 đ đang được giữ tạm trong ví cho khoản vay #{id}…", nút "Xem danh mục đầu tư",
   "Về sàn khoản vay".
6. Mở lại màn đặt lệnh: ô xác nhận đã bỏ tick (app không nhớ, mỗi lệnh phải tick lại).

Bất biến tiền: khả dụng 10.000.000 → 0, đang giữ 0 → 10.000.000 (evidence `order.hold-once`). Backend: lệnh `COMMITTED`, có
`paymentHoldReference`; Investment lưu `riskAcknowledgedVersion = INVESTOR_RISK_V1` và thời điểm trên lệnh.

**FUND-04 [UI] Đủ vốn.** Lệnh 10.000.000 phủ đủ target → listing "Đã đủ vốn"; màn chi tiết thay ô nhập bằng thẻ "Khoản vay đã
gọi đủ vốn"; hồ sơ người vay chuyển funding `FULLY_FUNDED`; Loan lập hợp đồng nhiều bên (mục 4.5). Người vay nhận tin đủ vốn
(mục 4.14).

**FUND-05 [UI] Hai nhà đầu tư cùng góp** (chuẩn bị cho `CTR-03`): nhà đầu tư A đặt 6.000.000, B đặt 4.000.000. Sau lệnh A:
"Còn thiếu" 4.000.000 và trợ giúp của ô số tiền lấy 4.000.000 làm mức tối đa; nhập 5.000.000 báo "Khoản vay chỉ còn thiếu
{max}.". Khi phần còn thiếu bằng mức tối thiểu, trợ giúp đổi thành "Khoản vay chỉ còn nhận đúng {max}.". Sau lệnh B: đủ vốn.

**FUND-06 [UI] Auto-Invest có xác nhận rủi ro.** Tab "Ví" → "Danh mục đầu tư" → lối tắt "Auto-Invest".

1. Thẻ "Tự góp vốn khi có khoản vay mới" ("Đang tắt"). Bật công tắc → hộp thoại "Xác nhận công bố rủi ro" (cùng câu xác
   nhận ở FUND-03), nút "Để sau" / "Tôi đã đọc và đồng ý".
2. Bấm "Tôi đã đọc và đồng ý" → PIN "Xác nhận lưu cài đặt Auto-Invest" → "Đang bật".
3. "Sửa tiêu chí": chọn hạng, "Lãi suất tối thiểu" (%/năm), "Kỳ hạn tối đa" (tháng), "Số tiền mỗi khoản vay" (đ) → "Lưu tiêu
   chí" → PIN. Sửa tiêu chí của quy tắc đang bật không hỏi xác nhận lại và không đổi thời điểm đã xác nhận.
4. Trên stack E2E worker Auto-Invest tắt (`INVESTMENT_AUTO_INVEST_ENABLED=false`): không có lệnh tự động; danh sách "Khớp gần
   đây" giữ trống ("Chưa có khoản nào được xét…"). Chỉ kiểm phần lưu cấu hình.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả trên UI | Backend và bất biến tiền |
|---|---|---|---|---|
| FUND-N01 | [UI] | Ví khả dụng 2.000.000, đặt 5.000.000 | Thẻ "Ví chưa đủ số dư": "Số dư khả dụng không đủ để giữ 5.000.000 đ cho lệnh này nên lệnh chưa được đặt…", nút "Nạp tiền vào ví", "Chọn số tiền khác" | `201` với `status = REJECTED`, `rejectedReasonCode = PAYMENT_INSUFFICIENT_BALANCE`; ví không đổi (khả dụng 2.000.000, đang giữ 0) |
| FUND-N02 | [UI] | Để trống, nhập 500.000, nhập 1.500.000, nhập quá phần còn thiếu | Lỗi tại chỗ, nút khóa: "Nhập số tiền muốn đầu tư.", "Tối thiểu {min}.", "Số tiền phải là bội số của {step}.", "Khoản vay chỉ còn thiếu {max}." | Không gọi API |
| FUND-N03 | [UI] | Không tick ô xác nhận | Nút "Đầu tư …" khóa | Không gọi API |
| FUND-N04 | [API] | Gửi lệnh không có `riskAcknowledgementVersion` (T7, PIN scope `INVEST`) | Không có đường UI (app luôn gửi) | `400 RISK_ACKNOWLEDGEMENT_REQUIRED`; không giữ tiền |
| FUND-N05 | [API] | Gửi `riskAcknowledgementVersion = INVESTOR_RISK_V0` | — | `409 RISK_DISCLOSURE_OUTDATED`; không giữ tiền |
| FUND-N06 | [UI] | Đóng bảng PIN khi đang đặt lệnh | Hủy im lặng, không có lệnh | Ví không đổi |
| FUND-N07 | [UI] | Đặt lệnh thành công rồi bấm lại (mạng chập) với cùng số tiền | Không có lệnh thứ hai cho cùng khóa thao tác | Cùng `orderReference`; đang giữ không tăng lần hai (`order.idempotent-replay`) |
| FUND-N08 | [UI] | Listing đã đủ vốn/hết hạn/đã hủy | Thẻ chặn "Khoản vay đã gọi đủ vốn" / "Đã hết hạn gọi vốn" / "Đợt gọi vốn đã bị huỷ" + nút "Về sàn khoản vay" | Không gọi API đặt lệnh |
| FUND-N09 | [UI] | Hủy lệnh sơ cấp đã `COMMITTED` | Mobile không có nút hủy lệnh sơ cấp (chỉ chợ Notes có "Huỷ") | Tiền giữ chỉ được trả khi đợt gọi vốn bị hủy/hết hạn (FUND-N10, CTR-N04) |
| FUND-N10 | [UI] | Đợt gọi vốn bị hủy sau khi đủ vốn (CTR-N04) | Chi tiết listing: "Khoản vay không được giải ngân vì hợp đồng bị từ chối ký… Tiền bạn đã giữ cho khoản này được trả lại ví." | `LoanFundingCancelled` → Investment hủy cam kết, Payment nhả khoản giữ: đang giữ −số lệnh, khả dụng +số lệnh, đúng một lần |
| FUND-N11 | [UI] | Auto-Invest: bấm "Để sau" ở hộp thoại xác nhận | Công tắc trở về "Đang tắt", không lưu | Cấu hình không đổi |
| FUND-N12 | [API] | Bật Auto-Invest không gửi hoặc gửi sai version (`PUT /api/v1/investments/auto-invest`, PIN scope `AUTO_INVEST`) | App luôn gửi đúng version khi bật | `400 RISK_ACKNOWLEDGEMENT_REQUIRED` / `409 RISK_DISCLOSURE_OUTDATED`; lỗi 400/409 trên app hiện nguyên `message` của backend |
| FUND-N13 | [UI + công cụ] | Đổi bản công bố: đặt `INVESTMENT_RISK_DISCLOSURE_VERSION` khác trong `finora-investment/.env`, `-Action Restart -Services investment`; mở Auto-Invest đang bật; xong trả lại | Banner "Auto-Invest đang tạm dừng" / "Công bố rủi ro đã được cập nhật…", nút "Đọc và xác nhận". Lưu ý: app gửi cố định `INVESTOR_RISK_V1` nên xác nhận lại sẽ bị `409` cho tới khi trả biến về `INVESTOR_RISK_V1` | `pausedForRiskAcknowledgement = true`; matcher bỏ qua với lý do `RISK_ACKNOWLEDGEMENT_OUTDATED` ("Chờ xác nhận rủi ro" trong "Khớp gần đây") |
| FUND-N14 | [UI] | Hết hạn gọi vốn (chờ 1 ngày): trước khi duyệt hồ sơ, admin web "Gọi vốn & Notes" → "Tham số sàn" → "Số ngày gọi vốn" = 1 → "Lưu tham số"; duyệt hồ sơ; nhà đầu tư góp một phần (ví dụ 4.000.000); sau 24 giờ thật, worker (30 giây/lần) hoặc admin bấm "Đóng khoản quá hạn". Đặt lại 14 ngày sau ca | Mobile: listing "Hết hạn gọi vốn"; thẻ chặn "Đã hết hạn gọi vốn". Web: "Đã đóng vì hết hạn"; toast "Đã đóng {n} khoản quá hạn, tiền giữ chỗ đã trả về ví nhà đầu tư." Thông báo: người vay "Đợt gọi vốn đã kết thúc", nhà đầu tư "Khoản đầu tư đã được hủy" | Listing `CLOSED`, lệnh `CANCELLED` với `LISTING_EXPIRED` "Khoản vay hết hạn gọi vốn mà chưa đủ vốn; tiền giữ được trả lại ví"; event `LoanFundingExpired`. Ví nhà đầu tư: đang giữ −4.000.000, khả dụng +4.000.000, đúng một lần. Investment dùng giờ thật, không dời đồng hồ được |
| FUND-N15 | [API] | Đặt lệnh dưới mức tối thiểu / không chia hết mệnh giá / vượt phần còn thiếu (T7, PIN `INVEST`) | App chặn trước (FUND-N02) | `400 ORDER_BELOW_MINIMUM` "Số tiền đầu tư thấp hơn mức tối thiểu"; `400 ORDER_NOT_DIVISIBLE` "Số tiền đầu tư phải chia hết cho mệnh giá Note"; `409 LISTING_INSUFFICIENT_REMAINING` "Khoản vay chỉ còn nhận thêm {n} đồng"; không giữ tiền |
| FUND-N16 | [API] | Đặt lệnh vào listing đã đủ vốn hoặc quá hạn | — | `409 LISTING_NOT_OPEN` "Khoản vay không còn nhận vốn" / `409 LISTING_WINDOW_CLOSED` "Đã hết hạn gọi vốn" |
| FUND-N17 | [API] | Hủy lệnh sơ cấp qua API: `POST :8084/api/v1/investments/orders/{orderReference}/cancel` (không cần PIN) khi listing còn `OPEN` | Mobile không có nút | `200`, lệnh `CANCELLED`, khoản giữ được nhả sau khi lệnh commit: đang giữ −số lệnh, khả dụng +số lệnh. Listing đã đủ vốn: `409 FUNDED_ALLOCATION_LOCKED`; đang giải ngân: `409 COMMITMENT_ALREADY_FINALIZED`; lệnh của người khác: `403 ACCESS_DENIED`; gọi lại khi đã hủy: `200` như cũ |
| FUND-N18 | [UI] | Người vay mở "Sàn" → một khoản vay → "Đầu tư …" | Thanh đầu tư vẫn hiện cho người vay (app không ẩn theo vai trò); backend chặn bằng `403` không có body, app hiện "Bạn không có quyền thực hiện thao tác này." (B86; bản mobile trước `b43f41c` hiện "Phiên đăng nhập đã hết hạn.") | Không có lệnh, ví không đổi |
| FUND-N19 | [UI + công cụ] | Tắt Payment (T5) rồi đặt lệnh; bật lại | "Lệnh đang chờ ví xác nhận" / "Hệ thống đã ghi nhận lệnh nhưng ví chưa xác nhận giữ tiền…" | `409 PAYMENT_UNAVAILABLE` "Dịch vụ ví đang bận…", lệnh `PENDING_FUNDS`; worker làm tiếp sau khoảng 2 phút khi Payment sống: `COMMITTED`, giữ tiền đúng một lần. Kẹt từ 3 lần thử thì hiện ở `INC-06` |

**FUND-07 [UI] Màn gọi vốn trên web.** "Gọi vốn & Notes": dải "Chờ duyệt", "Đang gọi vốn", "Đã đủ vốn", "Rời sàn"; tab "Tất cả",
"Chờ duyệt", "Đang gọi vốn", "Đã đủ vốn", "Đã đóng", "Đã hủy"; cột "Hạn gọi vốn" ("Còn {n} ngày", "Đóng hôm nay", "Quá hạn {n}
ngày, chờ đóng"). Chi tiết listing: tab "Tổng quan", "Nhà đầu tư", "Note"; "Tiến trình" "Chờ duyệt" → "Gọi vốn" → "Đủ vốn" →
"Khóa vốn" → "Phát hành Note"; nút "Bằng chứng blockchain" (khi listing có số hợp đồng). Khi đủ vốn nhưng chưa giải ngân, câu
"Chờ hợp đồng có hiệu lực và giải ngân…" và không có nút. **Kiểm thêm:** đọc code cho thấy nhãn trạng thái của khoản đủ vốn chưa
giải ngân có thể hiện "Đã phát hành Note" trước khi có Note: chụp ảnh nếu gặp (đề xuất defect). Nút "Khóa vốn (sửa lỗi)" và
"Phát hành {n} Note (sửa lỗi)" chỉ hiện sau giải ngân và chỉ dùng khi tự động không chạy; bấm khi đã đủ thì toast "Mọi phần vốn
đã được khóa từ trước, không có gì thay đổi." / "Note đã được phát hành trước đó, không tạo thêm." (B15).

### 4.5 Hợp đồng nhiều bên ký PAdES (bên cho vay trước, người vay cuối)

Điều kiện: FUND-04 xong; Loan lập hợp đồng `LC-…` trạng thái chờ bên cho vay ký. Trên stack E2E chữ ký là `MOCK`: PDF được ký
PAdES-B-B thật bằng chứng thư demo tự ký `CN=FINORA DEMO - KHONG CO GIA TRI PHAP LY`, không có giá trị pháp lý.

**CTR-01 [UI] Nhà đầu tư ký trước.**

1. Tab "Ví" → "Lịch sử ví" → "Hợp đồng chờ ký" (hoặc "Danh mục đầu tư" → lối tắt "Ký hợp đồng") → "Ký hợp đồng đầu tư": nhãn
   "Chờ bạn ký", "Vốn của bạn trong hợp đồng" 10.000.000, "Mục đích" = "Hợp đồng cho vay nhiều bên", "Số Note" 10, "Kỳ hạn",
   "Hạn ký", "Nhà đầu tư chưa ký".
2. "Đọc hợp đồng PDF" → "Mở hợp đồng" (PDF có trang xác nhận mô tả thứ tự ký số).
3. Bước "Xác nhận hợp đồng" (gợi ý "Bản thử nghiệm: xác nhận này chỉ phục vụ phát triển, không thay thế chữ ký số hợp pháp.") →
   "Xác nhận hợp đồng" → PIN "Xác nhận ký hợp đồng" → hộp thoại "Đã ký hợp đồng" / "Chữ ký của bạn đã được ghi nhận trên đúng
   bản PDF hợp đồng." → "Xong".
4. Thẻ "Chữ ký số trong PDF": "1 chữ ký đã nhúng vào tệp hợp đồng", nút "Bản đang ký (1 chữ ký)", câu "Chữ ký trong tệp dùng
   chứng thư demo “FINORA DEMO - KHONG CO GIA TRI PHAP LY” nên không có giá trị pháp lý."

Backend: phần vốn `SIGNED`, phương thức `CLICK_WRAP_MVP`; hợp đồng `PENDING_BORROWER_SIGNATURE`; người vay nhận "Đến lượt bạn
ký hợp đồng".

**CTR-02 [UI] Người vay ký cuối.**

1. Tab "Hồ sơ" → "Hợp đồng" → "Hợp đồng của tôi" → "Chi tiết hợp đồng": "HỢP ĐỒNG VAY ĐIỆN TỬ", nhãn "Chờ bạn ký", "Số tiền
   vay", "Thời hạn", "Lãi suất", "Hạn xác nhận: còn …".
2. Thẻ "NỘI DUNG HỢP ĐỒNG" → "Xem hợp đồng PDF" → đóng. Khối "Bạn đã xem bản PDF hiện hành" chỉ hiện sau khi đã mở đúng bản PDF.
3. Tick "Tôi đã mở và đọc bản PDF, hiểu nghĩa vụ thanh toán và đồng ý xác nhận đúng phiên bản đang được FINORA cung cấp." → "Ký
   xác nhận hợp đồng" → PIN → hộp thoại "Đã xác nhận hợp đồng" / "FINORA đã nhúng chữ ký số của bạn vào tệp PDF hợp đồng. Chữ ký
   dùng chứng thư demo nên không có giá trị pháp lý." → "Đã hiểu".
4. Trạng thái "Đang hiệu lực"; thẻ "Chữ ký số trong PDF": "2 chữ ký", "Lần ký gần nhất", nút "Trang biên nhận chữ ký"; "Xem hợp
   đồng PDF" giờ mở bản ký đủ.
5. "Trang biên nhận chữ ký": mỗi chữ ký một khối (thứ tự, vai trò, mã bên ký, thời điểm, CN, serial, tổ chức cấp, SHA-256 chứng
   thư) và SHA-256 của bản đã ký đủ. Nhà đầu tư mở biên nhận ở màn của mình: cùng một tệp.
6. (Tùy chọn, máy tính) Mở bản ký đủ bằng Adobe Acrobat Reader: thấy 2 chữ ký, mỗi chữ ký báo tài liệu không bị sửa kể từ khi ký
   phiên bản đó; danh tính người ký "chưa xác minh" (chứng thư demo) là đúng.

API (tùy chọn, T7): `GET :8081/api/v1/loan-contracts/{LC}`: `signatureProfile = PADES_B_B`, `signedPdfDocument.signatureCount =
2`, `receiptDocument.contentHash` bằng `receiptDocumentHash` ở `GET :8081/api/v1/investor/loan-contracts/{LC}`. Evidence: bản
SIGNABLE khoảng 52 KB là tiền tố byte của bản ký đủ khoảng 120 KB. Thông báo: "Hợp đồng đã có hiệu lực" (người vay), "Hợp đồng
cho vay đã có hiệu lực" (nhà đầu tư).

**CTR-03 [UI] Hai bên cho vay ký nối tiếp** (sau FUND-05). Khi A chưa ký, màn của người vay hiện "Chờ nhà đầu tư ký" ("Chuỗi
ký: Chờ nhà đầu tư"), không có khối xác nhận. A ký → 1 chữ ký; B ký → 2; người vay ký → 3 chữ ký, "Đang hiệu lực". Với MOCK, A
và B bấm ký cùng lúc vẫn thành công cả hai (ký tuần tự dưới khóa), không có `409`. Màn "Ký hợp đồng đầu tư" chỉ hiện **một**
hợp đồng chờ ký đầu tiên: nhà đầu tư có hai hợp đồng chờ thì ký xong cái đầu mới thấy cái sau (giới hạn).

**CTR-04 [UI + công cụ] Hợp đồng kiểu cũ (`DETACHED_EVIDENCE`) không có thẻ chữ ký.** Đặt `FINORA_SIGNATURE_PADES_ENABLED=false`
trong `finora-loan/.env` (đường lùi D49), `-Action Restart -Services loan`, đi một khoản vay mới tới hợp đồng: màn hợp đồng
không có thẻ "Chữ ký số trong PDF"; ký xong vẫn `EFFECTIVE` với bằng chứng click-wrap tách rời. Xóa biến, Restart Loan. Hợp
đồng đã lập giữ nguyên kiểu ký của nó (không đổi giữa chừng). Chưa có lượt harness cho nhánh này (chỉ có IT).

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả trên UI | Backend và bất biến tiền |
|---|---|---|---|---|
| CTR-N01 | [UI] | Người vay mở hợp đồng khi bên cho vay chưa ký | Không có khối xác nhận và nút từ chối, nhãn "Chờ nhà đầu tư ký" | Qua API ký hoặc từ chối sớm: `409 INVALID_CONTRACT_TRANSITION` "Hợp đồng không còn ở trạng thái chờ ký" (có IT; `LENDER_SIGNATURES_INCOMPLETE` chỉ là chốt phòng thủ) |
| CTR-N02 | [UI] | Nhà đầu tư bấm ký khi chưa mở PDF | Nút khóa, gợi ý "Mở hợp đồng ở bước 1 trước, rồi mới ký được." | Không gọi API |
| CTR-N03 | [API] | Gửi ký với `pdfDocumentHash`/`documentHash` sai; với `version` cũ | Trên app mọi `409` khác hai mã ở CTR-N08/N09 làm màn tự tải lại và mất khối xác nhận (câu lỗi biến mất cùng khối) | Người vay: `documentHash` sai → `409 CONTRACT_CONTENT_MISMATCH` "Nội dung hợp đồng đã xem không khớp phiên bản cần ký"; `pdfDocumentHash` sai → `409 CONTRACT_PDF_MISMATCH` "Bản PDF hợp đồng đã xem không khớp phiên bản cần ký"; `version` cũ → `409 LOAN_CONTRACT_VERSION_CONFLICT` "Hợp đồng đã được cập nhật bởi yêu cầu khác". Nhà đầu tư: `version`/`documentHash` sai → `409 CONTRACT_CONTENT_MISMATCH` "Phiên bản hoặc hash hợp đồng không khớp", PDF sai → `409 CONTRACT_PDF_MISMATCH`; ký lại bằng khóa khác sau khi đã ký → `409 CONTRACT_PARTY_ALREADY_SIGNED`. Không có chữ ký mới |
| CTR-N04 | [UI] | Người vay "Từ chối hợp đồng" → lý do ("Lý do khác" bắt buộc "Chi tiết lý do": "Vui lòng mô tả lý do từ chối.") → "Xác nhận từ chối" (không hỏi PIN) | "Đã từ chối hợp đồng" / "FINORA đã ghi nhận quyết định của bạn."; nhãn "Đã từ chối". Nhà đầu tư: listing "Đã huỷ" với câu "…hợp đồng bị từ chối ký. Tiền bạn đã giữ cho khoản này được trả lại ví."; web: "Đã hủy, không giải ngân" ("Hợp đồng bị từ chối ký") | `LoanFundingCancelled` → Investment hủy cam kết → Payment nhả khoản giữ: nhà đầu tư đang giữ −10.000.000, khả dụng +10.000.000, đúng một lần; người vay không nhận tiền. Thông báo "Khoản vay không được giải ngân" / "Khoản đầu tư đã được hủy" |
| CTR-N05 | [UI] | Nhà đầu tư muốn từ chối ký | App không có nút từ chối cho nhà đầu tư | — |
| CTR-N06 | [UI + công cụ] | Hết hạn ký (cửa sổ ký mặc định 7 ngày, chung cho mọi bên). Cách gọn: trước khi đủ vốn, đặt `LOAN_CONTRACT_SIGNATURE_WINDOW=PT10M` trong `finora-loan/.env`, `-Action Restart -Services loan`, đi tới hợp đồng, chờ quá 10 phút (worker hết hạn chạy 60 giây/lần); xong xóa biến, Restart Loan. Cách khác: Restart chỉ Loan với `-LoanClockOffset "P8D"` (T2, không cần business date Fineract) rồi trả đồng hồ. Cách dời đồng hồ làm mọi hợp đồng đang chờ ký trên DB local cùng hết hạn; cách đổi biến chỉ ảnh hưởng hợp đồng lập sau khi đổi | Đồng hồ đếm ngược "Đã hết hạn"; câu "Hợp đồng đã quá hạn xác nhận nên không còn ký được…"; nhãn "Đã hết hạn"; web "Hợp đồng hết hạn ký". Bấm ký sau hạn: `409 CONTRACT_EXPIRED` "Hợp đồng đã hết hạn ký" | Hợp đồng `EXPIRED` → `LoanFundingCancelled` (`CONTRACT_EXPIRED`) → Investment hủy cam kết, nhả khoản giữ đúng một lần như CTR-N04. Chưa có lượt harness cho ca này (giới hạn §6): ghi kết quả thực tế |
| CTR-N07 | [API] | Gửi lại lệnh ký cùng `Idempotency-Key` | — | `200`, `partyStatus = SIGNED`, số chữ ký không đổi (`contract.lender-sign-replay`) |
| CTR-N08 | N/A trên MOCK | Hai nhà đầu tư ký SmartCA UAT cùng lúc (cần UAT và người duyệt trên app) | "Một bên khác đang ký số hợp đồng này. Các bên ký lần lượt trên cùng tệp PDF, bạn hãy đợi vài phút rồi bấm ký lại." App giữ nguyên khóa thao tác, không tự thử lại | `409 CONTRACT_SIGNATURE_IN_PROGRESS`; MOCK ký đồng bộ nên không tái hiện được |
| CTR-N09 | N/A trên MOCK | SmartCA trả chữ ký không khớp chứng thư | "Chữ ký trả về không khớp chứng thư nên lần ký này đã bị hủy và chưa có chữ ký nào được thêm vào hợp đồng. Bạn có thể ký lại."; nút "Ký lại" (khóa thao tác mới) | `503 SIGNATURE_PROVIDER_INVALID_SIGNATURE`, lần ký `ABANDONED` |
| CTR-N10 | [API] | Tải `/receipt` trước khi đủ chữ ký; `/signed-document` trước chữ ký đầu | Nút tương ứng chưa hiện | `404` |
| CTR-N11 | [UI] | Sai PIN khi ký; đóng bảng PIN | Như AUTH-N08; không có chữ ký | — |

### 4.6 Giải ngân và phát hành Note

Điều kiện: CTR-02 vừa xong. Saga giải ngân chạy tự động: Payment chuyển tiền (provider `mock`), Loan ghi khoản vay vào Fineract,
Investment phát hành Note.

**DISB-01 [UI] Kết quả giải ngân.** Trong vòng vài chục giây (kéo để làm mới):

- Người vay: "Số dư khả dụng" +10.000.000; "Khoản vay của tôi" có `LN-…` "Đang trả nợ", "Kỳ tới {ngày}" (một tháng sau ngày giải
  ngân), số kỳ tới 1.726.000; hợp đồng "Đang hiệu lực"; tin "Khoản vay đã được giải ngân".
- Nhà đầu tư: dòng "Đang giữ" biến mất (giữ 10.000.000 → 0, đã capture); "Danh mục đầu tư": "Vốn đang đầu tư" 10.000.000, "10
  Note trên 1 khoản vay", thẻ "Khoản vay #{id}" "10 Note, 100% khoản vay", "Dư nợ còn lại" 10.000.000, nút "Bán Note trên chợ";
  tin "Khoản đầu tư đã bắt đầu".
- Web "Gọi vốn & Notes": listing đã phát hành Note, tab "Note" có 10 Note; nút "Bằng chứng blockchain" mở 3 proof
  `CONTRACT_DOCUMENT`, `DISBURSEMENT`, `NOTE_OWNERSHIP` "Đã xác nhận" (MOCK).

Bất biến tiền: người vay +10.000.000 đúng một lần; nhà đầu tư khả dụng không đổi, đang giữ −10.000.000. Backend (T6/T7): hợp
đồng `disbursementStatus = COMPLETED`, có `fineractLoanId`; Fineract `loanStatusType.active`, gốc 10.000.000,
`isInterestRecalculationEnabled = true` (core V4); kỳ đầu Fineract trùng kỳ đầu trong hợp đồng; Kafka mỗi topic
(`disbursement-requested`, `disbursement-completed`, `disbursed`, `note-ownership-changed`) đúng một `eventId`.

**Ca lỗi và ca biên** (drill lỗi thật Q13; mỗi ca một khoản vay mới, dừng ngay trước CTR-02 bước 3; lệnh PowerShell)

| Mã | Kích hoạt | Cách gây ra | Kết quả mong đợi |
|---|---|---|---|
| DISB-N01 | [UI + công cụ] | Loan chết sau khi Fineract ghi khoản vay: `-Action Restart -Services loan -DrillEnv 'loan:FINORA_E2E_PAUSE_POINT=core-booked-before-save,loan:FINORA_E2E_PAUSE_DURATION=PT240S,loan:LOAN_DISBURSEMENT_CORE_BOOKING_LEASE=PT30S'`; người vay ký; chờ `Select-String -Path docker/e2e/out/logs/loan.log -Pattern 'E2E_PAUSE point=core-booked-before-save' -SimpleMatch`; `Stop-Process -Id <pid loan> -Force`; `-Action Restart -Services loan -DrillEnv 'loan:LOAN_DISBURSEMENT_CORE_BOOKING_LEASE=PT30S'`; cuối ca Restart loan không có `-DrillEnv` | Người vay nhận tiền đúng một lần; hợp đồng "Đang hiệu lực"; Fineract đúng một khoản vay theo externalId là số hợp đồng (T6 `/loans?externalId=<LC-…>`); saga `COMPLETED` với `attemptCount ≥ 2` (`GET :8081/api/v1/admin/disbursement-sagas?status=COMPLETED`, token admin); không có dòng ở "Sự cố giao dịch" |
| DISB-N02 | [UI + công cụ] | Payment chết sau khi provider chuyển tiền: `-Action Restart -Services payment -DrillEnv 'payment:FINORA_E2E_PAUSE_POINT=provider-disbursed-before-save,payment:FINORA_E2E_PAUSE_DURATION=PT240S,payment:PAYMENT_DISBURSEMENT_PROCESSING_LEASE=PT30S'`; người vay ký; chờ dòng `E2E_PAUSE point=provider-disbursed-before-save` trong `payment.log`; `Stop-Process` Payment; Restart Payment với `-DrillEnv 'payment:PAYMENT_DISBURSEMENT_PROCESSING_LEASE=PT30S'` | Trong lúc chờ, web "Sự cố giao dịch" có thể hiện lệnh ở "Giải ngân chưa có kết quả chuyển tiền" ("Đang chuyển tiền"). Sau khi bật lại: tự đối soát theo `sagaId` → `COMPLETED`; ví người vay +10.000.000 đúng một lần; `GET :8080/api/v1/admin/disbursement-reconciliation/{sagaId}` có `attemptCount = 1`, `errorCode = PAYMENT_PROCESSING_INTERRUPTED` |
| DISB-N03 | [UI + công cụ] | Investment tắt khi có `LoanDisbursed`: Restart Loan với `-DrillEnv 'loan:FINORA_E2E_PAUSE_POINT=core-booked-before-save,loan:FINORA_E2E_PAUSE_DURATION=PT40S'`; người vay ký; khi Loan dừng ở điểm dừng thì `Stop-Process` Investment; chờ saga `COMPLETED`; Restart Investment; Restart Loan không có `-DrillEnv` | Khi Investment tắt: tab "Sàn"/"Danh mục đầu tư" báo lỗi tải. Bật lại: đúng 10 Note, một `NoteOwnershipChanged`, vị thế có ngày đáo hạn; không Note thứ hai |
| DISB-N04 | [UI + công cụ] | Saga `REPAIR_REQUIRED` (ghi Fineract lỗi không tự phục hồi). Không có cách tạo ổn định trên stack E2E | Nếu gặp: web "Đã giải ngân, hệ thống lõi chưa ghi khoản vay" → "Ghi lại" (INC-05). Tiền đã giải ngân không bị chuyển lại |

### 4.7 Trả kỳ: giữ tới ngày đến hạn, hủy, ghi nhận đúng ngày

Điều kiện: khoản vay vừa giải ngân (`DISB-01`, core V4), chưa có khoản trả nào; ví người vay khả dụng 10.000.000 đ. Đường đi:
tab "Hồ sơ" → "Khoản vay" ("Trả nợ và cơ cấu") → "Khoản vay của tôi" → thẻ khoản vay → "Chi tiết khoản vay". Xác nhận Hủy và
tất toán dùng hộp thoại của hệ điều hành: kiểm trên Android/iOS (trên Expo web hộp thoại không hiện).

**REP-01 [UI] Xem khoản vay và lịch trả.** Thẻ trong danh sách: mã `LN-…`, "Đang trả nợ", dư nợ còn lại, "Vay …", "Đáo hạn
…", "Kỳ tới {ngày}". "Chi tiết khoản vay": "Dư nợ còn lại", "Đã trả 0% gốc", "Gốc còn lại", "Ngày đáo hạn", dòng "Số liệu
cập nhật …". Bấm "Xem lịch trả nợ 6 kỳ" → "Lịch trả nợ": "Lịch trả từng kỳ (6 kỳ)", kỳ 1 có nhãn "Kỳ tới", số tiền khớp
hợp đồng (1.726.000 đ). Thẻ "Thanh toán và điều chỉnh" có "Thanh toán kỳ tới", "Trả trước một phần gốc", "Tất toán toàn bộ
trước hạn", "Đề nghị điều chỉnh kỳ hạn hoặc gia hạn".

**REP-02 [UI] Trả kỳ 1 trước hạn: tiền rời ví ngay, ghi sổ nợ đúng ngày đến hạn (D16).**

1. Bấm "Thanh toán kỳ tới" → màn "Thanh toán kỳ tới": "SỐ TIỀN THANH TOÁN" 1.726.000 đ, "Đến hạn {dd/MM/yyyy}", các dòng "Số
   dư ví khả dụng", "Dư nợ hiện tại", "Nguồn số tiền" = "Kỳ đến hạn từ Fineract", "Ghi nhận vào sổ nợ" = ngày đến hạn.
2. Ghi chú: "Bạn đang thanh toán trước ngày đến hạn. Tiền được trừ khỏi ví hôm nay và ghi nhận vào kỳ đúng ngày {dd/MM/yyyy},
   nên lãi của kỳ giữ nguyên theo lịch. Muốn giảm lãi, hãy chọn trả trước một phần gốc."
3. "Xác nhận thanh toán từ ví" → PIN "Nhập mã PIN" / "Xác nhận thanh toán khoản vay".
4. Thẻ "Kết quả giao dịch" nhãn "Chờ ghi nhận {dd/MM/yyyy}", dòng "Số tiền đã thu", "Ngày thu từ ví", "Ghi nhận vào sổ nợ";
   câu "Đã trừ 1.726.000 đ từ ví. Khoản tiền được ghi nhận vào kỳ đúng ngày đến hạn…".
5. Mở lại "Thanh toán kỳ tới": thay form bằng khối "ĐÃ THANH TOÁN, CHỜ GHI NHẬN", "Ghi nhận vào kỳ ngày {…}", nút "Hủy và
   hoàn tiền về ví", "Về khoản vay".

Bất biến tiền: người vay khả dụng 10.000.000 → 8.274.000 (đúng một lần), đang giữ không đổi; nhà đầu tư không đổi. Backend:
khoản trả `COLLECTED`, `postOn` = ngày đến hạn; Fineract chưa có giao dịch `FINORA-REPAY-<repaymentId>` (T6); Kafka có một
`RepaymentCollected`. Thẻ "Thanh toán và điều chỉnh" vẫn hiện kỳ tới chưa trả cho tới ngày ghi nhận (giới hạn đã biết, W2C).

**REP-03 [UI] Đang giữ thì không lập được báo giá.** Ở "Chi tiết khoản vay" bấm "Tất toán toàn bộ trước hạn": thẻ "Còn khoản
chờ ghi nhận" với câu backend "Kỳ đến hạn {ngày} đã thanh toán và đang chờ ghi nhận đúng ngày đến hạn. Hủy khoản chờ ghi nhận
(tiền hoàn về ví) để lập báo giá trả trước hoặc tất toán theo dư nợ hiện tại", nút "Xem khoản chờ ghi nhận" và "Thử lại".
"Trả trước một phần gốc" cho cùng câu và nút "Xem khoản chờ ghi nhận". Backend `409 INSTALLMENT_PENDING_POSTING`; không có
tiền di chuyển.

**REP-04 [UI] Hủy khoản đang giữ.** "Hủy và hoàn tiền về ví" → hộp thoại "Hủy khoản thanh toán chờ ghi nhận?" / "Tiền hoàn
về ví ngay. Nếu không thanh toán lại trước ngày đến hạn, kỳ này sẽ bị quá hạn." → "Hủy và hoàn tiền" → PIN → thông báo "Đã
hủy khoản thanh toán chờ ghi nhận; tiền đã hoàn về ví. Kỳ này vẫn cần được thanh toán trước ngày đến hạn." Bất biến tiền:
khả dụng 8.274.000 → 10.000.000. Backend `CANCELLED`, một `RepaymentCancelled`, Fineract không có giao dịch. Sau bước này
báo giá tất toán lập được (`PRE-02`, phí 100.000 đ).

**REP-05 [UI + công cụ] Ghi nhận đúng ngày đến hạn.**

1. Trả lại kỳ 1 (REP-02 bước 1–4): khả dụng 10.000.000 → 8.274.000.
2. T2 dời **chỉ Payment** tới ngày đến hạn: đặt business date Fineract = ngày đến hạn, chạy `LA_RINT`, `LA_ARAG`, rồi
   PowerShell
   `powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Restart -Services payment -Database local -PaymentClockOffset "P$($days)D"`.
3. Chờ worker ghi core (vài giây tới một phút), mở lại khoản vay: kết quả "Hoàn tất", "Vào gốc" 1.624.000, "Vào lãi" 102.000,
   "Phí và phạt" 0, "Dư nợ sau giao dịch" 8.376.000, "Kỳ tới" sang kỳ 2.
4. Nhà đầu tư: khả dụng +1.726.000 (gốc + lãi); "Danh mục đầu tư" tăng "Lãi đã nhận" 102.000 và "Đã thu hồi {x}% gốc".
5. Thông báo: người vay `REPAYMENT_RECORDED`, nhà đầu tư `REPAYMENT_CREDITED`; proof `REPAYMENT` `CONFIRMED` (mục 4.13).
6. T2 trả đồng hồ về ngày thật. Không dùng khoản vay này cho ca khác (đã có giao dịch đề ngày tương lai).

Bất biến tiền tổng REP-02…REP-05: người vay bị trừ đúng 1.726.000 một lần (tính cả lệnh đã hủy); Fineract đúng một giao dịch
`FINORA-REPAY-<id>` đề ngày đến hạn; gốc + lãi + phí + phạt = 1.726.000 (evidence `repayment.*`, `fineract.*`).

**REP-06 [UI + công cụ] Trả từ ngày đến hạn trở đi thì ghi ngay.** Trên một khoản vay mới, T2 dời Payment tới đúng ngày đến hạn
rồi mới trả: không có dòng "Ghi nhận vào sổ nợ", trạng thái đi "Đã thu tiền" → "Đang ghi nhận vào khoản vay" → "Đã ghi nhận,
đang phân phối" → "Hoàn tất" (app tự hỏi lại 2 giây/lần, tối đa khoảng 60 giây). T2 trả đồng hồ.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả trên UI | Backend và bất biến tiền |
|---|---|---|---|---|
| REP-N01 | [UI] | Ví khả dụng nhỏ hơn số phải trả (rút bớt trước) | "Số dư ví chưa đủ. Hãy nạp thêm tiền trước khi xác nhận.", nút khóa | Không gọi API. Qua API: `422 PAYMENT_INSUFFICIENT_BALANCE` "Số dư wallet không đủ cho giao dịch"; ví không đổi |
| REP-N02 | [API] | Gửi số tiền khác nghĩa vụ (ví dụ 1.000.000) | — | `422 REPAYMENT_AMOUNT_NOT_SCHEDULED_DUE` "Số tiền phải bằng nghĩa vụ hiện tại từ Fineract: …"; ví không đổi |
| REP-N03 | [API] | Gửi `amount` hơn 2 chữ số thập phân (ví dụ `1726000.123`) | — | `400 VALIDATION_FAILED` "Dữ liệu không hợp lệ"; ví không đổi |
| REP-N04 | [UI] | Khoản đang giữ, trả lần nữa từ thiết bị thứ hai đăng nhập cùng tài khoản (hoặc T7) | Câu backend "Kỳ đến hạn {dd/MM/yyyy} đã được thanh toán; khoản tiền được ghi nhận vào sổ nợ đúng ngày đến hạn" | `409 INSTALLMENT_ALREADY_PAID_PENDING`; chỉ một lần trừ ví |
| REP-N05 | [UI + công cụ] | Trong lúc một khoản đang `CORE_POSTING`/`RECONCILIATION_REQUIRED` (REP-N07), trả thêm từ thiết bị thứ hai hoặc T7 | Khối "ĐANG GHI NHẬN THANH TOÁN" / câu backend "Khoản thanh toán trước đang được ghi nhận vào sổ nợ; vui lòng thử lại sau ít phút" | `409 REPAYMENT_IN_PROGRESS`; không trừ lần hai |
| REP-N06 | [API] | Hủy khoản đã tới ngày ghi nhận hoặc đã ghi xong | Nút hủy không hiện (`pending.cancellable = false`) | `409 REPAYMENT_NOT_CANCELLABLE` "Chỉ hủy được khoản trả kỳ trước hạn khi chưa tới ngày ghi nhận vào sổ nợ" |
| REP-N07 | [UI + công cụ] | Mất phản hồi Fineract: stack `-WithToxiproxy`; REP-05 bước 1; bật toxic `timeout` (T4); REP-05 bước 2; xem web `INC-02`; gỡ toxic | Kết quả "Cần đối soát": "Giao dịch cần quản trị viên đối soát. Không thanh toán lại để tránh trùng giao dịch." Sau khi gỡ toxic, worker đối soát (30 giây/lần) tìm thấy → "Hoàn tất" | `RECONCILIATION_REQUIRED`, `errorCode = FINERACT_REPAYMENT_OUTCOME_UNKNOWN` → `COMPLETED`; Fineract đúng một giao dịch; ví trừ đúng một lần |
| REP-N08 | [UI + công cụ] | Mất request: như REP-N07 nhưng toxic `reset_peer`; ở bước dời đồng hồ thêm `-DrillEnv 'payment:PAYMENT_CORE_REPOST_SAFE_AFTER=PT1M'` vào lệnh Restart; gỡ toxic; admin bấm "Ghi lại vào hệ thống lõi" (`INC-03`) | "Cần đối soát" tới khi admin ghi lại, rồi "Hoàn tất" | Đối soát không thấy giao dịch; trước khoảng an toàn: `REPAYMENT_REPOST_TOO_EARLY`; sau đó `CORE_POSTED` → `COMPLETED`, `coreRepostCount = 1`; Fineract một giao dịch (B32) |
| REP-N09 | [API] | Token PIN của lệnh khác (SEC-05) | — | `403 PIN_TOKEN_INVALID` "Xác nhận mã PIN đã hết hạn hoặc không hợp lệ, vui lòng nhập lại"; thiếu token: `403 PIN_REQUIRED` "Thao tác này cần xác nhận bằng mã PIN" |
| REP-N10 | [UI] | Nhập sai PIN; sai nhiều lần | "Mã PIN không đúng, bạn còn {n} lần thử"; khi khóa: "Mã PIN tạm khoá do nhập sai nhiều lần. Thử lại sau {m} phút hoặc chọn Quên mã PIN" | Không có lệnh; ví không đổi |
| REP-N11 | [UI] | Đóng bảng PIN | Hủy im lặng | Không có lệnh |
| REP-N12 | [UI + công cụ] | `docker stop finora-fineract`, chờ 20–30 giây, kéo làm mới khoản vay; xong `docker start finora-fineract` | "Số liệu khoản vay đang chờ đồng bộ lại. Hãy kéo xuống để làm mới trước khi thanh toán.", mọi nút thanh toán khóa | Projection `stale = true`, giữ số cũ; không có lệnh |

### 4.8 Trả trước một phần và tất toán sớm

Điều kiện: khoản vay core V4 `ACTIVE`, không quá hạn, không có khoản trả đang giữ; mỗi ca dùng một khoản vay riêng. Số liệu
dưới đây là khi thao tác ngay ngày giải ngân (lãi đã phát sinh bằng 0; evidence `w2c-partialprepayment-20261017-110733`,
`w2c-earlysettlement-20261017-111021`). Thao tác ngày khác thì lãi lớn hơn 0 nhưng không vượt lãi đã phát sinh tới hôm đó.
Báo giá chỉ lập, không chuyển tiền, hết hạn sau 15 phút.

**PRE-01 [UI] Trả trước một phần gốc.**

1. "Chi tiết khoản vay" → "Trả trước một phần gốc" → thẻ "Số gốc muốn trả thêm", "Gốc còn lại: 10.000.000 đ".
2. "Số tiền trả trước" = 3.000.000 → "Xem số tiền cần thanh toán".
3. Thẻ "Báo giá trả trước": "Khoản của kỳ gần nhất" 1.726.000; "Gốc trả trước thêm" 3.000.000; "Tổng phí trả trước ({tỷ
   lệ}%)" 100.000 (phí = max(gốc trả thêm × tỷ lệ, 100.000); tỷ lệ 1% × 3.000.000 = 30.000 nhỏ hơn mức tối thiểu); "Trong phí:
   bù nhà đầu tư (80%)" 80.000; "Trong phí: FINORA (20%)" 20.000; "Tổng trừ từ ví" 4.826.000; "Báo giá có hiệu lực đến
   {HH:mm dd/MM/yyyy}"; "Phí tính theo biểu phí ghi trong hợp đồng của bạn (AGRIBANK_REFERENCE_FINORA_2026_V2)."; một câu giải
   thích phân bổ theo chiến lược của khoản vay (core V4: "Toàn bộ số tiền được ghi nhận hôm nay: lãi chỉ tính đến hôm nay…").
4. "Xác nhận trả trước" → PIN → kết quả → "Xem lịch trả nợ mới".

Kết quả: Fineract ghi gốc 4.726.000, lãi 0; "Gốc còn lại" 5.274.000; kỳ tới lùi vì gốc trả thêm phủ các kỳ gần nhất
(`NEXT_INSTALLMENT`). Bất biến tiền: người vay trừ đúng 4.826.000 một lần; nhà đầu tư +4.806.000 (gốc 4.726.000 + lãi 0 +
80.000 phí); danh mục có "Phí trả trước được chia" 80.000. Fineract một giao dịch `FINORA-REPAY-<id>`.

**PRE-02 [UI] Tất toán sớm.**

1. "Tất toán toàn bộ trước hạn" → app tự lập báo giá ("Đang lập báo giá tất toán").
2. Thẻ "Số tiền cần thanh toán": "Gốc còn lại" 10.000.000; "Lãi phát sinh đến hôm nay" 0; "Phí/phạt nghĩa vụ hiện tại" 0;
   "Tổng phí tất toán ({tỷ lệ}%)" 100.000 (evidence `feeRate = 0.010000`, 1% × 10.000.000); "Trong phí: bù nhà đầu tư (80%)"
   80.000; "Trong phí: FINORA (20%)" 20.000; "Tổng trừ từ ví" 10.100.000; "Báo giá có hiệu lực đến …"; câu "Không thu lãi của
   các tháng tương lai…".
3. Người vay chỉ có 10.000.000 tiền giải ngân: nạp thêm ít nhất 100.000 (mục 2.8), quay lại "Lấy báo giá mới".
4. "Xác nhận tất toán" → hộp thoại "Xác nhận tất toán toàn bộ?" / "Sau khi giao dịch thành công, khoản vay sẽ được đóng và
   không thể hoàn tác." → "Tiếp tục" → PIN.
5. "Về chi tiết khoản vay": trạng thái "Đã tất toán", câu "Bạn đã tất toán khoản vay này. Lịch trả nợ vẫn còn để bạn xem
   lại.", không còn nút thanh toán.

Bất biến tiền: người vay trừ đúng 10.100.000 một lần; nhà đầu tư +10.080.000; vị thế "Đã tất toán", "Dư nợ còn lại" 0, "Đã
thu hồi 100% gốc". Fineract `closed.obligations.met`, Loan `SETTLED`, Note đóng.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả trên UI | Backend và bất biến tiền |
|---|---|---|---|---|
| PRE-N01 | [UI] | Lập báo giá trả trước (PRE-01 bước 3), giữ nguyên màn; trên thiết bị thứ hai (cùng tài khoản) hoặc T7 trả kỳ tới; quay lại bấm "Xác nhận trả trước" | Câu backend "Khoản vay đã có giao dịch mới sau khi lập báo giá; vui lòng lập lại báo giá" | `409 PARTIAL_PREPAYMENT_QUOTE_STALE` (tất toán: `EARLY_SETTLEMENT_QUOTE_STALE`); không trừ ví cho báo giá cũ. Ghi lại nếu mã khác |
| PRE-N02 | [UI] | Lập báo giá, chờ quá 15 phút, bấm xác nhận (app không tự kiểm hạn, nút vẫn bật) | Câu backend "Quote đã hết hạn" (hoặc "Quote không còn hiệu lực" nếu đã dùng) | `409 PARTIAL_PREPAYMENT_QUOTE_NOT_ACTIVE` / `EARLY_SETTLEMENT_QUOTE_NOT_ACTIVE`; ví không đổi. "Lấy báo giá mới" lập lại được |
| PRE-N03 | [UI + công cụ] | Khoản vay đang quá hạn (`OVD-01`), mở "Trả trước một phần gốc" | Ghi chú "Khoản vay đang quá hạn. Hãy thanh toán phần quá hạn trước khi trả trước gốc; phần gốc trả thêm lúc này sẽ chỉ trả nợ quá hạn mà vẫn bị tính phí.", nút "Thanh toán phần quá hạn", ẩn nút báo giá | Qua API: `409 PARTIAL_PREPAYMENT_LOAN_OVERDUE` (D27). Tất toán vẫn lập được khi quá hạn |
| PRE-N04 | [UI] | Còn khoản trả kỳ đang giữ (REP-03) | Như REP-03 | `409 INSTALLMENT_PENDING_POSTING` (D22: hủy khoản giữ rồi mới báo giá) |
| PRE-N05 | [UI] | Nhập 0 hoặc lớn hơn/bằng gốc còn lại | "Số tiền phải lớn hơn 0 và nhỏ hơn gốc còn lại." | Không gọi API. Qua API: `422 PARTIAL_PREPAYMENT_AMOUNT_TOO_HIGH` |
| PRE-N06 | [UI] | Ví không đủ tổng báo giá | Trả trước: "Số dư ví không đủ cho tổng báo giá."; tất toán: câu lỗi của backend (tên trạng thái cần kiểm lại trên app) | Không trừ ví; qua API `422 PAYMENT_INSUFFICIENT_BALANCE` |
| PRE-N07 | [UI] | Hộp thoại tất toán chọn "Quay lại"; hoặc đóng PIN | Không có giao dịch | Ví không đổi |
| PRE-N08 | [API] | Gửi lại lệnh thực hiện báo giá cùng `Idempotency-Key` | — | Cùng `repaymentId`, trừ ví một lần (`servicing.idempotent-replay`) |
| PRE-N09 | [API] | 31 báo giá trong một phút | Câu "Bạn thao tác quá nhanh, vui lòng thử lại sau {N} giây" | `429 RATE_LIMITED` |
| PRE-N10 | [UI + công cụ] | Khoản vay core V1–V3. Product của stack E2E là V4 nên phải tạo khoản vay cũ: đặt `FINERACT_PRODUCT_CONFIG_VERSION=FINORA-FINERACT-V3` trong `finora-loan/.env`, `-Action Restart -Services loan`, tạo Product mới (ADM-05), đi hết tới giải ngân; xong trả biến về mặc định. Chưa được harness kiểm | Câu backend "Khoản vay này dùng sản phẩm cũ tính trước lãi cho cả kỳ hạn nên chưa hỗ trợ trả trước hoặc tất toán trên ứng dụng…", ẩn nút báo giá; trả kỳ và khắc phục quá hạn vẫn được | `409 PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN` (D17) |

### 4.9 Quá hạn, khắc phục, nợ xấu và góc nhìn nhà đầu tư

Điều kiện: một khoản vay mới (10.000.000 đ, 6 kỳ) đã giải ngân, chưa trả kỳ nào; một nhà đầu tư nắm toàn bộ Note; stack
`-Database local`; ngày đến hạn kỳ 1 là `D1` (đọc ở "Lịch trả nợ"). Mọi bước dời đồng hồ dùng T2 với
`-Services loan,payment`; sau mỗi lần dời chờ 10–20 giây rồi kéo làm mới. Số liệu theo evidence
`w3r-defaultcure-20261017-205432`. Nhóm nợ nội bộ: DPD 1–9 nhóm 1, 10–90 nhóm 2, 91–180 nhóm 3 (nợ xấu, `DEFAULTED`), 181–360
nhóm 4, trên 360 nhóm 5 (`docs/LOAN-SERVICING-POLICY.md`). Phía người vay app không hiện số nhóm nợ, chỉ hiện DPD và trạng
thái; phía nhà đầu tư hiện "Quá hạn {dpd} ngày · Nhóm nợ {g}".

**OVD-01 [UI + công cụ] DPD 10 → nhóm 2.** T2 dời tới `D1 + 10`.

- Người vay: thẻ danh sách có ô đỏ "Quá hạn 10 ngày"; trạng thái vẫn "Đang trả nợ"; thẻ "Thanh toán và điều chỉnh": "1.726.000 đ
  / quá hạn", "Đã quá hạn 10 ngày", ghi chú "Khoản thanh toán tiếp theo được ghi nhận là khắc phục quá hạn. Hãy thanh toán sớm
  để khoản vay trở lại đúng lịch.", nút "Khắc phục quá hạn".
- Nhà đầu tư: thẻ vị thế không có nhãn trạng thái (đang hoạt động), ô màu hổ phách "Quá hạn 10 ngày · Nhóm nợ 2", "Số tiền quá
  hạn 1.726.000 đ".
- Thông báo: một tin quá hạn cho người vay và một tin rủi ro cho nhà đầu tư (mục 4.14); push nhóm `DELINQUENCY` câu chung.

**OVD-02 [UI + công cụ] Khắc phục toàn bộ quá hạn.** "Khắc phục quá hạn" → màn cùng tên: "SỐ TIỀN THANH TOÁN" 1.726.000 (mặc
định toàn bộ phần quá hạn, D26), "Quá hạn 10 ngày", dòng "Trong đó quá hạn", "Nguồn số tiền" = "Nghĩa vụ quá hạn từ Fineract",
không có dòng "Ghi nhận vào sổ nợ" (ghi ngay) → xác nhận → PIN → "Hoàn tất". Sau đó: DPD 0, "Đang trả nợ"; ô quá hạn của nhà
đầu tư biến mất (nhóm 1); tin "đã khắc phục" cho hai bên. Bất biến tiền: người vay trừ đúng 1.726.000 một lần (nạp thêm nếu
thiếu); nhà đầu tư nhận phần gốc + lãi của khoản trả. T2 trả đồng hồ.

**OVD-03 [UI + công cụ] Nợ xấu ở DPD 91** (khoản vay mới khác). T2 dời tới `D1 + 10` (kiểm như OVD-01) rồi `D1 + 91`.

- Người vay: trạng thái "Nợ xấu", "Quá hạn 91 ngày", quá hạn 5.178.000 (3 kỳ). Màn "Khắc phục quá hạn" có lựa chọn "Toàn bộ quá
  hạn" (5.178.000, mặc định) hoặc "Chỉ kỳ cũ nhất" (1.726.000).
- Nhà đầu tư: nhãn "Nợ xấu", ô đỏ "Quá hạn 91 ngày · Nhóm nợ 3"; nút "Bán Note trên chợ" ẩn.
- Thông báo: tin nợ xấu cho người vay (`LOAN_DEFAULTED`) và nhà đầu tư (`BAD_DEBT_MILESTONE`).

**OVD-04 [UI + công cụ] Trả bớt kỳ cũ nhất vẫn là nợ xấu (D26, D29).** Chọn "Chỉ kỳ cũ nhất" → cảnh báo "Bạn chỉ trả kỳ quá hạn
cũ nhất. Khoản vay vẫn quá hạn với 3.452.000 đ còn lại và chỉ trở lại bình thường khi trả đủ toàn bộ phần quá hạn." → xác nhận.
Sau đó: người vay vẫn "Nợ xấu", DPD 61, quá hạn 3.452.000; nhà đầu tư vẫn "Nợ xấu", "Nhóm nợ 3" (không về nhóm 2).
**Quan sát cần ghi:** evidence cho thấy DPD trên vị thế nhà đầu tư vẫn là 91 trong khi Loan báo 61 (harness chỉ kiểm trạng thái
và nhóm). Nếu UI nhà đầu tư hiện "Quá hạn 91 ngày", ghi vào phiếu và báo Thái (đề xuất defect mới, mục 7 #8 và 7.1).

**OVD-05 [UI + công cụ] Khắc phục từ nợ xấu.** Trả nốt "Toàn bộ quá hạn" 3.452.000 → "Đang trả nợ", DPD 0, kỳ tới sang kỳ chưa
trả kế tiếp, tổng dư nợ 5.275.000; nhà đầu tư hết nhãn "Nợ xấu", hết ô quá hạn (nhóm 1). Tin "đã khắc phục" cho hai bên.

**OVD-06 [UI + công cụ] DPD 181, 361 rồi trả hết.** Gọi `N` là ngày kỳ tới sau OVD-05. T2 dời tới `N + 181`: "Nợ xấu", DPD 181,
quá hạn 5.324.000; nhà đầu tư "Nhóm nợ 4". Dời tới `N + 361`: "Nhóm nợ 5" (quá hạn vẫn 5.324.000 vì core V4 không tính thêm lãi
sau đáo hạn). Trả "Toàn bộ quá hạn" (có thể cần 2–3 lần nếu còn phần lãi sau đáo hạn chưa vào nghĩa vụ, harness tối đa 3 lần)
→ "Đã tất toán"; nhà đầu tư "Đã tất toán", thu hồi đủ 10.000.000 gốc. T2 trả đồng hồ.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả mong đợi |
|---|---|---|---|
| OVD-N01 | [UI + công cụ] | Dời business date nhưng quên `Invoke-FxJob 'LA_ARAG'` | DPD đứng ở 0 (D25): lỗi thao tác, chạy job rồi kéo làm mới; không ghi defect |
| OVD-N02 | [API] | Ở DPD 91 gửi số tiền nằm giữa hai lựa chọn (ví dụ 2.000.000) | `422 REPAYMENT_AMOUNT_NOT_SCHEDULED_DUE` "Số tiền phải bằng nghĩa vụ hiện tại từ Fineract: … (hoặc chỉ kỳ quá hạn cũ nhất: …)"; ví không đổi |
| OVD-N03 | [UI + công cụ] | Quá hạn một kỳ (OVD-01) | Không hiện lựa chọn "Chỉ kỳ cũ nhất" (chỉ có khi quá hạn nhiều kỳ) |
| OVD-N04 | [UI + công cụ] | Khi quá hạn, mở "Trả trước một phần gốc" | PRE-N03 |
| OVD-N05 | [UI + công cụ] | Dời từ DPD 10 sang DPD 20 (cùng nhóm 2) | Không có tin quá hạn mới cho người vay hay nhà đầu tư (D61, NTF-N08) |
| OVD-N06 | [UI + công cụ] | Khoản vay `DEFAULTED` mở "Đề nghị điều chỉnh kỳ hạn hoặc gia hạn" | Vẫn gửi được đề nghị cơ cấu (khoản vay đang hoạt động hoặc default) |
| OVD-N07 | [UI + công cụ] | Fineract tắt khi đang quá hạn (REP-N12) | Khóa thanh toán vì số liệu cũ; không thu sai số |
| OVD-N08 | [UI + công cụ] | Trả đủ quá hạn rồi xem nhóm nợ | Về nhóm 1 ngay (chưa có thời gian theo dõi; giới hạn đã biết, không phải defect) |

### 4.10 Cơ cấu: đề nghị, thẩm định, biểu quyết, kết quả

Điều kiện: khoản vay `ACTIVE` (hoặc `DEFAULTED`); lúc `Start` launcher không in cảnh báo thiếu reason code
`LoanRescheduleReason` (mục 2.3); nhà đầu tư nắm Note. Lịch cũ luôn còn hiệu lực cho tới khi Fineract áp dụng lịch mới.

**RSC-01 [UI] Người vay gửi đề nghị gia hạn.** "Chi tiết khoản vay" → "Đề nghị điều chỉnh kỳ hạn hoặc gia hạn" → "Cơ cấu khoản
vay":

1. "Hình thức đề nghị" = "Gia hạn thời hạn vay" ("Thêm kỳ trả và lùi ngày đáo hạn cuối cùng của khoản vay.").
2. "Kỳ trả nợ muốn điều chỉnh *": chọn chip "Kỳ tới".
3. "Số kỳ muốn gia hạn *" = 2 (trợ giúp "Từ 1 đến 120 kỳ. Lịch mới chỉ áp dụng sau khi đề nghị được duyệt.").
4. "Lý do đề nghị *" (bộ đếm "{n}/500"), tick ô điều khoản (chữ do backend trả).
5. "Gửi đề nghị" (không hỏi PIN) → thẻ "Đã gửi đề nghị" với câu "FINORA sẽ thẩm định trước. Nếu đạt, toàn bộ nhà đầu tư đang
   nắm Note phải đồng ý rồi Fineract mới áp dụng lịch mới. Trong thời gian chờ, lịch trả hiện tại vẫn có hiệu lực."; lịch sử
   ghi "Chờ thẩm định".

**RSC-02 [UI] Admin thẩm định (web).** "Vận hành khoản vay" → tab "Yêu cầu cơ cấu": dòng có "Khoản vay", "Đề nghị" = "Gia hạn
thêm kỳ", "Thay đổi", "Ngày đáo hạn", "Lý do người vay nêu", "Gửi lúc". Menu "⋯" → "Thẩm định đạt" → modal "Xác nhận thẩm định
đạt" ("FINORA chỉ xác nhận đề nghị đạt điều kiện. Lịch cũ vẫn có hiệu lực; hệ thống chỉ gửi sang Fineract sau khi toàn bộ nhà
đầu tư còn nắm dư nợ đồng ý."), "Ghi chú (không bắt buộc)" → "Chuyển nhà đầu tư đồng ý" → toast "Đã thẩm định đạt yêu cầu của
khoản vay {x}. Đang chờ toàn bộ nhà đầu tư đồng ý." Mobile người vay: "Chờ nhà đầu tư đồng ý". Chưa gọi Fineract ở bước này.
Nhánh từ chối: "⋯" → "Từ chối" → modal "Từ chối yêu cầu cơ cấu", "Lý do từ chối" bắt buộc (chip gợi ý "Không đáp ứng chính sách
cơ cấu hiện hành", "Thiếu giấy tờ chứng minh thu nhập giảm"; bỏ trống: "Nhập lý do từ chối.") → "Từ chối yêu cầu" → toast "Đã
từ chối yêu cầu cơ cấu của khoản vay {x}." (RSC-N03).

**RSC-03 [UI] Nhà đầu tư biểu quyết.** Tab "Ví" → "Lịch sử ví" → khối "Dành cho nhà đầu tư" → "Đề nghị cơ cấu cần đồng ý" → màn
"Đồng ý cơ cấu khoản vay": thẻ "Khoản vay LN-…", "Gia hạn thêm 2 kỳ trả nợ", "Phần vốn của bạn", "Kỳ bắt đầu điều chỉnh",
"Ngày đáo hạn hiện tại", trạng thái "Chờ bạn quyết định". "Đồng ý lịch mới" → hộp thoại "Đồng ý cơ cấu lịch trả?" / "Sau khi
toàn bộ nhà đầu tư đồng ý, hệ thống sẽ áp dụng lịch trả mới. Quyết định này không thể hoàn tác." → "Đồng ý" → "Đã đồng ý".
Không hỏi PIN, không có ô ghi chú (app gửi `comment = null`).

**RSC-04 [UI] Kết quả.** Trong vài phút: đề nghị của người vay qua "Đang áp dụng lịch mới" tới "Đã áp dụng"; khoản vay có thể
hiện "Đang cơ cấu" ("…Thanh toán tạm khoá cho tới khi lịch mới có hiệu lực.") rồi về "Đang trả nợ"; "Xem lịch trả nợ 8 kỳ"
(6 → 8 kỳ); ngày đáo hạn lùi 2 tháng (evidence: 2027-04-17 → 2027-06-17). Không thu phí (ví không đổi). Ngày đáo hạn mới của
vị thế nhà đầu tư không hiện trên app: kiểm bằng API `GET :8084/api/v1/investments/portfolio` (`maturityDate`). Thông báo:
người vay có tin chờ biểu quyết (`RESCHEDULE_UNDER_INVESTOR_VOTE`) và tin lịch mới, nhà đầu tư có lời mời
(`RESCHEDULE_CONSENT_REQUESTED`) và tin `RESCHEDULED`. Kafka: mỗi topic `reschedule-consent-requested`,
`reschedule-consent-completed`, `rescheduled` đúng một event.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả trên UI | Backend |
|---|---|---|---|---|
| RSC-N01 | [UI] | Gửi thêm đề nghị khi đề nghị trước chưa kết thúc | Form bị thay bằng thẻ "Đề nghị đang xử lý"; nếu gửi được (thiết bị thứ hai) thì câu "Khoản vay đã có một yêu cầu cơ cấu chưa kết thúc" và màn tự tải lại | `409 ACTIVE_RESTRUCTURE_REQUEST_EXISTS` |
| RSC-N02 | [UI] | Bỏ trống/nhập sai form | "Chọn kỳ bắt đầu áp dụng.", "Ngày bắt đầu không được trước hôm nay.", "Số kỳ gia hạn từ 1 đến 120.", "Cho FINORA biết lý do bạn cần cơ cấu.", "Lý do tối đa 500 ký tự.", "Hãy xác nhận điều khoản cơ cấu trước khi gửi." | Không gọi API |
| RSC-N03 | [UI] | Admin từ chối (RSC-02 nhánh từ chối) | Đề nghị "Đã từ chối", có mục "Phản hồi của FINORA"; lịch cũ giữ nguyên. Người vay **không** nhận thông báo (P8 chưa làm) | Fineract không bị gọi |
| RSC-N04 | [UI] | Nhà đầu tư bấm "Từ chối" → "Từ chối đề nghị cơ cấu?" / "Một nhà đầu tư từ chối sẽ kết thúc đề nghị; lịch trả hiện tại tiếp tục có hiệu lực." → "Từ chối" | Nhà đầu tư: "Đã từ chối"; người vay: "Nhà đầu tư không đồng ý"; lịch, ngày đáo hạn không đổi | `INVESTOR_REJECTED`, completion `approved=false`; không request Fineract. Với hai nhà đầu tư: một người từ chối là kết thúc |
| RSC-N05 | [API] | Gửi `confirmedTermsVersion` cũ | App tự tải lại điều khoản | `409 RESTRUCTURE_TERMS_CHANGED` "Điều khoản cơ cấu đã thay đổi, vui lòng đọc và xác nhận lại" |
| RSC-N06 | [UI] | Khoản vay đã tất toán | "Khoản vay đang ở trạng thái “Đã tất toán” nên chưa nhận đề nghị cơ cấu mới." | Qua API: `LOAN_NOT_ACTIVE` "Chỉ khoản vay đang hoạt động hoặc default mới được cơ cấu" |
| RSC-N07 | [API] | `rescheduleFromDate` ở quá khứ | — | `RESCHEDULE_DATE_IN_PAST` "Ngày bắt đầu cơ cấu không được ở quá khứ" |
| RSC-N08 | [API] | Gửi lại đúng đề nghị cùng `Idempotency-Key` | — | Cùng `requestId` (`reschedule.idempotent-submit`) |
| RSC-N09 | [UI + công cụ] | Fineract local thiếu reason code `LoanRescheduleReason` (launcher cảnh báo, không bật worker cơ cấu) rồi đi hết RSC-01…03 | Đề nghị dừng ở "Đã duyệt, chờ áp dụng"; khoản vay "Đang cơ cấu", thanh toán tạm khóa. Nếu worker bật mà thiếu `FINERACT_RESCHEDULE_REASON_ID`: "Đang kiểm tra thêm" | `CREATE_PENDING` (worker tắt) hoặc `MANUAL_REVIEW`; chưa có API gỡ `MANUAL_REVIEW`, khoản vay bị chặn đề nghị mới: bỏ khoản vay này |
| RSC-N10 | [API] | Admin gửi quyết định (khóa mới) cho đề nghị không còn `PENDING_REVIEW` | Web chỉ hiện đề nghị chờ thẩm định | `500 INTERNAL_ERROR` thay vì `409` (code ném `IllegalStateException`): ghi defect đề xuất (mục 7.1) |
| RSC-N11 | [API] | Admin từ chối không có `comment` | Web bắt buộc "Lý do từ chối" | `400 REJECTION_REASON_REQUIRED` |
| RSC-N12 | — | Nhà đầu tư không biểu quyết | Đề nghị chờ mãi "Chờ nhà đầu tư đồng ý" | Không có hạn hay timeout biểu quyết (không hỗ trợ) |

### 4.11 Rút tiền về tài khoản ngân hàng (W3B, provider giả lập)

Điều kiện: ví khả dụng ít nhất vài trăm nghìn (người vay sau giải ngân hoặc nhà đầu tư sau khi nhận trả nợ). Đường vào: nút
nhanh "Rút" ở "Trang chủ" → màn "Rút tiền" (màn gốc "Lịch sử ví" không có nút rút). Policy demo: tối thiểu 50.000 đ, tối đa
50.000.000 đ mỗi ngày nghiệp vụ (giờ Việt Nam), phí 0, chỉ rút số dư khả dụng (Q4, policy cấu hình được `PAYMENT_WITHDRAWAL_*`,
không phải luật).

**WDR-01 [UI] Chính sách và nhãn demo.** Màn "Rút tiền" có ghi chú "Môi trường demo: tiền trong ví là tiền giả lập và lệnh rút
do nhà cung cấp giả lập xử lý, không có tiền thật chuyển tới ngân hàng. Số tài khoản kết thúc bằng 0000 mô phỏng tài khoản không
hợp lệ để thử trường hợp hoàn tiền."; thẻ "Hạn mức rút tiền": "Tối thiểu mỗi lần" 50.000 đ, "Còn được rút hôm nay" 50.000.000
đ, "Phí rút" = "Miễn phí"; chú thích "Hạn mức 50.000.000 đ mỗi ngày (ngày {dd/MM/yyyy}, giờ Việt Nam), hôm nay đã dùng 0 đ. Lệnh
thất bại đã hoàn tiền không tính vào hạn mức."

**WDR-02 [UI] Rút thành công qua callback ký HMAC.**

1. Thẻ "Tài khoản nhận": "Ngân hàng nhận" → chọn một ngân hàng trong danh sách (danh sách lấy từ policy); "Số tài khoản"
   `0123456789`; "Tên chủ tài khoản" `NGUYEN VAN AN`.
2. "Số tiền rút" = 100.000: "Phí rút" 0, "Tiền trừ khỏi ví" 100.000. Ghi chú "Tiền được trừ khỏi ví ngay khi lệnh được nhận.
   Nếu lệnh chi không thành công, toàn bộ số tiền được hoàn lại ví."
3. "Rút tiền" → PIN "Xác nhận rút tiền".
4. Màn "Lệnh rút tiền" tự cập nhật 3 giây/lần trong 2 phút ("Đang tự cập nhật trạng thái…"): "Đã nhận lệnh" → "Đang gửi lệnh
   chi" → "Chờ xác nhận" → "Hoàn tất". Kết quả "Đã rút 100.000 đ (giả lập)" / "Nhà cung cấp giả lập đã xác nhận lệnh chi tới
   {ngân hàng} ******6789 lúc {giờ}. Môi trường demo không chuyển tiền thật tới ngân hàng."
5. Dòng chi tiết: "Mã lệnh", "Ngân hàng nhận", "Số tài khoản" (che), "Chủ tài khoản", "Số tiền rút", "Phí rút", "Tạo lúc", "Hoàn
   tất lúc".

Bất biến tiền: khả dụng −100.000 đúng một lần ngay lúc nhận lệnh, đang giữ không đổi (tiền đi vào clearing
`PAYOUT_PROVIDER_CLEARING`, không dùng `HELD`, D41); "Còn được rút hôm nay" còn 49.900.000. Thông báo `WITHDRAWAL_COMPLETED`.

**WDR-03 [UI] Lịch sử.** "Lịch sử rút tiền": thẻ "Rút về {ngân hàng}", "******6789 · {giờ}", số tiền "−100.000 đ"; trang 20 lệnh,
"Xem thêm lệnh cũ hơn". Không màn nào hiện đủ số tài khoản.

**WDR-04 [UI] Tài khoản sai → thất bại và hoàn tiền.** Rút 60.000 về số tài khoản `123450000` → trạng thái "Thất bại · đã hoàn
tiền"; kết quả "Rút tiền không thành công", lý do "Tài khoản nhận không hợp lệ (provider giả lập)", "Tiền đã hoàn về ví: 60.000 đ
đã trở lại số dư khả dụng."; "Mã lỗi" `MOCK_ACCOUNT_INVALID`; nút "Tạo lệnh rút mới", "Về ví". Bất biến tiền: khả dụng −60.000
rồi +60.000 (ròng 0); hạn mức đã dùng không tăng. Lịch sử: số tiền gạch ngang. Thông báo `WITHDRAWAL_FAILED`.

**WDR-05 [UI + công cụ] Mất callback và hàng đợi admin "Lệnh rút tiền chờ đối soát".** Đặt `PAYMENT_MOCK_PAYOUT_CALLBACK_ENABLED=false`
trong `finora-payment/.env` (không có trong danh sách `-DrillEnv`), `-Action Restart -Services payment`. Rút 100.000:

1. Mobile dừng ở "Chờ xác nhận"; sau 2 phút tự cập nhật thì "Đã dừng tự cập nhật. Bấm “Kiểm tra lại”…".
2. Web "Vận hành khoản vay" → "Sự cố giao dịch" → bảng "Lệnh rút tiền chờ đối soát" ("Tiền đã trừ ví, chỉ tra cứu theo mã lệnh,
   không chi lại"): dòng có "Lệnh rút", "Chủ ví", "Số tiền", "Tài khoản nhận" (che), "Nhà cung cấp", "Lần gửi / tra cứu",
   "Tình trạng". "Tra cứu lại" bị khóa, tooltip "Lệnh chưa vào đối soát, hệ thống vẫn đang chờ kết quả từ nhà cung cấp."
3. Khoảng 2 phút sau khi provider nhận lệnh (`PAYMENT_WITHDRAWAL_CALLBACK_TIMEOUT`), Payment tự tra cứu provider theo
   `withdrawalId` → "Hoàn tất"; "Làm mới" thì dòng biến mất. Mobile "Kiểm tra lại" → "Hoàn tất".

Bất biến tiền: trừ ví đúng một lần lúc nhận lệnh, không có lệnh chi thứ hai. Xóa biến và Restart Payment sau ca. "Tra cứu lại"
chỉ bật khi lệnh `RECONCILIATION_REQUIRED` đã hết lượt tra cứu tự động (`nextAttemptAt` rỗng, sau 10 lần) → toast "Đã hẹn tra
cứu lại lệnh rút {id} với nhà cung cấp; kết quả cập nhật sau lượt xử lý kế tiếp."; trạng thái này không có cách tạo ổn định trên
stack E2E (WDR-N11).

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả trên UI | Backend và bất biến tiền |
|---|---|---|---|---|
| WDR-N01 | [UI] | Số tiền 49.000 | "Mỗi lần rút tối thiểu 50.000 đ." tại ô số tiền | Không gọi API. Qua API: `422 WITHDRAWAL_BELOW_MINIMUM` "Mỗi lần rút tối thiểu 50000 đ"; ví không đổi |
| WDR-N02 | [UI] | Số tiền lớn hơn khả dụng (nhưng dưới hạn mức còn lại) | "Vượt số dư khả dụng {khả dụng}." | Không gọi API. Qua API: `422 WITHDRAWAL_INSUFFICIENT_BALANCE` "Số dư khả dụng không đủ để rút số tiền này" |
| WDR-N03 | [UI] | Sau WDR-02, nhập 50.000.000 (hạn mức cộng dồn trong ngày) | "Hôm nay bạn còn được rút tối đa 49.900.000 đ." (app kiểm hạn mức trước số dư) | Qua API: `422 WITHDRAWAL_DAILY_LIMIT` "Vượt hạn mức rút trong ngày; hôm nay còn được rút {n} đ" (backend kiểm hạn mức trước số dư); ví không đổi |
| WDR-N04 | [UI] | Dùng hết 50.000.000 trong ngày (cần ví lớn: nạp mock tối đa 100.000.000 mỗi lần) rồi mở lại màn | Cảnh báo "Hôm nay bạn đã dùng hết hạn mức rút 50.000.000 đ. Hạn mức được tính lại theo ngày (giờ Việt Nam).", nút khóa | Hai lệnh đồng thời vượt hạn mức: lệnh sau `422 WITHDRAWAL_DAILY_LIMIT` (khóa dòng ví) |
| WDR-N05 | [UI] | Bấm "Rút tiền" hai lần hoặc gửi lại cùng thông tin sau lỗi mạng (tắt Wi-Fi ngay sau khi bấm, bật lại, bấm lần nữa) | Lỗi mạng: "…Lệnh có thể đã được ghi nhận: bấm "Rút tiền" lần nữa với đúng thông tin để kiểm tra, ví không bị trừ hai lần." Lần sau mở đúng lệnh cũ | Cùng `withdrawalId`, `201`, ví trừ một lần (`withdrawal.debited-once`) |
| WDR-N06 | [UI] | Gửi lại sau khi đổi thông tin lệnh trong cùng phiên | "Thông tin lệnh rút đã đổi so với lần gửi trước. Bấm "Rút tiền" lần nữa để tạo lệnh mới." | `WITHDRAWAL_IDEMPOTENCY_CONFLICT`; không trừ cho lần bị từ chối |
| WDR-N07 | [UI] | Tên "NGUYEN VAN 1", tên 1 ký tự, bỏ trống; số tài khoản `12345`; không chọn ngân hàng | "Tên chủ tài khoản chỉ gồm chữ cái và khoảng trắng.", "Tên chủ tài khoản tối thiểu 2 ký tự.", "Nhập tên chủ tài khoản.", "Số tài khoản gồm 6–19 chữ số.", "Chọn ngân hàng nhận." | Không gọi API |
| WDR-N08 | [UI] | 11 lệnh rút trong một giờ (số nhỏ) | Lệnh thứ 11: "Bạn thao tác quá nhanh, vui lòng thử lại sau {N} giây" | `429 RATE_LIMITED`; không trừ ví cho lệnh bị chặn; khởi động lại Payment thì bộ đếm về 0 |
| WDR-N09 | [API] | Mã ngân hàng không có trong danh sách; tên chủ tài khoản sai định dạng | — | `WITHDRAWAL_BANK_UNSUPPORTED` "Ngân hàng nhận chưa được hỗ trợ"; `WITHDRAWAL_HOLDER_INVALID`; ví không đổi |
| WDR-N10 | [UI] | Đóng bảng PIN | Không có lệnh | Ví không đổi |
| WDR-N11 | [UI + công cụ] | Tắt Payment (T5) ngay sau khi lệnh "Đã nhận lệnh", bật lại sau 1 phút | Màn trạng thái có thể dừng tự cập nhật ("Đã dừng tự cập nhật. Bấm “Kiểm tra lại”…"); "Kiểm tra lại" sau khi bật lại thấy "Hoàn tất" hoặc "Đang đối soát" ("Đang xác minh kết quả lệnh rút"…) | Hết lease → tra cứu provider theo `withdrawalId`, không gửi lệnh chi thứ hai khi chưa rõ (D42). Provider giả lập giữ kết quả trong bộ nhớ nên sau restart có thể gửi lại đúng mã. Ví chỉ trừ một lần |
| WDR-N12 | [API] | Gọi `POST :8082/api/v1/wallets/callbacks/mock-payout` với `X-Payout-Signature` sai | — | `401`, không đổi trạng thái lệnh hay ví |

### 4.12 Web "Vận hành khoản vay": sự cố giao dịch, đối soát, thu hồi

Điều kiện: admin web. Trang "Vận hành khoản vay" (`/loans/operations`) có tab "Quá hạn & thu hồi", "Yêu cầu cơ cấu", "Đối
soát", "Sự kiện trả nợ chờ ghép", "Sự cố giao dịch"; nút "Làm mới" làm mới cả hàng đợi Loan, Payment, Investment. Mọi thao tác
sự cố chỉ tra cứu hoặc chạy lại đúng bước còn dở theo mã đã gửi; không trừ hay chuyển tiền lần hai.

**INC-01 [UI] Tab "Sự cố giao dịch" khi không có sự cố.** Câu dẫn "Tiền đã đi nhưng một bên chưa ghi xong…"; năm bảng: "Trả nợ
chưa ghi xong vào hệ thống lõi", "Giải ngân chưa có kết quả chuyển tiền", "Lệnh rút tiền chờ đối soát", "Đã giải ngân, hệ thống
lõi chưa ghi khoản vay", "Lệnh góp vốn chưa khớp với ví"; mỗi bảng trống có dấu tích xanh. Số trên tab là tổng năm hàng đợi
(chỉ hiện khi cả năm tải được; có thể gồm lệnh giải ngân đang chạy bình thường vài giây).

**INC-02 [UI + công cụ] Trả nợ mất phản hồi → "Đối soát".** Tạo sự cố như REP-N07. Bảng 1: dòng "Cần đối soát", "Lỗi gần nhất"
`FINERACT_REPAYMENT_OUTCOME_UNKNOWN`, link "Bằng chứng". Gỡ toxic, menu "⋯" → "Đối soát" → toast "Hệ thống lõi đã ghi nhận giao
dịch {id}; tiền sẽ được chia cho nhà đầu tư ở lượt xử lý kế tiếp." (hoặc worker tự tìm thấy trước) → "Làm mới": dòng biến mất.
Mobile "Hoàn tất". Bất biến: trừ ví một lần, Fineract một giao dịch.

**INC-03 [UI + công cụ] Trả nợ mất request → "Ghi lại vào hệ thống lõi" (B32).** Tạo sự cố như REP-N08. "Đối soát" → toast "Hệ
thống lõi chưa có giao dịch {id}; giao dịch vẫn chờ đối soát." Mục "Ghi lại vào hệ thống lõi" bị khóa, tooltip "Ghi lại được từ
{thời điểm}" (`coreRepostAvailableAt`, mặc định 15 phút; 1 phút nếu đã đặt `-DrillEnv`). Qua thời điểm đó bấm "Làm mới" để mở
khóa → modal "Ghi lại vào hệ thống lõi" → "Tra cứu rồi ghi lại" → toast "Đã ghi giao dịch {id} vào hệ thống lõi; tiền sẽ được
chia cho nhà đầu tư ở lượt xử lý kế tiếp." Bất biến: trừ ví một lần; Fineract đúng một giao dịch `FINORA-REPAY-<id>` đề ngày ghi
nhận ban đầu; `coreRepostCount = 1`, lưu người và thời điểm ghi lại.

**INC-04 [UI + công cụ] Giải ngân chưa có kết quả.** Trong DISB-N02, bảng 2 hiện lệnh "Đang chuyển tiền"/"Đang tra cứu lại"; khi
dòng ở trạng thái đối soát thì có "Tra cứu lại" → toast "Đã hẹn tra cứu lại lệnh giải ngân của hợp đồng {HĐ} với đối tác chuyển
tiền." (chỉ hẹn tra cứu theo `sagaId`, không chuyển tiền). Cửa sổ này ngắn vì Payment tự đối soát ngay khi bật lại.

**INC-05 [UI] Saga cần ghi lại core.** Khi có dòng ở bảng 4: "Ghi lại" → modal "Ghi lại khoản vay vào hệ thống lõi", "Ghi chú xử
lý" bắt buộc (tối đa 500 ký tự; bỏ trống: "Ghi lại đã kiểm tra gì và xử lý nguyên nhân ra sao.") → "Đưa về hàng chờ ghi lại" →
toast "Đã đưa hợp đồng {HĐ} về hàng chờ ghi vào hệ thống lõi." Booking đối chiếu theo số hợp đồng nên không tạo khoản vay thứ
hai. (Chưa có cách tạo `REPAIR_REQUIRED` ổn định trên stack E2E.)

**INC-06 [UI + công cụ] Lệnh góp vốn chưa khớp với ví.** Tắt Payment (T5) khoảng 10 phút trong lúc nhà đầu tư đặt lệnh
(FUND-N19) để lệnh `PENDING_FUNDS` thử tới 3 lần → bảng 5: "Chưa rõ đã giữ tiền", "Lần thử" ≥ 3. Bật Payment → "Thử lại" →
toast "Đã hẹn thử lại lệnh {ref}; kết quả cập nhật sau lượt xử lý kế tiếp." → lệnh `COMMITTED` (hoặc `REJECTED` nếu ví không
đủ), khoản giữ đúng một lần (đối chiếu `GET :8080/api/v1/transactions/holds?orderReference=`).

**INC-07 [UI + công cụ] Tab "Đối soát" (số liệu servicing cũ).** `docker stop finora-fineract`, chờ quá chu kỳ đồng bộ: dòng
khoản vay "Cần đối soát". `docker start finora-fineract` → "Đối soát lại" → toast "Đã đối soát {x}, số liệu khớp với hệ thống
lõi." Phần "Sai lệch đang mở" chỉ đọc.

**INC-08 [UI + công cụ] Tab "Quá hạn & thu hồi".** Khi có khoản vay quá hạn (OVD-01/OVD-03): dòng với mức "Cần chú ý" (nhóm 2)
hoặc "Nợ xấu" (nhóm 3); bấm dòng → ngăn "Ghi nhận mới" ("Đã liên hệ" / "Hẹn trả" / "Ghi chú") và "Lịch sử xử lý". Không có thao
tác sửa DPD, nhóm nợ hay số dư.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả mong đợi |
|---|---|---|---|
| INC-N01 | [API] | Ghi lại trước khoảng an toàn | `409 REPAYMENT_REPOST_TOO_EARLY` "Lần ghi hệ thống lõi gần nhất chưa đủ thời gian an toàn; chỉ ghi lại được từ {thời điểm}" |
| INC-N02 | [API] | Hai admin ghi lại cùng lúc; hoặc đối soát/ghi lại khoản không ở `RECONCILIATION_REQUIRED` | Người sau: `409 REPAYMENT_NOT_RECONCILABLE` |
| INC-N03 | [API] | "Tra cứu lại" giải ngân không ở trạng thái đối soát | `409 DISBURSEMENT_NOT_RECONCILABLE` "Chỉ lệnh giải ngân đang đối soát mới được tra cứu lại" |
| INC-N04 | [API] | Requeue saga không ở `REPAIR_REQUIRED`; requeue thiếu `note` | `409 DISBURSEMENT_SAGA_NOT_REPAIRABLE`; `400` |
| INC-N05 | [API] | "Tra cứu lại" lệnh rút không ở đối soát | `409 WITHDRAWAL_NOT_RECONCILABLE` "Chỉ lệnh rút đang đối soát mới được tra cứu lại" |
| INC-N06 | [UI] | Ngăn "Ghi nhận mới": "Hẹn trả" thiếu ngày/số tiền, ngày ở quá khứ; "Ghi chú" trống | "Nhập ngày hẹn trả và số tiền.", "Ngày hẹn trả không được trước hôm nay.", "Nhập nội dung ghi chú." |
| INC-N07 | [UI] | Lỗi khi thao tác (ví dụ service tắt) | Khối lỗi trên đầu tab với `message`, mã, "mã truy vết" và "Thử lại" (chạy lại đúng thao tác) |

### 4.13 Web "Bằng chứng blockchain" (W3D)

Điều kiện: admin web; một khoản vay đã giải ngân (và đã trả kỳ nếu muốn có proof `REPAYMENT`). Mặc định ledger `MOCK`. ID hồ
sơ vay dạng số (không phải `LA-…`) đọc ở đầu trang chi tiết gọi vốn ("#{id}, hạng …"). Câu cố định trên trang: proof là bằng
chứng kỹ thuật về toàn vẹn dữ liệu, không phải chữ ký số hay chứng cứ pháp lý (`NEEDS_LEGAL_REVIEW`).

**BC-01 [UI] Tra cứu theo khoản vay.** Sidebar "Kiểm toán" → "Bằng chứng blockchain" (hoặc nút "Bằng chứng blockchain" ở chi
tiết gọi vốn, hoặc link "Bằng chứng" ở "Sự cố giao dịch"). Chọn "Hồ sơ vay", nhập ID số → "Tra cứu" → banner "Đang tra theo Hồ
sơ vay {id}: gồm mọi trạng thái, cũ nhất trước. Chọn một trạng thái ở trên sẽ bỏ tra cứu." Bảng: các mốc hợp đồng, giải ngân,
phát hành Note (và trả nợ), cột "Trạng thái" "Đã xác nhận", "Biên nhận" = "MOCK, không có trên Fabric", "Giao dịch Fabric" = "Không
có, biên nhận MOCK". Mỗi mốc đúng một dòng (`proof.milestones-confirmed`). Các loại tra cứu khác: "Hợp đồng" (`LC-…`), "Giải
ngân" (mã saga), "Trả nợ" (mã giao dịch trả nợ), "Phát hành Note", "Chuyển nhượng Note".

**BC-02 [UI] Chi tiết proof MOCK.** "⋯" → "Xem chi tiết và kiểm tra": khối cảnh báo MOCK ("Receipt MOCK chỉ dùng cho demo; bằng
chứng này KHÔNG được ghi lên Hyperledger Fabric"); mục "Hash đã neo", "Event nguồn" (có `eventId`), "Biên nhận sổ cái" ("Mã biên
nhận MOCK (không phải giao dịch Fabric)"), "Đối chiếu gần nhất".

**BC-03 [UI] Kiểm tra không kèm dữ liệu gốc.** Mục "Kiểm tra toàn vẹn", để trống "Dữ liệu gốc của event (không bắt buộc)" →
"Kiểm tra": "Dữ liệu gốc so với hash đã neo" = "Không kiểm"; "Cơ sở dữ liệu so với sổ cái Fabric" = "MOCK, không có trên Fabric".

**BC-04 [UI + công cụ] Kiểm tra kèm dữ liệu gốc.** Lấy `data` của event nguồn bằng T3 với `eventId` ở mục "Event nguồn" và topic
theo loại proof; dán vào ô → "Kiểm tra": "Khớp hash đã neo" + "MOCK, không có trên Fabric" (`proof.artifact-recomputed`).

**BC-05 [UI + công cụ] Neo biên nhận hợp đồng (D75).** Với proof hợp đồng, `data.pdfReceiptHash` trong artifact phải bằng SHA-256
của tệp "Trang biên nhận chữ ký" tải từ mobile/API: PowerShell `(Get-FileHash -Algorithm SHA256 <tệp biên nhận>).Hash.ToLower()`.

**BC-06 [UI + công cụ] Ledger Fabric.** Stack `Start -WithFabric` (mục 2.3). Đi một khoản vay mới tới giải ngân: proof "Biên nhận"
= "Trên Fabric", "Giao dịch Fabric" là 64 ký tự hex, khối dạng `finora-channel/<n>`; kiểm tra kèm dữ liệu gốc: "Khớp hash đã neo"
+ "Khớp sổ cái". Tổ chức kiểm toán đọc độc lập (PowerShell):
`powershell -ExecutionPolicy Bypass -File docker/fabric-network/fabric-network.ps1 -Action Proof -Org org2 -ProofId <proofId>` →
dòng `[fabric]` kèm JSON cùng `payloadHash`, `submitterMspId = Org1MSP`. Xong: `fabric-network.ps1 -Action Down`.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả mong đợi |
|---|---|---|---|
| BC-N01 | [UI] | Dán `{"a":1` (thiếu ngoặc) | "Dữ liệu gốc không phải JSON hợp lệ. Kiểm tra dấu ngoặc, dấu phẩy và dấu nháy kép."; không gọi API |
| BC-N02 | [UI] | Dán `[1,2]` hoặc `"abc"` | "Dữ liệu gốc phải là một object JSON: phần data của event nguồn." |
| BC-N03 | [UI + công cụ] | Dán `data` đúng nhưng thêm một field (ví dụ `"qa":1`) hoặc đổi một chữ số | "Không khớp hash đã neo" (`MISMATCHED`, `proof.artifact-tamper-detected`); dữ liệu proof không đổi |
| BC-N04 | [UI + công cụ] | Dán cả envelope (`eventId`, `occurredAt`, `data`…) thay vì riêng `data` | "Không khớp hash đã neo": hash tính trên `data` |
| BC-N05 | [UI] | Ô tra cứu trống; "Hồ sơ vay" với `LA-…`; mã dài hơn 100 ký tự | "Nhập mã tham chiếu cần tra."; "ID hồ sơ vay là số nguyên dương, ví dụ 123 (không phải mã hồ sơ dạng LA-...)."; "Mã tham chiếu dài tối đa 100 ký tự." |
| BC-N06 | [UI] | Đang tra cứu rồi chọn một tab trạng thái | Tra cứu bị bỏ, không báo lỗi. Qua API gửi cả hai: `400 PROOF_FILTER_UNSUPPORTED` "Lọc theo trạng thái chỉ dùng khi không tra theo tham chiếu" |
| BC-N07 | [UI] | "Đưa lại hàng đợi gửi" với proof không phải `DEAD_LETTER` | Mục bị khóa, tooltip "Chỉ proof dừng gửi (DEAD_LETTER) mới đưa lại hàng đợi được."; qua API `409 PROOF_NOT_DEAD_LETTER` |
| BC-N08 | [UI + công cụ] | Fabric: `docker stop finora-fabric-peer0-org2`, tạo một mốc mới (ví dụ giải ngân khoản vay khác); sau vài phút `docker start finora-fabric-peer0-org2` | Proof "Chờ gửi"/"Đang gửi" với lỗi `FABRIC_ENDORSE_FAILED` (thiếu endorsement Org2); bật lại → "Đã xác nhận" đúng một lần, "Khớp sổ cái". Tiền/hợp đồng ở service nguồn không bị chặn |
| BC-N09 | [UI + công cụ] | Như BC-N08 nhưng để peer tắt tới khi hết 10 lần thử (lùi 5 giây → 5 phút, khoảng 20–25 phút) | Tab "Dừng gửi" có proof; bật peer → "Đưa lại hàng đợi gửi" (không có hộp thoại xác nhận) → toast "Đã đưa proof {mã} về hàng đợi; hệ thống sẽ gửi lại lên sổ cái ở lượt kế tiếp." → "Đã xác nhận". Ledger MOCK không bao giờ lỗi nên không có `DEAD_LETTER` trên stack mặc định |
| BC-N10 | [UI] | Rà mọi màn với ledger MOCK | Không chỗ nào hiện proof MOCK như đã lên chain: không có transaction 64 hex, kết quả chain không bao giờ "Khớp sổ cái". Badge biên nhận và kết quả đối chiếu cùng nhãn "MOCK, không có trên Fabric", dòng phụ "Dữ liệu demo, không ghi lên Hyperledger Fabric" (B85) |
| BC-N11 | [API] | Token người vay gọi `GET :8080/api/v1/blockchain/proofs` | `403` (chỉ `ROLE_ADMIN`) |

### 4.14 Thông báo và push (W3F)

Điều kiện: người vay và nhà đầu tư đã đi qua các mốc của mục 4.4–4.11. Thông báo trong app luôn được tạo (không tắt được);
push chỉ tạo khi thiết bị đã đăng ký và nhóm tin đang bật. Stack E2E dùng provider `log`: bản ghi gửi có provider
`LOG_MOCK`, không tới máy thật.

**NTF-01 [UI] Hộp thư, số chưa đọc, đánh dấu đã đọc (mobile).**

1. Tab "Trang chủ": chuông ở đầu trang có badge số tin chưa đọc ("9+" khi trên 9).
2. Bấm chuông → màn "Thông báo": dòng phụ "{n} tin chưa đọc" hoặc "Bạn đã đọc hết"; tin nhóm theo "Hôm nay", "Hôm qua",
   "dd/mm/yyyy", "Trước đó".
3. Tin chưa đọc có chấm đỏ, tiêu đề đậm. Bấm vào → đổi sang đã đọc ngay (app gọi `POST /api/v1/notifications/{id}/read`).
   Quay lại "Trang chủ": badge giảm 1. Tin không mở màn chi tiết.
4. Bấm biểu tượng "Đánh dấu tất cả đã đọc" (góc phải, chỉ hiện khi còn tin chưa đọc) → "Bạn đã đọc hết", badge mất. App
   gọi từng tin một, không dùng `read-all`.
5. Kiểm câu chữ: tiêu đề và nội dung do backend tạo; số tiền hiện dạng `842.500 đ` (app đổi từ `842500.00 VND`); nhãn nhóm,
   biểu tượng, màu do app chọn theo `type`/`category`.

API (T7, tùy chọn): `GET http://localhost:8086/api/v1/notifications/unread-count` khớp badge;
`GET http://localhost:8086/api/v1/notifications?limit=100` có `type`, `category`, `businessReference`, `occurredAt`.

**NTF-02 [UI] Mốc nào báo cho ai.** Sau mỗi mốc, mở "Thông báo" của đúng tài khoản. Mỗi mốc một lần chỉ có một tin cho mỗi
người nhận (kể cả khi Kafka giao lại event).

| Mốc (ca tạo mốc) | Người vay: `type` — tiêu đề trong app | Nhà đầu tư: `type` — tiêu đề trong app | Nhóm |
|---|---|---|---|
| Lên sàn gọi vốn (FUND-01) | `LOAN_FUNDING_OPENED` — "Hồ sơ vay đã lên sàn gọi vốn" | — | FUNDING |
| Đủ vốn (FUND-04) | `LOAN_FULLY_FUNDED` — "Hồ sơ vay đã đủ vốn" | — (gộp vào lời mời ký) | FUNDING |
| Hết hạn gọi vốn (FUND-N14) | `LOAN_FUNDING_EXPIRED` — "Đợt gọi vốn đã kết thúc" | `INVESTMENT_FUNDING_EXPIRED` — "Khoản đầu tư đã được hủy" | FUNDING |
| Hủy sau khi đủ vốn (CTR-N04) | `LOAN_FUNDING_CANCELLED` — "Khoản vay không được giải ngân" | `INVESTMENT_FUNDING_CANCELLED` — "Khoản đầu tư đã được hủy" | FUNDING |
| Mời ký (CTR-01) | — | `INVESTOR_SIGNATURE_REQUESTED` — "Mời bạn ký hợp đồng cho vay" | CONTRACT |
| Đến lượt người vay ký (CTR-02) | `BORROWER_SIGNATURE_REQUESTED` — "Đến lượt bạn ký hợp đồng" | — | CONTRACT |
| Hợp đồng hiệu lực (CTR-02) | `CONTRACT_ACTIVATED` — "Hợp đồng đã có hiệu lực" | `INVESTOR_CONTRACT_ACTIVATED` — "Hợp đồng cho vay đã có hiệu lực" | CONTRACT |
| Giải ngân, phát hành Note (DISB-01) | `LOAN_DISBURSED` — "Khoản vay đã được giải ngân" | `NOTES_ISSUED` — "Khoản đầu tư đã bắt đầu" | DISBURSEMENT |
| Khoản trả được ghi nhận (REP-05, PRE-01) | `REPAYMENT_RECORDED` — "Đã ghi nhận khoản trả nợ" | `REPAYMENT_CREDITED` — "Đã nhận khoản trả nợ" (chỉ trong app) | REPAYMENT |
| Tất toán (PRE-02, OVD-06) | `LOAN_SETTLED` — "Khoản vay đã tất toán" | `EARLY_SETTLEMENT` — "Khoản vay đã tất toán sớm" / `SETTLED` — "Khoản vay đã tất toán" | REPAYMENT |
| Bắt đầu quá hạn (OVD-01) | `LOAN_OVERDUE` — "Khoản vay đã quá hạn" | `DELINQUENCY_CHANGED` — "Khoản vay đang quá hạn" (chỉ khi là mốc) | DELINQUENCY |
| Lên nhóm nợ (OVD-06) | `OVERDUE_STAGE_CHANGED` — "Khoản vay chuyển nhóm nợ" | `DELINQUENCY_CHANGED` / `BAD_DEBT_MILESTONE` (chỉ khi là mốc) | DELINQUENCY |
| Nợ xấu (OVD-03) | `LOAN_DEFAULTED` — "Khoản vay đã chuyển nợ xấu" | `BAD_DEBT_MILESTONE` — "Cập nhật rủi ro khoản vay" | DELINQUENCY |
| Khắc phục (OVD-02, OVD-05) | `OVERDUE_CURED` — "Khoản vay đã hết quá hạn" | `DELINQUENCY_CURED` — "Khoản vay đã khắc phục quá hạn" | DELINQUENCY |
| Cơ cấu qua thẩm định (RSC-02) | `RESCHEDULE_UNDER_INVESTOR_VOTE` — "Yêu cầu cơ cấu đã qua thẩm định" | `RESCHEDULE_CONSENT_REQUESTED` — "Cần bạn biểu quyết cơ cấu" | RESTRUCTURING |
| Kết quả biểu quyết (RSC-03, RSC-N04) | `RESCHEDULE_APPROVED_BY_INVESTORS` — "Nhà đầu tư đã đồng ý cơ cấu" / `RESCHEDULE_REJECTED_BY_INVESTORS` — "Yêu cầu cơ cấu không được chấp thuận" | — | RESTRUCTURING |
| Lịch mới có hiệu lực (RSC-04) | `LOAN_RESCHEDULED` — "Lịch trả nợ mới đã có hiệu lực" | `RESCHEDULED` — "Lịch trả nợ đã được cơ cấu" | RESTRUCTURING |
| Rút tiền (WDR-02, WDR-04) | `WITHDRAWAL_COMPLETED` — "Rút tiền thành công" / `WITHDRAWAL_FAILED` — "Rút tiền không thành công" | Như người vay | WALLET |

Không có tin khi admin từ chối đề nghị cơ cấu (P8 chưa làm). Tin nhắc kỳ (`INSTALLMENT_DUE_SOON`, `INSTALLMENT_DUE_TODAY`) và tin
giữ/hủy khoản trả kỳ (`INSTALLMENT_PAYMENT_HELD`, `INSTALLMENT_PAYMENT_CANCELLED`) chỉ có khi bật
`NOTIFICATION_DUE_REMINDER_ENABLED` (mặc định tắt, D64). Push quá hạn/nợ xấu dùng tiêu đề "FINORA" và nội dung chung. Thông
báo trả hết nợ không push (đã có push tất toán).

**NTF-03 [UI] Cài đặt thông báo.** Tab "Hồ sơ" → thẻ "Thông báo & Cài đặt" → dòng "Cài đặt thông báo".

1. Thẻ "Thông báo đẩy trên thiết bị này" hiện trạng thái:

   | Nhãn | Khi nào |
   |---|---|
   | "ĐANG BẬT" | Thiết bị đã đăng ký push |
   | "ĐANG KIỂM TRA" | Đang hỏi quyền/đăng ký |
   | "CHƯA CHO PHÉP" | Hệ điều hành chưa cho phép (còn hỏi lại được, hoặc phải "Mở cài đặt máy") |
   | "CHƯA CẤU HÌNH" | Bản cài thiếu `EXPO_PUBLIC_EAS_PROJECT_ID` (mặc định của `.env.example`) |
   | "KHÔNG HỖ TRỢ" | Nền tảng web |
   | "DỮ LIỆU GIẢ" | `notification` nằm trong `EXPO_PUBLIC_MOCK_DOMAINS` |
   | "CHƯA ĐĂNG KÝ ĐƯỢC" | Lấy token hoặc đăng ký thiết bị lỗi (hiện thông điệp lỗi) |
   | "CHƯA BẬT" | Chưa bật push |

2. Thẻ "Nhận thông báo đẩy theo nhóm" có 8 công tắc: "Gọi vốn" (FUNDING), "Hợp đồng và chữ ký" (CONTRACT), "Giải ngân"
   (DISBURSEMENT), "Trả nợ và nhắc lịch" (REPAYMENT), "Quá hạn" (DELINQUENCY), "Cơ cấu khoản vay" (RESTRUCTURING),
   "Ví và rút tiền" (WALLET), "Thông báo khác" (GENERAL). Ghi chú: "Thông báo trong ứng dụng luôn được lưu ở mục Thông báo,
   kể cả khi bạn tắt thông báo đẩy của một nhóm." và câu màn hình khóa chỉ báo có cập nhật mới, không hiện số tiền hay mã
   khoản vay.
3. Tắt một nhóm: công tắc khóa trong lúc lưu (`PUT /api/v1/notifications/preferences` chỉ gửi nhóm đó). Thoát ra vào lại vẫn
   giữ trạng thái. Lưu lỗi thì công tắc bật lại và hiện ghi chú màu hổ phách.

**NTF-04 [UI + công cụ] Đường push giả lập `LOG_MOCK` không cần development build.** Không có projectId thì app không đăng ký
thiết bị, nên đăng ký một token Expo giả bằng API như harness (T7, token của người dùng cần kiểm):

```powershell
$dev = Invoke-E2EHttp -Method POST -Uri 'http://localhost:8086/api/v1/notifications/devices' -Token $token -Body ([ordered]@{ token = "ExponentPushToken[qa-$([guid]::NewGuid().ToString('N').Substring(0,12))]"; platform = 'ANDROID'; appVersion = 'qa' })
"$($dev.Status) $($dev.Json.deviceId) $($dev.Json.status)"
```

Làm một thao tác sinh tin (ví dụ rút tiền, `WDR-02`), rồi admin (token admin) đọc
`GET http://localhost:8086/api/v1/notifications/admin/push-deliveries?status=SENT&size=100` (lặp với `CONFIRMED`):
có dòng của người dùng đó, provider `LOG_MOCK`, nhóm đúng (`WALLET`), tiêu đề/nội dung không có chữ số, tên hay mã khoản vay
(D63). Không dòng nào `FAILED`/`DEAD`.

**NTF-05 [UI + công cụ] Push thật trên development build** (N/A nếu không có EAS projectId và máy thật).

1. Đặt `EXPO_PUBLIC_EAS_PROJECT_ID` trong `.env` mobile, cài development build lên máy thật.
2. Trong `finora-notification/.env` (git-ignore) đặt `NOTIFICATION_PUSH_PROVIDER=expo` (thêm `EXPO_ACCESS_TOKEN` nếu project bật
   enhanced push security), rồi `-Action Restart -Services notification`. Đặt lại `log` sau khi kiểm.
3. Đăng nhập → app hỏi quyền một lần → cho phép → "ĐANG BẬT".
4. Tạo một mốc (ví dụ nạp rồi rút tiền) → máy nhận push; màn hình khóa chỉ có câu chung; bấm push mở "Thông báo". App đang
   mở thì hiện banner, không âm thanh.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả trên UI | Backend |
|---|---|---|---|---|
| NTF-N01 | [UI] | Bản cài không có projectId (mặc định) | "CHƯA CẤU HÌNH": "Bản cài đặt này chưa cấu hình EAS projectId nên chưa nhận được thông báo đẩy."; không hiện hộp thoại quyền của hệ điều hành; hộp thư trong app vẫn đủ tin | Không đăng ký thiết bị; admin `push-deliveries` không có lần gửi nào của người dùng (trừ khi đăng ký token giả ở NTF-04) |
| NTF-N02 | [UI] | Có projectId nhưng chạy Expo Go hoặc emulator | "Không lấy được mã nhận thông báo trên thiết bị này. Máy ảo và Expo Go không nhận được thông báo đẩy từ xa." | Không đăng ký thiết bị |
| NTF-N03 | [UI] | Development build, từ chối quyền thông báo | "CHƯA CHO PHÉP"; nút "Bật thông báo đẩy" hoặc "Mở cài đặt máy" khi hệ điều hành không cho hỏi lại | Không đăng ký thiết bị |
| NTF-N04 | [UI + công cụ] | NTF-04 xong, tắt nhóm "Ví và rút tiền", rút tiền thành công | Tin "rút tiền thành công" vẫn có trong hộp thư | Không có lần gửi push mới nhóm `WALLET` cho thiết bị đó |
| NTF-N05 | [UI + công cụ] | Mốc xảy ra trong giờ nghỉ 22:00–07:00 (giờ Việt Nam thật của Notification) với thiết bị đã đăng ký. Muốn thử giờ khác: đặt `NOTIFICATION_PUSH_QUIET_HOURS_START`/`_END` bao giờ hiện tại trong `finora-notification/.env`, `-Action Restart -Services notification`, xong trả lại | Hộp thư có tin ngay | Push nhóm khác `WALLET` hẹn tới giờ kết thúc (vẫn `PENDING`); push rút tiền gửi ngay. Event cũ hơn 24 giờ chỉ vào hộp thư, không push. Mobile không hiển thị giờ nghỉ |
| NTF-N06 | [UI + công cụ] | Đăng ký cùng token (NTF-04) cho tài khoản B | Tài khoản A không nhận push nữa | Thiết bị của A `REVOKED` (lý do chuyển chủ), thiết bị của B `ACTIVE`; tối đa 5 thiết bị mỗi người |
| NTF-N07 | [UI] | Đăng xuất trên máy đã đăng ký (development build) | Đăng xuất bình thường kể cả khi mạng lỗi | App gọi `DELETE /api/v1/notifications/devices/{deviceId}` (best effort); thiết bị `REVOKED`, không còn push |
| NTF-N08 | [UI + công cụ] | Khoản vay quá hạn: dời đồng hồ tới DPD 10 rồi DPD 20 (cùng nhóm 2), T2 | Chỉ một tin quá hạn ở DPD 10; không có tin mới ở DPD 20, cả phía người vay và nhà đầu tư | D61, B60 |
| NTF-N09 | [UI + công cụ] | Tắt Notification (T5), bấm một tin chưa đọc; bật lại | Tin trở lại chưa đọc và có dòng lỗi; "Thử lại" sau khi bật lại | Không đổi bản ghi |
| NTF-N10 | [UI + công cụ] | Tắt Notification, mở "Thông báo" | "Không tải được thông báo." + "Thử lại" | — |
| NTF-N11 | [API] | Token người A gọi `POST /api/v1/notifications/{id}/read` với id tin của B | Luôn `204` (không lộ tin có tồn tại hay không) | Tin của B vẫn chưa đọc: đăng nhập B, badge không đổi |
| NTF-N12 | [API] | Đăng ký thiết bị với token sai định dạng, `platform = WINDOWS`; 21 lần trong 10 phút | — | `400 VALIDATION_FAILED` "Push token không đúng định dạng Expo"; `400 MALFORMED_REQUEST`; `429 RATE_LIMITED` |
| NTF-N13 | [UI + công cụ] | Đăng ký 6 token giả cho cùng tài khoản (NTF-04) | — | Thiết bị có `lastRegisteredAt` cũ nhất bị `REVOKED` (`DEVICE_LIMIT`, tối đa 5) |
| NTF-N14 | [API] | Admin `POST /api/v1/notifications/admin/push-deliveries/{id}/requeue` cho lần gửi `SENT` | — | `409 PUSH_DELIVERY_NOT_REQUEUEABLE` "Chỉ gửi lại được push ở trạng thái DEAD hoặc FAILED"; thiết bị đã thu hồi: `409 PUSH_DEVICE_REVOKED`. Web không có màn vận hành push |

### 4.15 CIC nhận nợ quá hạn (`-WithLocalCic`, D65)

Điều kiện:

- Stack `Start -WithLocalCic -CicPath <thư mục cic-service-node của nhánh feat/finora-delinquency-intake>` (nhánh `main` không có
  `src\messaging` nên launcher từ chối). CIC local nghe cổng 9000, kho tệp `docker/e2e/out/cic/du-lieu.json`, khóa nội bộ
  `CIC_INTERNAL_API_KEY_E2E` trong `docker/e2e/out/.env.e2e` (không in ra).
- Không có UI cho CIC: người kiểm quan sát bằng API của CIC local. **Chỉ dùng CCCD tổng hợp đầu 999 (không phải mã tỉnh thật)
  và chỉ với CIC local**; không bao giờ gọi bản Vercel bằng CCCD nào (B70).
- Người vay E2E không eKYC nên worker User không gửi mapping; người kiểm tự đăng ký mapping `borrowerId ↔ CCCD` như harness.
  `borrowerId` là trường `borrowerId` của `GET :8081/api/v1/loan-applications/{LA}` (token người vay, T7).

**CIC-01 [UI + công cụ] Đăng ký mapping** (PowerShell, module ở mục 2.10):

```powershell
$e2e = Read-E2EDotEnv -Path .\docker\e2e\out\.env.e2e
$cccd = '999' + (Get-Date).ToString('MMddHHmmss').Substring(0, 9)     # CCCD tổng hợp 12 số, đầu 999
$map = Invoke-E2EHttp -Method POST -Uri 'http://localhost:9000/api/v1/internal/borrower-mappings' -Headers @{ 'X-Finora-Internal-Key' = $e2e['CIC_INTERNAL_API_KEY_E2E'] } -Body @{ borrowerId = '<borrowerId>'; soCccd = $cccd }
"$($map.Status) $($map.Json.soCccdDaChe) phatLai=$($map.Json.soSuKienDaPhatLai)"   # 201, CCCD đã che dạng 999*******xx
```

Response không bao giờ trả CCCD đầy đủ.

**CIC-02 [UI + công cụ] Hồ sơ CIC theo mốc quá hạn.** Chạy mục 4.9 trên khoản vay của người vay đã có mapping. Sau mỗi mốc, đọc
`Invoke-E2EHttp -Method GET -Uri "http://localhost:9000/api/v1/ho-so/$cccd"` (Git Bash: `curl -s http://localhost:9000/api/v1/ho-so/<cccd>`):

| Mốc | `nhomNoHienTai` | `nhomNoCaoNhat` | Ngày phục hồi |
|---|---|---|---|
| DPD 10 | 2 | 2 | trống |
| DPD 91 | 3 | 3 | trống |
| Khắc phục từ nợ xấu | 1 | 3 | `ngayKhacPhucNoXau` = ngày khắc phục (theo ngày nghiệp vụ đã dời), `tamKhoaVayDen` = +12 tháng, `thamDinhThuCongDen` = +24 tháng |
| DPD 361 | 5 | 5 | trống |

`GET /api/v1/ho-so/<cccd>/lich-su`: `soPhienBan` bằng số event `LoanDelinquencyChanged` của khoản vay (evidence: 7). Mốc 12/24
tháng là policy demo FINORA cấu hình bằng `CIC_BAD_DEBT_COOLDOWN_MONTHS`/`CIC_BAD_DEBT_MANUAL_REVIEW_MONTHS`, không phải luật.

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả mong đợi |
|---|---|---|---|
| CIC-N01 | [API] | Gửi mapping với `X-Finora-Internal-Key` sai hoặc thiếu | `403`, `maLoi = DU_LIEU_KHONG_HOP_LE`, "API liên kết định danh CIC chưa được phép" |
| CIC-N02 | [API] | Cùng `borrowerId` với CCCD khác; cùng CCCD với `borrowerId` khác | `400 DU_LIEU_KHONG_HOP_LE` "borrowerId đã liên kết với CCCD khác" / "CCCD đã liên kết với borrowerId khác" |
| CIC-N03 | [API] | Gửi lại đúng cặp đã có | `201`, giữ `createdAt` cũ (idempotent) |
| CIC-N04 | [API] | `soCccd` không đủ 12 số; `GET /ho-so/123` | `400 DU_LIEU_KHONG_HOP_LE`; `400 CCCD_KHONG_HOP_LE` "Số CCCD phải gồm đúng 12 chữ số, nhận được: …" |
| CIC-N05 | [UI + công cụ] | Event tới trước mapping: dời đồng hồ tới DPD 10 **trước** khi đăng ký mapping, rồi chạy CIC-01 | Response mapping có `soSuKienDaPhatLai = 1`; hồ sơ có phiên bản nhóm 2 (event chờ được phát lại theo `occurredAt`) |
| CIC-N06 | [UI + công cụ] | Phát lại event quá hạn cuối (PowerShell): `$rec = @(Get-E2EKafkaRecords -Container finora-kafka -Topic finora.loan.delinquency-changed -Contains @('<LN-…>'))[-1]; Send-E2EKafkaRecord -Container finora-kafka -Record $rec` | `soPhienBan` không đổi (`cic.duplicate-event-ignored`); Loan, Investment, Notification cũng bỏ qua theo `eventId` |
| CIC-N07 | [API] | `GET /api/v1/ho-so/<CCCD 999 chưa có>` trên CIC local | `200` với hồ sơ mới chưa lưu (`phienBan` 1, nhóm nợ trống). Trên bản Vercel thao tác này lưu hồ sơ công khai: không làm |

### 4.16 Bảo mật và lạm dụng (W3A, R8A)

Điều kiện: hai người vay (A, B) và hai nhà đầu tư (A, B). A đã có hồ sơ `LA-…`, hợp đồng `LC-…`, khoản vay `LN-…`, khoản trả
`repaymentId` và lệnh góp vốn `orderReference` (lấy từ màn hình, evidence hoặc API của chính A).

**SEC-01 [UI] Mỗi người chỉ thấy dữ liệu của mình.** Đăng nhập mobile bằng người vay B: danh sách hồ sơ, khoản vay, hợp đồng,
ví, thông báo không có gì của A. Nhà đầu tư B: "Danh mục đầu tư", "Hợp đồng chờ ký", "Đề nghị cơ cấu cần đồng ý" không có
khoản của A.

**SEC-02 [API] Đổi ID để đọc tài nguyên của người khác** (T7 bằng token của B; mã lấy từ evidence
`w3r-installment-20261017-204753`):

| Gọi bằng token của B | Mong đợi |
|---|---|
| `GET :8081/api/v1/loan-applications/<LA của A>` | `403 LOAN_APPLICATION_ACCESS_DENIED` |
| `GET :8081/api/v1/loan-contracts/<LC của A>` | `403 LOAN_CONTRACT_ACCESS_DENIED` |
| `GET :8081/api/v1/loan-contracts/<LC của A>/document` (PDF) | `403` |
| `GET :8081/api/v1/loans/<LN của A>` | `403 LOAN_ACCESS_DENIED` |
| `GET :8080/api/v1/repayments/scheduled-due?loanApplicationId=<id của A>` | `403 PAYMENT_LOAN_OWNER_MISMATCH` |
| `GET :8080/api/v1/repayments/<repaymentId của A>` | `403 REPAYMENT_OWNER_MISMATCH` |
| Nhà đầu tư B: `GET :8080/api/v1/transactions/holds?orderReference=<của A>` | `404 PAYMENT_HOLD_NOT_FOUND` |
| Nhà đầu tư B: `GET :8081/api/v1/investor/loan-contracts/<LC của A>` | `403 CONTRACT_PARTY_ACCESS_DENIED` |

Không response `2xx` nào; không thay đổi gì ở dữ liệu của A.

**SEC-03 [API] Token người dùng gọi API quản trị** (token người vay/nhà đầu tư): `GET :8081/api/v1/admin/disbursement-sagas`
→ `403`; `GET :8080/api/v1/admin/repayment-reconciliation` → `403 ADMIN_ROLE_REQUIRED`;
`GET :8080/api/v1/investments/admin/order-incidents` (nhà đầu tư) → `403`; `GET :8080/api/v1/blockchain/proofs` → `403`.

**SEC-04 [API] Lời gọi không token**: `POST http://localhost:8000/api/v1/ai/credit/explain` và qua Gateway
`POST :8080/api/v1/ai/credit/explain` với body `{"probe":1}` → `401`; token người vay → `403`;
`POST http://localhost:8086/api/internal/notifications/otp-email` không token → `401`. Admin gọi AI qua Gateway với body
`{"probe":1}` → `422` liệt kê từng field thiếu (chứng minh Gateway chuyển đủ body, B43).

**SEC-05 [API] Token PIN chỉ dùng cho đúng một thao tác (D38).** Lấy token PIN scope `REPAYMENT` với `requestKey = k1` (T7),
gửi `POST :8080/api/v1/repayments` với `Idempotency-Key = k2` và token đó → `403 PIN_TOKEN_INVALID`. Gửi đúng `k1` → được
xử lý; gửi lại `k1` → trả kết quả cũ, không trừ tiền lần hai. Thiếu header `Idempotency-Key` ở endpoint cần PIN → `400
IDEMPOTENCY_KEY_REQUIRED`.

**SEC-06 [UI] Người vay/nhà đầu tư mở web quản trị.** Như AUTH-N13: mọi URL quản trị (kể cả gõ thẳng
`http://localhost:5173/loans/operations?tab=incidents` hay `/blockchain/proofs`) chỉ hiện "Không có quyền truy cập"; không có
dữ liệu nào tải về (DevTools: các lời gọi admin trả `403`).

**SEC-07 [UI] Người vay đặt lệnh đầu tư** (FUND-N18): bị backend chặn `403`, không có lệnh; app hiện "Bạn không có quyền thực
hiện thao tác này." (B86; bản mobile trước `b43f41c` hiện "Phiên đăng nhập đã hết hạn.").

**SEC-08 [UI] Nhà đầu tư không xem được hồ sơ người vay chưa lên sàn.** Hồ sơ ẩn danh chỉ mở từ listing đã lên sàn; hồ sơ khác
trả `404` như không tồn tại (`LEGAL-DISCLOSURE-02`). Màn "Hồ sơ người vay" lỗi hiện "Thử tải lại sau ít phút. Hồ sơ chỉ xem được
khi khoản vay đã lên sàn."

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Cách gây ra | Kết quả mong đợi |
|---|---|---|---|
| SEC-N01 | [UI] | Tạo 11 lệnh nạp ví trong một giờ (cùng tài khoản) | Lệnh thứ 11 bị chặn với câu "Bạn thao tác quá nhanh, vui lòng thử lại sau {N} giây" (`429 RATE_LIMITED`, header `Retry-After`; app chỉ dùng câu dự phòng "Bạn thao tác quá nhiều lần, vui lòng thử lại sau." khi backend không gửi `message`). Ví không đổi |
| SEC-N02 | [UI] | 11 lệnh rút tiền trong một giờ (WDR-N08) | Lệnh thứ 11 `429 RATE_LIMITED`; ví không bị trừ cho lệnh bị chặn |
| SEC-N03 | [API] | 31 lần báo giá trả trước/tất toán trong một phút | Lần thứ 31 `429 RATE_LIMITED` |
| SEC-N04 | [API] | 21 lần đăng ký thiết bị push trong 10 phút | `429` |
| SEC-N05 | [UI + công cụ] | Khởi động lại service sau SEC-N01 | Bộ đếm về 0 (giới hạn trong bộ nhớ, theo instance, D40): ghi nhận, không phải lỗi |
| SEC-N06 | [UI] | Mở ảnh chụp/màn hình khóa khi có push (NTF-05) | Không có số tiền, tên, mã khoản vay (D63) |
| SEC-N07 | [UI] | Lịch sử rút tiền | Số tài khoản luôn che `******1234`, kể cả màn chi tiết (D44) |

### 4.17 Health và readiness cho vận hành (W3E)

Mọi service Java có `/actuator/health/liveness` (JVM còn chạy) và `/actuator/health/readiness` (dependency mọi request cần).
Không cần token để xem trạng thái; chi tiết (lý do, thời điểm, lỗi) chỉ hiện với token `ROLE_ADMIN`. Nhóm `operations` chứa
thứ vận hành phải xem nhưng không rút service khỏi traffic (D56).

| Service | Lệnh (PowerShell; Git Bash dùng `curl -s <URL>`) | Readiness gồm | Nhóm `operations` |
|---|---|---|---|
| Gateway | `Invoke-RestMethod http://localhost:8080/actuator/health/readiness` | `readinessState` | — |
| Loan | `Invoke-RestMethod http://localhost:8081/actuator/health/readiness`; `.../actuator/health/operations` | `readinessState`, `db` | `fineractEndOfDay`, `kafka` |
| Payment | `http://localhost:8082/actuator/health/readiness` | `readinessState`, `db` (không còn `redis`, B23) | `kafka` |
| Blockchain | `http://localhost:8083/actuator/health/readiness` | `readinessState`, `db` | `kafka` khi listener bật |
| Investment | `http://localhost:8084/actuator/health/readiness` | `readinessState`, `db` | `kafka` |
| User | `http://localhost:8085/actuator/health/readiness` | `readinessState`, `db`, `redis` | — |
| Notification | `http://localhost:8086/actuator/health/readiness` | `readinessState`, `db` | `kafka` |
| AI | `http://localhost:8000/health` | tĩnh | — |
| Fineract | `http://localhost:18443/fineract-provider/actuator/health` (chỉ tiến trình) | — | — |
| Keycloak | `http://localhost:8180/realms/finora/.well-known/openid-configuration` | — | — |

**OPS-01 [UI + công cụ] Stack khỏe trước khi kiểm.** Gọi từng dòng bảng trên. Mong đợi `status: UP` cho readiness của bảy
service Java; AI trả `200`. Ghi vào phiếu kết quả (mục 5) trước mỗi đợt kiểm.

**OPS-02 [UI + công cụ] Job cuối ngày Fineract.** `Invoke-RestMethod http://localhost:8081/actuator/health/operations`:
`fineractEndOfDay` là `UP` khi `LA_RINT` và `LA_ARAG` có lần chạy thành công trong 26 giờ (`LOAN_FINERACT_EOD_STALE_AFTER`);
`DOWN` với `FINERACT_EOD_STALE`/`FINERACT_EOD_JOB_MISSING`; `UNKNOWN` khi chưa kiểm hoặc không đọc được. Nếu `DOWN`: chạy
`Invoke-FxJob 'LA_RINT'; Invoke-FxJob 'LA_ARAG'` (T2) rồi gọi lại sau tối đa 5 phút → `UP`. Tuổi job tính theo giờ thật,
không theo đồng hồ E2E.

**OPS-03 [UI + công cụ] Chi tiết chỉ cho admin.** Gọi `operations` không token: chỉ thấy tên và trạng thái thành phần. Gọi
với token admin (T7, `-Headers @{ Authorization = "Bearer $token" }`): thấy thêm `lastSuccessAt`, `checkError`… Metric
`http://localhost:8082/actuator/metrics/finora.operations.incidents` không token → `401`; token người vay → `403`; token admin
→ `200` (B56).

**Ca lỗi và ca biên**

| Mã | Kích hoạt | Bước | Kết quả mong đợi |
|---|---|---|---|
| OPS-N01 | [UI + công cụ] | `docker stop finora-kafka`, gọi readiness và `operations` của Loan; đặt một thao tác có event (ví dụ nạp ví), rồi `docker start finora-kafka` | Readiness vẫn `UP` (Kafka không nằm trong readiness), `operations` báo `kafka` `DOWN` (cache 30 giây). Event nằm trong outbox và đi tiếp khi broker sống lại; không mất thông báo/proof |
| OPS-N02 | [UI + công cụ] | `docker stop finora-ai` rồi mở một màn web gọi AI; xong `docker start finora-ai` | `http://localhost:8000/health` không trả lời; hồ sơ mới vào hàng đợi chấm lại (`LOAN-N06`) |
| OPS-N03 | [UI + công cụ] | Tắt Investment (T5), mở tab "Sàn" trên mobile, bật lại | Mobile hiện trạng thái lỗi có nút "Thử lại"; bật lại rồi "Thử lại" thì tải được. Không có dữ liệu giả thay thế |
| OPS-N04 | [UI] | Đặt `EXPO_PUBLIC_MOCK_FAIL=wallet` trong `.env` mobile, `npx expo start -c`, mở tab "Ví"; xong xóa biến | Màn ví hiện lỗi giả lập có "Thử lại"; các tab khác bình thường |

## 5. Ghi kết quả và báo lỗi

### 5.1 Phiếu đợt kiểm

Ghi một lần ở đầu mỗi đợt (không ghi secret):

| Mục | Giá trị |
|---|---|
| Ngày, giờ bắt đầu/kết thúc | |
| Người kiểm, thiết bị (máy thật/emulator, Android/iOS, Expo Go/development build), trình duyệt | |
| Commit (lệnh chỉ đọc: `git -C <repo> rev-parse --short HEAD`) | platform `…` · web `…` · mobile `…` |
| Cờ launcher | `-Database local` · `-WithToxiproxy` có/không · `-WithFabric` có/không · `-WithLocalCic` có/không · `-BorrowerProfileProvider` |
| `-Action Status` trước khi kiểm | `clock=thật` cho loan/payment, `db=local` |
| OPS-01 | Pass/Fail |

### 5.2 Bảng kết quả từng ca

| Mã ca | Kết quả (Pass / Fail / Blocked / N/A) | Mã nghiệp vụ dùng (LA-…, LC-…, LN-…, repaymentId, withdrawalId, proofId) | Bằng chứng (ảnh, tên file) | Ghi chú / mã defect |
|---|---|---|---|---|
| AUTH-01 | | | | |

Quy ước:

- **Pass** khi mọi kết quả mong đợi đúng, kể cả bất biến tiền. Số tiền lệch dù 1 đồng là **Fail**.
- **Blocked** khi điều kiện không đạt (service lỗi, thiếu Product, ca trước hỏng); ghi lý do.
- **N/A** khi ca cần thứ không có trên máy (development build, SmartCA UAT, Fabric).
- Ảnh chụp đặt tên `<mã ca>-<bước>-<yyyyMMdd-HHmm>.png`, che token/PIN/OTP/email thật nếu có; ảnh DevTools chỉ giữ phần
  status, `code`, `message` và `X-Trace-Id`.
- Mỗi lỗi API ghi `X-Trace-Id` nếu response có header này, để tra log trong `docker/e2e/out/logs/<service>.log`.

### 5.3 Báo defect

1. Kiểm defect register `.agents/plans/finora-remaining-work-plan.md` mục 3b xem lỗi đã có chưa (ví dụ B66, B73, B76 đang
   `Open`). Đã có thì ghi mã cũ vào cột ghi chú.
2. Lỗi mới lấy số `B<n>` kế tiếp số lớn nhất trong register (lúc viết tài liệu là B76, nên số tiếp theo là B77; kiểm lại
   trước khi đặt vì nhiều người cùng ghi).
3. Gửi Thái theo đúng cột của register:

   | ID | Mức | Workstream | Vấn đề và bằng chứng | Trạng thái |
   |---|---|---|---|---|
   | B77 | P0–P3 | R2/R3/R4/R5/R6/R7/R8/R9/FE/DOC | Mã ca, các bước, kết quả mong đợi so với thực tế, mã nghiệp vụ, `X-Trace-Id`, ảnh; không token/PII | Open |

4. Mức: **P0** sai tiền hoặc mất tiền (trừ hai lần, hoàn thiếu, cộng thừa, ghi Fineract hai lần); **P1** chặn luồng chính,
   lộ dữ liệu người khác, bỏ qua PIN/quyền; **P2** sai trạng thái, thông báo thiếu/trùng, số hiển thị lệch nguồn chuẩn;
   **P3** câu chữ, giao diện.
5. Lỗi thuộc module của Hải (User, Investment, Notification, AI, CIC, phần UI Hải làm) ghi "Hải review" (D2).

## 6. Giới hạn đã biết

| Giới hạn | Ảnh hưởng tới kiểm thử | Nguồn |
|---|---|---|
| Ký VNPT SmartCA UAT thật cần người duyệt giao dịch trên app SmartCA (một signer test cố định) | Stack E2E chỉ ký `MOCK`; `409 CONTRACT_SIGNATURE_IN_PROGRESS` và `503 SIGNATURE_PROVIDER_INVALID_SIGNATURE` không tái hiện được bằng MOCK | W3C §13, `docs/integrations/VNPT-SMARTCA-UAT.md` |
| Push thật cần EAS projectId, development build, máy thật và `NOTIFICATION_PUSH_PROVIDER=expo`; chưa từng thử trên máy thật | Mặc định chỉ kiểm được bản ghi gửi `LOG_MOCK` | W3F §18, `MOBILE-NOTIFY-001` |
| Nhắc kỳ đến hạn tắt mặc định (`NOTIFICATION_DUE_REMINDER_ENABLED=false`, D64) | Không có tin nhắc trước 3 ngày/đúng ngày; tin "đã giữ/đã hủy khoản trả kỳ" chỉ có khi bật | D64 |
| B66: test CIC của Hải dùng CCCD đã có trong seed | Không ảnh hưởng UI | Register 3b |
| B73: chi tiết thẩm định của admin thiếu `id` số và `contractNumber` | Từ trang duyệt hồ sơ không mở được proof; tra proof theo id hồ sơ ở trang "Bằng chứng blockchain" | Register 3b |
| B76: khoản trả kỳ đang giữ bị Fineract từ chối đúng ngày đến hạn thì hoàn tiền, `FAILED` nhưng không phát event | Chỉ ảnh hưởng nhắc lịch khi bật D64; hiếm | Register 3b |
| AI không tra CIC cho người vay chưa eKYC (người vay E2E dùng profile mock) | Hồ sơ thường về `PENDING_REVIEW`; quyết định AI theo nhóm nợ CIC không kiểm được trên stack E2E | Runbook E2E §5b |
| CIC bản Vercel không giữ consumer Kafka, là mock công khai | Quá hạn sang CIC chỉ kiểm được với `-WithLocalCic`; không dùng CCCD thật với bản deploy (B70) | D65, B70 |
| PAdES bằng chứng thư demo và proof Fabric local không phải chứng cứ pháp lý | Mọi nhãn trên UI phải nói rõ là demo/MOCK | `LEGAL-CONTRACT-01`, `LEGAL-DATA-01` |
| Tự đăng ký vai trò `ADMIN` chưa bị chặn (D20) | Không tạo admin mới khi kiểm; dùng tài khoản demo | D20 |
| Giới hạn tần suất và hạn mức rút tiền đếm trong bộ nhớ theo instance | Khởi động lại service thì bộ đếm `429` về 0 | D40, W3B §7 |
| Rút tiền chưa đối chiếu tên chủ tài khoản với họ tên eKYC | Nhập tên bất kỳ vẫn được nhận | W3B §7 |
| Chưa có drill hết hạn ký hợp đồng trên stack thật; Toxiproxy chỉ đặt giữa Payment và Fineract | Ca hết hạn ký và lỗi mạng Loan → Fineract chỉ thử được ở mức đề xuất | Runbook E2E §7, W2D §6 |
| Outbox `DEAD_LETTER` chưa có API đưa lại hàng đợi (B57) | Chỉ proof Blockchain có nút thử lại | B57 |
| Chưa có event/thông báo khi admin từ chối đề nghị cơ cấu (P8) và hộp thư sự cố cho admin (P9) | Người vay không nhận tin khi bị từ chối cơ cấu | W3F §13 |
| Fineract V4 không tính thêm lãi sau ngày đáo hạn; nhóm nợ về 1 ngay khi trả đủ quá hạn | Quá hạn giữ nguyên từ DPD 181 tới 361; không có thời gian theo dõi sau khắc phục | W2D §6 |
| Hàng đợi sự cố chưa phân quyền chi tiết, chưa duyệt hai người | Mọi admin đều bấm được thao tác sự cố | W2D §6 |

## 7. Lệch tài liệu

Theo quy tắc của tài liệu này, khi plan/tài liệu và code khác nhau thì ca kiểm thử theo code và harness. Các chỗ lệch tìm thấy
khi viết (2026-10-17):

| # | Tài liệu nói | Code/harness hiện tại | Đề xuất |
|---|---|---|---|
| 1 | `finora-web/.env.example` có `VITE_API_URL`, `VITE_WS_URL`, `VITE_APP_NAME=Smart Library Navigator` | Code không đọc ba biến này; dùng `VITE_LOAN_API_URL`, `VITE_PAYMENT_API_URL`, `VITE_INVESTMENT_API_URL`, `VITE_AI_API_URL`, `VITE_BLOCKCHAIN_API_URL` (mặc định đường tương đối qua proxy Vite) | Sửa `.env.example` của web |
| 2 | W3A: người dùng thấy "Bạn thao tác quá nhanh, thử lại sau N giây" | Backend (`RateLimitInterceptor`): "Bạn thao tác quá nhanh, vui lòng thử lại sau {N} giây"; mobile chỉ dùng "Bạn thao tác quá nhiều lần, vui lòng thử lại sau." khi không có `message` | Ca kiểm theo câu của backend |
| 3 | `finora-mobile/docs/LOAN-SERVICING-UI-TEST.md` TC04 "Lập báo giá"; "Lịch trả nợ từ Fineract"; "Đề nghị cơ cấu hoặc gia hạn"; TC06 "Nhà đầu tư từ chối" | Nút thật: "Xem số tiền cần thanh toán"; "Xem lịch trả nợ {n} kỳ" (màn ghi "theo lịch trong hợp đồng"); "Đề nghị điều chỉnh kỳ hạn hoặc gia hạn"; nhãn "Nhà đầu tư không đồng ý" | Cập nhật tài liệu mobile |
| 4 | Tài liệu mobile TC04/TC05: khoản vay V2/V3 vẫn trả trước/tất toán được | Payment chỉ cho core V4; V1–V3 nhận `409 PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN` (D17) | Cập nhật tài liệu mobile |
| 5 | Tài liệu mobile TC02: "Đã thu tiền → Đang ghi nhận vào khoản vay → Hoàn tất" | Trả trước hạn trên V4 dừng ở "Chờ ghi nhận dd/MM/yyyy" (D16); chuỗi đầy đủ có thêm "Đã ghi nhận, đang phân phối"; tài liệu chưa có bước PIN, khoản giữ/hủy, lựa chọn "Chỉ kỳ cũ nhất" | Cập nhật tài liệu mobile |
| 6 | `MOBILE-LOAN-003`: lối vào "Khoản vay đang trả" | Code: "Khoản vay" (tab "Hồ sơ") → "Khoản vay của tôi" | Sửa plan |
| 7 | W3B §7: chưa có đối soát tổng theo lô; VietQR chưa làm | Mobile vẫn hiện "Mọi giao dịch đều qua tài khoản đối soát — đối soát batch 00:15 hằng ngày" (chân trang ví) và quảng cáo "Nạp ví nhanh qua VietQR" ở Trang chủ | Sửa câu chữ mobile (có thể gây hiểu nhầm, `LEGAL-PAYMENT-01`) |
| 8 | `docs/testing/LOAN-SERVICING-E2E.md` §8: danh mục nhà đầu tư phải trả đúng DPD | Evidence `w3r-defaultcure-20261017-205432`: sau khi trả bớt ở DPD 91, Loan DPD 61 nhưng vị thế nhà đầu tư vẫn DPD 91 (nhóm 3 đúng) | Thái xác nhận; nếu là lỗi thì mở defect mới (OVD-04) |
| 9 | Plan W3F/thiết kế: nhóm tin `CONTRACT`, `RESTRUCTURING` là hợp đồng và cơ cấu | Hộp thư mobile gắn nhãn "Lịch trả nợ" (màu đỏ) cho hai nhóm này | Sửa ánh xạ nhãn trên mobile |
| 10 | `docs/testing/E2E-VERTICAL-SLICE.md` §5: hồ sơ `PENDING_REVIEW` vì "thiếu dữ liệu CIC" | AI quyết định theo điểm (≥ 75 tự duyệt, < 65 tự từ chối, ở giữa chờ thẩm định); thiếu CIC chỉ làm hai luật lấy điểm trung tính. Dữ liệu mẫu cho 66,79 nên mới `PENDING_REVIEW` | Sửa câu trong runbook |
| 11 | Yêu cầu kiểm thử giả định đăng ký người vay và nhà đầu tư trên app | Form đăng ký mobile không có lựa chọn vai trò, mọi tài khoản là người vay; nhà đầu tư tạo bằng API (như harness) hoặc tài khoản demo | Thêm lựa chọn vai trò hoặc ghi rõ trong tài liệu mobile |
| 12 | W2D "Ai thấy gì": "giải ngân hết lượt tra cứu tự động có 'Tra cứu lại'" | Web hiện "Tra cứu lại" cho mọi lệnh giải ngân `RECONCILIATION_REQUIRED` (chỉ hàng đợi rút tiền mới chờ hết lượt tự động) | Thống nhất một quy tắc |
| 13 | W3F "Ai tham gia": admin có màn vận hành push `DEAD`/`FAILED` (gửi lại) | Web không có màn này; chỉ có API `/api/v1/notifications/admin/push-deliveries` | Ghi rõ là API |
| 14 | `docker/fabric-network/README.md` chỉ nêu `Up`, `Status`, `Down` | Script còn `-Action Proof -Org org1/org2 -ProofId <uuid>` (W3D dùng để tổ chức kiểm toán đọc proof) | Bổ sung README |
| 15 | `finora-web/plans/WEB-UI-001-giao-dien-mem.md`: web gọi thẳng AI `localhost:8000`; WEB-LOAN-001: trang chính sách AI chỉ đọc | Web gọi AI qua proxy → Gateway; trang "Chính sách đánh giá AI" sửa và lưu được | Cập nhật plan web |

### 7.1 Quan sát nghi lỗi (đề xuất defect; mục đã sửa có mã B trong register)

Đọc từ code hoặc evidence khi viết tài liệu; người kiểm xác nhận trên UI rồi mới mở mã B mới (mục 5.3). Mục **Đã sửa** có bản
sửa ngày 2026-10-18: kiểm lại theo ca ghi ở cuối dòng.

1. DPD trên vị thế nhà đầu tư không giảm sau khi người vay trả bớt ở DPD 91 (91 so với 61 phía Loan) — OVD-04.
2. Loan nhận số tiền vay không chia hết mệnh giá Note, listing không bao giờ được tạo, hồ sơ kẹt ở gọi vốn — LOAN-N11.
3. Admin quyết định đề nghị cơ cấu đã qua bước thẩm định trả `500` thay vì `409` — RSC-N10.
4. Web "Gọi vốn & Notes" có thể ghi "Đã phát hành Note" cho khoản đủ vốn chưa giải ngân — FUND-07.
5. **Đã sửa — B86 (mobile `b43f41c`).** `403` không có body (ví dụ người vay bấm "Đầu tư") hiện "Phiên đăng nhập đã hết hạn.";
   nay là "Bạn không có quyền thực hiện thao tác này." Thanh "Đầu tư" vẫn hiện cho người vay — FUND-N18, SEC-07.
6. Đăng nhập tài khoản bị admin khóa có thể trả "Lỗi hệ thống xác thực…" (`500`) — AUTH-N11; phiên hết hạn không có thông báo trên
   mobile và web — AUTH-N12.
7. **Đã sửa — B83 (web `c363690`).** Web bật "Yêu cầu chấm lại" khi assessment còn `RETRY_PENDING` nhưng backend chỉ nhận
   `FAILED`; nay nút khóa kèm lý do — ADM-N02b. Cùng nút: web gửi version lần chấm thay vì version hồ sơ nên chấm lại trả `409`
   (B84, web `554522e`) — ADM-N02.
8. Form từ chối điều khoản ghi tiêu đề "Từ chối hợp đồng này" — LOAN-03; nhãn hộp thư "Lịch trả nợ" cho tin hợp đồng — mục 7 #9.
9. **Đã sửa — B87 (mobile `21f9d39`).** Lỗi HTTP khi mở hoặc tải PDF hợp đồng hiện nguyên chuỗi "API {status}: {body}" trên
   mobile; nay hiện câu của backend hoặc câu theo mã lỗi, lỗi tệp và chia sẻ: "Không mở được bản PDF hợp đồng, vui lòng thử lại."
10. Cảnh báo "khoản vay chưa đồng bộ kịp từ Fineract" trên trang thống kê web đang bị tắt trong code (`TEMPORARILY_HIDDEN`).
11. Thiếu header bắt buộc (không phải PIN), UUID sai định dạng hay enum lạ ở query có thể trả `500 INTERNAL_ERROR` thay vì `400`.
12. **Đã sửa — B85 (web `d5dc976`, `deb23fd`).** Web dùng hai nhãn cho cùng ý proof MOCK; nay badge và kết quả đối chiếu cùng
    là "MOCK, không có trên Fabric" — BC-N10.
