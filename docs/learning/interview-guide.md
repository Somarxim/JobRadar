# 面试讲解指南（JobRadar）

> W4 汇总交付：把四周开发记录（document/plans/）里的设计取舍与概念卡，组织成面试可直接使用的材料。
> 结构：3 分钟话术（STAR）→ 白板讲解路径 → 深度追问 Q&A 树 → 可引用数字。
> 使用方法：先脱稿讲 3 分钟版，再按 Q&A 树自测——答不出的回到对应开发记录/源码复习。

## 1. 三分钟项目介绍（STAR）

**S（情境）**：2026 秋招，目标方向是军工研究所/央国企/银行的技术岗。这类岗位信息分散在官网、公众号海报、国聘、牛客等平台，互不互通；投递进度靠备忘录记，漏投、忘笔试是常态。

**T（任务）**：做一个个人求职中台：多源信息统一归集、投递全生命周期管理、AI 辅助决策（投不投、怎么准备）。

**A（行动）**：Java 21 + Spring Boot + Spring AI 技术栈，Maven 多模块（core/app/mcp-server）：
1. **统一岗位库**：Chrome 插件一键收藏、JD 文本/海报图片 AI 解析导入、定向爬虫每日扫描——三条采集通道共用一套「公司别名归一 + dedupe hash」去重管线；
2. **投递管理**：看板流转 + 事件溯源（每次流转留痕），系统自动生成阶段待办，日历/DDL 提醒；
3. **AI 能力**：简历结构化档案 ↔ 岗位匹配精评（硬性条件逐条核对）、每日推荐管线（粗筛 → LLM 精评 → 反馈闭环）、周报 Agent（确定性数据 + LLM 叙事分层）；
4. **MCP Server**：把整个数据库封装成 10 个 MCP 工具暴露给 Claude Desktop，自然语言管理投递全流程。

**R（结果）**：系统真实在用——爬虫首轮收录 162 个岗位，每日推荐 07:45 自动产出 Top5；46 个自动化测试（Testcontainers 真实 PostgreSQL）；LLM 调用全程记账 + 每日 token 成本闸，成本可控。

**一句话收尾（可选）**：这个项目对我同时是工具和演练场——目标雇主的技术栈是 Java/Spring，我用真实需求把这套栈从零基础练到了能讲清取舍的程度。

## 2. 白板架构讲解路径

自顶向下五层，边画边讲（每层一个「为什么这么设计」）：

```
┌─ 入口层 ────────────────────────────────────────┐
│  Web UI (React)   Chrome 插件(MV3)   Claude Desktop │
└──────┬────────────────┬──────────────────┬─────────┘
       │ REST+token     │ REST+token       │ stdio/JSON-RPC (MCP)
┌──────┴────────────────┴──────────────────┴─────────┐
│  jobradar-app (Spring Boot REST)   jobradar-mcp-server │  ← 两个进程，同一个 core
├──────────────────────────────────────────────────────┤
│  jobradar-core：领域服务（去重/流转/推荐/匹配/周报）      │
│  + LLM 门面（多模型路由、结构化输出、记账、成本闸）         │
├──────────────────────────────────────────────────────┤
│  PostgreSQL 16 + pgvector（Flyway 迁移在 core）          │
└──────────────────────────────────────────────────────┘
```

- **为什么两个进程共享 core**：MCP Server 是 stdio 子进程（Claude Desktop 拉起），不是 Web 应用——配置/装配下沉 core，app 与 mcp-server 只是两种「壳」；
- **为什么调度只在 app**：爬虫/推荐定时任务若两进程都跑会重复执行——单一调度者原则；
- **为什么迁移脚本在 core**：schema 与实体同模块，任何进程启动都能校验（ddl-auto=validate）。

## 3. 深度追问 Q&A 树

按「为什么这么设计 → 怎么实现的 → 遇到什么问题 → 如何验证」四层组织。

### 3.1 技术选型

**Q：为什么用 Java/Spring 而不是你更熟的 Python？**
ADR-0（docs/architecture.md）：目标雇主（军工所/运营商/银行）后端以 Java/Spring 为主流，项目本身是实战备战；Spring AI 提供了不亚于 Python 生态的 LLM 抽象（ChatModel 统一接口、结构化输出、MCP Server Starter）。同时验证「AI 辅助下快速补齐一门技术栈」的学习方法论——docs/learning/ 整套笔记就是证据链。

