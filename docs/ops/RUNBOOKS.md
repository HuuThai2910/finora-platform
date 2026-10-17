# Runbook vận hành FINORA

Tài liệu R9 (task plan [`W3E`](../../.agents/plans/W3E-ci-health-operations.md)). Mỗi mục trả lời: dấu hiệu là gì, kiểm
tra ở nguồn chuẩn nào, làm gì, và điều tuyệt đối không làm. Môi trường và biến cấu hình ở
[`ENVIRONMENT-MATRIX.md`](ENVIRONMENT-MATRIX.md); CI ở [`CI.md`](CI.md).

Nguyên tắc chung cho mọi thao tác dưới đây:

- Tiền và trạng thái nghiệp vụ chỉ được sửa qua API của service sở hữu (07-service-boundaries). Không sửa ví, ledger,
  saga hay khoản trả nợ bằng SQL. Ngoại lệ duy nhất có kiểm soát là đưa event outbox/proof `DEAD_LETTER` về hàng đợi
  (mục 3.2), vì thao tác đó chỉ làm relay gửi lại một event đã commit và consumer chống trùng theo `eventId`.
- Không bao giờ gọi chuyển tiền, ghi Fineract hay gửi lệnh chi lần hai "cho chắc" khi chưa tra cứu kết quả lần trước
  theo external reference (`sagaId`, `FINORA-REPAY-<repaymentId>`, `withdrawalId`, số hợp đồng).
- Mọi thao tác admin ghi lại ai làm và lúc nào (các API dưới đây đã lưu actor); ghi thêm lý do vào ticket/nhật ký vận hành.
- Token admin: đăng nhập tài khoản `ROLE_ADMIN` qua `POST /api/v1/auth/login` của User (header `X-Client-Type: mobile` để
  nhận `accessToken` trong body, như harness E2E) hoặc dùng phiên web quản trị. Không dán token vào ticket hay log.

## 1. Bảng tra nhanh

| Dấu hiệu | Nguồn | Mục |
|---|---|---|
| Service không nhận request, compose báo `unhealthy` | `/actuator/health/readiness` | 2.1, 2.2 |
| `finora.outbox.events{status="pending"}` tăng, `finora.outbox.oldest.pending.age` vượt vài phút | Metric, health `kafka` | 3.1 |
| `finora.outbox.events{status="dead_letter"}` > 0 | Metric, bảng outbox | 3.2 |
| Bản ghi trong topic `<topic>.DLT` (Loan, Investment) | Kafka | 3.3 |
| Consumer Payment log `Backoff ... exhausted`; bản ghi trong DLT của Blockchain/Notification | Log, Kafka | 3.4 |
| `finora.operations.incidents{type="repayment_reconciliation_required"}` | Payment | 4.1 |
| `finora.operations.incidents{type="disbursement_reconciliation_required"}` | Payment | 4.2 |
| `finora.operations.incidents{type="disbursement_saga_repair_required"}` | Loan | 4.3 |
| Lệnh rút tiền `RECONCILIATION_REQUIRED` (W3B) | Payment | 4.4 |
| `finora.operations.incidents{type=~"order_.*"}` | Investment | 4.5 |
| `servicing_reconciliation_open`, `repayment_event_quarantined` | Loan | 4.6 |
| Health `fineractEndOfDay` DOWN, DPD không đổi qua ngày | Loan, Fineract | 5 |
| Deploy bản mới lỗi sau migration | Flyway, log khởi động | 6 |

Đọc tín hiệu:

```powershell
# Không cần token: trạng thái từng thành phần
Invoke-RestMethod http://localhost:8081/actuator/health/operations
# Có token admin: thêm lý do, thời điểm, lỗi; metric vận hành
$headers = @{ Authorization = "Bearer $adminToken" }
Invoke-RestMethod http://localhost:8081/actuator/health/operations -Headers $headers
Invoke-RestMethod "http://localhost:8082/actuator/metrics/finora.operations.incidents?tag=type:repayment_reconciliation_required" -Headers $headers
```

Gauge làm mới mỗi 60 giây; `NaN` nghĩa là chưa đọc được (xem `finora.operations.gauge.refresh.failures`), không phải 0.

## 2. Khởi động lại service

