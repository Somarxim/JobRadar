# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-11 |
| 分支 | feat/route-error-boundary |
| 类型 | fix |

## 改动内容

- **路由级错误边界**：根路由挂 `errorElement: <RouteErrorState />`（StatusStates.tsx 新增，
  与 Loading/Error/Empty 同文件保持状态组件集中）。任何子路由组件渲染抛错时，收敛为
  「页面出错了 + 错误消息 + 刷新页面/回到仪表盘」，不再白屏露出 react-router 默认的
  "Unexpected Application Error" 堆栈页；404（无匹配路由抛出的 Response）单独表述为
  「页面不存在或已被移动」
- **Dashboard 崩溃排查结论**（用户报告 recharts `Cannot read properties of null (reading
  'useContext')`）：非代码 bug。干净浏览器 + 重新预打包依赖后仪表盘 3 个图表正常渲染、
  控制台零报错。根因是 vite.config.ts 变更（advancedChunks）后，浏览器仍引用旧哈希
  `.vite/deps/recharts.js?v=3d296421` 预打包产物，与新的 React  chunk 拼出两份 React 实例
  → hook dispatcher 为 null。vite 重启时检测到 config 变化已自动重优化（日志
  "Re-optimizing dependencies because vite config has changed"），用户侧重启 dev server +
  硬刷新即可

## 改动原因

报错页本身是 react-router 给的开发者提示（"you can provide a way better UX ... by providing
your own ErrorBoundary or errorElement"），趁本次排查把这块短板补齐：崩溃态降级为可操作界面，
错误细节 console.error 保留排查入口。

## 技术概念卡

### React「两份实例」综合征
- 是什么：`Cannot read properties of null (reading 'useContext')` / `useState` 等 hook 调用
  报 null，几乎都是运行时存在两份 React——当前渲染树用的 dispatcher 挂在另一个实例上，
  本实例是 null。dev 下常见诱因：vite 预打包缓存与源码模块混载（旧 hash 的 .vite/deps
  被浏览器缓存引用）、monorepo 里依赖被重复安装
- 项目里哪里用了：本次用户侧的崩溃；排查路径 = npm ls react 去重确认 → 干净浏览器复现
  → vite 重优化日志坐实缓存过期
- 面试可能追问：生产构建会有吗？→ 几乎不会，rolldown 打包时模块图是静态闭包，React 只会
  进一个 chunk；这是 dev 预打包机制（esbuild 按依赖边界拆 bundle + hash 缓存）特有的
  一致性问题，所以修复动作是「清缓存重启」而非改代码

### react-router errorElement 的捕获语义
- 是什么：data router（createBrowserRouter）在渲染/loader/action 任一环节抛错时，沿路由
  树向上找最近的 errorElement 渲染兜底；`useRouteError()` 拿到原始错误，
  `isRouteErrorResponse()` 区分路由框架自己抛的 Response（404/loader 响应）与组件异常
- 项目里哪里用了：App.tsx 根路由挂 RouteErrorState，一处兜底全部子路由
- 面试可能追问：和 ErrorBoundary 类组件区别？→ 语义相同（渲染期错误兜底），但
  errorElement 由 router 驱动，能区分 404/loader 错误并保留导航上下文；类组件
  ErrorBoundary 捕获不了 loader/action 错误。两者可叠加：errorElement 管路由层，
  ErrorBoundary 管路由外的悬浮组件（如全局 Toaster 之外的弹层）

## 影响范围

- 纯前端、纯新增兜底路径：正常渲染路径零改动
- 404 体验变化：之前是 react-router 默认灰页，现在是统一风格的提示页

## 验证方式

- `tsc --noEmit` / `vite build` / oxlint 全绿（仅 2 个 shadcn 模板遗留 warning）
- 真实浏览器验证（worktree dev server :5198）：访问不存在路由 → 渲染「404 页面不存在
  或已被移动 + 刷新/回仪表盘」；访问 `/` → 3 个 recharts 图表正常渲染、无新增报错

## 遗留问题

- 若后续要给崩溃页加「上报」入口（如写 localStorage 错误日志供反馈），在 RouteErrorState
  内扩展即可
