# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-10 |
| 分支 | feat/w4-weekly-report |
| 类型 | feat |

## 改动内容

W4-2 周报 Agent 落地（替换 W4-1 的确定性占位版）：

1. **core 新增 `WeeklyReportService`**：两层结构。
   - 数据层（确定性 SQL，永远可用）：周区间（周一至周日）流转事件流水、阶段流入计数、
     投递数 vs 周目标 vs 上周环比、新收录岗位数、推荐生成/采纳/忽略计数；
     `weekOffset` 0=本周、负数回看历史周、正数抛 400。
   - 叙事层（LLM，可选）：`narrative=true` 时把统计 JSON（仅数字+公司+流向，不含 note 原文）
     交给模型写 Markdown 复盘；失败/未启用降级 `llm_generated=false`（纯数据版，功能不中断）。
2. **`LlmService.narrateWeeklyReport`**：首个自由文本生成任务（区别于既有抽取式任务），
   不做结构化约束，质量靠 prompt「只许用给定数据 + 固定四节结构 + 300-500 字」约束；
   温度 0.6（叙事专用常量）；记账 task=`weekly_report`，复用每日 token 成本闸。
3. **REST**：`GET /api/dashboard/weekly-report?week_offset=0&narrative=false`（narrative 按需开启，
   前端「AI 复盘」按钮触发，避免每次进页面烧 token）。
4. **MCP `weekly_report` 重写**：接 WeeklyReportService，week_offset 支持历史周；
   Markdown 组装（统计 → 事件流水 → AI 叙事），降级时明示「纯数据版」。
5. 仓储按需补查询：事件区间查询/区间阶段计数、岗位区间计数、推荐区间计数（生成/按状态）。

## 改动原因

- roadmap W4-2；W4-1 遗留项「weekly_report 当前是确定性数据组装，LLM 叙事版归 W4-2」的兑现；
- 设计原则延续：**确定性数据打底，LLM 只做增值叙事**——LLM 挂了周报照样能用（面试叙事点）。

## 技术概念卡

### 确定性数据 + LLM 叙事的分层
- 是什么：数字统计用 SQL 聚合保证准确（LLM 会算错数），LLM 只负责把数据「讲出来」；
  输入只给聚合 JSON 而非原始记录，既省 token 又天然防编造（prompt 再约束一层）。
- 项目里哪里用了：`WeeklyReportService.report()` → `LlmService.narrateWeeklyReport()`；
  降级路径 `llm_generated=false`。
- 面试可能追问：为什么不让 LLM 自己查数据？（function calling 让模型逐条查事件 = N 次调用 +
  幻觉空间；一次性喂聚合 JSON = 1 次调用 + 数据可校验。取舍：灵活性换确定性，周报场景确定性赢。）

### 时间区间口径
- 周区间统一「周一 00:00 ~ 下周一 00:00（左闭右开）」，环比基准取上一整周而非「本周至今 vs 上周至今」——
  口径可比性优先；时区用服务器本地时区（单用户本地应用，直觉一致）。

## 影响范围

- **core**：新增 WeeklyReportService + 3 个 DTO record（DashboardDtos 内）；LlmService 加叙事方法；
  ApplicationEventRepository/JobRepository/RecommendationRepository 各加区间查询（纯新增，旧方法不动）；
- **app**：DashboardController 加 `/weekly-report` 端点；
- **mcp-server**：weekly_report 工具接新服务（描述更新，工具名/参数名不变，宿主侧无感知）；
- 无 schema 变化；无 API 契约破坏（新增端点）。

## 验证方式

- core 30/30：新增 WeeklyReportServiceTest 5 例（未来周 400、统计装配且 narrative=false 零 LLM 交互、
  -1 周区间正确、LLM 空响应降级、LLM 响应挂上）；
- app 10/10：新增 weeklyReportReflectsThisWeekEvents（真实 PG：流转后本周流水含该公司、applied≥1、
  未来周 400；共享库断言用包含式）；
- mcp 5/5 回归（weekly_report 走新服务，无 LLM key 下降版路径）；
- 全量 `mvn test`：45/45 绿。

## 遗留问题

- 前端「本周复盘」卡片（调 `/weekly-report?narrative=true` 渲染叙事）随 W4-3 Dashboard 图表一起做；
- 真实 LLM 叙事效果待开发库重启后人工抽查一次（记账表可查 token 消耗）；
- 运行中的开发后端需重启才加载新端点（合并后用户自行重启）。
