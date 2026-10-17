---
task_id: W3D
title: Neo hash bốn mốc nghiệp vụ lên Hyperledger Fabric local, explorer và kiểm toàn vẹn (R6)
owner: Thai
status: READY_FOR_REVIEW
depends_on: [BC-001, D14, D19-Q10]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# W3D — Bằng chứng blockchain trên mạng Hyperledger Fabric local

Thái chốt D14 (dựng Fabric local thật, MOCK vẫn mặc định) và Q10 trong D19 (2 tổ chức × 1 peer, 1 orderer Raft, không
CouchDB, bật bằng `-WithFabric` + profile Compose, neo 4 mốc), cho agent tự chọn phần kỹ thuật còn lại (D23). Plan này
ghi các lựa chọn đó (D50–D54), defect tìm thấy (B50–B54), thiết kế và bằng chứng chạy thật. Kiểm soát pháp lý:
[`LEGAL-DATA-01`, `LEGAL-CONTRACT-01`](../../docs/LEGAL-COMPLIANCE.md). Module: `finora-blockchain` (Thái) và hạ tầng
mới `docker/fabric-network/`; không đổi producer Loan/Payment/Investment.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

FINORA lưu hợp đồng, tiền và Note trong database của từng service. Database có thể bị sửa âm thầm bởi người có quyền
quản trị. **Proof** (bằng chứng) là dấu vân tay SHA-256 của một sự kiện nghiệp vụ đã xảy ra, ghi lên một sổ cái mà nhiều
tổ chức cùng giữ (**Hyperledger Fabric**). Sau này ai nghi ngờ "hợp đồng này có bị sửa không, khoản giải ngân này có
thật không" thì tính lại dấu vân tay từ dữ liệu gốc và so với sổ cái: khớp là dữ liệu nguyên vẹn từ lúc neo.

Trước task: chỉ có proof `REPAYMENT`, ledger `MOCK` (receipt giả có nhãn rõ), Fabric fail-closed, không có API tra cứu.
Sau task:

1. Bốn mốc được neo tự động: hợp đồng đủ chữ ký, giải ngân, trả nợ, phát hành/chuyển nhượng Note.
2. Mạng Fabric local thật: tổ chức **FINORA (Org1)** ghi, tổ chức **kiểm toán độc lập (Org2)** cùng endorse (ký xác
   nhận) mọi giao dịch ghi — FINORA không thể tự viết lại lịch sử nếu Org2 không đồng ý.
3. Admin tra proof theo khoản vay, kiểm "dữ liệu gốc → hash" và "database → sổ cái", đưa proof lỗi về hàng đợi.
4. Fabric tắt chỉ làm proof chờ và thử lại; tiền và hợp đồng ở Loan/Payment/Investment không bị chặn hay đảo ngược.

### Ai tham gia và nhìn thấy gì?

| Người/hệ thống | Làm gì | Thấy gì |
|---|---|---|
| Loan/Payment/Investment (producer) | Phát event v1 đã có qua outbox, không đổi code | Không thấy gì mới |
| finora-blockchain | Consume 4 topic, tính hash, ghi DB, worker gửi Fabric | Log proof, DLT nếu event sai |
| Admin FINORA (web, sắp có) | `GET /api/v1/blockchain/proofs?referenceType=LOAN_APPLICATION&referenceId=<id>`, verify, retry | Danh sách proof của khoản vay, loại receipt `FABRIC_ON_CHAIN`/`MOCK_NOT_ON_CHAIN`, kết quả đối chiếu |
| Tổ chức kiểm toán (Org2) | Peer riêng, chaincode riêng, endorse mọi lần ghi | `fabric-network.ps1 -Action Proof -Org org2 -ProofId <id>` đọc thẳng từ peer của mình |
| Vận hành/dev | `fabric-network.ps1 -Action Up/Down/Status`, launcher `-WithFabric` | RAM Fabric, trạng thái container |

### Luồng chính và luồng không thành công

```text
Loan/Payment/Investment commit state + outbox ──Kafka──► MilestoneProofConsumer (blockchain-group)
   │                                                        │ 1 transaction DB Blockchain: kiểm envelope,
   │ (không chờ, không phụ thuộc Blockchain)                │ hash canonical, INSERT ... ON CONFLICT DO NOTHING
   ▼                                                        ▼
tiền/hợp đồng đã xong                           blockchain_proof_submissions = PENDING
                                                            │ ProofSubmissionWorker (5 giây/lần, lease 2 phút)
                                                            ▼
                                    FabricProofLedger: GetProof trước → (chưa có) RegisterProof
                                    Gateway peer0.org1 xin endorsement Org1 + Org2 → orderer → block
                                                            ▼
                                    CONFIRMED + txId (64 hex) + "finora-channel/<block>"
```

| Tình huống | Kết quả |
|---|---|
| Kafka giao trùng cùng event | Trả proof cũ (unique source event), không thêm dòng |
| Cùng mốc phát lại với eventId mới | Trả proof cũ (proofId xác định theo mốc + unique mốc) |
| Cùng mốc nhưng hash khác | `409 PROOF_MILESTONE_CONFLICT` → `<topic>.DLT` để điều tra |
| Event sai contract (thiếu field, số thực) | `IllegalArgumentException` → `<topic>.DLT` ngay |
| PostgreSQL Blockchain tạm lỗi khi consume | Retry 1-2-4-8-16 giây rồi `<topic>.DLT` (B52) |
| Peer/orderer tắt, mất kết nối | Proof giữ `PENDING`, retry backoff 5 giây → 5 phút, tối đa 10 lần rồi `DEAD_LETTER` |
| Gửi orderer xong nhưng chưa biết commit (timeout/JVM chết) | Lần sau `GetProof` thấy bản ghi → dùng lại txId gốc, không ghi lần hai |
| Fabric đã có proofId với hash khác | `FABRIC_PROOF_CONFLICT` (không retry) → `DEAD_LETTER` |
| DB bị sửa hash sau khi neo | Verify/worker đối chiếu báo `MISMATCHED`, log ERROR; không tự sửa bên nào |

