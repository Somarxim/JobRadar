# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-04 |
| 分支 | feat/jpa-entities |
| 类型 | feat |

## 改动内容

W1 数据模型落地（roadmap W1 第二项）：V2 schema 全量 10 张表的 JPA 映射 + Repository 层。

- **枚举 ×5**（`domain/`）：CompanyType / CompanyTier / ApplicationStage / RecommendationStatus / CrawlCategory，与 CHECK 约束一一对应
- **枚举转换器**（`domain/converter/`）：`LowercaseEnumConverter` 抽象基类 + 5 个 `autoApply` 子类，桥接 Java 大写常量与 DB 小写取值
- **实体 ×10**（`domain/`）：Company / Job / Application / ApplicationEvent / Resume / MatchReport / Recommendation / CrawlSource / WeeklyGoal / Setting
- **Repository ×10**（`repository/`）：派生查询（findByName/existsByDedupeHash/findByStage…）+ 一个 @Query 接口投影（dashboard 漏斗计数）；JobRepository 挂 JpaSpecificationExecutor 为 W1 组合筛选铺路
- `JobRadarApplication`：补 `@EntityScan` + `@EnableJpaRepositories`（scanBasePackages 不覆盖 JPA 扫描，见概念卡）
- core 模块引入 Lombok（optional=true，只生成 @Getter/@Setter）

关键映射决策：

- `jobs.embedding` / `search_vector` / `resumes.embedding` **故意不映射**（validate 不查 DB 多余列）；embedding 等 W2 pgvector-java，search_vector 是生成列由 DB 维护
- JSONB 列统一 `@JdbcTypeCode(SqlTypes.JSON)` + String 透传，W2 LLM schema 定型后再换 POJO 直映
- 关联一律 `FetchType.LAZY`（*ToOne 默认 EAGER 是 N+1 温床）
- 时间戳用 @CreationTimestamp/@UpdateTimestamp 应用侧维护，不依赖 DB DEFAULT

## 改动原因

roadmap W1「数据模型落地：JPA Entity」。实体映射是后续所有 API/Service 的地基；ddl-auto=validate 保证实体与 Flyway schema 强一致。

## 技术概念卡

### scanBasePackages 不管 JPA 扫描（AutoConfigurationPackages 机制）
- 是什么：@SpringBootApplication 的 scanBasePackages 只影响 @ComponentScan；实体扫描与 @EnableJpaRepositories 默认取主类所在包
- 项目里哪里用了：JobRadarApplication 显式 @EntityScan("com.jobradar.core.domain") + @EnableJpaRepositories("com.jobradar.core.repository")
- 面试可能追问：多模块下实体不在主类包下会怎样？（启动报 Not a managed type）怎么排查？

### AttributeConverter（枚举桥接）
- 是什么：JPA 标准扩展点，实体字段 ↔ 数据库列的双向自定义转换
- 项目里哪里用了：LowercaseEnumConverter + 5 个 autoApply 子类（Java 大写枚举 ↔ DB 小写 CHECK 值）
- 面试可能追问：为什么不用 @Enumerated(STRING)？（存 name() 大写，撞 CHECK 约束）；autoApply 与显式 @Convert 的区别？

### @JdbcTypeCode(SqlTypes.JSON)（Hibernate 6 原生 JSONB）
- 是什么：Hibernate 6 内置 JSON 列支持，String/POJO/Map 都可直映 jsonb
- 项目里哪里用了：Job.requirements/meta、MatchReport.detail 等 6 处 JSONB 列
- 面试可能追问：JSONB vs TEXT 存 JSON 的区别？（二进制存储/GIN 索引/路径查询 vs 纯文本）；什么时候不该用 JSONB？（需要约束/关联/高频更新的结构化字段）

### ddl-auto=validate 的实战价值
- 是什么：启动时校验实体映射与库表一致性，不改动表
- 项目里哪里用了：本次开发中真实抓到一处 bug——Job.active 字段漏写 @Column(name="is_active")，命名策略推导出 active 列不存在，启动即报 Schema-validation: missing column
- 面试可能追问：validate 校验什么不校验什么？（校验实体声明的表/列存在且类型兼容；不管 DB 多余的列和索引——所以 embedding 列可以不映射）

### 接口投影（Interface Projection）
- 是什么：统计/部分字段查询不建实体，定义 getter 接口，Spring Data 生成代理返回结果
- 项目里哪里用了：ApplicationRepository.countGroupByStage() 返回 List\<StageCount\>
- 面试可能追问：和 DTO 投影（构造器表达式）的区别？和直接返回 Object[] 的区别？

### Lombok（编译期注解处理）
- 是什么：编译期通过注解处理器改 AST 生成代码（getter/setter 等），非运行期代理
- 项目里哪里用了：全部实体的 @Getter/@Setter；pom 中 optional=true 不传递下游
- 面试可能追问：为什么实体不用 @Data？（equals/hashCode/toString 遍历懒加载字段）；Lombok 与 Spring AOP 的本质区别？

## 影响范围

新增 core 模块领域层，无破坏性变更。jobradar-app 启动类新增两个注解。DB schema 无变化（实体适配既有 Flyway V2）。

## 验证方式

- [x] `mvn install -DskipTests` 三模块编译通过（Lombok 注解处理正常）
- [x] 应用启动，Flyway "Schema is up to date" + Hibernate validate 通过（修复 is_active 命名后）
- [x] `curl /api/health` 返回 UP
- [ ] Repository 读写冒烟：等 W2 Testcontainers 基线（roadmap 计划），不在本提交造临时测试

## 遗留问题

- Repository 层尚无真实数据库读写的自动化测试（W2 Testcontainers 补）
- docs/learning/spring-data-jpa.md 学习笔记待按本提交概念卡填充
- 下一步：W1 API 层（jobs CRUD + 手动录入 + applications 流转 + events + dashboard）
