# JobRadar 部署教程（从零到上线）

> 面向自部署用户的完整教程。全程约 1 小时（不含购买等待）。
> 最终形态：**Vercel 托管前端（免费）+ 你的云服务器跑后端和数据库（Docker）+ Caddy 自动 HTTPS**。
> 另一份 `docs/deployment.md` 是方案对比参考；本文是可照抄的操作手册。

---

## 0. 架构与为什么这样选

```
浏览器 ──HTTPS──▶ Vercel（前端静态站 + /api 反向代理）
                     │
                     └──HTTPS──▶ api.你的域名（Caddy 自动证书）
                                    └──▶ 127.0.0.1:8080 Spring Boot（Docker）
                                            └──▶ PostgreSQL + pgvector（Docker，不暴露公网）

Chrome 插件 ──HTTPS + X-Local-Token──▶ api.你的域名（令牌预认证，不经 Session）
```

关键决策：

| 决策 | 理由 |
|---|---|
| 前端放 Vercel | 免费、自带 CDN 和 HTTPS、GitHub push 自动部署 |
| `/api` 走 Vercel rewrite 代理 | 浏览器视角全程同源，Session Cookie 无跨域问题（SameSite=Lax 即可），前端代码零改动 |
| 后端 Docker Compose | 一条命令拉起 PostgreSQL + 应用，Flyway 自动建表 |
| 香港/新加坡节点 | 域名解析到国内服务器的 80/443 需 ICP 备案（1-3 周）；香港免备案、延迟可接受 |
| Caddy 反代 | 两行配置自动申请+续期 Let's Encrypt 证书，免维护 |
| 8080 只绑 127.0.0.1 | 公网只暴露 443/80/22，应用端口不对网，纵深防御 |

---

## 1. 采购清单

### 1.1 服务器（约 40-70 元/月）

以腾讯云轻量应用服务器为例（阿里云路径几乎相同）：

