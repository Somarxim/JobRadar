# JobRadar 技术架构

| 版本 | 日期 | 状态 |
|---|---|---|
| v0.1 | 2026-09-03 | 初稿 |

## 1. 总体架构

```
┌────────────────────────────────────────────────────────┐
│                  apps/web  (Vite + React SPA)           │
│   Dashboard │ 看板 │ 日历 │ 岗位库 │ 详情 │ 简历 │ 设置   │
└──────────────────────────┬─────────────────────────────┘
                           │ REST / JSON
┌──────────────────────────▼─────────────────────────────┐
│                 apps/api  (FastAPI)                     │
│  ┌────────────┬─────────────┬───────────┬────────────┐ │
│  │ jobs 模块  │ applications │ dashboard │ match 模块 │ │
│  └────────────┴─────────────┴───────────┴────────────┘ │
│  依赖注入：DB Session / LLM Provider / Embedder         │
└───┬───────────────┬───────────────────────┬────────────┘
    │ 共享 SQLAlchemy Models + Service 层 (packages/core)
    │               │                       │
┌───▼───────┐  ┌────▼─────────────┐  ┌──────▼─────────────┐
│ apps/     │  │ packages/        │  │ apps/              │
│ mcp-server│  │ crawler          │  │ scheduler (内嵌api) │
│ (stdio)   │  │ 定向爬虫+清洗管线  │  │ APScheduler 定时任务│
└───────────┘  └────┬─────────────┘  └────────────────────┘
                    │ 抓取目标：国聘/研究所官网/牛客
┌───────────────────▼────────────────────────────────────┐
│              SQLite (jobradar.db)                       │
│   业务表 + FTS5 全文索引 + BLOB 向量（内存暴力检索）        │
└────────────────────────────────────────────────────────┘

外部触点：
  Chrome 插件 (apps/extension) ──POST /api/jobs/ingest──▶ api
  Claude Desktop ◀──stdio MCP──▶ apps/mcp-server
  LLM APIs (Claude/DeepSeek/Kimi) ◀──▶ packages/llm (Provider 抽象)
```

## 2. Monorepo 目录结构

```
JobRadar/
├── apps/
│   ├── api/                 # FastAPI 后端（含 APScheduler 定时任务）
│   │   ├── main.py
│   │   ├── deps.py          # 依赖注入（DB/LLM/Embedder）
│   │   ├── routers/         # jobs / applications / dashboard / resumes / match ...
│   │   └── services/        # 业务逻辑（与 router 分离，供 MCP 复用）
│   ├── web/                 # React SPA
│   │   ├── src/pages/       # Dashboard / Board / Calendar / Jobs / JobDetail / Resume / Settings
│   │   ├── src/components/
│   │   └── src/lib/api.ts   # API client (fetch 封装)
│   ├── extension/           # Chrome MV3 插件
│   │   ├── manifest.json
│   │   ├── content.js       # 页面岗位信息提取
│   │   └── popup/           # 收藏弹窗（确认/编辑后提交）
│   └── mcp-server/          # MCP Server（stdio，接 Claude Desktop）
│       └── server.py        # tools/resources/prompts 注册
├── packages/
│   ├── core/                # 共享层：models / schemas / database / config
│   ├── llm/                 # LLM Provider 抽象 + 各厂商实现 + Embedding
│   ├── agent/               # 匹配 Agent / 推荐 Agent / 周报 Agent
│   └── crawler/             # 爬虫框架 + 各站点 spider + 清洗管线 + 去重
├── tests/                   # pytest（core/llm/agent 单测 + api 集成测试）
├── data/                    # jobradar.db / 上传的简历文件（gitignore）
├── docs/                    # 设计文档
├── document/plans/          # 开发记录
├── pyproject.toml           # uv 管理（单 Python 包，apps/packages 全在内）
└── package.json             # workspace 根（仅聚合 web/extension 脚本）
```

**为什么 Python 侧不分多个包**：个人项目，单一 pyproject + 内部包划分最省心；模块边界靠目录与 import 约束，MCP/爬虫/调度都复用同一套 models 与 service，避免跨包版本地狱。

