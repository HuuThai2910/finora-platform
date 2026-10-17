-- Q5 (Wave 2D): bật Auto-Invest là uỷ quyền đặt lệnh thay nhà đầu tư nên phải có xác nhận công bố rủi ro như lệnh tự đặt
-- (D6, LEGAL-DISCLOSURE-02). Lưu phiên bản và thời điểm xác nhận trên quy tắc; đổi phiên bản công bố thì quy tắc tạm dừng
-- cho tới khi nhà đầu tư xác nhận lại. Quy tắc đang bật trước V17 chưa có xác nhận nên tạm dừng ngay khi bản này chạy.
-- Lệnh Auto-Invest từ nay mang phiên bản xác nhận của quy tắc (thay ghi chú "để NULL" của V16).
ALTER TABLE auto_invest_configs
    ADD COLUMN risk_acknowledged_version VARCHAR(50),
    ADD COLUMN risk_acknowledged_at TIMESTAMPTZ;

ALTER TABLE auto_invest_configs
    ADD CONSTRAINT ck_auto_invest_risk_ack CHECK ((risk_acknowledged_version IS NULL) = (risk_acknowledged_at IS NULL));