1. 打开 [轻量应用服务器购买页](https://buy.cloud.tencent.com/lighthouse)
2. **地域**：中国香港（或新加坡）——**免备案关键**
3. **镜像**：Ubuntu 24.04 LTS（**选裸系统，不要应用镜像**）
4. **套餐**：2核4G（推荐）；2核2G 也能跑，本文第 3.4 节会配 swap 兜底
5. **登录方式**：SSH 密钥 → 新建密钥，粘贴你本机的公钥

本机没有密钥的话先生成：

```bash
ssh-keygen -t ed25519 -f ~/.ssh/jobradar-deploy -N "" -C "jobradar-deploy"
cat ~/.ssh/jobradar-deploy.pub   # 输出整串粘到购买页
```

### 1.2 域名（首年约 10 元）

[DNSPod](https://dnspod.cloud.tencent.com) 或万网注册一个 `.top` / `.xyz` 域名即可。
本文示例统一用 `example.top`，**实操时全部替换成你自己的域名**。

---

## 2. 首次登录与安全加固（5 分钟）

购买完成后拿到公网 IP（示例用 `1.2.3.4`）。

```bash
# 本机执行：登录（第一次会提示指纹确认，输 yes）
ssh -i ~/.ssh/jobradar-deploy root@1.2.3.4
```

以下都在**服务器上**执行：

```bash
# 2.1 系统更新
apt update && apt upgrade -y

# 2.2 防火墙：只放行 SSH / HTTP / HTTPS
ufw allow 22 && ufw allow 80 && ufw allow 443
ufw --force enable
ufw status   # 确认 22,80,443 ALLOW
```

> **云厂商还有第二层防火墙**：腾讯云/阿里云控制台的「安全组」独立于 ufw。
> 必须在控制台确认入站规则放行了 80 和 443（默认安全组通常只开 22），
> 否则 ufw 配置得再对，外网也访问不到 Caddy。

```bash
# 2.3 安装 Docker（官方一键脚本）
curl -fsSL https://get.docker.com | sh
docker version   # 验证

# 2.4 小内存机器（2C2G）加 2G swap；2C4G 可跳过
fallocate -l 2G /swapfile && chmod 600 /swapfile
mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

> **安全提醒**：保持「SSH 密钥登录 + ufw 只开 3 个端口」就够个人项目用了。
> 不要去改 `/etc/ssh/sshd_config` 关密码登录，除非确认密钥已可正常登录——改错会把自己锁在门外。

---

## 3. 部署后端（10 分钟）

```bash
# 3.1 拉代码
cd /opt && git clone https://github.com/<你的GitHub>/JobRadar.git
# 仓库为私有时改用 SSH（先在 GitHub 配置部署密钥）或带 PAT：
# git clone git@github.com:<你的GitHub>/JobRadar.git
# git clone https://<PAT>@github.com/<你的GitHub>/JobRadar.git
cd JobRadar/deploy

# 3.2 写环境变量（每个值都换成你自己的）
cat > .env << 'EOF'
DB_PASSWORD=换成强密码-数据库
JOBRADAR_PASSWORD=换成强密码-网页登录
JOBRADAR_LOCAL_TOKEN=换成强随机串-插件令牌
CORS_ORIGINS=https://placeholder.vercel.app
DeepSeek_API_KEY=sk-你的deepseek密钥
DASHSCOPE_API_KEY=
EOF
chmod 600 .env
```

生成强密码的快捷方式：`openssl rand -base64 24`。

> `CORS_ORIGINS` 先填占位符，第 5 步拿到 Vercel 域名后回来改。

```bash
# 3.3 启动（首次构建镜像约 5-10 分钟，取决于机器和网络）
docker compose -f docker-compose.prod.yml up -d

# 3.4 验证：等待健康检查通过
docker compose -f docker-compose.prod.yml ps
# jobradar-app 显示 (healthy) 即可；或手动探：
curl http://127.0.0.1:8080/api/health   # 期望返回含 "status":"UP" 的 JSON
```

数据库表结构（V1~V8）由 Flyway 在应用启动时自动创建，**不需要手动执行任何 SQL**。

---

## 4. HTTPS（域名 + Caddy，5 分钟）

### 4.1 DNS 解析

到域名控制台加一条 **A 记录**：主机记录 `api`，记录值填服务器 IP。
等 1-2 分钟，`ping api.example.top` 能通再往下走。

### 4.2 安装并配置 Caddy

```bash
apt install -y caddy

cat > /etc/caddy/Caddyfile << 'EOF'
api.example.top {
    reverse_proxy 127.0.0.1:8080
}
EOF

systemctl enable --now caddy   # 首次：启用并启动
systemctl reload caddy         # 载入新 Caddyfile（后续改配置用这条）
```

Caddy 会自动向 Let's Encrypt 申请证书并配置好续期。验证：

```bash
curl https://api.example.top/api/health   # 期望返回含 "status":"UP" 的 JSON
```

---

## 5. 部署前端（Vercel，10 分钟）

### 5.1 把后端域名写进代理配置

**在你的开发电脑上**（不是服务器上）编辑仓库里的 `frontend/vercel.json`，把 `api.your-domain.top` 替换成你的真实域名：

```json
"rewrites": [
  { "source": "/api/:path*", "destination": "https://api.example.top/api/:path*" },
  { "source": "/(.*)", "destination": "/index.html" }
]
```

> 顺序不能反：Vercel 按数组顺序匹配，`/api` 规则必须在前。
> **改完必须 commit 并 push 到 GitHub**——Vercel 拉的是远程仓库，本地改动不 push 等于没改。

### 5.2 Vercel 导入项目

1. [vercel.com](https://vercel.com) → 用 GitHub 登录 → New Project → 导入 JobRadar
2. **Root Directory** 选 `frontend`；Framework 自动识别为 Vite
3. 不需要任何环境变量（API 地址在 vercel.json 里）
4. Deploy，得到形如 `https://jobradar-xxxx.vercel.app` 的域名

### 5.3 回服务器补 CORS 并重启

```bash
cd /opt/JobRadar/deploy
# 把 .env 里的 CORS_ORIGINS 改成你的 Vercel 域名（注意 https、不带斜杠结尾）
sed -i 's|CORS_ORIGINS=.*|CORS_ORIGINS=https://jobradar-xxxx.vercel.app|' .env
docker compose -f docker-compose.prod.yml up -d --force-recreate app
```

---

## 6. 端到端验证清单

| 检查项 | 操作 | 预期 |
|---|---|---|
| 前端可访问 | 打开 Vercel 域名 | 跳转到登录页 |
| 登录 | 输入 admin + `JOBRADAR_PASSWORD` | 进入仪表盘 |
| Session 保持 | 刷新页面 | 不掉登录 |
| API 走通 | 仪表盘有数据/无报错 | 数据正常加载 |
| HTTPS 直达后端 | `curl https://api.example.top/api/health` | 返回含 `"status":"UP"` |
| 未认证拦截 | `curl https://api.example.top/api/jobs` | 401 JSON |
| 定时任务 | 等到调度时点或看日志 `docker logs jobradar-app` | 爬虫/推荐按 cron 触发 |

### Chrome 插件指向线上

插件弹窗 → 设置：
- **后端地址**：`https://api.example.top`
- **前端地址**：`https://jobradar-xxxx.vercel.app`
- **X-Local-Token**：与服务器 `.env` 的 `JOBRADAR_LOCAL_TOKEN` 一致

保存后正常收藏岗位即可。插件用令牌预认证，不受网页登录态影响。

---

## 7. 日常运维

### 更新版本

```bash
cd /opt/JobRadar && git pull
cd deploy && docker compose -f docker-compose.prod.yml up -d --build
```

数据库迁移（Flyway）随应用启动自动执行，无需干预。

### 备份（数据库是最核心资产）

```bash
# 每天凌晨 3 点备份，保留最近 14 天
mkdir -p /backup
crontab -e
# 加一行：
0 3 * * * docker exec jobradar-db pg_dump -U postgres -d jobradar | gzip > /backup/jobradar-$(date +\%F).sql.gz && find /backup -name 'jobradar-*.sql.gz' -mtime +14 -delete
```

简历 PDF 在 Docker volume `resumes` 里，定期拷出（先删同名旧目录，避免 docker cp 嵌套进子目录）：

```bash
rm -rf /backup/resumes-$(date +%F)
docker cp jobradar-app:/data/resumes /backup/resumes-$(date +%F)
```

### 看日志

```bash
docker logs -f jobradar-app    # 应用（含 LLM 调用/爬虫明细）
docker logs jobradar-db        # 数据库
journalctl -u caddy -f         # HTTPS 层（Caddy 走 systemd 日志，无独立 CLI 子命令）
```

---

## 8. 排错 FAQ

**Q：登录后立刻被踢回登录页 / 一直 401**
A：九成是 Cookie 没写进去。按顺序查：
1. 浏览器必须走 `https://`（Vercel 域名），不能混用 http；
2. 服务器 `.env` 确认 `SESSION_COOKIE_SECURE` 生效（compose 里已是 `"true"`）——它是 secure Cookie，HTTP 下浏览器直接拒存；
3. F12 → Network → `/api/auth/login` 响应头应有 `Set-Cookie: JSESSIONID=...`（Spring Boot 默认会话 Cookie 名）。

**Q：插件提示「缺少或错误的 X-Local-Token」**
A：插件设置里的令牌与服务器 `.env` 的 `JOBRADAR_LOCAL_TOKEN` 不一致；或后端地址没改（仍指向 localhost）。

**Q：LLM 相关功能（AI 解析/匹配报告/推荐精评）没反应**
A：`DeepSeek_API_KEY` 没配或额度超了。看 `docker logs jobradar-app | grep -i llm`。
未配置时系统按设计降级（ingest 需手填字段），不会报错。

**Q：爬虫没有按点触发**
A：服务器时区默认 UTC，cron 表达式 `0 30 7 * * *` 是容器内时区（镜像已设 Asia/Shanghai）。
`docker exec jobradar-app date` 确认。也可以用 Dashboard 的「立即爬取」手动兜底。

**Q：公司类型想重新分类**
A：`POST https://api.example.top/api/companies/backfill-types`（需先登录拿到 Session，或带 X-Local-Token），
会把所有仍为「其他」的公司重走「规则 → LLM」分类。

**Q：想换服务器/迁移**
A：旧机 `pg_dump` 导出 + 拷出 resumes volume → 新机按第 3 节部署 → 导入备份 → 改 DNS A 记录。

---

## 9. 安全自查清单（上线前过一遍）

- [ ] `.env` 三个密钥都是强随机值（不是默认值/弱密码）
- [ ] `.env` 权限 600，且从未提交进 git
- [ ] ufw 只开 22/80/443
- [ ] 8080 没有直接暴露公网（`curl http://1.2.3.4:8080/api/health` 应该超时）
- [ ] SSH 用密钥登录
- [ ] 登录页密码不是默认值 `jobradar`
- [ ] 数据库端口 5432 未映射到宿主机（compose 默认即如此）
