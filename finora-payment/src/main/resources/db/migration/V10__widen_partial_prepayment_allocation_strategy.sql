-- Tên chiến lược lưu cả strategy Fineract và future-installment rule để quote là snapshot tự đủ.
-- "advanced-payment-allocation-strategy/REAMORTIZATION" dài hơn giới hạn V9 ban đầu.
ALTER TABLE payment_partial_prepayment_quotes
    ALTER COLUMN allocation_strategy TYPE VARCHAR(100);
