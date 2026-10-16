CREATE TABLE IF NOT EXISTS review_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    merge_request_iid INTEGER NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED')),
    reason TEXT,
    occurred_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_review_record_merge_request
    ON review_record (project_id, merge_request_iid, id);

CREATE TABLE IF NOT EXISTS review_failure_note (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    merge_request_iid INTEGER NOT NULL,
    note_id INTEGER NOT NULL,
    UNIQUE (project_id, merge_request_iid)
);
