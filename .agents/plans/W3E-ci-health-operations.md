---
task_id: W3E
title: CI GitHub Actions, health/readiness thật, metric sự cố vận hành, gỡ Redis của Payment và runbook (R9)
owner: Both
status: READY_FOR_REVIEW
depends_on: [W2D, W3A]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# W3E — Vận hành: CI, health, metric và runbook (R9)

Thái duyệt Q14 (GitHub Actions) trong gói Q (D19) và cho agent tự chọn phương án tốt nhất (D23). Plan làm phần R9 của
[`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 14: CI, health/readiness không chỉ kiểm cổng, giám sát
job cuối ngày Fineract (D25), metric sự cố, runbook và ma trận môi trường; đóng B23 và xử lý phần tài liệu của B21/B22.
Module của Hải bị sửa (AI test, Investment, User, Notification) theo D2: Hải review trước merge. Không thêm entity, API
nghiệp vụ, event hay migration.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **Mỗi lần push đều được kiểm như máy dev kiểm.** Trước W3E không có CI: test chỉ chạy khi ai đó nhớ chạy, secret lỡ
   commit không ai phát hiện. Sau W3E mỗi push/PR chạy validator rule, quét secret toàn lịch sử, `mvn verify` từng module
   với PostgreSQL 17 thật (Testcontainers), pytest của AI, build web và kiểm kiểu mobile.
2. **"Service sống" nghĩa là phục vụ được, không chỉ mở cổng.** Healthcheck Docker trước đây chỉ mở socket tới cổng; service
   đang chạy Flyway hoặc mất database vẫn "healthy". Sau W3E mỗi service có liveness (JVM còn chạy) và readiness (database
   và Redis mà mọi request cần), Compose dùng readiness.
3. **Biết ngay khi DPD đứng yên.** DPD, nhóm nợ và số tiền khắc phục quá hạn lấy từ Fineract, mà Fineract chỉ cập nhật khi
   job cuối ngày `LA_RINT` rồi `LA_ARAG` chạy (D25). Job không chạy thì không có lỗi nào: người vay quá hạn vẫn hiện "trong
   hạn". Sau W3E health `fineractEndOfDay` của Loan chuyển `DOWN` khi lần chạy thành công gần nhất cũ hơn 26 giờ.
4. **Đếm được hàng đợi cần người xử lý.** Vận hành phải mở từng màn admin mới biết có khoản trả nợ chờ đối soát hay saga cần
   sửa. Sau W3E Loan/Payment/Investment có metric đếm outbox chờ gửi/dead letter, saga `REPAIR_REQUIRED`, trả nợ và giải ngân
   `RECONCILIATION_REQUIRED`, sự cố lệnh góp vốn.
5. **Biết làm gì khi có sự cố.** Runbook cho khởi động lại, phát lại event/dead letter, đối soát thủ công từng hàng đợi,
   rollback migration tương thích; ma trận môi trường cho biết dependency nào thật, sandbox hay mock.

### Ai tham gia và nhìn thấy gì?

- Developer/agent: kết quả CI trên GitHub; job đỏ kèm báo cáo test; secret scan chỉ in vị trí, không in giá trị.
- Vận hành/quản trị viên: `/actuator/health/*` (ai cũng thấy trạng thái từng thành phần, chi tiết cần token `ROLE_ADMIN`),
  `/actuator/metrics/*` (chỉ `ROLE_ADMIN`), runbook trong `docs/ops/`.
- Người vay/nhà đầu tư: không đổi giao diện. Gián tiếp: DPD sai do job không chạy được phát hiện trong một ngày.

### Luồng chính và luồng không thành công

- Push → CI chạy bốn nhóm job song song → xanh thì sẵn sàng review. Test mới đỏ, secret mới, rule sai → đỏ đúng job.
- Mười test AI lệch cấu hình (B44) không chặn merge nhưng vẫn chạy ở bước báo trạng thái riêng; khi xanh, bước đó nhắc gỡ.
- Kafka tắt: readiness vẫn `UP` (event nằm trong outbox), nhóm `operations` báo `kafka DOWN`, gauge tuổi outbox tăng.
- Fineract tắt: health EOD giữ số liệu lần đọc thành công trước, vẫn chuyển `DOWN` đúng lúc vượt 26 giờ; chưa đọc được lần
  nào thì `UNKNOWN` kèm mã lỗi.

### Ví dụ thực tế

Đêm 2026-10-18 cron Fineract bị tắt sau khi bảo trì. Lần `LA_ARAG` thành công cuối là 2026-10-17 00:01:05 (giờ VN). Lúc
2026-10-18 02:01:06 health Loan chuyển `DOWN` với lý do "Job cuối ngày chưa chạy thành công trong 26 giờ: LA_ARAG (thành
công gần nhất 2026-10-16T17:01:05Z)…; DPD, nhóm nợ và số khắc phục quá hạn có thể đứng yên". Vận hành chạy bù `LA_RINT` rồi
`LA_ARAG` theo runbook mục 5, bật lại cron; trong 5 phút health về `UP`, worker servicing cập nhật DPD.

### Điều kiện hoàn thành nghiệp vụ

- CI có đủ job, không cần secret thật, action pin theo SHA, cú pháp đã kiểm.
- Mỗi service Java có liveness/readiness đúng dependency; Compose dùng readiness.
- EOD Fineract cũ hơn ngưỡng thì health `DOWN` có lý do rõ; provider booking mock không làm health đỏ.
- Metric sự cố đếm đúng trạng thái, có trần, dùng index đã có (trừ các truy vấn đã ghi rõ là quét tuần tự).
- B23 đóng có test chứng minh Payment khởi động không có Redis; runbook và ma trận môi trường có trong `docs/ops/`.

## 1. Current state, phạm vi và ngoài phạm vi

Hiện trạng trước W3E (đọc code tại `ca9efa2`):

| Hạng mục | Trước | Sau |
|---|---|---|
| CI | Không có `.github/` ở cả ba repository | Workflow platform + hai bản cho web/mobile |
| Actuator | Chỉ Loan (readiness `db`), Payment, Investment có dependency; Gateway, Blockchain, User, Notification không có | Tám service Java đều có liveness/readiness |
| Healthcheck Compose | Mở socket tới cổng | `GET /actuator/health/readiness` |
| Metric | Không có metric nghiệp vụ; Loan mở `/actuator/metrics` cho mọi token hợp lệ (B56) | Gauge sự cố; metric chỉ `ROLE_ADMIN` |
| EOD Fineract | Không giám sát | Health `fineractEndOfDay` + gauge tuổi |
| Payment Redis | Có dependency/cấu hình/container nhưng không dùng (B23) | Đã gỡ |

Ngoài phạm vi: dashboard/Prometheus/alert manager (không có scraper; endpoint metric đủ để nối sau), build image Docker
trong CI, contract test giữa owner, E2E trong CI, API admin requeue outbox (B57), DLT cho consumer Payment/Notification/
Blockchain (B58), nâng Spring Boot/Flyway/Testcontainers (D59), hàng đợi rút tiền W3B trong metric (bảng V18 chưa merge vào
nhánh này; mục 11).

## 2. Ownership, SoR và dependency

| Thay đổi | Module (owner) | Ghi chú |
|---|---|---|
| Hạ tầng health Kafka, scheduler gauge | `finora-common` (Both) | Optional dependency; chỉ bật ở service có actuator |
| EOD Fineract, gauge Loan | `finora-loan` (Thái) | Chỉ đọc Fineract qua REST `GET /jobs` (07: không đọc DB Fineract) |
| Gauge Payment, gỡ Redis | `finora-payment` (Thái) | Không đụng code withdrawal (W3B), không migration |
| Gauge Investment | `finora-investment` (Hải review) | `OrderIncidentService.ATTEMPT_THRESHOLD` thành `public` để gauge dùng cùng ngưỡng |
| Health Blockchain, gauge proof | `finora-blockchain` (Thái) | Lớp mới, giữ nguyên chain bảo mật mặc định |
| Health User, Notification | `finora-user`, `finora-notification` (Hải review) | Notification thêm actuator; khối Testcontainers/failsafe trong pom giống hệt W3F để merge sạch |
| Health Gateway | `finora-gateway` (dùng chung) | Không đổi route |
| CI, docs, registry | `.github/`, `docs/ops/`, `05-registry.md` (Both) | Registry bỏ Payment Redis |

SoR không đổi: mỗi gauge chỉ đọc database của chính service; Loan đọc trạng thái job của Fineract qua API như mọi adapter
Fineract khác.

## 3. Entity, value object, snapshot, projection

Không thêm hay đổi entity, cột, bảng hay migration. Ba kiểu dữ liệu mới chỉ sống trong bộ nhớ:

| Kiểu | Loại | Sống ở đâu | Vì sao không lưu DB |
|---|---|---|---|
| `FineractEndOfDayStatus` + `JobFreshness` | Value object (kết quả đánh giá tại một thời điểm) | Loan, bộ nhớ | Nguồn chuẩn là lịch sử job của Fineract; đọc lại được bất cứ lúc nào |
| Snapshot quan sát job (`Observation`, nội bộ monitor) | Cache đọc gần nhất | Loan, bộ nhớ | Mất khi restart thì lượt kiểm sau 20 giây đọc lại |
| Giá trị gauge | Projection số đếm | Bộ nhớ từng service | Đếm lại mỗi 60 giây từ bảng nguồn; không có lịch sử (lịch sử thuộc hệ thống metric) |

## 4. Sơ đồ thành phần (không có ERD mới)

Không có entity mới nên không có ERD. Quan hệ đọc giữa thành phần mới và bảng đã có:

```text
OperationalMonitoringScheduler (finora-common, 1 luồng daemon / service)
  ├── 1..N OperationalGaugeSet (mỗi service 1 bean) ── 1..N OperationalGauge ──đọc──▶ bảng của chính service
  │        Loan: loan_outbox_events, loan_disbursement_sagas, loan_reconciliation_incidents, loan_repayment_event_quarantine
  │        Payment: payment_outbox_events, payment_repayments, payment_disbursements, payment_top_ups
  │        Investment: investment_outbox_events, investment_orders
  │        Blockchain: blockchain_proof_submissions
  └── 0..N OperationalRefreshTask ── Loan: FineractEndOfDayMonitor ──REST──▶ Fineract GET /jobs, /jobs/{id}/runhistory
                                              ▲
HealthIndicator fineractEndOfDay ──đọc bộ nhớ─┘          HealthIndicator kafka ──AdminClient──▶ Kafka describeCluster
```

Cardinality: một scheduler mỗi service vì một luồng đủ cho vài câu đếm/phút và không tranh với worker nghiệp vụ; một
`OperationalGaugeSet` mỗi service để tag `service` thống nhất; một monitor EOD vì Loan chỉ có một Fineract. Không có FK nào
mới; các bảng được đọc giữ nguyên ràng buộc hiện hành.

## 5. Trạng thái và bất biến

### Health

| Thuật ngữ | Cách hiểu | Trong FINORA |
|---|---|---|
| Liveness | "Tiến trình còn sống không?"; sai thì orchestrator khởi động lại | Chỉ `livenessState`; database lỗi không được làm restart vòng lặp |
| Readiness | "Có nhận request được không?"; sai thì ngừng gửi traffic tới bản đó | `readinessState` + `db` (User thêm `redis`) |
| Health group | Nhóm thành phần có URL riêng | `liveness`, `readiness`, `operations` |

Bất biến: thành phần dùng chung giữa mọi service (Kafka, Keycloak, Fineract) không nằm trong readiness, vì lỗi của nó sẽ rút
mọi service khỏi traffic cùng lúc mà không giúp gì (D56).

### EOD Fineract

```text
NOT_CHECKED ──refresh OK──▶ FRESH ⇄ STALE      (đánh giá lại theo giờ thật mỗi lần đọc)
     │                        │        ▲
     └─refresh lỗi─▶ CHECK_FAILED      └── JOB_MISSING / HISTORY_UNAVAILABLE (theo kết quả đọc gần nhất)
NOT_APPLICABLE: tắt kiểm tra hoặc FINERACT_BOOKING_PROVIDER=mock (không bao giờ gọi Fineract)
```

Health: `FRESH` → UP; `STALE`, `JOB_MISSING` → DOWN; còn lại → UNKNOWN. Bất biến: tuổi tính bằng `Clock.systemUTC()`, không
dùng clock nghiệp vụ của Loan (harness E2E có thể dời clock đó, Q12).

## 6. API, event và worker

Không có API nghiệp vụ hay event mới. Endpoint vận hành (actuator):

| Endpoint | Ai gọi được | Nội dung |
|---|---|---|
| `GET /actuator/health/liveness`, `/readiness` | Mọi người (probe) | Trạng thái, tên thành phần |
| `GET /actuator/health`, `/actuator/health/operations` | Mọi người; chi tiết cần `ROLE_ADMIN` | Kafka, EOD Fineract |
| `GET /actuator/metrics/{name}` (Loan, Payment, Investment) | `ROLE_ADMIN` | Gauge sự cố |

Worker mới: luồng `finora-ops-refresh` mỗi service (mặc định chờ 20 giây sau khởi động, đếm mỗi 60 giây; task EOD mỗi 5
phút). Lỗi một lượt chỉ tăng counter và báo NaN/`checkError`, không ảnh hưởng worker khác.

## 7. Query, index và chi phí

| Thuật ngữ | Cách hiểu |
|---|---|
| Đếm có trần | `SELECT count(*) FROM (SELECT 1 … LIMIT 10000)`: dừng ở 10.000 dòng; số 10.000 nghĩa là "ít nhất 10.000" |
| Index một phần | Index chỉ chứa dòng thỏa điều kiện (ví dụ `WHERE status = 'PENDING'`): nhỏ, đếm hàng đợi chỉ đọc đúng phần đó |
| EXPLAIN với `enable_seqscan=off` | Ép planner dùng index nếu có index dùng được; còn Seq Scan nghĩa là không có index phù hợp |

| Gauge | Truy vấn | Index | Ghi chú |
|---|---|---|---|
| Loan outbox `pending` | `status = 'PENDING'` | `idx_loan_outbox_events_due` (một phần) | Index-only |
| Loan/Payment/Investment outbox `dead_letter` | `status = 'DEAD_LETTER'` | Không có | Quét tuần tự bảng outbox mỗi 60 giây; vài chục dòng/khoản vay. Thêm index một phần khi bảng lớn |
| Tuổi outbox chưa gửi | `min(created_at)` của `PENDING`/`PROCESSING` | Index một phần due + processing lease | Đọc heap đúng các dòng chưa gửi |
| Saga `REPAIR_REQUIRED` | `status = …` | `idx_loan_disbursement_sagas_status_updated` | status đứng đầu |
| Servicing incident `OPEN` | `status = 'OPEN'` | `idx_loan_reconciliation_admin_queue` hoặc `uq_loan_reconciliation_one_open_type` | |
| Event trả nợ bị giữ | `status = 'PENDING'` | `idx_loan_repayment_event_quarantine_work` | |
| Payment outbox `pending` | `status = 'PENDING'` | `idx_payment_outbox_due` | |
| Trả nợ `RECONCILIATION_REQUIRED` | `status = …` | `idx_payment_repayments_loan_in_flight` (một phần, chỉ khoản đang ghi core) | Kiểm lại status trên heap |
| Giải ngân `RECONCILIATION_REQUIRED` | `status = …` | `idx_payment_disbursements_incident_queue` | |
| Nạp tiền `RECONCILIATION_REQUIRED` | `status = …` | `idx_payment_top_ups_pending` | |
| Investment outbox `pending` | `status = 'PENDING'` | `idx_investment_outbox_events_due` | |
| `order_pending_funds_stuck` | `PENDING_FUNDS`, chưa bị dừng, `pending_attempt_count >= 3` | `ix_order_pending_due` | |
| `order_release_retrying` | Kết thúc, còn hold, chưa nhả, chưa dừng, `release_attempt_count >= 3` | `ix_order_release_due` | |
| `order_release_blocked` | `release_blocked_at IS NOT NULL` | Không có | Hiếm; quét tuần tự bảng lệnh |
| Proof `pending`/`dead_letter`, tuổi | Như outbox | `idx_blockchain_proof_submissions_due`/`_processing_lease`; dead letter quét tuần tự | |

Ba loại sự cố lệnh góp vốn rời nhau và cộng bằng đúng hàng đợi admin `findIncidents` (một OR không dùng được index); test
chứng minh tổng bằng nhau. Không thêm index vì không được thêm migration ở nhánh này (Payment V18 đã thuộc W3B; Loan và
Blockchain đang có agent khác sửa) và quy mô hiện tại nhỏ; đề xuất index một phần `WHERE status = 'DEAD_LETTER'` khi một
bảng outbox vượt khoảng 100.000 dòng.

## 8. Transaction, concurrency và idempotency

- Câu đếm là `SELECT` đơn lẻ, tự commit, không khóa dòng; `JdbcTemplate` riêng có `queryTimeout` 5 giây, không đổi
  `JdbcTemplate` dùng chung.
- Đồng thời: luồng lịch và `refreshNow()` (test/admin) có thể cùng đọc; giá trị gauge là `volatile double`, cờ log là
  `volatile`; snapshot EOD là record bất biến thay cả khối. Hai request health cùng lúc chỉ một request kiểm Kafka (khóa
  quanh lần làm mới cache 30 giây).
- Không side effect nên không cần idempotency key. Kiểm tra EOD chỉ dùng `GET`.

## 9. External failure, retry và reconciliation

| Dependency | Lỗi | Hành vi |
|---|---|---|
| Fineract `GET /jobs` | Kết nối/timeout/5xx/401/403 | Lưu `checkError` (mã của `FineractRequestExecutor`), giữ số liệu lần đọc trước, đếm `finora.operations.task.failures{task=fineract-eod}`; thử lại sau 5 phút. Không qua circuit breaker để lỗi kiểm tra vận hành không làm mở circuit của servicing/booking |
| Fineract `runhistory` | Bị từ chối | Dùng lần thành công đã thấy ở lượt trước; chưa từng thấy thì `HISTORY_UNAVAILABLE` |
| Kafka | Broker tắt | `kafka DOWN` (cache 30 giây, timeout 3 giây); không ảnh hưởng readiness |
| Database | Câu đếm lỗi/timeout | Gauge NaN, counter `finora.operations.gauge.refresh.failures`, WARN một lần mỗi đợt lỗi |

## 10. Security, audit, logging và PII

- Health chi tiết và metric chỉ cho `ROLE_ADMIN` (Loan, Payment, Investment thêm `/actuator/** hasRole("ADMIN")` sau quy tắc
  health). Blockchain chưa có JWT nên chỉ bỏ qua bảo mật đúng `/actuator/health/**` bằng `WebSecurityCustomizer`; các API còn
  lại vẫn dưới chain mặc định của Spring Boot (test kiểm 401/302).
- Chi tiết health không chứa dữ liệu khách hàng: mã job, thời điểm, mã lỗi; không trả `jobRunErrorMessage` thô của Fineract
  (có thể chứa id khoản vay) và không trả message lỗi Kafka (có thể chứa địa chỉ nội bộ).
- CI: quyền `contents: read`, checkout không lưu credential, không có secret; gitleaks in giá trị đã che.

## 11. File, rollout và việc còn lại cho người merge

File chính: `.github/workflows/ci.yml`, `.github/ci/ai-known-failures-b44.txt`, `.gitleaksignore`,
`finora-common/.../observability/*` (8 lớp + `AutoConfiguration.imports`), `finora-loan/.../integration/fineract/client/
FineractJob*`, `.../service/operations/FineractEndOfDay*`, `.../repository/operations/*`, `.../config/LoanOperationsConfig`,
`FineractEndOfDayProperties`, tương tự cho Payment/Investment/Blockchain, cấu hình `management` trong tám
`application.yml`, `docker/docker-compose*.yml`, `docker/.env.example`, `docker/smoke-infra.ps1`, `docs/ops/*`.

Rollout:

1. Merge nhánh này vào `feat/finora-remaining-wave-3` (đầu nhánh lúc kiểm cuối: `0a2ce5a`, đã chứa W3B, W3C, W3D, W3F và
   B70). `git merge-tree --write-tree` không báo xung đột; cây merge thử đã verify các module hai bên cùng sửa (mục 16).
2. Chép `docs/ops/ci/finora-web-ci.yml` thành `finora-web/.github/workflows/ci.yml` và `docs/ops/ci/finora-mobile-ci.yml`
   thành `finora-mobile/.github/workflows/ci.yml` rồi commit ở hai repository đó (agent không ghi được ngoài worktree).
3. Sau merge W3B: thêm gauge `withdrawal_reconciliation_required` vào `PaymentOperationsConfig` (một dòng) với câu đếm
   `payment_withdrawals WHERE status = 'RECONCILIATION_REQUIRED'`; index `ix_payment_withdrawals_due` không chứa lệnh chờ vận
   hành (`next_attempt_at IS NULL`) nên câu này quét tuần tự bảng rút tiền (nhỏ).
4. Rebuild container `finora-user` (launcher E2E sẽ đòi vì commit chạm `finora-user`/`finora-common`).
5. Chép quyết định D55–D59 và defect B55–B59 (mục 15) vào plan tổng hợp mục 3a/3b; đổi B23 thành Fixed, B21/B22 theo mục 15.
6. Push lần đầu để CI chạy thật; theo dõi job `java` (Docker API) và `secret-scan`.
7. Dọn sau merge (không gấp): xóa dòng khai báo volume `payment-redis-data:` trong `docker/docker-compose.yml`; thay
   `BlockchainHealthEndpointSecurityConfig` bằng `permitAll()` cho `/actuator/health/**` trong `BlockchainSecurityConfig`
   và đổi `show-details` của Blockchain sang `when-authorized` + `roles: ADMIN`.

## 12. Test plan và acceptance

| Rủi ro | Test |
|---|---|
| Gauge NaN trước lần đọc, lỗi → NaN + counter, hồi phục; task chạy trước gauge; trùng tên/tag bị từ chối; luồng riêng chạy định kỳ | `OperationalMonitoringSchedulerTest` (8) |
| Kafka UP/DOWN, chỉ trả loại lỗi, cache TTL, không broker, lỗi runtime của client vẫn thành DOWN | `KafkaClusterHealthIndicatorTest` (5) |
| Điều kiện auto-configuration, binding, thứ tự sau `KafkaAutoConfiguration`, không có class lồng | `OperationalAutoConfigurationTest` (8) |
| Parse `GET /jobs`/`runhistory` Fineract 1.15, ISO instant/offset/epoch, query `orderBy=id DESC`, phân loại lỗi | `FineractJobHttpClientTest` (5) |
| Fresh/stale theo giờ thật không cần gọi lại Fineract, lần cuối lỗi nhưng lịch sử có thành công, chưa từng thành công, thiếu job, Fineract tắt trước/sau lần đọc tốt, mock/tắt, cấu hình sai | `FineractEndOfDayMonitorTest` (12) |
| Câu đếm đúng trạng thái, trần, tuổi, index thật sự dùng được trên schema Flyway thật | `LoanOperationalMetricsRepositoryIT` (3), `PaymentOperationalMetricsRepositoryIT` (3), `InvestmentOperationalMetricsRepositoryIT` (3) |
| Nhóm health, Kafka/EOD không chặn readiness, chi tiết chỉ admin, metric 401/403/200 | `LoanOperationsHealthIT` (3), `PaymentOperationsHealthIT` (2), `InvestmentOperationsHealthIT` (2) |
| B23: Payment khởi động và readiness UP khi không có Redis trên classpath | `PaymentOperationsHealthIT.paymentStartsWithoutAnyRedisBeanAndIsReady` |
| Probe Blockchain/User/Notification/Gateway | `BlockchainOperationsHealthIT` (2), `UserHealthProbesIT` (1), `NotificationHealthProbesIT` (1), `GatewayHealthProbesTest` (1) |
| Healthcheck Compose thật sự phân biệt 200/404 | Chạy lệnh healthcheck trong `eclipse-temurin:21-jre-jammy` với jar Gateway |
| Workflow đúng cú pháp, shell không lỗi | actionlint 1.7.7 + shellcheck 0.10.0, PyYAML |

## 13. Nghiệm thu

Plan ở `READY_FOR_REVIEW`: code và test đã chạy trên máy (mục 16). Chuyển `ACCEPTED` khi Thái/Hải review, CI chạy xanh lần
đầu trên GitHub và hai workflow frontend đã commit vào repository của chúng.

## 14. Bản đồ code thực tế

| Điểm vào | Lớp → hàm | Dữ liệu / phụ thuộc |
|---|---|---|
| Khởi động service | `OperationalMetricsAutoConfiguration` → `OperationalMonitoringScheduler.start()` | Đăng ký gauge từ mọi `OperationalGaugeSet`, lịch `finora-ops-refresh` |
| Lịch 60 giây | `OperationalMonitoringScheduler.refreshGauges()` → `RegisteredGauge.refresh()` → `<Service>OperationalMetricsRepository.*` | `SELECT` có trần trên bảng của service |
| Lịch 5 phút (Loan) | `FineractEndOfDayMonitor.refresh()` → `observe()` → `FineractJobHttpClient.listJobs()`/`recentRuns()` | `GET /jobs`, `GET /jobs/{id}/runhistory?offset=0&limit=50&orderBy=id&sortOrder=DESC` |
| `GET /actuator/health/operations` (Loan) | `FineractEndOfDayHealthIndicator.health()` → `FineractEndOfDayMonitor.currentStatus()` | Bộ nhớ, `Clock.systemUTC()` |
| `GET /actuator/health/*` (`kafka`) | `KafkaHealthAutoConfiguration.kafkaHealthIndicator` → `KafkaClusterHealthIndicator.health()` | `Admin.describeCluster` ngắn hạn, cache 30 giây |
| `GET /actuator/metrics/*` | Actuator + `SecurityConfig` `/actuator/** hasRole("ADMIN")` | Giá trị gauge đã có |
| Probe Blockchain | `BlockchainHealthEndpointSecurityConfig` (`web.ignoring()` đúng đường health) | — |

## 15. Quyết định, defect và sai khác so với dự kiến

### Quyết định (D55–D59, chép vào plan tổng hợp mục 3a)

| # | Quyết định |
|---|---|
| D55 | CI GitHub Actions (Q14): platform có job `rules` (PowerShell 7 Linux), `secret-scan` (gitleaks 8.30.1 OSS bằng image pin digest, quét lịch sử của HEAD, `--redact`, fingerprint false positive trong `.gitleaksignore`), `java` (ma trận 8 module, `mvn -pl <m> -am verify`, Testcontainers trên runner, `api.version=1.44` + tắt Ryuk vì B55), `ai` (Python 3.12, cổng chặn bỏ chọn đúng 10 node id B44, bước báo trạng thái riêng không chặn); web `npm ci` + `npm run build`, mobile `npm ci` + `npx tsc --noEmit`, Node 22. Action pin SHA bản Node 24 phát hành từ 2,5 tháng trở lên; `contents: read`; không secret. Chọn bỏ chọn đúng node id thay vì `continue-on-error` cả job để test mới đỏ vẫn chặn. Ruff chưa vào CI (15 lỗi lint, 28 file chưa format ở `finora-ai`): bật sau khi owner AI sửa một lượt |
| D56 | Health: liveness chỉ `livenessState`; readiness = `readinessState` + `db` (User thêm `redis` riêng của nó); Kafka (health mới trong `finora-common`) và kiểm tra nghiệp vụ nằm ở nhóm `operations` và health tổng, không vào readiness vì outbox giữ event và broker dùng chung sẽ rút mọi service khỏi traffic cùng lúc. Tên/trạng thái thành phần công khai, chi tiết cần `ROLE_ADMIN`; metric chỉ `ROLE_ADMIN`. Compose dùng readiness qua `/dev/tcp`. Notification tắt health `mail`; Blockchain bỏ qua bảo mật đúng đường health để giữ chain mặc định |
| D57 | Health `fineractEndOfDay` (D25): đọc `GET /jobs`, lần cuối không thành công thì đọc `runhistory` (50 lần, `id DESC`) tìm lần thành công; DOWN khi `LA_RINT`/`LA_ARAG` chưa từng thành công hoặc thành công gần nhất cũ hơn `LOAN_FINERACT_EOD_STALE_AFTER` (26 giờ = chu kỳ 24 giờ + 2 giờ dư) hoặc thiếu job; UNKNOWN khi chưa kiểm, không đọc được (giữ số liệu cũ, vẫn già theo giờ thật), thiếu lịch sử, hoặc booking provider `mock`. Đọc mỗi 5 phút trên luồng vận hành, health chỉ đọc bộ nhớ; tuổi theo `Clock.systemUTC()`; không qua circuit breaker; cần quyền `READ_SCHEDULER` |
| D58 | Metric sự cố: gauge `finora.outbox.events{status}`, `finora.outbox.oldest.pending.age`, `finora.operations.incidents{type}`, `finora.fineract.eod.last.success.age{job}`, `finora.proof.*`; làm mới 60 giây trên một luồng daemon riêng mỗi service (không `@Scheduled` dùng chung, không khai báo `TaskScheduler`); đếm có trần 10.000, query timeout 5 giây, lỗi → NaN + counter; tuổi outbox tính từ `created_at` vì hạn retry luôn bị dời. Investment tách ba loại sự cố cộng bằng hàng đợi admin. Chưa thêm Prometheus registry vì chưa có scraper |
| D59 | Giữ Flyway 9.22.3 do Spring Boot 3.2.5 quản lý dù Flyway cảnh báo PostgreSQL 17 chưa được kiểm (B59): mọi test migrate/upgrade đã chạy trên PostgreSQL 17.5. Nâng cùng lần lên Spring Boot 3.4.x (Flyway 10.20.1, bản đầu hỗ trợ PostgreSQL 17) hoặc 3.5.x, thêm `flyway-database-postgresql`, verify đủ sáu module; không ghi đè riêng `flyway.version` trên Boot 3.2 |

### Defect (B55–B59, chép vào plan tổng hợp mục 3b)

| ID | Mức | Workstream | Vấn đề và bằng chứng | Trạng thái |
|---|---|---|---|---|
| B55 | P1 | R9 | Testcontainers 1.19.7 (Boot 3.2.5) mặc định gọi Docker API 1.32; Docker Engine 29 trên runner GitHub chỉ nhận từ 1.44, mọi IT sẽ lỗi "client version 1.32 is too old" (docker-java chỉ đọc `api.version` từ system property hoặc `~/.docker-java.properties`, không đọc `DOCKER_API_VERSION`). Máy dev Docker 27 (min API 1.24) không thấy | Mitigated W3E — job `java` ghi `api.version=1.44`, tắt Ryuk; sửa gốc khi nâng Testcontainers (D59) |
| B56 | P2 | R8/R9 | Loan mở `/actuator/metrics` cho mọi token hợp lệ (`anyRequest().authenticated()`): người vay đọc được metric nội bộ | Fixed W3E — `/actuator/** hasRole("ADMIN")` ở Loan/Payment/Investment; IT 401/403/200 |
| B57 | P2 | R9 | Outbox `DEAD_LETTER` của Loan/Payment/Investment không có API đưa lại hàng đợi; trước W3E không có số đếm. Proof Blockchain đã có `POST /api/v1/blockchain/proofs/{proofId}/retry` từ W3D | Open — gauge `dead_letter` + runbook mục 3.2 (SQL có kiểm soát, từng event); đề xuất API admin requeue có audit như proof của W3D |
| B58 | P1 | R2/R9 | Consumer Payment (`DisbursementRequested`, `LoanDisbursed`, `NoteOwnershipChanged`) dùng error handler mặc định: record lỗi thử 10 lần liên tiếp rồi bị bỏ qua, chỉ còn log. Loan/Investment có `.DLT`; Blockchain có `.DLT` từ W3D, Notification có `.notification.DLT` từ W3F (trên nhánh gốc `ca9efa2` hai service này cũng chưa có) | Open — runbook mục 3.4 (phát lại từ outbox nguồn, cùng `eventId`); đề xuất `DeadLetterPublishingRecoverer` cho Payment như Loan/Investment |
| B59 | P3 | R9 | Flyway 9.22.3 in "PostgreSQL 17.5 is newer than this version of Flyway… latest supported version is 15" mỗi lần khởi động | Documented (D59) — runbook mục 6.5 |
| B23 | P2 | R9 | Payment khai báo `spring-boot-starter-data-redis` nhưng không dùng Redis | Fixed W3E — gỡ dependency, `spring.data.redis`, container/volume `payment-redis`, `PAYMENT_REDIS_*`, registry; `PaymentOperationsHealthIT` chứng minh khởi động/readiness không có Redis |
| B21 | P1 | R9/R2 | DB Loan dùng chung trỏ tới Fineract của máy khác | Mitigated W3E — quy tắc "Loan DB và Fineract theo cặp" trong `ENVIRONMENT-MATRIX.md` mục 4 (DB local riêng hoặc booking `mock` với DB dùng chung); không còn việc code |
| B22 | P2 | R9 | DB dev dùng chung trễ migration | Mitigated W3E — CI chạy Flyway từ DB rỗng ở mọi job Java; quy tắc người merge migration chạy service một lần với DB dùng chung (`ENVIRONMENT-MATRIX.md` mục 4) |

### Sai khác so với dự kiến

- Worktree agent được tạo từ `main` (`00eaa81`); nhánh làm việc `feat/W3E-ci-health-operations` được tạo lại từ
  `feat/finora-remaining-wave-3` (`ca9efa2`) vì R9 phụ thuộc code Wave 2D–3A (hàng đợi sự cố, harness E2E).
- Workflow web/mobile chưa nằm trong `.github/` của hai repository: công cụ chặn ghi ngoài worktree, nên bản cuối nằm ở
  `docs/ops/ci/` để người merge chép (mục 11).
- Lỗi phát triển đã sửa: class `@Configuration` lồng trong auto-configuration của `finora-common` bị component scan của service
  nhận như cấu hình thường (service quét `com.finora.common`), làm `@ConditionalOnBean(KafkaAdmin)` luôn sai; sửa bằng điều
  kiện trên method và test chặn class lồng.
- Trong lúc làm, `feat/finora-remaining-wave-3` nhận thêm W3B, W3C, W3D, B70 và W3F. Đã kiểm `git merge-tree` sau mỗi lần
  nhánh đó đổi và chỉnh để merge sạch: giữ khai báo volume `payment-redis-data` (W3D chèn volume Fabric ngay trước dòng đó;
  volume không còn service dùng nên Compose không tạo, đã thử); test Blockchain kiểm `/api/v1/blockchain/proofs` thay cho
  `/api/v1/blockchain/health` vì chain JWT của W3D mở đường đó; pom Notification lấy nguyên khối Testcontainers/failsafe của
  W3F (commit `7d1445b`) vì hai nhánh cùng thêm khối này ở vị trí khác nhau; `.gitleaksignore` ghi thêm fingerprint hai
  false positive trong test của W3C và W3F để job `secret-scan` xanh sau merge. Sau merge, `BlockchainHealthEndpointSecurityConfig`
  có thể thay bằng `permitAll()` cho `/actuator/health/**` trong `BlockchainSecurityConfig` của W3D (khi đó đổi
  `show-details` của Blockchain sang `when-authorized` + `roles: ADMIN`); câu đếm proof `DEAD_LETTER` dùng index một phần mới
  của W3D (V2); proof `DEAD_LETTER` đưa lại hàng đợi bằng API `retry` của W3D.

## 16. Bằng chứng kiểm thử (2026-10-17, Windows, Docker Desktop 27.2, Maven 3.9.14, JDK 21)

| Lệnh | Kết quả |
|---|---|
| `mvn -B -ntp -pl finora-gateway -am verify` | BUILD SUCCESS — Gateway 4 test |
| `mvn -B -ntp -pl finora-loan -am verify` | BUILD SUCCESS — common 73, Loan unit 279, IT 23 (1 skipped có sẵn) |
| `mvn -B -ntp -pl finora-payment -am verify` | BUILD SUCCESS — common 73, Payment unit 90, IT 29 |
| `mvn -B -ntp -pl finora-investment -am verify` | BUILD SUCCESS — common 73, Investment unit 75, IT 52 |
| `mvn -B -ntp -pl finora-blockchain -am verify` | BUILD SUCCESS — common 73, Blockchain unit 11, IT 6 (chạy lại sau commit cuối `d7118ad`) |
| `mvn -B -ntp -pl finora-user -am verify` | BUILD SUCCESS — common 73, User unit 65, IT 2 |
| `mvn -B -ntp -pl finora-notification -am verify` | BUILD SUCCESS — common 73, Notification unit 5, IT 1 |
| Mô phỏng hai bước pytest của job `ai` (bash, đúng file danh sách) | Cổng chặn `379 passed, 10 deselected` (exit 0); bước B44 `10 failed` (exit 1, không chặn) |
| AI pytest (image `finora/ai:17.0.0-local`, baseline) | `10 failed, 379 passed` — đúng danh sách B44 |
| Validator rule bằng PowerShell 7.4.6 Linux | `FINORA rules validation: OK` |
| Merge thử, không tạo commit: `git merge-tree --write-tree feat/finora-remaining-wave-3 HEAD` (wave-3 `292004a`) | exit 0, không xung đột |
| Cây merge thử `a34cfe8` xuất bằng `git archive` (không có `.env`, như runner CI; commit sau đó `d7118ad` chỉ đổi comment YAML): `mvn -B -ntp -fae -pl finora-loan,finora-payment,finora-blockchain,finora-notification,finora-gateway -am verify` | BUILD SUCCESS (5:47) — common 73; Gateway 4; Loan unit 300, IT 26 (1 skipped có sẵn); Payment unit 99, IT 37; Blockchain unit 49, IT 15 (2 test Fabric live của W3D tự bỏ qua khi không có mạng Fabric); Notification unit 134, IT 19 |
| gitleaks 8.30.1 lịch sử nhánh W3E (72 commit) và `feat/finora-remaining-wave-3` `292004a` (79 commit có diff) với `.gitleaksignore` | `no leaks found` |
| `docker compose config --quiet` cho `docker-compose.yml` và `docker-compose.app.yml` (env placeholder) | exit 0; không còn service `payment-redis` hay biến `PAYMENT_REDIS_*` ở Payment |
| Lặp lại khi wave-3 lên `0a2ce5a` (thêm docs, rule, script E2E; không đổi module Java) | merge-tree exit 0; validator rule trên cây merge `FINORA rules validation: OK`; gitleaks wave-3 (81 commit có diff) `no leaks found` |
| actionlint 1.7.7 + shellcheck 0.10.0, PyYAML | Không phát hiện; 3 workflow parse được |
| Web/mobile trên bản sao sạch, Node 22.23.2 | `npm ci` + `npm run build` exit 0; `npm ci` + `npx tsc --noEmit` exit 0 |
| Healthcheck Compose với jar Gateway trong `eclipse-temurin:21-jre-jammy` | readiness exit 0, đường không tồn tại exit 1 |
