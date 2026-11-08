# FINORA trên Azure (dựng ngày 15/10/2026)

Thư mục này là `finora-platform/deploy/` (trước 16/10 nằm ở `docker/azure/`). Dockerfile của các service vẫn ở `docker/`.

Backend chạy trên một VM Linux, bật khi có người cần và tự tắt khi rảnh. Web nằm trên Vercel, gọi API
qua rewrite `/api/v1/*` của Vercel nên trình duyệt chỉ thấy một domain (cookie đăng nhập `SameSite=Lax`
chạy như ở máy dev, không cần CORS). Database của các service vẫn ở Neon.

```
Trình duyệt ─► Vercel (web tĩnh + hàm /api/wake) ─rewrite /api/v1─► Caddy :443 trên VM ─► gateway/service
Mobile ───────────────────────────────────────────────────────────► Caddy :443 trên VM
VM (Docker Compose) ─► Neon (AWS Singapore): DB của user, loan, payment, investment, notification, Keycloak, Fineract
```

## Tài nguyên Azure (subscription "Azure for Students")

| Tài nguyên | Tên | Ghi chú |
|---|---|---|
| Resource group | `finora-rg` | Vùng `malaysiawest`. Gói sinh viên chỉ cho 5 vùng, không có Singapore; đây là vùng gần Neon nhất |
| VM | `finora-vm` | `Standard_B2as_v2` (2 vCPU, 8 GB), Ubuntu 24.04, ổ Standard SSD 32 GB, swap 4 GB |
| IP tĩnh | `finora-ip` | Domain `finora-api.malaysiawest.cloudapp.azure.com`, giữ nguyên khi VM tắt |
| NSG | `finora-nsg` | 80/443 mở; 22 chỉ mở cho IP máy dev (`finora-azure.ps1 ssh` tự cập nhật) |
| Role | `FINORA VM Power` | Xem trạng thái, bật, tắt (deallocate) đúng VM này. Định nghĩa: `vm-power-role.json` |
| Managed identity của VM | (system) | Có role trên để VM tự tắt |
| Service principal | `finora-vercel-wake` | Có role trên để hàm Vercel bật VM. Secret hết hạn sau 2 năm |
| Role | `FINORA VM Deploy` | Như `FINORA VM Power` cộng `runCommand` (chạy script bằng root trong VM). Định nghĩa: `vm-deploy-role.json` |
| App registration | `finora-github-cd` | Danh tính của workflow CD; federated credential chỉ tin GitHub Actions chạy trên nhánh `main` của repo, không có secret |
| Log Analytics workspace | `finora-logs` | Lưu log container 30 ngày, trần nạp 0,15 GB/ngày để nằm trong phần miễn phí 5 GB/tháng |
| Data collection rule | `finora-containers-dcr` | Gom syslog facility `local0` (log container) từ VM vào `finora-logs`. Định nghĩa: `host/azure-monitor-dcr.json` |
| Extension của VM | `AzureMonitorLinuxAgent` | Azure Monitor Agent, tự nâng cấp |

Giá niêm yết (15/10/2026): VM 0,0855 USD/giờ chạy; ổ 2,4 USD/tháng và IP 3,65 USD/tháng tính cả khi VM tắt.
Dùng khoảng 60 giờ/tháng thì tổng khoảng 11 USD, trừ vào credit sinh viên.

## Bật và tắt

- **Vào web là bật:** trang web gọi hàm `/api/wake` trên Vercel. VM đang tắt thì hiện màn "Hệ thống đang tắt",
  nhập mã bật hệ thống (biến `WAKE_CODE` trên Vercel) là VM khởi động, khoảng 5 phút sau trang tự vào màn
  đăng nhập. Trình duyệt đã nhập đúng mã một lần thì lần sau tự bật, không hỏi lại.
- **Tự tắt khi rảnh:** `finora-idle-stop.timer` chạy mỗi 5 phút. Nếu đã qua 30 phút kể từ lúc bật và không có
  request `/api/v1` nào trong 45 phút (tính theo request `/api/v1` cuối cùng trong `api-access.log`), VM tự deallocate. Lượt chuyển http→https, request dò quét khác và request đọc trạng thái
  không được tính.
