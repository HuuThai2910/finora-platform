-- Migration legacy đã được áp dụng trên Neon trước khi LN-009/LN-010 được tích hợp.
-- Giữ nguyên version/description; database cũ cần repair checksum một lần sau khi
-- đã xác minh listing_version thực tế là INTEGER NOT NULL DEFAULT 1.
ALTER TABLE market_listings
    ALTER COLUMN listing_version SET DEFAULT 1;
