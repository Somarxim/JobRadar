# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-09 |
| 分支 | feat/w3-crawler-fw |
| 类型 | feat |

## 改动内容

W3-1 爬虫框架：调度/抓取/解析/清洗/去重管线落地（站点适配留 W3-2）。

**core 新增 `crawl` 包**（7 个类）：
- `RawJobPosting`：解析层原始条目（全字符串，归一化在管线做）
- `SiteParser`：站点解析器 SPI（parser 标识 ↔ crawl_sources.parser）
- `SelectorListParser`：通用解析器——meta JSON 配 CSS 选择器即接入新静态站，
  支持 `selector@attr` 取属性、相对链接补全、title_contains 关键词过滤
- `ParserRegistry`：Spring List 注入自动注册所有 SiteParser 实现
- `PageFetcher`：Jsoup 抓取（UA/10s 超时/重试 2 次指数退避，4xx 不重试）
- `CompanyAliases`：company-aliases.yml 加载，别名→标准名归一
  （标准名自身也注册为键，幂等；去空白小写匹配）
- `CrawlerService`：管线编排——fetch→parse→别名归一→JdTextCleaner 噪音截断
  →DedupeHash 幂等→落库；**单源失败隔离**（异常收敛为 SourceResult.error），
  成功源更新 last_crawled_at；source_platform 从 meta.platform 读取

**app 层**：
- `CrawlScheduler`：每日 07:30（cron 可配），`jobradar.crawler.enabled` 开关
- `CrawlController`：GET /api/crawl/sources、POST /api/crawl/run[?source_id=]
- `V4__seed_crawl_sources.sql`：国聘网/牛客两条种子（enabled=false，
  URL 与解析方式待 W3-2 实地验证——牛客已确认纯 JS 渲染）
- `company-aliases.yml`：按 target-sources.md §6 建初版别名表

## 改动原因

roadmap Week 3「发现引擎」第一块地基。记录系统只能管"已知岗位"，
W3 解决信息盲区（M3：每日推荐命中之前不知道的机会）。

## 技术概念卡

### 插件化解析器（SPI + 注册表）
- 是什么：SiteParser 接口 + ParserRegistry 按名查找，新增站点 = 新增实现类或一套 meta 配置
- 项目里哪里用了：core/crawl 包
- 面试可能追问：为什么不用 if-else 分发？（开闭原则；单源失败隔离、独立演进）；配置化解析的边界？（表达力上限，SPA/JSON API 站点需专属解析器，两者共存）

### 爬虫幂等与失败隔离
- 是什么：dedupe_hash 唯一约束兜底 + 应用层先查；每源独立 try/catch，失败不扩散
- 项目里哪里用了：CrawlerService.runSourceSafely / runSource
- 面试可能追问：为什么不用大事务包一批？（爬虫是"尽力而为"语义，一条脏数据不该回滚整批；且长事务占连接）；重复岗位内容变化怎么办？（以首见为准 + 人工修正，爬虫不做 diff 更新——数据新鲜度与复杂度取舍）

### 合规边界
- 是什么：单用户低频（每日一轮）、无并发无代理池、4xx 不重试
- 项目里哪里用了：PageFetcher
- 面试可能追问：遇到反爬怎么办？（设计上认输：源插件化降级手动粘贴兜底，见 roadmap 风险登记）

## 影响范围

- 新增表数据：crawl_sources 两条种子（disabled，无运行影响）
- 新增端点：GET /api/crawl/sources（只读免令牌）、POST /api/crawl/run（写操作受 LocalTokenFilter 保护）
- 新增配置：jobradar.crawler.enabled / cron
- 每日 07:30 起有定时任务运行（当前无启用的源，空跑无副作用）

## 验证方式

- `mvn test` 18 用例全绿：
  - SelectorListParserTest×3（字段提取/@attr/相对链接补全/关键词过滤/缺配置快速失败）
  - CompanyAliasesTest×4（别名命中/大小写空白不敏感/标准名幂等/未知透传）
  - JobRadarIntegrationTest 新增 crawlPipelineIsIdempotentAndIsolatesFailures
    （PageFetcher 打桩 → 解析 2 条入库 + 别名归一验证 + 二轮幂等 + 坏源失败隔离）

## 遗留问题

- 国聘网/牛客为 SPA：W3-2 需实地验证 JSON API 或评估 Playwright（已在 V4 meta 备注）
- 解析器只覆盖列表页一层：详情页二次抓取（补 JD 全文）留待 W3-2 按需
- 爬虫新岗位 → 每日推荐的联动在 W3-3
- company-aliases.yml 与 popup.js 的噪音标记一样存在两处维护问题（W3-2 统一审视）
