---
task_id: CD-001
title: CD finora-platform lên VM Azure bằng GitHub Actions (build image trên GitHub, VM chỉ kéo image)
owner: Hai
status: IN_PROGRESS
depends_on: [CI-001]
created_at: 2026-11-06
updated_at: 2026-11-06
approved_by: Hai
approved_at: 2026-11-06
---

# CD-001 — Deploy finora-platform lên VM Azure từ GitHub Actions

Plan thay quy trình deploy tay ở `Đồ Án/deploy/README.md` mục "Cập nhật code" bằng workflow `cd.yml`. Web không nằm trong
plan (Vercel lo). Không đổi code service, entity, API, event hay migration; đổi cách đóng gói image, bố cục thư mục trên
VM, `docker-compose.azure.yml` và quyền Azure.

Hải chốt 06/11: không có bước duyệt tay; commit nào đã qua CI thì merge xong tự deploy, CI đỏ thì không deploy. Hải có
quyền ghi và vào được Settings của repo (tự thêm Variables); package GHCR thuộc tài khoản HuuThai2910 nên đổi sang public
có thể cần Thái. Azure là subscription của Hải.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **Hết deploy tay.** Hiện mỗi lần cập nhật: `git archive` từ máy Hải, SSH lên VM (cổng 22 chỉ mở cho IP máy Hải), chép
   tay `deploy/` và 7 file `.env`, đổi tên thư mục, build lần lượt 9 image ngay trên VM 8 GB (Maven Central trên VM hay
   timeout, đã phải build image từ jar ở máy dev), rồi `docker builder prune` vì ổ 32 GB. Sau CD-001: merge PR vào `main`
   là xong, workflow tự làm phần còn lại.
2. **Chỉ deploy mã đã qua CI.** CI (CI-001) chỉ chạy ở PR. CD tìm PR của commit vừa vào `main` và đọc check `CI passed` của
   PR đó: xanh mới deploy; đỏ, chưa chạy xong hoặc commit không đi qua PR (push thẳng) thì dừng, VM không bị đụng.
3. **Biết VM đang chạy commit nào, quay lại được.** Image gắn tag theo commit; VM giữ bản trước. Deploy hỏng thì workflow
   tự trả service về tag cũ; muốn quay lại bản bất kỳ thì chạy tay workflow với commit đó, không build lại.
4. **Không cần mở SSH cho GitHub.** Lệnh chạy trên VM gửi qua API Azure (`az vm run-command`), cổng 22 vẫn chỉ mở cho máy Hải.

### Ai tham gia và nhìn thấy gì?

| Người | Việc | Nhìn thấy |
|---|---|---|
| Người merge PR vào `main` (Thái) | Merge như bình thường | Tab Actions có lần chạy CD; Summary ghi service được thay và migration Flyway mới |
| Người dùng web/mobile | Không làm gì | Trong vài phút service đang thay có thể trả 502; service khác chạy bình thường |
| Thái (admin repo) | Làm một lần: biến Azure, chuyển package sang public (mục 9) | — |
| Hải (chủ subscription Azure) | Làm một lần: identity + role cho GitHub, chuyển VM sang cách mới (mục 10) | Log deploy trong `finora-logs` |

### Luồng chính

```
merge PR vào main ─► cd.yml
  1. gate   : tìm PR của commit, check "CI passed" của PR phải xanh
  2. plan   : dò service thay đổi từ diff before..after của lần push, liệt kê migration Flyway mới
  3. build  : build + đẩy image service thay đổi lên GHCR, tag sha-<commit>
  4. deploy : az login (OIDC) ─► bật VM nếu đang tắt ─► run-command chạy deploy/cd-deploy.sh
              ─► VM: tải deploy/ đúng commit, sửa tag image, pull, up, chờ healthy, tự rollback nếu hỏng
  5. smoke  : runner đọc https://finora-api.../_status.json, mọi service healthy
  6. power  : trước khi deploy VM đang tắt thì tắt lại
```

### Luồng không thành công

