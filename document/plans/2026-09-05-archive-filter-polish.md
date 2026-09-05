# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-05 |
| 分支 | feat/archive-filter-polish |
| 类型 | fix |

## 改动内容

W1 第四轮反馈（用户四条意见）：

1. **看板/漏斗/日历/待办过滤已归档岗位**（后端）：`ApplicationRepository` 新增 `findByJobActiveTrue()` 及带 `JobActiveTrue` 条件的派生查询，`countGroupByStage` 的 JPQL 加 `where a.job.active = true`；`ApplicationService.board()`、`DashboardService` 的漏斗/待办/日历共 5 处调用点切换。归档（active=false）语义自此完整：岗位从列表、看板、统计、日历全面消失
2. **「累计投递（在途）」→「累计投递（流程中）」**：文案更贴合中文语境
3. **侧边栏降深**：slate-900 → slate-800，文字 slate-400 → slate-300、悬停 hover:bg-slate-700，降低与白色内容区的割裂感
4. **详情页左栏顺序调整**：岗位信息（高度稳定，概览置顶）→ 投递状态 → 职位描述（高度不定，放最后）

## 改动原因

用户反馈：①归档岗位仍出现在看板（归档语义不完整）②"在途"用词生硬 ③深蓝侧边栏与白色内容区对比过硬 ④JD 高度不定，应放左栏最后。

## 技术概念卡

### 派生查询的属性路径（Property Traversal）
- 是什么：Spring Data 方法名中的 `JobActiveTrue` 会解析为 `job.active = true` 的隐式 JOIN 条件，无需手写 JPQL
- 项目里哪里用了：`findByJobActiveTrueAndNextActionAtBetween` 等；`countGroupByStage` 因已有 @Query 注解，直接在 JPQL 里加 `a.job.active = true`
- 面试可能追问：派生查询解析歧义？（如 `JobActive` 优先匹配实体的 jobActive 字段，无则按驼峰切分为 job.active；易歧义时可用下划线显式分隔 `findByJob_ActiveTrue`）

### 软删除/归档的查询一致性
- 是什么：归档（active=false）不是删除，但所有「工作区视图」查询都必须带 active 条件，否则归档数据泄漏回 UI
- 项目里哪里用了：岗位列表/DDL 倒计时此前已过滤，本轮补齐看板/漏斗/待办/日历 5 处
- 面试可能追问：如何避免漏加过滤条件？（@Where/@SQLRestriction 全局过滤器，或数据库视图；本项目查询点少，显式条件更直观，故不用全局过滤）

## 影响范围

后端：ApplicationRepository + ApplicationService.board + DashboardService（查询收紧，契约不变）。
前端：DashboardPage 文案、AppLayout 侧边栏色、JobDetailPage 左栏顺序。

## 验证方式

- [x] 后端重启后经代理验证：归档岗位（job 5, is_active=f）的 planned 申请从看板消失，漏斗 planned 1→0
- [x] `pnpm build` 零错误
- [x] 排障记录：首次重启后过滤未生效——`mvn spring-boot:run` 在 jobradar-app 目录单模块运行时用的是 .m2 里的旧 core 构件，需先在 backend 根目录 `mvn install` 重打包 core（与 W1 骨架期的多模块构建坑同源，已再次印证）
- [ ] 侧边栏浅色效果、详情页新顺序需用户浏览器复核

## 遗留问题

- 多模块本地开发流程：改 core 后必须 `mvn install` 再启动 app，已在两份开发记录中踩过；W2 可考虑 root 直接 `mvn spring-boot:run -pl jobradar-app -am` 的可用性或 devtools 热重启
