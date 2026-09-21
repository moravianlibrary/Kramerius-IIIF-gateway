-- Applied at startup, idempotently. Small enough that a migration tool would be more moving parts than
-- schema; when that stops being true, this is the file that becomes migration 001.

CREATE TABLE IF NOT EXISTS iiif_document_sizes (
    library     text        NOT NULL,
    pid         text        NOT NULL,
    -- version byte, then per page: tag | uuid(16) or len+utf8 | w int32 | h int32 | api uint8.
    -- One row per DOCUMENT, not per page: ~1.0 GB at MZK against ~7.6 GB row-per-page, and it replaces
    -- the batched `pid IN (?, ...)` read that pgjdbc's lack of bytea-array binding would force.
    pages       bytea       NOT NULL,
    api_version smallint    NOT NULL DEFAULT 1,
    fetched_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (library, pid)
);

CREATE TABLE IF NOT EXISTS iiif_dimension_job (
    library      text        NOT NULL,
    pid          text        NOT NULL,
    state        smallint    NOT NULL DEFAULT 0,   -- 0 queued, 1 running, 2 failed
    requested_at timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    attempts     int         NOT NULL DEFAULT 0,
    last_error   text,
    PRIMARY KEY (library, pid)                     -- collapses duplicate enqueues transactionally
);

CREATE INDEX IF NOT EXISTS iiif_dimension_job_queued
    ON iiif_dimension_job (requested_at) WHERE state = 0;

CREATE TABLE IF NOT EXISTS iiif_library_sync (
    library     text PRIMARY KEY,
    state       smallint    NOT NULL DEFAULT 0,    -- 0 disabled, 1 running, 2 paused
    cursor      text,                              -- Solr cursorMark, so a 22-day walk resumes
    -- Greatest `modified` processed. Seeded at epoch this is a full backfill and seeded at now it
    -- tracks only new material: one loop, one state row, no second mechanism.
    watermark   timestamptz NOT NULL DEFAULT to_timestamp(0),
    seen        bigint      NOT NULL DEFAULT 0,
    last_run_at timestamptz,
    last_error  text
);
