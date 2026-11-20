-- =====================================================================
-- V12 (EKYC duyệt tay khi AI lỗi + rút lại hồ sơ): thêm trạng thái lượt WITHDRAWN.
-- Người dùng rút lại hồ sơ đang chờ duyệt tay (nhân viên chưa duyệt/từ chối) để gửi hồ sơ mới.
-- Tách khỏi ABANDONED để màn duyệt hiện đúng "Người dùng đã rút lại"; bằng chứng xoá như lượt bỏ dở.
-- Không sửa V6: chỉ thay check constraint, dữ liệu cũ không đổi.
-- =====================================================================
ALTER TABLE ekyc_attempts DROP CONSTRAINT ck_ekyc_attempts_status;

ALTER TABLE ekyc_attempts
    ADD CONSTRAINT ck_ekyc_attempts_status
        CHECK (status IN ('IN_PROGRESS','VERIFIED','MANUAL_REVIEW','APPROVED','REJECTED','ABANDONED','WITHDRAWN'));
