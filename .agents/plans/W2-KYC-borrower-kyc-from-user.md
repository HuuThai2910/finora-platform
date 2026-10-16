---
task_id: W2-KYC
title: Loan lấy trạng thái eKYC và tuổi người vay thật từ User khi nộp hồ sơ
owner: Thai
status: IN_PROGRESS
depends_on: [R1A]
created_at: 2026-10-17
updated_at: 2026-10-17
approved_by: Thai
approved_at: 2026-10-17
review_required: Hải review endpoint mới của finora-user trước merge (D2)
---

# W2-KYC — Adapter KYC người vay từ User (D4, B19)

Thái chốt D4 ngày 2026-10-17: Loan dùng dữ liệu KYC thật của User, chỉ người vay `VERIFIED` được nộp hồ sơ, mock chỉ
còn cho test và E2E. Plan xử lý B19 trong [`finora-remaining-work-plan.md`](finora-remaining-work-plan.md) mục 3b.
Kiểm soát pháp lý: [`LEGAL-DATA-01`](../../docs/LEGAL-COMPLIANCE.md), [`LEGAL-DISCLOSURE-02`](../../docs/LEGAL-COMPLIANCE.md).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Hiện Loan coi mọi người vay là đã eKYC và 30 tuổi (`LocalMockBorrowerProfileProvider`). Người chưa xác minh danh tính
vẫn nộp được hồ sơ, được AI chấm và có thể lên sàn; nhà đầu tư thấy nhãn “hồ sơ giả lập”. Sau task này:

- Người vay chưa hoàn tất eKYC bấm “Nộp hồ sơ” thì bị chặn ngay với lời nhắc xác minh eKYC và nút mở màn eKYC; hồ sơ
  không được tạo, Fineract không bị gọi.
- Người vay đã `VERIFIED` nộp bình thường; Loan chụp lại trạng thái eKYC và tuổi tại thời điểm nộp làm bằng chứng
  (snapshot — bản ghi bất biến của dữ liệu đúng lúc đó). Bước kiểm điều kiện và AI dùng snapshot này.
- Nhà đầu tư thấy tuổi và trạng thái eKYC thật; nhãn “hồ sơ giả lập” chỉ còn ở stack E2E/test.

### Ví dụ

An đăng ký tài khoản sáng nay, chưa quét CCCD. An chọn gói vay và nộp: app báo “Bạn cần hoàn tất xác minh eKYC trước khi
nộp hồ sơ vay” và nút “Xác minh eKYC”. An quét CCCD, xác nhận, quay lại nộp: thành công, hồ sơ ghi `kyc = VERIFIED`,
tuổi 27, nguồn `USER_SERVICE`, thời điểm chụp.

## 1. Lựa chọn tích hợp

| Phương án | Đánh giá |
|---|---|
| **Chuyển tiếp token của chính người vay lúc nộp hồ sơ tới endpoint tối giản của User** (chọn) | Người vay tự cho phép đọc dữ liệu của mình; không thêm client Keycloak hay secret dùng chung; đọc trạng thái hiện tại trong DB User (claim trong token có thể cũ tới lần refresh); User là SoR |
| Service account Loan → User | Thêm client/secret mới, quyền đọc hồ sơ người khác; worker có thể đọc lại nhưng không cần vì snapshot đủ |
| Đọc claim `ekyc_status` trong JWT | Không có tuổi; claim chỉ cập nhật khi cấp token mới |
| Dùng `GET /api/v1/users/me` | Trả họ tên, ngày sinh, CCCD che… — vượt mục đích, trái tối thiểu hóa dữ liệu (`LAW-DATA-2025`) |

Worker chấm điểm chạy nền, không có token người vay, nên không gọi User; nó dùng snapshot đã lưu cùng hồ sơ.

## 2. Contract mới của User

`GET /api/v1/users/me/kyc-summary` — quyền `user:profile:read` (đã có cho người vay). Chỉ trả:

| Field | Ý nghĩa | Ghi chú |
|---|---|---|
| `userId` | ID hồ sơ nội bộ, cùng giá trị claim `user_id` mà Loan dùng làm `borrowerId` | Loan đối chiếu để chặn token lệch người |
| `ekycStatus` | `PENDING`, `VERIFIED`, `FAILED`, `MANUAL_REVIEW` | Enum dùng chung `EkycStatus` |
| `documentVerified` | CCCD đã được xác nhận | |
| `ekycCompletedAt` | Thời điểm hoàn tất eKYC | null khi chưa xong |
| `age` | Tuổi tròn tính theo ngày Việt Nam | Không trả ngày sinh; null khi chưa có |

Không trả họ tên, ngày sinh, CCCD, số điện thoại, email, địa chỉ hay ảnh.

## 3. Loan

### 3.1 Map trạng thái