### Dữ liệu nào ảnh hưởng kết quả?

| Dữ liệu | Người dùng hiểu là gì | Nguồn | Dùng ở bước nào | Nếu thiếu/sai | Vì sao lưu |
|---|---|---|---|---|---|
| `data` của event | Sự thật nghiệp vụ service nguồn đã commit | Outbox Loan/Payment/Investment | Tính hash | Không neo, event vào DLT | **Không lưu** ở Blockchain (payload tài chính) |
| `payload_hash` + `payload_version` | Dấu vân tay + cách tính | Blockchain | Ghi chain, verify | — | Bằng chứng |
| `proof_type` + `aggregate_type/id` | Mốc nào của đối tượng nào | Event | Idempotency theo mốc, tra cứu | Event vào DLT | Một mốc một proof |
| `loan_application_id` | Khoản vay liên quan | Event | Explorer theo khoản vay | Proof vẫn neo, không gom được theo khoản vay | Tra cứu |
| `source_event_type/version/occurred_at` | Event nào là artifact | Envelope | Kiểm toán lấy đúng artifact | null với bản ghi cũ | Truy vết |
| `provider` | MOCK hay Fabric | Cấu hình lúc đăng ký | Worker chọn ledger | — | Không lẫn receipt (B50) |
| `provider_transaction_id/block_reference` | Biên nhận | Ledger | Explorer, đối chiếu | — | Tham chiếu chain |
| `chain_check_status/at` | Lần đối chiếu gần nhất | Verify/worker | Explorer, cảnh báo | null = chưa kiểm | Audit result F08 |

### Ví dụ thực tế (chạy ngày 2026-10-17, mục 16)

Hợp đồng `LC-IT-1792236138391` đủ chữ ký → proof `212ebdfe-…` → giao dịch Fabric `ffeeb8d0…9b48` ở block 4. Tổ chức
kiểm toán đọc từ peer của mình: cùng hash `db6c7a93…11c0`, `submitterMspId=Org1MSP`. Sửa hash proof trả nợ trong
PostgreSQL thành `111…1` → verify báo `MISMATCHED`, chain vẫn giữ `4a56b399…d04e`.

### Điều kiện hoàn thành nghiệp vụ

- Hash tính lại được từ artifact nguồn (Java + Python độc lập + `jq` cho cùng kết quả).
- Receipt MOCK và Fabric không thể lẫn nhau (định dạng loại trừ, guard trong worker, check constraint DB).
- Trùng/restart không tạo proof logic thứ hai (DB + chain).
- Fabric tắt chỉ làm proof chờ/retry/DLT.

## 1. Current state, phạm vi và ngoài phạm vi

**Trước W3D (đọc code commit `ca9efa2`):** bảng `blockchain_proof_submissions` (V1) với idempotency theo source event,
lease/claim token, retry/DEAD_LETTER; consumer `RepaymentDistributedProofConsumer` hash `mapper.writeValueAsBytes(data)`;
`HyperledgerFabricProofLedger` luôn ném `FABRIC_INTEGRATION_NOT_READY`; không API; `fabric-gateway 1.4.0` khai báo nhưng
không dùng; không có network/chaincode; launcher chạy blockchain với `BLOCKCHAIN_PROOF_PROVIDER=MOCK`.

**Trong phạm vi:** mạng Fabric local + chaincode, adapter Java, consumer 4 mốc, explorer/verify/retry API, worker đối
chiếu, migration V2, launcher `-WithFabric`, test (unit, IT MOCK, IT chạy thật trên Fabric), tài liệu.

**Ngoài phạm vi:** trang explorer trên `finora-web`; proof quyết định servicing (tất toán/cơ cấu/nợ xấu); Fabric CA,
HSM, nhiều orderer, TLS peer↔chaincode; API lấy artifact từ service nguồn; production deployment.

## 2. Ownership, SoR và dependency

- Blockchain sở hữu proof, trạng thái gửi, receipt, kết quả đối chiếu (07-service-boundaries). Không giữ payload gốc.
- Loan/Payment/Investment vẫn là SoR của hợp đồng, tiền, Note; Blockchain chỉ consume event đã đăng ký
  (`05-registry`: thêm `blockchain-group` cho `finora.loan.contract-activated`, `finora.loan.disbursed`,
  `finora.investment.note-ownership-changed`).
- Fabric là SoR của bản ghi on-chain; PostgreSQL Blockchain là bản theo dõi trạng thái gửi.
- **Producer không phải đổi**: bốn event v1 hiện có đủ field cho mốc (mục 8).

## 3. Quyết định D50–D54

