# JobRadar API 设计

| 版本 | 日期 | 状态 |
|---|---|---|
| v0.1 | 2026-09-03 | 初稿 |

Base URL：`http://127.0.0.1:8000/api`。风格：REST + Pydantic v2 schema，错误用 HTTP 状态码 + `{"detail": ...}`。分页参数：`page`（从 1 起）、`size`（默认 20，上限 100），分页响应统一为 `{items: [], total: n, page, size}`。

## 1. 端点总览

| 模块 | 方法 & 路径 | 说明 | 优先级 |
|---|---|---|---|
| Jobs | `GET /jobs` | 岗位库搜索（关键字/筛选/排序/语义） | P0 |
| | `POST /jobs` | 手动录入岗位 | P0 |
| | `GET /jobs/{id}` | 岗位详情（含 JD、匹配报告、投递状态） | P0 |
| | `PATCH /jobs/{id}` | 修正岗位信息 | P1 |
| | `POST /jobs/ingest` | 插件/粘贴导入（原始文本或 HTML → LLM 结构化） | P0（文本版）/P1（插件） |
| Applications | `GET /applications` | 看板数据源（按 stage 分组返回） | P0 |
| | `POST /applications` | 收藏/加入计划（创建 application） | P0 |
| | `PATCH /applications/{id}` | 更新备注/优先级/计划日/next_action | P0 |
| | `POST /applications/{id}/stage` | 状态流转（写事件留痕） | P0 |
| | `GET /applications/{id}/events` | 阶段历史时间线 | P0 |
| Dashboard | `GET /dashboard/summary` | 漏斗统计、本周新增、DDL 倒计时、推荐未读数 | P0 |
| | `GET /dashboard/calendar?month=YYYY-MM` | 日历事件（DDL/笔试/面试/计划） | P0 |
| | `GET /dashboard/todos` | 今日待办（今日计划投递 + 临期 DDL + next_action） | P1 |
| | `GET /dashboard/weekly-report` | 周报数据（投递数/转化/响应率） | P2 |
| Resumes | `POST /resumes` | 上传 PDF（异步解析） | P1 |
| | `GET /resumes` / `GET /resumes/{id}` | 列表/详情（含解析结果） | P1 |
| | `POST /resumes/{id}/reparse` | 重新解析 | P1 |
| | `PATCH /resumes/{id}/default` | 设为默认简历 | P1 |
| Match | `POST /match/jobs/{job_id}` | 触发匹配（可指定 resume_id） | P1 |
| | `GET /match/jobs/{job_id}` | 查最新匹配报告 | P1 |
| | `POST /match/batch` | 批量匹配（每日推荐管线复用） | P1 |
| Recommendations | `GET /recommendations/today` | 今日推荐 Top N | P1 |
| | `POST /recommendations/{id}/feedback` | `{action: accept}` → 建 application；`{action: ignore}` | P1 |
| Planning | `GET /goals/current-week` / `PUT /goals/current-week` | 周投递目标 | P1 |
| | `PATCH /companies/{id}` | 公司分级（dream/target/backup） | P1 |
| Crawler | `GET /crawler/sources` / `POST /crawler/sources` / `PATCH /crawler/sources/{id}` | 爬虫源管理 | P1 |
| | `POST /crawler/run` | 手动触发全量/单源爬取（异步任务） | P1 |
| Settings | `GET /settings` / `PUT /settings` | LLM provider、通知等配置 | P2 |

## 2. 关键端点详设

### 2.1 `GET /jobs` —— 岗位库搜索

**Query 参数：**

| 参数 | 类型 | 说明 |
|---|---|---|
| `q` | string | 关键字（FTS5：公司/岗位/JD 全文） |
| `semantic` | bool | `true` 时改用向量语义搜索（q 为自然语言描述） |
| `company_type` | enum | internet/soe_central/soe_local/institute/bank/foreign |
| `city` | string | 城市 |
| `stage` | enum | 按投递阶段筛（含 `none` = 未收藏的新发现岗位） |
| `tier` | enum | 公司目标分级 |
| `deadline_before` | date | DDL 早于某日 |
| `min_match` | int | 匹配分下限（联表最新 match_report） |
| `sort` | enum | `created_desc`(默认) / `deadline_asc` / `match_desc` |

