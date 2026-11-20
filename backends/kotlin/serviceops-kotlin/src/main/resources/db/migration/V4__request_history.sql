CREATE TABLE request_history (
    request_id uuid NOT NULL REFERENCES requests(id),
    version bigint NOT NULL,
    actor_id uuid NOT NULL,
    action varchar(24) NOT NULL,
    status varchar(20) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (request_id, version)
);
