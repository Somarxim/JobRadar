# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-04 |
| 分支 | docs/project-design |
| 类型 | docs |

## 改动内容

- **后端技术栈整体更换**（Python/FastAPI/SQLite → Java/Spring/PostgreSQL）：
  - `architecture.md` v0.2：新增 ADR-0（Java/Spring 选型理由）；修订 ADR-1（PostgreSQL+pgvector）、ADR-3（基于 Spring AI 抽象）、ADR-4（pgvector HNSW）；目录结构改为 Maven 三模块（core/app/mcp-server）
  - `data-model.md` v0.2：DDL 全量改写 PostgreSQL 方言（IDENTITY/timestamptz/jsonb/vector(1024)/tsvector 生成列 + GIN/HNSW 索引）；company_type 枚举细化（operator/bank 独立）；新增军工所公司别名归一化说明
  - `agent-design.md` v0.2：schema 定义改 Java record；LLM 接入改 Spring AI ChatModel 路由 + `entity()` 结构化输出；MCP 改 Spring AI MCP Server Starter（@Tool）；简历叙事要点按 Java 栈重写
  - `api-design.md` v0.2：契约不变（REST 与语言无关），Base URL 端口改 8080
  - `roadmap.md` v0.2：W1–W4 任务技术词同步（Flyway/Testcontainers/Jsoup/@Scheduled）；风险表新增 Java 熟练度与 Spring AI 版本变动两条
  - `README.md`：技术栈表与选型说明更新
- **新增 `docs/target-sources.md`**：目标企业清单（军工研究所 7 集团、运营商 3 家、银行软开中心）+ 渠道类型 + 公司别名映射表 + 岗位方向词表 + W3 爬虫实施顺序
- `PRD.md` v0.2：补充「目标求职方向」段落（Agent 研发/AI 全栈/信息系统开发与维护/传统后端）

## 改动原因

1. 作者既有项目均为 Python/FastAPI/SQLite 栈，简历技术栈重复会削弱项目区分度；
2. 目标雇主（军工研究所信息部门、运营商、银行软开中心）技术栈以 Java/Spring 为主，项目栈与雇主栈对齐可直接转化为面试话题；
3. 作者明确了秋招目标企业清单与岗位方向，需固化为文档供采集层与匹配 Agent 使用。

## 影响范围

纯文档；对 v0.1 设计为破坏性替换（Python 方案废弃），无代码迁移成本（尚未编码）。API 契约层刻意保持稳定。

## 验证方式

- 交叉检查：architecture 的 ADR 编号与 data-model/agent-design 内引用一致；DDL 与 ER 图字段一致
- Spring AI 能力映射核对：ChatModel 多 Provider / entity() 结构化输出 / MCP Server Starter 均对应当前 Spring AI 公开能力（具体版本号以初始化时官方文档为准）

## 遗留问题

- Spring Boot/Spring AI 具体版本（Boot 3.5+ 或 4.x）待 W1 初始化时按当时稳定版锁定
- target-sources.md 中所有招聘 URL 标注 TBD，W3 配置爬虫时实地验证
- 作者本机需具备 Docker（pgvector 容器）；若无则用 Homebrew 安装 postgresql@16 + pgvector 替代
