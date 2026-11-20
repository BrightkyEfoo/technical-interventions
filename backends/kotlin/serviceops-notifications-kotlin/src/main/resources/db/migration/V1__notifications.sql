CREATE TABLE notifications (
    event_id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    request_version bigint NOT NULL,
    recipient_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (request_id, request_version, recipient_id)
);
CREATE INDEX notifications_recipient_idx ON notifications(recipient_id, created_at DESC, event_id);
