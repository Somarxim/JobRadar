-- 简历软删支持：有 match_reports 外键关联的简历不宜物理删除，archived 标记实现软删
ALTER TABLE resumes
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_resumes_archived ON resumes(archived) WHERE archived = FALSE;