| Hỏng ở | Hệ thống làm gì | Người làm gì |
|---|---|---|
| CI của PR đỏ/chưa xong, hoặc push thẳng `main` | Job `gate` đỏ, không build, không deploy | Sửa và mở PR mới; hoặc chạy tay CD sau khi CI xanh |
| Build image | Không deploy; VM không bị đụng | Sửa code, mở PR |
| Bật VM / run-command lỗi | Job đỏ; VM giữ nguyên bản cũ | Chạy lại job |
| Service mới không healthy sau 8 phút | `cd-deploy.sh` trả các service vừa thay về tag cũ, `up -d` lại, job đỏ | Đọc log (mục 7), sửa, mở PR |
| Migration Flyway đã chạy rồi mới hỏng | Rollback image **không** gỡ migration (Flyway không tự hoàn tác) | Migration phải tương thích ngược (rule 06); nếu không, sửa tay trên Neon |

Không chặn được merge khi CI đỏ: việc đó cần ruleset của nhánh `main` (Thái bật, CI-001 mục 6) và Hải chọn không dùng. Hậu
quả: PR đỏ vẫn có thể bị merge, khi đó `main` chứa mã chưa qua CI nhưng VM không nhận mã đó; lần merge sau (CI xanh) sẽ
deploy cả mã cũ chưa qua CI lẫn mã mới. Người merge cần tránh merge PR đỏ.

### Ví dụ thực tế

PR sửa `finora-loan` và thêm `V21__...sql`, CI xanh, Thái merge lúc 21:00. CD thấy `CI passed` của PR xanh, build đúng
image `finora-loan:sha-ab12cd3` (khoảng 4 phút), Summary ghi "Service: loan; migration mới: finora-loan/.../V21__....sql".
VM đang tắt nên workflow bật VM (khoảng 2 phút), cụm cũ tự lên, script thay riêng `loan`, chờ healthy, `_status.json` báo
đủ 14 service, workflow tắt VM lại. Tổng khoảng 12 phút, không ai mở terminal.

### Điều kiện hoàn thành nghiệp vụ

- Merge một PR có CI xanh sửa một service → service đó trên VM chạy đúng commit mới, service khác không bị khởi động lại.
- Merge một PR có CI đỏ → không build, không deploy.
- Deploy cố tình hỏng → VM tự quay về bản trước, `_status.json` vẫn đủ healthy.
- Chạy tay với commit cũ → VM về đúng commit đó mà không build.

## 1. Current state, phạm vi và ngoài phạm vi

Hiện hành (kiểm 06/11):

- Thư mục `deploy/` (compose, Caddyfile, script VM, systemd) nằm ở `Đồ Án/deploy`, **không có trong repo** sau lần pull 18/10.
  Trên VM là `/opt/finora/src/finora-platform/deploy`.
- `docker-compose.azure.yml` có `build:` cho 9 service (`fineract`, `ai`, 7 Java) với image `finora/<svc>:azure`; image
  build trên VM bằng `build-images.sh`.
- `env_file: ../finora-<svc>/.env` trỏ vào cây mã, nên mỗi lần thay mã phải chép lại `.env`.
- Role `FINORA VM Power`: read, instanceView, start, deallocate; gán cho managed identity VM và SP `finora-vercel-wake`.
- VM tự tắt: rảnh 45 phút hoặc 00:00, trừ khi có phiên SSH hoặc `/opt/finora/state/keep-awake`.
- `_status.json` công khai qua Caddy, liệt kê 14 service và trạng thái healthy.
- Repo `HuuThai2910/finora-platform` **public** (GitHub API trả 200 khi chưa đăng nhập) → GHCR public miễn phí, đủ cho image
  AI khoảng 3 GB.
- CI (CI-001) chỉ chạy ở PR vào `main`, có check tổng `CI passed`; không có ruleset.

Phạm vi: `cd.yml`, `deploy/` đưa lại vào repo, compose dùng image GHCR theo tag, bố cục `/opt/finora` mới, `cd-deploy.sh`,
identity Azure cho GitHub (OIDC), role deploy, cổng kiểm CI, rollback, chuyển VM sang cách mới một lần.

Ngoài phạm vi: web (Vercel), mobile, ruleset chặn merge, nâng cấp VM, Neon branch riêng cho production, giảm kích thước
image AI (`xgboost-cpu`), CI build thử image ở PR.

## 2. Ownership và dependency

