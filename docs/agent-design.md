# JobRadar Agent 与 MCP 设计

| 版本 | 日期 | 状态 |
|---|---|---|
| v0.2 | 2026-09-04 | 实现栈切换 Spring AI；补充目标岗位方向 |
| v0.1 | 2026-09-03 | 初稿（Python 实现方案，已废弃） |

本文档是项目的 AI 核心设计，覆盖：Spring AI 多模型接入、简历解析、JD 结构化、匹配 Agent、每日推荐 Agent、MCP Server。

## 1. LLM 接入（Spring AI，packages → jobradar-core/agent）

### 1.1 基于 Spring AI 抽象

Spring AI 已提供统一抽象，本项目在其上做**任务路由**而非自造 Provider 层：

```java
// ChatModel：spring-ai-anthropic / spring-ai-openai（DeepSeek、Kimi 走 OpenAI 兼容协议接入）
// EmbeddingModel：OpenAI 兼容协议接 bge-m3（百炼/硅基流动）或 Ollama 本地

ChatClient parseClient;   // 注入 DeepSeek 模型 —— 便宜快速，批量结构化
ChatClient matchClient;   // 可配 DeepSeek 或 Claude —— 质量敏感
ChatClient reportClient;  // Claude —— 表达质量优先（周报/面试材料）
```

**任务路由**（`application.yml` 配置驱动）：

```yaml
jobradar:
  llm:
    routing:
      parse: deepseek      # JD/简历结构化：便宜快速
      match: deepseek      # 匹配精评：可切 anthropic 提质量
      report: anthropic    # 周报/面试准备：表达质量优先
    embedding:
      provider: openai-compatible   # bge-m3 via 百炼/硅基流动；或 ollama 本地
      model: bge-m3
      dimension: 1024
```

- Key 经环境变量注入：`DEEPSEEK_API_KEY` / `ANTHROPIC_API_KEY` / `MOONSHOT_API_KEY` / `EMBEDDING_API_KEY`。
- 上游故障自动 fallback（DeepSeek ↔ Kimi 同为 OpenAI 协议，互备成本低），记 warning。
- **成本记账**：每次调用记录 token 用量（Spring AI 的 `Usage` 元数据）累计到 `settings`，Dashboard 展示——工程化细节，简历可讲。

## 2. Embedding 策略

| 方案 | 默认 | 说明 |
|---|---|---|
| OpenAI 兼容 API（bge-m3，百炼/硅基流动） | ✅ 优先 | 零本地依赖；DeepSeek 无 embedding API，故 embedding 与 chat Provider 解耦 |
| Ollama 本地 bge-m3 | 降级方案 | 断网/无 key 时可用，Spring AI 原生支持 Ollama |

向量化文本构造：
- 岗位：`"{companyName} {title} {city}\n{jdSummary or jdText[:1500]}"`
- 简历：由 parsed JSON 拼「技能 + 项目摘要 + 求职意向」

## 3. 结构化 Schema 设计（Java record + Jackson）

Spring AI 结构化输出：`chatClient.prompt().user(...).call().entity(MatchDetail.class)`（底层 BeanOutputConverter 生成 JSON Schema 约束 + 自动反序列化）。

### 3.1 简历解析 schema（`ResumeProfile`）

```java
record Education(String school, String degree, String major, String period,
                 Boolean is985, Boolean is211) {}   // LLM 推断 92 标签，国企筛选常用

record Experience(String type,      // internship|project|competition|research
                  String org, String role, String period,
                  List<String> highlights) {}        // 量化成果要点

record ResumeProfile(
    String name,                      // 脱敏模式下为 null
    List<Education> education,
    List<String> skills,              // 规范化技能词
    List<Experience> experiences,
    List<String> targetPositions,     // 求职意向（用户可手改）
    List<String> targetCities,
    List<String> awards,
    String summary                    // LLM 生成的 100 字能力画像
) {}
```

