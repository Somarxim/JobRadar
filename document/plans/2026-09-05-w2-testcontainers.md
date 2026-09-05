# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-05 |
| 分支 | feat/w2-testcontainers |
| 类型 | test |

## 改动内容

W2 收尾：Testcontainers 集成测试基线（roadmap W2 最后一项）。

1. `JobRadarIntegrationTest`（4 个用例，直测 Service 层）：
   - `ingestIsIdempotent`：create + 两次 ingest（不同 source）命中 dedupe_hash 幂等
   - `applicationTransitionWritesEvents`：collected→planned→applied 流转 + 事件留痕 + 同阶段重复流转幂等
   - `archivedJobExcludedFromBoard`：归档岗位从看板消失（W1 归档过滤修复的回归防护）
   - `resumeUploadWithoutLlmIsPending`：伪 PDF 降级路径（解析失败 pending、首份默认）
2. 依赖：app 模块补 `spring-boot-starter-test`（JUnit5/AssertJ）+ `testcontainers:junit-jupiter`（@Container/@Testcontainers 注解在独立模块）
3. **Docker 29 兼容性修复**：daemon 拒绝 <1.43 的 API（400），docker-java 默认按 1.32 请求；且 docker-java **只从系统属性/properties 文件读 `api.version`，不读环境变量**（javap 反编译 shaded `DefaultDockerClientConfig` + 独立探针程序实证 `API_VERSION` env 无效、`-Dapi.version=1.44` 生效）→ surefire `systemPropertyVariables` 钉住 1.44；testcontainers 版本显式覆盖 1.21.3
4. 容器镜像用 `pgvector/pgvector:pg16`（V1 迁移装 vector/pg_trgm 扩展，普通 postgres 镜像没有）

## 改动原因

真实 PG 方言（JSONB/pgvector/pg_trgm/ON CONFLICT）H2 模拟不了——「H2 全绿、PG 就炸」是经典事故；且 Flyway 迁移脚本本身被测试覆盖（写错即启动失败）。

## 技术概念卡

### Testcontainers + @ServiceConnection
- 是什么：JUnit 扩展在 Docker 里起真实中间件容器跑测试；Boot 3.1+ 的 `@ServiceConnection` 自动把容器连接信息注入数据源，免手写 `@DynamicPropertySource`
- 项目里哪里用了：`JobRadarIntegrationTest` 的 static @Container PostgreSQLContainer
- 面试可能追问：为什么不用 H2？（方言差异：JSONB/pgvector/触发器/并发语义）；容器复用？（`withReuse(true)` + ~/.testcontainers.properties，本地迭代提速）

### 环境兼容性排障方法论
- 是什么：库（docker-java）声明的契约与其实现不符（文档说支持 env，实现只读系统属性）时，用最小探针程序实证而非反复试错
- 项目里哪里用了：TcProbe.java 独立 main 验证 `API_VERSION` env 无效 / `-Dapi.version` 生效；`curl --unix-socket` 直接探测 daemon 的 API 版本边界（1.32→400、1.43→200）
- 面试可能追问：遇到三方库行为与文档不符怎么排查？（读字节码/源码找真相；最小复现隔离变量）

## 影响范围

仅测试代码与构建配置（app pom + 后端 parent pom 属性）。主代码零改动。

## 验证方式

- [x] `mvn test -pl jobradar-app`：4 用例全绿（Tests run: 4, Failures: 0, Errors: 0）
- [x] 容器内 Flyway 全量迁移（V1 扩展 + V2 schema + V3 llm_usage）通过 = 迁移脚本与实体校验双覆盖
- [x] 降级路径断言生效：伪 PDF → pending（日志可见「未抽取到文本层」WARN）

## 遗留问题

- 后端 LLM 相关路径（parseJd/parsePoster/evaluateMatch 的 happy path）未纳入集成测试——依赖外部 API key，宜用 WireMock stub OpenAI 端点（后续切片）
- 前端无测试基线（vitest 未引入）；当前靠构建 + 人工冒烟
- 测试运行需本机 Docker Desktop 在线；CI 环境（如未来 GitHub Actions）开箱即用
