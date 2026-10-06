CREATE TABLE in_app_notifications (
    id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL,
    recipient_id VARCHAR(100) NOT NULL,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    business_reference VARCHAR(100),
    external_push_required BOOLEAN NOT NULL DEFAULT FALSE,
    occurred_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_delivery UNIQUE (source_event_id, recipient_id, type)
);

CREATE INDEX idx_notification_recipient_time
    ON in_app_notifications (recipient_id, occurred_at DESC, id);
CREATE INDEX idx_notification_recipient_unread
    ON in_app_notifications (recipient_id, read_at, occurred_at DESC);