### 2.1 Cách làm theo môi trường

| Môi trường | Lệnh |
|---|---|
| IntelliJ (`local`) | Stop rồi Run cấu hình của service |
| Stack E2E | `powershell -ExecutionPolicy Bypass -File docker/e2e/start-local-services.ps1 -Action Restart -Services payment` (giữ chế độ DB của lượt `Start`) |
| Demo Compose | `docker compose -f docker/docker-compose.app.yml restart payment`; sau khi đổi code: `docker compose -f docker/docker-compose.app.yml up -d --build --no-deps payment` |
| Container User (stack E2E) | `docker compose --env-file docker/.env -f docker/docker-compose.yml --profile demo up -d --build --no-deps user` |

Sau khi bật: chờ `GET /actuator/health/readiness` trả `200` (compose healthcheck làm việc này), rồi xem nhóm
`operations` và các gauge sự cố ở mục 1.

### 2.2 Vì sao khởi động lại lúc nào cũng an toàn và cần chờ gì

Mọi việc có side effect đều lưu trạng thái bền trong DB trước khi gọi ra ngoài, và worker nhận việc bằng lease có hạn.
JVM chết giữa chừng thì việc đang làm nằm lại ở trạng thái "đang xử lý"; hết lease worker khác (hoặc chính service sau
khi bật lại) nhận lại và đối soát theo external reference trước khi làm tiếp. Vì vậy sau khi bật lại, các việc dở sẽ tự
chạy tiếp sau đúng khoảng lease, không cần sửa tay:

| Việc dở | Trạng thái giữ lại | Hết lease sau | Bước tiếp theo khi nhận lại |
|---|---|---|---|
| Event outbox Loan/Investment | `PROCESSING` | `*_OUTBOX_PROCESSING_LEASE` (2 phút) | Gửi lại; consumer chống trùng theo `eventId` |
| Saga ghi Fineract | `CORE_BOOKING` | `LOAN_DISBURSEMENT_CORE_BOOKING_LEASE` (5 phút) | Tra khoản vay theo số hợp đồng rồi mới tạo |
| Giải ngân Payment | `PROCESSING` | `PAYMENT_DISBURSEMENT_PROCESSING_LEASE` (2 phút) | Đối soát provider theo `sagaId`, không chuyển lại |
| Rút tiền (W3B) | `PROCESSING` | `PAYMENT_WITHDRAWAL_PROCESSING_LEASE` (2 phút) | Tra cứu provider theo `withdrawalId` |
| Proof Blockchain | `PROCESSING` | `BLOCKCHAIN_PROOF_PROCESSING_LEASE` (2 phút) | Gửi lại idempotent theo source event |

Không làm: không đổi trạng thái `PROCESSING` về `PENDING` bằng tay để "chạy nhanh hơn"; không bật hai bản cùng service với
cấu hình đồng hồ/điểm dừng E2E khác nhau.

Thứ tự khi dựng lại cả stack: PostgreSQL/Kafka/Keycloak/Fineract → User → Loan, Payment, Investment → Notification,
Blockchain → Gateway. Service chịu được thứ tự khác (kết nối lười, retry), readiness cho biết lúc nào nhận request được.

## 3. Phát lại event và dead letter

### 3.1 Outbox chờ gửi tăng

Kiểm health `kafka` (nhóm `operations`). Broker tắt thì event nằm an toàn trong outbox; relay tự gửi khi broker sống lại,
không cần làm gì thêm. Nếu `finora.outbox.oldest.pending.age` tăng mà Kafka `UP`: xem log relay của service (lỗi topic,
route chưa khai báo, serializer) rồi sửa cấu hình; không xóa dòng outbox.

### 3.2 Outbox/proof `DEAD_LETTER` (Loan, Payment, Investment, Blockchain)

Relay chuyển event sang `DEAD_LETTER` sau số lần thử tối đa hoặc lỗi không retry được. Proof Blockchain: từ W3D dùng API
`POST /api/v1/blockchain/proofs/{proofId}/retry` (ADMIN), chỉ dùng câu SQL dưới đây khi chạy bản trước W3D. Outbox của
Loan, Payment, Investment chưa có API admin để đưa lại hàng đợi (B57); làm theo đúng các bước dưới, một event mỗi lần:

