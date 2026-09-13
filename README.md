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
| 后端 | Java 21 + Spring Boot + **Spring AI** + Spring Data JPA + Maven 多模块 |
| 数据库 | PostgreSQL 16 + **pgvector**（向量检索）+ tsvector 全文检索 + Flyway |
| AI | Spring AI 多模型路由（Claude / DeepSeek / Kimi）+ **MCP Server**（官方 Java SDK） |
| 采集 | Chrome Extension (MV3) + Jsoup/Playwright 定向爬虫 |
| 工具链 | Maven / pnpm / JUnit 5 + Testcontainers / docker-compose |

> 选型说明：后端刻意采用与作者既有 Python 项目（FastAPI/SQLite）不同的企业级栈——目标雇主（军工研究所/运营商/银行）以 Java/Spring 为主流，详见 docs/architecture.md ADR-0。

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/PRD.md](docs/PRD.md) | 产品需求：背景、目标、功能分级、用户流程 |
| [docs/architecture.md](docs/architecture.md) | 技术架构、模块划分、ADR 关键决策 |
| [docs/data-model.md](docs/data-model.md) | 数据模型 ER 图、建表 DDL、状态机 |
| [docs/api-design.md](docs/api-design.md) | REST API 接口定义与示例 |
| [docs/agent-design.md](docs/agent-design.md) | Agent 与 MCP Server 详细设计 |
| [docs/deployment-tutorial.md](docs/deployment-tutorial.md) | **生产部署教程**：Vercel + 云服务器 + Docker + HTTPS，从零到上线 |
| [docs/target-sources.md](docs/target-sources.md) | 目标企业清单（军工所/运营商/银行）与爬虫源 |
| [docs/roadmap.md](docs/roadmap.md) | 开发路线图与里程碑 |

## 快速开始

```bash
# 1. 数据库（PostgreSQL 16 + pgvector，容器名 jobradar-db）
docker compose -f deploy/docker-compose.yml up -d

# 2. 后端（Java 21 + Maven；启动时 Flyway 自动建表）
export DeepSeek_API_KEY=sk-...        # JD/简历解析、匹配、周报叙事（可选，缺失时相关能力自动降级）
export DASHSCOPE_API_KEY=sk-...       # 招聘海报图片识别（可选）
export JOBRADAR_LOCAL_TOKEN=...       # 前端/插件访问后端的本机令牌（默认 dev-only-token-change-me）
export JOBRADAR_PASSWORD=...          # 网页登录密码（默认 jobradar；本地免登可设 JOBRADAR_AUTH_ENABLED=false）
cd backend && mvn spring-boot:run -pl jobradar-app

# 3. 前端（Vite dev server，/api 代理到 127.0.0.1:8080）
cd frontend && npm install && npm run dev   # http://localhost:5173

# 4. Chrome 插件：扩展管理页 → 开发者模式 → 加载 extension/ 目录
#    popup 里填后端地址与本机令牌即可一键收藏当前页岗位

# 5. MCP Server（Claude Desktop 自然语言管理投递，可选）
cd backend && mvn -pl jobradar-mcp-server -am -DskipTests package
#    将 jar 配进 claude_desktop_config.json，详见 docs/agent-design.md §6.5
```

## 项目状态

- 当前版本见 [docs/roadmap.md](docs/roadmap.md) 顶部版本表（四周计划 W1–W4 已交付）；
- 自动化测试 46 个（JUnit 5 + Testcontainers 真实 PostgreSQL，含 MCP 协议级冒烟）；
- 演示脚本（面试 3 分钟动线）见 roadmap §2 里程碑表；
- 面试讲解素材：[docs/learning/interview-guide.md](docs/learning/interview-guide.md)。
