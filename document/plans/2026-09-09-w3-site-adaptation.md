# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-09 |
| 分支 | feat/w3-site-adaptation |
| 类型 | feat |

## 改动内容

W3-2 站点适配：第一个真实爬虫源（牛客校招）上线，爬虫框架从「骨架」变为「有真实产出」。

**实地探测（先行，决定技术路线）：**
- 国聘网：职位列表接口 `gp-api.iguopin.com/api/jobs/v3/list` 需个人账号登录态
  （匿名 403「账号类型错误」）；appv5 移动端接口请求/响应全链路 AES 加密。
  结论：匿名不可采，保持禁用（V5 迁移记录了结论与后续可选方案）。
- 牛客：发现职位搜索 JSON API `POST nowpick.nowcoder.com/u/job/square-search`，
  表单编码、**匿名可用**；query/recruitType（1校招）/page 参数，每页 20 条；
  JD 全文在 ext 内嵌 JSON（infos 描述 / requirements 要求）；
  详情页 `/jobs/{id}` 实测可达。

**代码改动：**
- `PageFetcher`：从「GET 静态页」扩展为通用请求构造器——meta.request 描述
  method/form|json/params/headers，meta.pagination 描述页码参数与页数；
  构造逻辑抽为静态纯函数 `buildRequest`（不触网可单测）。
- `CrawlerService`：按 meta.pagination 驱动分页循环（上限 20 页防配置笔误打爆目标站）；
  任一页失败即整源失败（幂等兜底，下轮重抓无损）。
- 新增 `NowcoderSearchParser`（parser id `nowcoder-search`）：
  - 跳过广告位（datas 中无 jobName 的卡片）
  - 标题清洗：剥「【27届校招】」批次前缀、「(J10357)」内部编号后缀（语义括号保留）
  - 薪资哨兵识别：0-9999999K = 面议 → null；正常 → `10-20K·14薪`
  - deliverEnd（epoch 毫秒）→ deadline；投递窗口 >400 天视为长期岗不落 deadline
    （防 DDL 倒计时被 2029 年的假截止日淹没）
  - company 取自 recommendInternCompany.companyName（组件复用命名的历史遗留字段）
- `V5__niuke_search_sources.sql`：牛客占位源改造为 `软件开发` 关键词源并启用，
  新增 `大模型`/`Java后端`/`银行` 三个关键词源（均 recruitType=1 校招、3 页/天）；
  国聘网源记录探测结论，保持禁用。
- 测试：NowcoderSearchParserTest×3（字段映射/广告跳过/标题清洗/异常响应快速失败）、
  PageFetcherTest×4（请求构造：纯 GET/POST form/POST json/静态页分页）；
  集成测试扩展为「静态页 + JSON API 分页」双形态（跨页去重断言）。
  **并修了一个真 bug 级别的测试盲区**：V5 启用种子源后，集成测试的打桩 fetcher
  会让种子源抢先消费测试夹具——测试开头先清空 crawl_sources，避免环境耦合。

## 改动原因

roadmap W3 第二项「站点适配」。W3-1 交付的是管线骨架，本任务按
docs/target-sources.md §7 顺序实地验证目标站、接通第一个真实源。

## 技术概念卡

### SPA 站点的 API 逆向（不碰逆向工程的合规边界）
- 是什么：JS 渲染站点的数据来自后端 JSON API；从 bundle 里找接口路径、域名映射、
  鉴权方式（自定义头/token/加密），优先找「匿名可用」的接口
- 项目里哪里用了：国聘（结论：需登录态，放弃匿名采集）与牛客（nowpick 网关匿名可用）
  的探测过程；产出落在 V5 迁移注释与本文档
- 面试可能追问：怎么找到接口的？（bundle 提取路径→域名映射→实弹验证）；
  遇到加密/登录态怎么办？（评估成本与合规，果断换源而不是硬刚——爬虫是
  「尽力而为」的信息渠道，不是破解竞赛）

### 哨兵值与数据卫生
- 是什么：第三方数据里的「无值」常编码为哨兵（薪资 0-9999999K、deliverEnd=2029 年），
  直接落库会污染下游（DDL 倒计时被假截止日淹没）
- 项目里哪里用了：NowcoderSearchParser.formatSalary / deadlineOf
- 面试可能追问：为什么不在展示层判空？（脏数据进库后每个消费方都要知道哨兵约定，
  在采集边界归一化是单一职责）

## 影响范围

- 数据迁移 V5：牛客占位源改名启用 + 新增 3 个关键词源；国聘网 meta 更新（仍禁用）
- 真实落库：首轮抓取 162 个真实校招岗位（已在开发库验证）；二轮幂等 0 全量去重
- 每日 07:30 调度开始产出真实岗位（4 源 × 3 页 × 20 条上限，日请求量 12 次，低频合规）
- PageFetcher.fetch(String) 旧签名保留兼容；CrawlerService 内部改走 fetch(source, page)

## 验证方式

- `mvn test` 25 全绿（core 19 + 集成 6）
- 真实环境联调（worktree 打包 8081 端口 + 开发库）：
  - Flyway V4/V5 迁移成功
  - `POST /api/crawl/run` 首轮：4 源抓取 163 条、落库 162（1 条跨源去重）
  - 二轮：162 全部去重跳过、7 条为两轮间隔内的真实新帖 → 幂等成立
  - 抽查详情：公司名（美团/阿里/华为）、薪资映射、deadline、详情链接均正确

## 遗留问题

- 国聘网需登录态：后续可选 ① meta 支持用户自带 __token__（会过期）② Playwright
  带登录态方案——优先级待真实使用后再定
- 「Java后端」关键词命中太少（4 条）——关键词矩阵需按产出质量迭代（在真实使用中调整）
- 牛客匿名上限约 200 条/查询：关键词分片已规避，但深翻（>10 页）无意义
- 牛客校招日程接口（/u/school-schedule/list-card）可作 W3-4 投递规划的日历数据源，待评估
- W3-3 每日推荐管线将消费这些新岗位（freshness 信号：last_crawled_at + publish_date）
