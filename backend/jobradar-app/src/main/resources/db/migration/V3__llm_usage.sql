-- V3: LLM 调用记账表（W2）。
-- 设计依据：docs/roadmap.md 风险登记「LLM 调用成本超预期 → 成本闸」。
-- 每次调用（成功/失败）都落一行，后续成本闸与月度报表基于此表聚合。

CREATE TABLE llm_usage (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task              TEXT NOT NULL,              -- jd_parse | poster_parse | match_assess | ...
    model             TEXT NOT NULL,              -- deepseek-chat | qwen-vl-plus | ...
    prompt_tokens     INT,
    completion_tokens INT,
    total_tokens      INT,
    success           BOOLEAN NOT NULL,
    error             TEXT,                       -- 失败时的异常摘要（截断）
    latency_ms        INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 按时间聚合账单是主场景
CREATE INDEX idx_llm_usage_created_at ON llm_usage (created_at);