1. Xem lỗi trên DB của đúng service (ví dụ Loan):
   `SELECT event_id, event_type, event_version, aggregate_id, attempt_count, last_error_code, last_error_detail, updated_at
   FROM loan_outbox_events WHERE status = 'DEAD_LETTER' ORDER BY updated_at DESC LIMIT 20;`
2. Sửa nguyên nhân (topic thiếu, route sai, broker ACL, payload vượt giới hạn). Payload sai contract thì không phát lại:
   báo owner service phát event bù đúng contract.
3. Ghi `event_id` và lý do vào nhật ký vận hành, rồi đưa đúng một event về hàng đợi. Câu lệnh giữ đúng ràng buộc trạng thái
   của bảng (PENDING không có lease/token):

| Service | Câu lệnh |
|---|---|
| Loan | `UPDATE loan_outbox_events SET status = 'PENDING', attempt_count = 0, available_at = now(), processing_started_at = NULL, processing_token = NULL, updated_at = now(), version = version + 1 WHERE event_id = '<uuid>' AND status = 'DEAD_LETTER';` |
| Investment | `UPDATE investment_outbox_events SET status = 'PENDING', attempt_count = 0, available_at = now(), processing_started_at = NULL, processing_token = NULL, updated_at = now(), version = version + 1 WHERE event_id = '<uuid>' AND status = 'DEAD_LETTER';` |
| Payment | `UPDATE payment_outbox_events SET status = 'PENDING', attempt_count = 0, available_at = now(), updated_at = now() WHERE event_id = '<uuid>' AND status = 'DEAD_LETTER';` |
| Blockchain | `UPDATE blockchain_proof_submissions SET status = 'PENDING', attempt_count = 0, available_at = now(), processing_started_at = NULL, processing_token = NULL, updated_at = now(), version = version + 1 WHERE proof_id = '<uuid>' AND status = 'DEAD_LETTER';` |

4. Theo dõi tới khi dòng thành `PUBLISHED`/`CONFIRMED` và gauge `dead_letter` giảm. Lệnh `UPDATE` trả 0 dòng nghĩa là trạng
   thái đã đổi (relay khác đã xử lý): dừng, không lặp lại.

Không làm: không sửa `payload_json`, không chèn event mới tự viết, không xóa dòng (mất dấu vết đối soát).

### 3.3 Topic `<topic>.DLT` của Loan và Investment

Consumer Loan và Investment thử lại 2 lần (cách 1 giây) rồi đẩy record sang `<topic>.DLT` cùng partition. Record vẫn mang
envelope gốc nên `eventId` giữ nguyên; consumer ghi `processed_events` cùng transaction với side effect, phát lại nhiều lần
không tạo tác dụng thứ hai.

```powershell
# Đọc DLT (consumer tạm, không commit offset của service)
docker exec finora-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 `
  --topic finora.loan.disbursed.DLT --from-beginning --timeout-ms 10000 `
  --property print.key=true --property key.separator='|'
# Sau khi sửa nguyên nhân: phát lại đúng record (key|value một dòng) vào topic gốc
docker exec -i finora-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 `
  --topic finora.loan.disbursed --property parse.key=true --property key.separator='|'
```

Header trace của record gốc không đi theo khi phát lại bằng console; tra theo `eventId` trong envelope. Không phát lại record
mà consumer từ chối vì sai contract: báo producer.

### 3.4 Consumer Payment (chưa có DLT) và DLT của Blockchain/Notification

Consumer của Payment (`DisbursementRequested`, `LoanDisbursed`, `NoteOwnershipChanged`) dùng error handler mặc định của
Spring Kafka: record lỗi được thử 10 lần liên tiếp rồi bị bỏ qua, chỉ còn log (B58). Khôi phục bằng cách phát lại event từ
outbox của service nguồn (dòng vẫn còn ở trạng thái `PUBLISHED`, đủ `event_id`, topic theo `event_type`/`event_version` và
`payload_json`), dùng lệnh producer ở mục 3.3 với key là `aggregate_id`. Consumer chống trùng theo `eventId`, nên phát lại
event đã xử lý thành công không gây tác dụng lần hai. Trước khi phát lại `DisbursementRequested`, kiểm
`GET /api/v1/admin/disbursement-reconciliation/{sagaId}`: lệnh đã tồn tại thì không cần phát lại.

Từ W3D consumer Blockchain đẩy record lỗi sang `<topic>.DLT`, từ W3F consumer Notification sang
`<topic>.notification.DLT`; đọc và phát lại như mục 3.3 (Notification chống trùng theo nguồn event, người nhận và kênh).

## 4. Đối soát thủ công

Web quản trị có tab "Sự cố giao dịch" cho các hàng đợi dưới đây; API tương ứng đi qua Gateway (`http://localhost:8080`).

