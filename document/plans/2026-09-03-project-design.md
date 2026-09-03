# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-03 |
| 分支 | docs/project-design |
| 类型 | docs |

## 改动内容

- 初始化 JobRadar 项目仓库（git init，main 分支 + 初始 README/.gitignore）
- 新增完整设计文档（docs/）：
  - `PRD.md`：背景痛点、双重目标（秋招自用 + 简历项目）、5 条核心用户流程、P0/P1/P2 功能分级、非目标
  - `architecture.md`：系统架构图、monorepo 目录结构、技术选型、6 条 ADR（SQLite / 插件为主爬虫为辅 / LLM 抽象 / 内存向量检索 / MCP 独立进程 / 调度器内嵌）
  - `data-model.md`：ER 图、9 张表 DDL、FTS5 中文检索方案、向量存储方案、投递状态机
  - `api-design.md`：REST 端点总览（7 模块 25+ 端点）、关键端点请求/响应示例、统一约定
  - `agent-design.md`：LLM Provider 抽象与任务路由、ResumeProfile/JobRequirements/MatchDetail 三 schema、双阶段匹配 Agent、每日推荐管线、MCP Server（10 tools + 3 resources + 3 prompts）
  - `roadmap.md`：4 周计划与验收标准、里程碑演示脚本、风险登记
- 新增开发记录模板 `document/plans/development-log-template.md`

## 改动原因

项目启动前的设计阶段交付物。经与需求方（作者本人）确认三项关键决策：
1. 前后端全栈（FastAPI + React），由 AI 辅助实现；
2. LLM 采用多模型抽象层（Claude/DeepSeek/Kimi 可切换）；
3. 先完成设计文档评审，再进入编码。

## 影响范围

纯文档，无代码影响。设计文档将作为后续开发的基准，其中 ADR 决策（SQLite、向量内存检索等）如需变更须先更新文档。

## 验证方式

- 文档内部交叉引用一致性检查（API 端点与数据模型字段、Agent schema 引用一致）
- 技术选型与作者既有项目栈核对（uv / FastAPI / SQLAlchemy 2.0，参考 CodeAtlasMVP）

## 遗留问题

- 爬虫目标站点清单（具体研究所/国企官网列表）需按作者秋招目标清单细化，W3 前确定
- FTS5 中文分词最终方案（jieba 预处理 vs trigram）待编码时实测本机 SQLite 版本后定
- 需求方 review 本批文档后，确认无修改即合入 main 并启动 W1 开发
