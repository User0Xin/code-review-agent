CREATE TABLE IF NOT EXISTS review_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    merge_request_iid INTEGER NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED')),
    reason TEXT,
    occurred_at TEXT NOT NULL,
    spec_skipped INTEGER,
    spec_document_paths TEXT,
    standard_document_paths TEXT,
    summary_error TEXT,
    trigger_reason TEXT,
    head_sha TEXT,
    failure_note_id INTEGER,
    note_error TEXT,
    failure_detail TEXT
);

CREATE INDEX IF NOT EXISTS idx_review_record_merge_request
    ON review_record (project_id, merge_request_iid, id);

CREATE TABLE IF NOT EXISTS finding (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    merge_request_iid INTEGER NOT NULL,
    discussion_id INTEGER NOT NULL,
    path TEXT,
    new_line INTEGER,
    old_line INTEGER,
    category TEXT,
    citation TEXT,
    comment_body TEXT NOT NULL,
    head_sha TEXT NOT NULL,
    revision_id INTEGER,
    false_positive INTEGER,
    false_positive_reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_finding_merge_request
    ON finding (project_id, merge_request_iid, id);

CREATE TABLE IF NOT EXISTS review_failure_note (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    merge_request_iid INTEGER NOT NULL,
    note_id INTEGER NOT NULL,
    UNIQUE (project_id, merge_request_iid)
);

CREATE TABLE IF NOT EXISTS coding_standard_revision (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    source_branch TEXT NOT NULL UNIQUE,
    target_branch TEXT NOT NULL,
    base_ref TEXT,
    merge_request_iid INTEGER,
    state TEXT NOT NULL CHECK(state IN ('PREPARING', 'OPEN', 'CLOSED', 'MERGED')),
    files_json TEXT NOT NULL,
    finding_ids TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    error TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_one_active_revision
    ON coding_standard_revision(project_id) WHERE state IN ('PREPARING', 'OPEN');

CREATE TABLE IF NOT EXISTS revision_checkpoint (
    project_id INTEGER PRIMARY KEY,
    last_finding_id INTEGER NOT NULL DEFAULT 0,
    retry_pending INTEGER NOT NULL DEFAULT 0,
    retry_finding_ids TEXT
);
