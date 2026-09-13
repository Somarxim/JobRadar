# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-13 |
| 分支 | feat/llm-classify |
| 类型 | feat |

## 改动内容

- **LLM 语义分类 fallback**：规则分类器对"亿通国际""阿丘科技"等科技公司返回 OTHER，
  用户期望更智能的识别
  - `SmartCompanyTypeClassifier`（组件）：规则优先 → LLM fallback → 内存缓存
    - 规则命中非 OTHER 直接返回（低成本、可解释）
    - 规则返回 OTHER 时查缓存，未命中则调 LLM `classifyCompanyType`
    - LLM 失败/未配置/超预算时降级为 OTHER，不阻断爬虫
  - `LlmService.classifyCompanyType`：轻量分类 prompt，用 `CompanyTypeResult` +
    `BeanOutputConverter` 约束输出，温度 0.1（抽取式任务），记账到 `llm_usage`
  - `CrawlerService.getOrCreateCompany`：改用 `SmartCompanyTypeClassifier` 替代直接
    调用 `CompanyTypeClassifier`

## 改动原因

规则词典是 ordered dictionary，天然覆盖头部公司（运营商/银行/研究所/知名外企/互联网大厂），
但对长尾科技公司（如 AI 初创、SaaS 厂商）无能为力。LLM 有世界知识，能根据公司名推断
"阿丘科技 = AI 视觉 = internet"，正好补全规则盲区。设计成 fallback 而非替代，是因为：
1. 规则对头部公司的命中率很高，LLM 重复调浪费 token；
2. 规则的可解释性和零成本是 LLM 无法比拟的。

## 技术概念卡

### 分层分类器：规则层 + LLM fallback 层 + 缓存层
- 是什么：确定性任务先用规则（快、稳、可解释、零成本），规则盲区再用 LLM（泛化强、
  有世界知识），结果缓存避免重复调用。三层各司其职，不互相替代
- 项目里哪里用了：`SmartCompanyTypeClassifier`（规则 → LLM → 内存缓存）
- 面试可能追问：为什么不直接用 LLM？→ 规则对已知模式的命中是 O(1) 且零成本，LLM
  有网络延迟和 token 开销；面试中可以算一笔账：规则覆盖 80% 头部公司，LLM 只处理
  20% 长尾，成本降到直接全量 LLM 的 1/5

### Prompt 工程中的「输出约束」与「失败降级」
- 是什么：分类任务用 `BeanOutputConverter` 把 JSON Schema 注入 prompt，强迫模型输出
  结构化结果；解析失败时 `Optional.empty()` 返回，调用方保持 OTHER 不变
- 项目里哪里用了：`classifyCompanyType` 的 prompt + `mapCompanyType` 的容错映射
- 面试可能追问：怎么防幻觉？→ 温度 0.1 + 严格枚举值 + 解析失败降级；即使模型输出
  了不在枚举里的值，映射层也会 catch 住返回 OTHER

## 影响范围

- 新增 `SmartCompanyTypeClassifier` 组件（core.util）
- `CrawlerService` 构造函数新增依赖（Spring 自动注入）
- LLM 记账新增 `company_type_classify` 任务类型
- 无 schema/API 变更；存量公司不受影响（只在创建新公司时触发）

## 验证方式

- `mvn compile` / `mvn test` 56/56 绿
- 集成测试中的爬虫路径自然走通（LLM 未配置时 parseModel==null，直接降级为 OTHER，
  与旧行为一致）

## 遗留问题

- 内存缓存无持久化：重启后端后已分类的公司名会重新调 LLM。本地部署重启频率低，
  影响可控；若后续需要，可把缓存下沉到 Redis 或数据库表
- 未做启动回填：存量 OTHER 公司未用 LLM 重新分类。当前策略是「增量修正」——新入库
  的公司享受 LLM fallback，存量 OTHER 可手动 PATCH 纠正。若存量 OTHER 数量过多，
  可复用 `CompanyTypeBackfillService` 的逻辑加一轮 LLM 回填（需评估成本）