- File sửa: `.github/workflows/cd.yml` (mới), `deploy/**` (đưa vào repo), `deploy/cd-deploy.sh` (mới). Không chạm module
  service nên không cần owner module review theo D2. Hải không có quyền ghi repo nên thay đổi đi qua PR, Thái merge.
- Việc chỉ admin repo làm được (Thái): biến repo, chuyển package sang public (mục 9).
- Việc trên Azure (subscription của Hải; Hải đồng ý trước từng lệnh vì tạo resource): app registration + federated
  credential, role mới, gán role.
- Dependency: CI-001 (check `CI passed`).

## 3. Thuật ngữ

- **CD (Continuous Deployment):** tự đưa mã đã merge lên môi trường chạy. Ở đây không có bước duyệt tay; điều kiện duy
  nhất là CI của PR xanh.
- **GHCR (GitHub Container Registry):** kho image Docker của GitHub, địa chỉ `ghcr.io/huuthai2910/...`. Package public kéo
  về không cần đăng nhập.
- **OIDC federated credential:** GitHub tự phát token ngắn hạn chứng minh "đây là workflow của repo X chạy trên nhánh
  `main`"; Azure tin token đó và cho đăng nhập. Không lưu secret Azure nào trên GitHub, không có secret hết hạn.
- **Run command:** Azure chạy một đoạn script bằng root trong VM qua VM agent, trả về stdout (cắt còn 4 KB cuối). Không cần
  SSH. Quyền này tương đương root trên VM, nên chỉ cấp cho identity của CD.
- **Check run:** kết quả từng job CI GitHub gắn vào commit đầu (head) của PR. CD đọc check `CI passed` qua API bằng
  `GITHUB_TOKEN`, không cần quyền admin.

## 4. Đóng gói image

| Service | Dockerfile | Image GHCR | Build khi đổi |
|---|---|---|---|
| user, notification, payment, investment, loan, blockchain, gateway | `docker/Dockerfile.java-service`, arg `SERVICE_MODULE` | `ghcr.io/huuthai2910/finora-<svc>` | `finora-<svc>/**`; `finora-common/**` (6 service trừ gateway); `pom.xml`, `docker/Dockerfile.java-service` (cả 7) |
| ai | `docker/Dockerfile.ai` | `ghcr.io/huuthai2910/finora-ai` | `finora-ai/**`, `docker/Dockerfile.ai*` |
| fineract | `docker/fineract/Dockerfile` | `ghcr.io/huuthai2910/finora-fineract` | `docker/fineract/**` |

- Tag: `sha-<7 ký tự commit>` (bất biến) và `main` (trỏ bản mới nhất, chỉ để tiện xem). VM luôn dùng tag `sha-`.
- Build bằng `docker/build-push-action` + cache `type=gha`: lớp `mvn dependency:go-offline` chỉ build lại khi `pom.xml` đổi.
- Thêm label `org.opencontainers.image.source=https://github.com/HuuThai2910/finora-platform` để package gắn với repo.
- Image không chứa secret: Dockerfile Java chỉ `COPY` `pom.xml` và `src`; AI theo `Dockerfile.ai.dockerignore` (allowlist);
  `.env` chỉ nạp lúc chạy qua `env_file`. Repo vốn public nên nội dung image không lộ thêm gì.
- Image ngoài (caddy, kafka, keycloak, redis, postgres) giữ nguyên, VM kéo từ Docker Hub/Quay như hiện tại.

## 5. Bố cục trên VM (chuyển một lần, mục 10)

```
/opt/finora/
  env/                      # secret, quyền 600, chỉ root; KHÔNG thay khi deploy
    deploy.env              # = deploy/.env hiện tại (FINORA_DOMAIN, WEB_DOMAIN, PIN secret, ...)
    finora-ai.env, finora-user.env, finora-notification.env, finora-payment.env,
    finora-investment.env, finora-loan.env
  releases/<sha>/deploy/    # deploy/ của commit đó, tải từ codeload.github.com (repo public)
  current -> releases/<sha> # symlink tới bản đang chạy
  state/
    images.env              # tag đang chạy của từng service, ví dụ LOAN_TAG=sha-ab12cd3
    images.env.prev         # bản trước, dùng khi rollback
    deployed-commit         # commit deploy gần nhất
    deploy-<sha>.log        # log đầy đủ của lần deploy
    api-access.log, public/_status.json, keep-awake   # như hiện tại
  bin/                      # finora-status.sh, finora-idle-stop.sh như hiện tại
```

