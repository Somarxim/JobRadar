# JobRadar Agent 与 MCP 设计

| 版本 | 日期 | 状态 |
|---|---|---|
| v0.1 | 2026-09-03 | 初稿 |

本文档是项目的 AI 核心设计，覆盖：LLM Provider 抽象、简历解析、JD 结构化、匹配 Agent、每日推荐 Agent、MCP Server。

## 1. LLM Provider 抽象（packages/llm）

### 1.1 接口定义

```python
class LLMProvider(Protocol):
    name: str

    async def complete(self, prompt: str, *, system: str = "", max_tokens: int = 4096) -> str:
        """自由文本补全"""

    async def structured(self, schema: type[T], prompt: str, *, system: str = "") -> T:
        """结构化输出：schema 为 Pydantic 模型，底层用 JSON mode / tool use 实现，自动校验+重试1次"""

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """文本向量化（Provider 不支持时降级到本地模型）"""
```

### 1.2 实现与路由

| Provider | 协议 | 定位 |
|---|---|---|
| `ClaudeProvider` | Anthropic Messages API（tool use 做结构化） | 精评、周报等质量敏感任务 |
| `DeepSeekProvider` | OpenAI 兼容协议（JSON mode） | 批量解析、清洗等成本敏感任务（默认） |
| `KimiProvider` | OpenAI 兼容协议 | 备用/长文本（JD 特别长时） |

**任务路由**（配置驱动，`settings.llm_routing`）：

```yaml
routing:
  parse:    deepseek    # JD/简历结构化：便宜快速
  match:    deepseek    # 匹配精评：可切 claude 提质量
  report:   claude      # 周报/面试准备：表达质量优先
  embed:    deepseek    # embedding；不支持则自动降级本地 bge-m3
```

- `.env` 配置 key：`DEEPSEEK_API_KEY` / `ANTHROPIC_API_KEY` / `MOONSHOT_API_KEY`，`LLM_DEFAULT=deepseek`。
- Provider 工厂按任务类型返回实例；上游故障自动 fallback 到备用 Provider 并记 warning 日志。
- **成本控制**：所有调用记录 token 用量到 `settings`（llm_usage 累计），Dashboard 可展示——也是简历上"工程化"的细节。

## 2. Embedding 策略

| 方案 | 默认 | 说明 |
|---|---|---|
| Provider API（如 DeepSeek embedding） | ✅ 优先 | 零本地依赖，速度快 |
| 本地 bge-m3（sentence-transformers） | 降级方案 | 断网/无 key 时可用；首次下载 ~2GB |

统一封装 `Embedder.embed(texts)`，维度归一化处理（不同源向量不混用，切换时全量重算——记录 `embedding_model` 字段）。

向量化文本构造：
- 岗位：`f"{company_name} {title} {city}\n{jd_summary or jd_text[:1500]}"`
- 简历：由 parsed_json 拼「技能 + 项目摘要 + 求职意向」

## 3. 结构化 Schema 设计（Pydantic）

### 3.1 简历解析 schema（`ResumeProfile`）

```python
class Education(BaseModel):
    school: str; degree: str          # 本科/硕士/博士
    major: str; period: str           # "2022.09-2026.06"
    is_985: bool | None = None; is_211: bool | None = None  # LLM 推断，国企筛选常用

class Experience(BaseModel):
    type: Literal["internship", "project", "competition", "research"]
    org: str; role: str; period: str
    highlights: list[str]             # 量化成果要点

class ResumeProfile(BaseModel):
    name: str | None                  # 脱敏模式下为 None
    education: list[Education]
    skills: list[str]                 # 规范化技能词：["Python", "FastAPI", "MySQL", ...]
    experiences: list[Experience]
    target_positions: list[str]       # 求职意向（用户可手改）
    target_cities: list[str]
    awards: list[str]
    summary: str                      # LLM 生成的 100 字能力画像
```

解析流程：PDF → `pypdf` 提取文本 → `structured(ResumeProfile, ...)` → 用户确认页可编辑 → 定稿后生成 embedding。

### 3.2 JD 结构化 schema（`JobRequirements`）

