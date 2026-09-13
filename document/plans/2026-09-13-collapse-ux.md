# 2026-09-13 岗位库分组折叠 UX 优化

## 背景
用户反馈：折叠栏和内容栏区分度不高，折叠反馈差。
经 3 视角设计评审（视觉层级/交互反馈/信息密度）+ 评委收敛，产出最终规格并实现。

## 改动内容（全部在 JobsPage.tsx + labels.ts）

### 视觉层级
- 组头背景加深：展开 `bg-muted/60` / 折叠 `bg-muted/30`（原为统一的 muted/40，与子行几乎无区分）
- 组头左侧 2px 动态色带：颜色 = 公司类型色（`COMPANY_TYPE_META` 新增 `band` 字段），折叠时 50% 透明
- 子行缩进 + 层级导引线：checkbox 列 `pl-6` + 绝对定位竖线，与组头 chevron 纵向对齐
- 单岗位公司弱左边线（`border-l-muted/20`），消除「有的有头有的没头」的视觉断层
- 组间插入 `h-1` 呼吸缝行，避免组头/子行跨组粘连

### 交互反馈
- chevron 容器化：圆角描边小按钮（affordance），单图标 `-rotate-90` 旋转动画替代双图标切换
- 折叠显示「已折叠」虚线徽章；计数改实心 Badge
- 组头加 `title` 预告（「点击折叠，隐藏 N 个岗位」）、focus-visible 环、active 按压底色
- 双向动画：展开时子行阶梯淡入（index*30ms 级联，`tw-animate-css` 已有依赖）；
  折叠时先 150ms 淡出（`collapsing` Set 暂存动画中状态），再卸载 DOM

## 技术概念卡

**Tailwind JIT 动态类名陷阱**
`border-l-${color}-500` 这类拼接类不会被打包——JIT 只扫源码字面量。
解法：`COMPANY_TYPE_META` 加显式 `band` 字段（字面量类名），单一事实源扩展而非运行时拼接。

**table 行的动画约束**
`<tr>` 不能做 height transition（破坏表格布局模型）。
双向折叠动画的替代方案：拆「视觉消失」（animate-out 150ms）与「DOM 卸载」（setTimeout 后才改 collapsed），
进入方向靠 `animate-in` + 条件渲染自然触发（重新挂载即重放动画）。
快速连点防抖：展开时先 clearTimeout 取消 pending 的折叠。

## 验证
- 截图确认：组头色带/缩进/徽章/折叠态区分度（jobs-expanded.png / jobs-collapsed.png）
- 点击折叠：chevron 旋转 + 子行淡出 + 「已折叠」徽章出现，无布局跳动