| User `EkycStatus` | Loan `BorrowerKycStatus` | Nộp hồ sơ |
|---|---|---|
| `VERIFIED` | `VERIFIED` | Cho phép |
| `PENDING` | `PENDING` | `409 BORROWER_KYC_REQUIRED` |
| `MANUAL_REVIEW` | `PROCESSING` | `409 BORROWER_KYC_REQUIRED` |
| `FAILED` | `REJECTED` | `409 BORROWER_KYC_REQUIRED` |

### 3.2 Luồng nộp hồ sơ

```text
POST /api/v1/loan-applications (token người vay)
 → kiểm idempotency (khóa đã dùng → trả hồ sơ cũ, không gọi User)
 → ngoài transaction: BorrowerProfileProvider.captureAtSubmission(borrowerId, ngày VN)
      user: GET User /me/kyc-summary với Authorization của chính request; timeout 3 giây
      mock (chỉ test/E2E): VERIFIED, 30 tuổi, nguồn MOCK_USER_PROFILE
 → không VERIFIED → 409 BORROWER_KYC_REQUIRED; User lỗi/timeout → 503 USER_PROFILE_UNAVAILABLE
 → ngoài transaction: Fineract tính lịch (như cũ)
 → transaction: kiểm hạn mức dư nợ (D5) + ghi Application kèm kyc snapshot + schedule snapshot + history
```

### 3.3 Snapshot trên hồ sơ (Loan V29)

`loan_applications` thêm value object nhúng `BorrowerKycSnapshot`, quan hệ hồ sơ `1 — 0..1` snapshot (null cho hồ sơ
trước V29):

| Cột | Nguồn | Dùng ở đâu |
|---|---|---|
| `kyc_snapshot_status` | Provider lúc nộp | Policy điều kiện, hồ sơ cho nhà đầu tư |
| `kyc_snapshot_age` | Provider lúc nộp | Policy tuổi, hồ sơ cho nhà đầu tư |
| `kyc_snapshot_reference`, `kyc_snapshot_version` | Provider | Truy vết nguồn |
| `kyc_snapshot_income_status` | Provider (hiện `NOT_VERIFIED`) | Input AI |
| `kyc_snapshot_source` | `USER_SERVICE` hoặc `MOCK_USER_PROFILE` | Nhãn hồ sơ giả lập |
| `kyc_snapshot_captured_at` | Thời điểm chụp | Bằng chứng |

CHECK: hoặc tất cả null (legacy) hoặc status/source/income/captured_at cùng có; giá trị enum giống bảng
`borrower_eligibility_checks`. Không cần index: chỉ đọc theo khóa chính của hồ sơ.

### 3.4 Worker điều kiện

`BorrowerEligibilityService.evaluate` dùng snapshot của hồ sơ. Hồ sơ legacy không có snapshot: provider mock vẫn đọc
được ở nền; provider user không có token nên ghi kết quả `INVALID_PROFILE` lý do `KYC_SNAPSHOT_MISSING` (hồ sơ sang
`PENDING_REVIEW` cho admin), không đoán `VERIFIED`.

### 3.5 Cấu hình

- `finora.borrower-profile.provider` mặc định `user`; profile `local` đọc `FINORA_BORROWER_PROFILE_PROVIDER`
  (mặc định `user`). Launcher E2E đặt `mock` vì kịch bản không dùng ảnh CCCD giả (D11).
- `finora.user.base-url` (`USER_SERVICE_URL`, local `http://localhost:8085`), `connect-timeout`, `read-timeout`.

## 4. Failure và concurrency

- User timeout/5xx/401/403: `503 USER_PROFILE_UNAVAILABLE`, không tạo hồ sơ; client gửi lại cùng `Idempotency-Key`.
- `userId` trả về khác `borrowerId` của token: `503` và log `error` (cấu hình sai), không tạo hồ sơ.
- Hai request cùng khóa: lần kiểm idempotency hoặc unique key chặn như cũ; cả hai có thể gọi User (chỉ đọc, vô hại).
- Người vay bị hạ eKYC sau khi nộp: hồ sơ đã nộp giữ snapshot lúc nộp; kiểm lại trước giải ngân không thuộc task này.

## 5. Mobile

Lỗi `BORROWER_KYC_REQUIRED` khi nộp hiện thông điệp backend kèm nút “Xác minh eKYC” mở `Hồ sơ → EkycCapture`.

## 6. Test và acceptance

- User: `kyc-summary` trả đúng field, tính tuổi theo ngày Việt Nam, không có PII.
- Loan unit: map trạng thái; provider user gửi đúng `Authorization`, lỗi → `503`, `userId` lệch → `503`; worker dùng
  snapshot; legacy không snapshot với provider user → `INVALID_PROFILE/KYC_SNAPSHOT_MISSING`.
- Loan IT (PostgreSQL): hồ sơ có snapshot sau khi nộp; người vay chưa `VERIFIED` bị `409`, không có hồ sơ.
- Migration upgrade IT lên V29; E2E R1A vẫn xanh với provider mock.

## 7. Bản đồ code thực tế

Điền sau khi triển khai.
