# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-04 |
| 分支 | feat/backend-skeleton |
| 类型 | feat |

## 改动内容

W1 骨架落地（roadmap W1 第一项）：

- `backend/` Maven 多模块骨架：父 POM（Spring Boot 3.5.0 parent + Spring AI 1.0.0 BOM 占位）+ 三子模块
  - `jobradar-core`：领域模块（JPA/Flyway/Validation 依赖就位，实体下轮提交）
  - `jobradar-app`：Web 宿主（spring-boot-starter-web + Testcontainers 测试依赖）
  - `jobradar-mcp-server`：MCP 宿主占位（W4 引入 MCP starter，结构先行）
- 启动类 ×2：`JobRadarApplication`（显式 `scanBasePackages` 覆盖 core 包）、`JobRadarMcpServerApplication`（`WebApplicationType.NONE`，stdio 宿主不需要 HTTP）
- `HealthController`：`GET /api/health` 用于骨架验证
- `application.yml`：datasource/JPA/Flyway/CORS/local-token 配置（含关键配置的教学注释）
- Flyway 迁移：`V1__extensions.sql`（pgvector + pg_trgm）、`V2__init_schema.sql`（data-model.md v0.2 全量 10 张表 + 索引）
- `deploy/docker-compose.yml`：pgvector/pgvector:pg16 单容器（仅绑回环地址 + healthcheck）
- `.gitignore`：补充 Java/Maven 条目

## 改动原因

按 roadmap W1 计划搭建可运行骨架，验证「多模块构建 + 数据库容器 + Flyway 迁移 + 应用启动」全链路，为后续实体与 API 开发奠基。

## 技术概念卡

### Spring Boot 父 POM（parent）与 BOM
- 是什么：parent 提供依赖版本仲裁与插件默认配置；BOM（如 spring-ai-bom）只统一版本不含插件配置
- 项目里哪里用了：backend/pom.xml 继承 spring-boot-starter-parent，dependencyManagement 导入 spring-ai-bom
- 面试可能追问：parent 和 import scope 的 BOM 有什么区别？为什么 Spring AI 走 BOM 而不是 parent？

### 多模块 Maven（packaging=pom + modules）
- 是什么：父工程聚合子模块，统一构建；模块间用依赖声明分层
- 项目里哪里用了：core（领域逻辑）/ app（HTTP 宿主）/ mcp-server（stdio 宿主），见 ADR-5
- 面试可能追问：为什么不分 git 仓库？（单体仓库 + 模块边界，个人项目成本最低）

### ddl-auto=validate
- 是什么：Hibernate 启动时只校验实体与库表一致性，不自动改表
- 项目里哪里用了：application.yml（配合 Flyway 管理 schema）
- 面试可能追问：为什么不用 update？（实体与库表漂移不可控，生产事故高发点）

### open-in-view=false
- 是什么：关闭"视图层保持 Hibernate Session"的默认行为，懒加载必须在 Service 事务内完成
- 项目里哪里用了：application.yml
- 面试可能追问：OSIV 开着有什么问题？（隐藏 N+1、占用数据库连接到渲染结束）

### Flyway
- 是什么：按版本号顺序执行 SQL 迁移脚本，记录 checksum 防篡改
- 项目里哪里用了：V1__extensions.sql / V2__init_schema.sql，应用启动时自动执行
- 面试可能追问：和 JPA 的 create/update 有什么区别？脚本冲突/回滚怎么处理？

### tsvector 生成列（GENERATED ALWAYS AS ... STORED）
- 是什么：由 search_text 自动派生的全文检索向量列，写入时自动计算并存盘
- 项目里哪里用了：jobs 表（中文经应用层 jieba 分词后写 search_text）
- 面试可能追问：为什么中文不能直接 to_tsvector？（缺中文分词，simple 配置按空白切分，故应用层先分词）

## 影响范围

新增后端骨架，无既有代码影响。数据库为全新容器卷，无迁移负担。

## 验证方式

- [x] `docker compose -f deploy/docker-compose.yml up -d` 容器健康（2026-09-04 重建容器后 healthcheck 通过，pgvector 0.8.6）
- [x] `mvn -q install -DskipTests` 三模块编译通过（本机 mvn；Maven Wrapper 待后续提交生成）
- [x] `mvn spring-boot:run`（在 jobradar-app 模块目录）启动成功，Flyway 日志显示 V1（extensions）/ V2（init schema）已应用
- [x] `curl http://127.0.0.1:8080/api/health` 返回 `{"service":"jobradar-app","status":"UP",...}`

（2026-09-04 回填：JDK 21 (Temurin 21.0.12.1) 环境下全链路验证通过。注意：根目录 `mvn spring-boot:run -pl jobradar-app -am` 会把 run goal 跑在父 POM 上报 "Unable to find a suitable main class"，需先 `mvn install` 再到模块目录启动。）

## 遗留问题

- ~~本机原仅有 JDK 8/17，JDK 21 经 Homebrew 安装中~~ 已就绪并验证
- JPA 实体类、Repository 未创建（下一提交，与 ddl-auto=validate 联调）
- springdoc-openapi（接口文档）W2 与业务 API 一并引入
