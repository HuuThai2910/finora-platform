-- Admin cần xem cả Product DRAFT/FAILED, không thể dùng index catalog chỉ phục vụ status ACTIVE.
-- Index thứ nhất hỗ trợ danh sách toàn bộ theo thứ tự mới nhất; index thứ hai hỗ trợ lọc trạng thái sync.
CREATE INDEX idx_loan_products_admin_created
    ON loan_products (created_at DESC, id DESC);

CREATE INDEX idx_loan_products_core_sync_created
    ON loan_products (core_sync_status, created_at DESC, id DESC);
