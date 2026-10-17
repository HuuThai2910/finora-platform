# Mạng Hyperledger Fabric local của FINORA (R6, W3D)

Mạng này cho `finora-blockchain` ghi bằng chứng hash của bốn mốc nghiệp vụ lên sổ cái thật thay cho ledger `MOCK`.
Mặc định FINORA vẫn dùng `MOCK`; chỉ bật Fabric khi chạy launcher E2E với `-WithFabric` hoặc tự cấu hình
`BLOCKCHAIN_PROOF_PROVIDER=HYPERLEDGER_FABRIC`. Thiết kế, quyết định D50–D54 và bằng chứng chạy thật nằm trong
[`W3D-fabric-proofs.md`](../../.agents/plans/W3D-fabric-proofs.md).

## 1. Thành phần

| Container | Vai trò | Cổng | RAM đo được (W3D) |
|---|---|---|---:|
| `finora-fabric-orderer` | Orderer Raft một node, `OrdererMSP`, channel participation API (không có system channel) | nội bộ 7050, admin 7053 | ~53 MiB |
| `finora-fabric-peer0-org1` | Peer của **Org1MSP = FINORA** (ứng dụng ghi proof bằng `User1@org1`), LevelDB | **127.0.0.1:7051** (Gateway) | ~64–68 MiB |
| `finora-fabric-peer0-org2` | Peer của **Org2MSP = đơn vị kiểm toán độc lập**, chỉ đọc nhưng phải endorse mọi lần ghi | nội bộ 9051 | ~58–62 MiB |
| `finora-fabric-ccaas-org1/org2` | Chaincode `finora-ledger` (Go) dạng chaincode-as-a-service, mỗi tổ chức chạy bản của mình | nội bộ 9999 | ~7–9 MiB mỗi container |

Tổng khoảng 190 MiB khi chạy; giới hạn cứng trong Compose 1,1 GiB (`mem_limit`). Image pin
`ghcr.io/hyperledger/fabric-*:2.5.16` (LTS, phát hành 2026-06-17), Go `1.26.7-alpine3.24`, runtime `alpine:3.24.1`.

Channel `finora-channel`, chaincode `finora-ledger` version `1.0` sequence `1`, endorsement policy mặc định
`MAJORITY Endorsement` của channel — với hai tổ chức nghĩa là **cả FINORA và đơn vị kiểm toán phải ký** từng giao
dịch, nên FINORA không thể tự viết lại lịch sử. Chaincode chỉ cho MSP trong `FINORA_PROOF_WRITER_MSPIDS` (mặc định
`Org1MSP`) gọi `RegisterProof`.

## 2. Dựng, kiểm tra, dừng

```powershell
# Sinh crypto (cryptogen) + genesis block + package CCaaS nếu chưa có, bật container, join channel,
# install/approve (hai tổ chức)/commit chaincode, smoke đọc qua từng peer. Chạy lại an toàn (idempotent).
powershell -ExecutionPolicy Bypass -File docker/fabric-network/fabric-network.ps1 -Action Up

powershell -ExecutionPolicy Bypass -File docker/fabric-network/fabric-network.ps1 -Action Status

# Dừng và xóa container, giữ ledger (volume) và crypto:
powershell -ExecutionPolicy Bypass -File docker/fabric-network/fabric-network.ps1 -Action Down
# Xóa hẳn ledger, crypto và build để lần Up sau là mạng mới:
powershell -ExecutionPolicy Bypass -File docker/fabric-network/fabric-network.ps1 -Action Down -RemoveData
```

`Up` kiểm RAM Docker còn trống trước (mặc định cần 768 MiB, D14); `-SkipMemoryCheck` bỏ qua nếu chấp nhận rủi ro.
`-ProjectName <tên>` chạy dưới project Compose riêng (W3D dùng `finora-fabric-verify` để không đụng stack đang chạy).

Sau `Up`, `build/client.json` ghi endpoint và **đường dẫn** file danh tính cho `finora-blockchain` (không chép nội
dung khóa). Khi chạy service từ IntelliJ, đặt các biến tương ứng:

```text
BLOCKCHAIN_PROOF_PROVIDER=HYPERLEDGER_FABRIC
BLOCKCHAIN_PROOF_WORKER_ENABLED=true
FABRIC_PEER_ENDPOINT=localhost:7051
FABRIC_CERT_PATH=<certPath trong build/client.json>
FABRIC_KEY_PATH=<keyPath trong build/client.json>
FABRIC_TLS_CERT_PATH=<tlsCertPath trong build/client.json>
```

Launcher E2E làm việc này tự động: `docker/e2e/start-local-services.ps1 -Action Start -WithFabric`.

