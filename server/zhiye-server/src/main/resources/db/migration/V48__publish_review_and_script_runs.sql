-- 发布审核申请表（Vibe Coding 应用登记表）与无页面 Python / Node.js 脚本在线运行记录

CREATE TABLE IF NOT EXISTS publish_request (
    id                  TEXT PRIMARY KEY,
    app_id              TEXT NOT NULL REFERENCES app(id) ON DELETE CASCADE,
    workspace_id        TEXT NOT NULL,
    version_id          TEXT NOT NULL REFERENCES version(id) ON DELETE CASCADE,
    version_number      INT NOT NULL,
    access_level        TEXT NOT NULL DEFAULT 'public',
    status              TEXT NOT NULL DEFAULT 'pending',
    app_name            TEXT NOT NULL,
    department          TEXT NOT NULL,
    business_bg         TEXT NOT NULL,
    pain_points         TEXT NOT NULL DEFAULT '',
    solution            TEXT NOT NULL,
    expected_benefit    TEXT NOT NULL,
    target_users        TEXT NOT NULL,
    deploy_mode         TEXT NOT NULL,
    contacts            TEXT NOT NULL,
    expected_until      TEXT NOT NULL,
    tech_and_data       TEXT NOT NULL DEFAULT '',
    form_json           JSONB NOT NULL DEFAULT '{}'::jsonb,
    submitter_id        TEXT,
    submitter_name      TEXT,
    reviewer_actor      TEXT,
    review_comment      TEXT,
    reviewed_at         TIMESTAMPTZ,
    published_site_url  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_publish_request_status ON publish_request(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_publish_request_app ON publish_request(app_id, created_at DESC);

CREATE TABLE IF NOT EXISTS script_run (
    id                  TEXT PRIMARY KEY,
    app_id              TEXT NOT NULL REFERENCES app(id) ON DELETE CASCADE,
    workspace_id        TEXT NOT NULL,
    version_id          TEXT,
    entry_file          TEXT NOT NULL,
    runtime             TEXT NOT NULL,
    args_text           TEXT NOT NULL DEFAULT '',
    exit_code           INT,
    timed_out           BOOLEAN NOT NULL DEFAULT false,
    duration_ms         INT NOT NULL DEFAULT 0,
    stdout_preview      TEXT NOT NULL DEFAULT '',
    stderr_preview      TEXT NOT NULL DEFAULT '',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_script_run_app ON script_run(app_id, created_at DESC);
