# MCP 协议（Model Context Protocol）

## 1. 是什么

MCP 是 Anthropic 主导的开放协议，把「工具（Tools）/ 资源（Resources）/ 提示词（Prompts）」三类能力标准化暴露给 LLM 宿主（Claude Desktop、IDE 插件等）。stdio 传输模式下，宿主启动 MCP Server 子进程，stdin/stdout 走换行分隔的 JSON-RPC 帧——零端口、零鉴权、进程生命周期由宿主管理。

## 2. 为什么这个项目用它

备选方案：① 只做 REST API——LLM 宿主无法直接消费，需要人工当中转；② 自定义插件协议——私有协议宿主不认。MCP 的价值在「标准化」：一次封装，所有支持 MCP 的宿主（Claude Desktop/Code、其他 Agent）都能用。这是「Agent 时代应用的新形态」——应用不再只有人类 UI，还需要面向 Agent 的接口层。

## 3. 项目里哪里用了

- 模块：`backend/jobradar-mcp-server`（Spring AI MCP Server Starter，stdio）；
- 工具注册：`JobRadarMcpTools` 10 个 @Tool 方法（薄封装 core service），由
  `McpServerConfig` 的 `MethodToolCallbackProvider` 自动收集；
- 资源/提示词：`McpServerConfig` 里 2 个 `SyncResourceSpecification`（投递漏斗、默认简历画像）
  + 3 个 `SyncPromptSpecification`（prep_interview / weekly_review / jd_gap_analysis，
  参数注入实时数据组装成 user message）；
- 协议安全：`web-application-type: none` + banner off + logback 全走 System.err。

## 4. 面试追问 Q&A

**Q：MCP 与 Function Calling 什么关系？**
FC 是模型单次调用的能力（决定「调哪个函数、传什么参」）；MCP 是应用层协议，把工具发现/调用/资源读取/提示词模板的全生命周期标准化。MCP Server 注册的工具最终以 FC 形式出现在宿主模型的上下文里——MCP 不替代 FC，是给 FC 提供标准化的「工具供给侧」。

**Q：stdio 和 SSE/HTTP 传输怎么选？**
stdio：本地单用户、宿主拉子进程、零端口零鉴权——Desktop 场景天然合适。远程多用户才需要 SSE/HTTP + 鉴权。本项目是个人本地应用，stdio 攻击面最小（没有网络监听端口）。

**Q：stdout 协议洁癖是怎么回事？踩过什么坑？**
stdio 模式下 stdout 是协议通道，任何非 JSON-RPC 输出（banner、日志、第三方库 println）都会破坏宿主帧解析。踩坑实录：Spring Boot banner、logback 默认 ConsoleAppender（System.out）、以及最隐蔽的——PDFBox 传递依赖的 commons-logging 在日志框架初始化之前向 stdout 打印 149 字节发现警告，logback 配置管不住它。解法：core pom 排除 commons-logging（spring-jcl 是同包名 drop-in 替代）。验证：冒烟测试给 stdin EOF，断言 stdout 恰好 0 字节。

**Q：写操作的安全性怎么保证？**
三层：① 写工具只认 jobId（LLM 必须先 search 定位，「不猜不写」）；② 幂等友好——重复投递/同阶段流转返回当前状态而非报错，LLM 重试无害；③ 领域异常（404/409/400）翻译为 {ok:false, 中文原因} 结果对象，不抛协议层错误——让 LLM 读到原因后自行追问用户，而不是把异常甩给宿主 UI。

**Q：Resources 和 Tools 怎么分工？**
Tools 是动作/查询（有参数、有副作用或计算），Resources 是只读上下文（URI 寻址，宿主可主动挂载进上下文）。项目里收敛为 2 个具体 URI（漏斗统计、简历画像）——岗位详情由 get_job_detail 工具覆盖更符合 LLM 调用习惯，URI 模板（jobs/{id}）在 Spring AI 1.0.0 自动配置链中支持不完整，这是落地时的务实取舍（见 docs/agent-design.md §6.4）。
