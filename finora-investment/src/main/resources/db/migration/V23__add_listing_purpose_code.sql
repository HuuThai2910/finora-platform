-- Mã mục đích vay (enum LoanPurpose của Loan) để app vẽ biểu tượng theo mục đích trên thẻ khoản vay; cột purpose vẫn là
-- nhãn hiển thị. Listing mới nhận mã qua field bổ sung purposeCode của LoanFundingRequested.
ALTER TABLE market_listings
    ADD COLUMN purpose_code VARCHAR(40);

-- Listing cũ: Loan ghép purpose = nhãn LoanPurpose hoặc "nhãn: chi tiết", nên suy ngược mã từ nhãn. Không khớp nhãn nào
-- (nhãn đổi, dữ liệu mẫu) thì để NULL, app dùng biểu tượng chung.
UPDATE market_listings AS listing
SET purpose_code = labels.code
FROM (VALUES
          ('DEBT_CONSOLIDATION', 'Hợp nhất các khoản nợ'),
          ('CREDIT_CARD', 'Thanh toán dư nợ thẻ tín dụng'),
          ('HOME_IMPROVEMENT', 'Sửa chữa nhà'),
          ('MAJOR_PURCHASE', 'Mua sắm tài sản có giá trị'),
          ('MEDICAL', 'Chi phí y tế'),
          ('CAR', 'Mua hoặc sửa chữa xe'),
          ('SMALL_BUSINESS', 'Vốn kinh doanh nhỏ'),
          ('MOVING', 'Chi phí chuyển nơi ở'),
          ('VACATION', 'Du lịch'),
          ('EDUCATION', 'Chi phí giáo dục'),
          ('OTHER', 'Mục đích khác')
     ) AS labels(code, label)
WHERE listing.purpose_code IS NULL
  AND (listing.purpose = labels.label OR listing.purpose LIKE labels.label || ': %');