## 3. File trong thư mục

| Đường dẫn | Commit? | Nội dung |
|---|---|---|
| `config/crypto-config.yaml` | Có | Đặc tả cryptogen: 1 orderer org, Org1/Org2 mỗi org 1 peer + Admin + User1, SAN `localhost` |
| `config/configtx.yaml` | Có | Profile `FinoraChannel`: Raft, policy, anchor peer trong genesis block |
| `scripts/network.sh` | Có | `generate`/`channel`/`deploy`/`smoke`, chạy trong container `fabric-tools` |
| `chaincode/finora-ledger/` | Có | Chaincode Go (shim thuần), unit test chạy trong Docker build |
| `fabric-network.ps1` | Có | Wrapper Windows: kiểm RAM, gọi Compose profile `fabric`/`fabric-tools`, ghi `build/client.json` |
| `crypto-config/` | **Không** (git-ignore) | Khóa riêng, chứng thư MSP/TLS do cryptogen sinh |
| `build/` | **Không** (git-ignore) | Genesis block, package CCaaS + package ID, `core.yaml` của peer, `client.json` |

`core.yaml` của peer được sinh từ đúng file trong image `fabric-tools:2.5.16`, chỉ bỏ `vm.endpoint` để peer không
đăng ký health check Docker (không mount `docker.sock`); builder `ccaas_builder` có sẵn trong image peer.

## 4. Chaincode `finora-ledger`

| Hàm | Loại | Ý nghĩa |
|---|---|---|
| `RegisterProof(proofId, proofType, aggregateType, aggregateId, payloadHash, payloadVersion, sourceService)` | submit | Ghi một lần. Cùng proofId + cùng nội dung → trả bản ghi cũ (kèm `txId` lần ghi đầu), không ghi thêm. Cùng proofId khác nội dung hoặc mốc đã có proofId khác → `PROOF_CONFLICT`. Sai định dạng → `PROOF_INVALID`; MSP không được ghi → `PROOF_FORBIDDEN` |
| `GetProof(proofId)` | evaluate | JSON bản ghi; payload rỗng nếu không có |
| `GetProofsByAggregate(aggregateType, aggregateId)` | evaluate | Mọi proof của một aggregate (composite key, chạy trên LevelDB) |

Bản ghi chỉ có `proofId, proofType, aggregateType, aggregateId, payloadHash, payloadVersion, sourceService, txId,
recordedAt, submitterMspId`. `aggregateId` chỉ nhận ký tự của mã nghiệp vụ (`[A-Za-z0-9._:-]`), không có khoảng trắng
hay chữ có dấu, nên không thể chèn họ tên/địa chỉ (LEGAL-DATA-01). Không có hàm sửa/xóa.

## 5. Tự tính lại hash từ artifact nguồn

`payloadHash` (scheme `CANONICAL_JSON_V2`, `payloadVersion=2`) là SHA-256 của object `data` của event nguồn sau khi
chuẩn hóa: bỏ member có giá trị `null`, sắp key theo thứ tự UTF-16, không khoảng trắng, chuỗi escape theo RFC 8785,
chỉ chấp nhận số nguyên (tiền luôn là chuỗi decimal). Với dữ liệu không có ký tự điều khiển, có thể kiểm bằng `jq`:

```bash
jq -cSj '.data | walk(if type == "object" then with_entries(select(.value != null)) else . end)' event.json | sha256sum
```

Proof `REPAYMENT` ghi trước W3D (`payloadVersion=1`, `LEGACY_COMPACT_V1`) là SHA-256 của `data` compact giữ nguyên
thứ tự field đã nhận; API `POST /api/v1/blockchain/proofs/{proofId}/verify` vẫn kiểm được loại này.

## 6. Sự cố thường gặp

- **`Docker chỉ còn ~N MiB trống`**: tắt bớt container/JVM, hoặc `-SkipMemoryCheck`.
- **Peer không healthy**: `docker logs finora-fabric-peer0-org1`; thường do chưa chạy `generate` (thiếu crypto).
- **`chaincode ... not found` khi gọi**: container `finora-fabric-ccaas-*` chưa chạy hoặc `build/chaincode/package-id`
  khác package đã install (đã xóa `build/` nhưng giữ volume). Chạy `Down -RemoveData` rồi `Up`.
- **Proof `PENDING` mãi với `FABRIC_UNAVAILABLE`**: mạng đang tắt; proof tự retry có backoff tới `max-attempts` rồi vào
  `DEAD_LETTER`. Bật lại mạng rồi `POST /api/v1/blockchain/proofs/{proofId}/retry`. Tiền/hợp đồng ở service nguồn
  không bị ảnh hưởng.