**Q：Maven 多模块怎么划的？为什么不是单模块？**
core（领域+基础设施，无 Web 依赖）/ app（REST API + 调度）/ mcp-server（MCP 协议壳）。切分动机在 W4 被验证：MCP Server 需要独立进程（stdio），复用 core 全部领域能力但零 Web 容器。如果一开始是单模块，拆分时要动所有装配——多模块的边界成本在 W1 就付了，W4 收获。

**Q：为什么 Flyway 而不是 ddl-auto=update？**
ddl-auto 的隐式 schema 演进不可审查、不可回滚、多人/多环境漂移。Flyway 版本化迁移是显式的、进 Git 的、可评审的。生产环境 ddl-auto=validate（只校验不修改），schema 唯一事实来源是迁移脚本。迁移脚本放 core 模块：schema 与实体同一事实源，app/mcp-server 任何进程启动都过 validate。

### 3.2 数据与一致性

**Q：投递状态机怎么设计的？流转历史怎么追？**
`applications.stage` 是当前态，`application_events` 是只追加的流转日志（from_stage/to_stage/note/occurred_at）——事件溯源的极简版：不写 Event Sourcing 框架，只保留「当前状态 + 完整轨迹」两个事实，支撑周报统计、笔试面试日程、问题回溯。补录历史投递时可显式回写 occurred_at（所以不用 @CreationTimestamp）。

**Q：去重怎么做的？**
三层：① 内容层 dedupe_hash（公司名归一化 + 岗位名 + 城市的 SHA-256，唯一约束兜底并发）；② 语义层公司别名表（company-aliases.yml，25 条映射，如「航天科技一院」→「中国航天科技集团有限公司」）；③ 同源多平台重复出现时合并 also_seen_on（同一岗位在牛客和官网都看到，记录渠道而非重复建卡）。

**Q：待办事项的「自动生成」和用户自定义怎么共存？**
流转驱动：每个阶段有默认待办文案（如 applied→「跟进进度，准备笔试」）。流转时仅当当前文案为空或等于上一阶段默认值（=没被用户改过）才替换；用户自定义文案永不被覆盖。终态（rejected/withdrawn）自动清空。不需要 is_custom 标志列——「等于默认值」本身就是判别式，少一个状态就少一类不一致。

### 3.3 LLM 工程

**Q：多模型怎么接的？换模型成本？**
OpenAI 兼容协议：DeepSeek、阿里 DashScope 都实现了 OpenAI 的 /chat/completions，一套 OpenAiChatModel 换 base-url/api-key 即切换供应商——面向协议编程优于面向供应商 SDK 编程。两个模型实例（parse 文本 / vision 视觉）显式构造，不用自动装配（多实例表达力不够）。

**Q：LLM 输出不稳定怎么办？**
三板斧：① 结构化输出用 BeanOutputConverter（record → JSON Schema 注进 prompt → 自动反序列化）；② 质量敏感任务（匹配评估）失败重试 1 次，把上次错误信息喂回去让模型自我修正；③ 仍失败返回 Optional.empty()，调用方降级（422 人工兜底/规则兜底/纯数据版）。**核心原则：LLM 是增强不是依赖**——没配 key 时系统全功能可用，只是 AI 能力自动降级。集成测试就是靠强制置空 key 来覆盖降级路径的。

**Q：成本怎么控制？**
全链路记账：每次调用（成功/失败）写 llm_usage（task/model/三档 token/耗时/错误摘要），记账用 REQUIRES_NEW 独立事务——否则调用方业务回滚会把失败账一并回滚掉，而失败账恰恰是排查依据。成本闸：每日 token 限额，超闸拒发并记账。匹配报告 24h 缓存，同岗位同简历不重复烧钱。

**Q：海报图片解析为什么是两段式？**
一步到位「图片→JSON」会把视觉模型的发挥空间限制死在 schema 上，且调试 prompt 时每次都要烧视觉模型的 token。两段式：视觉模型先忠实转录全部文字（转录稿本身落库做存档），文本模型再做结构化。调试迭代只花文本 token，成本差一个数量级。

