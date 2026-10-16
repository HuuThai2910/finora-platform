-- G-POLICY-W2 (B17): biểu phí trả trước mà văn bản hợp đồng đã công bố. LoanDisbursed mang giá trị này sang Payment để
-- phí trả trước/tất toán tính theo đúng điều khoản người vay đã ký, không theo cấu hình hiện hành.
ALTER TABLE loan_contracts
    ADD COLUMN early_settlement_policy_version VARCHAR(50);

-- LOAN_TERMS_V2 (từ commit e5ac375, 2026-10-16) luôn in điều 4 với biểu phí AGRIBANK_REFERENCE_FINORA_2026_V2.
-- Hợp đồng LOAN_TERMS_V1 không có điều khoản phí trả trước nên để NULL; không sửa văn bản, hash hay trạng thái hợp đồng.
UPDATE loan_contracts
SET early_settlement_policy_version = 'AGRIBANK_REFERENCE_FINORA_2026_V2'
WHERE terms_version = 'LOAN_TERMS_V2'
  AND early_settlement_policy_version IS NULL;
