-- V2: 初始 schema。设计依据：docs/data-model.md v0.2（ER 图与设计说明以该文档为准）

CREATE TABLE companies (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT NOT NULL UNIQUE,
    company_type TEXT NOT NULL DEFAULT 'other'
        CHECK (company_type IN ('internet','soe_central','soe_local','institute','operator','bank','foreign','other')),
    tier         TEXT NOT NULL DEFAULT 'none'
        CHECK (tier IN ('dream','target','backup','none')),
    homepage     TEXT,
    notes        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE jobs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id      BIGINT NOT NULL REFERENCES companies(id),
    title           TEXT NOT NULL,
    jd_text         TEXT NOT NULL DEFAULT '',
    jd_summary      TEXT,
    requirements    JSONB,                  -- JobRequirements schema，见 docs/agent-design.md §3.2
    city            TEXT,
    salary_range    TEXT,
    source_platform TEXT NOT NULL,          -- boss|niuke|guopin|official|extension|manual
    source_url      TEXT,
    dedupe_hash     TEXT NOT NULL UNIQUE,
    publish_date    DATE,
    deadline        DATE,
    embedding       vector(1024),           -- bge-m3，见 data-model.md §2.4
    search_text     TEXT NOT NULL DEFAULT '',  -- jieba 分词拼接（应用层写入）
    search_vector   TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    meta            JSONB,                  -- also_seen_on 等
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_jobs_company   ON jobs(company_id);
CREATE INDEX idx_jobs_deadline  ON jobs(deadline) WHERE deadline IS NOT NULL AND is_active;
CREATE INDEX idx_jobs_created   ON jobs(created_at DESC);
CREATE INDEX idx_jobs_fts       ON jobs USING GIN (search_vector);
CREATE INDEX idx_jobs_title_trgm    ON jobs USING GIN (title gin_trgm_ops);
CREATE INDEX idx_jobs_embedding ON jobs USING hnsw (embedding vector_cosine_ops);

CREATE TABLE applications (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id         BIGINT NOT NULL UNIQUE REFERENCES jobs(id),
    stage          TEXT NOT NULL DEFAULT 'collected'
        CHECK (stage IN ('collected','planned','applied','written_test','interview','offer','rejected','withdrawn')),
    channel        TEXT,                    -- official|boss|niuke|email|referral|campus_talk
    priority       INT NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    planned_at     DATE,
    next_action    TEXT,
    next_action_at TIMESTAMPTZ,
    notes          TEXT,
    applied_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_applications_stage       ON applications(stage);
CREATE INDEX idx_applications_next_action ON applications(next_action_at) WHERE next_action_at IS NOT NULL;

CREATE TABLE application_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id),
    from_stage     TEXT,
    to_stage       TEXT NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_app ON application_events(application_id, created_at);

CREATE TABLE resumes (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT NOT NULL,
    file_path  TEXT NOT NULL,
    parsed     JSONB,                       -- ResumeProfile schema，见 docs/agent-design.md §3.1
    embedding  vector(1024),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE match_reports (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id         BIGINT NOT NULL REFERENCES jobs(id),
    resume_id      BIGINT NOT NULL REFERENCES resumes(id),
    score_total    INT NOT NULL CHECK (score_total BETWEEN 0 AND 100),
    detail         JSONB NOT NULL,          -- MatchDetail schema，见 docs/agent-design.md §3.3
    model_used     TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_match_job ON match_reports(job_id, created_at DESC);

CREATE TABLE recommendations (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id          BIGINT NOT NULL REFERENCES jobs(id),
    match_report_id BIGINT REFERENCES match_reports(id),
    rec_date        DATE NOT NULL,
    rank            INT NOT NULL,
    reason          TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','accepted','ignored')),
    feedback_tag    TEXT,
    UNIQUE (job_id, rec_date)
);

CREATE TABLE crawl_sources (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    url             TEXT NOT NULL,
    parser          TEXT NOT NULL,
    category        TEXT NOT NULL CHECK (category IN ('institute_military','operator','bank','soe_other','community')),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    last_crawled_at TIMESTAMPTZ,
    meta            JSONB
);

CREATE TABLE weekly_goals (
    week_start   DATE PRIMARY KEY,
    target_count INT NOT NULL DEFAULT 10,
    note         TEXT
);

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value JSONB
);
