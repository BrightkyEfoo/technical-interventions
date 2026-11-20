CREATE TABLE request_receipts (
    scope text NOT NULL,
    command_key varchar(128) NOT NULL,
    fingerprint text NOT NULL,
    response jsonb NOT NULL,
    PRIMARY KEY (scope, command_key)
);

CREATE TABLE request_comments (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES requests(id),
    author_id uuid NOT NULL,
    body varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE request_attachments (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES requests(id),
    media_type varchar(30) NOT NULL,
    content bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE request_outbox (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES requests(id),
    request_version bigint NOT NULL,
    recipient_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(200)
);

CREATE INDEX request_outbox_pending_idx ON request_outbox(next_attempt_at) WHERE published_at IS NULL;
