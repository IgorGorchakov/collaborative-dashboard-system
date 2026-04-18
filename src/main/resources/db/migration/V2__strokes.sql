CREATE TABLE strokes (
    id           BIGSERIAL   PRIMARY KEY,
    dashboard_id UUID        NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    ordinal      BIGINT      NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_strokes_dashboard_ordinal UNIQUE (dashboard_id, ordinal)
);

CREATE INDEX idx_strokes_dashboard_ordinal ON strokes (dashboard_id, ordinal);

-- Per-dashboard ordinal counter. Incremented atomically by an UPSERT ... RETURNING
-- in the write path, so ordinals are gap-free and monotonic per dashboard even
-- under concurrent writes.
CREATE TABLE dashboard_stroke_counter (
    dashboard_id UUID   PRIMARY KEY REFERENCES dashboards(id) ON DELETE CASCADE,
    last_ordinal BIGINT NOT NULL DEFAULT 0
);