| # | Quyết định | Lý do và phương án đã loại |
|---|---|---|
| D50 | **Mạng**: Fabric **2.5.16 LTS** (GHCR, 2026-06-17); `OrdererMSP` Raft 1 node + `Org1MSP` (FINORA, ghi) + `Org2MSP` (kiểm toán, endorse/đọc), mỗi org 1 peer, LevelDB; channel participation API (không system channel); anchor peer trong genesis block; **cryptogen** chạy trong `fabric-tools` (không cần binary trên Windows), crypto vào `crypto-config/` git-ignore; TLS mọi kết nối client/peer/orderer; chỉ gateway `peer0.org1` mở `127.0.0.1:7051`; profile Compose `fabric` + `fabric-tools`; `mem_limit` từng container; launcher `-WithFabric` kiểm RAM Docker ≥ 768 MiB | 2.5 là LTS còn vá (3.x đổi BFT/không cần cho demo). Fabric CA đúng cho production nhưng thêm 2–3 container và quy trình enroll; cryptogen đủ cho local và được Fabric docs khuyến nghị cho test. Endorsement `MAJORITY` với 2 org = cả hai phải ký: đúng ý nghĩa "kiểm toán độc lập" |
| D51 | **Chaincode** `finora-ledger` viết **Go, shim thuần** (không contractapi), chạy **chaincode-as-a-service**: builder `ccaas_builder` có sẵn trong image peer, peer gọi tới container chaincode theo `connection.json`; **không mount `docker.sock`**; mỗi org một container chaincode; `core.yaml` sinh từ image và bỏ `vm.endpoint`. Bản ghi write-once khóa theo proofId; index composite `aggregate~type` (một proof/mốc trên chain); `RegisterProof` idempotent; ACL writer = `Org1MSP`; allowlist proofType và regex mã aggregate (chặn khoảng trắng/chữ có dấu → không chèn được PII); `GetProof`, `GetProofsByAggregate` | Shim thuần ít dependency (chaincode-go v2.3.0 + protos v0.3.7), không reflection/metadata; contractapi v2.2.3 đòi Go 1.26.7 và một pseudo-version chưa release. CCaaS tránh cấp quyền Docker daemon cho peer (rủi ro bảo mật lớn nhất của mô hình build cũ) và chạy được trên Docker Desktop |
| D52 | **Bốn mốc**: hợp đồng đủ chữ ký (`LoanContractActivated.v1` → `CONTRACT_DOCUMENT`, aggregate `LoanContract/<contractNumber>`), giải ngân (`LoanDisbursed.v1` → `DISBURSEMENT`, `DisbursementSaga/<sagaId>`), trả nợ (`RepaymentDistributed.v1` → `REPAYMENT`, `Repayment/<repaymentId>`), Note (`NoteOwnershipChanged.v1` → `NOTE_OWNERSHIP`, `NoteIssuance/<listingId>` hoặc `NoteTransfer/<tradeReference>`). **Hash** = SHA-256 của `data` theo **FINORA canonical JSON v2** (tập con RFC 8785: key sắp UTF-16, bỏ member null, không khoảng trắng, chỉ số nguyên ±2^53−1), `payload_version=2`; v1 cũ giữ để kiểm. Servicing (tất toán/cơ cấu/nợ xấu) để BC-005 | Bốn mốc bao trọn vòng đời tiền và quyền: cam kết pháp lý (hash PDF + receipt chữ ký), tiền ra (paymentReference/amount), tiền vào (core transaction/phân bổ), ai sở hữu khoản phải thu (chống tranh chấp chuyển nhượng — use case kinh điển của ledger). Tất toán là hệ quả của repayment cuối (đã được neo); cơ cấu/nợ xấu có lịch sử trong Fineract và chưa có artifact độc lập để kiểm. Hash toàn bộ `data` buộc chặt mọi field; canonical để tính lại được dù JSONB đổi thứ tự key |
| D53 | **Adapter Java**: `fabric-gateway 1.4.0` (đã quản lý ở root pom, grpc 1.59.0), danh tính `User1@org1` đọc từ file PEM theo env (`FABRIC_CERT_PATH/KEY_PATH/TLS_CERT_PATH`), thiếu file thì dừng khởi động; **đối chiếu trước khi ghi** (`GetProof` → nếu có dùng lại txId gốc); timeout evaluate 5s/endorse 15s/submit 5s/commit 60s; phân loại lỗi (mục 11); receipt `txId` 64 hex + `finora-channel/<block>` (qscc `GetBlockByTxID` khi dùng lại giao dịch cũ); **proofId UUID v5 theo mốc**; worker chỉ xử lý proof của provider đang chạy | Không nâng fabric-gateway để tránh sửa root pom dùng chung; 1.4 hỗ trợ đủ Gateway 2.4+. Reconcile-before-submit là cùng nguyên tắc R2 (tra theo external reference trước khi POST lại) |
| D54 | **API/vận hành**: `GET /api/v1/blockchain/proofs` (lọc `referenceType`=`LOAN_APPLICATION/LOAN_CONTRACT/DISBURSEMENT_SAGA/REPAYMENT/NOTE_ISSUANCE/NOTE_TRANSFER` + `referenceId`, hoặc `status`; `size` ≤ 100), `GET /{proofId}`, `POST /{proofId}/verify` (body tùy chọn `{"artifact": <data>}`), `POST /{proofId}/retry` (DEAD_LETTER → PENDING); chỉ `ROLE_ADMIN` qua JWT Keycloak (route Gateway đã có); worker đối chiếu opt-in (`BLOCKCHAIN_PROOF_RECONCILIATION_ENABLED`, launcher bật khi Fabric) lưu `MATCHED/MISMATCHED/MISSING_ON_CHAIN`; Kafka error handler retry lùi dần rồi `<topic>.DLT` | Explorer chỉ cho admin vì có ID khoản vay/receipt nội bộ; nhà đầu tư xem proof qua màn của service nguồn sau này. Verify nhận artifact từ người gọi vì Blockchain không được giữ payload tài chính |

## 4. Defect B50–B54

