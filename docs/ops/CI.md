# CI GitHub Actions

Quyết định Q14 (D19) chọn GitHub Actions; thiết kế chi tiết là D55 trong task plan
[`W3E`](../../.agents/plans/W3E-ci-health-operations.md). CI không cần secret thật: database là Testcontainers, Kafka,
Keycloak và Fineract được giả lập trong test, AI test giả lập CIC và JWKS.

## 1. Workflow của từng repository

| Repository | File | Job | Cổng chặn |
|---|---|---|---|
| `finora-platform` | `.github/workflows/ci.yml` | `rules` | `.agents/scripts/validate-rules.ps1` bằng PowerShell 7 trên Linux |
| | | `secret-scan` | gitleaks 8.30.1 quét mọi commit của HEAD, log đã che giá trị |
| | | `java` (ma trận 8 module) | `mvn -B -ntp -pl <module> -am verify`, đúng lệnh trong `06-quality-gates.md` |
| | | `ai` | `pytest` của `finora-ai`, trừ đúng 10 test của B44 |
| `finora-web` | `.github/workflows/ci.yml` (bản duyệt: [`ci/finora-web-ci.yml`](ci/finora-web-ci.yml)) | `build` | `npm ci` + `npm run build` (tsc + vite) |
| `finora-mobile` | `.github/workflows/ci.yml` (bản duyệt: [`ci/finora-mobile-ci.yml`](ci/finora-mobile-ci.yml)) | `typecheck` | `npm ci` + `npx tsc --noEmit` |

Trigger: push lên `main`, `feat/**`, `fix/**`; mọi pull request; chạy tay (`workflow_dispatch`). Lượt mới trên cùng ref
hủy lượt cũ. Quyền token mặc định chỉ `contents: read`; checkout không lưu credential.

## 2. Pin version

Mọi action pin theo commit SHA, tag ghi ở comment, chỉ dùng bản phát hành ít nhất hai tuần và chạy Node 24 (Node 20 hết
hỗ trợ tháng 4/2026):

| Action | Tag | SHA |
|---|---|---|
| `actions/checkout` | v6.0.2 | `de0fac2e4500dabe0009e67214ff5f5447ce83dd` |
| `actions/setup-java` | v5.7.0 | `b6effb05e454b25005698d916606bdc6ffcbf961` |
| `actions/setup-python` | v6.3.0 | `ece7cb06caefa5fff74198d8649806c4678c61a1` |
| `actions/setup-node` | v6.5.0 | `249970729cb0ef3589644e2896645e5dc5ba9c38` |
| `actions/upload-artifact` | v6.0.0 | `b7c566a772e6b6bfb58ed0dc250532a479d7789f` |
| gitleaks (image) | v8.30.1 | `sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f` |

Nâng version: lấy SHA bằng `git ls-remote https://github.com/<owner>/<action> refs/tags/<tag>`, kiểm ngày phát hành và
input của `action.yml` ở đúng SHA, cập nhật SHA và comment cùng lúc ở cả ba repository.

## 3. Testcontainers trên runner GitHub

Spring Boot 3.2.5 quản lý Testcontainers 1.19.7, mặc định gọi Docker API 1.32. Docker Engine 29 trên runner Ubuntu chỉ nhận
API từ 1.44, nên mọi IT sẽ lỗi "client version 1.32 is too old" (B55). Job Java ghi `api.version=1.44` vào
`~/.docker-java.properties` (docker-java đọc file này; biến `DOCKER_API_VERSION` không có tác dụng) và tắt Ryuk
(`TESTCONTAINERS_RYUK_DISABLED=true`) vì image Ryuk của bản 1.19 cũng dùng API cũ; runner tự hủy sau job nên container
không bị bỏ lại. Hai thiết lập này vô hại với Docker Engine 25 trở lên. Sửa gốc: nâng Testcontainers cùng lần nâng Spring Boot
(D59) rồi bỏ bước này.

## 4. AI và B44

Baseline ngày 2026-10-17 (commit `ca9efa2`): `10 failed, 379 passed`. Mười test đỏ là lệch giữa test và
`finora-ai/config/product_config.json`/payload `TestRouterV14` (B44, Hải quyết cấu hình nào chuẩn vì đổi bên nào cũng đổi
điểm tín dụng). Job `ai`:

1. Bước chặn chạy toàn bộ suite với `--deselect` đúng các node id trong
   [`.github/ci/ai-known-failures-b44.txt`](../../.github/ci/ai-known-failures-b44.txt). Test mới đỏ, hoặc test B44 đổi
   tên, vẫn làm job đỏ.
