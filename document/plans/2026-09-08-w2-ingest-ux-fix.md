# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-08 |
| 分支 | fix/w2-ingest-ux |
| 类型 | fix |

## 改动内容

W2 验收后 UX 修正，三个入口一次做完（均属 ingest 链路）：

1. **W2-1 文本解析职责降级**（`LlmService.parseJd` + `JobService.ingest` + `IngestDialog.tsx`）
   - parseJd prompt：company/title 改为「仅文本明确出现时提取，否则 null，禁止猜测」；
     新增忽略噪音指令（安全提示/职位推荐/广告/导航页脚）
   - ingest 新策略：hints 提供 company/title 时 AI 只补全 city/salary/deadline，
     **失败降级为警告而非 422**；enrichment 字段全部手填时不调 AI（零收益不烧 token）；
     company/title 缺省时仍回退 AI 提取 + 422 兜底，错误信息改为引导「补字段重新提交」
   - 前端文案：公司/岗位从「（可选）」改为「（建议填写）」，说明 AI 主职是补全
2. **W2-2 海报两段式解析**（`LlmService`）
   - 拆为 `transcribePoster`（视觉模型忠实转录，task=poster_transcribe）+
     `structurePosterText`（文本模型结构化，task=poster_struct），`parsePoster` 编排两段
   - 转录稿直接落 jd_text 存档（海报的文字层）
   - 无具体岗位的宣讲会/校招启动海报：title 输出「<批次>校园招聘」占位
   - JobService 海报路径（hints 完整时）直接调两段：结构化失败也不丢转录稿存档
3. **W2-5 插件三层提取**（`extension/popup.js`）
   - 提取优先级：选中文字 > main/article/[role=main] > Readability 式向下钻取
     （每步进入占父级文本 ≥70% 的最大子元素，直至文本分裂）> body 兜底
   - 噪音标记截断安全网：命中「安全提示/看过该职位的人还在看/猜你喜欢」等词
     即丢弃其后内容（依据：innerText 按 DOM 序，推荐位多在正文之后）
   - popup 显示提取来源（选中/主内容/整页），整页兜底时提示改用手动选中

## 改动原因

W2 验收用户实测反馈：
- JD 正文经常不含公司名，AI 强求识别命中率低且诱发幻觉 → 体验不如手填
- 海报信息密度高，单次调用直出 JSON 约束模型发挥；宣讲会海报常无具体岗位
- 插件整页 innerText 混入牛客侧边「看过该职位的人还在看」「安全提示」等噪音

## 技术概念卡

### Readability 式主内容提取（简化版）
- 是什么：从 body 递归进入「占父级文本 ≥70% 的最大子元素」，文本分裂处即正文容器层
- 项目里哪里用了：extension/popup.js `pickMainContainer()`
- 面试可能追问：为什么不用站点专属选择器？（站点无关优先，专属选择器作配置化增强，与 W3 爬虫同源思路）；innerText 顺序坑（DOM 序≠视觉序，侧栏可能在正文前，故噪音截断只作安全网）

### 两段式 LLM 管线（转录/结构化分离）
- 是什么：贵的视觉模型只做忠实转录，便宜的文本模型做结构化提取
- 项目里哪里用了：LlmService.parsePoster → transcribePoster + structurePosterText
- 面试可能追问：收益？（①转录稿独立存档为 jd_text；②结构化 prompt 迭代不烧视觉 token，调试成本差一个数量级；③单段直出 JSON 在高信息密度图片上约束发挥）

### AI 职责降级设计
- 是什么：把 AI 从「识别实体」（低命中率）改为「补全 enrichment 字段」（高命中率），失败降级警告而非错误
- 项目里哪里用了：JobService.ingest 新合并策略
- 面试可能追问：为什么 company/title 不适合让 AI 从 JD 猜？（JD 正文无义务出现公司名，这是数据源特性而非模型能力问题）

## 影响范围

- POST /api/jobs/ingest 行为变化：hints 完整 + AI 失败时**从 422 变为成功入库 + 警告**（契约语义软化，前端无需改错误处理——成功路径本就处理 warnings）
- llm_usage 新增 task 取值：poster_transcribe / poster_struct（替代 poster_parse）
- 无 DB 迁移；无破坏性变更

## 验证方式

- `mvn test` 全绿（5 用例，Testcontainers 真实 PG）；新增 `ingestWithHintsSurvivesLlmDown`
  覆盖「hints 完整 + LLM 不可用 → 入库成功 + 降级警告」
- 测试环境 LLM 降级改为强制（@SpringBootTest properties 置空 api-key），
  不再依赖开发机是否 export 了环境变量——本次测试失败即因此暴露
- `npm run build`（tsc + vite）通过；`node --check extension/popup.js` 通过
- 待用户实测：牛客岗位页重新收藏验证三层提取；海报两张（有/无具体岗位）验证两段式

## 遗留问题

- 噪音标记列表需随实测持续补充（现为常见词初版）
- 海报多岗位场景仍只取最主要一个入库，拆分靠人工（占位岗位 + 后续手工）
- 前端 ingest 表单在 422 时未做「AI 部分结果预填」（当前靠表单状态天然保留，
  预填需新 API 契约，暂未实现）
- 插件站点专属选择器配置化留待 W3 与爬虫解析器统一设计
