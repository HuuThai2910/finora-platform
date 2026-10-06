# CIC current/history risk và thông báo servicing

> Trạng thái: `IMPLEMENTED_LOCAL_PENDING_FULL_E2E` — schema/API/consumer/UI đã nối;
> cần chạy migration trên database tích hợp, rebuild CIC/Investment/Notification và smoke Kafka.

## 1. Mục tiêu nghiệp vụ

- Không dùng `nhomNoCaoNhat` lịch sử như lệnh cấm vay vĩnh viễn.
- Người đang có nợ xấu bị chặn; người đã khắc phục đi qua thời gian phục hồi có version.
- Nhà đầu tư thấy DPD/nhóm nợ/số tiền quá hạn trên đúng Note đang sở hữu.
- Không gửi push mỗi ngày. Chỉ phát tín hiệu push khi đổi nhóm nợ quan trọng, khắc phục,
  cơ cấu, tất toán hoặc tất toán sớm; DPD 1 chỉ cập nhật read model/in-app.

Các mốc 12/24 tháng là **chính sách rủi ro nội bộ FINORA**, không phải thời hạn cấm vay
do pháp luật ấn định. Lịch sử CIC mock có thể được lưu để mô phỏng báo cáo, nhưng thời gian
lưu không tự đồng nghĩa người dùng bị cấm vay trong toàn bộ thời gian đó.

## 2. Field CIC

| Field | Kiểu | Nguồn/cách cập nhật | Cách dùng |
|---|---|---|---|
| `nhomNoCaoNhat` | `Integer 1..5` | `max(lịch sử, event.debtGroup)` | Feature lịch sử, ảnh hưởng điểm; không tự chặn hồ sơ |
| `nhomNoHienTai` | `Integer 1..5` | `LoanDelinquencyChanged.debtGroup` | Nhóm 3–5 chặn tự động theo policy FINORA |
| `ngayKhacPhucNoXau` | `LocalDate` | Ngày chuyển từ nhóm 3–5 về 1–2 | Audit mốc phục hồi |
| `tamKhoaVayDen` | `LocalDate` | `ngàyKhắcPhục + CIC_BAD_DEBT_COOLDOWN_MONTHS` | Trước mốc: `REJECTED` |
| `thamDinhThuCongDen` | `LocalDate` | `ngàyKhắcPhục + CIC_BAD_DEBT_MANUAL_REVIEW_MONTHS` | Sau cooldown nhưng trước mốc: `PENDING_REVIEW` |

Migration `cic-service/V4` backfill bảo thủ `nhomNoHienTai = nhomNoCaoNhat` cho dữ liệu cũ.
Event Loan tiếp theo tạo phiên bản mới đúng trạng thái; không sửa dòng lịch sử.

## 3. Quyết định AI

| Điều kiện | Mã | Kết quả |
|---|---|---|
| `nhom_no_hien_tai >= 3` | `CIC_CURRENT_BAD_DEBT` | `REJECTED` |
| ngày tra cứu `< tam_khoa_vay_den` | `CIC_BAD_DEBT_COOLDOWN` | `REJECTED` |
| cooldown đã hết nhưng `< tham_dinh_thu_cong_den` | `CIC_BAD_DEBT_RECOVERY_REVIEW` | `PENDING_REVIEW` |
| Có lịch sử nhưng thiếu nhóm hiện tại | `CIC_CURRENT_GROUP_MISSING` | `PENDING_REVIEW` |
| Hết giai đoạn phục hồi | Không có chốt | Chấm bình thường; lịch sử vẫn ảnh hưởng score |

`review_reasons` được tách khỏi `rejection_reasons`. Loan lưu response AI; không biến lý do
thẩm định thành lý do từ chối.

## 4. Projection Investment

`investment_loan_servicing_states` là read model, không thay Fineract/Loan làm nguồn chuẩn.

| Field | Ý nghĩa |
|---|---|
| `daysPastDue`, `debtGroup`, `overdueAmount`, `overdueSince` | Snapshot rủi ro mới nhất |
| `totalOutstanding` | Tổng nghĩa vụ tại `riskDataAsOf` |
| `riskChangedAt` | Thời điểm event phát |
| `riskDataAsOf` | Thời điểm dữ liệu core có hiệu lực; event cũ hơn không ghi đè |
| `maturityDate`, `scheduleChangedAt` | Projection sau cơ cấu |
| `settledAt` | Mốc khoản vay đóng |

Điều khoản Note đã phát hành (gốc, lãi, kỳ hạn ban đầu) không bị sửa. API portfolio ghép read
model này theo `loanApplicationId` để mobile hiển thị rủi ro.

## 5. Event và delivery

Investment consume `LoanDelinquencyChanged.v1`, cập nhật projection trong cùng transaction với
`processed_events`, rồi ghi transactional outbox `InvestorNoteServicingChanged.v1` lên topic
`finora.investment.note-servicing-changed`.

Event chỉ mang logical `investorId`, Note reference và dữ kiện vận hành; không mang tên, CCCD,
email, OTP hoặc tài liệu hợp đồng. Notification fan-out một bản ghi in-app cho mỗi investor và
dedup bằng `sourceEventId + recipientId + type`.

| `changeType` | In-app | Cờ push ngoài app |
|---|---:|---:|
| `DELINQUENCY_CHANGED` tại DPD 1, chưa đổi nhóm | Có | Không |
| Đổi sang nhóm 2/3/4/5 | Có | Có |
| `DELINQUENCY_CURED` | Có | Có |
| `REPAYMENT_CREDITED` | Có | Không |
| `EARLY_SETTLEMENT`, `SETTLED`, `RESCHEDULED` | Có | Có |

`externalPushRequired` hiện là durable intent. Gửi push hệ điều hành còn cần device-token thuộc
User và provider Expo/FCM/APNs; không được giả lập “đã gửi push” khi chưa có receipt provider.

## 6. API/UI

- `GET /api/v1/notifications?limit=50`: danh sách của JWT subject hiện tại.
- `GET /api/v1/notifications/unread-count`: badge trang chủ.
- `POST /api/v1/notifications/{id}/read`: chỉ sửa bản ghi thuộc chính người đăng nhập.
- Mobile portfolio hiển thị DPD, nhóm nợ và số tiền quá hạn từ Investment; không tự tính.
- Web admin tiếp tục lấy collection/DPD từ Loan servicing API vì đó là màn vận hành, không đọc DB Investment.

## 7. Kiểm thử bắt buộc

1. Giao lại cùng event Kafka: không tạo thông báo hoặc phiên bản side effect lần hai.
2. Event risk cũ hơn `riskDataAsOf`: không ghi đè snapshot mới.
3. Một investor giữ nhiều Note của cùng loan: chỉ có một notification cho event/type.
4. DPD 1 không đặt `externalPushRequired`; đổi nhóm/cure có đặt cờ.
5. JWT investor A không đọc/mark-read được notification của investor B.
