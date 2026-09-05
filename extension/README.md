# JobRadar 采集助手（Chrome 扩展 v1）

一键把当前招聘页面收藏进本地 JobRadar：抓取页面文本（选中优先）→ 后端 LLM 结构化 → 入库去重。

## 安装（开发者模式）

1. 确认后端已启动（`backend/jobradar-app` 下 `mvn spring-boot:run`）
2. Chrome 打开 `chrome://extensions/`，右上角开启「开发者模式」
3. 「加载已解压的扩展程序」→ 选择本目录（`extension/`）
4. 点击工具栏扩展图标 → 底部「设置」→ 填入与后端 `jobradar.security.local-token`
   一致的令牌（默认 `dev-only-token-change-me`）→ 保存

## 使用

1. 在 BOSS 直聘 / 牛客 / 研究所官网打开岗位详情页
2. （可选）选中 JD 正文——选中内容优先于全页抓取
3. 点扩展图标 → 确认/补全字段（公司、岗位留空由 AI 提取）→「AI 解析并入库」
4. 成功后点「查看详情」跳到本地前端岗位详情页

## 权限说明（面试可答）

- `activeTab` + `scripting`：仅在点击图标时向当前页注入提取函数，不常驻后台、不读浏览历史——最小权限原则
- `storage`：令牌存 `chrome.storage.local`（不进页面上下文，网页脚本读不到）
- `host_permissions: localhost:8080`：只允许访问本地后端

## 安全链路

扩展请求无受信 Origin（`chrome-extension://` 随机 id）→ `LocalTokenFilter` 要求 `X-Local-Token`
→ CORS 由 `WebConfig` 的 `allowedOriginPatterns("chrome-extension://*")` 放行预检。
防的是恶意网页借浏览器 POST 本地 API（CSRF），不是防黑客。
