# 采集工程（Crawler Engineering）

> 对应开发记录：`document/plans/2026-09-09-w3-crawler-framework.md`、`2026-09-09-w3-site-adaptation.md`、`2026-09-09-w3-recommend-ux-fix.md`（软 404 概念卡）

## 1. 是什么

JobRadar 的采集层是一个**站内解析器可插拔的轻量爬虫框架**：定时调度 → 页面抓取（Jsoup）→ 站点解析器（JSON API 或 CSS selector）→ 归一化 → 去重落库。解决"秋招岗位分散在 N 个网站、人工刷不过来"的问题，目标是把多源岗位收敛成一张统一的 `jobs` 表供匹配/推荐消费。

## 2. 为什么这个项目用它

- **Jsoup 而非 Playwright/Selenium**：目标是"能拿到数据的最低成本方案"。牛客的搜索网关是匿名可用的 JSON API，Jsoup 直连即可；Playwright 要拖一个浏览器内核（内存 +300MB、速度慢一个数量级、CI 里还要装依赖），属于"确定需要渲染再升级"的预留项，不是首选项。
- **解析器注册表（ParserRegistry）而非 if-else**：站点适配是持续增加的（今天牛客、明天智联），开闭原则——新站点 = 新实现类 + 一行注册，不动调度主流程。
- **数据库唯一约束兜底去重，而非只在应用层判断**：`jobs.dedupe_hash` 唯一索引。应用层"先查再插"有 TOCTOU 并发窗口，唯一约束是最后一道防线；应用层先查只是为了给用户友好报错（409 而非 500）。
- **每源独立事务 + 失败隔离**：一个源挂了（反爬改版、超时）不能拖垮整轮采集，更不能让已抓到的数据回滚。
- **不做的事**：不破解加密接口（国聘 appv5 全链路 AES，放弃）、不模拟登录绕过验证码（合规风险 > 数据价值）、不做分布式（单用户本地部署，定时器足够）。

## 3. 项目里哪里用了

| 组件 | 文件 | 职责 |
|---|---|---|
| 调度 | `jobradar-app/config/CrawlScheduler.java` | `@Scheduled(cron = "${jobradar.crawler.cron:0 30 7 * * *}")`，cron 走配置不写死 |
| 主流程 | `core/crawl/CrawlerService.java` | 遍历启用源 → 抓取 → 解析 → 落库；单源异常 catch 后记录继续 |
| 抓取 | `core/crawl/PageFetcher.java` | Jsoup，UA 伪装 + 超时 + 有限重试；`ignoreContentType(true)` 放开 JSON |
| 解析器 | `core/crawl/SiteParser.java`（接口）、`NowcoderSearchParser.java`、`SelectorListParser.java`、`ParserRegistry.java` | 策略模式：按 source.parser 名路由 |
| 去重 | `core/util/DedupeHash.java` | `SHA-256(company\|title\|city)` 截断 128 bit；归一化 = trim + 去全部空白 + 小写 |
| 公司归一 | `core/crawl/CompanyAliases.java` | 25 条别名映射（"华为技术有限公司"→"华为"），避免同公司多实体 |
| 落库 | `core/service/JobService.java#importCrawled` | 命中 dedupe_hash 跳过计数；`@Transactional` 每源独立 |

数据流：`crawl_sources（启用）→ PageFetcher → SiteParser → List<RawJobPosting> → DedupeHash 查重 → jobs 表`。

## 4. 面试追问 Q&A

**Q1：爬虫数据怎么保证不重复？**
A：三层。① 归一化：公司名/标题/城市 trim + 去空白 + 小写后拼 key；② 哈希：SHA-256 截断 128 bit 存 `dedupe_hash` 列；③ 数据库唯一约束兜底并发，应用层先查只负责友好报错。另有一张公司别名表把"华为技术有限公司"这类全称归一到"华为"，防止别名穿透哈希。
追问：为什么截断 128 bit 不怕碰撞？——生日悖论下 128 bit 需要 ~2^64 条记录才有可观碰撞概率，个人项目量级（千级岗位）可忽略；真到亿级再换全量哈希。