### 4.1 Trả nợ chờ đối soát (Payment, `RECONCILIATION_REQUIRED`)

Ý nghĩa: ví người vay đã bị trừ, tiền nằm ở `CLEARING:REPAYMENT`, nhưng chưa chắc Fineract đã ghi (mất phản hồi, worker chết).
Khoản vay bị khóa lệnh trả mới cho tới khi xử lý xong (B30).

1. `GET /api/v1/admin/repayment-reconciliation?status=RECONCILIATION_REQUIRED` — xem `errorCode`, số lần ghi lại.
2. `POST /api/v1/admin/repayment-reconciliation/{repaymentId}/reconcile` — tra Fineract theo externalId
   `FINORA-REPAY-<repaymentId>`. Thấy giao dịch thì khoản trả nợ hoàn tất và phân bổ cho nhà đầu tư.
3. Chỉ khi tra cứu xác nhận Fineract chưa có giao dịch và đã qua `PAYMENT_CORE_REPOST_SAFE_AFTER` (15 phút):
   `POST /api/v1/admin/repayment-reconciliation/{repaymentId}/repost` (D28) — ghi lại đúng externalId/số tiền/ngày.
4. Fineract từ chối ghi lại: Payment tra cứu lại rồi mới hoàn tiền về ví; ghi lý do vào ticket.

Không làm: không ghi repayment thẳng vào Fineract bằng tay, không cộng tiền lại ví bằng SQL.

### 4.2 Giải ngân chờ đối soát (Payment, `RECONCILIATION_REQUIRED`)

Ý nghĩa: provider có thể đã chuyển tiền cho người vay nhưng Payment chưa biết kết quả.

1. `GET /api/v1/admin/disbursement-reconciliation` và `GET /api/v1/admin/disbursement-reconciliation/{sagaId}` — xem
   `attemptCount`, `reconciliationAttemptCount`, `errorCode`.
2. `POST /api/v1/admin/disbursement-reconciliation/{sagaId}/recheck` — chỉ hẹn tra cứu provider theo `sagaId` lần nữa,
   không bao giờ gọi chuyển tiền.
3. Provider khẳng định đã chuyển: lệnh `COMPLETED`, Payment phát `DisbursementCompleted`, Loan đi tiếp saga. Provider khẳng
   định chưa chuyển: lệnh theo nhánh thất bại của Payment; không tạo lệnh giải ngân mới bằng tay.

### 4.3 Saga giải ngân `REPAIR_REQUIRED` (Loan)

Ý nghĩa: tiền đã giải ngân nhưng ghi khoản vay vào Fineract lỗi không tự phục hồi được.

1. `GET /api/v1/admin/disbursement-sagas?status=REPAIR_REQUIRED` — lấy `sagaId`, số hợp đồng, `lastErrorCode`.
2. Kiểm Fineract theo externalId là số hợp đồng: `GET /fineract-provider/api/v1/loans?externalId=<contractNumber>`
   (header `Fineract-Platform-TenantId`). Sửa nguyên nhân (Product chưa đồng bộ, office/payment type sai, Fineract tắt).
3. `POST /api/v1/admin/disbursement-sagas/{sagaId}/requeue` với body `{"note": "<lý do và việc đã sửa>"}` — saga ghi
   core lại; booking đối chiếu theo externalId nên không tạo khoản vay thứ hai.

Không làm: không chuyển tiền lại, không đổi trạng thái saga bằng SQL.

### 4.4 Rút tiền chờ đối soát (Payment, W3B)

