ALTER TABLE requests
    ADD COLUMN owner_id uuid,
    ADD COLUMN priority text NOT NULL DEFAULT 'NORMAL'
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN report varchar(2000);

CREATE INDEX requests_list_idx ON requests (created_at DESC, id);
CREATE INDEX requests_owner_idx ON requests (owner_id);
CREATE INDEX requests_assignee_idx ON requests (assignee_id);