**Q：匹配 Agent 怎么防止「忽略硬门槛给高分」？**
Prompt 结构即 CoT 顺序：hardChecks 字段在 schema 里排最前，模型先逐条核对硬性条件（学历/专业/应届/政治面貌/保密要求——军工所特化）再打分；hardPass=false 时分数上限 39 且一句话结论必须点明未满足项。双路输入：结构化 JSON（保准确）+ JD 原文截断 3000 字（防提取遗漏）。

### 3.4 采集工程

详见 [crawler-engineering.md](crawler-engineering.md)（9 条 Q&A：去重、登录墙、SPA 软 404、脏数据清洗、失败隔离、调度、合规红线）。

**要点速记**：单源失败隔离（catch 记日志继续跑其他源）；@Scheduled 单用户场景够用（不上 Quartz）；51job/应届生被阿里云 WAF JS 挑战拦截（识别特征：`aliyun_waf_aa` meta + renderData），结论是 Playwright 或插件路线，不硬刚；合规边界：只读公开列表页、低频、不带登录态撞库。

### 3.5 MCP Server

详见 [mcp-protocol.md](mcp-protocol.md)（协议原理 + Q&A）。

**要点速记**：10 tools / 2 resources / 3 prompts；薄封装（业务全在 core，工具只做参数解析+异常翻译）；写操作幂等友好（重复 apply 返回当前状态）；领域错误翻译为 {ok:false, 中文原因} 让 LLM 自己决定追问；stdio 协议洁癖——stdout 只能走 JSON-RPC，PDFBox 传入的 commons-logging 发现警告都排掉了。

### 3.6 测试与质量

**Q：为什么用 Testcontainers 真实 PG 而不是 H2？**
JSONB、pgvector、pg_trgm、ON CONFLICT 都是 PG 方言，H2 模拟不了。「测试在 H2 上全绿、上 PG 就炸」是经典事故。附带收益：Flyway 迁移脚本本身被测试覆盖（脚本写错容器启动即失败）。本项目的实证：JPQL 字段名写错（company.type vs companyType）编译不报错，启动时仓储校验才暴露——集成测试当场抓住。

**Q：共享测试数据库下断言怎么写？**
用「包含式」断言（anySatisfy/≥）而非全等——测试间数据共存，断言自己的数据在场即可，不假设库是空的。

### 3.7 安全

- 本机令牌（X-Local-Token）：单用户本地应用防的是「同机其他进程/局域网误连」，不做用户体系；
- 简历隐私：本地存储；LLM 输入最小化（周报叙事只给聚合 JSON，不给 note 原文）；
- MCP 仅 stdio 无网络端口，攻击面 = 本机宿主；
- 爬虫合规：只读公开信息、低频、遵守 robots 精神，不绕登录态。

## 4. 可引用数字（简历/面试用）

- 46 个自动化测试（core 30 单测 + app 11 集成 + mcp 5 协议级），Testcontainers 真实 PostgreSQL；
- 10 个 MCP 工具 + 2 资源 + 3 提示词，stdio 协议级冒烟（stdout 0 字节污染）；
- 爬虫首轮收录 162 岗位，每日推荐管线 173 候选 → Top5（真实 E2E）；
- LLM 成本闸：每日 20 万 token 限额，全链路记账；
- 6 个 Flyway 迁移版本，多模块 Maven（core/app/mcp-server）。

## 5. 演示动线（3 分钟，M4 脚本）

1. Dashboard：漏斗图 + 30 天趋势 + 类型分布 →「这是我的秋招全景」；
2. 插件收藏一个新岗位 →「多平台信息统一归集，自动去重」；
3. 岗位详情 → AI 匹配报告（硬性条件逐条核对）→「AI 帮我做投递决策」；
4. 每周复盘卡片 → 点「AI 复盘」→「确定性数据 + LLM 叙事分层，LLM 挂了纯数据版照样用」；
5. Claude Desktop：「我这周进度如何」→「把刚收藏的岗位标记为已投递」→「数据库由我封装成了 MCP 工具」。