1. `GET /api/v1/admin/withdrawals` — lệnh `RECONCILIATION_REQUIRED` đã hết lượt tự động (`nextAttemptAt` rỗng).
2. `POST /api/v1/admin/withdrawals/{withdrawalId}/recheck` — tra cứu provider theo `withdrawalId` (D42); chỉ khi provider
   khẳng định chưa nhận lệnh mới được gửi lại với đúng mã đó. Tiền đã trừ khỏi ví từ lúc nhận lệnh; thất bại thì Payment
   hoàn bằng bút toán ngược.
3. Gateway route `/api/v1/admin/withdrawals/**` tới Payment (W3B); khi Gateway không chạy, gọi thẳng cổng 8082.

### 4.5 Sự cố lệnh góp vốn (Investment)

Gauge tách ba loại, tổng bằng hàng đợi admin: `order_pending_funds_stuck` (giữ tiền chưa rõ kết quả sau 3 lần thử),
`order_release_retrying` (nhả khoản giữ đang thử lại), `order_release_blocked` (Payment từ chối nhả, ví dụ khoản giữ đã
vào giải ngân).

1. `GET /api/v1/investments/admin/order-incidents` — xem `orderReference`, `releaseErrorCode`.
2. Đối chiếu khoản giữ: `GET /api/v1/transactions/holds?orderReference=<orderReference>` (Payment, D30, không side effect).
3. Nguyên nhân tạm thời đã hết: `POST /api/v1/investments/admin/order-incidents/{orderReference}/retry` (chỉ hẹn worker
   chạy ngay). `PAYMENT_HOLD_NOT_RELEASABLE` vì khoản giữ đã capture vào giải ngân: lệnh thực chất đã góp vốn, cần owner
   Investment xử lý theo nghiệp vụ, không nhả tay.

### 4.6 Servicing lệch Fineract và event trả nợ bị giữ (Loan)

- `GET /api/v1/admin/loan-servicing-reconciliation/incidents` (V23: sai core loan id, externalId, gốc giải ngân, tổng
  breakdown). Sửa phía nguồn rồi `POST /api/v1/admin/loan-servicing-reconciliation/{loanNumber}/reconcile`.
- `GET /api/v1/admin/repayment-event-quarantine` (V24: event hợp lệ đến khi chưa có mapping). Sau khi mapping có:
  `POST /api/v1/admin/repayment-event-quarantine/{eventId}/replay`.

## 5. Job cuối ngày Fineract không chạy (health `fineractEndOfDay`)

Dấu hiệu: `GET /actuator/health/operations` của Loan có `fineractEndOfDay` `DOWN` (`FINERACT_EOD_STALE` hoặc
`FINERACT_EOD_JOB_MISSING`), gauge `finora.fineract.eod.last.success.age{job=...}` vượt 93.600 giây (26 giờ), DPD và số
khắc phục quá hạn không đổi qua ngày.

1. Xem chi tiết bằng token admin: job nào, `lastRunStatus`, `lastSuccessAt`, `cronActive`.
2. Trên Fineract (base `.../fineract-provider/api/v1`, header `Fineract-Platform-TenantId`, tài khoản API của Loan):
   `GET /jobs/short-name/LA_ARAG` và `GET /jobs/short-name/LA_RINT/runhistory?offset=0&limit=5&orderBy=id&sortOrder=DESC`.
3. Chạy bù theo đúng thứ tự cron (D25): `POST /jobs/short-name/LA_RINT?command=executeJob` rồi, khi lần chạy đó kết thúc,
   `POST /jobs/short-name/LA_ARAG?command=executeJob`. `LA_RINT` kết thúc `failed` thường do một khoản vay lỗi: đọc
   `jobRunErrorMessage`, xử lý khoản đó với owner Loan; `LA_ARAG` vẫn phải chạy để cập nhật `totalOverdue`.
4. `cronActive=false`: bật lại lịch bằng `PUT /jobs/short-name/<job>` với `{"active": true}` (giữ `cronExpression` đang có,
   production 00:01) hoặc qua giao diện Fineract.
5. Trong tối đa 5 phút (`LOAN_FINERACT_EOD_REFRESH_INTERVAL`) health về `UP`; worker servicing sync của Loan cập nhật DPD
   ở chu kỳ kế tiếp.

