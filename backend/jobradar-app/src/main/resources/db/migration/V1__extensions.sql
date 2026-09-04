-- V1: 数据库扩展。与建表分离的原因：扩展安装需要 superuser 权限，
-- 若未来迁移到托管 PG（权限受限），本文件可由 DBA 单独执行。
CREATE EXTENSION IF NOT EXISTS vector;    -- pgvector：向量类型与 HNSW 索引（ADR-4）
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- trigram：公司名/岗位名模糊匹配（data-model.md §2.3）
