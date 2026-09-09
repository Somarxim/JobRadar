# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-09 |
| 分支 | feat/w3-daily-recommend |
| 类型 | feat |

## 改动内容

W3-3 每日推荐管线 + Dashboard 今日推荐区：推荐从「设计文档」变为「每日自动产出」。

**后端（jobradar-core / jobradar-app）：**
- 新增 `RecommendService`（core）：候选池（近 36h 新岗位 − 已进看板 − 近 7 天推过）
  → 规则粗筛打分 → Top 20 进 LLM 精评（复用 `MatchingService.match`，吃 24h 缓存）
  → hardPass 优先 + 总分排序取 Top 5 落 `recommendations` 表。
- 粗筛规则（`coarseScore`，纯函数可单测）：方向词 15/个（封顶 45，词表来自
  docs/target-sources.md §1）+ 目标岗位 20 + 目标城市 15 + 薪资透明 5
  + 截止日有效 5 + 近 3 天发布 10。
- 降级链（每层失效都有产出）：无默认简历 → 纯方向词粗排；LLM 不可用/超预算 →
  粗筛直出（reason 注明「AI 精评不可用」）；单岗精评失败 → 跳过不影响整轮。
- 幂等：同日重跑先清当日 PENDING 再重算（用户已 accept/ignore 的保留）；
  (job_id, rec_date) 唯一约束兜底；7 天防重复窗口。
- 反馈闭环 `feedback()`：accept → 自动建 COLLECTED 看板卡片（已有则不重复建）；
  ignore → 记 feedback_tag（回流评估推荐质量）。
- `RecommendationRepository`：今日列表 / 近 N 天推过的岗位 id 批量查询（JPQL 投影）。
- `MatchingService.match` 事务传播 REQUIRED → **REQUIRES_NEW**（见概念卡）。
- `RecommendController`：GET /today、POST /{id}/feedback、POST /run（手动调试，
  与定时调度同一入口）；`RecommendScheduler`：cron 07:45（爬虫 07:30 之后），
  `jobradar.recommend.enabled` 可关停。

**前端：**
- Dashboard 新增「今日推荐」区（`RecommendationSection`）：位次、公司·岗位、
  分数徽章（降级时显示「规则粗排」）、一句话理由、截止日；感兴趣 → accept 自动进看板，
  忽略 → ignore；已处理卡片降透明度保留（当天可回溯）；「立即推荐」手动触发。
- 推荐区独立加载：接口失败不拖垮整个仪表盘。

## 改动原因

roadmap W3-3：「每日推荐管线（@Scheduled）+ Dashboard 今日推荐区 + 反馈闭环」。
对应 agent-design.md §5 的落地——与文档的差异：粗筛用规则打分而非 pgvector
embedding（当前日增量 ~170 条，全量规则打分成本可忽略，embedding 是过早优化，
留待数据规模需要时上）。

## 技术概念卡

### Spring 事务陷阱：REQUIRED 内层异常污染外层事务
- 是什么：`@Transactional` 方法加入既有事务后抛出 RuntimeException，会把整个
  外层事务标记 rollback-only——即使调用方 catch 住，提交时仍炸
  `UnexpectedRollbackException`。
- 项目里哪里用了：`RecommendService.runPipeline` 循环逐岗调 `MatchingService.match`
  并 catch 单岗失败继续；`match` 改为 `REQUIRES_NEW` 后单岗失败只回滚自己的事务，
  已产出报告作为 24h 缓存独立保留。Controller 直调场景（本是最外层）行为不变。
- 面试可能追问：REQUIRES_NEW 的代价？（挂起外层事务、占第二条连接；内层读不到
  外层未提交的写入——本场景外层只删了当日 PENDING 推荐，内层不读该表，安全）

### N+1 查询与批量投影
- 是什么：循环里逐条 `existsByJobId(...)` = 候选 N 条就 N 次 SQL；用 JPQL 投影
  `select r.job.id ...` 一次取回排除集在内存里过滤。
- 项目里哪里用了：`RecommendationRepository.findJobIdsByRecDateGreaterThanEqual`
  + `ApplicationRepository.findByJobIdIn`，候选过滤从 ~2N 次查询降为 3 次。

### 读操作幂等 vs 写操作幂等
- 推荐管线重跑不是「查到已有就跳过」，而是「清掉未处理的、保留已反馈的」——
  幂等的粒度是用户资产（反馈记录），不是数据行。

## 影响范围

- 新表无改动（recommendations 表 V2 已建）；新增 API ×3；Dashboard 页面结构变化。
- `MatchingService.match` 事务传播变化：对既有调用方（MatchController）无行为差异。
- 每日 LLM 成本上限：粗筛出线 20 条 × 精评 ~1.5k token ≈ 3 万 token/天，
  受 W2-6 日预算闸保护；24h 缓存让重跑近乎免费。

## 验证方式

- 单测 `RecommendServiceTest` ×4：方向词命中与 45 封顶、目标岗位/城市加分、
  时效/信息质量加分、全 null 字段健壮性。
- 集成测试 `recommendPipelineDegradesIdempotentAndClosesLoop`（Testcontainers 真 PG）：
  三类排除（无关键词/已进看板/7 天窗口）、无简历降级、重跑幂等（同日同岗不重复）、
  accept 自动建看板、ignore 记标签、已反馈记录重跑保留。
- 全量套件绿：单测 23 + 集成 7。
- 真实 E2E（共享 dev 库 + DeepSeek 在线）：首轮 候选 173 → 粗筛 20 → 精评 20 →
  推荐 5（83~88 分，oneLiner 理由可读）；accept 牛客「agent开发」→ 看板自动出现卡片；
  ignore 记 tag；重跑 候选 171（-2 精确排除）+ 清理 3 条 PENDING + 已反馈保留。

## 遗留问题

- 重跑后已处理推荐保留原位次、新 PENDING 从 1 重排 → 同日 rank 不唯一（有意为之：
  处理过的卡片原地留痕，避免用户找不到刚点过的项）；若观感混乱可后续把已处理项
  沉底展示（前端排序即可，不动表）。
- 粗筛词表是静态常量；W3-5 语义搜索上线后可换 embedding 粗筛，词表退化为兜底。
- 牛客关键词矩阵仍待迭代（「Java后端」命中少）；国聘网登录态方案优先级待定。