- **Lưới an toàn:** `finora-nightly-stop.timer` tắt VM lúc 00:00 giờ Việt Nam. Azure không có tính năng tự tắt
  theo lịch ở Malaysia West nên VM tự làm.
- Không tắt khi có phiên SSH đang mở hoặc có file `/opt/finora/state/keep-awake`
  (`sudo touch` để giữ VM thức khi sửa lâu, `sudo rm` khi xong).
- Từ máy Windows: `.\finora-azure.ps1 status | up | down | ssh`.

## Trên VM

Bố cục từ CD-001 (lần deploy đầu bằng CD tự chuyển từ bố cục cũ `/opt/finora/src/finora-platform`):

| Đường dẫn | Nội dung |
|---|---|
| `/opt/finora/env/deploy.env` | Biến chung cho compose: `docker/.env` cộng `FINORA_DOMAIN`, `WEB_DOMAIN` (domain Vercel của web, dùng làm domain cookie), `FINORA_PIN_TOKEN_SECRET`, `BLOCKCHAIN_VM_DB_PASSWORD`. Quyền 600, deploy không bao giờ thay |
| `/opt/finora/env/finora-<service>.env` | `.env` của ai, user, notification, payment, investment, loan. Quyền 600 |
| `/opt/finora/releases/<commit>/deploy` | Thư mục `deploy/` của từng commit đã deploy (giữ 5 bản gần nhất) |
| `/opt/finora/current` | Symlink tới bản đang chạy |
| `/opt/finora/config/Caddyfile` | Caddyfile đang dùng (bind mount cố định, đổi bản deploy không tạo lại Caddy) |
| `/opt/finora/state/images.env` | Tag image GHCR đang chạy của từng service (`LOAN_TAG=sha-…`); `images.env.prev` là bản trước |
| `/opt/finora/state/deployed-commit`, `deploy-<commit>.log` | Commit đang chạy và log từng lần deploy |
| `/opt/finora/state` | Như trước: `api-access.log` (Caddy), `public/_status.json` (trạng thái container), `keep-awake` |
| `/opt/finora/bin` | `finora-status.sh`, `finora-idle-stop.sh`, `finora-compose` (cũng ở `/usr/local/bin`) |

Mọi lệnh compose trên VM đi qua `sudo finora-compose …` (nạp đúng file compose và hai file env), ví dụ
`sudo finora-compose ps`, `sudo finora-compose logs -f loan`.

`docker-compose.azure.yml` khác bản chạy ở máy dev:
- Image ứng dụng lấy từ GHCR (`ghcr.io/huuthai2910/finora-<service>:sha-<commit>`), không build trên VM.
- Thêm `notification`, `blockchain` (ledger MOCK, không chạy mạng Fabric) và `blockchain-db` (PostgreSQL trong VM,
  mật khẩu `BLOCKCHAIN_VM_DB_PASSWORD` trong `deploy.env`).
- Không mở cổng service ra ngoài; Keycloak và Fineract chỉ mở ở 127.0.0.1 của VM.
- Giới hạn RAM từng container; heap JVM tính theo giới hạn đó.
- Kafka heap 256 MB.
- Pool kết nối Hikari 5.
- Log container gửi sang Azure Monitor Logs (mục "Log trên Azure").

Caddy chuyển `/api/v1/*` vào gateway. Có hai ngoại lệ:
- SSE sổ lệnh đi thẳng investment.
- `/api/v1/repayments` và đối soát trả nợ đi thẳng payment (từ bản 15/10, khi gateway chưa có route; nay giữ nguyên).

Từ 02/11 `/api/v1/notifications` đi qua gateway: gọi thẳng thì notification tự chặn CORS với origin `localhost`
của bản web Expo dùng khi kiểm thử.

Trang đánh thức đọc `_status.json` do `finora-status.sh` ghi; danh sách service trong script phải khớp compose
(02/11: bỏ `fineract-db`, thêm `blockchain-db`, `blockchain`), nếu không web kẹt ở màn "Đang khởi động".

Caddy còn bỏ header `Origin` khi nó đúng bằng `https://<WEB_DOMAIN>`. Lý do:
- Rewrite của Vercel đổi Host sang domain VM, nên Spring thấy Origin khác Host và coi là gọi chéo origin.
- Nếu không bỏ, mọi POST từ trình duyệt (kể cả đăng nhập) bị trả 403 "Invalid CORS request".
- Origin lạ vẫn bị CORS của gateway/service chặn như cũ.