`docker-compose.azure.yml` đổi:

- Bỏ `build:` của 9 service, thay `image: ghcr.io/huuthai2910/finora-<svc>:${<SVC>_TAG}`.
- `env_file: /opt/finora/env/finora-<svc>.env` (đường dẫn tuyệt đối, không phụ thuộc thư mục release).
- Chạy bằng `docker compose -p finora -f docker-compose.azure.yml --env-file /opt/finora/env/deploy.env
  --env-file /opt/finora/state/images.env ...`. Giữ project name hiện tại để volume (`kafka-data`, `caddy-data`,
  `blockchain-db-data`...) không đổi tên.
- Cụm tự lên lúc boot nhờ `restart: unless-stopped` như hiện tại.
- `host/azure-monitor-dcr.json`: thay subscription id thật bằng `<SUBSCRIPTION_ID>` như `vm-power-role.json` trước khi đưa
  vào repo public.

## 6. Workflow `cd.yml`

```yaml
on:
  push:
    branches: [main]
  workflow_dispatch:
    inputs:
      commit:   { description: "Commit cần deploy (để trống = HEAD của main)", required: false }
      services: { description: "Danh sách service, cách nhau dấu phẩy (để trống = theo diff; all = tất cả)", required: false }

permissions:
  contents: read

concurrency:
  group: cd-azure
  cancel-in-progress: false   # xếp hàng, không hủy deploy đang chạy giữa chừng
```

| Job | Quyền | Nội dung |
|---|---|---|
| `gate` | `contents: read`, `pull-requests: read`, `checks: read` | `GET /repos/{repo}/commits/{sha}/pulls` → PR đã merge chứa commit; lấy `head.sha` → `GET /commits/{head.sha}/check-runs?check_name=CI passed`; đạt khi `conclusion == success`. Không có PR (push thẳng) hoặc không đạt → đỏ, ghi lý do vào Summary. Chạy tay với commit cũ: kiểm như trên với commit đó. |
| `plan` | `contents: read` | Diff `github.event.before..github.sha` theo bảng mục 4 → `services` (JSON). `deploy/**` đổi mà không service nào đổi → vẫn deploy (cập nhật compose/Caddy, `up -d` không đổi image). Liệt kê migration Flyway mới (`*/src/main/resources/db/migration/V*.sql` được thêm). Ghi Summary. Chạy tay: lấy từ input; image của commit đó phải đã có trên GHCR, thiếu thì build. |
| `build` | `contents: read`, `packages: write` | Ma trận theo `services`, `fail-fast: false`. `docker/login-action` (GITHUB_TOKEN) → `build-push-action` → tag `sha-…`, `main`. |
| `deploy` | `contents: read`, `id-token: write` | `azure/login` OIDC → ghi trạng thái nguồn VM → `az vm start` nếu `deallocated` → `az vm run-command invoke --command-id RunShellScript --scripts @deploy/cd-deploy.sh --parameters sha=… services=…` (timeout 25 phút) → in 4 KB cuối. |
| `smoke` | — | Đọc `https://finora-api.malaysiawest.cloudapp.azure.com/_status.json` tối đa 10 phút, đạt khi 14 service `healthy`/`running`; gọi thử một API công khai qua gateway có header `Origin` của web (bẫy CORS 15/10). |
| `power` | `contents: read`, `id-token: write`; `if: always()` | VM tắt trước khi deploy thì `az vm deallocate` lại; VM đang bật thì để nguyên (idle-stop lo). |

Thứ tự `needs`: `gate` → `plan` → `build` → `deploy` → `smoke` → `power`. Mọi action pin SHA như CI (D55). Quyền khai báo
theo job để job build không có `id-token`, job deploy không có `packages: write`.

Kiểm thêm ở `gate`: CI đã chạy trên head của PR nhưng ghép với `main` lúc đó; nếu `main` có commit mới sau lần chạy CI cuối,
mã deploy là bản ghép mới chưa được CI kiểm. Chấp nhận với nhóm hai người; ghi cảnh báo vào Summary khi `CI passed` cũ hơn
commit trước đó của `main`.

