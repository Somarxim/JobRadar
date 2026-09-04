# JobRadar 技术架构

| 版本 | 日期 | 状态 |
|---|---|---|
| v0.2 | 2026-09-04 | 后端栈更换为 Java/Spring（见 ADR-0） |
| v0.1 | 2026-09-03 | 初稿（Python/FastAPI 方案，已废弃） |

## 1. 总体架构

```
┌────────────────────────────────────────────────────────┐
│                  apps/web  (Vite + React SPA)           │
│   Dashboard │ 看板 │ 日历 │ 岗位库 │ 详情 │ 简历 │ 设置   │
└──────────────────────────┬─────────────────────────────┘
                           │ REST / JSON
┌──────────────────────────▼─────────────────────────────┐
│        jobradar-app (Spring Boot, Maven module)         │
│  web 层: Controllers (jobs/applications/dashboard/...)  │
│  service 层: 业务逻辑（供 Web 与 MCP 双入口复用）          │
│  agent 层: 匹配/推荐/周报 (Spring AI ChatClient)         │
│  crawler 层: 爬虫框架 + 站点适配 (Jsoup/Playwright)      │
│  调度: @Scheduled 每日管线                               │
└───┬───────────────────────────────┬────────────────────┘
    │ 共享 Maven 模块 jobradar-core   │
    │ (domain / repo / service / agent / dto)
    │                               │
┌───▼──────────────────┐   ┌────────▼─────────────────────┐
│ jobradar-mcp-server  │   │  PostgreSQL 16 + pgvector     │
│ (stdio, Spring AI    │   │  业务表 + tsvector 全文检索    │
│  MCP Server Starter) │   │  + vector(1024) 语义检索      │
└──────────────────────┘   └──────────────────────────────┘

外部触点：
  Chrome 插件 (apps/extension) ──POST /api/jobs/ingest──▶ app
  Claude Desktop ◀──stdio MCP──▶ jobradar-mcp-server
  LLM APIs (Claude/DeepSeek/Kimi) ◀──▶ Spring AI ChatModel 抽象
```

## 2. 目录结构

```
JobRadar/
├── apps/
│   ├── web/                 # React SPA（与后端语言无关，结构不变）
│   └── extension/           # Chrome MV3 插件（不变）
├── backend/                 # Maven 多模块项目
│   ├── pom.xml              # 父 POM（依赖版本管理）
│   ├── jobradar-core/       # 核心模块：domain(JPA Entity) / repository(Spring Data JPA)
│   │                        #   / service / agent / crawler / dto
│   ├── jobradar-app/        # Web 应用模块：Controller + @Scheduled 调度 + 启动类
│   └── jobradar-mcp-server/ # MCP 模块：@Tool 注册 + stdio 启动类（依赖 core）
├── apps/web/…               # （见上）
├── data/                    # 简历文件等本地数据（gitignore）
├── deploy/
│   └── docker-compose.yml   # pgvector/pgvector:pg16 单容器
├── docs/                    # 设计文档
├── document/plans/          # 开发记录
└── README.md
```

**Maven 三模块划分理由**：core 沉淀全部领域逻辑，app 与 mcp-server 是同一能力的两种宿主（HTTP / stdio），编译期即强制业务逻辑不泄漏进宿主层。前端 `apps/` 与后端 `backend/` 并列，界限清晰。

## 3. 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| 语言 | Java 21 LTS | 目标雇主（银行/运营商/军工所）主流栈；虚拟线程等新特性是校招面试常考点 |
| 框架 | Spring Boot 3.5+/4.x（以初始化时稳定版为准） | 企业级事实标准 |
| AI 集成 | **Spring AI 1.x** | ChatModel 多 Provider 抽象 / `entity()` 结构化输出 / EmbeddingModel / MCP Server Starter——原生覆盖本项目全部 AI 需求 |
| ORM | Spring Data JPA (Hibernate 6) | 企业主流；Hibernate 对复杂领域模型表达力好 |
| 数据库 | PostgreSQL 16 + pgvector | 与既有项目 SQLite 差异化；pgvector 原生向量索引；JSONB 存半结构化数据 |
| 迁移 | Flyway | Java 生态标准，W1 即引入 |
| 全文检索 | PG tsvector + jieba-analysis 分词（pg_trgm 兜底） | 见 data-model.md §2.3 |
| 调度 | Spring `@Scheduled` | 单机定时任务够用，零额外组件 |
| 爬虫 | Jsoup（静态页）+ Playwright Java（动态页，按需） | Java 生态对应方案 |
| PDF 解析 | Apache PDFBox | 简历 PDF 文本提取 |
| 构建 | Maven | 国企/银行存量主流，面试安全牌 |
| 测试 | JUnit 5 + Spring Boot Test + Testcontainers(PG) | Testcontainers 提供真实 PG 集成测试，简历加分项 |
| MCP | spring-ai-mcp-server（官方 Java SDK） | 见 agent-design.md §6 |
| 前端 | 不变：Vite + React 18 + TS + Tailwind + shadcn/ui + Recharts + @dnd-kit | |
| 前端包管理 | pnpm | |

## 4. 关键架构决策（ADR）

### ADR-0：后端采用 Java/Spring 而非 Python/FastAPI ★（v0.2 新增）