Đổi domain web thì phải sửa `WEB_DOMAIN` rồi tạo lại cả `caddy` lẫn `user`.

Từ 02/11 Fineract dùng hai database trên Neon (`fineract_tenants`, `fineract_default`) như máy dev, thông tin
kết nối lấy từ `docker/.env`. PostgreSQL cũ trong VM (`fineract-db`, dữ liệu `pg_dump` ngày 15/10) không chạy nữa,
chỉ giữ volume ở profile `legacy`.

## Log trên Azure (từ 03/11/2026)

Log của mọi container đi theo đường: Docker (driver `syslog`, UDP `127.0.0.1:514`, facility `local0`, tag là tên
container) → rsyslog của VM → Azure Monitor Agent → Log Analytics workspace `finora-logs`, bảng `Syslog`
(`ProcessName` là tên container, ví dụ `finora-loan-1`). Log còn lại khi VM tắt; `docker logs` vẫn dùng được.

- rsyslog: `/etc/rsyslog.d/05-finora-docker.conf` (bản ở `host/rsyslog-finora.conf`) mở UDP 514 chỉ trên localhost;
  `/etc/rsyslog.d/45-finora-stop.conf` chặn `local0` sau bước chuyển cho agent để không ghi trùng vào
  `/var/log/syslog`.
- Gateway chỉ định tuyến, không lưu nhật ký; mỗi lượt gọi API nó in một dòng JSON (logger `finora.api`): method, path, service, status, thời gian,
  người gọi, Web/Mobile, IP, mã và câu lỗi, body gửi lên đã che mật khẩu/PIN/OTP/token/ảnh. Tắt bằng
  `API_LOG_STDOUT=false`.
- Xem: Azure Portal → `finora-logs` → Logs. Câu truy vấn mẫu cho nhật ký API ở `host/kql-nhat-ky-api.kql`; log
  một service: `Syslog | where ProcessName == "finora-loan-1" | order by TimeGenerated desc`.
- Log tới workspace chậm khoảng 2–5 phút. Hết trần 0,15 GB trong ngày thì Azure ngừng nhận tới hôm sau.
- Trang **Nhật ký hoạt động** của web quản trị (chỉ ADMIN) đọc các dòng `finora.api` qua finora-user
  (`GET /api/v1/admin/activity-logs`, biến `ACTIVITY_LOG_WORKSPACE_ID`). finora-user lấy token bằng managed identity
  của VM, nên managed identity đó cần role **Log Analytics Reader** trên `finora-logs`; thiếu role thì trang báo
  "Log Analytics từ chối truy vấn (HTTP 403)".

## Chạy lần đầu (đã chạy ngày 15/10/2026, cả 13 service healthy sau khoảng 3,5 phút)

Đo ngày 15/10: cả cụm dùng 4,5/7,9 GB RAM, chưa đụng swap. Lần bật VM sau, mọi thứ tự chạy lại.
Lệnh dưới đây chỉ cần khi dựng lại từ đầu.

```bash
.\finora-azure.ps1 ssh
cd /opt/finora/src/finora-platform/deploy
docker compose -f docker-compose.azure.yml up -d
docker compose -f docker-compose.azure.yml ps        # chờ tất cả healthy, khoảng 3–5 phút
```

Phiên SSH đang mở thì VM không tự tắt, nên cứ ở trong SSH tới khi cả cụm healthy.

Trước đó tắt hẳn cụm ở máy dev (Docker Desktop và các service chạy trong IntelliJ). Hai cụm cùng đọc một
outbox trên Neon sẽ đẩy event vào hai Kafka khác nhau. Event rơi vào Kafka của máy dev thì VM không bao giờ
nhận được.

Sau lần đầu, các container đặt `restart: unless-stopped` nên VM bật lên là cả cụm tự chạy lại.

## CD từ GitHub Actions (CD-001, từ 06/11/2026)

Merge PR vào `main` là workflow `.github/workflows/cd.yml` tự deploy, không có bước duyệt:

1. **gate:** commit phải đến từ PR đã merge và check `CI passed` của PR đó xanh. Đẩy thẳng `main` hoặc CI đỏ thì dừng,
   VM không bị đụng.
2. **plan:** so nội dung image của từng service với bản `main` mới nhất trên GHCR (nhãn
   `org.opencontainers.image.revision`); chỉ build service có thay đổi (mã `src/main`, `pom.xml`, Dockerfile).
   Summary của lần chạy liệt kê service và migration Flyway mới.
3. **build:** build trên runner GitHub, đẩy `ghcr.io/huuthai2910/finora-<service>:sha-<7 ký tự>` và dời tag `main`.
4. **retag:** service không đổi được gắn thêm tag `sha-<commit>` trỏ tới image hiện có, nên commit nào cũng đủ 9 tag.
5. **deploy:** đăng nhập Azure bằng OIDC (app registration `finora-github-cd`, role `FINORA VM Deploy`), bật VM nếu
   đang tắt, chạy `cd-deploy.sh` trong VM bằng `az vm run-command` (không cần SSH, cổng 22 vẫn chỉ mở cho máy dev),
   đợi `/_status.json` báo sẵn sàng, VM vốn tắt thì tắt lại.

`cd-deploy.sh` trên VM: giữ `keep-awake`, tải `deploy/` đúng commit từ codeload (repo public), sửa tag trong
`images.env`, `pull` + `up -d` (service không đổi tag giữ nguyên container), đợi cả cụm healthy (10 phút, 20 phút nếu
có Fineract). Không healthy thì tự trả `images.env`, `current`, Caddyfile về bản trước và `up -d` lại. Log: tab Actions
(4 KB cuối), `/opt/finora/state/deploy-<commit>.log`, Log Analytics `Syslog | where ProcessName == "finora-cd"`.

Rollback chỉ đổi image, **không gỡ migration Flyway đã chạy trên Neon**: migration phải tương thích ngược.

**Chạy tay** (Actions → CD → Run workflow):
- Quay lại bản cũ: `commit` = commit cũ trên `main`, `services` = `all`. Image đã có nên không build lại.
- Deploy lại một service: `services` = `loan` (hoặc danh sách `loan,user`).
- Commit đẩy thẳng `main` (không qua PR) hoặc lần deploy đầu: bật `skip_ci_check`.

### Cài đặt một lần

1. Azure (chủ subscription chạy, Git Bash cần `MSYS_NO_PATHCONV=1` cho `--scope`):

   ```bash
   SUB=$(az account show --query id -o tsv); TENANT=$(az account show --query tenantId -o tsv)
   APP=$(az ad app create --display-name finora-github-cd --query appId -o tsv)
   az ad sp create --id "$APP"
   az ad app federated-credential create --id "$APP" --parameters '{"name":"github-main",
     "issuer":"https://token.actions.githubusercontent.com",
     "subject":"repo:HuuThai2910/finora-platform:ref:refs/heads/main",
     "audiences":["api://AzureADTokenExchange"]}'
   # GitHub (06/11) gửi subject kèm ID tài khoản và repo, nên thêm cả dạng này (ID lấy từ lỗi AADSTS700213 nếu đổi repo):
   az ad app federated-credential create --id "$APP" --parameters '{"name":"github-main-id",
     "issuer":"https://token.actions.githubusercontent.com",
     "subject":"repo:HuuThai2910@141815687/finora-platform@1315018335:ref:refs/heads/main",
     "audiences":["api://AzureADTokenExchange"]}'
   sed "s/<SUBSCRIPTION_ID>/$SUB/" vm-deploy-role.json > /tmp/vm-deploy-role.json
   az role definition create --role-definition @/tmp/vm-deploy-role.json
   az role assignment create --assignee "$APP" --role "FINORA VM Deploy"      --scope "/subscriptions/$SUB/resourceGroups/finora-rg/providers/Microsoft.Compute/virtualMachines/finora-vm"
   echo "AZURE_CLIENT_ID=$APP AZURE_TENANT_ID=$TENANT AZURE_SUBSCRIPTION_ID=$SUB"
   ```