| ID | Mức | Vấn đề và bằng chứng | Sửa |
|---|---|---|---|
| B50 | P1 | `ProofSubmissionProcessor` gửi mọi proof đã claim cho ledger đang cấu hình, không xét `provider` ghi trên bản ghi: đổi `BLOCKCHAIN_PROOF_PROVIDER` giữa chừng thì proof Fabric nhận receipt `MOCK-…` (và ngược lại); DB không có ràng buộc định dạng receipt | Worker chỉ lấy/claim proof của provider đang chạy, processor kiểm provider + định dạng receipt, check constraint `ck_..._receipt_format` (V2); test `neverSubmitsAProofRegisteredForAnotherProvider`, `mockLookingReceiptIsNeverStoredOnAFabricProof`, IT `databaseRefusesMockReceiptOnFabricProof`, `workerIgnoresProofsRegisteredForAnotherProvider` |
| B51 | P1 | Hash REPAYMENT = SHA-256 của `data` compact theo thứ tự key nhận được. Outbox Payment lưu `payload_json JSONB` (V2 Payment), JSONB tự sắp lại key nên hash phụ thuộc cách lưu/serializer, không có quy tắc chuẩn hóa công bố; người kiểm toán dựng lại `data` từ nguồn khác ra hash khác | Scheme v2 canonical JSON; v1 giữ cho bản ghi cũ; test `legacySchemeKeepsTheReceivedFieldOrder`, `fieldOrderWhitespaceAndNullMembersDoNotChangeTheHash`, vector độc lập Python |
| B52 | P1 | Blockchain không cấu hình Kafka error handler: mặc định thử 10 lần liên tiếp không backoff rồi bỏ record (chỉ một dòng log). PostgreSQL Blockchain tạm lỗi lúc consume là mất yêu cầu proof, không có DLT để replay | `BlockchainKafkaConsumerConfiguration`: retry 1-2-4-8-16 giây rồi `<topic>.DLT`; contract/xung đột vào DLT ngay; test `BlockchainKafkaConsumerConfigurationTest` |
| B53 | P2 | proofId ngẫu nhiên và idempotency chỉ theo source event: cùng mốc được phát lại với eventId mới (replay từ DLT qua outbox mới, lỗi producer) tạo proof logic thứ hai; chain không có ràng buộc một proof/mốc | proofId UUID v5 theo mốc, unique index `uq_..._milestone` (v2), lookup theo mốc cho bản v1 cũ, chaincode index `aggregate~type`; IT `sameMilestoneRepublishedWithNewEventIdDoesNotCreateSecondLogicalProof`, `concurrentEventsOfTheSameMilestoneCreateOneProof`, `legacyRepaymentProofStaysTheOnlyProofWhenItsEventIsReplayed`, Go `TestSecondProofForTheSameMilestoneIsRejected` |
| B54 | P2 | `finora-common` kéo Spring Security vào Blockchain nhưng service không có resource server: mọi endpoint (kể cả `/api/v1/blockchain/health`) rơi vào user Basic sinh ngẫu nhiên của Spring Boot; explorer không gọi được bằng token Keycloak | `BlockchainSecurityConfig` (health public, proof `ROLE_ADMIN`, JWT + cookie như service khác); test `ProofControllerSecurityTest` |

## 5. Entity, value object và dữ liệu

`ProofSubmission` (entity, có lifecycle) — cột mới V2:

| Field | Java/PostgreSQL | Null/constraint | Ghi bởi | Đọc bởi/query |
|---|---|---|---|---|
| `source_event_type` | `String` / `VARCHAR(80)` | null với bản cũ | registrar | explorer |
| `source_event_version` | `Integer` / `INTEGER` | `> 0` hoặc null | registrar | explorer |
| `source_occurred_at` | `Instant` / `TIMESTAMPTZ` | null với bản cũ | registrar | explorer |
| `loan_application_id` | `Long` / `BIGINT` | `> 0` hoặc null | registrar | `idx_..._loan` |
| `chain_check_status` | enum / `VARCHAR(30)` | chỉ khi Fabric + CONFIRMED | verify/worker (bulk update) | explorer, `idx_..._chain_check` |
| `chain_checked_at` | `Instant` / `TIMESTAMPTZ` | đi cùng status | như trên | như trên |

Value object: `ProofSource` (service, eventId, type, version, occurredAt), `ProofSubject` (proofType, aggregate,
loanApplicationId), `ProofHashScheme` (v1/v2), `ProofReceiptFormat`, `ProofIdentity` (UUID v5). On-chain:
`ProofRecord` của chaincode = `LedgerProofRecord` phía Java. Không có snapshot payload hay projection mới.

## 6. ERD và lý do cardinality

```text
Event nguồn (Kafka/outbox của Loan, Payment, Investment)   ── logic, không FK
   1 ──────── 0..1 ProofSubmission         uq(source_service, source_event_id)
Mốc nghiệp vụ (proof_type, aggregate_type, aggregate_id)
   1 ──────── 1    ProofSubmission v2      uq_..._milestone (partial: payload_version >= 2)
              (0..N bản v1 cũ chỉ tồn tại với REPAYMENT trước W3D; service coi bản cũ nhất là proof của mốc)
Khoản vay (loan_application_id, logic)
   1 ──────── 0..N ProofSubmission         idx_..._loan (tối đa 4 loại mốc + N lần trả nợ/chuyển nhượng)
ProofSubmission (provider = HYPERLEDGER_FABRIC, CONFIRMED)
   1 ──────── 1    ProofRecord trên Fabric  khóa proofId (UUID v5 của mốc) — chaincode giữ write-once
Mốc trên chain (aggregate~type index)
   1 ──────── 1    ProofRecord              chaincode từ chối proofId thứ hai cho cùng mốc
```

