-- Chợ thứ cấp Notes (task E1, plan INV-E1).
--
-- Nhà đầu tư đang giữ Note treo bán để lấy tiền trước hạn; nhà đầu tư khác mua lại và nhận
-- quyền hưởng gốc lãi còn lại. Người vay không liên quan: họ vẫn trả đúng lịch, chỉ đích
-- đến của tiền đổi sang người mua.

-- Tin đăng bán một Note trên bảng tin.
CREATE TABLE note_listings (
    id                  BIGSERIAL     PRIMARY KEY,
    listing_reference   VARCHAR(50)   NOT NULL,
    note_id             BIGINT        NOT NULL,
    seller_id           VARCHAR(100)  NOT NULL,

    -- Giá người bán tự đặt. Trần là dư nợ gốc còn lại của Note tại thời điểm đăng, để người
    -- mua luôn trả không quá phần gốc mình sẽ nhận về (plan INV-E1 mục 3).
    asking_price        NUMERIC(18,2) NOT NULL,

    -- Dư nợ gốc lúc đăng bán, chụp lại để so sánh về sau. Dư nợ có thể giảm trong lúc tin
    -- đang treo nếu người vay trả nợ, nên trần được kiểm lại tại thời điểm mua chứ không
    -- tin vào con số này.
    outstanding_at_listing NUMERIC(18,2) NOT NULL,

    -- Note đang nợ xấu lúc đăng bán. Cho phép bán nhưng giao diện phải cảnh báo ở cả màn
    -- đăng bán và màn xác nhận mua (plan INV-E1 mục 8.2).
    defaulted_at_listing   BOOLEAN       NOT NULL DEFAULT FALSE,

    status              VARCHAR(30)   NOT NULL,

    -- Người mua và thời điểm khớp, chỉ có giá trị khi tin đã bán.
    buyer_id            VARCHAR(100),
    sold_at             TIMESTAMPTZ,

    -- Số tiền thực tế của giao dịch, chụp lại tại thời điểm khớp thay vì tính lại từ
    -- asking_price: mức phí là policy có thể đổi, còn bản ghi lịch sử thì không được đổi theo.
    sold_price          NUMERIC(18,2),
    platform_fee        NUMERIC(18,2),
    seller_proceeds     NUMERIC(18,2),
    payment_reference   VARCHAR(100),

    cancelled_at        TIMESTAMPTZ,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_by          VARCHAR(100)  NOT NULL,
    updated_by          VARCHAR(100)  NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_note_listing_note FOREIGN KEY (note_id) REFERENCES investment_notes (id),

    CONSTRAINT ck_note_listing_price_positive CHECK (asking_price > 0),

    -- Trần giá tại thời điểm đăng. Kiểm lại lúc mua vì dư nợ có thể đã giảm.
    CONSTRAINT ck_note_listing_price_within_outstanding
        CHECK (asking_price <= outstanding_at_listing),

    -- Người bán không tự mua Note của mình: mua lại của chính mình không đổi quyền sở hữu
    -- nhưng vẫn sinh giao dịch tiền và bản ghi lịch sử, tức là đường làm giả khối lượng.
    CONSTRAINT ck_note_listing_buyer_not_seller
        CHECK (buyer_id IS NULL OR buyer_id <> seller_id),

    -- Tin đã bán phải có đủ thông tin giao dịch; tin chưa bán không được có thông tin đó.
    CONSTRAINT ck_note_listing_sold_fields
        CHECK (
            (status = 'SOLD'
                AND buyer_id IS NOT NULL AND sold_at IS NOT NULL
                AND sold_price IS NOT NULL AND platform_fee IS NOT NULL
                AND seller_proceeds IS NOT NULL AND payment_reference IS NOT NULL)
            OR
            (status <> 'SOLD'
                AND buyer_id IS NULL AND sold_at IS NULL
                AND sold_price IS NULL AND platform_fee IS NULL
                AND seller_proceeds IS NULL AND payment_reference IS NULL)
        ),

    -- Tiền người bán nhận cộng phí nền tảng luôn bằng giá bán: bất biến sổ sách của giao dịch.
    CONSTRAINT ck_note_listing_fee_balanced
        CHECK (
            sold_price IS NULL
            OR (platform_fee >= 0 AND seller_proceeds >= 0
                AND seller_proceeds + platform_fee = sold_price)
        ),

    CONSTRAINT ck_note_listing_cancelled_field
        CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_note_listing_reference ON note_listings (listing_reference);

-- Một Note chỉ được treo bán ở đúng một tin đang mở. Index một phần: tin đã bán hoặc đã huỷ
-- không chặn việc treo lại Note đó (người mua sau này có thể bán tiếp).
CREATE UNIQUE INDEX ux_note_listing_open_per_note
    ON note_listings (note_id)
    WHERE status = 'OPEN';

-- Bảng tin: lọc tin đang mở rồi sắp theo thời điểm đăng.
CREATE INDEX ix_note_listing_status_created ON note_listings (status, created_at DESC);

-- Nhà đầu tư xem tin mình đã đăng và Note mình đã mua.
CREATE INDEX ix_note_listing_seller ON note_listings (seller_id, status);
CREATE INDEX ix_note_listing_buyer ON note_listings (buyer_id) WHERE buyer_id IS NOT NULL;

-- Lịch sử chuyển nhượng của từng Note: ai bán cho ai, giá nào, lúc nào.
--
-- Tách khỏi note_listings vì một Note có thể được chuyển nhượng nhiều lần, và bản ghi lịch sử
-- phải bất biến — tin đăng bán còn đổi trạng thái được, còn giao dịch đã xảy ra thì không.
CREATE TABLE note_transfers (
    id                  BIGSERIAL     PRIMARY KEY,
    note_id             BIGINT        NOT NULL,
    note_listing_id     BIGINT        NOT NULL,
    seller_id           VARCHAR(100)  NOT NULL,
    buyer_id            VARCHAR(100)  NOT NULL,
    price               NUMERIC(18,2) NOT NULL,
    platform_fee        NUMERIC(18,2) NOT NULL,
    seller_proceeds     NUMERIC(18,2) NOT NULL,

    -- Dư nợ gốc tại thời điểm chuyển nhượng: cần để đối soát về sau xem giá có hợp lệ so với
    -- trần hay không, mà không phải suy lại từ dòng tiền đã chạy tiếp sau đó.
    outstanding_at_transfer NUMERIC(18,2) NOT NULL,

    -- Note đang nợ xấu lúc chuyển nhượng, lưu lại để đối soát: người mua đã được cảnh báo chưa.
    defaulted_at_transfer   BOOLEAN       NOT NULL DEFAULT FALSE,

    payment_reference   VARCHAR(100)  NOT NULL,
    transferred_at      TIMESTAMPTZ   NOT NULL,
    created_by          VARCHAR(100)  NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_note_transfer_note FOREIGN KEY (note_id) REFERENCES investment_notes (id),
    CONSTRAINT fk_note_transfer_listing FOREIGN KEY (note_listing_id) REFERENCES note_listings (id),

    CONSTRAINT ck_note_transfer_price_positive CHECK (price > 0),
    CONSTRAINT ck_note_transfer_parties_differ CHECK (buyer_id <> seller_id),
    CONSTRAINT ck_note_transfer_fee_balanced
        CHECK (platform_fee >= 0 AND seller_proceeds >= 0
               AND seller_proceeds + platform_fee = price),
    CONSTRAINT ck_note_transfer_price_within_outstanding
        CHECK (price <= outstanding_at_transfer)
);

-- Một tin đăng bán chỉ sinh đúng một lần chuyển nhượng: chạy lại việc khớp sau lỗi mạng
-- không tạo bản ghi thứ hai.
CREATE UNIQUE INDEX ux_note_transfer_listing ON note_transfers (note_listing_id);

-- Cùng một mã giao dịch thanh toán chỉ ghi nhận một lần.
CREATE UNIQUE INDEX ux_note_transfer_payment ON note_transfers (payment_reference);

-- Lịch sử theo Note, mới nhất trước.
CREATE INDEX ix_note_transfer_note ON note_transfers (note_id, transferred_at DESC);