## 7. Script `deploy/cd-deploy.sh` (chạy bằng root trong VM)

1. `touch /opt/finora/state/keep-awake`; `trap` gỡ file khi thoát (kể cả lỗi), để idle-stop/nightly-stop không tắt VM giữa
   chừng.
2. Chờ Docker sẵn sàng (VM vừa bật).
3. Tải `https://codeload.github.com/HuuThai2910/finora-platform/tar.gz/<sha>`, giải nén riêng thư mục `deploy/` vào
   `releases/<sha>/`; lỗi mạng thử lại 3 lần, vẫn lỗi thì thoát trước khi đụng container.
4. `cp images.env images.env.prev`; sửa dòng `<SVC>_TAG=sha-…` của các service được deploy.
5. `docker compose ... pull <services>` → `up -d --no-build --remove-orphans` (service không đổi tag thì Compose giữ nguyên
   container).
6. Chờ tối đa 8 phút tới khi mọi service trong danh sách `healthy` (healthcheck có sẵn trong compose: readiness Actuator cho
   Java, `/health` cho AI).
7. Đạt: đổi symlink `current`, ghi `deployed-commit`, xóa image GHCR không phải tag hiện tại hoặc tag trước của từng
   service, `docker image prune -f`.
8. Không đạt: `cp images.env.prev images.env`, `up -d` lại bằng release cũ, chờ healthy, thoát mã 1.
9. Mọi bước ghi `/opt/finora/state/deploy-<sha>.log` và `logger -p local0.info -t finora-cd` → lên Log Analytics
   `finora-logs` (bảng `Syslog`, `ProcessName == "finora-cd"`), vì run-command chỉ trả 4 KB cuối.

Thứ tự khởi động giữ theo `depends_on` + healthcheck của compose (user trước loan, …). Script chạy lại với cùng `sha` cho
kết quả như nhau: tag đã đúng thì `up -d` không làm gì.

## 8. Bảo mật

- **Identity riêng cho CD:** app registration `finora-github-cd`, federated credential subject
  `repo:HuuThai2910/finora-platform:ref:refs/heads/main` — chỉ workflow chạy trên nhánh `main` lấy được token; PR từ fork
  hay nhánh khác thì không.
- **Role riêng `FINORA VM Deploy`:** `virtualMachines/read`, `instanceView/read`, `start/action`, `deallocate/action`,
  `runCommand/action`, scope đúng VM `finora-vm`. Không thêm `runCommand` vào `FINORA VM Power` vì Vercel và VM đang dùng
  role đó; run-command bằng quyền root.
- **Biến repo (không phải secret):** `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`. Không có client secret.
- **Secret service** ở `/opt/finora/env`, quyền 600, không bao giờ rời VM; workflow và image không thấy.
- Log run-command có thể in tên service và tag, không in nội dung `.env` (script không `cat`/`set -x` quanh file env).
- Rủi ro còn lại: ai merge được vào `main` (Thái) là deploy được lên VM có quyền root. Chấp nhận vì không có bước duyệt.

## 9. Cài đặt GitHub (làm một lần)

