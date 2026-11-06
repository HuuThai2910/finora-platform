---
task_id: CI-001
title: CI chỉ chạy khi có pull request vào main và chỉ kiểm phần có thay đổi
owner: Hai
status: READY_FOR_REVIEW
depends_on: [W3E]
created_at: 2026-11-06
updated_at: 2026-11-06
approved_by: Hai
approved_at: 2026-11-06
---

# CI-001 — CI theo pull request vào `main`, chạy theo phần thay đổi

Plan sửa workflow CI do W3E dựng (D55). Chỉ đổi `.github/workflows/ci.yml` (script dò thay đổi viết thẳng trong file) và
ghi một quyết định mới; không đổi code service, entity, API, event hay migration. Ruleset của nhánh `main` là tùy chọn,
nằm trong cài đặt repo GitHub, người có quyền admin repo bật tay nếu muốn khóa merge.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **CI chỉ chạy khi thật sự sắp đưa mã vào `main`.** Hiện mỗi lần push lên `main`, `feat/**`, `fix/**` và mọi pull request
   (PR) vào bất kỳ nhánh nào đều chạy đủ 11 job. Push nháp lên nhánh tính năng cũng tốn khoảng 8 job Maven có
   Testcontainers. Sau CI-001 chỉ PR vào `main` (và bấm chạy tay) mới kích hoạt CI.
2. **Chỉ kiểm phần bị ảnh hưởng.** PR sửa một file trong `finora-loan` hiện vẫn chạy `verify` cả 8 module Java và pytest của
   AI. Sau CI-001 PR đó chỉ chạy job của `finora-loan` và job quét secret.
3. **Job Java chỉ chạy test của chính module.** Do lệnh `mvn -pl <module> -am verify`, job nào cũng chạy lại test của
   `finora-common`. Sau CI-001 `finora-common` chỉ được build và cài vào kho Maven của runner (bỏ test); test của common chỉ
   chạy ở job `finora-common` khi common có thay đổi.
4. **Một check tổng.** Sau CI-001 có check `ci-passed` đỏ khi có job lỗi, job bị bỏ qua tính là đạt. Kết quả CI vẫn chỉ để
   xem; muốn khóa nút merge khi đỏ thì admin repo bật ruleset (mục 6, tùy chọn).

### Ai tham gia và nhìn thấy gì?

| Người | Trước | Sau |
|---|---|---|
| Người mở PR vào `main` | 11 check, chờ toàn bộ Maven | Vài check liên quan + `ci-passed`; job không liên quan hiện trạng thái *Skipped* |
| Người push lên nhánh tính năng | CI chạy mỗi lần push | Không chạy; muốn kiểm sớm thì bấm *Run workflow* hoặc mở PR nháp (draft) vào `main` |
| Người duyệt/merge | Tự nhìn từng check | Nhìn một check `ci-passed`; nút merge chỉ bị khóa nếu bật ruleset |
| Admin repo | Không có việc | Tùy chọn: bật ruleset một lần (mục 6) |

### Luồng chính và luồng không thành công

1. Mở PR hoặc push thêm commit vào PR → workflow chạy; lần chạy cũ của cùng PR bị hủy.
2. Job `changes` so diff PR với `main`, xuất danh sách việc cần chạy.
3. Các job được chọn chạy song song; job không được chọn bị bỏ qua.
4. `ci-passed` chờ tất cả, đỏ nếu có job nào `failure` hoặc `cancelled`, xanh nếu còn lại là `success`/`skipped`.
5. Không thành công: `changes` lỗi (ví dụ không lấy được commit gốc) → mọi job phía sau bị bỏ qua nhưng `ci-passed` đỏ vì
   `changes` không thành công; không có trường hợp lỗi dò thay đổi mà vẫn xanh.

### Ví dụ thực tế

| PR sửa | Job chạy |
|---|---|
| `finora-loan/src/...` | Secret scan, Java (finora-loan) |
| `finora-common/src/...` | Secret scan, Java (common, loan, payment, blockchain, investment, user, notification) — không có gateway |
| `finora-ai/app/...` + `finora-user/src/...` | Secret scan, AI tests, Java (finora-user) |
| `docker/keycloak/template/realm-finora.json` | Secret scan, Java (finora-user) — test RBAC của user đọc file này |
| `docs/...`, `README.md` | Chỉ Secret scan |
| `.agents/plans/...` | Secret scan, Rule validator |
| `.github/workflows/ci.yml` | Tất cả |

### Điều kiện hoàn thành nghiệp vụ

