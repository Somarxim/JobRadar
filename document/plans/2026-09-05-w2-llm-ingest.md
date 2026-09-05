# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-05 |
| 分支 | feat/w2-llm-ingest |
| 类型 | feat |

## 改动内容

W2 第一切片：LLM 接入 + JD 免手填导入 + token 记账。

1. **LLM 基础设施**（core/llm）：
   - `LlmModelConfig`（baseUrl/apiKey/model，key 空=未启用）+ `LlmService` 门面：按任务路由模型实例
   - 模型路由：parse=DeepSeek deepseek-chat（文本结构化），vision=DashScope qwen-vl-plus（海报多模态，下一切片接入）——两者都走 **OpenAI 兼容协议**，同一个 OpenAiChatModel 换 base-url 即用
   - 显式构造 bean（app/config/LlmConfig），不走 spring.ai 自动装配；yml 里 `spring.ai.model.*=none` 关掉 starter 自带的 chat/embedding/image/audio/moderation 自动装配（否则缺省 key 时启动即失败）
2. **token 记账**：Flyway V3 建 `llm_usage` 表（task/model/三段 token/success/error/latency_ms）；每次调用成败都落行，为后续成本闸供数
3. **ingest 升级**：`Hints` 的 company/title 从 @NotBlank 放宽为可选；缺省时 `LlmService.parseJd` 提取（BeanOutputConverter 结构化输出，温度 0.1），合并策略**手填 > AI**；AI 不可用/失败回 422 引导人工录入；日期容错解析（ISO/斜杠/点/中文格式）
4. **前端**：粘贴导入对话框公司/岗位改「可选（AI 提取）」，JD 全文变为必填，提交中显示「AI 解析中…」

## 改动原因

roadmap W2 第一项。秋招信息流多为整段 JD 文本，逐字段手填是最大录入摩擦点。

## 技术概念卡

### OpenAI 兼容协议（多供应商路由）
- 是什么：DeepSeek/DashScope（兼容模式）/Moonshot 等均实现 OpenAI /chat/completions 协议，换 base-url+api-key 即可切换，无需各家 SDK
- 项目里哪里用了：`LlmService` 一个 OpenAiChatModel 构造器服务两家供应商；`spring.ai.model.*=none` 关自动装配
- 面试可能追问：为什么不用 Spring AI 自动装配？（单模型场景自动装配方便；多模型/多供应商路由时 bean 冲突且缺省 key 校验会导致启动失败，显式构造可控可降级）

### 结构化输出（BeanOutputConverter）
- 是什么：按目标 record 生成 JSON Schema 说明拼进 prompt，再把响应文本解析回对象
- 项目里哪里用了：`LlmService.parseJd`；本质是「约定+解析」而非强约束，故失败降级 Optional.empty + 422 人工兜底
- 面试可能追问：LLM 输出不稳定怎么保证可用？（低温度 + schema 约束 + 解析容错 + 人工兜底四级防线；对应 roadmap 风险登记「LLM 结构化输出不稳定」）

## 影响范围

后端：core 新增 llm 包 + LlmUsage 实体/仓库，JobService.ingest 契约放宽（hints 可选——向后兼容：带全 hints 的旧调用路径不变）。DB：V3 迁移。前端：IngestDialog。

## 验证方式

- [x] `mvn install` 编译通过；启动日志 `LLM 配置：parse=deepseek-chat, vision=qwen-vl-plus`
- [x] 真实 JD 不带 hints 导入：AI 提取 中国航天科技集团第五研究院/卫星结构设计师（2026校招）/北京/2026-10-15（中文日期→ISO 正确），落库 job_id=7
- [x] llm_usage 记账：471+52 tokens，936ms，success=t
- [x] 幂等：同 JD 再导入返回 already_exists=true, job_id=7
- [x] `pnpm build` 零错误
- [x] 排障：starter 自带 audio-speech 自动装配在无 spring.ai.openai.api-key 时启动失败 → yml 全模态禁用

## 遗留问题

- 海报图片导入（vision 模型已就绪）：ingest 扩展 image 输入（base64），走 qwen-vl-plus
- 成本闸（每日限额）未启用：记账先行，限额策略在匹配 Agent 上线前补
- JobRequirements 结构化（agent-design §3.2）并入匹配 Agent 切片