解析流程：PDF → **Apache PDFBox** 提取文本 → `entity(ResumeProfile.class)` → 用户确认页可编辑 → 定稿生成 embedding。

**默认求职意向**（按作者目标，作为解析后的校验基准）：
- Agent 研发工程师 / AI 应用工程师
- AI 全栈开发
- 信息系统开发与维护（军工所常见岗位名）
- 软件开发工程师 / 后端开发（Java/Python）

### 3.2 JD 结构化 schema（`JobRequirements`）

```java
record HardRequirements(
    String degreeMin,                 // 大专|本科|硕士|博士，null=不限
    List<String> majors,              // 要求专业（空=不限）
    List<Integer> graduationYears,    // 如 [2026]，应届身份要求
    String city,
    String politicalStatus,           // 党员等（军工所/国企常见）
    List<String> other                // 其他硬性条件原文（如"英语六级""能适应出差"）
) {}

record JobRequirements(
    HardRequirements hard,
    List<String> skills,              // 与简历技能同词表规范化
    List<String> niceToHave,
    String jobCategory,               // 见下方岗位类别词表
    List<String> responsibilities     // 职责要点 3-5 条
) {}
```

**岗位类别词表**（`jobCategory` 枚举约束，按作者目标方向定）：
`agent_ai`（Agent/大模型应用研发）、`ai_fullstack`（AI 全栈）、`backend_java`、`backend_python`、`fullstack`、`embedded`（嵌入式，军工所大量需求）、`devops_sre`、`data`、`test`、`other_tech`、`non_tech`

**技能词表规范化**：prompt 附约 200 个标准技能词（语言/框架/中间件/AI 栈），要求 LLM 归一化输出（"golang"→"Go"，"大模型"→"LLM"）。词表存 `backend/jobradar-core/src/main/resources/skill-dict.txt`，可迭代。

### 3.3 匹配报告 schema（`MatchDetail`）

```java
record HardCheck(String item, String resumeValue, boolean passed, String note) {}

record MatchDetail(
    List<HardCheck> hardChecks,   // 硬性条件逐条核对
    boolean hardPass,             // 硬性条件整体是否通过
    int scoreTotal,               // 0-100
    Map<String,Integer> scoreBreakdown,  // {"skill":.., "experience":.., "fit":..}
    List<String> matchedSkills,
    List<String> missingSkills,
    List<String> highlights,      // 简历中最契合的 2-3 个经历
    String suggestion,            // 投递建议：是否投 + 简历侧重点 + 注意事项
    String oneLiner               // 一句话结论（推荐列表展示用）
) {}
```

**打分规则**（写进 prompt 的评分基准）：
- `hardPass=false` → scoreTotal 上限 39，oneLiner 必须点明不满足的硬性条件；
- 技能重合（40）+ 经历契合（40）+ 综合契合（20，含城市/公司类型偏好）；
- 分数锚点：≥85 强匹配必投 / 70–84 推荐 / 55–69 可投 / <55 不推荐。
- **军工所特化**：专业对口、学历、应届身份、政治面貌、保密要求（如"能适应封闭管理"）必须出现在 hardChecks 中逐项核对。

## 4. 匹配 Agent（双阶段）

```
输入: jobId, resumeId
│
├─ Stage 1 粗筛（SQL，零 LLM 成本）
│   SELECT ... FROM jobs WHERE id=:jobId
│   → pgvector: resume.embedding <=> jobs.embedding 已在入库时算不了，
│     粗筛实为「该 job 向量 vs 简历向量」单点计算 + 技能词交集检查
│   相似度 < 0.35 且无技能交集 → 直接判低分(≤40)，不调 LLM
│
└─ Stage 2 精评（chatClient.entity(MatchDetail.class)）
    system: 资深校招求职顾问（熟悉军工所/央国企/银行招聘惯例）
            + 评分基准 + 硬性条件核对要求 + 输出约束
    user:   JobRequirements(JSON) + JD原文(截断3000字) + ResumeProfile(JSON)
    ↓
    校验 → 存 match_reports（含 model_used + prompt_version）→ 返回
```

