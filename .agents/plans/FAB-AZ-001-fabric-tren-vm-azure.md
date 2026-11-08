---
task_id: FAB-AZ-001
title: Chạy mạng Hyperledger Fabric của W3D trên VM Azure, finora-blockchain ghi proof lên Fabric thay cho MOCK
owner: Hai
status: IN_PROGRESS
depends_on: [W3D, CD-001]
created_at: 2026-11-08
updated_at: 2026-11-08
approved_by: Hai
approved_at: 2026-11-08
---

# FAB-AZ-001 — Fabric trên VM Azure

Trước plan này VM chạy `BLOCKCHAIN_PROOF_PROVIDER=MOCK`: proof chỉ nằm trong `blockchain-db`, receipt có tiền tố MOCK.
Mạng Fabric thật (W3D, D50–D54) chỉ dựng được ở máy dev bằng `fabric-network.ps1`. Plan đưa đúng mạng đó lên VM qua
CD-001; không đổi chaincode, adapter Java, API hay migration.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

1. **Bằng chứng trên bản demo là bằng chứng thật.** Bốn mốc (hợp đồng kích hoạt, giải ngân, phân bổ trả nợ, chuyển
   nhượng Note) và hash giấy chuyển nhượng được ghi lên sổ cái Fabric có hai tổ chức ký, không còn là MOCK.
2. **Không ai tự viết lại lịch sử.** Endorsement policy mặc định `MAJORITY` với hai tổ chức nghĩa là cả FINORA (Org1)
   lẫn đơn vị kiểm toán (Org2) phải ký mỗi lần ghi.
3. **Không thêm việc tay.** Lần deploy đầu có Fabric tự sinh crypto và dựng channel; các lần sau CD chỉ thay container.

### Ai tham gia và nhìn thấy gì?

| Người | Thấy gì |
|---|---|
| Quản trị viên | Trang proof/explorer của web: proof mới có receipt Fabric (txId, block), nút kiểm toàn vẹn đọc lại từ Fabric |
| Người vay/nhà đầu tư | Không đổi: proof chạy nền, Fabric tắt chỉ làm proof chờ retry |
| Hải (vận hành) | `sudo finora-compose ps` có thêm 5 container `fabric-*`; `_status.json` tính cả 5 container |

### Vì sao giữ 2 peer thay vì 1

Hải đề nghị thử bản gọn "1 peer, 1 orderer" để tiết kiệm RAM. Đo ở W3D cả mạng (1 orderer + 2 peer + 2 chaincode)
chỉ khoảng 190 MiB, peer thứ hai khoảng 60 MiB. Bỏ peer Org2 thì phải viết lại `configtx.yaml`/`crypto-config.yaml`
và mất tính chất ở mục 2 (FINORA tự endorse một mình), trong khi chỉ tiết kiệm ~60 MiB. Plan giữ nguyên topology W3D.

### Luồng chính và không thành công

```
CD (push hoặc chạy tay) ─► plan: image finora-fabric-ccaas chưa có ─► build ─► cd-deploy.sh
  1. fabric-tools generate (một lần) → /opt/finora/fabric/{crypto-config,build}; chép danh tính cho uid 10001
  2. pull chaincode, up -d (orderer, 2 peer, 2 chaincode; blockchain tạo lại vì đổi biến môi trường)
  3. đợi cả cụm healthy
  4. fabric-tools all (một lần): join channel, install/approve/commit, smoke đọc qua 2 peer → build/.network-ready
```

Hỏng ở bước nào thì `cd-deploy.sh` quay về bản trước: compose cũ không có Fabric nên `--remove-orphans` gỡ 5 container,
blockchain về MOCK. Crypto và volume ledger giữ lại cho lần sau.

## 1. Thay đổi