```python
class HardRequirements(BaseModel):
    degree_min: Literal["大专","本科","硕士","博士"] | None
    majors: list[str] | None              # 要求专业（空=不限）
    graduation_years: list[int] | None    # 如 [2026]，应届身份要求
    city: str | None
    political_status: str | None          # 党员等（国企常见）
    other: list[str]                      # 其他硬性条件原文（如"英语六级"）

class JobRequirements(BaseModel):
    hard: HardRequirements
    skills: list[str]                     # 与简历技能同词表规范化
    nice_to_have: list[str]
    job_category: str                     # 后端/前端/算法/测试/嵌入式/产品...
    responsibilities: list[str]           # 职责要点（3-5 条）
```

**技能词表规范化**：prompt 中附带常见技能标准词表（约 200 个：语言/框架/工具/领域），要求 LLM 输出时归一（"golang"→"Go"，"mysql"→"MySQL"）。粗粒度匹配靠这个词表对齐，避免同义词失配。

### 3.3 匹配报告 schema（`MatchDetail`）

```python
class HardCheck(BaseModel):
    item: str            # "学历要求：硕士及以上"
    resume_value: str    # "硕士"
    passed: bool
    note: str = ""

class MatchDetail(BaseModel):
    hard_checks: list[HardCheck]     # 硬性条件逐条核对
    hard_pass: bool                  # 硬性条件整体是否通过
    score_total: int                 # 0-100
    score_breakdown: dict[str, int]  # {"skill":.., "experience":.., "fit":..}
    matched_skills: list[str]
    missing_skills: list[str]
    highlights: list[str]            # 简历中最契合的 2-3 个经历
    suggestion: str                  # 投递建议：是否投 + 简历侧重点 + 注意事项
    one_liner: str                   # 一句话结论（推荐列表展示用）
```

**打分规则**（写进 prompt 的评分基准）：
- `hard_pass=False` → score_total 上限 39，one_liner 必须点明不满足的硬性条件；
- 技能重合（40）+ 经历契合（40）+ 综合契合（20，含城市/薪资预期/公司类型偏好）；
- 分数校准锚点：≥85 强匹配必投 / 70–84 推荐 / 55–69 可投 / <55 不推荐。

## 4. 匹配 Agent（双阶段）

```
输入: job_id, resume_id
│
├─ Stage 1 粗筛（本地，零成本）
│   job.embedding vs resume.embedding 余弦相似度
│   sim < 阈值(0.35) 且无技能词交集 → 直接判低分(≤40)，不调 LLM
│
└─ Stage 2 精评（LLM，structured(MatchDetail)）
    system prompt: 角色(资深校招求职顾问，熟悉央国企/研究所招聘惯例)
                   + 评分基准 + 硬性条件核对要求 + 输出约束
    user prompt: JobRequirements(JSON) + JD原文(截断3000字) + ResumeProfile(JSON)
    ↓
    MatchDetail 校验入库 → 返回
```

**设计要点：**
- 精评输入用「结构化 JSON + JD 原文」双路：结构化保准确，原文防提取遗漏。
- 硬性条件核对放最前，LLM 先列 hard_checks 再打分（CoT 顺序约束，减少"忽略硬门槛给高分"的失败模式）。
- 失败重试：schema 校验失败重试 1 次（附错误信息）；仍失败落 `422`，岗位详情页提示人工阅读。
- 报告缓存：同 (job_id, resume_id) 24h 内复用最新报告；简历更新后主动失效相关报告。

## 5. 每日推荐 Agent（定时管线）

```
APScheduler cron: 每日 07:30（可配）
│
├─ 1. crawl      遍历 enabled 的 crawl_sources，增量抓取列表页+详情页
├─ 2. structure  新 raw 岗位 → LLM 结构化(JobRequirements) + 摘要 + 规范化
├─ 3. dedupe     dedupe_hash 查重，重复则合并 also_seen_on
├─ 4. embed      批量生成 embedding
├─ 5. coarse     与默认简历向量粗筛，取 Top 20（且 sim ≥ 0.35）
├─ 6. score      Top 20 → 匹配 Agent 精评 → MatchReport
├─ 7. select     hard_pass 优先 + 分数排序取 Top 5，写 recommendations(reason=one_liner)
└─ 8. notify     Dashboard 今日推荐区 + 可选 Webhook（Server酱/Telegram）
```

