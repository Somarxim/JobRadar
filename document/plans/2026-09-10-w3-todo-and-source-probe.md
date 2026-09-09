# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-10 |
| 分支 | feat/w3-todo-auto-gen |
| 类型 | feat |

## 改动内容

三件事：待办事项自动生成（设计修改，已获用户授权）、爬虫 QA 素材沉淀、新数据源探测 + 智联解析器落地。

1. **待办自动生成（流转驱动）**：
   - `ApplicationService`：新增 `DEFAULT_NEXT_ACTION` 阶段默认待办表（收藏→"评估是否投递"、计划→"完成投递"、已投递→"跟进进度，准备笔试"、笔试→"参加笔试"、面试→"参加面试"、offer→"确认 offer"）；create 时按阶段填充，transition 时经 `applyAutoNextAction` 自动替换，终态（rejected/withdrawn）清空；
   - 覆盖规则：当前文案为空或等于 from 阶段默认文案（= 未被用户改过）才替换；用户 PATCH 过的自定义文案流转时绝不覆盖；自动待办一律 `next_action_at = null`（无时间 = "尽快"）；
   - `ApplicationRepository`：待办查询从 `NextActionAtNotNull` 改为 `findNextActions()`（JPQL：next_action 非空 + 排除终态 + CASE WHEN 实现无时间排最前）；
   - `DashboardService.summary` 换用新查询；前端 `DashboardPage` 待办时间为 null 时显示"尽快"（琥珀色），`types.ts` 的 `next_action_at` 改 `string | null`。
2. **爬虫 QA 素材**：`docs/learning/crawler-engineering.md` 按四段模板成文（Jsoup/Playwright 取舍、三层去重、国聘登录墙、SPA 软 404、脏数据清洗、162 条的由来、失败隔离、合规边界等 9 组 Q&A），README 清单勾掉。
3. **新数据源探测**（curl 实测，结论落 V6 迁移注释）：
   - 智联招聘：`POST fe-api.zhaopin.com/c/i/search/positions` **匿名可用**（JSON body + Referer 头即可），落地 `ZhaopinSearchParser`（parser=`zhaopin-search`）+ 单测；但无结构化校招过滤（campusTypeSearch 等参数实测不生效），社招噪音大 → 源落库**默认禁用**；
   - 前程无忧 51job：搜索页与匿名 API 均被**阿里云 WAF JS 挑战**拦截，禁用记录结论；
   - 应届生求职网：主站变 App 下载页，职位搜索迁至 q 子站，同被阿里云 WAF 拦截，禁用记录结论。

## 改动原因

- 用户反馈：收藏/笔试中/面试中的岗位都不出现在待办模块——根因是 `next_action` 只能手动 PATCH 设置，create/transition 从不生成，待办区必然长期为空；
- 用户授权："允许你做待办事项的设计修改"；
- 用户要求：爬虫反爬经历沉淀为面试 QA 素材；探测智联/51job 等央国企常用渠道评估数据源扩展。

## 技术概念卡

### JPQL CASE WHEN 排序（nulls-first）
- 是什么：JPQL 不直接支持 NULLS FIRST 语法，用 `CASE WHEN col IS NULL THEN 0 ELSE 1 END` 作为第一排序键等价实现。
- 项目里哪里用了：`ApplicationRepository.findNextActions()`——无时间的自动待办（"尽快"）排在有时间的之前。
- 面试可能追问：为什么不用数据库原生 NULLS FIRST？（JPQL 可移植性；Hibernate 6 支持 `nulls first` 但属方言扩展）

### 系统默认值 vs 用户自定义的共存策略
- 是什么：同一字段（next_action）既要系统自动维护又要尊重用户修改——用"当前值是否等于系统默认值"判定归属，默认值可替换、自定义值不触碰；不加额外标志列。
- 项目里哪里用了：`ApplicationService.applyAutoNextAction`（create/transition 唯一写入口）。
- 面试可能追问：判等法有什么边界？（用户故意填了和默认一样的文案会被当自动——无害，语义本就一致）；更严谨的做法？（加 `next_action_auto` 标志列，多一次迁移，本项目量级不必要）

### WAF JS 挑战（阿里云 WAF）
- 是什么：反爬不是返回 403，而是返回一个带混淆 JS 的挑战页，浏览器执行 JS 算出 cookie 后放行；纯 HTTP 客户端（Jsoup/curl）拿到的是挑战页而非数据。
- 项目里哪里用了：51job/应届生网探测结论（V6 注释）；识别特征 = 响应里 `aliyun_waf_aa` meta + `renderData` textarea。
- 面试可能追问：怎么绕？（Playwright 执行 JS；或逆向挑战算法——投入产出不划算，个人项目选择放弃并记录结论，见 docs/learning/crawler-engineering.md Q2/Q9）

## 影响范围

- **行为变化**：新建/流转投递记录会自动生成阶段默认待办（无时间）；Dashboard 待办区从"只显示手动设置时间的条目"变为"显示所有有待办动作的非终态条目"；存量数据不受影响（旧记录 next_action 仍为 null，流转一次后即获得自动待办）；
- **接口**：`/dashboard/summary` 的 `next_actions[].next_action_at` 可能为 null（前端已适配）；无破坏性变更；
- **数据迁移**：V6 新增 3 条禁用的 crawl_sources 记录（智联/51job/应届生），不影响启用中的源；
- **新增解析器**：`zhaopin-search` 已注册可用，但唯一引用它的源是禁用状态——不改变每日抓取行为。

## 验证方式

- 后端：`mvn -pl jobradar-app -am test` 全绿——core 25/25（含新增 ZhaopinSearchParserTest 2 条）、app 集成测试 9/9（Testcontainers 实跑 V6 迁移 + 新增 autoNextActionLifecycle：自动替换/终态清空/自定义保留/终态排除/nulls-first 排序断言；applicationTransitionWritesEvents 补自动待办断言）；
- 前端：`npx tsc --noEmit` 通过；
- 智联 API 探测为 curl 实测（含字段映射、字符串参数兼容、"2026届"关键词噪音验证、详情页 Security Verification 确认）。

## 遗留问题

- 智联源启用前的关键词调优（社招噪音）：候选方向——更精准关键词（"2026届校招 Java"）、或按 property=国企/事业单位 在后处理过滤（需 RawJobPosting 加字段）；
- 待办模块仍无前端编辑入口（PATCH /applications/{id} 的 next_action/next_action_at 只能走 API）；看板卡片上补"编辑待办"是对应后续项；
- 51job/应届生如需接入，路径是 Playwright 过 WAF（重的）或用户浏览器态插件（轻的，与 Chrome 插件路线一致）；
- 国聘登录态问题：见本次答复（账号借用可行性 + 封控风险分析），代码侧维持禁用。
