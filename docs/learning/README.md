# JobRadar 学习笔记体系

> 目的：项目由 AI 辅助实现，作者对 Java/Spring/Spring AI 栈零基础起步。本目录沉淀**面试答辩导向**的学习笔记——目标不是"会用"，而是"被深挖时能讲清楚"。
>
> 使用方法：随开发进度逐篇填充；每篇在对应功能开发完成后由 AI 辅助整理初稿，作者通读 + 自行复述验证。

## 笔记模板

每篇笔记固定四段结构：

```markdown
## 1. 是什么
（概念的一句话定义 + 解决什么问题，不超过 5 行）

## 2. 为什么这个项目用它
（对比备选方案的取舍——面试官必问"为什么不用 X"）

## 3. 项目里哪里用了
（文件路径 + 关键代码片段 + 数据/调用流向说明）

## 4. 面试追问 Q&A
（预判 5-10 个由浅到深的追问及应答要点，含"答不上时的兜底说法"）
```

## 笔记清单（按开发顺序填充）

### W1 —— 工程底座
- [ ] `maven.md` —— 生命周期与常用命令、依赖传递与冲突仲裁、父子 POM 与多模块、dependencyManagement vs dependencies
- [ ] `spring-boot-basics.md` —— IoC/DI 本质、自动配置原理（@Conditional）、Starter 机制、Bean 生命周期、配置体系（yml/环境变量/优先级）
- [ ] `spring-data-jpa.md` —— Entity 映射、Repository 方法推导原理、@Transactional 传播与失效场景、N+1 问题与解决、懒加载
- [ ] `postgresql-pgvector.md` —— B-tree/GIN/HNSW 索引原理与选择、tsvector 全文检索、JSONB vs TEXT、pgvector 余弦检索、Flyway 迁移机制
- [ ] `java-21-features.md` —— 本项目用到的：record、var、文本块、switch 表达式、Stream API、Optional、虚拟线程（如启用）

### W2 —— AI 能力
- [ ] `spring-ai-basics.md` —— ChatModel/ChatClient 抽象、多模型路由实现方式、`entity()` 结构化输出原理（BeanOutputConverter）、token 计量
- [ ] `llm-engineering.md` —— Prompt 工程实践、结构化输出稳定性（校验/重试/降级）、成本控制、embedding 原理与选型

### W3 —— 采集工程
- [ ] `crawler-engineering.md` —— Jsoup/Playwright 取舍、去重算法（hash + 别名归一）、调度与幂等、失败隔离、合规边界

### W4 —— 协议与答辩
- [ ] `mcp-protocol.md` —— MCP 协议原理（stdio/JSON-RPC）、Tools/Resources/Prompts 三要素、与 Function Calling 的关系、为什么 MCP 是新范式
- [ ] `interview-guide.md` —— **重点交付**：3 分钟项目介绍话术（STAR）、架构图白板讲解路径、深度追问 Q&A 树（按"为什么这么设计→怎么实现的→遇到什么问题→如何验证效果"四层组织）

## 配套机制

1. **开发记录 = 学习素材源头**：每次提交的开发记录（document/plans/）含「技术概念卡」栏目，是填充上述笔记的第一手材料；
2. **代码注释即讲义**：关键机制处（Bean 装配、@Transactional、Spring AI 调用、MCP 注册）的注释解释"为什么"，阅读代码即复习；
3. **自检方式**：每篇笔记完成后，作者应能脱稿回答该篇 Q&A 的 80%；答不出的条目回馈给 AI 补充讲解。
