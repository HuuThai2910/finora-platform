-- Lệnh góp vốn sơ cấp lưu phiên bản công bố rủi ro nhà đầu tư đã xác nhận và thời điểm xác nhận
-- (D6, LEGAL-DISCLOSURE-02). Lệnh cũ và lệnh Auto-Invest chưa có xác nhận riêng nên để trống cả hai cột.
ALTER TABLE investment_orders
    ADD COLUMN risk_acknowledged_version VARCHAR(50),
    ADD COLUMN risk_acknowledged_at TIMESTAMPTZ;

ALTER TABLE investment_orders
    ADD CONSTRAINT ck_investment_order_risk_acknowledgement CHECK (
        (risk_acknowledged_version IS NULL AND risk_acknowledged_at IS NULL)
        OR (risk_acknowledged_version IS NOT NULL AND risk_acknowledged_at IS NOT NULL)
    );