**设计要点：**
- 精评输入「结构化 JSON + JD 原文」双路：结构化保准确，原文防提取遗漏。
- hardChecks 先输出再打分（字段顺序即 CoT 顺序，减少"忽略硬门槛给高分"的失败模式）。
- 失败重试：反序列化/校验失败重试 1 次（附错误信息）；仍失败返回 422，详情页提示人工阅读。
- 缓存：同 (job_id, resume_id) 24h 内复用最新报告；简历更新后失效相关报告。

## 5. 每日推荐 Agent（定时管线）

```
@Scheduled(cron = "0 30 7 * * *")   // 每日 07:30，可配
│
├─ 1. crawl      遍历 enabled 的 crawl_sources（Jsoup 抓列表页+详情页）
├─ 2. structure  新岗位 → entity(JobRequirements.class) + 摘要 + 规范化
├─ 3. dedupe     公司别名归一 → dedupe_hash 查重 → 合并 also_seen_on
├─ 4. embed      批量生成 embedding（EmbeddingModel）
├─ 5. coarse     pgvector 粗筛 vs 默认简历，Top 20 且相似度 ≥ 0.35
├─ 6. score      匹配 Agent 精评 Top 20 → match_reports
├─ 7. select     hardPass 优先 + 分数排序取 Top 5 → recommendations
└─ 8. notify     Dashboard 今日推荐区 + 可选 Webhook（Server酱/Telegram）
```

**工程要点：**
- 每步幂等；单源失败隔离（catch 记日志继续）；产出「运行报告」（新增/重复/失败/LLM 用量）。
- 成本闸：每日结构化 ≤ 100 条 + 精评 ≤ 20 条，超出截断。
- 反馈闭环：accept → 自动建 application；ignore → feedback_tag（城市/方向/已投过），回流调阈值。
- **军工所时效性**：研究所校招窗口短（常 2–4 周截止），爬虫每日跑 + deadline 提取优先级高。

## 6. MCP Server（jobradar-mcp-server 模块）★

基于 Spring AI MCP Server Starter（官方 Java SDK），stdio 传输接 Claude Desktop。`@Tool` 注解薄封装 core service：

```java
@Tool(name = "apply", description = "记录一次岗位投递。jobId 必填；channel 为投递渠道")
public ApplyResult apply(long jobId, String channel, String note, LocalDateTime appliedAt) { ... }
```

### 6.1 Tools

| Tool | 参数 | 返回 | 示例话术 |
|---|---|---|---|
| `search_jobs` | `query?, companyType?, city?, stage?, minMatch?, limit=10` | 岗位摘要列表 | "帮我找成都的军工所软件岗" |
| `get_job_detail` | `jobId` | 岗位详情+匹配报告 | "128 号岗位详情" |
| `add_job` | `rawText, url?` | 解析结果+jobId | "我复制了个 JD，帮我录入：……" |
| `apply` | `jobId, channel, note?, appliedAt?` | application 对象 | "我今天官网投了 128 号岗位" |
| `update_stage` | `jobId, toStage, note?` | 流转结果 | "腾讯进面试了" |
| `set_next_action` | `jobId, action, at` | 更新结果 | "提醒我周五前做移动笔试" |
| `today` | — | 今日待办（计划投递+DDL+next_action） | "我今天要干什么" |
| `weekly_report` | `weekOffset=0` | 周报 Markdown | "复盘这周投递情况" |
| `match_job` | `jobId, resumeId?` | MatchDetail | "这个岗位和我匹配吗" |
| `recommend_today` | — | 今日推荐 Top 5 | "今天有什么值得投的" |

**设计原则：**
- tool 返回**结构化 JSON + 自然语言摘要**双格式。
- 写操作幂等友好：重复调用返回当前状态而非报错。
- 模糊匹配容错：按公司名模糊查（pg_trgm），多候选返回列表让 LLM 追问——**不猜不写**，写操作必须定位到唯一记录。

