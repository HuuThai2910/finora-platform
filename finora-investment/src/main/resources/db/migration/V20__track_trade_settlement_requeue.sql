-- =====================================================================
-- V20 (web quản trị đợt 2): nhân viên đưa lần khớp "Cần đối soát" (thanh toán FAILED) về hàng chờ
-- thanh toán lại. Payment idempotent theo trade_reference nên gửi lại không chuyển tiền hai lần.
-- Lưu người và thời điểm đưa lại gần nhất để đối chiếu; cột mới nullable/default, bản cũ vẫn chạy.
-- =====================================================================

ALTER TABLE order_book_trades
    ADD COLUMN settlement_requeue_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN settlement_requeued_by   VARCHAR(100),
    ADD COLUMN settlement_requeued_at   TIMESTAMPTZ;
