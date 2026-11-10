-- Số tiền đã gọi được tại lúc listing đóng (hết hạn) hoặc bị hủy. Khi đóng/hủy, phần vốn còn ACTIVE được hoàn và trừ
-- khỏi committed_amount nên cột đó về 0; màn quản trị cần số đã góp trước khi hoàn để vẽ tiến độ khoản đã đóng/hủy.
-- NULL với listing chưa đóng/hủy.
ALTER TABLE market_listings
    ADD COLUMN raised_amount NUMERIC(18,2);

ALTER TABLE market_listings
    ADD CONSTRAINT ck_listing_raised_amount_non_negative CHECK (raised_amount IS NULL OR raised_amount >= 0);

-- Listing đã đóng/hủy trước bản này: số đã gọi được = phần còn lại trong committed_amount (phần vốn đã khóa, nếu có)
-- cộng các phần vốn bị hủy cùng lúc đóng/hủy listing. Phần vốn đó nhận ra qua mã lý do trên lệnh: LISTING_EXPIRED (hết
-- hạn gọi vốn) hoặc LOAN_FUNDING_CANCELLED (Loan hủy đợt gọi vốn). Phần vốn nhà đầu tư tự hủy hoặc rời đi khi gọi vốn
-- bổ sung mang mã khác nên không bị cộng.
UPDATE market_listings AS listing
SET raised_amount = listing.committed_amount + COALESCE((
        SELECT SUM(commitment.amount)
        FROM investment_commitments AS commitment
                 JOIN investment_orders AS investment_order ON investment_order.id = commitment.order_id
        WHERE commitment.listing_id = listing.id
          AND commitment.status = 'CANCELLED'
          AND investment_order.rejected_reason_code IN ('LISTING_EXPIRED', 'LOAN_FUNDING_CANCELLED')
    ), 0)
WHERE listing.status IN ('CLOSED', 'CANCELLED')
  AND listing.raised_amount IS NULL;
