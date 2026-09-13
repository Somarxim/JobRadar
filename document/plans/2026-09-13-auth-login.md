# 2026-09-13 登录认证系统（公网部署安全加固）

## 背景
用户计划将项目部署到公网服务器，当前系统无任何认证，IP/域名泄露后等于裸奔。需要最小可行的单用户密码登录。

## 改动内容

### 后端
- **pom.xml**：引入 `spring-boot-starter-security`
- **SecurityConfig**：
  - `InMemoryUserDetailsManager` 单用户（用户名固定 `admin`，密码走环境变量 `JOBRADAR_PASSWORD`）
  - `authEnabled` 开关：默认启用认证；开发环境设 `JOBRADAR_AUTH_ENABLED=false` 免登
  - Session Cookie 认证（非 JWT）：单用户场景下 JWT 引入额外复杂度无收益
  - 自定义 JSON 响应处理器：登录成功/失败、401 未认证、登出成功均返回 JSON（适配 API 模式）
- **AuthController**：`GET /api/auth/me` 供前端启动时校验登录态
- **WebConfig**：CORS 开启 `allowCredentials(true)`（Session Cookie 跨域必需）
- **application.yml**：新增 `jobradar.security.password` / `auth-enabled` 配置项

### 前端
- **LoginPage**：居中卡片表单，用户名固定 `admin`，输入密码后调用 `POST /api/auth/login`（form-urlencoded）
- **App.tsx**：`ProtectedLayout` 路由守卫，启动时调 `/api/auth/me`，未登录跳转 `/login`
- **client.ts**：
  - 所有请求携带 `credentials: 'include'`
  - 拦截 401 自动跳登录页
  - 新增 `api.login/logout/me`
- **AppLayout**：侧边栏底部增加「退出登录」按钮

## 技术概念卡

**Session vs JWT（面试点）**
- Session：服务端存登录态，Cookie 带 session-id 往返；登出即销毁；适合单用户/小团队
- JWT：无状态，服务端不存会话；但刷新、黑名单、密钥轮换复杂；适合多租户/微服务
- 本项目选 Session 的理由：只有一人使用，引入 JWT 的边际收益为负

**Spring Security 在 API 模式下的配置要点**
1. 关闭 CSRF（前后端分离 + Cookie 跨域时维护 CSRF token 成本高，单用户场景风险可控）
2. 自定义 `AuthenticationEntryPoint` 返回 JSON 401（而非默认的 302 重定向到 HTML 登录页）
3. `formLogin()` 的 `successHandler` / `failureHandler` 均配成 JSON 响应

**CORS + Cookie 的坑**
- `fetch` 必须设 `credentials: 'include'`
- CORS 配置必须开 `allowCredentials(true)`
- 一旦开 credentials，`Access-Control-Allow-Origin` 不能是 `*`，必须是具体域名（或 pattern）

## 本地开发注意
启动后端前如不想每次都登录，可设环境变量：
```bash
export JOBRADAR_AUTH_ENABLED=false
```
或在前端 `.env` 同级给后端配 `application-local.yml` 覆盖。

## 生产部署注意
- `JOBRADAR_PASSWORD` 必须改为强密码（默认 `jobradar` 仅用于首次体验）
- `JOBRADAR_LOCAL_TOKEN` 同样需要修改（原本就存在的安全项，这里一并提醒）
- 两个密钥都通过环境变量注入，不入 git
