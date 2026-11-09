-- Mã hiển thị dễ đọc do Loan sinh (hồ sơ HS-yyyyMMdd-XXXX, hợp đồng HĐ-yyyyMMdd-XXXX), nhận từ field bổ sung của
-- LoanFundingRequested/InvestorSignatureRequested/LoanContractActivated/LoanDisbursed. Câu thông báo ưu tiên mã này hơn
-- mã tra cứu LA-/LC-; khoản vay chỉ có event cũ để NULL và giữ nhãn như trước.
ALTER TABLE notification_loan_references
    ADD COLUMN application_code VARCHAR(20),
    ADD COLUMN contract_code VARCHAR(20);