2. GitHub: Settings → Secrets and variables → Actions → **Variables**: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`,
   `AZURE_SUBSCRIPTION_ID` (giá trị in ở bước 1; không phải secret, không cần client secret).
3. Chạy tay CD với `services` = `all`, bật `skip_ci_check`: build đủ 9 image lên GHCR. Lần này VM chưa kéo được image
   nếu package còn private: `cd-deploy.sh` tự quay lại cụm cũ, job đỏ.
4. Packages → từng package `finora-*` → Package settings → Change visibility → **Public** (chủ tài khoản
   HuuThai2910 hoặc người có quyền admin package).
5. Chạy tay lại như bước 3. Lần đầu `cd-deploy.sh` tự chép `.env` từ `/opt/finora/src/finora-platform` sang
   `/opt/finora/env`, tạo `images.env`, chuyển cả cụm sang image GHCR. Giữ `/opt/finora/src/finora-platform` một tuần
   để quay lại nếu cần (`cd` vào `deploy/` cũ rồi `docker compose -f docker-compose.azure.yml up -d --no-build`).

### Dự phòng: build trên VM

Khi GitHub Actions/GHCR không dùng được: `git archive` cây mã lên VM, `bash build-images.sh loan` (image tag `local`,
dùng thêm `docker-compose.azure.build.yml`), sửa `LOAN_TAG=local` trong `images.env`, `sudo finora-compose up -d loan`,
`docker builder prune -af`. Giữ `keep-awake` trong lúc build. Cụm cũ tự chạy lại ngay khi VM bật; dừng service ứng
dụng trước khi đổi mã để mã cũ không ghi vào Neon.

## Vercel (finora-web)

- `vercel.json`: rewrite `/api/v1/:path*` sang domain VM, các đường khác về `index.html`. Hàm chạy ở `sin1`.
- `api/wake.js`: hàm đánh thức.
- Các file đánh thức (`api/`, `vercel.json`, `.vercelignore`, `src/features/system-wake/`, sửa `main.tsx`) nằm ở
  commit `95121d8` của nhánh `deploy`, không có trên `main`. Khi deploy: tạo worktree từ `main`, áp
  `git diff 95121d8^ 95121d8` (riêng `src/features/auth/index.ts` chỉ thêm dòng export `BrandMark`), chép
  `.vercel/project.json`, chạy `vercel --prod` trong worktree đó.
- Biến môi trường của project:
  - `VITE_SYSTEM_WAKE=true`
  - `VITE_AI_API_URL=/api/v1/ai` (từ AI-CHAT-001 `.env` của repo cũng dùng giá trị này; giữ biến cho rõ ràng)
  - `finora-ai/.env` và `finora-loan/.env` trên VM phải có cùng `AI_INTERNAL_TOKEN` (Loan gửi khi chấm điểm)
  - `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` (của `finora-vercel-wake`)
  - `AZURE_SUBSCRIPTION_ID`, `AZURE_RESOURCE_GROUP=finora-rg`, `AZURE_VM_NAME=finora-vm`
  - `FINORA_API_DOMAIN=finora-api.malaysiawest.cloudapp.azure.com`
  - `WAKE_CODE`
- Đổi domain Vercel thì sửa `WEB_DOMAIN` trong `deploy/.env` trên VM rồi `up -d --no-deps user`.
- Link preview của Vercel có domain khác nên không đăng nhập được.

## Tạo lại từ đầu (tóm tắt)

1. `az provider register` cho `Microsoft.Compute` và `Microsoft.Network`.
2. `az group create`, rồi `az vm create`. Tham số: `--size Standard_B2as_v2 --image Ubuntu2404 --os-disk-size-gb 32
   --storage-sku StandardSSD_LRS --public-ip-sku Standard --public-ip-address-dns-name finora-api --assign-identity [system]`.
3. Mở NSG 80/443.
4. `az role definition create --role-definition @vm-power-role.json`, sau khi thay `<SUBSCRIPTION_ID>`.
5. Gán role cho managed identity của VM và cho service principal. Trong Git Bash phải đặt `MSYS_NO_PATHCONV=1`,
   nếu không đường dẫn `--scope` bị đổi sai.
6. Chép thư mục này lên VM rồi chạy `sudo bash setup-vm.sh`. Sau đó chép mã nguồn và các file `.env`,
   chạy `bash build-images.sh`, khôi phục DB Fineract, rồi chạy lần đầu như mục trên.