- **背景**：作者既有项目（CodeAtlasMVP 等）已是 Python/FastAPI/SQLite 栈，简历技术栈需差异化。
- **决策**：Java 21 + Spring Boot + Spring AI + PostgreSQL + Maven。
- **理由**：
  1. 简历叙事：Python（AI/Agent 项目）+ Java（企业级后端）双栈，覆盖面最大化；
  2. 雇主匹配：目标雇主（军工研究所信息部门、运营商、银行软开）技术栈以 Java/Spring 为主，项目即面试话题；
  3. 生态就绪：Spring AI 的 ChatModel 抽象、结构化输出、MCP Server Starter 使原设计无损平移。
- **代价与对策**：作者 Java/Spring 熟练度低于 Python → 开发以 AI 辅助为主，同时把开发过程当作银行/国企 Java 技术面试的实战备战；编码规范遵循 Spring 官方指南，保证"讲得清楚每一层"。

### ADR-1：PostgreSQL + pgvector 而非 SQLite（v0.2 修订）

- **决策**：PostgreSQL 16（docker-compose 单容器）+ pgvector 扩展。
- **理由**：与既有项目 SQLite 差异化；pgvector 提供原生向量类型与 HNSW 索引，替代原"内存暴力扫描"方案；JSONB 适合存 `requirements` / `match detail` 等半结构化数据；PG 是国企/银行新项目主流（替代 Oracle 趋势）。
- **代价**：本地开发需 Docker（备选：Homebrew postgresql@16 + pgvector）；运维成本略增，可接受。

### ADR-2：插件辅助采集为主，定向爬虫为辅（不变）

- 不做互联网平台（BOSS 等）自动爬取；广度靠 Chrome 插件，增量发现靠国企/研究所/运营商官网爬虫（目标清单见 [target-sources.md](target-sources.md)）。
- 理由同 v0.1：反爬合规与覆盖面的平衡；盲区痛点集中在反爬弱的官网。

### ADR-3：LLM 接入基于 Spring AI 抽象，不自造 Provider 层（v0.2 修订）

- **决策**：直接使用 Spring AI 的 `ChatModel`/`ChatClient` 与 `EmbeddingModel` 抽象，按任务类型装配不同 Model bean（解析用 DeepSeek、精评/报告可用 Claude）。
- **理由**：Spring AI 已提供统一抽象与各家 starter（Anthropic、OpenAI 兼容协议接 DeepSeek/Kimi），自造轮子无增量价值；"基于 Spring AI 的多模型路由"本身就是简历叙事。
- **保留的自研部分**：任务路由配置（`application.yml` 按 parse/match/report 分配模型）、token 用量记账、失败 fallback——这些是工程亮点所在。

### ADR-4：向量检索用 pgvector HNSW 索引（v0.2 修订）

- **决策**：`embedding vector(1024)` 列 + HNSW 索引，粗筛 SQL `ORDER BY embedding <=> ? LIMIT k`。
- **理由**：PG 方案下这是零额外组件的最优解；万级数据 HNSW 毫秒级。
- embedding 模型：bge-m3（1024 维），走 OpenAI 兼容 API（百炼/硅基流动）或本地 Ollama；chat 与 embedding 的 Provider 解耦（DeepSeek 无 embedding API）。

### ADR-5：MCP Server 独立模块，复用 core service 层（实现方式修订）

- **决策**：`jobradar-mcp-server` 模块，Spring AI MCP Server Starter，stdio 传输接 Claude Desktop；`@Tool` 注解薄封装 core 的 service 方法。
- **理由**：同一领域能力的两种暴露（HTTP vs LLM 工具），业务逻辑只在 core 存在一份。

### ADR-6：调度器内嵌应用进程（实现方式修订）

- **决策**：`@Scheduled(cron)` 挂在 jobradar-app 内跑每日推荐管线；无独立 worker。
- **理由**：同 v0.1——个人应用无需消息队列与独立调度服务。

## 5. 部署与运行

```bash
# 0. 数据库（首次）
docker compose -f deploy/docker-compose.yml up -d   # pgvector/pgvector:pg16

# 1. 后端（含调度器）
cd backend && ./mvnw spring-boot:run -pl jobradar-app

# 2. 前端
pnpm --filter web dev   # localhost:5173，proxy /api → :8080

# 3. MCP Server（Claude Desktop 配置）
claude_desktop_config.json:
  "jobradar": { "command": "java",
                "args": ["-jar", "/path/to/jobradar-mcp-server/target/jobradar-mcp-server.jar"] }

# 4. Chrome 插件
chrome://extensions → 开发者模式 → 加载已解压的 apps/extension
```

LLM 配置经 `application.yml` + 环境变量（`DEEPSEEK_API_KEY` / `ANTHROPIC_API_KEY` / `MOONSHOT_API_KEY` / `EMBEDDING_API_KEY`），提供 `application-example.yml`。

## 6. 安全与隐私

- 简历 PDF 仅存本地 `data/`；LLM 解析仅发送文本，可选脱敏开关（隐去姓名/电话）。
- 出站调用仅 LLM API 与被爬官网，无遥测。
- API 默认绑定 127.0.0.1；插件请求带 `X-Local-Token` 校验；CORS 仅放行 vite dev server 与插件 origin。
