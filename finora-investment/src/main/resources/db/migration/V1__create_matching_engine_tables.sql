-- Matching Engine: loan_listings, investment_orders, match_results

CREATE TABLE loan_listings (
    id              BIGSERIAL       PRIMARY KEY,
    loan_application_id BIGINT      NOT NULL,
    borrower_id     BIGINT          NOT NULL,
    amount          NUMERIC(15,2)   NOT NULL CHECK (amount > 0),
    remaining_amount NUMERIC(15,2)  NOT NULL CHECK (remaining_amount >= 0),
    term_months     INT             NOT NULL CHECK (term_months > 0),
    grade           VARCHAR(2)      NOT NULL,
    interest_rate   NUMERIC(5,2)    NOT NULL CHECK (interest_rate > 0),
    status          VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_listing_status CHECK (status IN ('OPEN','FUNDED','CANCELLED'))
);

CREATE INDEX idx_listing_status_grade ON loan_listings (status, grade);
CREATE INDEX idx_listing_borrower    ON loan_listings (borrower_id);

CREATE TABLE investment_orders (
    id              BIGSERIAL       PRIMARY KEY,
    investor_id     BIGINT          NOT NULL,
    amount          NUMERIC(15,2)   NOT NULL CHECK (amount > 0),
    remaining_amount NUMERIC(15,2)  NOT NULL CHECK (remaining_amount >= 0),
    min_rate        NUMERIC(5,2),
    max_rate        NUMERIC(5,2),
    grade_filter    VARCHAR(20),     -- CSV: "A,B,C"
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    version         INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_order_status CHECK (status IN ('PENDING','MATCHED','PARTIALLY_MATCHED','CANCELLED'))
);

CREATE INDEX idx_order_status    ON investment_orders (status);
CREATE INDEX idx_order_investor  ON investment_orders (investor_id);

CREATE TABLE match_results (
    id              BIGSERIAL       PRIMARY KEY,
    listing_id      BIGINT          NOT NULL REFERENCES loan_listings(id),
    order_id        BIGINT          NOT NULL REFERENCES investment_orders(id),
    matched_amount  NUMERIC(15,2)   NOT NULL CHECK (matched_amount > 0),
    matched_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_match_listing ON match_results (listing_id);
CREATE INDEX idx_match_order   ON match_results (order_id);
