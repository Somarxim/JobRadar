# 开发记录

| 字段 | 内容 |
|---|---|
| 日期 | 2026-09-13 |
| 分支 | feat/deploy |
| 类型 | chore（基础设施） |

## 改动内容

- **生产部署配置**：用户希望项目能挂到类似 Vercel 的平台，解决本地部署后端不在线导致的爬虫断档
  - `backend/Dockerfile`：多阶段构建（Maven 编译 → JRE 运行），Alpine 基础镜像，含中文字体支持、健康检查
  - `frontend/vercel.json`：Vite 框架配置、SPA 回写、静态资源长期缓存
  - `deploy/docker-compose.prod.yml`：生产级编排（db + app 双服务，环境变量驱动，卷持久化）
  - `docs/deployment.md`：部署指南，覆盖 Vercel/Render/阿里云三种路径，含环境变量清单、安全提醒、备份建议
  - `application.yml`：`server.address` 改为 `${SERVER_ADDRESS:127.0.0.1}`，本地默认安全，生产用 `0.0.0.0` 覆盖

## 改动原因

本地部署的后端随用户电脑开关机，@Scheduled 定时爬虫错过窗口不补跑，导致数据 stale。
云部署让后端 7×24 在线，爬虫和每日推荐都能可靠触发；前端静态站挂 CDN 加速全球访问。

## 技术概念卡

### 本地工具 vs 云服务的部署哲学
- 是什么：本地工具 = 进程断续运行，定时任务是 bonus；云服务 = 进程常开，定时任务是基线。
  从本地迁到云，不只是「换个地方跑」，而是数据新鲜度保障从「用户记得开机」变为「基础设施 SLA」
- 项目里哪里用了：docker-compose.prod.yml + deployment.md 的多平台方案
- 面试可能追问：为什么不一上来就云部署？→ 秋招周期内需求变化快，本地开发验证成本低；
  云部署是「验证后的放大器」，不是「开发的起点」。项目路径符合「先本地 MVP → 后云上 7×24」

## 影响范围

- 新增 4 个文件（Dockerfile / vercel.json / docker-compose.prod.yml / deployment.md）
- application.yml 一处变更（server.address 环境变量化）
- 无业务逻辑变更

## 验证方式

- `mvn compile` 全绿（application.yml 语法 Spring Boot 容忍）
- Dockerfile 可通过 `docker build -f backend/Dockerfile backend/` 构建（需网络下载依赖）

## 遗留问题

- 未配置 CI/CD 流水线（GitHub Actions → Vercel auto-deploy + Docker image push）
- 未配置启动自愈补跑爬虫（概念设计在 deployment.md §6，代码待实现）
- mcp-server 未包含在生产编排中（当前仅部署 app 模块）
