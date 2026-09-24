-- Gọi vốn + Notes (task #10).
--
-- Tiền dùng NUMERIC(18,2) và lãi suất NUMERIC(7,4): không dùng kiểu dấu phẩy động cho tiền.
-- Mọi bảng nghiệp vụ có cột version cho khóa lạc quan của JPA, và bộ bốn cột audit
-- created_by/updated_by/created_at/updated_at.

-- Khoản vay được niêm yết trên sàn gọi vốn.
CREATE TABLE market_listings (
    id                     BIGSERIAL     PRIMARY KEY,
    loan_id                BIGINT        NOT NULL,
    contract_number        VARCHAR(50)   NOT NULL,
    product_code           VARCHAR(50)   NOT NULL,
    purpose                VARCHAR(100)  NOT NULL,
    region                 VARCHAR(100)  NOT NULL,
    credit_grade           VARCHAR(5)    NOT NULL,
    credit_score           INTEGER       NOT NULL,
    target_amount          NUMERIC(18,2) NOT NULL,
    committed_amount       NUMERIC(18,2) NOT NULL,
    annual_interest_rate   NUMERIC(7,4)  NOT NULL,
    term_months            SMALLINT      NOT NULL,
    repayment_method       VARCHAR(30)   NOT NULL,
    note_denomination      NUMERIC(18,2) NOT NULL,
    min_investment_amount  NUMERIC(18,2) NOT NULL,
    status                 VARCHAR(30)   NOT NULL,
    funding_round          INTEGER       NOT NULL,
    funding_opened_at      TIMESTAMPTZ   NOT NULL,
    funding_closes_at      TIMESTAMPTZ   NOT NULL,
    fully_funded_at        TIMESTAMPTZ,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_by             VARCHAR(100)  NOT NULL,
    updated_by             VARCHAR(100)  NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,

    -- Vốn đã gom không bao giờ được vượt mục tiêu: chốt chặn cuối cùng cho việc gom quá vốn,
    -- nằm dưới cả khóa bi quan ở tầng ứng dụng.
    CONSTRAINT ck_listing_committed_within_target
        CHECK (committed_amount >= 0 AND committed_amount <= target_amount),
    CONSTRAINT ck_listing_target_positive CHECK (target_amount > 0),
    CONSTRAINT ck_listing_denomination_positive CHECK (note_denomination > 0)
);

-- Một khoản vay chỉ được niêm yết một lần cho mỗi vòng gọi vốn.
CREATE UNIQUE INDEX ux_listing_loan_round ON market_listings (loan_id, funding_round);

-- Sàn lọc theo trạng thái rồi sắp theo hạn đóng; đây là truy vấn chính của màn danh sách.
CREATE INDEX ix_listing_status_closes ON market_listings (status, funding_closes_at);

-- Lệnh đặt vốn của nhà đầu tư.
CREATE TABLE investment_orders (
    id                      BIGSERIAL     PRIMARY KEY,
    order_reference         VARCHAR(50)   NOT NULL,
    listing_id              BIGINT        NOT NULL,
    investor_id             VARCHAR(100)  NOT NULL,
    amount                  NUMERIC(18,2) NOT NULL,
    status                  VARCHAR(30)   NOT NULL,
    idempotency_key         VARCHAR(150)  NOT NULL,
    request_hash            VARCHAR(64)   NOT NULL,
    payment_hold_reference  VARCHAR(100),
    payment_hold_at         TIMESTAMPTZ,
    payment_released_at     TIMESTAMPTZ,
    rejected_reason_code    VARCHAR(50),
    rejected_reason_detail  VARCHAR(500),
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_by              VARCHAR(100)  NOT NULL,
    updated_by              VARCHAR(100)  NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_order_listing FOREIGN KEY (listing_id) REFERENCES market_listings (id),
    CONSTRAINT ck_order_amount_positive CHECK (amount > 0)
);

CREATE UNIQUE INDEX ux_order_reference ON investment_orders (order_reference);

-- Chống bấm hai lần: cùng một nhà đầu tư gửi lại cùng khóa thì không sinh lệnh thứ hai.
-- Khóa theo cả investor_id để khóa của người này không chặn người khác.
CREATE UNIQUE INDEX ux_order_idempotency ON investment_orders (investor_id, idempotency_key);

CREATE INDEX ix_order_listing_status ON investment_orders (listing_id, status);

-- Phần vốn đã cam kết, sinh ra khi lệnh được xác nhận.
CREATE TABLE investment_commitments (
    id                      BIGSERIAL     PRIMARY KEY,
    order_id                BIGINT        NOT NULL,
    listing_id              BIGINT        NOT NULL,
    investor_id             VARCHAR(100)  NOT NULL,
    amount                  NUMERIC(18,2) NOT NULL,
    note_count              INTEGER       NOT NULL,
    note_denomination       NUMERIC(18,2) NOT NULL,
    share_percent           NUMERIC(9,6)  NOT NULL,
    status                  VARCHAR(30)   NOT NULL,
    payment_hold_reference  VARCHAR(100)  NOT NULL,
    finalized_at            TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_by              VARCHAR(100)  NOT NULL,
    updated_by              VARCHAR(100)  NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_commitment_order FOREIGN KEY (order_id) REFERENCES investment_orders (id),
    CONSTRAINT fk_commitment_listing FOREIGN KEY (listing_id) REFERENCES market_listings (id),
    CONSTRAINT ck_commitment_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_commitment_note_count_positive CHECK (note_count > 0)
);

-- Một lệnh sinh đúng một phần vốn; chạy lại việc xác nhận không tạo bản ghi thứ hai.
CREATE UNIQUE INDEX ux_commitment_order ON investment_commitments (order_id);

CREATE INDEX ix_commitment_listing_status ON investment_commitments (listing_id, status);
CREATE INDEX ix_commitment_investor ON investment_commitments (investor_id);

-- Note: phần vốn đã khóa được xé nhỏ theo mệnh giá cố định.
CREATE TABLE investment_notes (
    id                     BIGSERIAL     PRIMARY KEY,
    note_number            VARCHAR(50)   NOT NULL,
    commitment_id          BIGINT        NOT NULL,
    listing_id             BIGINT        NOT NULL,
    loan_id                BIGINT        NOT NULL,
    investor_id            VARCHAR(100)  NOT NULL,
    principal_amount       NUMERIC(18,2) NOT NULL,
    outstanding_principal  NUMERIC(18,2) NOT NULL,
    principal_repaid       NUMERIC(18,2) NOT NULL,
    interest_received      NUMERIC(18,2) NOT NULL,
    annual_interest_rate   NUMERIC(7,4)  NOT NULL,
    term_months            SMALLINT      NOT NULL,
    sequence_number        INTEGER       NOT NULL,
    status                 VARCHAR(30)   NOT NULL,
    issued_at              TIMESTAMPTZ   NOT NULL,
    closed_at              TIMESTAMPTZ,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_by             VARCHAR(100)  NOT NULL,
    updated_by             VARCHAR(100)  NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_note_commitment FOREIGN KEY (commitment_id) REFERENCES investment_commitments (id),
    CONSTRAINT fk_note_listing FOREIGN KEY (listing_id) REFERENCES market_listings (id),

    -- Dư nợ không bao giờ âm và không vượt mệnh giá ban đầu. Nếu service thanh toán gửi sai
    -- số, giao dịch dừng tại đây thay vì âm thầm ghi một Note có dư nợ âm.
    CONSTRAINT ck_note_outstanding_range
        CHECK (outstanding_principal >= 0 AND outstanding_principal <= principal_amount),
    CONSTRAINT ck_note_repaid_non_negative CHECK (principal_repaid >= 0),
    CONSTRAINT ck_note_interest_non_negative CHECK (interest_received >= 0),

    -- Gốc còn lại cộng gốc đã trả luôn bằng mệnh giá: bất biến của sổ sách từng Note.
    CONSTRAINT ck_note_principal_balanced
        CHECK (outstanding_principal + principal_repaid = principal_amount)
);

-- Mã Note sinh theo công thức cố định từ khoản vay, phần vốn và số thứ tự, nên chạy lại
-- việc phát hành không nhân đôi Note.
CREATE UNIQUE INDEX ux_note_number ON investment_notes (note_number);
CREATE UNIQUE INDEX ux_note_commitment_sequence ON investment_notes (commitment_id, sequence_number);

CREATE INDEX ix_note_commitment ON investment_notes (commitment_id);
CREATE INDEX ix_note_investor_status ON investment_notes (investor_id, status);
CREATE INDEX ix_note_loan ON investment_notes (loan_id);

-- Tham số gọi vốn của sàn: đúng một bản ghi, id cố định bằng 1.
CREATE TABLE funding_settings (
    id                     SMALLINT      PRIMARY KEY,
    note_denomination      NUMERIC(18,2) NOT NULL,
    min_investment_amount  NUMERIC(18,2) NOT NULL,
    funding_days           SMALLINT      NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    updated_by             VARCHAR(100),

    CONSTRAINT ck_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_settings_denomination_positive CHECK (note_denomination >= 1000),
    CONSTRAINT ck_settings_minimum_positive CHECK (min_investment_amount >= 1000),
    CONSTRAINT ck_settings_days_range CHECK (funding_days BETWEEN 1 AND 90)
);

-- Giá trị khởi tạo: mệnh giá 1 triệu, tối thiểu một Note, gọi vốn 14 ngày.
-- Quản trị đổi được qua màn tham số sàn; đây chỉ là mốc để service chạy được ngay.
INSERT INTO funding_settings (id, note_denomination, min_investment_amount, funding_days, updated_at, updated_by)
VALUES (1, 1000000.00, 1000000.00, 14, NOW(), 'SYSTEM');
