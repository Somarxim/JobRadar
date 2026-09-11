# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-10 |
| 分支 | feat/fts-search |
| 类型 | feat |

## 改动内容

- 岗位库搜索从 LIKE 子串匹配切换为 PostgreSQL 全文检索（`search_vector @@ to_tsquery`）：
  - 新增 `core/util/JiebaSearchText`：jieba 分词桥接。索引侧 `indexText()`（INDEX 模式细粒度切分 +
    去重 + 长 JD 截断 4000 字符），查询侧 `tsQuery()`（SEARCH 模式切词 + tsquery 元字符白名单剥除 +
    `&` AND 连接，输出作绑定参数）
  - `JobRepository.findIdsByFullText()`：native 查询走 GIN 索引取 id 集合（ts_rank 排序，LIMIT 1000
    防深命中集），回到 Specification 用 `id IN (...)` 与动态条件组合
  - 写入侧四处覆盖：`JobService.create/ingest/patch` + `CrawlerService.runSource`
  - 存量回填：`SearchTextBackfillService`（分批 200、每批一事务）+ app 启动 Runner（幂等，无存量即空转一次）
- 依赖：core 引入 `com.huaban:jieba-analysis:1.0.2`（父 pom 锁版本）
- 测试：core 新增 `JiebaSearchTextTest`（5 例，分词契约/AND 拼接/元字符剥除/空查询）；
  集成测试新增 `fullTextSearchMatchesTokensAndBackfills`（词元命中/公司名命中/纯标点零命中/回填恢复）

## 改动原因

V2 建表时 `search_vector`（TSVECTOR 生成列）+ GIN 索引就建好了，但 PG 的 `simple` 配置不会中文分词
（「大模型工程师」整串一个词元），所以设计就是「应用层 jieba 分词写 search_text」——该写入一直未实现，
搜索停留在 W1 的 LIKE 过渡实现。LIKE 的硬伤：中文子串匹配语义错位（搜「大模型」要求连续子串）、
`%xx%` 前导通配符走不了任何索引。

## 技术概念卡

### PG 全文检索（tsvector/tsquery/GIN）
- 是什么：`to_tsvector` 把文本切成词元（lexeme）存倒排结构，`to_tsquery` 把查询变成词元布尔表达式，
  `@@` 运算符判命中，GIN 索引让命中判断从全表扫描变成倒排查找
- 项目里哪里用了：`V2__init_schema.sql`（生成列 + `idx_jobs_fts` GIN）、
  `JobRepository.findIdsByFullText`（native 查询）
- 面试可能追问：①为什么生成列？→ 索引文本与 tsvector 强一致，应用层只写 search_text；
  ②为什么 'simple' 配置？→ 中文没有词干/停用词概念，词元化由应用层 jieba 做了，DB 只需按空格切

### 中文分词为何放在应用层
- 是什么：PG 中文分词扩展（zhparser/pg_jieba）需要 DBA 装扩展且绑定特定 PG 版本；
  应用层 jieba 是纯 Java 依赖，词典随 jar 走
- 项目里哪里用了：`JiebaSearchText`（INDEX 模式建索引词元 / SEARCH 模式切查询词）
- 面试可能追问：两侧分词器不一致会怎样？→ 索引切出 [大，模型] 而查询产出 [大模型] 就永不命中，
  所以同一 JiebaSegmenter 单例 + 实测验证过「大模型」查询（Q=大 & 模型）能命中（IDX 含 大、模型）

### JPA Specification 与 native 查询混合
- 是什么：`@@` 是运算符不是函数，Criteria API 表达不了；解法是 native 查询先取 id 集，
  Specification 里 `id IN (...)` 与其他动态条件（公司类型/阶段/DDL）继续类型安全地组合
- 项目里哪里用了：`JobService.search` 的 q 分支
- 面试可能追问：为什么不整个查询都 native？→ 动态条件组合会退化成字符串拼接，失去类型安全；
  id 预取有 LIMIT 1000 截断，命中集超大时只保相关度最高的（搜索场景用户只看前几页）

## 影响范围

- 搜索语义变化：子串不再命中（搜 "ava" 不再命中 "Java"）——中文词元匹配才是搜索意图，取舍成立
- 部署后首次启动自动回填存量岗位（日志输出回填条数）；回填期间新搜索对已回填部分即时生效
- 无 schema 变更、无 API 契约变化（GET /jobs 参数不变）

## 验证方式

- core 单测 5 例全绿；`mvn test` 全量（core 35 + app 11 + mcp 5）绿
- 集成测试覆盖：中文词元命中（「大模型」「智谱」）、纯标点零命中、清空 search_text 后回填恢复命中
- 实测确认分词一致性：Q[大模型]=大 & 模型 可命中 IDX[大模型应用工程师]=大 模型 应用 工程 工程师

## 遗留问题

- 排序：命中集内仍按 sort 参数（默认 created_desc），ts_rank 相关度只在 native 预取阶段起作用；
  如需相关度排序进入最终序，需把 rank 带入主查询（当前单用户数据量无感）
- W3-5 语义搜索（pgvector embedding 粗筛）仍搁置：DashScope embedding 额度未确认可用，
  且 FTS 已覆盖关键词检索需求，embedding 的增量价值在"语义相近但词面不同"的场景