| Quan hệ | Vì sao cần | Bảo vệ thật sự | Lifecycle |
|---|---|---|---|
| Event → proof `1:0..1` | Kafka giao lại không thêm dòng | unique `(source_service, source_event_id)` | Không xóa |
| Mốc → proof `1:1` | Cùng mốc phát lại không tạo proof thứ hai | unique partial index + proofId v5 + chaincode index | Không xóa |
| Khoản vay → proof `1:0..N` | Explorer theo khoản vay | Không FK (khác service) | Không xóa |
| Proof → bản ghi chain `1:1` | Đối chiếu DB ↔ chain | proofId là khóa chaincode | Write-once |

## 7. State machine và invariant

`PENDING → PROCESSING → CONFIRMED | PENDING (retry) | DEAD_LETTER`, `DEAD_LETTER → PENDING` chỉ bằng
`POST /retry` (đếm lại attempt, giữ lỗi cuối). Invariant mới: receipt đúng định dạng provider; `provider` bất biến; chỉ
worker của đúng provider claim; `chain_check_*` chỉ có trên proof Fabric đã CONFIRMED; chain không có sửa/xóa.

## 8. API/event/worker contract

**Consumer** `MilestoneProofConsumer` (group `blockchain-group`, listener tắt mặc định `BLOCKCHAIN_KAFKA_LISTENER_ENABLED`):

| Topic | Event | Field bắt buộc | Aggregate |
|---|---|---|---|
| `finora.loan.contract-activated` | `LoanContractActivated` v1 | `contractNumber`, `documentHash`, `pdfReceiptHash` | `LoanContract/<contractNumber>` |
| `finora.loan.disbursed` | `LoanDisbursed` v1 | `sagaId` (UUID), `contractNumber`, `paymentReference`, `amount` | `DisbursementSaga/<sagaId>` |
| `finora.payment.repayment-distributed` | `RepaymentDistributed` v1 | `repaymentId` | `Repayment/<repaymentId>` |
| `finora.investment.note-ownership-changed` | `NoteOwnershipChanged` v1 | `reason` ∈ {ISSUED, TRANSFERRED}, `notes[]`, `listingId`/`reference` | `NoteIssuance/<listingId>` hoặc `NoteTransfer/<reference>` |

Header `finora-event-id/type/version` nếu có phải khớp envelope. `loanApplicationId` lấy từ `data` nếu có.

**REST** (ADMIN): mục D54. Lỗi theo contract chung `{code, message, details, traceId}`: `PROOF_NOT_FOUND`,
`PROOF_ID_INVALID`, `PROOF_REFERENCE_INCOMPLETE`, `PROOF_REFERENCE_TYPE_INVALID`, `PROOF_REFERENCE_INVALID`,
`PROOF_STATUS_INVALID`, `PROOF_FILTER_UNSUPPORTED`, `PROOF_ARTIFACT_INVALID` (400), `PROOF_NOT_DEAD_LETTER` (409).
Verify trả `artifact.result` (`MATCHED/MISMATCHED` + hash tính lại) và `chain.result` (`MATCHED`, `MISMATCHED`,
`MISSING_ON_CHAIN`, `NOT_ON_CHAIN_MOCK`, `NOT_CONFIRMED`, `LEDGER_UNAVAILABLE`) kèm txId/recordedAt/MSP trên chain.

**Chaincode** `finora-ledger`: `RegisterProof(proofId, proofType, aggregateType, aggregateId, payloadHash,
payloadVersion, sourceService)`, `GetProof(proofId)`, `GetProofsByAggregate(aggregateType, aggregateId)`; lỗi có tiền tố
`PROOF_INVALID/PROOF_CONFLICT/PROOF_FORBIDDEN/PROOF_STATE_ERROR`.

**Worker**: `ProofSubmissionWorker` (`BLOCKCHAIN_PROOF_WORKER_ENABLED`), `ProofChainReconciliationWorker`
(`BLOCKCHAIN_PROOF_RECONCILIATION_ENABLED`, 10 phút/lượt, kiểm lại sau 24 giờ, batch 100).

## 9. Query, pagination, N+1 và index

| Use case | Query | Index | Ghi chú |
|---|---|---|---|
| Worker gửi | `provider = ? AND ((status=PENDING AND available_at<=now) OR (status=PROCESSING AND processing_started_at<=lease))` `ORDER BY id LIMIT 100` | `idx_..._due`, `idx_..._processing_lease` (V1) | provider có 2 giá trị nên lọc sau index partial là đủ |
| Explorer theo khoản vay | `loan_application_id = ?` `ORDER BY created_at, id` | `idx_..._loan` (partial NOT NULL) | Mỗi khoản vay vài chục dòng |
| Explorer theo aggregate | `aggregate_type = ? AND aggregate_id = ?` | `idx_..._aggregate` (V1) | |
| Màn DLT | `status = DEAD_LETTER` `ORDER BY updated_at DESC, id DESC` | `idx_..._dead_letter` (partial) | Trạng thái khác hiếm dùng → không index |
| Danh sách chung | `ORDER BY id DESC` | PK | |
| Worker đối chiếu | `status=CONFIRMED AND provider=FABRIC AND (chain_checked_at IS NULL OR < ?)` `ORDER BY chain_checked_at NULLS FIRST, id` | `idx_..._chain_check` (partial) | |
| Đăng ký | lookup source event, lookup mốc | `uq_..._source_event`, `uq_..._milestone` | |

