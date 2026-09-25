# VNPT SmartCA UAT — cấu hình và kiểm thử FINORA

Tài liệu này chỉ áp dụng cho môi trường UAT `rmgateway.vnptit.vn`. Không dùng credential hoặc chứng
thư test cho production. Không gửi secret, CCCD, OTP hay private key qua chat, Git, log hoặc ảnh chụp.

## 1. Phạm vi hiện tại

- Loan gọi Web API v1 để kiểm tra chứng thư, gửi hash PDF cần ký và chủ động tra cứu kết quả.
- FINORA không nhận mật khẩu/OTP SmartCA của người ký.
- UAT dùng một signer test cố định lấy từ biến môi trường. Đây không phải mô hình định danh production.
- Bằng chứng trả về được băm và lưu trong Loan; raw signature không được lưu. PDF `SIGNABLE` không bị
  sửa. Receipt hiện là bằng chứng tách rời, chưa phải PDF PAdES có chữ ký nhúng.
- Chưa dùng webhook vì contract xác thực webhook chưa được chốt; mobile cho phép borrower bấm kiểm tra
  lại trong lúc Contract ở trạng thái `SIGNING`.

## 2. Cấu hình cục bộ

Sao chép tên biến từ `finora-loan/.env.example` sang `finora-loan/.env`, sau đó điền giá trị do portal
VNPT UAT cấp. Để bật adapter:

```dotenv
FINORA_SIGNATURE_PROVIDER=VNPT_SMART_CA
VNPT_SMARTCA_BASE_URL=https://rmgateway.vnptit.vn/sca/sp769
VNPT_SMARTCA_SP_ID=<secret-local-only>
VNPT_SMARTCA_SP_PASSWORD=<secret-local-only>
VNPT_SMARTCA_SANDBOX_FIXED_SIGNER_ENABLED=true
VNPT_SMARTCA_SANDBOX_USER_ID=<uat-user-id-local-only>
VNPT_SMARTCA_SANDBOX_SERIAL_NUMBER=<uat-serial-local-only>
```

Không thay `sp_id/sp_password` bằng tài khoản đăng nhập portal. Với Web API v1, FINORA chỉ cấu hình
đúng cặp service-provider credential mà VNPT cấp cho hệ thống tích hợp.

## 3. Luồng kiểm thử thủ công

1. Khởi động Loan với migration V13 và xác nhận application start thành công.
2. Tạo hồ sơ mới, đi tới Contract `PENDING_SIGNATURE`, rồi mở đúng PDF server trả về.
3. Mobile gửi action ký với `signatureMethod=VNPT_SMART_CA` và idempotency key mới.
4. Loan phải trả Contract `SIGNING`; việc này chỉ có nghĩa yêu cầu đã được VNPT tiếp nhận.
5. Mở app SmartCA UAT, đọc thông tin giao dịch rồi chấp nhận hoặc từ chối tại đó.
6. Trên mobile bấm **Kiểm tra kết quả ký**. Khi VNPT trả signature đúng `transactionId + docId`, Loan
   chuyển sang `SIGNED` và tạo `SIGNED_RECEIPT`. Nếu VNPT từ chối/hết hạn, Contract trở về
   `PENDING_SIGNATURE` để thử lại.
7. Đối chiếu rằng PDF `SIGNABLE` trước và sau ký có cùng SHA-256; không có secret/PII trong log.

Không dùng hồ sơ/hợp đồng cũ để thử migration luồng nghiệp vụ nếu chúng được tạo trước V13; nên tạo dữ
liệu test mới. Không cần xóa toàn bộ database chỉ để bật adapter.

## 4. Điều kiện trước production

- Bỏ fixed signer và lấy đúng signer theo authenticated user contract.
- Chốt contract nhà đầu tư/bên cho vay và chữ ký của cả hai bên.
- Dùng secret manager, rotation, audit và outbound allow-list; không dùng `.env` production.
- Chốt cơ chế nhúng/kiểm tra PAdES bằng SDK/HashSigner chính thức nếu sản phẩm yêu cầu PDF ký số nhúng.
- Có legal/security review, chính sách retry/reconciliation và contract callback/webhook được xác thực.
