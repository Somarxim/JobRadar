# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-13 |
| 分支 | feat/resume-delete |
| 类型 | feat |

## 改动内容

- **简历删除/归档**：用户反馈秋招常改简历，会堆积很多版本，容易误选
  - 后端：resumes 表新增 `archived` boolean（Flyway V8），ResumeRepository
    加 `findByArchivedFalse()`，list() 只返回未归档简历
  - `ResumeService.delete(id)`：软删（archived=true），幂等（已归档直接忽略）；
    若删除的是默认简历，自动把默认资格转给下一个未归档简历
  - `DELETE /api/resumes/{id}` → 204 No Content
  - 前端：简历卡片加「删除」按钮（红色 ghost），点击弹出确认弹窗说明
    「若该简历已生成过匹配报告，数据会保留在后台供历史查看」
  - 修正 `upload()` 的「首份自动默认」逻辑：原 `count() == 0` 在已有归档简历时
    不会触发自动默认，改为 `findByIsDefaultTrue().isEmpty()`

## 改动原因

秋招周期内简历迭代频繁（不同岗位方向需要不同侧重点），没有删除入口会导致
列表无限增长，增加选错版本的风险。物理删除会破坏 match_reports 外键关联的
历史数据，因此采用软删——用户视角「消失了」，数据层面完整保留。

## 技术概念卡

### 软删 vs 物理删除的取舍
- 是什么：软删=加标记位过滤；物理删除=真正移除行。选择取决于外键关联密度
  和业务对历史数据的需求
- 项目里哪里用了：Resume 有 match_reports 外键（无 cascade），物理删除会被
  数据库 FK 约束阻断；Application 的 batchDelete 也走了「有关联则归档」的
  类似哲学（jobs 的 batch-delete）
- 面试可能追问：为什么不用级联删除？→ 匹配报告是用户决策依据，删除简历后
  用户仍可能回顾「我当时为什么没投这个岗」，级联掉就丢了上下文；软删保留
  了复盘能力

## 影响范围

- 新增 Flyway V8 迁移（resumes.archived + 部分索引）
- Resume list API 行为变化：已归档简历不再返回（对前端透明，不需要 archived 字段）
- 无 breaking change：旧数据 archived=false 默认兼容

## 验证方式

- `mvn compile` 全绿（补了 List/DeleteMapping import）
- `mvn test` 56/56 通过（含集成测试）
- `npm run build` / `tsc --noEmit` 全绿

## 遗留问题

- 当前没有「已归档简历回收站」页面：用户误删后无法自助恢复，但数据在库里，
  需要时可通过 DB 直接改 archived=false；若后续反馈误删频繁，可补回收站页
