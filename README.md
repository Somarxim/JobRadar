# JobRadar 求职雷达

> 聚合多平台招聘信息、管理投递全生命周期的个人求职中台，内置基于 MCP 协议的 AI Agent。

## 为什么做这个

应届毕业生求职信息分散在互联网招聘平台（BOSS/牛客/官网）、央国企平台（国聘等）与研究所官网之间，平台互不互通。求职者面临三个问题：

1. **信息割裂**：优质国企/研究所岗位容易因"不常逛"而错过；
2. **进度失控**：投递记录散落在各平台，忘记投过什么、何时投递、进行到哪一步；
3. **规划缺失**：缺乏可视化的投递节奏管理与进度复盘工具。

JobRadar 通过「多源采集 + 统一岗位库 + 投递看板 + AI 匹配 Agent」解决以上问题。

## 核心能力

- **多源采集**：Chrome 插件一键收藏任意平台岗位；定向爬虫每日扫描国企/研究所官网；JD 文本粘贴导入
- **投递管理**：看板式状态流转（收藏 → 计划 → 已投递 → 笔试 → 面试 → Offer），全链路留痕，DDL 提醒
- **可视化 Dashboard**：投递漏斗、日历视图、周趋势、公司类型分布
- **AI Agent**：
  - 简历-JD 匹配 Agent（Embedding 粗筛 + LLM 精评，针对央国企硬性条件特化）
  - 每日推荐 Agent（自动发现高匹配岗位并推送）
  - **MCP Server**：将求职数据库暴露为 LLM 工具，支持自然语言管理投递进度

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vite + React + TypeScript + Tailwind + shadcn/ui + Recharts + @dnd-kit |
| 后端 | FastAPI + SQLAlchemy 2.0 + SQLite (FTS5) |
| AI | 多模型 Provider 抽象（Claude / DeepSeek / Kimi），MCP Python SDK |
| 采集 | Chrome Extension (MV3) + httpx/Playwright 定向爬虫 |
| 工具链 | uv / pnpm / pytest |

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/PRD.md](docs/PRD.md) | 产品需求：背景、目标、功能分级、用户流程 |
| [docs/architecture.md](docs/architecture.md) | 技术架构、模块划分、ADR 关键决策 |
| [docs/data-model.md](docs/data-model.md) | 数据模型 ER 图、建表 DDL、状态机 |
| [docs/api-design.md](docs/api-design.md) | REST API 接口定义与示例 |
| [docs/agent-design.md](docs/agent-design.md) | Agent 与 MCP Server 详细设计 |
| [docs/roadmap.md](docs/roadmap.md) | 开发路线图与里程碑 |

## 快速开始

> 待 MVP 开发完成后补充。