**Q2：遇到最麻烦的反爬是什么？怎么处理的？**
A：国聘网（iguopin）。Web 端列表接口 `gp-api.iguopin.com/api/jobs/v3/list` 匿名调用返回 403「账号类型错误」，要求个人账号登录态；移动端 appv5 接口请求/响应全链路 AES 加密。我的决策是**放弃匿名采集**：逆 AES 要扒 APK/前端 bundle 找密钥和算法模式，且绕登录态有账号封控和合规风险，投入产出不成正比。源记录保留在 `crawl_sources` 里但置为禁用，meta 里写明探测结论——这是"探测→评估→决策"的流程产出，不是失败。
备选路径（未实施）：① 用户自带 `__token__` 写进 source.meta.headers（会过期，体验差）；② Playwright 带用户自己的登录态渲染（合规上等同于用户自己浏览）。

**Q3：SPA 站点的链接怎么验证有效性？**
A：这是踩过的坑（见推荐 UX 修复记录）。牛客详情页是 SPA，**任何路径都返回 HTTP 200 + 应用外壳 HTML**（软 404）——`/jobs/463523` 这个错误格式状态码也是 200，但前端路由渲染的是首页。教训：**验证 SPA 资源必须校验内容（`<title>`/canonical/特征 DOM），不能只看状态码**。现在 `NowcoderSearchParser.DETAIL_URL` 用真实格式 `/jobs/detail/{id}`，代码注释里写了这个坑，还有一条 SQL 修过存量脏数据。

**Q4：抓到的脏数据怎么清洗？**
A：四类真实案例：① 哨兵值——薪资 0 或 9999999 表示"面议"，要映射成 null 而不是原样展示；② 广告卡片——牛客列表接口会混入没有 jobName 的推广位，解析时跳过；③ 标题噪音——"【2026届】"前缀、"_急招"后缀要剥掉再参与去重，否则同一岗位换皮就绕过哈希；④ 超宽时间窗——接口返回 400 天的 deadline 其实是"常年招聘"，需要识别。原则：**清洗规则跟着真实数据迭代，每类脏数据对应一个测试断言**。

**Q5：一轮抓取为什么只有 ~162 条而不是更多？**
A：这是设计出来的上限，不是故障。4 个关键词 × 3 页 × 每页 20 条 = 240 理论值，牛客匿名接口每关键词 totalCount 封顶 ~200，再去掉跨关键词重复和广告位，162 是合理产出。第二天重跑只新增 7 条——dedupe_hash 幂等生效，说明秋招早期日更新量本来就是这个量级。加量的正确姿势是扩关键词矩阵（分方向）或加数据源，而不是放开页数（深处全是过期帖）。

**Q6：单源失败会不会影响整轮？**
A：不会。CrawlerService 对每个源 try-catch 隔离，失败记 warn 日志继续下一个源；落库按源独立事务，一个源的数据问题不会回滚其他源。集成测试里专门有一个"坏源"（未注册解析器）验证这个隔离性。

**Q7：调度怎么做的？为什么不用 Quartz/XXL-Job？**
A：Spring `@Scheduled` + cron 走配置（`jobradar.crawler.cron`，默认每天 07:30）。单实例本地部署，不需要分布式调度的分片/故障转移；Quartz 引入 11 张表和集群语义，是杀鸡用牛刀。如果未来多实例部署，`@Scheduled` 会重复触发——那时的升级路径是 ShedLock（基于数据库行锁的轻量方案），而不是直接上 XXL-Job。

**Q8：合规边界在哪里？**
A：三条自设红线：① 只抓匿名可访问的公开数据，不绕登录/验证码；② 低频（每天一轮、每关键词 3 页），UA 如实说明，不给目标站造成负载压力；③ 数据只供个人求职使用，不分发不售卖。 robots.txt 与网站 ToS 是灰色地带，个人非商业低频抓取司法实践中风险低，但**商业化是分水岭**——这是项目保持"个人工具"定位的原因之一。

**Q9（兜底）：如果面试官问"为什么不直接用现成的爬虫框架如 WebMagic"？**
A：承认评估过但需求太小：核心是"两个 JSON API + 归一化落库"，WebMagic 的管道/下载器抽象对这个规模是过度设计；自己写的 200 行框架每个组件都能讲清楚（ParserRegistry、PageFetcher），这对学习项目反而是优点。真到 20+ 源、需要代理池/渲染集群时，迁移到成熟框架或干脆买数据服务是更理性的选择。