### 6.2 Resources 与 Prompts

| Resource URI | 内容 |
|---|---|
| `jobradar://stats/funnel` | 实时投递漏斗 JSON |
| `jobradar://jobs/{id}` | 单岗位完整信息 |
| `jobradar://resume/default` | 默认简历结构化画像 |

| Prompt | 用途 |
|---|---|
| `prep_interview` | JD + 简历 → 高频问题清单 + 项目深挖点 + 反问建议 |
| `weekly_review` | 数据 + 漏斗转化分析 + 下周策略建议 |
| `jd_gap_analysis` | 岗位要求 vs 简历 → 学习/补足计划 |

### 6.3 安全

- 仅 stdio（无网络端口）；写操作 tool 标注 MCP tool annotations（`idempotentHint` 等），宿主正确渲染确认 UI。

### 6.4 落地偏差记录（W4-1 实测）

- **Resources 收敛为 2 个具体 URI**（`stats/funnel`、`resume/default`）：`jobs/{id}` 的 URI 模板在 MCP SDK 0.10.0 的 Spring AI 自动配置链中支持不完整，且岗位详情由 `get_job_detail` tool 覆盖更符合 LLM 调用习惯；
- `weekly_report` v2（W4-2 已落地）：`WeeklyReportService` 两层结构——确定性 SQL 组装统计（周区间事件流水/阶段流入/环比/推荐采纳率），LLM 只做 Markdown 叙事（输入仅聚合 JSON，不含 note 原文）；LLM 失败降级纯数据版。REST `GET /api/dashboard/weekly-report?week_offset=&narrative=` 与 MCP 工具同源；
- `apply` 对未收藏岗位自动建卡再流转（一次调用两个 service 方法）；
- stdio 排雷：PDFBox 传入的 commons-logging 会向 stdout 打印发现警告（污染协议流），已在 core pom 排除；日志走 logback System-Err。

### 6.5 Claude Desktop 接入

```json
// ~/Library/Application Support/Claude/claude_desktop_config.json
{
  "mcpServers": {
    "jobradar": {
      "command": "java",
      "args": ["-jar", "/path/to/backend/jobradar-mcp-server/target/jobradar-mcp-server-0.1.0-SNAPSHOT.jar"],
      "env": { "DeepSeek_API_KEY": "…", "DASHSCOPE_API_KEY": "…" }
    }
  }
}
```
构建：`cd backend && mvn -pl jobradar-mcp-server -am -DskipTests package`；验证：`mcp_smoke.py` 式 initialize/tools_list 握手（见 W4-1 开发记录）。

## 7. Prompt 资产管理

- system prompt 集中在 `jobradar-core/src/main/resources/prompts/`（match.st / parse-jd.st / parse-resume.st / weekly-report.st），用 Spring AI 的 `PromptTemplate` 加载，代码与 prompt 分离。
- 每次 Agent 输出记录 `model_used` + `prompt_version` 到 match_reports，支持效果回溯对比（A/B 叙事点）。

## 8. 简历叙事要点（v0.2 按 Java 栈更新）

1. **Java + Spring AI 的 Agent 工程实践**：区别于市面 90% 的 Python 调 API 项目；Spring AI ChatModel 多模型路由、结构化输出、token 成本记账；
2. **MCP 协议服务端**：基于 Spring AI MCP Starter 将领域系统工具化，理解"Agent 时代应用的新形态"；
3. **pgvector 双阶段检索-评分引擎**：HNSW 粗筛控成本 + LLM 精评保质量；
4. **领域特化**：针对军工所/央国企硬性门槛（学历/专业/应届/政治面貌）的 rule+LLM 混合校验；
5. **企业级工程**：Maven 多模块、Flyway 迁移、Testcontainers 集成测试、公司别名归一化去重。
