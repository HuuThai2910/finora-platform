-- R2 (Wave 2D): vận hành đưa saga REPAIR_REQUIRED về ghi core lại sau khi kiểm Fineract theo externalId (số hợp đồng) và
-- xử lý nguyên nhân. Saga chỉ vào REPAIR_REQUIRED sau khi Payment đã giải ngân, nên thao tác này chỉ chạy lại bước ghi
-- Fineract; booking reconcile theo externalId nên không tạo khoản vay thứ hai.
-- attempt_count vẫn tăng đơn điệu làm claim token; giới hạn tự động tính từ repair_attempt_base của lần sửa gần nhất.
ALTER TABLE loan_disbursement_sagas
    ADD COLUMN repair_attempt_base INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN repair_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_repair_by VARCHAR(100),
    ADD COLUMN last_repair_at TIMESTAMPTZ,
    ADD COLUMN last_repair_note VARCHAR(500);

ALTER TABLE loan_disbursement_sagas
    ADD CONSTRAINT ck_loan_disbursement_sagas_repair CHECK (
        repair_attempt_base >= 0 AND repair_attempt_base <= attempt_count AND repair_count >= 0
        AND ((last_repair_at IS NULL) = (last_repair_by IS NULL))
    );

-- Màn sự cố của admin lọc saga theo trạng thái.
CREATE INDEX idx_loan_disbursement_sagas_status_updated
    ON loan_disbursement_sagas (status, updated_at, id);
