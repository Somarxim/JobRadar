# 2026-09-13 公司类型存量回填 + 规则补充

## 背景
LLM 语义分类 fallback 只作用于「新入库公司」（CrawlerService.getOrCreateCompany），
存量 31 家 OTHER 公司永远不会被重判。用户点名「亿通国际」「阿丘科技」应属互联网。

## 改动内容

### 1. 存量数据一次性修复（SQL，已执行）
逐家人工研判（含公司背景 + JD 内容佐证），28 家改判：

| 新类型 | 公司 |
|---|---|
| internet ×25 | 亿通国际、阿丘科技、联想、汽车之家（车之家）、极智嘉、安克创新、CVTE、新易盛、神州信息、群核科技（酷家乐）、思必驰、大普微电子、思朗科技、文华财经、宇量昇、德赛西威、埃科光电、诺瓦星云、中创宇峰、高斯全球、亚控科技、杭州先进编译、柠檬微趣、Dexmal 原力灵机、酷睿程 |
| foreign ×1 | 翱盟湃（比利时 OMP 供应链软件，JD 为英文，佐证外企） |
| bank ×1 | 兴证全球基金（金融机构桶） |
| institute ×1 | 航天X院（军工院所） |
| 保持 other ×3 | 联影医疗（医疗器械）、公牛集团（传统制造）、盐城人才金港（教育培训）——taxonomy 中这三类本就该落 other |

执行前快照至 `_backup_company_type_20260913` 表（可回滚）。

### 2. 规则分类器补充（CompanyTypeClassifier）
- INTERNET 增补：联想/lenovo、汽车之家/车之家（头部公司规则漏网）
- BANK 增补：基金（秋招语境下基金公司同属金融机构）

只加头部高置信词；长尾公司继续交给 LLM fallback（规则/LLM 的职责边界不破坏）。

### 3. 回填端点（POST /api/companies/backfill-types）
- CompanyService.backfillTypes()：查出全部 OTHER → 走 SmartCompanyTypeClassifier 重判 → 逐条 save
- 刻意不加 @Transactional：LLM 调用是秒级网络 IO，不能占数据库连接；
  逐条 save 用 Repository 自带事务（与 CrawlerService 同模式），单条失败不扩散
- 返回 {checked, changed, changes[]} 明细
- 手动纠正过的非 OTHER 公司天然免疫（查询条件只捞 OTHER）

## 技术概念卡

**存量数据修复的两条路**
- 一次性 SQL：即时、确定、零代码，但需要人工研判且不可重复
- 回填端点：可重复执行（规则每次扩充后跑一次），复用分类器全链路（规则→LLM→缓存）
- 本项目两者都做：SQL 解决当下，端点留给未来

**为什么回填方法不加大事务**
- 31 家公司 × LLM 2-5s/家 ≈ 1-2 分钟；@Transactional 会让一个数据库连接被网络 IO 占满
- 逐条 save 的代价是「部分成功」——对回填语义来说这恰恰是想要的（尽力而为，非全或无）

## 面试问答素材
Q: 规则分类器和 LLM 怎么分工？
A: 规则管头部（零成本、可解释、确定性），LLM 管长尾（泛化能力），缓存管重复调用。
   规则扩充后存量数据通过 backfill 端点重判——规则永远先于缓存检查，所以新规则立即生效。