| File | Nội dung |
|---|---|
| `deploy/docker-compose.azure.yml` | Thêm `fabric-orderer`, `fabric-peer0-org1/org2`, `fabric-ccaas-org1/org2` (như `docker/docker-compose.yml` profile `fabric`, bỏ cổng host, alias trên mạng `finora`, log syslog), `fabric-tools` (profile `fabric-tools`, mount `config`/`scripts` của bản deploy và `/opt/finora/fabric`); 3 volume ledger; blockchain đổi sang `HYPERLEDGER_FABRIC`, bật reconciliation, mount `/opt/finora/fabric/client` |
| `deploy/cd-deploy.sh` | Service CD `fabric-ccaas` (biến `FABRIC_CCAAS_TAG`, một image hai container); tải thêm `docker/fabric-network/{config,scripts}`; `prepare_fabric`, `setup_fabric_network` |
| `deploy/host/finora-compose.sh` | Biến `FINORA_FABRIC_DIR` |
| `deploy/host/finora-status.sh` | 5 container Fabric trong danh sách `ready` |
| `.github/workflows/cd.yml` | Service `fabric-ccaas`: build từ `docker/fabric-network/chaincode/finora-ledger` khi thư mục đó đổi; đổi `config`/`scripts` của Fabric cũng kích hoạt deploy |
| `deploy/README.md` | Mục "Fabric trên VM" |

## 2. Bảo mật

- cryptogen sinh khóa offline (D50): chỉ hợp với demo; production phải dùng Fabric CA/HSM.
- `/opt/finora/fabric` quyền 700 root; bản danh tính User1@org1 cho blockchain ở `client/` chủ uid 10001, khóa 400.
- Không cổng Fabric nào mở ra ngoài VM (NSG chỉ 80/443 và 22 cho máy dev).
- `aggregateId` chỉ nhận mã nghiệp vụ, không có dữ liệu cá nhân (LEGAL-DATA-01 của W3D).

## 3. Test plan

| # | Cách kiểm | Kỳ vọng |
|---|---|---|
| T1 | Logic `plan` của CD với GHCR giả | Chỉ build `fabric-ccaas`, gắn tag 9 image còn lại, deploy `fabric-ccaas` |
| T2 | `cd-deploy.sh` với VM giả: bước channel lỗi | `rolled-back`, `images.env` không có `FABRIC_CCAAS_TAG`, `current` về bản trước |
| T3 | Như T2 nhưng thành công | `ok`, `.network-ready`, chỉ pull 2 container chaincode |
| T4 | Deploy service khác sau đó | Không chạy lại `fabric-tools` |
| T5 | Trên VM: `docker stats`, `free -m` | Fabric < 300 MiB, VM còn RAM trống |
| T6 | Trên VM: một proof mới | Receipt Fabric, `verify` đọc lại khớp, `fabric-tools proof org2 <id>` thấy bản ghi |

## 4. Quyết định

| Mã | Nội dung |
|---|---|
| D89 | VM Azure chạy mạng Fabric W3D nguyên trạng (1 orderer, 2 peer hai tổ chức, 2 chaincode CCaaS) và blockchain ghi `HYPERLEDGER_FABRIC`; crypto sinh một lần trên VM ở `/opt/finora/fabric`, chaincode image build qua CD (`finora-fabric-ccaas`). Không rút còn 1 peer vì chỉ tiết kiệm ~60 MiB mà mất endorse của tổ chức kiểm toán |

## 5. Bản đồ code thực tế

Code 08/11, chưa commit, chưa chạy trên VM.

## 6. Bằng chứng kiểm thử (08/11, Git Bash, stub)

- T1: `build=["fabric-ccaas"]`, `retag` 9 image, `deploy_services=fabric-ccaas`.
- T2: `rolled-back`, `images.env` về 9 tag, `current` về `5758ad6`.
- T3: `ok`, `build/.network-ready`, `pull fabric-ccaas-org1 fabric-ccaas-org2`, `fabric-tools all` chạy một lần.
- T4: deploy `loan` sau đó không gọi `fabric-tools run`.
- Compose đọc được bằng PyYAML; khối peer so với `docker/docker-compose.yml` chỉ khác đường dẫn crypto, cổng host,
  mạng.
- Chưa kiểm thật: T5, T6 (cần push và chạy CD).
