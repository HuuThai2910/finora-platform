-- FUNDING-REOPEN-001: nhà đầu tư chưa ký rời hợp đồng thì Loan phát LoanFundingReopened; Investment hủy đúng phần vốn
-- đó, mở lại listing để gọi phần còn thiếu và giữ nguyên các phần vốn còn lại.

-- Projection hợp đồng trên listing có thêm bước chờ gọi vốn bổ sung.
ALTER TABLE market_listings DROP CONSTRAINT ck_market_listing_contract_status;
ALTER TABLE market_listings ADD CONSTRAINT ck_market_listing_contract_status CHECK (
    contract_status IS NULL OR contract_status IN (
        'PENDING_LENDER_SIGNATURES', 'PENDING_REFUNDING', 'PENDING_BORROWER_SIGNATURE', 'EFFECTIVE'
    )
);

-- Thời điểm phần vốn được đưa vào allocation đã khóa để lập hợp đồng. Khi listing mở lại, phần vốn đã gắn hợp đồng
-- không được hủy trực tiếp qua lệnh (nhà đầu tư đã ký hoặc đang chờ ký trên văn bản sẽ phát hành lại); phần vốn mới
-- góp trong đợt bổ sung (NULL) vẫn hủy được như listing mở bình thường.
ALTER TABLE investment_commitments
    ADD COLUMN contract_bound_at TIMESTAMPTZ;
