CREATE TABLE IF NOT EXISTS review_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    merge_request_iid INTEGER NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED')),
    reason TEXT,
    occurred_at TEXT NOT NULL,
    spec_skipped INTEGER,
    spec_document_paths TEXT,
    standard_document_paths TEXT
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
    head_sha TEXT NOT NULL
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
