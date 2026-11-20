CREATE TABLE requests (
    id uuid PRIMARY KEY,
    title varchar(120) NOT NULL,
    status text NOT NULL,
    assignee_id uuid,
    CONSTRAINT requests_status_check
        CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'))
);
