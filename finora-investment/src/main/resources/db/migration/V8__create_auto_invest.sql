-- Auto-Invest (C2.1): mỗi nhà đầu tư một bộ tiêu chí; worker tự đặt lệnh khi khoản vay mới mở gọi vốn.

CREATE TABLE auto_invest_configs (
    id                BIGSERIAL     PRIMARY KEY,
    investor_id       VARCHAR(100)  NOT NULL,
    enabled           BOOLEAN       NOT NULL,
    -- CSV tên hạng, ví dụ "A,B". Bảng hạng là cấu hình động bên finora-ai nên không dùng enum.
    grades            VARCHAR(100)  NOT NULL,
    min_annual_rate   NUMERIC(7,4)  NOT NULL,
    max_term_months   INT           NOT NULL,
    amount_per_loan   NUMERIC(18,2) NOT NULL,
    -- Đặt lại mỗi lần chuyển tắt → bật; ai bật trước được khớp trước.
    enabled_at        TIMESTAMPTZ,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_auto_invest_rate CHECK (min_annual_rate >= 0 AND min_annual_rate <= 100),
    CONSTRAINT ck_auto_invest_term CHECK (max_term_months > 0),
    CONSTRAINT ck_auto_invest_amount CHECK (amount_per_loan > 0),
    CONSTRAINT ck_auto_invest_enabled_at CHECK (NOT enabled OR enabled_at IS NOT NULL)
);

CREATE UNIQUE INDEX ux_auto_invest_config_investor ON auto_invest_configs (investor_id);
CREATE INDEX ix_auto_invest_config_queue ON auto_invest_configs (enabled_at, id) WHERE enabled;

-- Nhật ký mỗi lần một cấu hình được xét cho một listing.
CREATE TABLE auto_invest_matches (
    id               BIGSERIAL     PRIMARY KEY,
    investor_id      VARCHAR(100)  NOT NULL,
    listing_id       BIGINT        NOT NULL,
    outcome          VARCHAR(20)   NOT NULL,
    reason           VARCHAR(50),
    amount           NUMERIC(18,2),
    order_reference  VARCHAR(50),
    created_at       TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_auto_invest_match_listing FOREIGN KEY (listing_id) REFERENCES market_listings (id),
    CONSTRAINT ck_auto_invest_match_outcome CHECK (outcome IN ('MATCHED', 'SKIPPED'))
);

-- Một cấu hình không bao giờ được xét hai lần cho cùng khoản vay.
CREATE UNIQUE INDEX ux_auto_invest_match_investor_listing ON auto_invest_matches (investor_id, listing_id);
CREATE INDEX ix_auto_invest_match_history ON auto_invest_matches (investor_id, created_at DESC);

ALTER TABLE market_listings ADD COLUMN auto_invest_processed_at TIMESTAMPTZ;

CREATE INDEX ix_market_listing_auto_invest_pending
    ON market_listings (funding_opened_at, id)
    WHERE status = 'OPEN' AND auto_invest_processed_at IS NULL;
