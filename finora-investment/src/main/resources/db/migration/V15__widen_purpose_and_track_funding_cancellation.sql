-- Loan gửi purpose = nhãn mục đích + ": " + chi tiết người vay tự nhập (tối đa 500 ký tự). Cột 100 ký tự
-- làm LoanFundingRequested vào DLT và listing không bao giờ xuất hiện, nên nới đủ cho nhãn dài nhất.
ALTER TABLE market_listings
    ALTER COLUMN purpose TYPE VARCHAR(600);

-- LoanFundingCancelled.v1: đã đủ vốn nhưng khoản vay không giải ngân. Listing CANCELLED giữ lý do và thời
-- điểm để đối soát việc trả tiền giữ; tiền được nhả bởi worker theo từng lệnh, không trong transaction này.
ALTER TABLE market_listings
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancellation_reason VARCHAR(40);

-- NOT VALID: chỉ ràng buộc dòng ghi mới để migration không thất bại nếu database dùng chung còn listing
-- CANCELLED cũ từ trước khi có hai cột này.
ALTER TABLE market_listings
    ADD CONSTRAINT ck_listing_cancellation_complete CHECK (
        status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL)
    ) NOT VALID;