`UNKNOWN` với `checkError` là Loan không đọc được job: `FINERACT_CREDENTIAL_MISSING` (thiếu `FINERACT_API_PASSWORD`),
`FINERACT_REQUEST_REJECTED` (thường là thiếu quyền `READ_SCHEDULER`), `FINERACT_UNAVAILABLE`/`FINERACT_TIMEOUT` (Fineract
tắt). Provider booking `mock` luôn `UNKNOWN` (`FINERACT_EOD_NOT_APPLICABLE`), không phải lỗi.

## 6. Migration và rollback

### 6.1 Quy tắc migration tương thích

Migration của FINORA theo kiểu mở rộng: thêm bảng, thêm cột có mặc định hoặc cho phép NULL, thêm index, nới ràng buộc
(comment "bản cũ vẫn đọc/ghi được bảng" trong V15–V18 của Payment/Investment). Nhờ đó bản ứng dụng trước vẫn chạy được trên
schema mới. Không sửa migration đã merge: `validate-on-migrate` làm service dừng khi checksum đổi. Thu hẹp schema (xóa cột,
siết ràng buộc) tách thành migration riêng ở lần phát hành sau, khi không còn bản nào dùng phần cũ.

### 6.2 Rollback một lần deploy

1. Dừng bản mới, chạy lại bản trước (jar/image trước) trên schema hiện tại. Không xóa dòng `flyway_schema_history`, không
   drop cột mới: bản trước bỏ qua cột/bảng nó không biết.
2. Trước khi rollback, kiểm dữ liệu chỉ bản mới hiểu, ví dụ trạng thái mới trong cột `status` (bản cũ đọc enum lạ sẽ lỗi):
   `SELECT status, count(*) FROM <bảng> GROUP BY status;`. Có dữ liệu như vậy thì không rollback; sửa tiến bằng bản vá.
3. Cần gỡ hẳn thay đổi schema: viết migration mới `V<n+1>` (chỉ chạy sau khi bản đang chạy không dùng phần đó nữa), test
   migrate từ DB rỗng và từ version trước như mọi migration khác.

### 6.3 Migration lỗi khi khởi động

PostgreSQL chạy DDL trong transaction: migration lỗi bị hoàn tác cả khối và không ghi lịch sử, service dừng với lỗi Flyway.
Sửa script ở nhánh chưa merge rồi chạy lại. Câu lệnh không chạy được trong transaction (ví dụ `CREATE INDEX CONCURRENTLY`)
không dùng trong migration FINORA (V13, V28 ghi rõ lý do). Nếu lịch sử có dòng lỗi do câu lệnh ngoài transaction: dọn phần
đã tạo dở rồi chạy `flyway repair` với đúng datasource của service đó.

### 6.4 Sao lưu trước demo

Neon: tạo branch/restore point cho từng project service trước buổi demo. PostgreSQL Docker: `pg_dump` từng container DB
bằng user ứng dụng (volume riêng từng service). Không dùng chung credential giữa service khi restore.

### 6.5 Phiên bản Flyway và PostgreSQL 17 (D59)

Spring Boot 3.2.5 quản lý Flyway 9.22.3. Mỗi lần khởi động trên PostgreSQL 17 Flyway in cảnh báo: "Flyway upgrade
recommended: PostgreSQL 17.5 is newer than this version of Flyway and support has not been tested. The latest supported
version of PostgreSQL is 15." Migration của FINORA chỉ dùng SQL chuẩn; toàn bộ test migrate/upgrade của từng module chạy
trên `postgres:17.5-alpine` và xanh, nên rủi ro hiện tại là Flyway gặp hành vi PostgreSQL mới chưa được kiểm, không phải lỗi
đã thấy. Đường nâng cấp: cùng lần nâng Spring Boot lên 3.4.x (quản lý Flyway 10.20.1 — bản đầu tiên hỗ trợ PostgreSQL 17,
phát hành 2024-10-16) hoặc 3.5.x; thêm dependency `org.flywaydb:flyway-database-postgresql` vào từng module có DB, chạy
`mvn -pl <module> -am verify` cho cả sáu module và xác nhận cảnh báo biến mất trong log IT. Không ghi đè riêng
`flyway.version` trên Spring Boot 3.2: auto-configuration của 3.2 được viết cho Flyway 9.
