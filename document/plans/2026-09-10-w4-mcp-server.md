# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-10 |
| 分支 | feat/w4-mcp-server |
| 类型 | feat |

## 改动内容

W4-1 MCP Server 落地（jobradar-mcp-server 模块从占位骨架变为可用服务）：

1. **10 个 tools**（`mcp/JobRadarMcpTools.java`，@Tool 薄封装 core service）：search_jobs / get_job_detail / add_job / apply / update_stage / set_next_action / today / weekly_report / match_job / recommend_today。写操作幂等友好（重复 apply/同阶段流转返回当前状态）、领域异常翻译为 `{ok:false, 中文原因}` 结果对象（不抛协议层错误，让 LLM 读到原因后自行追问）。
2. **2 个 resources + 3 个 prompts**（`mcp/McpServerConfig.java`）：`jobradar://stats/funnel`、`jobradar://resume/default`；prep_interview / weekly_review / jd_gap_analysis（参数注入实时数据组装成 user message）。
3. **stdio 协议安全**：`web-application-type: none` + banner off + logback 全走 System-Err；**排除 PDFBox 传入的 commons-logging**（core pom）——其发现逻辑向 stdout 打印警告会污染 JSON-RPC 帧。
4. **配置下沉 core**（双进程共享）：`CoreLlmConfig`（jobradar.llm.*）、`CoreResumeConfig`（jobradar.storage.*）从 app 移入 core；app 侧 LlmConfig/ResumeConfig 删除，JobRadarProperties 相应精简（行为不变）。
5. **Flyway 迁移脚本从 app 移到 core**（schema 与实体同模块，mcp-server 测试也能跑全量迁移；逻辑路径 classpath:db/migration 不变，已运行的库无感）。
6. mcp-server yml：flyway.enabled=false（迁移归 app 单一写者）、crawler/recommend 调度关闭 + 主类不加 @EnableScheduling（调度归 app 单一调度者）。

## 改动原因

- roadmap W4-1（M4 里程碑的核心亮点："数据库由我封装成了 MCP 工具"）；
- 用户决策：W3 主链路闭环后优先 W4，细节修缮（W3-4/3-5/更多数据源）后置。

## 技术概念卡

### MCP 协议与 Spring AI MCP Server
- 是什么：Model Context Protocol——Anthropic 主导的开放协议，把"工具/资源/提示词"标准化暴露给 LLM 宿主（Claude Desktop）；stdio 传输 = 宿主起子进程，stdin/stdout 走换行分隔的 JSON-RPC。
- 项目里哪里用了：jobradar-mcp-server 模块；`ToolCallbackProvider` bean 由 Spring AI 自动配置收集注册为 tools；resources/prompts 用 `McpServerFeatures.SyncResourceSpecification/SyncPromptSpecification` bean 列表注册。
- 面试可能追问：MCP 与 Function Calling 什么关系？（FC 是单次调用的模型能力，MCP 是把工具发现/调用/资源/提示词全生命周期标准化的应用层协议——MCP server 的工具最终仍以 FC 形式出现在宿主模型的上下文里）；stdio vs SSE？（本地单用户 stdio 零端口零鉴权；远程多用户才需要 SSE/HTTP）。

### stdout 协议洁癖
- 是什么：stdio 模式下 stdout 是协议通道，任何非 JSON-RPC 输出（banner、日志、第三方库 println）都会破坏宿主解析。
- 项目里哪里用了：logback-spring.xml 强制 System-Err；banner-mode off；排除 commons-logging（它的发现警告走 System.out 且发生在日志框架初始化之前，logback 管不住）。
- 面试可能追问：怎么发现的？（冒烟测试 stdin 给 EOF 看 stdout 应为 0 字节，实测有 149 字节警告 → dependency:tree 定位 PDFBox 传入）。

### @SpringBootApplication 扫描边界的两个坑
- scanBasePackages 只管 @ComponentScan，不管 JPA——@EntityScan/@EnableJpaRepositories 必须显式（核心模块实体不在主类包下）；
- @SpringBootTest 的 webEnvironment 推导看 classpath：MCP starter 带 webflux 会被推成 REACTIVE，stdio 应用必须显式 WebEnvironment.NONE。

## 影响范围

- **core**：新增 config 包（CoreLlmConfig/CoreResumeConfig）；pdfbox 排除 commons-logging（app 行为不变，PDFBox 日志改走 SLF4J）；迁移脚本物理位置 app→core（逻辑 classpath 路径不变，已部署库零影响）；
- **app**：LlmConfig/ResumeConfig 删除（改由 core 装配，bean 等价）；JobRadarProperties 移除 llm/storage 两个未再使用的记录；
- **mcp-server**：从骨架变为完整服务（10 tools + 2 resources + 3 prompts + stdio）；
- 无 API 契约变化；无数据库 schema 变化。

## 验证方式

- `mvn clean test` 全绿：core 25/25、app 9/9（迁移移动后回归）、mcp-server 5/5（新增 McpToolsRegistrationTest：10 tool 注册断言 + resources/prompts 齐备 + 空库冒烟 + 两个写工具友好失败用例）；
- 协议级冒烟（真实 jar + stdio）：initialize 握手返回 serverInfo；tools/list 10 个、resources/list 2 个、prompts/list 3 个；tools/call today 返回真实库待办（顺带验证了 09-10 早的待办自动生成在存量数据上的效果）；stdout 0 字节非协议输出。

## 遗留问题

- Claude Desktop 联调（改用户机器上的 claude_desktop_config.json + 重启客户端）需用户操作——配置片段见 docs/agent-design.md §6.5；
- 写操作的 MCP tool annotations（idempotentHint 等）未标：Spring AI 1.0.0 的 @Tool 注解未暴露 annotations 字段，需等后续版本或手搓 ToolDefinition；
- weekly_report tool 当前是确定性数据组装，LLM 叙事版归 W4-2；
- 用户机器重启后端后才会跑 V6 之后的库（无新迁移，V6 已在 09-10 早合入）。