Response map từ entity không có association nên không có N+1; `size` tối đa 100.

## 10. Transaction, concurrency và idempotency

```text
Consumer: Transaction 1 (DB Blockchain) = tìm theo event → tìm theo mốc → INSERT ... ON CONFLICT DO NOTHING
          → đọc lại bản thắng → so nội dung. Không gọi mạng.
Worker:   Transaction A = khóa dòng (FOR UPDATE), claim token + lease → commit
          Ngoài transaction = gọi Fabric (GetProof, RegisterProof, chờ commit)
          Transaction B = khóa dòng, kiểm claim token, lưu receipt hoặc lỗi + backoff
Verify:   đọc proof (read-only) → gọi Fabric ngoài transaction → bulk update chain_check_* (transaction ngắn)
```

| Đồng thời | Nguy cơ | Cơ chế | Kết quả |
|---|---|---|---|
| Hai consumer cùng event | Hai proof | `ON CONFLICT DO NOTHING` + đọc lại | Cùng proofId (IT) |
| Hai event cùng mốc | Hai proof logic | proofId v5 + unique mốc | Cùng proofId (IT) |
| Hai worker cùng proof | Gửi hai lần | Row lock ngắn + claim token + lease | Worker sau bỏ qua; nếu lease hết, worker mới GetProof trước |
| Hai lần ghi chain đua nhau | Hai bản ghi | Chaincode write-once + MVCC | Bản sau trả bản ghi gốc hoặc MVCC conflict → retry → reconcile |
| Verify và worker đối chiếu | Ghi đè kết quả | Bulk update chỉ hai cột kết quả, không đụng `@Version` | Kết quả mới nhất thắng (cùng giá trị) |

## 11. External failure, retry, reconciliation

| Lỗi Fabric | Mã | Retry? |
|---|---|---|
| Chaincode `PROOF_CONFLICT/INVALID/FORBIDDEN` | `FABRIC_PROOF_*` | Không → DEAD_LETTER |
| Bản ghi chain cùng proofId khác hash (phát hiện ở GetProof) | `FABRIC_PROOF_CONFLICT` | Không |
| `UNAVAILABLE` | `FABRIC_UNAVAILABLE` | Có |
| `DEADLINE_EXCEEDED` | `FABRIC_TIMEOUT` | Có |
| Không đủ endorsement (peer Org2 tắt), chaincode chưa deploy, lỗi khác | `FABRIC_<OPERATION>_FAILED` | Có |
| `PERMISSION_DENIED/UNAUTHENTICATED` | `FABRIC_IDENTITY_REJECTED` | Không |
| `INVALID_ARGUMENT` | `FABRIC_REQUEST_REJECTED` | Không |
| Chờ commit timeout (đã gửi orderer) | `FABRIC_COMMIT_STATUS_UNKNOWN` | Có, lần sau GetProof |
| Commit `MVCC_READ_CONFLICT/PHANTOM_READ_CONFLICT/DUPLICATE_TXID` | `FABRIC_TX_CONFLICT` | Có |
| Commit invalid khác (ví dụ `ENDORSEMENT_POLICY_FAILURE`) | `FABRIC_TX_INVALID` | Không |
| Phản hồi chaincode sai contract | `FABRIC_RESPONSE_INVALID` | Không |

Retry: backoff 5 giây nhân đôi tới 5 phút, tối đa 10 lần (cấu hình hiện có), rồi DEAD_LETTER; vận hành sửa nguyên nhân
và gọi `POST /retry`. Reconciliation: verify và worker đối chiếu; sai lệch chỉ ghi kết quả + log ERROR (F08 bước 4).
Compensation: không có — proof là phụ trợ, không bao giờ rollback giao dịch của service nguồn.

## 12. Security, audit, logging, PII và pháp lý

- On-chain: proofId, loại proof, mã aggregate (regex mã nghiệp vụ), SHA-256, version, service nguồn, txId, thời điểm,
  MSP người gửi. Không có số tiền, investorId, tên, CCCD, PDF. DB Blockchain cũng không lưu `data`.
- Danh tính Fabric: khóa riêng chỉ nằm trong `docker/fabric-network/crypto-config/` (git-ignore, sinh bằng cryptogen);
  Java chỉ nhận đường dẫn; log không in nội dung khóa; thông điệp lỗi không chứa khóa.
- Explorer `ROLE_ADMIN`; receipt MOCK luôn có `kind=MOCK_NOT_ON_CHAIN`, `onChain=false` và câu cảnh báo (LEGAL-DATA-01).
- `LEGAL-CONTRACT-01`: proof hash là bằng chứng toàn vẹn kỹ thuật cho PDF/receipt đã ký (`documentHash`,
  `pdfReceiptHash`), không biến click-wrap thành chữ ký số và không thay legal review. Trạng thái trình bày proof như bằng
  chứng pháp lý: `NEEDS_LEGAL_REVIEW` (kiểm 2026-10-17; căn cứ đối chiếu `LAW-ELECTRONIC-2023` Điều 8–11 về toàn vẹn
  thông điệp dữ liệu đã ghi trong sổ pháp lý). Mạng local một máy không phải "đơn vị kiểm toán độc lập" thật.
- Peer↔chaincode không TLS (chỉ trong mạng Docker nội bộ, cổng 9999 không mở ra host) — giới hạn demo.

## 13. Mạng Fabric: topology, port, identity, RAM

Chi tiết vận hành: [`docker/fabric-network/README.md`](../../docker/fabric-network/README.md). Registry: `05-registry`.

RAM đo bằng `docker stats` ngày 2026-10-17 (Docker Desktop 7,6 GiB, 13 container FINORA đang chạy dùng ~3,0 GiB):

