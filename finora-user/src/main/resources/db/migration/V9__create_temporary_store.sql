-- =====================================================================
-- V9 (USER-NO-REDIS): trạng thái tạm của User chuyển từ Redis sang PostgreSQL.
-- Hai bảng thay cho key Redis có TTL; khóa giữ nguyên tên key cũ (reg_otp:<email>,
-- ekyc_draft:<uid>, ...). Dòng có expires_at <= now() coi như không tồn tại; worker
-- dọn định kỳ xoá hẳn để bản nháp eKYC (có embedding khuôn mặt) không nằm lại.
-- Dữ liệu ở đây sống vài giây đến 30 ngày và có thể mất mà không sai nghiệp vụ, nên
-- không có created_at/updated_at như bảng nghiệp vụ.
-- =====================================================================

-- Giá trị tạm: OTP, phiên đăng ký tạm, bản nháp và thử thách eKYC, khoá chặn đăng nhập,
-- suất gọi AI, hash media đã gửi. Giá trị nhạy cảm được mã hoá AES-GCM trước khi ghi.
CREATE TABLE user_temporary_entries (
    entry_key   VARCHAR(512)             NOT NULL,
    entry_value TEXT                     NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_temporary_entries PRIMARY KEY (entry_key)
);

CREATE INDEX idx_user_temporary_entries_expires_at ON user_temporary_entries (expires_at);

-- Bộ đếm theo cửa sổ thời gian: đăng nhập sai, số lần thử OTP, số yêu cầu OTP mỗi giờ,
-- số lần chụp lại thẻ, số lần trượt liveness.
CREATE TABLE user_rate_counters (
    counter_key   VARCHAR(512)             NOT NULL,
    counter_value BIGINT                   NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_rate_counters PRIMARY KEY (counter_key)
);

CREATE INDEX idx_user_rate_counters_expires_at ON user_rate_counters (expires_at);