**响应示例：**
```json
{
  "items": [{
    "id": 128,
    "company": {"id": 12, "name": "中国电科XX研究所", "company_type": "institute", "tier": "none"},
    "title": "嵌入式软件工程师（2026校招）",
    "city": "成都",
    "salary_range": "18-25万/年",
    "source_platform": "official",
    "source_url": "https://...",
    "deadline": "2026-10-15",
    "jd_summary": "面向2026届硕士，嵌入式Linux驱动方向……",
    "match_score": 82,
    "application_stage": null,
    "created_at": "2026-09-03T02:11:00"
  }],
  "total": 46, "page": 1, "size": 20
}
```

### 2.2 `POST /jobs/ingest` —— 统一导入入口（插件/粘贴共用）

**请求：**
```json
{
  "source": "extension | manual_paste",
  "url": "https://www.zhipin.com/job_detail/xxx",       // 可选
  "raw_text": "职位描述全文……",                          // raw_text / page_html 二选一
  "page_html": "<div class=job-detail>…</div>",          // 插件抓的 DOM 片段
  "hints": {"company": "华为", "title": "通用软件开发工程师"}  // 插件预提取，可选
}
```

**处理流程**：有 hints 先用；raw_text/page_html → LLM 结构化（schema 见 agent-design.md §3.2）→ 规范化 → dedupe_hash 查重 → 新建或合并 → 异步生成 embedding。

**响应：**
```json
{
  "job_id": 128,
  "already_exists": false,
  "parsed": {"company": "华为", "title": "通用软件开发工程师", "city": "成都", "deadline": null},
  "warnings": ["deadline 未能从文本中识别，请人工确认"]
}
```

### 2.3 `POST /applications/{id}/stage` —— 状态流转

**请求：**
```json
{
  "to_stage": "applied",
  "note": "官网投递，上传了秋招v3简历",
  "channel": "official",          // to_stage=applied 时必填
  "occurred_at": "2026-09-03T20:30:00"   // 缺省=当前时间（补录历史投递时显式传）
}
```

**响应：** 更新后的 application 对象（含最新 events）。服务端事务：`applications` 更新 + `application_events` 插入；`to_stage=applied` 时回填 `applied_at`。

### 2.4 `GET /dashboard/summary`

```json
{
  "funnel": {"collected": 34, "planned": 12, "applied": 45, "written_test": 8, "interview": 5, "offer": 1, "rejected": 9},
  "this_week": {"applied": 6, "goal": 10, "new_jobs": 87, "recommendations_unread": 5},
  "upcoming_deadlines": [
    {"job_id": 128, "company": "中国电科XX研究所", "title": "嵌入式软件工程师", "deadline": "2026-09-07", "days_left": 4}
  ],
  "next_actions": [
    {"application_id": 55, "company": "腾讯", "next_action": "完成行测", "next_action_at": "2026-09-04T19:00:00"}
  ]
}
```

### 2.5 `GET /dashboard/calendar?month=2026-09`

```json
{
  "events": [
    {"date": "2026-09-07", "type": "deadline", "title": "中国电科XX研究所 投递截止", "job_id": 128},
    {"date": "2026-09-09", "type": "written_test", "title": "腾讯 笔试", "application_id": 55},
    {"date": "2026-09-10", "type": "planned", "title": "计划投递：航天X院", "application_id": 61}
  ]
}
```
事件类型：`deadline` / `written_test` / `interview` / `planned` / `next_action`。

### 2.6 `POST /match/jobs/{job_id}`

**请求**：`{"resume_id": 2}`（缺省用默认简历）
**响应**：完整 MatchReport（schema 见 agent-design.md §3.4）。同步返回（精评一次 LLM 调用，约 3–10s），前端显示 loading；批量场景走 `/match/batch`（异步任务 + 轮询）。

## 3. 约定与规范

- **时间**：全部 ISO 8601 带时区存储，API 输出 UTC，前端本地化为东八区。
- **鉴权**：本地单用户，插件请求头带 `X-Local-Token`（settings 中生成，防其他网页恶意 POST 本地 API）。浏览器前端同源代理无需 token。
- **幂等**：`/jobs/ingest` 天然幂等（dedupe_hash）；stage 流转非幂等但重复流转同 stage 直接返回当前态（不重复写事件）。
- **错误码**：`400` 参数错 / `404` 资源不存在 / `409` 冲突（重复收藏）/ `422` LLM 解析失败（返回 raw_text 引导人工录入）/ `502` LLM 上游故障。
- **CORS**：仅放行 `chrome-extension://<id>` 与 vite dev server。
