-- Auto-Invest configurations

CREATE TABLE auto_invest_configs (
    id              BIGSERIAL       PRIMARY KEY,
    investor_id     BIGINT          NOT NULL UNIQUE,
    grade_filter    VARCHAR(20),
    min_rate        NUMERIC(5,2),
    max_rate        NUMERIC(5,2),
    min_term        INT,
    max_term        INT,
    amount_per_note NUMERIC(15,2)   NOT NULL CHECK (amount_per_note >= 1000000),
    total_budget    NUMERIC(15,2)   NOT NULL CHECK (total_budget > 0),
    remaining_budget NUMERIC(15,2)  NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_autoinvest_active ON auto_invest_configs (is_active) WHERE is_active = true;
