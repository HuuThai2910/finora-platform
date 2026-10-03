-- Thanh toán một phần từ khoản giữ (hợp đồng docs/integrations/INVESTMENT-PAYMENT-ORDER-BOOK.md).
--
-- Lệnh mua trên sổ lệnh Notes của finora-investment giữ tiền một lần lúc đặt rồi khớp từng phần
-- với nhiều người bán. Mỗi lần khớp chuyển một phần tiền giữ sang người bán (HELD người mua →
-- AVAILABLE người bán + phí nền tảng); phần còn lại vẫn giữ tới khi lệnh kết thúc và được nhả.
-- Khoản giữ của luồng gọi vốn sơ cấp không thanh toán một phần nên giữ nguyên 0.
ALTER TABLE payment_holds
    ADD COLUMN settled_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

-- Không bao giờ chuyển đi quá số đã giữ.
ALTER TABLE payment_holds
    ADD CONSTRAINT ck_payment_holds_settled_amount
        CHECK (settled_amount >= 0 AND settled_amount <= amount);