- Push lên nhánh không phải PR vào `main` không tạo lần chạy CI.
- Ba PR thử (mục 9) cho đúng tập job ở bảng trên.
- PR có job đỏ thì `ci-passed` đỏ.

## 1. Current state, phạm vi và ngoài phạm vi

Hiện hành theo `.github/workflows/ci.yml` (commit `f36d8f3`, D55):

- `on`: `push` (`main`, `feat/**`, `fix/**`), `pull_request` (mọi nhánh), `workflow_dispatch`.
- `concurrency.group: ci-${{ github.ref }}`, `cancel-in-progress: true`.
- Job `rules` (1), `secret-scan` (1, gitleaks quét `--full-history HEAD`), `java` (ma trận cố định 8 module,
  `mvn -B -ntp -pl <module> -am verify`), `ai` (1, bỏ chọn 10 test B44 ở bước chặn).
- Không có job tổng, không có ruleset.

Phạm vi: trigger, job `changes`, ma trận Java động, lệnh Maven hai bước, phạm vi gitleaks, job `ci-passed`, cập nhật
quyết định. Ruleset chỉ ghi hướng dẫn, không bắt buộc.

Ngoài phạm vi: CD (build image, deploy VM), CI của `finora-web` và `finora-mobile` (repo khác, áp cùng quy tắc thì làm
plan riêng), sửa 10 test B44, đổi phiên bản action.

## 2. Ownership và dependency

- File sửa: `.github/workflows/ci.yml`. Không chạm module service nên không cần owner module review theo D2.
- Dependency: Git trên runner `ubuntu-24.04`, `jq` (có sẵn trên runner) để in JSON. Không thêm action bên ngoài.

## 3. Trigger và concurrency

```yaml
on:
  pull_request:
    branches: [main]
  workflow_dispatch:

concurrency:
  group: ci-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true
```

- `pull_request` mặc định gồm `opened`, `synchronize` (push thêm commit), `reopened`. PR nháp cũng chạy, để có cách kiểm sớm.
- `workflow_dispatch` giữ lại: bấm tay thì chạy hết (mục 4), dùng khi cần kiểm `main` sau khi nâng dependency.
- Concurrency theo số PR: hai lần push liên tiếp vào cùng PR chỉ giữ lần sau.

## 4. Job `changes` và bảng ánh xạ

Job nhẹ (checkout `fetch-depth: 0`, không cài JDK/Python). Script bash viết thẳng trong bước `detect` của `ci.yml`:

1. Nếu sự kiện không phải `pull_request` → chạy hết.
2. Lấy file thay đổi: `git diff --name-only <base.sha>...<head.sha>` (ba chấm = so với điểm rẽ nhánh, tức toàn bộ thay đổi
   của PR, không lẫn commit mới của `main`). `base.sha`, `head.sha` lấy từ `github.event.pull_request`.
3. Mỗi đường dẫn đi qua bảng dưới theo thứ tự; khớp dòng nào thì cộng kết quả dòng đó.
4. Xuất ra `GITHUB_OUTPUT`: `java_modules` (mảng JSON, có thể `[]`), `run_ai`, `run_rules` (`true`/`false`).

| Đường dẫn | Kết quả |
|---|---|
| `.github/workflows/ci.yml` | Chạy hết |
| `pom.xml` (gốc) | 8 module Java |
| `finora-common/**` | `finora-common` + loan, payment, blockchain, investment, user, notification |
| `finora-<module>/**` với 7 module còn lại | Module đó |
| `docker/keycloak/**` | `finora-user` (`RealmTemplateStaffRbacTest` đọc `docker/keycloak/template/realm-finora.json`) |
| `finora-ai/**`, `.github/ci/ai-known-failures-b44.txt` | AI |
| `.agents/**`, `AGENTS.md`, `CLAUDE.md`, `finora-loan/PLAN.md`, `finora-loan/plans/**` | Rules (validator đọc đúng các file này; `finora-loan/**` đồng thời chạy Java loan) |
| `docs/**`, `docker/**` còn lại, `*.md` ở gốc, `.gitignore`, `.gitleaksignore`, `hs_err_*.log`, `replay_*.log` | Không thêm job |
| Mọi đường dẫn khác | **Chạy hết** |

Dòng cuối là chốt an toàn: thêm thư mục mới mà quên cập nhật bảng thì CI chạy dư chứ không bỏ sót. Thêm module Maven mới
thì phải thêm vào bảng và vào danh sách 8 module trong script.