**工程要点：**
- 管线每步幂等，单源失败隔离（try/except 记日志继续），整体产出「运行报告」（新增/重复/失败数）。
- LLM 成本上限：每日结构化 ≤ 100 条 + 精评 ≤ 20 条，超出截断（成本控制闸）。
- 推荐反馈闭环：accept → 自动建 application；ignore → 记录原因（可选标签：城市不符/方向不符/已投过），反馈数据用于后续调阈值。

## 6. MCP Server（apps/mcp-server）★

基于官方 `mcp` Python SDK（FastMCP），stdio 传输，Claude Desktop 直接接入。内部复用 `apps/api/services` 业务层与同一 SQLite。

### 6.1 Tools

| Tool | 参数 | 返回 | 示例话术 |
|---|---|---|---|
| `search_jobs` | `query?, company_type?, city?, stage?, min_match?, limit=10` | 岗位摘要列表 | "帮我找成都的国企后端岗" |
| `get_job_detail` | `job_id` | 岗位详情+匹配报告 | "128 号岗位详情给我看看" |
| `add_job` | `raw_text, url?` | 解析结果+job_id | "我复制了个 JD，帮我录入：……" |
| `apply` | `job_id, channel, note?, applied_at?` | application 对象 | "我今天官网投了 128 号岗位" |
| `update_stage` | `job_id, to_stage, note?` | 流转结果 | "腾讯进面试了"（自动匹配岗位） |
| `set_next_action` | `job_id, action, at` | 更新结果 | "提醒我周五前做腾讯笔试" |
| `today` | — | 今日待办（计划投递+DDL+next_action） | "我今天要干什么" |
| `weekly_report` | `week_offset=0` | 周报 Markdown | "帮我复盘这周投递情况" |
| `match_job` | `job_id, resume_id?` | MatchDetail | "这个岗位和我匹配吗" |
| `recommend_today` | — | 今日推荐 Top 5 | "今天有什么值得投的岗位" |

**设计原则：**
- 所有 tool 返回**结构化 + 自然语言摘要**双格式（LLM 消费结构化，用户看摘要）。
- `apply`/`update_stage` 等写操作幂等友好：重复调用返回当前状态而非报错。
- 模糊匹配容错：`update_stage(job: "腾讯")` 时按公司名模糊查 application，多个候选时返回列表让 LLM 追问——**不猜不写**，写操作必须确认到唯一记录。

### 6.2 Resources

| URI | 内容 |
|---|---|
| `jobradar://stats/funnel` | 实时投递漏斗 JSON |
| `jobradar://jobs/{id}` | 单岗位完整信息 |
| `jobradar://resume/default` | 默认简历结构化画像 |

### 6.3 Prompts

| Prompt | 用途 | 核心模板变量 |
|---|---|---|
| `prep_interview` | 面试准备：JD + 简历 → 高频问题清单 + 项目深挖点 + 反问建议 | job_id |
| `weekly_review` | 周复盘引导：数据 + 漏斗转化分析 + 下周策略建议 | week_offset |
| `jd_gap_analysis` | 缺口分析：某岗位要求 vs 简历，生成学习计划建议 | job_id |

### 6.4 安全

- 仅绑定 stdio（无网络端口），权限边界 = Claude Desktop 会话。
- 写操作 tool 在描述中明确标注 `destructiveHint: false, idempotentHint: true`（MCP tool annotations），让宿主正确渲染确认 UI。

## 7. Prompt 资产管理

- 所有 system prompt / 输出 schema 集中在 `packages/agent/prompts/`，按场景分文件（match.md / parse_jd.md / parse_resume.md / weekly_report.md），代码与 prompt 分离，便于迭代与版本 diff。
- 每次 Agent 输出记录 `model_used` + prompt 版本号到 match_reports，支持效果回溯对比（A/B 叙事点）。

## 8. 简历叙事要点（本项目 AI 部分的可讲点）

1. **MCP 协议实践**：将领域系统工具化暴露给 LLM，理解"Agent 时代应用的新形态"；
2. **双阶段检索-评分引擎**：embedding 粗筛控成本 + LLM 精评保质量，工程与效果的平衡；
3. **结构化输出工程**：Pydantic schema 约束 + 校验重试 + 失败降级，解决 LLM 输出不稳定；
4. **领域特化**：针对央国企/研究所硬性门槛设计 rule+LLM 混合校验，非通用套壳；
5. **数据闭环**：推荐反馈回流调优，prompt 版本化管理与效果回溯。
