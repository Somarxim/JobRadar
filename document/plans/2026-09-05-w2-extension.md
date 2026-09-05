# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-05 |
| 分支 | feat/w2-extension |
| 类型 | feat |

## 改动内容

W2 第五切片：Chrome 插件 v1（采集入口闭环）。

1. **extension/ 目录**（MV3，零构建链——手写 HTML/JS/CSS 与前端 zinc/blue 风格对齐）：
   - `manifest.json`：最小权限集（activeTab + scripting + storage + host localhost:8080）
   - `popup.html/js`：打开即注入提取函数抓当前页（**选中文字优先**，否则正文截断 8000 字）→ 表单确认/补全（公司/岗位留空由 AI 提取）→ `POST /api/jobs/ingest`（source=extension）→ 成功给「查看详情」跳转链接
   - 令牌存 `chrome.storage.local`（设置区折叠收纳）
   - `README.md`：安装步骤 + 权限说明
2. **后端零改动**：CORS 的 `allowedOriginPatterns("chrome-extension://*")` 与 LocalTokenFilter 的令牌校验在 W1 已就位——插件请求无受信 Origin（扩展 id 随机），走 X-Local-Token 路径

## 改动原因

W2 验收要求「插件在 BOSS/牛客/某国企官网各收藏 1 个真实岗位成功入库」。采集入口是「发现-入库」闭环的起点。

## 技术概念卡

### Chrome Extension MV3 权限模型
- 是什么：声明式权限清单；activeTab 仅在用户点击图标时授予当前页临时访问权，scripting 按需注入函数（非常驻 content script）
- 项目里哪里用了：manifest 只声明 4 项权限；提取函数以 `func` 注入页面上下文执行，站点无关
- 面试可能追问：content script vs scripting.executeScript？（前者常驻每页、耗电耗内存且权限范围大；后者按需注入、最小权限）；为什么不过度申请 `tabs` 权限？（tabs 能读所有标签页 URL=浏览历史，隐私敏感，activeTab 足够）

### CSRF 与本地 API 的鉴权设计
- 是什么：CORS 只拦「读」不拦「写」——恶意网页可借浏览器向 127.0.0.1 发简单跨域 POST；本地单用户应用的防线是受信 Origin + 自定义头令牌
- 项目里哪里用了：LocalTokenFilter（GET 放行 / 受信 Origin 放行 / 其余要求 X-Local-Token）；插件走令牌路径
- 面试可能追问：为什么自定义头能防 CSRF？（自定义头触发 CORS 预检，非简单请求被浏览器拦截；token 不存 cookie 不随请求自动携带）

## 影响范围

仅新增 extension/ 目录；后端、前端、数据库零改动。

## 验证方式

- [x] 模拟插件请求特征（无 Origin + X-Local-Token + source=extension + raw_text + hints）POST /api/jobs/ingest：job_id=8 中国工商银行软件开发中心 Java 后端（2026 校招），公司/岗位/截止日期 AI 提取成功，source_platform=extension 落库
- [x] 无 token 的同类请求 401（此前切片已验证）
- [ ] **真实浏览器安装与三站点实测待用户操作**（chrome://extensions 开发者模式加载；步骤见 extension/README.md）

## 遗留问题

- 插件暂未做「页面上直接显示该岗位是否已收藏」（需要按 URL 查询的只读端点，v2 加）
- 海报截图导入（插件截屏 → image_base64 走海报解析）等 DashScope 额度恢复后可加
- job8 为 curl 模拟的测试数据（工行软开中心），用户可留作演示或归档