| Container | Khi vừa dựng | Sau 6 giao dịch + drill | Giới hạn |
|---|---:|---:|---:|
| `finora-fabric-orderer` | 53,2 MiB | 51,4 MiB | 256 MiB |
| `finora-fabric-peer0-org1` | 64,0 MiB | 74,3 MiB | 384 MiB |
| `finora-fabric-peer0-org2` | 57,8 MiB | 58,2 MiB (sau restart) | 384 MiB |
| `finora-fabric-ccaas-org1` | 7,0 MiB | 9,1 MiB | 64 MiB |
| `finora-fabric-ccaas-org2` | 9,0 MiB | 9,1 MiB | 64 MiB |
| **Tổng** | **~191 MiB** | **~202 MiB** | 1,15 GiB |

Image: peer 155 MB, orderer 121 MB, tools 610 MB (chỉ chạy một lần khi dựng), chaincode 20,5 MB. Launcher tăng heap
blockchain 192 → 256 MiB khi `-WithFabric` (gRPC/Netty). Host lúc đo còn ~1,8 GiB RAM trống; Fabric chạy trong Docker
VM nên không lấy thêm RAM host ngoài JVM blockchain.

## 14. Migration, file và rollout

- Migration `V2__fabric_proof_milestones.sql`: cột mới, 5 check constraint, unique partial index mốc, 3 index. Tương
  thích dữ liệu cũ (bản ghi MOCK có receipt đúng định dạng; không có bản v2 trùng mốc).
- Rollout: mặc định không đổi gì (MOCK). Bật Fabric: `fabric-network.ps1 -Action Up` rồi provider + đường dẫn danh
  tính, hoặc launcher `-WithFabric`. Proof đăng ký khi đang MOCK vẫn là MOCK (không gửi lại lên Fabric).
- Rollback: tắt provider về MOCK; proof Fabric đang PENDING chờ tới khi Fabric bật lại, không bị gán receipt MOCK.

## 15. Test plan, acceptance và kết quả

| Nhóm | Test | Kết quả 2026-10-17 |
|---|---|---|
| Chaincode Go (Docker build và `go test`) | 10 test: ghi mới, gửi lại idempotent (không ghi thêm), hash khác → conflict, mốc thứ hai → conflict, ACL, 9 input sai/PII, GetProof rỗng, theo aggregate, writer mặc định | `ok finora.local/ledger` (`go vet` sạch) |
| Unit Java | 49 test (hash vector Python, UUID v5, receipt format, entity, processor B50, Fabric ledger reconcile/conflict/race, phân loại lỗi gateway, registrar 4 mốc, security B54, Kafka B52, properties) | 49/49 pass |
| IT PostgreSQL 17.5 + MOCK | 11 test `FinoraBlockchainApplicationIT` (migration V2, idempotency hai lớp + đồng thời, v1 cũ, constraint receipt, provider filter, explorer/verify/retry HTTP) | 11/11 pass |
| IT Fabric thật (opt-in) | `FabricProofFlowLiveIT`: 4 mốc → CONFIRMED trên chain → verify artifact + chain MATCHED; gửi lại → cùng tx; hash giả → conflict; sửa DB → MISMATCHED | 1/1 pass (52 giây) |
| Drill Fabric thật (opt-in) | `FabricOutageDrillLiveIT`: tắt peer0.org2 → PENDING retryable; bật lại → CONFIRMED một lần, MATCHED | 1/1 pass (33 giây) |

Lệnh: `mvn -B -ntp -pl finora-blockchain -am verify` (common 52 + blockchain 49 unit + 11 IT, live IT skipped);
`FINORA_FABRIC_IT=true …` và `FINORA_FABRIC_OUTAGE_DRILL=true … -Dit.test=FabricOutageDrillLiveIT` khi mạng đang chạy.

## 16. Bằng chứng chạy thật trên Fabric (2026-10-17, project Compose `finora-fabric-verify`)

