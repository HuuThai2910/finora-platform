-- Mã hiển thị dễ đọc do Loan sinh (hồ sơ HS-yyyyMMdd-XXXX, hợp đồng HĐ-yyyyMMdd-XXXX), nhận qua field bổ sung của
-- LoanFundingRequested/InvestorSignatureRequested/LoanContractActivated/LoanDisbursed để app hiện cho nhà đầu tư.
-- application_number/contract_number vẫn là khóa đối chiếu với Loan. Listing tạo trước bản này để NULL cho tới khi
-- nhận event mới có mã (Investment không đọc database của Loan để backfill); app khi đó hiện dòng tóm tắt, không hiện mã.
ALTER TABLE market_listings
    ADD COLUMN application_code VARCHAR(20),
    ADD COLUMN contract_code VARCHAR(20);
