# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-05 |
| 分支 | feat/w2-poster-ingest |
| 类型 | feat |

## 改动内容

W2 第二切片：海报图片多模态导入（用户 2026-09-04 反馈立项）。

1. **契约扩展**：`IngestRequest` 新增 `image_base64` / `image_media_type`；与 raw_text 至少其一，文本路径优先（更准更便宜）
2. **视觉解析**：`LlmService.parsePoster`——qwen-vl-plus 多模态直读海报（版式理解 + 文字提取 + JD 信息整理一步完成，替代 OCR 两段式）；`ParsedJob` 增加 `jd_text` 字段承接海报整理文本（海报无文字层，模型整理结果沉淀为 JD）
3. **前端**：IngestDialog 加图片上传（≤4MB、预览、可移除），JD 文本与海报二选一，按钮态「AI 解析中…」
4. **两个排障修复**：
   - DashScope base-url 去掉 `/v1`：Spring AI `OpenAiApi` 的 completionsPath 默认 `/v1/chat/completions`，base-url 带 /v1 会拼成 `/v1/v1/...` → 404
   - **记账事务独立化**：`recordUsage` 改 `REQUIRES_NEW` 新事务——原实现混在 ingest 业务事务里，解析失败抛 422 回滚时失败账一并丢失，而失败账正是成本/排查的关键数据

## 改动原因

研究所/国企公众号招聘多为海报大图、无文字层，纯文本导入覆盖不了（用户反馈第四条）。

## 技术概念卡

### 多模态调用（Spring AI Media）
- 是什么：UserMessage 支持 text + List\<Media\>（图片 base64/URL），视觉模型一次调用完成版式理解+文字提取
- 项目里哪里用了：`LlmService.parsePoster`；对比 OCR 方案（PaddleOCR 两段式）少了版面还原误差累积
- 面试可能追问：多模态 vs OCR+LLM 两段式取舍？（成本、准确率、延迟；海报含版式语义——标题层级/色块强调——OCR 丢失）

### 记账的事务独立性（REQUIRES_NEW）
- 是什么：Spring 事务传播级别，挂起当前事务开新事务提交
- 项目里哪里用了：`LlmService.recordUsage`（TransactionTemplate 编程式，避免自调用代理失效问题）
- 面试可能追问：记账/审计/日志为何不能用主事务？（主业务回滚不应带走审计痕迹；编程式 TransactionTemplate vs 注解 @Transactional(propagation=REQUIRES_NEW) 的自调用陷阱）

## 影响范围

后端：IngestRequest 契约扩展（向后兼容）、LlmService + parsePoster、JobService.ingest 海报分支。前端：IngestDialog 图片上传。

## 验证方式

- [x] 后端编译 + 启动，vision=qwen-vl-plus 加载
- [x] 合成测试海报（800x1100，含单位/岗位/城市/截止日）经代理 POST ingest：到达模型端点
- [x] 失败降级路径验证：DashScope 返回 403 FreeTierOnly（用户账号免费额度耗尽）→ ingest 返回 422 引导人工填写，llm_usage 失败账落表（证明 REQUIRES_NEW 生效）
- [x] `pnpm build` 零错误
- [ ] **happy path 未验证**：等用户给 DashScope 充值/关闭 free-tier-only，或提供其他视觉模型 key 后复测

## 遗留问题

- **需要用户操作**：DashScope 免费额度耗尽（报错 FreeTierOnly），请在阿里云百炼控制台充值或调整额度模式；或告知我换用其他视觉模型
- 成本闸（每日限额）仍未启用，匹配 Agent 上线前补