```text
fabric-network.ps1 -Action Up -ProjectName finora-fabric-verify
  [fabric] package ID: finora-ledger_1.0:5e05a9924eed88fd8395626bba1eca43cf144d8a8f0a05638acda84cf469598f
  [fabric] orderer join finora-channel; peer0.org1/org2 join finora-channel
  [fabric] cài package trên peer0.org1/org2; Org1MSP approve; Org2MSP approve; commit (cả hai tổ chức endorse)
  [fabric] peer0.org1: chaincode finora-ledger trả lời GetProof; peer0.org2: ... trả lời GetProof
  [OK] Fabric sẵn sàng: channel finora-channel, chaincode finora-ledger, gateway localhost:7051 (MSP Org1MSP)
(chạy lại Up: mọi bước báo "đã có", idempotent)

FabricProofFlowLiveIT
  CONTRACT_SIGNED        proofId=212ebdfe-c4d2-5847-9cc3-8edc60706226 LoanContract/LC-IT-1792236138391
                         tx=ffeeb8d02773781ab018a9307efae5d288d642b204faedc014489b0e97599b48 block=finora-channel/4
  LOAN_DISBURSED         proofId=0333d5a7-fac8-5762-a5e1-60f55a99aceb DisbursementSaga/ebd8abe6-67d7-4180-86b6-c8b1b1f617f5
                         tx=4d6cba37ea7cbfa95b4315e7f79015f1f19d130695e2ad61c9da11d4b7a87702 block=finora-channel/5
  REPAYMENT_DISTRIBUTED  proofId=a6a97b0f-004b-5bfb-938d-b7b1471fad1a Repayment/2b74b512-6311-41d1-8f40-5c900601ea99
                         tx=46911afa0cefe48ca1afadfe1d7bf4146984aa0bb159c42b3fee421d9f71eb71 block=finora-channel/6
  NOTE_OWNERSHIP_CHANGED proofId=782f192c-2f1e-5e54-b9ae-2014794e9363 NoteIssuance/900138391
                         tx=53ac5c83337a3f3e6877ba17c0be14fdfb7417ff7041c216b34912d58276c5e2 block=finora-channel/7
  verify mỗi proof: artifact MATCHED, chain MATCHED, submitterMspId=Org1MSP
  gửi lại proof hợp đồng -> cùng tx ffeeb8d0...9b48; hash giả cùng proofId -> FABRIC_PROOF_CONFLICT
  tamper-check: dbHash=111...1, chainHash=4a56b399...d04e -> MISMATCHED

fabric-network.ps1 -Action Proof -Org org2 -ProofId 212ebdfe-c4d2-5847-9cc3-8edc60706226   (đọc từ peer kiểm toán)
  [fabric] peer0.org2 (Org2MSP), chiều cao chain finora-channel: 8
  [fabric] {"proofId":"212ebdfe-…","proofType":"CONTRACT_DOCUMENT","aggregateType":"LoanContract",
            "aggregateId":"LC-IT-1792236138391","payloadHash":"db6c7a9348ef98db07faac47d26a33da6323bbef9c26cfa386fe5e86043011c0",
            "payloadVersion":2,"sourceService":"finora-loan","txId":"ffeeb8d0…9b48",
            "recordedAt":"2026-10-17T11:22:20.732937Z","submitterMspId":"Org1MSP"}

FabricOutageDrillLiveIT (docker stop finora-fabric-peer0-org2)
  khi peer0.org2 tắt: status=PENDING attempt=1 error=FABRIC_ENDORSE_FAILED detail=ABORTED failed to collect enough
    transaction endorsements | Org2MSP@peer0.org2.finora.local:9051: ... Unavailable
  sau khi bật lại: status=CONFIRMED attempt=4 tx=79851e02318e4c9349da46f9a5d7b23f649a1a2f476491b4d4d6812309da895a
    block=finora-channel/8 chainCheck=MATCHED
```

Block 0 là genesis, 1–3 là approve/commit chaincode, 4–8 là proof. Mạng kiểm chứng đã được dừng và xóa (volume, crypto,
build) sau khi chạy để trả RAM.

## 17. Bản đồ code thực tế

```text
Kafka 4 topic → MilestoneProofConsumer (messaging) → MilestoneProofRegistrar.register
   → ProofMilestone.aggregate (field bắt buộc + aggregate) → ProofPayloadHasher.hash (CANONICAL_JSON_V2)
   → ProofSubmissionService.register [@Transactional]: findExisting → ProofSubmissionInsertImpl.insertIfAbsent
     (JDBC, ON CONFLICT DO NOTHING) → findExisting → requireCompatible
ProofSubmissionWorker (@Scheduled) → ProofSubmissionStateService.dueIds (lọc provider)
   → ProofSubmissionProcessor.process: claim [tx] → ProofLedger.submit (ngoài tx) → kiểm ProofReceiptFormat
     → markConfirmed/markFailed [tx]
   FabricProofLedger.submit: read(GetProof) → (có) requireSameContent + blockNumberOf(qscc) | (chưa) client.submit
     GatewayFabricLedgerClient: newProposal.endorse().submitAsync() → getStatus() → classify lỗi
   FabricGatewayConfiguration: ManagedChannel TLS + Gateway (identity PEM, timeout) khi provider=HYPERLEDGER_FABRIC
ProofController /api/v1/blockchain/proofs → ProofQueryService (search/find) | ProofVerificationService.verify
   (checkArtifact + checkChain → ProofSubmissionStateService.recordChainCheck [tx]) | ProofSubmissionService.requeue
ProofChainReconciliationWorker (@Scheduled, opt-in) → chainCheckDueIds → ProofVerificationService.reconcile
BlockchainKafkaConsumerConfiguration (DLT), BlockchainSecurityConfig (ADMIN), BlockchainProofConfiguration (chọn ledger)
Chaincode: main.go (CCaaS server, routing) → ledger.go registerProof/getProof/getProofsByAggregate
Mạng: docker-compose.yml profile fabric/fabric-tools → fabric-network.ps1 → scripts/network.sh
```

## 18. Sai khác, giới hạn và việc tiếp theo

- **Khác dự kiến:** chèn idempotent dùng JDBC (`ProofSubmissionInsertImpl`) thay native query JPA để tham số null có
  kiểu SQL tường minh; `RepaymentDistributedProofConsumer` gộp vào `MilestoneProofConsumer`; thêm action `Proof` cho tổ
  chức kiểm toán đọc thẳng chain.
- **Giới hạn:** cryptogen + một máy (không phải hai tổ chức thật); Raft một node; peer↔chaincode không TLS; chưa test
  Kafka thật trong IT của module (consumer được test ở mức registrar; E2E harness sẽ chạy qua broker); verify artifact
  cần người gọi tự lấy `data` của event; chưa có trang web explorer; proof servicing chưa có; hash scheme v2 chỉ nhận số
  nguyên (đúng contract hiện tại, event tương lai có số thực sẽ vào DLT thay vì neo sai).
- **Việc của main agent:** xem báo cáo W3D (launcher/E2E check, trang explorer web, cập nhật master plan R6 + D50–D54 +
  B50–B54, runbook E2E `-WithFabric`).