1. Hải: Settings → Secrets and variables → Actions → Variables: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`,
   `AZURE_SUBSCRIPTION_ID` (giá trị in ra ở mục 10 bước 1).
2. Sau lần build đầu: Packages → mỗi package `finora-*` → Package settings → Change visibility → Public (package của tài
   khoản cá nhân tạo ra mặc định private; private thì VM phải đăng nhập GHCR). Hải thử trước; không có nút thì nhờ Thái.

Không cần Environment, ruleset hay secret.

## 10. Chuyển VM sang cách mới (một lần, Hải chạy hoặc Claude chạy khi Hải đồng ý)

1. Trên Azure: tạo app registration `finora-github-cd` + federated credential (subject mục 8) + role `FINORA VM Deploy`
   (định nghĩa `deploy/vm-deploy-role.json`) + gán role; gửi 3 id cho Thái.
2. Đưa `Đồ Án/deploy` vào `finora-platform/deploy` (che subscription id), sửa compose như mục 5, thêm `cd-deploy.sh`; mở PR.
3. Sau khi Thái merge và lần build đầu đẩy image lên GHCR (lần chạy đó `deploy` sẽ lỗi vì VM chưa có bố cục mới — chấp
   nhận, hoặc chỉ chạy tay `build` trước):
   - Trên VM (qua `finora-azure.ps1 ssh`, có `keep-awake`): `docker compose ls` để biết tên project hiện tại;
     `mkdir -p /opt/finora/{env,releases}`; chép `deploy/.env` → `env/deploy.env`, `finora-*/.env` → `env/finora-*.env`,
     `chmod 600`, không mở file.
   - Tạo `state/images.env` trỏ tag của commit vừa build.
   - Dừng cụm cách cũ (giữ volume) → chạy `cd-deploy.sh` bằng tay với commit đó → kiểm 14 service.
   - Giữ `/opt/finora/src/finora-platform` một tuần để quay lại cách cũ nếu cần, rồi xóa.
4. Cập nhật `deploy/README.md` mục "Cập nhật code" thành "merge PR có CI xanh", giữ cách tay làm phương án dự phòng.

## 11. Rủi ro và đánh đổi

| Rủi ro | Giảm thiểu |
|---|---|
| Không có bước duyệt: merge là chạy migration lên Neon dùng chung | Summary liệt kê migration; quy ước đã có: tắt cụm local khi dùng VM |
| PR đỏ vẫn merge được (không ruleset) | `gate` chặn deploy; người merge tránh merge PR đỏ |
| Cụm cũ tự lên khi VM bật, chạy vài phút trước khi được thay | Chấp nhận: mã cũ không có migration mới |
| Image AI 3 GB kéo về VM mỗi lần AI đổi | Chỉ kéo khi AI đổi; giảm bằng `xgboost-cpu` (ngoài phạm vi) |
| Run-command hết 25 phút | Pull + up thường dưới 10 phút; timeout thì job đỏ, VM giữ `keep-awake` tới khi script thoát |
| Ổ 32 GB đầy vì image cũ | Bước 7 chỉ giữ tag hiện tại + tag trước |
| Deploy lúc có người đang dùng | Service không đổi thì không khởi động lại; service đổi gián đoạn vài phút |

## 12. Test plan và acceptance

| # | Cách kiểm | Kỳ vọng |
|---|---|---|
| T1 | Chạy logic job `plan` local với diff giả (như T1 của CI-001) | Đúng danh sách service theo mục 4 |
| T2 | Chạy logic `gate` với commit có PR CI xanh / CI đỏ / không có PR (gọi API thật, repo public) | Đạt / đỏ / đỏ |
| T3 | `cd-deploy.sh` trên VM với `services=loan`, tag hiện tại | Không container nào khởi động lại |
| T4 | Merge PR (CI xanh) sửa một file `finora-loan` | Chỉ build `finora-loan`; chỉ container `loan` đổi; `_status.json` đủ healthy |
| T5 | Merge PR có CI đỏ | `gate` đỏ, không build, VM không đổi |
| T6 | Deploy image cố tình lỗi (chạy tay với commit thử) | Script rollback, job đỏ, `loan` về tag trước và healthy |
| T7 | Chạy tay với commit cũ | Không build; VM về commit đó |
| T8 | VM đang tắt lúc merge | Workflow bật VM, deploy, tắt lại |
| T9 | Log Analytics `Syslog | where ProcessName == "finora-cd"` | Có log từng bước |

Acceptance: T1–T9 đạt; README cập nhật; ghi thời gian một lần deploy một service và cả cụm vào mục 14.

## 13. Quyết định

| Mã | Nội dung |
|---|---|
| D88 | CD tự chạy khi push vào `main`, không có bước duyệt; chỉ deploy khi check `CI passed` của PR chứa commit đó xanh (không có PR thì không deploy). Không dùng ruleset. Image GHCR public tag `sha-<commit>`; đăng nhập Azure bằng OIDC (subject nhánh `main`), role riêng có `runCommand`; VM chỉ pull image, không build; tự rollback khi không healthy. Thái làm phần cài đặt repo, Hải làm phần Azure. |

## 14. Bản đồ code thực tế

Triển khai 06/11, chưa commit; chưa chạy trên GitHub/Azure (còn mục 9, 10 bước 1).

| Phần | Vị trí |
|---|---|
| Workflow | `.github/workflows/cd.yml`: `gate` (API commits/pulls + check-runs `CI passed`), `plan` (so nhãn revision của tag `main` trên GHCR với `git diff` theo đường dẫn đưa vào image), `build` (ma trận, `docker/build-push-action`, `provenance: false`, cache gha theo service), `retag` (`docker buildx imagetools create`), `deploy` (OIDC, bật VM, run-command, kiểm `/_status.json`, tắt lại) |
| Script VM | `deploy/cd-deploy.sh`: tham số `sha services repo`; tự chuyển bố cục cũ lần đầu; `images.env` + `.prev`; symlink `current`; Caddyfile cố định ở `/opt/finora/config` + `caddy reload`; đợi healthy; rollback; dọn image và release cũ; dòng cuối `FINORA_CD_RESULT` |
| Compose | `deploy/docker-compose.azure.yml` (image GHCR theo `<SVC>_TAG`, `env_file` ở `/opt/finora/env`, Caddyfile qua `FINORA_CADDYFILE`); `deploy/docker-compose.azure.build.yml` + `build-images.sh` cho build dự phòng tag `local` |
| Wrapper | `deploy/host/finora-compose.sh` → `/opt/finora/bin/finora-compose` |
| Quyền Azure | `deploy/vm-deploy-role.json`; lệnh tạo app registration + federated credential trong `deploy/README.md` mục "CD từ GitHub Actions" |
| CI | `ci.yml`: `deploy/**` và `cd.yml` không kích hoạt job kiểm nào ngoài secret scan |

Sai khác so với dự kiến:
- Chọn service bằng nhãn `org.opencontainers.image.revision` của tag `main` thay vì diff `before..after`: lần CD bị `gate`
  chặn không làm sót thay đổi ở lần sau.
- Thêm job `retag`: mọi commit đã qua CD có đủ 9 tag, quay lại bản cũ không phải build.
- Thêm input `skip_ci_check` cho chạy tay (commit đẩy thẳng `main`, lần deploy đầu).
- Bước chuyển VM (mục 10 bước 3) do `cd-deploy.sh` tự làm ở lần chạy đầu: chép `.env`, tạo `images.env`; hỏng thì chạy
  lại cụm cũ.
- Biến Azure để ở Variables của repo (Hải tự thêm) thay vì nhờ Thái.
- Smoke chỉ kiểm `/_status.json` (`ready` và `updatedAt` mới), chưa gọi API qua gateway.
- GitHub gửi subject OIDC dạng kèm ID (`repo:HuuThai2910@141815687/finora-platform@1315018335:ref:refs/heads/main`), không
  khớp subject dạng tên ở mục 8 (lỗi AADSTS700213 lần chạy đầu); app `finora-github-cd` giữ cả hai federated credential.

## 15. Bằng chứng kiểm thử (06/11, Git Bash trên Windows)

- `plan`: tách script từ `cd.yml`, chạy trên lịch sử git thật với GHCR giả (stub `docker buildx imagetools`): registry
  rỗng → build cả 9; `main` = commit trước (chỉ đổi `.agents`/`.github`) → không build, gắn tag 9, không deploy; `main` =
  `dbfec10` → build 8 (trừ fineract); chạy lại khi loan đã build → chỉ deploy loan; chạy tay `all` / `loan,user` / để trống
  đều đúng.
- `cd-deploy.sh`: chạy với `FINORA_ROOT` giả và stub `docker`/`curl`: lần đầu `all` (chép 7 file env, `images.env` đủ 9
  tag, `current`, Caddyfile) → ok; deploy loan → chỉ `pull loan`, chỉ `LOAN_TAG` đổi → ok; loan unhealthy → quay về tag và
  release trước, `rolled-back`; SHA sai → `failed`; chỉ cấu hình (`-`) → ok; pull lỗi → `rolled-back`; lần đầu pull lỗi →
  xóa `images.env`/`current`, chạy lại compose cũ, `rolled-back`.
- Workflow đọc được bằng PyYAML, mọi action pin SHA 40 ký tự. Chưa chạy actionlint/shellcheck (auto mode chặn chạy binary
  tải về).
- Chưa kiểm thật: OIDC, run-command trên VM, kéo image GHCR, `caddy reload`, thời gian deploy.