## 3. 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| 前端框架 | Vite + React 18 + TypeScript | SPA 够用，无需 SSR；生态成熟 |
| UI | Tailwind CSS + shadcn/ui | 快速搭建美观界面，组件可复制定制 |
| 看板拖拽 | @dnd-kit | 现代拖拽库，可访问性好 |
| 图表 | Recharts | React 原生，漏斗/柱状/饼图开箱即用 |
| 日历 | 自研网格 + date-fns（或 react-big-calendar） | 需求简单（事件打点），避免重型依赖 |
| 后端 | FastAPI + SQLAlchemy 2.0 + Pydantic v2 | 与用户既有项目栈一致，异步 + 类型安全 |
| 数据库 | SQLite + FTS5 | 零运维单机方案；FTS5 支持全文检索；WAL 模式支撑 API/MCP/爬虫三进程并发读写 |
| 任务调度 | APScheduler (AsyncIOScheduler) | 内嵌 API 进程，无需独立 worker/broker |
| LLM SDK | 各厂商官方 SDK（anthropic / openai 兼容协议） | DeepSeek/Kimi 均兼容 OpenAI 协议，抽象成本低 |
| MCP | 官方 `mcp` Python SDK (FastMCP) | 协议正统实现，简历关键词 |
| 爬虫 | httpx + BeautifulSoup4；动态页 Playwright（按需引入） | 目标站点多为静态页，轻量优先 |
| 前端包管理 | pnpm | 快速、节省磁盘 |
| Python 包管理 | uv | 与用户习惯一致 |
| 测试 | pytest + httpx (API 集成) | 与既有项目一致 |

## 4. 关键架构决策（ADR）

### ADR-1：SQLite 而非 PostgreSQL

- **决策**：单文件 SQLite（WAL 模式）。
- **理由**：单用户本地应用，零运维、零安装、备份=复制文件；万级数据量下性能无瓶颈。
- **代价与对策**：多进程写入需串行化 → 爬虫/调度写入经 API 进程或加锁；FTS5 中文分词弱 → 建索引时用 jieba 预分词写入（或 trigram tokenizer，详见 data-model.md）。
- **退出路径**：SQLAlchemy 抽象保证未来可切 PostgreSQL，仅改连接串与少量方言。

### ADR-2：插件辅助采集为主，定向爬虫为辅

- **决策**：不做互联网平台（BOSS 等）的自动爬取；广度靠 Chrome 插件（人工浏览时一键收藏），增量发现靠反爬弱的公开官网爬虫。
- **理由**：BOSS 等平台反爬严格且涉及登录态，自动爬取有账号封禁与合规风险；而"发现盲区"的痛点集中在国企/研究所官网——这些站点反爬弱、更新规律，适合定时爬。
- **效果**：插件覆盖"看到的都能收"，爬虫解决"没看到的也能发现"。

### ADR-3：LLM Provider 抽象层

- **决策**：定义统一接口（`complete` / `structured_output` / `embed`），实现 Claude / DeepSeek / Kimi 三个 Provider，配置驱动切换，支持按任务类型路由（如解析用便宜模型、精评用强模型）。
- **理由**：避免供应商锁定；成本可控（结构化解析走 DeepSeek，关键评分可走 Claude）；简历上构成"多模型适配"叙事点。

### ADR-4：向量检索用内存暴力扫描，不引入向量数据库

- **决策**：embedding 以 BLOB 存 SQLite（float32 numpy），查询时全量加载到内存做余弦相似度。
- **理由**：岗位规模 ≤ 数万条，1024 维向量全量约几十 MB，单次扫描 < 100ms；引入 FAISS/Qdrant/sqlite-vec 对个人项目是过度工程。
- **退出路径**：数据量超 10 万条再切 sqlite-vec，接口不变。

### ADR-5：MCP Server 独立进程，复用 Service 层

- **决策**：`apps/mcp-server` 以 stdio 运行（Claude Desktop 标准接法），直接 import `apps/api/services` 与 `packages/core`，共享同一 SQLite 文件。
- **理由**：MCP Server 与 API 是同一领域能力的两种暴露方式（LLM 工具 vs HTTP），业务逻辑必须只有一份。
- **并发**：SQLite WAL 模式支持多读单写，MCP 的写操作走同一 service（带锁），安全。

### ADR-6：调度器内嵌 API 进程

- **决策**：APScheduler 以 AsyncIOScheduler 挂在 FastAPI lifespan 里，每日定时跑爬虫管线。
- **理由**：个人应用无需 Celery/Redis；重启即恢复，任务定义在代码里可版本化。

## 5. 部署与运行

```bash
# 后端（含调度器）
uv sync && uv run uvicorn apps.api.main:app --reload --port 8000

# 前端
pnpm --filter web dev   # localhost:5173，proxy /api → :8000

# MCP Server（Claude Desktop 配置）
claude_desktop_config.json:
  "jobradar": { "command": "uv", "args": ["run", "python", "-m", "apps.mcp_server.server"],
                "cwd": "/path/to/JobRadar" }

# Chrome 插件
chrome://extensions → 开发者模式 → 加载已解压的 apps/extension
```

LLM 配置经 `.env`（`LLM_PROVIDER=deepseek` / `DEEPSEEK_API_KEY=...`），提供 `.env.example`。

## 6. 安全与隐私

- 简历 PDF 仅存本地 `data/`；LLM 解析仅发送文本内容，可选脱敏开关（隐去姓名/电话后再发送）。
- 全部第三方调用出站仅 LLM API 与被爬官网，无其他遥测。
- API 默认绑定 127.0.0.1；插件请求带本地 token 校验（防止其他网页恶意调用本地 API）。
