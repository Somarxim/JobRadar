# JobRadar 部署指南

JobRadar 是「前端静态站点 + 后端 Spring Boot + PostgreSQL(pgvector)」三层架构。
本地开发用 `docker compose -f deploy/docker-compose.yml up -d` 起数据库即可；
本文档覆盖**生产环境**的几种部署路径。

---

## 1. 架构概览

| 层 | 技术 | 部署产物 |
|---|---|---|
| 前端 | Vite + React + Tailwind | 静态文件（`dist/`）→ CDN/边缘网络 |
| 后端 | Spring Boot 3.5 (Java 21) | 可执行 JAR → 容器/PaaS |
| 数据库 | PostgreSQL 16 + pgvector | 托管数据库或 Docker 容器 |

---

## 2. 前端：Vercel（推荐）

Vercel 对 Vite 项目零配置，自动识别框架、CDN 全球加速、HTTPS 默认开启。

### 步骤

1. 把代码推送到 GitHub（已有）
2. [vercel.com](https://vercel.com) → New Project → 导入 JobRadar 仓库
3. **Root Directory** 填 `frontend`（Vercel 只构建前端目录）
4. **Framework Preset** 选 `Vite`
5. 环境变量：无需（前端纯静态，API 地址通过 vite proxy / 运行时 base URL 处理）
6. Deploy

部署完成后拿到域名（如 `https://job-radar-xxxxx.vercel.app`），把该域名填到后端的 `CORS_ORIGINS` 环境变量。

### 国内访问

Vercel 在国内偶有波动；若追求稳定，可换：
- [Netlify](https://netlify.com)（同理零配置）
- 阿里云 OSS + CDN（把 `dist/` 上传静态托管）

---

## 3. 后端：平台选择

### 方案 A：Render（海外，免费 tier，最简单）

适合「先跑起来验证」。

| 项目 | 配置 |
|---|---|
| 服务类型 | Web Service |
| 构建命令 | `cd backend && mvn package -pl jobradar-app -am -DskipTests -q` |
| 启动命令 | `java -jar backend/jobradar-app/target/*.jar` |
| 环境变量 | `DB_URL` / `DB_USER` / `DB_PASSWORD` / `CORS_ORIGINS` / `JOBRADAR_LOCAL_TOKEN` / `DeepSeek_API_KEY` |
| 数据库 | Render PostgreSQL（免费 90 天，支持 pgvector） |

**注意**：Render 免费实例 15 分钟无请求会休眠，首次唤醒约 30s；爬虫定时任务（@Scheduled）在休眠期间不会触发。若需要 7×24 爬虫，需升级付费或换方案 B/C。

### 方案 B：阿里云/腾讯云轻量应用服务器（国内，推荐长期用）

适合「国内访问快 + 7×24 在线 + 数据自主」。

1. 购买一台 2C4G 轻量服务器（约 100 元/月）
2. 安装 Docker：`curl -fsSL https://get.docker.com | sh`
3. 克隆仓库，`cd deploy`
4. 写 `.env` 文件：
   ```bash
   DB_PASSWORD=你的强密码
   JOBRADAR_LOCAL_TOKEN=你的强令牌
   CORS_ORIGINS=https://你的前端域名.vercel.app
   DeepSeek_API_KEY=sk-xxx
   ```
5. 启动：`docker compose -f docker-compose.prod.yml up -d`
6. 配置 Nginx/Caddy 反向代理 + HTTPS（可用 `caddy` 一行自动证书）

**优点**：国内访问快、爬虫抓国内招聘站稳定、数据在自己服务器上、7×24 在线。

### 方案 C：Railway / Fly.io（海外 PaaS）

与 Render 类似，但 Railway 免费 tier 不强制休眠，更适合定时任务。

---

## 4. 关键环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `DB_URL` | ✅ | `jdbc:postgresql://host:5432/jobradar` |
| `DB_USER` | ✅ | 数据库用户名 |
| `DB_PASSWORD` | ✅ | 数据库密码 |
| `CORS_ORIGINS` | ✅ | 前端域名，如 `https://job-radar.vercel.app` |
| `JOBRADAR_LOCAL_TOKEN` | ✅ | Chrome 插件/本地 API 校验令牌（生产必须改！） |
| `DeepSeek_API_KEY` | ❌ | LLM 解析能力；为空时 ingest 降级为人工填写 |
| `DASHSCOPE_API_KEY` | ❌ | 海报多模态解析；为空时该功能不可用 |
| `LLM_DAILY_TOKEN_LIMIT` | ❌ | 每日 token 成本闸，默认 20 万 |

**安全提醒**：
- `JOBRADAR_LOCAL_TOKEN` 默认是 `dev-only-token-change-me`，生产必须改为随机强密码
- API key 绝不进 git，只通过平台环境变量注入
- 数据库密码不使用默认值 `postgres`

---

## 5. 首次部署后的初始化

1. **数据库迁移**：Flyway 在应用启动时自动跑迁移（V1~V8），无需手动执行
2. **爬虫源配置**：POST `/api/crawl/sources` 录入源（或等 V4 seed 自动插入）
3. **上传简历**：进入「简历」页上传 PDF，设默认简历
4. **运行爬虫**：Dashboard 点「立即爬取」抓一批岗位
5. **生成推荐**：Dashboard 点「立即推荐」产出今日推荐

---

## 6. 爬虫 7×24 在线的保障

- 本地部署：电脑关机 = 后端停止 = 定时爬虫错过窗口。手动「立即爬取」是 baseline
- 云部署：服务器持续运行，@Scheduled  cron 可靠触发
- 启动自愈：后端启动时检查 `crawl_sources.last_crawled_at`，若早于当天 07:30 则补跑一轮（未来迭代）

---

## 7. 域名与 HTTPS

建议配置：
- 前端：`https://jobradar.yourdomain.com`（Vercel 自定义域名）
- 后端：`https://api.jobradar.yourdomain.com`（Nginx/Caddy 反代 + Let's Encrypt）

---

## 8. 备份

数据库是核心资产：
```bash
# 每日自动备份（crontab）
0 3 * * * docker exec jobradar-db pg_dump -U postgres -d jobradar | gzip > /backup/jobradar-$(date +\%F).sql.gz
```

简历 PDF 文件在 `/data/resumes`（docker-compose.prod.yml 的 volume），建议定期 `rsync` 到对象存储。
