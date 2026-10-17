---
task_id: W3C
title: Hợp đồng nhiều bên ký PAdES-B-B nhúng vào PDF, ký nối tiếp bên cho vay trước người vay sau, trang biên nhận chữ ký
owner: Thai
status: READY_FOR_REVIEW
depends_on: [LN-008, LN-010, LN-018]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
---

# W3C — Chữ ký PDF nhiều bên (R5, PAdES-B-B)

Workstream R5 của [`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 10, theo quyết định D13 (PAdES-B-B
với VNPT SmartCA UAT) và D19/Q9 (PAdES ẩn, ký nối tiếp nhà đầu tư trước người vay cuối, trang biên nhận). Agent tự chọn
phương án cho các câu hỏi kỹ thuật còn lại theo D23; quyết định mới mang số D45–D49, lỗi phát hiện mang số B45–B49.
Kiểm soát pháp lý: [`LEGAL-CONTRACT-01`](../../docs/LEGAL-COMPLIANCE.md#legal-contract-01--hợp-đồng-và-xác-nhận-điện-tử),
[`LEGAL-DATA-01`](../../docs/LEGAL-COMPLIANCE.md#legal-data-01--dữ-liệu-hồ-sơ-và-cccd). Chỉ sửa `finora-loan`; không đổi
event liên service, không đổi Gateway, web, mobile hay harness E2E.

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Trước W3C, "đã ký" chỉ được chứng minh bằng hash và một receipt FINORA tự lập: tệp PDF các bên tải về không chứa chữ ký
nào. SmartCA ký SHA-256 của cả tệp, FINORA băm giá trị chữ ký rồi bỏ đi, nên không ai kiểm được chữ ký từ chính tệp hợp
đồng (B45). Sau W3C, mỗi bên ký số **vào chính tệp PDF**:

1. Hợp đồng nhiều bên mới được lập với bản PDF có trang xác nhận mô tả cách ký số (phiên bản `CONTRACT_PDF_V*_PADES`).
2. Từng nhà đầu tư ký: FINORA nối thêm vào cuối tệp một trường chữ ký ẩn (incremental update), dựng thuộc tính ký PAdES,
   gửi provider ký đúng một hash, rồi nhúng chữ ký CMS vào chỗ trống. Bytes của bản trước không đổi một byte nào.
3. Người vay chỉ ký sau khi đủ chữ ký bên cho vay, trên bản đã có các chữ ký đó.
4. Sau chữ ký cuối, FINORA công bố bản ký đủ (`SIGNED_PDF`) và lập **trang biên nhận chữ ký số** riêng liệt kê từng chữ
   ký: thứ tự, vai trò, mã bên ký, thời điểm, chứng thư (CN, serial, tổ chức cấp, SHA-256), SHA-256 của phiên bản đã ký.
5. Hợp đồng chỉ chuyển `EFFECTIVE` khi mọi bên bắt buộc đã ký (giữ nguyên quy tắc LN-010).

Mở tệp đã ký bằng trình đọc PDF có bảng chữ ký (Adobe Acrobat Reader, Foxit, công cụ EU DSS) sẽ thấy N chữ ký, mỗi chữ
ký báo toàn vẹn của phiên bản nó đã ký. Chứng thư demo/UAT không nằm trong danh sách tin cậy nên trình đọc báo chưa xác
minh được danh tính: điều này đúng với bản chất thử nghiệm.

### Ai tham gia và nhìn thấy gì?

| Người dùng | Thấy gì | Thay đổi so với trước |
|---|---|---|
| Nhà đầu tư | Mở `/document` là SIGNABLE như cũ, đồng ý và ký như cũ | Response có thêm `pdfSignatureCount`, `signedPdfDocumentHash`, `receiptDocumentHash`; sau khi hợp đồng hiệu lực `/document` là bản PAdES ký đủ |
| Người vay | Như cũ; chỉ được ký sau khi đủ bên cho vay | Detail có thêm `signatureProfile`, `signedPdfDocument`, `receiptDocument` |
| Cả hai | Có thể tải bản đang ký dở (`/signed-document`) và trang biên nhận (`/receipt`) | Hai endpoint mới, chỉ chủ hợp đồng/bên cho vay được đọc |
| Vận hành | Worker tự thu chữ ký SmartCA và đóng phiên quá hạn | Không cần người dùng bấm "Kiểm tra kết quả ký" mới ghi nhận |

### Luồng chính và luồng không thành công

```text
Contract PENDING_LENDER_SIGNATURES (SIGNABLE = bản 0)
  lender A ký → bản 1 = bản 0 + chữ ký A        (chỉ một phiên ký chờ trên mỗi hợp đồng)
  lender B ký → bản 2 = bản 1 + chữ ký B
→ PENDING_BORROWER_SIGNATURE
  borrower ký → bản 3 = bản 2 + chữ ký borrower
→ SIGNED → EFFECTIVE + SIGNED_PDF (= bản 3) + SIGNED_RECEIPT (trang biên nhận tách rời)
```

| Tình huống | Hệ thống làm gì | Người dùng nhận |
|---|---|---|
| Gửi lại cùng Idempotency-Key | Trả lần ký cũ (cùng bytes, digest, transaction) | `200`, không có chữ ký thứ hai |
| Người vay ký khi bên cho vay chưa ký đủ | Chặn bằng state machine | `409 INVALID_CONTRACT_TRANSITION` |
| Bên cho vay khác đang có phiên SmartCA chờ | Không chuẩn bị nhánh PDF thứ hai | `409 CONTRACT_SIGNATURE_IN_PROGRESS`, thử lại sau |
| Người dùng từ chối trên app SmartCA | Hủy lần ký, trả bên đó về chờ ký | Ký lại bằng yêu cầu mới (transaction mới) |
| Người dùng không quay lại app sau khi xác nhận | Worker hỏi SmartCA, nhúng chữ ký | Trạng thái tự cập nhật |
| Phiên quá 10 phút không có kết quả | Worker đóng phiên, giải phóng lượt ký | Ký lại nếu muốn |
| SmartCA trả chữ ký không khớp chứng thư | Không nhúng, hủy lần ký | `503 SIGNATURE_PROVIDER_INVALID_SIGNATURE` |
| Hợp đồng hết hạn khi phiên còn mở | Hủy phiên, chữ ký về sau không được nhúng | Hợp đồng `EXPIRED`, bồi hoàn như cũ (D8) |

### Dữ liệu nào ảnh hưởng kết quả?

| Dữ liệu | Người dùng hiểu là gì | Ai/nguồn | Dùng ở bước nào | Nếu thiếu/sai | Vì sao lưu |
|---|---|---|---|---|---|
| `signature_profile` | Hợp đồng ký kiểu cũ hay PAdES | Loan khi lập Contract | Chọn luồng ký | Mặc định `DETACHED_EVIDENCE` | Không đổi cách ký giữa chừng |
| Bản đã chuẩn bị (`content` lúc PREPARED) | Tệp có chỗ trống chữ ký | PDFBox | Nhúng chữ ký về sau | Không nhúng được → bỏ lần ký | SmartCA trả chữ ký sau vài phút, có thể qua restart |
| `signed_attributes` + digest | Đúng dữ liệu provider đã ký | BouncyCastle | Kiểm và đóng gói CMS | Chữ ký sai → bỏ | Ký hai bước phải dùng đúng bytes đã gửi |
| `certificate` (DER) | Chứng thư người ký | MOCK hoặc VNPT `cert_data` | signing-certificate-v2, CMS | Không lấy được → `503` | PAdES-B-B bắt buộc nhúng chứng thư |
| `base_content_hash` | Bản trước của chuỗi | Loan | Kiểm chuỗi liền mạch | Lệch → fail closed | Chứng minh mỗi bản nối tiếp đúng bản trước |
| `content` lúc SIGNED | Phiên bản thứ N của hợp đồng | Loan | Tải, ký tiếp, biên nhận | — | Là bằng chứng ký của bên đó |

### Ví dụ thực tế (IT `padesSignaturesAreSequentialIdempotentAndEveryEarlierSignatureStillVerifies`)

Khoản 50.000.000, ba phần vốn: INVESTOR-001 (20.000.000 + 10.000.000), INVESTOR-002 (20.000.000). INVESTOR-001 ký một
lần cho cả hai phần vốn → bản 1 có 1 chữ ký; gửi lại cùng khóa vẫn 1 chữ ký; INVESTOR-002 ký trên bản 1 → bản 2 có 2 chữ
ký và hợp đồng chờ người vay; người vay ký → bản 3 có 3 chữ ký, `EFFECTIVE`. Bản 1 và bản 2 là tiền tố nguyên vẹn của
bản 3; cả ba chữ ký kiểm hợp lệ trên tệp cuối; nội dung hiển thị của bản 3 trùng SIGNABLE.

### Điều kiện hoàn thành nghiệp vụ

- PDF tải về sau khi đủ chữ ký mở được bằng trình đọc chuẩn và thấy chữ ký/chứng thư của từng bên.
- Chữ ký sau không làm hỏng chữ ký trước; retry cùng bên ký và cùng yêu cầu không tạo chữ ký thứ hai.
- Thứ tự ký: mọi bên cho vay trước, người vay cuối; hợp đồng chỉ hiệu lực khi đủ chữ ký.
- Luồng MOCK + `CLICK_WRAP_MVP` của E2E vẫn chạy không đổi request; hợp đồng lập trước V32 ký theo luồng cũ.

## 1. Current state, phạm vi và ngoài phạm vi

### Hiện trạng đã kiểm trong code trước W3C

- PDF sinh bằng openhtmltopdf 1.0.10 (kéo PDFBox 2.0.24); Loan chưa có BouncyCastle.
- `VnptSmartCaSignatureProvider.submit` gửi `data_to_be_signed = pdfDocumentHash` (SHA-256 cả tệp, `sign_type: hash`),
  `get_certificate` chỉ đọc `serial_number` và `cert_status_code`; `signature_value` chỉ được băm vào evidence hash.
  PDF đã ký không có chữ ký nào (B45, B46).
- Lender ký song song; không có worker hỏi SmartCA phía server (B47).
- V9 có `UNIQUE (contract_id, artifact_type)`: một Contract chỉ có một bản mỗi loại (B48).
- Hết hạn trong lúc consent ghi history `from_status = PENDING_SIGNATURE` cố định (B49).
- E2E và local dùng `FINORA_SIGNATURE_PROVIDER=MOCK`, method `CLICK_WRAP_MVP`; harness kiểm `pdfDocumentHash` bằng
  SHA-256 của `/document` và người vay tải đúng bytes nhà đầu tư đã tải trước khi ký (`contract.same-document-both-parties`).

### Xác nhận đặc tả VNPT SmartCA (ngày đọc 2026-10-17)

Đọc từ tài liệu chính thức của VNPT: "VNPT SmartCA — Hướng dẫn tích hợp theo văn bản 769/QĐ-BTTTT" bản 2.0 (11-11-2022,
`smartca.vnpt.vn/cms_img/upload/20230201100424VNPT.IT_SmartCA_SP_QD769_API_specification_document_v2.0.pdf`) và "Tài
liệu tích hợp VNPT SmartCA" 4.1 (`doitac-smartca.vnpt.vn/help/document/Tai_lieu_tich_hop_smartca_v4.1.pdf`):

- `v1/credentials/get_certificate` trả `user_certificates[]` gồm `cert_data` (chứng thư DER mã base64, mẫu `MIIF...`),
  `chain_data.ca_cert`, `chain_data.root_cert` (có thể null), `serial_number`; bản 4.1 thêm `cert_status`
  ("Đang hoạt động"), `cert_subject`, `cert_valid_from/to`, `service_type`. **Có chứng thư DER đầy đủ** nên dựng được
  PAdES-B-B mà không cần SDK HashSigner.
- `v1/signatures/sign`: `data_to_be_signed` là "Base64 string với file, hex với hash"; mẫu 4.1 dùng hex SHA-256;
  "Hiện tại SmartCA chỉ hỗ trợ giao dịch ký hash". `transaction_id` phải duy nhất.
- `v1/signatures/sign/{tranId}/status` trả `signatures[].signature_value` (base64). Phản hồi mẫu có `expired_in: 299`.
- Giả định cần kiểm UAT thật: `signature_value` là RSA PKCS#1 v1.5 trên DigestInfo(SHA-256, hash gửi lên). FINORA kiểm
  chữ ký bằng khóa công khai của chứng thư trước khi nhúng; sai giả định thì lần ký bị hủy với
  `SIGNATURE_PROVIDER_INVALID_SIGNATURE`, không có PDF lỗi nào được lưu (mục 13).

### Trong phạm vi

PAdES-B-B ẩn cho hợp đồng nhiều bên mới; chuỗi chữ ký nối tiếp; trang biên nhận; signer demo cho MOCK; adapter SmartCA ký
hash signed attributes; worker đối soát phiên; hai endpoint tải mỗi phía; V32; test.

### Ngoài phạm vi

- PAdES-B-T/LT/LTA (dấu thời gian, DSS/OCSP/CRL): chỗ trống 16 KiB đã chừa cho dấu thời gian về sau.
- Chữ ký hiển thị (stamp), DocMDP/certification signature, chữ ký của chính FINORA lên trang biên nhận.
- Định danh SmartCA theo từng người dùng (UAT vẫn một thuê bao test cố định, LN-018).
- Webhook SmartCA (đặc tả chưa có cơ chế xác thực), đổi event liên service, UI web/mobile.

## 2. Ownership, SoR và dependency

- Loan là SoR của Contract, chuỗi chữ ký và các artifact PDF. VNPT là nguồn giá trị chữ ký; FINORA không giữ khóa riêng
  của người dùng. Khóa demo MOCK chỉ nằm trong bộ nhớ tiến trình Loan.
- Không đổi event: `LoanContractActivated.v1.pdfReceiptHash` vẫn là hash trang biên nhận; trang biên nhận chứa SHA-256 bản
  ký đủ nên R6 (Fabric) neo được cả hai qua receipt. Thêm field `signedPdfHash` optional vào event là đề xuất cho R6.
- Dependency mới: `org.bouncycastle:bcprov-jdk18on` và `bcpkix-jdk18on` 1.78.1 (phát hành 04-2024), ghim phiên bản;
  PDFBox giữ 2.0.24 của openhtmltopdf (đã kiểm bằng test chuỗi chữ ký).

## 3. Entity, value object, snapshot

| Loại | Tên | Vai trò |
|---|---|---|
| Entity (lifecycle) | `ContractPdfSignature` (`loan_contract_signatures`) | Một lần ký của một bên: PREPARED → SIGNED hoặc ABANDONED |
| Cột mới | `LoanContract.signatureProfile` | `DETACHED_EVIDENCE`/`PADES_B_B`, chốt khi lập, không cập nhật |
| Artifact bất biến | `LoanContractDocument` loại `SIGNED_PDF` | Bản ký đủ công bố cho các bên |
| Artifact bất biến | `LoanContractDocument` loại `SIGNED_RECEIPT` | Với PAdES là trang biên nhận (`PADES_RECEIPT_V1`); hợp đồng cũ giữ receipt cũ |
| Value object | `ContractPdfSignatureSummary` | Projection metadata, không kéo bytes |
| Value object | `SigningCertificate`, `DigestSignatureCommand` | Boundary provider |

### Field kỹ thuật chính của `loan_contract_signatures`

| Field | PostgreSQL | Null/constraint | Ghi bởi | Đọc bởi |
|---|---|---|---|---|
| `contract_id` | BIGINT | FK `loan_contracts` | prepare | mọi truy vấn |
| `signer_type`, `signer_id` | VARCHAR(20), VARCHAR(100) | LENDER/BORROWER | prepare | idempotency, biên nhận |
| `sequence_number` | SMALLINT | > 0; unique khi SIGNED | prepare | đầu chuỗi, thứ tự |
| `attempt_number` | SMALLINT | unique theo bên ký | prepare | transaction ID |
| `status` | VARCHAR(20) | PREPARED/SIGNED/ABANDONED + check vòng đời | prepare/complete/abandon | worker, index |
| `signature_provider`, `signature_method` | VARCHAR(30) | cặp MOCK/CLICK_WRAP_MVP hoặc VNPT/VNPT | prepare | biên nhận |
| `provider_transaction_id`, `provider_document_id` | VARCHAR(150), VARCHAR(100) | unique theo provider | prepare | SmartCA status |
| `idempotency_key`, `request_hash` | VARCHAR(150), CHAR(64) | NOT NULL | prepare | retry |
| `base_content_hash` | CHAR(64) | NOT NULL | prepare | kiểm chuỗi |
| `content`, `content_hash`, `content_length` | BYTEA, CHAR(64), INTEGER | `octet_length(content) = content_length` | prepare (placeholder), complete (đã nhúng, cùng độ dài) | tải, ký tiếp |
| `signature_contents_offset/length` | INTEGER | trong tệp | prepare | nhúng CMS |
| `document_digest`, `signed_attributes(_digest)` | CHAR(64), BYTEA, CHAR(64) | NOT NULL | prepare | kiểm/đóng gói CMS |
| `signing_time` | TIMESTAMPTZ | NOT NULL | prepare | /M, biên nhận |
| `certificate`, `certificate_chain` | BYTEA | chain nullable | prepare | CMS |
| `certificate_serial`, `certificate_sha256` | VARCHAR(80), CHAR(64) | NOT NULL | prepare | biên nhận, đối chiếu |
| `cms_hash`, `evidence_hash` | CHAR(64) | bắt buộc khi SIGNED | complete | event `LoanContractSigned`, party |
| `abandon_reason` | VARCHAR(50) | bắt buộc khi ABANDONED | abandon | vận hành |
| `prepared_at`, `signed_at`, `abandoned_at`, `version` | TIMESTAMPTZ, BIGINT | theo vòng đời | domain | worker |

Không có cột subject DN: chứng thư cá nhân VN có thể chứa `UID=CMND/CCCD` (mẫu VNPT 4.1). Chứng thư chỉ nằm trong DER
(bắt buộc cho chữ ký) và trong PDF; trang biên nhận chỉ in CN, serial, tổ chức cấp, SHA-256 (D49).

## 4. ERD và cardinality

```text
LoanContract 1 ─── 1..N ContractParty (V15)          signer_id = party_id (logic, không FK)
     │
     ├── 1 ─── 0..N ContractPdfSignature (V32, FK contract_id)
     │              • 0..1 PREPARED            (uq_..._in_flight)
     │              • 0..1 SIGNED mỗi sequence (uq_..._sequence)
     │              • 0..1 SIGNED mỗi bên ký   (uq_..._signer)
     │
     └── 1 ─── 1..3 LoanContractDocument (V9): SIGNABLE 1, SIGNED_PDF 0..1, SIGNED_RECEIPT 0..1
                    UNIQUE(contract_id, artifact_type) giữ nguyên
```

| Quan hệ | Vì sao | DB bảo vệ | Lifecycle |
|---|---|---|---|
| Contract → Signature `1:0..N` | Mỗi bên có thể thử nhiều lần; chỉ SIGNED thuộc chuỗi | FK + 3 partial unique | Không xóa; lần hỏng giữ ABANDONED để audit |
| Signature → Party `N:1..N` logic | Một nhà đầu tư nhiều phần vốn ký **một** chữ ký | Unique (contract, signer) khi SIGNED | Party lưu transaction/evidence của chữ ký |
| Contract → Document | Artifact công bố tại mốc | Unique theo loại | Bất biến |

Chuỗi revision không nhét vào `loan_contract_documents` (B48): giữ bất biến "một bản mỗi loại" của V9 và tách artifact
công bố khỏi bản làm việc của quy trình ký.

## 5. State machine và invariant

```text
ContractPdfSignature:  PREPARED ──complete(chữ ký khớp)──> SIGNED
                          └──abandon(REJECTED | SESSION_EXPIRED | SIGNATURE_INVALID | CONTRACT_CLOSED)──> ABANDONED
Contract (không đổi):  PENDING_LENDER_SIGNATURES → PENDING_BORROWER_SIGNATURE → [SIGNING] → SIGNED → EFFECTIVE
```

Invariant:

1. Bản thứ k được chuẩn bị từ đúng bytes bản k−1 (`base_content_hash`); bản k−1 là tiền tố của bản k.
2. Ngoài vùng hex `/Contents`, bản SIGNED trùng từng byte với bản PREPARED (`ContractPdfSignature.markSigned`).
3. Trước khi lưu SIGNED, toàn bộ tệp được kiểm lại: số chữ ký bằng `sequence_number`, chữ ký cuối là chứng thư đã chuẩn
   bị và bao đến byte cuối, mọi chữ ký trước vẫn hợp lệ (`PadesSignatureVerifier`).
4. Chữ ký người vay chỉ chuẩn bị khi mọi nhà đầu tư (theo `party_id` khác nhau) đã có chữ ký SIGNED.
5. Hợp đồng PAdES chỉ là hợp đồng nhiều bên (`ck_loan_contract_signature_profile`).

## 6. API, event, worker

| API | Thay đổi |
|---|---|
| `POST /api/v1/investor/loan-contracts/{n}/sign`, `POST /api/v1/loan-contracts/{n}/sign` | Request không đổi. `pdfDocumentHash` nhận hash SIGNABLE (như cũ) hoặc hash bản đầu chuỗi (D47). Lỗi mới `409 CONTRACT_SIGNATURE_IN_PROGRESS`, `409 LENDER_SIGNATURES_INCOMPLETE`, `503 SIGNATURE_PROVIDER_INVALID_SIGNATURE` |
| `GET .../{n}/document` | Trong lúc ký: SIGNABLE (không đổi). Sau khi đủ chữ ký: `SIGNED_PDF` với hợp đồng PAdES, receipt cũ với hợp đồng cũ |
| `GET .../{n}/signed-document` (mới, cả hai phía) | Bản đầu chuỗi; `404` khi chưa có chữ ký |
| `GET .../{n}/receipt` (mới, cả hai phía) | Trang biên nhận (hoặc receipt cũ); `404` khi chưa đủ chữ ký |
| Detail borrower | Thêm `signatureProfile`, `signedPdfDocument{signatureFormat, signatureCount, contentHash, contentLength, lastSignedAt, downloadPath}`, `receiptDocument`; `pdfDocument.artifactType` có thêm giá trị `SIGNED_PDF` |
| Investor response | Thêm `signatureProfile`, `pdfSignatureCount`, `signedPdfDocumentHash`, `receiptDocumentHash` |
| Event | Không đổi version/payload. `LoanContractSigned.signatureEvidenceHash` với PAdES là hash của (provider, transaction, doc, thứ tự, hash bản đã ký, hash CMS, hash chứng thư, thời điểm ký) |
| Worker `ContractPdfSignatureWorker` | Mỗi 15 giây (cấu hình), chỉ khi provider không ký đồng bộ; lấy tối đa 20 phiên PREPARED cũ trước |

Cấu hình (không có secret): `FINORA_SIGNATURE_PADES_ENABLED` (true), `FINORA_SIGNATURE_PADES_SESSION_TIMEOUT` (10m),
`FINORA_SIGNATURE_PADES_WORKER_ENABLED` (true), `FINORA_SIGNATURE_PADES_WORKER_DELAY` (15000 ms),
`FINORA_SIGNATURE_PADES_WORKER_BATCH_SIZE` (20).

## 7. Query, index và N+1

| Use case | Query | Index | Ghi chú |
|---|---|---|---|
| Phiên đang chờ của hợp đồng | `contract_id = ? AND status = 'PREPARED'` | `uq_loan_contract_signatures_in_flight` (partial, unique) | Đồng thời là chốt một-phiên-chờ |
| Đầu chuỗi | `contract_id = ? AND status = 'SIGNED' ORDER BY sequence_number DESC LIMIT 1` | `uq_loan_contract_signatures_sequence` | Đọc bytes một dòng |
| Chữ ký của một bên | `contract_id, signer_type, signer_id, status = SIGNED` | `uq_loan_contract_signatures_signer` | Idempotency theo bên ký |
| Lần thử kế tiếp | `max(attempt_number)` theo bên ký | `uq_loan_contract_signatures_attempt` | Vài dòng mỗi hợp đồng |
| Biên nhận/detail | projection metadata theo `sequence_number` | `uq_..._sequence` | Không kéo `content` |
| Worker | `status = 'PREPARED' ORDER BY prepared_at, id LIMIT n` | `idx_loan_contract_signatures_in_flight_due` | Partial nên chỉ chứa phiên đang chờ |
| Hash biên nhận cho response | `findContentHash(contract, SIGNED_RECEIPT)` | `uq_loan_contract_document_type` | Không kéo BYTEA |

Response nhà đầu tư vẫn đọc artifact hiện tại để lấy hash như trước W3C (nợ kỹ thuật cũ của `listMine`, không mở rộng).

## 8. Transaction, concurrency và idempotency

```text
MOCK (đồng bộ):   T1: khóa Contract → kiểm → chuẩn bị bản k → ký bằng khóa demo → nhúng → kiểm chuỗi → SIGNED
                      → party/contract/history/outbox (người vay: SIGNED_PDF + biên nhận + kích hoạt)   [một commit]
SmartCA (bất đồng bộ):
  ngoài tx: get_certificate
  T1: khóa Contract → kiểm → chuẩn bị bản k (PREPARED) → party/contract SIGNING                         [commit]
  ngoài tx: sign(hash signed attributes)
  ngoài tx: status (refresh của người dùng hoặc worker)
  T2: khóa Contract → kiểm PREPARED → kiểm chữ ký → nhúng → kiểm chuỗi → SIGNED → party/contract …     [commit]
```

Khi giữ khóa chỉ có việc CPU (PDFBox, BouncyCastle, render biên nhận), không gọi mạng.

| Tình huống đồng thời | Nguy cơ | Cơ chế | Kết quả |
|---|---|---|---|
| Hai nhà đầu tư MOCK ký cùng lúc | Hai nhánh từ cùng bản gốc | `findByContractNumberForUpdate` (PESSIMISTIC_WRITE) | Người sau chờ khóa rồi ký trên bản mới; cả hai thành công |
| Nhà đầu tư B ký khi A còn phiên SmartCA | Hai bản PREPARED cùng base | Kiểm phiên chờ + `uq_..._in_flight` | B nhận `409 CONTRACT_SIGNATURE_IN_PROGRESS` |
| Cùng nhà đầu tư gửi hai request cùng khóa | Hai chữ ký | Kiểm party/attempt theo khóa + `uq_..._signer` | Request sau trả kết quả cũ |
| Worker và người dùng cùng refresh | Nhúng hai lần | Khóa Contract + chỉ PREPARED mới được hoàn tất | Bên sau thấy SIGNED, không ghi |
| Hai instance worker | Gọi status hai lần | Status chỉ đọc; ghi dưới khóa | Không lease vì không có side effect ngoài |

## 9. Failure, retry, reconciliation

| Lỗi | Phân loại | Xử lý |
|---|---|---|
| `get_certificate` lỗi mạng/5xx/429 | Thử lại được | `503` từ `SmartCaIntegrationException`, chưa ghi gì |
| Chứng thư sai serial, hết hạn, không phải RSA, không được ký, trạng thái không hoạt động | Không thử lại | `503 SMARTCA_CERTIFICATE_*`, chưa ghi gì |
| `sign` timeout sau khi đã lưu PREPARED | Chưa biết kết quả | Retry cùng khóa: hỏi status; `NOT_FOUND` mới gửi lại cùng transaction |
| Status `PENDING` | Bình thường | Giữ PREPARED; worker hỏi lại; quá 10 phút thì đóng |
| Status `REJECTED` | Kết thúc | ABANDONED `PROVIDER_REJECTED`, bên ký về chờ ký |
| Chữ ký không khớp | Kết thúc | ABANDONED `PROVIDER_SIGNATURE_INVALID`, `503` sau commit |
| Lỗi PDFBox/CMS/kiểm chuỗi khi nhúng | Lỗi kỹ thuật | Rollback, PREPARED giữ nguyên, worker thử lại tới khi quá hạn rồi đóng |
| Hợp đồng hết hạn/bị từ chối khi phiên mở | Kết thúc | ABANDONED `CONTRACT_CLOSED` trong transaction đóng hợp đồng |
| Restart giữa chừng | — | PREPARED bền vững trong DB; worker/refresh tiếp tục từ status |

## 10. Security, audit, logging và PII

- Không có khóa riêng, keystore hay chứng thư test nào trong Git; khóa demo sinh mỗi lần khởi động, chỉ trong RAM; test
  tự sinh khóa (`PadesTestSigners`, `TestSigner` trong IT).
- Không log subject, CCCD, chữ ký, bytes PDF; log chỉ mã hợp đồng, id lần ký, loại exception.
- Endpoint tải mới kiểm đúng chủ hợp đồng/bên cho vay như `/document`.
- `LEGAL-DATA-01`: chứng thư SmartCA thật có thể chứa `UID=CCCD`; khi bật chứng thư production, PDF đã ký và bảng chữ ký
  sẽ chứa dữ liệu đó như một phần tất yếu của chữ ký số → cần legal review về lưu trữ/mã hóa/phân quyền tải (mục 11).

## 11. Pháp lý và chính sách

| Nội dung | Loại | Dẫn chiếu/trạng thái |
|---|---|---|
| Hợp đồng điện tử phải toàn vẹn, xem/tải được, gắn đúng phiên bản | Yêu cầu pháp luật | `LEGAL-CONTRACT-01`; `LAW-ELECTRONIC-2023` Điều 10, 11, 13, 22, 23, 38; `LAW-CONSUMER-2023` Điều 23, 38 (`CURRENT` theo sổ pháp lý) |
| Chữ ký demo MOCK và chứng thư VNPT UAT **không phải chữ ký số có giá trị pháp lý** | Giới hạn kỹ thuật demo | Ghi trên trang ký, /Reason của chữ ký và trang biên nhận |
| Click-wrap hay chữ ký số là bắt buộc cho loại hợp đồng này | Cần thẩm định | `LEGAL-OPEN-03` (`NEEDS_LEGAL_REVIEW`) |
| Chứng thư production theo từng bên, dịch vụ tin cậy, dấu thời gian | Cần thẩm định | `NEEDS_LEGAL_REVIEW`; nghị định về chữ ký điện tử/dịch vụ tin cậy chưa có trong sổ pháp lý → `NEEDS_PRIMARY_SOURCE` |
| CCCD trong subject chứng thư nằm trong PDF/DB | Dữ liệu cá nhân | `LEGAL-DATA-01` (`NEEDS_LEGAL_REVIEW` khi dùng chứng thư thật) |
| Ký nối tiếp lender trước, borrower cuối; trang biên nhận | Policy nghiệp vụ (Q9) | Không phải yêu cầu luật |

Sổ `docs/LEGAL-COMPLIANCE.md` còn câu "Receipt của adapter SmartCA hiện là bằng chứng chữ ký tách rời. Không được tuyên bố
PDF đã có chữ ký PAdES nhúng…": sau W3C, hợp đồng mới có PAdES-B-B nhúng (demo/UAT, chưa có giá trị pháp lý); cần owner
cập nhật sổ (vùng dùng chung, ngoài phạm vi agent Loan).

## 12. Quyết định (D45–D49) và lỗi (B45–B49)

| # | Quyết định | Lý do |
|---|---|---|
| D45 | Trang biên nhận là tài liệu riêng (`SIGNED_RECEIPT`, `PADES_RECEIPT_V1`) lập sau chữ ký cuối, không nằm trong phần được ký; bản SIGNABLE có trang xác nhận mô tả thứ tự ký và chỉ tới biên nhận. `/document` sau khi đủ chữ ký trả bản PAdES ký đủ (`SIGNED_PDF`); biên nhận ở `/receipt` | Mỗi chữ ký bao toàn tệp lúc ký nên trang trong tệp không liệt kê được chữ ký đến sau; chèn trang sau khi ký làm trình đọc coi chữ ký trước là bị sửa. Thực tiễn e-contract: tệp ký là tài liệu chính, biên nhận/chứng nhận hoàn tất là tài liệu kèm |
| D46 | Chuỗi revision lưu ở bảng mới `loan_contract_signatures` (PREPARED/SIGNED/ABANDONED, lưu đủ bytes mỗi bản); `loan_contract_documents` chỉ thêm loại `SIGNED_PDF` và giữ `UNIQUE(contract_id, artifact_type)` | Tách bản làm việc của quy trình ký khỏi artifact công bố; ký hai bước cần lưu bytes và signed attributes bền vững qua restart; quy mô demo chấp nhận lưu đủ bytes |
| D47 | Bên cho vay ký theo thứ tự hoàn tất, mỗi lúc chỉ một phiên ký chờ trên một hợp đồng (khóa Contract + partial unique); người vay ký sau cùng. Consent nhận hash SIGNABLE hoặc hash bản đầu chuỗi; `/document` giữ SIGNABLE trong lúc ký | PAdES buộc tuần tự ở mức bytes; thứ tự cố định giữa các lender làm một người chậm chặn mọi người. Giữ hash SIGNABLE để client/E2E hiện tại không đổi request |
| D48 | Hồ sơ CMS: PAdES-B-B `ETSI.CAdES.detached`, SHA-256, RSA PKCS#1 v1.5 (`rsaEncryption`), signed attributes content-type/message-digest/signing-certificate-v2, không signing-time (thời điểm ở /M), chứng thư ký + chuỗi CA trong `certificates`, chữ ký ẩn ở trang cuối, chừa 16 KiB. SmartCA ký hex SHA-256 của DER signed attributes; FINORA kiểm chữ ký trước khi nhúng. Worker phía server hỏi status mỗi 15 giây, đóng phiên sau 10 phút | ETSI EN 319 142-1 baseline; đặc tả VNPT (hash hex, `expired_in` 299 giây); fail closed thay vì lưu PDF hỏng |
| D49 | `signature_profile` chốt khi lập Contract; hợp đồng trước V32 giữ luồng cũ; `FINORA_SIGNATURE_PADES_ENABLED=false` là đường lùi cho hợp đồng mới. MOCK ký PAdES thật bằng chứng thư tự ký sinh lúc khởi động (`CN=FINORA DEMO - KHONG CO GIA TRI PHAP LY`), giữ provider/method `MOCK`/`CLICK_WRAP_MVP`. Không lưu subject; biên nhận chỉ in CN/serial/tổ chức cấp/SHA-256 | Không đổi cách ký giữa chừng; E2E tạo chữ ký CMS thật mà trình đọc phân tích được; tối thiểu hóa dữ liệu theo `LEGAL-DATA-01` |

| # | Mức | Lỗi và bằng chứng | Trạng thái |
|---|---|---|---|
| B45 | P1 | SmartCA ký SHA-256 của cả tệp (`data_to_be_signed = pdfDocumentHash`), giá trị chữ ký chỉ được băm rồi bỏ: PDF không có chữ ký nhúng, không ai kiểm được chữ ký từ tệp | Fixed W3C — ký hash signed attributes và nhúng CMS; `PadesSignatureChainTest`, IT PAdES |
| B46 | P1 | `get_certificate` bỏ qua `cert_data`/`chain_data`, không kiểm thời hạn/khóa/serial thật, chỉ đọc `cert_status_code` (đặc tả 4.1 dùng `cert_status = "Đang hoạt động"`) | Fixed W3C — `signingCertificate`; `VnptSmartCaSignatureProviderTest` |
| B47 | P2 | Không có worker hỏi SmartCA: người dùng xác nhận trên app mà không quay lại FINORA thì chữ ký không được ghi; phiên bỏ dở treo `SIGNING` tới khi hợp đồng hết hạn (với ký nối tiếp sẽ chặn bên khác) | Fixed W3C — `ContractPdfSignatureWorker`; IT bất đồng bộ |
| B48 | P2 | V9 `UNIQUE(contract_id, artifact_type)` không chứa được các bản ký nối tiếp | Fixed W3C bằng bảng riêng (D46), không nới ràng buộc |
| B49 | P3 | Hết hạn trong lúc consent ghi history `from_status = PENDING_SIGNATURE` cố định, sai với hợp đồng nhiều bên (`PENDING_BORROWER_SIGNATURE`/`SIGNING`) | Fixed W3C — ghi trạng thái thật trước khi hết hạn |

## 13. Test plan, acceptance và kiểm UAT thủ công

Tự động:

- `PadesSignatureChainTest` (PDF thật, khóa sinh trong test): ba chữ ký nối tiếp đều hợp lệ; ByteRange bao từ byte 0 tới
  hết phiên bản trừ đúng `/Contents`; bản trước là tiền tố; CMS detached có chứng thư ký và signing-certificate-v2, không
  signing-time; sửa một byte của bản trước làm hỏng chữ ký; bytes sau chữ ký cuối bị từ chối; chữ ký khóa khác bị phát
  hiện trước khi nhúng; không ghi đè chữ ký đã nhúng; chỗ trống đúng 16 KiB.
- `ContractPdfSignatureTest`, `ContractPartyTest`, `LoanContractTest`: vòng đời domain mới.
- `MockSignatureProviderTest`: chứng thư demo đúng subject, chữ ký digest kiểm được bằng SHA256withRSA.
- `VnptSmartCaSignatureProviderTest`: đọc `cert_data` + chuỗi, chặn serial sai/hết hạn/trạng thái không hoạt động,
  `submitDigest` gửi hex digest với `sign_type = hash`.
- `ContractPdfRendererTest`: bản PAdES đổi trang xác nhận và version; trang biên nhận.
- IT `padesSignaturesAreSequentialIdempotentAndEveryEarlierSignatureStillVerifies` (MOCK như E2E), IT
  `contractCreatedBeforePadesKeepsDetachedClickWrapEvidence` (luồng cũ), IT
  `asyncProviderSignaturesAreSerializedPolledAndAbandonedSessionsReleaseTheTurn` (spy giả lập SmartCA: 409 một phiên chờ,
  từ chối, worker thu chữ ký, phiên quá hạn, chữ ký sai), IT luồng ký nhiều bên sẵn có, `LoanMigrationUpgradeIT` V4 → V32.

Kiểm thủ công VNPT SmartCA UAT (cần người duyệt trên app SmartCA; không chạy trong test suite):

1. Điền credential UAT vào `finora-loan/.env` (git-ignore), `FINORA_SIGNATURE_PROVIDER=VNPT_SMART_CA`,
   `VNPT_SMARTCA_SANDBOX_FIXED_SIGNER_ENABLED=true`; không dán secret vào chat/log/ảnh.
2. Khởi động Loan (migration V32), tạo hồ sơ mới tới khi có Contract `PENDING_LENDER_SIGNATURES`.
3. Nhà đầu tư ký với `signatureMethod=VNPT_SMART_CA`: response `partyStatus=SIGNING`; DB có một dòng `PREPARED`.
4. Trên app SmartCA UAT, kiểm `transaction_desc` "FINORA LC-… - chu ky 1" rồi xác nhận. Không bấm kiểm tra trên FINORA;
   trong 15–30 giây worker phải chuyển dòng sang `SIGNED`.
5. Nếu dòng chuyển `ABANDONED` với `PROVIDER_SIGNATURE_INVALID`: SmartCA không ký theo DigestInfo(SHA-256) như giả định;
   ghi lại `message`, đặt `FINORA_SIGNATURE_PADES_ENABLED=false` cho hợp đồng mới và mở task điều chỉnh định dạng hash.
6. Lặp với nhà đầu tư thứ hai (thử ký cùng lúc để thấy `409 CONTRACT_SIGNATURE_IN_PROGRESS`) và người vay.
7. Tải `/document` sau khi `EFFECTIVE`, mở bằng Adobe Acrobat Reader: thấy N chữ ký, mỗi chữ ký "Document has not been
   modified since this signature was applied" cho phiên bản của nó, danh tính chưa xác minh (chứng thư UAT). Tải
   `/receipt` và đối chiếu SHA-256 tệp với dòng "Bản đã ký đủ".
8. Thử từ chối trên app và để quá 10 phút không xác nhận: dòng `ABANDONED` đúng lý do, bên ký về `PENDING_SIGNATURE`.

## 14. Bản đồ code thực tế sau triển khai

| Bước | Hàm | Transaction | Dữ liệu |
|---|---|---|---|
| Lập Contract | `LoanFullyFundedHandler.handle` → `LoanContractCreationServiceImpl.createFunded` → `ContractPdfRenderer.renderFundedSignable(..., pades)` → `LoanContract.usePadesSignatures` | Consumer transaction | `loan_contracts.signature_profile`, SIGNABLE `CONTRACT_PDF_V*_PADES` |
| Lender ký MOCK | `InvestorLoanContractController.sign` → `InvestorContractServiceImpl.sign` → `signPades` → `signingCertificate` (ngoài tx) → `signPadesSynchronously` → `ContractPdfSignatureService.signSynchronously` (`prepare` → `MockSignatureProvider.submitDigest` → `complete`) → `ContractParty.signMock` → `advanceContractIfAllLendersSigned` | Một `TransactionTemplate` khóa Contract | signature SIGNED, parties SIGNED, có thể history + outbox `BorrowerSignatureRequested` |
| Lender ký SmartCA | `signPades` → `beginPadesSignature` (T1) → `submitDigest` (ngoài tx) → `finishPadesApply` → `applyPadesSubmission` (T2) | Hai transaction ngắn | PREPARED; parties SIGNING |
| Lender refresh/worker | `refreshSignature` → `refreshPades`; `ContractPdfSignatureWorker.reconcileInFlightSignatures` → `InvestorContractServiceImpl.reconcilePadesSignature` → `applyPadesSubmission` | status ngoài tx; ghi T2 | SIGNED/ABANDONED |
| Borrower ký MOCK | `LoanContractController.sign` → `LoanContractServiceImpl.sign` → `signPades` → `LoanContractStateService.signPades` → `signSynchronously` → `LoanContract.sign` → `createSignedReceiptAndEvent` → `createPadesReceipt` (`publishFinal`, `renderSignatureReceipt`) → `activateMultiParty` | `@Transactional` khóa Contract | SIGNED_PDF, SIGNED_RECEIPT, history, `LoanContractSigned`, `LoanContractActivated`, saga |
| Borrower SmartCA | `beginPadesDigitalSignature` (T1) → `submitDigest` → `applyPadesSubmission` (T2); refresh: `pendingBorrowerSignature` → status → `applyPadesSubmission` | Hai transaction | như trên |
| Đóng hợp đồng | `expireOne`, `expireDuringConsent`, `decline` → `abandonPdfSignatureOnClose` | Cùng transaction đóng | ABANDONED `CONTRACT_CLOSED` |
| Tải | `document`, `signedDocument`, `receipt` (cả hai service) | readOnly | — |

File chính: `V32__create_pades_contract_signatures.sql`; `domain/contract/ContractPdfSignature*`,
`ContractSignatureProfile`; `repository/contract/ContractPdfSignatureRepository`, `InFlightPdfSignature`;
`integration/signature/{SigningCertificate, DigestSignatureCommand, DemoSigningCredential, MockSignatureProvider,
VnptSmartCaSignatureProvider, SignatureProvider}`; `service/contract/pades/*`; `service/contract/{ContractPdfSignatureService,
ContractPdfSignatureWorker, ContractSignatureReceipt}`; `config/PadesSignatureProperties`.

## 15. Sai khác so với dự kiến và bằng chứng

- Không cần SDK/HashSigner của VNPT: `cert_data` có chứng thư DER đầy đủ, BouncyCastle dựng CMS.
- `/document` sau khi đủ chữ ký đổi từ receipt sang bản PAdES (D45); test IT cũ được cập nhật tương ứng.
- Trang biên nhận ban đầu là bảng tám cột; khi render, mã bên ký và thời điểm bị ngắt giữa chừng nên đổi thành mỗi chữ
  ký một khối hai cột (đã xem ảnh render bằng PDFBox).
- IT trước đây không xóa `loan_disbursement_sagas` giữa các test (bảng không có FK nên `TRUNCATE ... CASCADE` bỏ sót);
  khi có thêm test đi tới `EFFECTIVE`, saga sót lại cùng `application_id` làm test sau không ghi `DisbursementRequested`.
  Đã thêm bảng này và `loan_processed_events` vào danh sách TRUNCATE của IT (chỉ sửa test).
- Worker đóng thêm phiên PDF "mồ côi" (phiên chờ mà phần vốn không còn `SIGNING`) khi quá hạn: không có luồng nào tạo ra
  trạng thái này, đây là chốt an toàn để một lỗi không chặn hợp đồng mãi (chưa có test riêng).

### Kết quả kiểm tra (2026-10-17, Windows, Docker Desktop 27.2, PostgreSQL 17.5 Testcontainers)

Baseline trước W3C, cùng lệnh `mvn -B -ntp -pl finora-loan -am verify`: common 52/52, Loan unit 262/262, IT 17 (1 skip
có chủ đích `dockerAiV17ScoresThroughLoanAndPersistsAssessment`), `BUILD SUCCESS`.

Sau W3C (lượt cuối, cùng lệnh, xóa `target/classes` trước khi chạy):

| Nhóm | Kết quả |
|---|---|
| `finora-common` unit | 52/52 |
| `finora-loan` unit/architecture | 283/283 (262 cũ + 21 mới: `PadesSignatureChainTest` 7, `ContractPdfSignatureTest` 3, `VnptSmartCaSignatureProviderTest` +3, `LoanContractTest` +2, `ContractPdfRendererTest` +2, `PadesSignaturePropertiesTest` 2, `MockSignatureProviderTest` +1, `ContractPartyTest` +1) |
| `FinoraLoanApplicationIT` | 19 chạy, 0 lỗi, 1 skip có chủ đích (16 cũ + 3 test PAdES mới) |
| `LoanMigrationUpgradeIT` | 1/1, V4 → V32 |
| Tổng | `BUILD SUCCESS` |

Kiểm độc lập bằng pyHanko 0.25.1 (venv riêng ngoài repo) trên tệp IT ghi ra
`finora-loan/target/test-output/finora-pades-signed-preview.pdf` (3 chữ ký MOCK): cả ba `intact=True valid=True`,
SubFilter `/ETSI.CAdES.detached`, `md=sha256`, signed attributes đúng `content_type, message_digest,
signing_certificate_v2`; chữ ký 1–2 `coverage=ENTIRE_REVISION`, `modification=FORM_FILLING` (sau chúng chỉ có thêm chữ
ký), chữ ký 3 `coverage=ENTIRE_FILE`, `modification=NONE`, `docmdp_ok=True`. Chứng thư demo chỉ "trusted" khi chính nó
được đặt làm trust root trong lệnh kiểm; với trình đọc thật nó không được tin cậy, đúng bản chất demo.

## 16. Việc còn lại cho người và cho owner khác

- Người: chạy mục 13 trên SmartCA UAT (cần duyệt trên app).
- Main agent: bổ sung check E2E (ví dụ `contract.pades-signature-count`, tải `/receipt`, đối chiếu `signedPdfDocument`);
  cập nhật `docs/LEGAL-COMPLIANCE.md` (`LEGAL-CONTRACT-01`, `LEGAL-DATA-01`), `docs/integrations/VNPT-SMARTCA-UAT.md`,
  `01-project-context.md` (dòng SmartCA ký hash), roadmap/plan tổng R5.
- Web/mobile: kiểu `artifactType` thêm `SIGNED_PDF`; nút "Trang biên nhận chữ ký" (`/receipt`) và "Bản đang ký"
  (`/signed-document`); hiện `pdfSignatureCount`; xử lý `409 CONTRACT_SIGNATURE_IN_PROGRESS` (thử lại sau) và
  `503 SIGNATURE_PROVIDER_INVALID_SIGNATURE` (ký lại).
- R6: neo `SIGNED_PDF` hash; cân nhắc thêm `signedPdfHash` optional vào `LoanContractActivated.v1`.