2. Bước báo trạng thái (không chặn) chạy riêng danh sách đó, ghi cảnh báo vào summary; khi cả danh sách xanh nó nhắc xóa
   file. Chọn cách này thay cho `continue-on-error` cả job vì cách đó sẽ che luôn lỗi mới.

Chạy lại đúng như CI trên máy có Docker (dùng image runtime của AI, không cài gì vào máy):

```powershell
docker run --rm -v "${PWD}\finora-ai\tests:/app/tests:ro" -v "${PWD}\finora-ai\pytest.ini:/app/pytest.ini:ro" `
  -v "${PWD}\finora-ai\app:/app/app:ro" -v "${PWD}\finora-ai\main.py:/app/main.py:ro" `
  -v "${PWD}\finora-ai\config:/app/config:ro" -v "${PWD}\finora-ai\models:/app/models:ro" `
  -v "${PWD}\finora-ai\requirements-dev.txt:/tmp/requirements-dev.txt:ro" finora/ai:17.0.0-local `
  sh -c "python -m pip install --user -q -r /tmp/requirements-dev.txt && python -m pytest -q -p no:cacheprovider"
```

## 5. Secret scan

gitleaks quét lịch sử của HEAD (`--log-opts="--full-history HEAD"`): mặc định nó quét `--all`, kéo cả nhánh khác chưa merge
vào kết quả. `--redact` che giá trị trong log. Không dùng `gitleaks-action` vì action này đòi license key với repository của
tổ chức; image OSS (MIT) không cần.

Kết quả rà ngày 2026-10-17: không có secret thật trong lịch sử của `feat/finora-remaining-wave-3` (79 commit có diff, gồm
W3B, W3C, W3D, W3F); sáu phát hiện là false positive đã ghi fingerprint trong [`.gitleaksignore`](../../.gitleaksignore) (hằng khóa
idempotency trong test, URL JDBC của Keycloak trong compose cũ, một dòng roadmap mô tả API eKYC, khóa mã hóa số tài khoản dev
của W3B bị cổng môi trường D37 chặn ở staging/production, token push giả trong test Notification, khai báo record chứa tham
số khóa riêng trong test PAdES). Không chép nguyên văn chuỗi bị nhận nhầm vào tài liệu, kể cả `.gitleaksignore`: chép lại sẽ
tạo phát hiện mới ở commit đó. Nhánh
`origin/chatbot-ai` (Hải, chưa merge) có một phát hiện `INTERNAL_TOKEN_TEST` trong
`docs/superpowers/plans/2026-10-15-tro-ly-finora.md`: Hải xác nhận là giá trị giả trước khi merge nhánh đó.

Gặp phát hiện mới trên PR: xác nhận giá trị là secret thật hay không. Secret thật thì thu hồi/đổi khóa trước, rồi xóa khỏi
code (lịch sử vẫn còn, nên đổi khóa là bắt buộc). False positive thì thêm `gitleaks:allow` trong comment trên đúng dòng, hoặc
thêm fingerprint `commit:file:rule:line` vào `.gitleaksignore` kèm một dòng lý do. Quét trước khi push:

```powershell
docker run --rm -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' `
  -v "${PWD}:/repo:ro" -w /repo ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo `
  --log-opts="--full-history HEAD" --gitleaks-ignore-path /repo/.gitleaksignore --redact --no-banner
```

Trong worktree (`.git` là file trỏ ra ngoài) mount thư mục `.git` chính của repository vào container và quét theo tên nhánh.

## 6. Chạy cổng chất lượng trên máy trước khi push

```powershell
powershell -ExecutionPolicy Bypass -File .agents/scripts/validate-rules.ps1
mvn -B -ntp -pl finora-loan -am verify          # từng module đã đổi
```

Web: `npm ci; npm run build`. Mobile: `npm ci; npx tsc --noEmit`. Node 22 như CI.

## 7. Giới hạn hiện tại

- Workflow mới được kiểm cú pháp (actionlint, PyYAML) và chạy thử từng lệnh trên máy; chưa chạy trên GitHub cho tới lần
  push đầu tiên.
- Chưa có job build image Docker, contract test giữa owner hay E2E harness (harness cần Fineract/Keycloak thật và chạy trên
  máy dev, D11).
- Java chạy `-am` nên mỗi job module chạy lại test của `finora-common` (khoảng một phút); đổi lấy lệnh giống hệt cổng chất
  lượng trên máy.
- Ruff chưa vào CI: `ruff check` (0.8.4) còn 15 lỗi (E741, E402, F541, F401) và `ruff format --check` báo 28 file chưa
  format trong `finora-ai`. Bật thành cổng chặn sau khi owner AI sửa một lượt, để CI không đỏ vì nợ cũ ngay từ lần đầu.
