-- G-POLICY-W2 (B17): biểu phí trả trước/tất toán mà hợp đồng của khoản vay đã công bố, nhận một lần từ LoanDisbursed.
-- Báo giá tính theo phiên bản này thay vì cấu hình hiện hành, nên đổi biểu phí không áp ngược cho khoản vay đã ký.
-- Tài khoản mở trước migration để NULL: Payment không đọc được hợp đồng của Loan, và hợp đồng LOAN_TERMS_V1 không ghi
-- phí trả trước, nên NULL được tính như không công bố biểu phí (phí 0). Không cần index: chỉ đọc theo khoản vay.
ALTER TABLE payment_loan_accounts
    ADD COLUMN early_settlement_policy_version VARCHAR(50);