Đã kiểm phụ thuộc thật (06/11): mọi module trừ `finora-gateway` khai báo `finora-common`; không module nào phụ thuộc module
service khác. Test đọc file ngoài module: `RealmTemplateStaffRbacTest` (user → `docker/keycloak`), hai test Fabric của
blockchain đọc `docker/fabric-network` nhưng chỉ chạy khi `FINORA_FABRIC_IT=true` nên không đưa vào bảng. Test AI không đọc
file ngoài `finora-ai`.

## 5. Job Java: ma trận động, chỉ chạy test của module

```yaml
java:
  needs: changes
  if: needs.changes.outputs.java_modules != '[]'
  strategy:
    fail-fast: false
    matrix:
      module: ${{ fromJSON(needs.changes.outputs.java_modules) }}
```

Lệnh Maven tách hai bước:

1. `mvn -B -ntp -pl finora-common -am install -DskipTests` — build POM cha `finora-platform` và `finora-common`, cài vào
   `~/.m2` của runner, không chạy test. Bỏ bước này ở job `finora-gateway` (gateway không dùng common).
2. `mvn -B -ntp -pl <module> verify` — không có `-am`, Maven lấy `finora-common` vừa cài; chỉ chạy test của module.

Job `finora-common` chạy thẳng `mvn -B -ntp -pl finora-common -am verify` như hiện tại.

Bẫy cache: `setup-java` với `cache: maven` lưu `~/.m2/repository`, có thể chứa bản `com.finora:finora-common:1.0.0-SNAPSHOT`
cũ của lần chạy khác. Bước 1 luôn chạy trước và ghi đè nên bản cũ không bao giờ được dùng; thêm bước xóa
`~/.m2/repository/com/finora` cuối job để bản SNAPSHOT không lọt vào cache.

Đánh đổi: khi common đổi, job loan không chạy lại test của common nữa, nhưng job `finora-common` vẫn chạy chúng trong cùng
lần CI, nên không mất độ phủ.

## 6. Secret scan, job tổng và ruleset

**Secret scan** luôn chạy (secret lọt được vào bất kỳ file nào). Với PR, đổi `--log-opts` thành
`<base.sha>..<head.sha>` để chỉ quét commit của PR; `workflow_dispatch` giữ quét `--full-history HEAD`. `.gitleaksignore`
dùng fingerprint theo commit nên không cần sửa.

**Rules, AI** thêm `needs: changes` và `if: needs.changes.outputs.run_rules == 'true'` / `run_ai == 'true'`.

**`ci-passed`**:

```yaml
ci-passed:
  needs: [changes, rules, secret-scan, java, ai]
  if: always()
  runs-on: ubuntu-24.04
  steps:
    - run: |
        # đỏ nếu bất kỳ job nào failure/cancelled; skipped coi là đạt
```

Script kiểm `needs.*.result` qua `toJSON(needs)`; `changes` bắt buộc `success`.

**Ruleset cho `main`** (tùy chọn; Settings → Rules → Rulesets, admin repo bật tay). Không bật thì CI vẫn chạy và báo
kết quả trong PR, chỉ không khóa nút merge:

- Require a pull request before merging.
- Require status checks to pass: chỉ chọn `ci-passed` (không chọn từng job: job bị bỏ qua hoặc đổi tên ma trận sẽ làm
  check bắt buộc treo ở *Expected*).
- Block force pushes.

## 7. Failure và trường hợp biên

| Tình huống | Xử lý |
|---|---|
| PR sửa cả code lẫn `ci.yml` | Chạy hết (dòng đầu bảng) |
| Đổi tên/chuyển file giữa hai module | `git diff --name-only` liệt kê cả đường dẫn cũ và mới → chạy cả hai module |
| Xóa cả thư mục module | Đường dẫn bị xóa vẫn khớp `finora-<module>/**` → job module đó chạy và đỏ; người sửa phải bỏ module khỏi `pom.xml` (khớp dòng `pom.xml` → chạy hết) |
| `main` có commit mới sau khi mở PR | Diff ba chấm vẫn chỉ tính thay đổi của PR; muốn kiểm trên `main` mới thì cập nhật nhánh PR |
| Runner lỗi giữa chừng | `ci-passed` đỏ vì job `cancelled`/`failure`; bấm *Re-run failed jobs* |

## 8. File và rollout

| File | Thay đổi |
|---|---|
| `.github/workflows/ci.yml` | Trigger, concurrency, job `changes` (có script bảng ánh xạ), `needs`/`if` cho rules, ai, java; Maven hai bước; gitleaks theo PR; `ci-passed`; sửa comment đầu file |
| `.agents/plans/finora-remaining-work-plan.md` | Thêm quyết định D87 (mục 10) |
| `.agents/plans/W3E-ci-health-operations.md` | Ghi chú D55 được sửa bởi D87 |

