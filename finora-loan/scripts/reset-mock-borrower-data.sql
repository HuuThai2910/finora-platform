-- Dọn dữ liệu vay được tạo trong giai đoạn Loan Service còn dùng người vay giả.
--
-- Trước khi service đọc danh tính từ access token, mọi hồ sơ đều được ghi với
-- borrower_id = 'BORROWER-001' và admin_id = 'ADMIN-001'. Sau khi chuyển sang claim
-- user_id, những bản ghi đó không thuộc về tài khoản thật nào: người dùng đăng nhập
-- sẽ không thấy chúng, còn admin thì thấy hồ sơ không tra ngược được chủ sở hữu.
--
-- Script xoá toàn bộ vòng đời hồ sơ vay và giữ nguyên danh mục sản phẩm
-- (loan_products, fineract_product_mappings) vì sản phẩm không gắn với người vay.
--
-- Cách chạy:
--   psql "$LOAN_DB_URL" -f scripts/reset-mock-borrower-data.sql
--
-- CẢNH BÁO: script xoá dữ liệu không khôi phục được. Chỉ dùng cho môi trường
-- phát triển và demo, không chạy trên dữ liệu cần giữ.

BEGIN;

-- TRUNCATE ... CASCADE thay vì xoá lần lượt vì loan_applications và
-- schedule_calculation_snapshots tham chiếu vòng lẫn nhau, không có thứ tự xoá nào
-- thoả mãn cả hai chiều khoá ngoại.
TRUNCATE TABLE
    loan_contract_status_histories,
    loan_contracts,
    credit_scoring_retry_requests,
    credit_scoring_assessments,
    borrower_credit_profiles,
    borrower_eligibility_checks,
    loan_application_status_histories,
    schedule_calculation_snapshots,
    loan_applications
    RESTART IDENTITY CASCADE;

COMMIT;
