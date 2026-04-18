CREATE TABLE dashboards (
    id         UUID        PRIMARY KEY,
    width      INTEGER     NOT NULL,
    height     INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