Rollout: mở PR chứa thay đổi này vào `main` → PR tự chạy hết (vì sửa `ci.yml`) → merge → chạy các PR thử T2–T6 →
(tùy chọn) admin bật ruleset.

## 9. Test plan và acceptance

| # | Cách kiểm | Kỳ vọng |
|---|---|---|
| T1 | Chạy script bước `detect` local với `git diff` giả cho mọi dòng ở bảng ví dụ | Đúng `java_modules`/`run_ai`/`run_rules` |
| T2 | Push lên nhánh `feat/ci-001-thu` không mở PR | Không có lần chạy |
| T3 | PR thử chỉ sửa `docs/` | Secret scan + `ci-passed` xanh; còn lại *Skipped* |
| T4 | PR thử sửa một file trong `finora-loan` | Java (finora-loan) + Secret scan; log Maven không có `Tests run` của `finora-common` |
| T5 | PR thử sửa `finora-common` | 7 job Java (không gateway); job `finora-common` chạy test common |
| T6 | PR thử làm đỏ một test | `ci-passed` đỏ (nút merge bị khóa nếu đã bật ruleset) |
| T7 | Bấm *Run workflow* trên `main` | Chạy hết, gitleaks quét toàn lịch sử |

Acceptance: T1–T7 đạt, thời gian chạy T4 ghi vào mục 11 để so với lần chạy đủ 11 job.

## 10. Quyết định

| Mã | Nội dung |
|---|---|
| D87 | CI `finora-platform` chỉ chạy khi có PR vào `main` và khi bấm tay (sửa D55). Job `changes` dò diff PR bằng script viết trong `ci.yml` (không dùng action ngoài), chạy theo bảng ánh xạ, đường dẫn lạ thì chạy hết. Java build/cài `finora-common` bỏ test rồi `verify` riêng module. Gitleaks với PR chỉ quét commit của PR. Check tổng `ci-passed`; nếu bật ruleset thì chỉ đặt check này là bắt buộc. |

## 11. Bản đồ code thực tế

Triển khai 06/11, chưa commit. Tất cả nằm trong `.github/workflows/ci.yml`:

| Phần | Vị trí |
|---|---|
| Trigger + concurrency | `on:` (`pull_request` vào `main`, `workflow_dispatch`), `concurrency.group` theo số PR |
| Dò thay đổi | job `changes`, bước `detect`: `git diff --name-only BASE...HEAD` → `case` theo bảng mục 4 → `GITHUB_OUTPUT` (`java_modules`, `run_ai`, `run_rules`) + tóm tắt vào Summary của lần chạy |
| Rules, AI | `needs: changes` + `if` theo `run_rules`/`run_ai` |
| Java | ma trận `fromJSON(java_modules)`; bước "Cài finora-common (bỏ test)" (trừ common, gateway); bước `mvn verify` (common giữ `-am`, module khác không `-am`); bước cuối `always()` xóa `~/.m2/repository/com/finora` |
| Secret scan | `--log-opts` = `BASE..HEAD` với PR, `--full-history HEAD` khi chạy tay |
| `ci-passed` | `if: always()`, đọc `toJSON(needs)` bằng `jq` |

Sai khác so với dự kiến: script dò thay đổi viết thẳng trong `ci.yml` thay vì file `.github/ci/changed-modules.sh`
(Hải muốn chỉ sửa một file); ruleset chuyển thành tùy chọn.

## 12. Bằng chứng kiểm thử (06/11, Git Bash trên Windows)

- T1: tách script bước `detect` từ `ci.yml`, thay `git`/`jq` bằng bản giả, chạy 14 trường hợp: loan, common (7 module,
  không gateway), AI + user, `docker/keycloak` (user), docs/README/docker (không job), `.agents` (rules),
  `finora-loan/plans` (loan + rules), `ci.yml` (hết), `pom.xml` (8 module), gateway, file B44 (AI), đường dẫn lạ (hết),
  diff rỗng (không job), `workflow_dispatch` (hết). Cả 14 đúng bảng.
- Maven hai bước (offline, máy Hải): `mvn -pl finora-common -am install -DskipTests` thành công, reactor gồm POM cha +
  common; sau đó `mvn -pl finora-loan test-compile` không `-am` lấy common từ `~/.m2`, BUILD SUCCESS.
- YAML đọc được bằng PyYAML. T2–T7 cần chạy trên GitHub sau khi đẩy nhánh.
