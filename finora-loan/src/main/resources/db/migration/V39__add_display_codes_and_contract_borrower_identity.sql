-- Mã hiển thị dễ đọc cho người dùng: hồ sơ HS-yyyyMMdd-XXXX, hợp đồng HĐ-yyyyMMdd-XXXX (ngày theo giờ Việt Nam lúc tạo,
-- XXXX lấy từ bảng chữ đã bỏ ký tự dễ nhầm 0/O, 1/I/L, U/V). application_number/contract_number giữ nguyên làm khóa API
-- và khóa event: mã hiển thị đoán được ngày nên không dùng làm khóa tra cứu công khai.
ALTER TABLE loan_applications
    ADD COLUMN application_code VARCHAR(20);

ALTER TABLE loan_contracts
    ADD COLUMN contract_code VARCHAR(20);

-- Ràng buộc duy nhất tạo trước khi backfill để bước kiểm trùng bên dưới dùng được index.
ALTER TABLE loan_applications
    ADD CONSTRAINT uq_loan_applications_application_code UNIQUE (application_code);

ALTER TABLE loan_contracts
    ADD CONSTRAINT uq_loan_contracts_contract_code UNIQUE (contract_code);

-- Backfill hồ sơ/hợp đồng cũ theo created_at. random() đủ cho dữ liệu đã có; mã mới do ứng dụng sinh bằng SecureRandom.
DO $$
DECLARE
    alphabet CONSTANT TEXT := 'ABCDEFGHJKMNPQRSTWXYZ23456789';
    item RECORD;
    candidate TEXT;
BEGIN
    FOR item IN SELECT id, created_at FROM loan_applications WHERE application_code IS NULL ORDER BY id LOOP
        LOOP
            candidate := 'HS-' || to_char(item.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYYMMDD') || '-'
                || (SELECT string_agg(substr(alphabet, 1 + floor(random() * length(alphabet))::INT, 1), '')
                    FROM generate_series(1, 4));
            EXIT WHEN NOT EXISTS (SELECT 1 FROM loan_applications WHERE application_code = candidate);
        END LOOP;
        UPDATE loan_applications SET application_code = candidate WHERE id = item.id;
    END LOOP;

    FOR item IN SELECT id, created_at FROM loan_contracts WHERE contract_code IS NULL ORDER BY id LOOP
        LOOP
            candidate := 'HĐ-' || to_char(item.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYYMMDD') || '-'
                || (SELECT string_agg(substr(alphabet, 1 + floor(random() * length(alphabet))::INT, 1), '')
                    FROM generate_series(1, 4));
            EXIT WHEN NOT EXISTS (SELECT 1 FROM loan_contracts WHERE contract_code = candidate);
        END LOOP;
        UPDATE loan_contracts SET contract_code = candidate WHERE id = item.id;
    END LOOP;
END $$;

ALTER TABLE loan_applications
    ALTER COLUMN application_code SET NOT NULL;

ALTER TABLE loan_contracts
    ALTER COLUMN contract_code SET NOT NULL;

-- Danh tính bên vay in trên hợp đồng, chụp từ finora-user lúc lập hợp đồng (ngoài transaction) để PDF ký và các bản
-- biên nhận render sau đều in cùng một danh tính. Chỉ có họ tên và CCCD đã che 4 số cuối (LEGAL-DATA-01).
-- Hợp đồng lập trước bản này để NULL: văn bản đã lưu và biên nhận của chúng giữ cách ghi cũ.
ALTER TABLE loan_contracts
    ADD COLUMN borrower_full_name VARCHAR(255),
    ADD COLUMN borrower_masked_id_number VARCHAR(32),
    ADD COLUMN borrower_identity_captured_at TIMESTAMPTZ;
