# 服务器迁移部署 Runbook

本文档用于把当前 Noval 服务迁移到新服务器，目标是让后续迁移时可以按清单备份、复制、启动和验证。

当前线上已知信息：

- 主域名：`www.panch.fun`
- 根域名：`panch.fun`
- 当前公网 IP：`47.238.247.169`
- SSL 宿主机目录：`/etc/nginx/ssl`
- SSL 文件：
  - `/etc/nginx/ssl/cloudflare-origin-pull-ca.pem`
  - `/etc/nginx/ssl/panch-origin.crt`
  - `/etc/nginx/ssl/panch-origin.key`
- 当前 `.env` 记录位置：`/etc/opt/noval/ssl/.env`

> 注意：`.env` 里包含数据库密码、Redis 密码、JWT 密钥、模型 key、Turnstile secret 等敏感值。迁移时只在服务器之间安全传输，不要提交到仓库，不要贴到日志或工单。

## 1. 部署拓扑

当前项目推荐使用仓库根目录的 `docker-compose.yml` 部署。服务边界如下：

```text
Cloudflare
  -> nginx 容器: 80/443
    -> backend 容器: 8080
      -> mysql 容器: 3306
      -> redis 容器: 6379
      -> crawler 容器: 5000
      -> langgraph-worker 容器: 8001
```

公网只应该暴露：

- `80`
- `443`
- `22`，建议限制来源 IP

不要向公网暴露：

- `8080`
- `5000`
- `8001`
- `3306`
- `6379`

## 2. 迁移前必须准备

### 2.1 服务器基础

新服务器需要：

- Linux 服务器，建议 Ubuntu 22.04/24.04 或 Debian 12。
- Docker Engine。
- Docker Compose v2。
- 可访问外网，用于拉取镜像、安装依赖、访问 DeepSeek/OpenAI-compatible API。
- 防火墙开放 `80/443`，`22` 按需开放。

基础安装示例：

```bash
sudo apt update
sudo apt install -y ca-certificates curl git openssl
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
docker version
docker compose version
```

安装后重新登录一次，让 docker 用户组生效。

### 2.2 必须迁移的文件

必须从旧服务器复制到新服务器：

```text
/etc/nginx/ssl/cloudflare-origin-pull-ca.pem
/etc/nginx/ssl/panch-origin.crt
/etc/nginx/ssl/panch-origin.key
/etc/opt/noval/ssl/.env
```

建议同时备份：

```text
/opt/noval 或当前项目部署目录
MySQL 数据库 dump
Redis 数据，通常可选
当前 docker-compose.yml 渲染结果
```

### 2.3 必须保持一致或谨慎轮换的密钥

迁移时建议保持不变：

- `CONFIG_SECRET_MASTER_KEY`
  - 如果数据库里已经保存了加密后的模型 key 或系统密钥，这个值必须保持一致，否则后端无法解密旧配置。
- `MYSQL_PASSWORD`
  - 如果直接迁移 MySQL volume，需要保持一致。
  - 如果用 dump/restore，可重新设置，但要确保 compose 环境变量和 MySQL 用户密码一致。
- `REDIS_PASSWORD`
  - 如果迁移 Redis 数据或希望保持同一 Redis 密码，保持一致。
- `JWT_SECRET`
  - 保持一致可减少迁移时会话/token 问题。
  - 轮换会导致旧 access token 失效，通常可接受，但用户需要重新登录。

可以重新生成，但必须同步到 `.env`：

- `CRAWLER_INTERNAL_API_KEY`
- `AI_LANGGRAPH_WORKER_INTERNAL_API_KEY`

可以根据新环境更新：

- `SERVER_PUBLIC_IP`
- `CLOUDFLARE_TURNSTILE_SITE_KEY`
- `CLOUDFLARE_TURNSTILE_SECRET_KEY`
- `DEEPSEEK_API_KEY`
- 短信相关 `ALIYUN_DYPNS_*`

## 3. 推荐目录规范

新服务器建议使用以下目录：

```text
/opt/noval                         # 项目代码或部署包解压目录
/etc/nginx/ssl                     # Cloudflare Origin 证书和 Origin Pull CA
/etc/opt/noval/ssl/.env            # 当前线上沿用的 env 文件位置
/opt/noval-backup                  # 手工备份目录
```

创建目录：

```bash
sudo mkdir -p /opt/noval
sudo mkdir -p /etc/nginx/ssl
sudo mkdir -p /etc/opt/noval/ssl
sudo mkdir -p /opt/noval-backup
```

证书权限建议：

```bash
sudo chmod 644 /etc/nginx/ssl/cloudflare-origin-pull-ca.pem
sudo chmod 644 /etc/nginx/ssl/panch-origin.crt
sudo chmod 600 /etc/nginx/ssl/panch-origin.key
sudo chmod 600 /etc/opt/noval/ssl/.env
```

## 4. `.env` 模板

当前 compose 支持通过 `--env-file` 指定 `.env`。如果沿用当前路径，所有部署命令都使用：

```bash
docker compose --env-file /etc/opt/noval/ssl/.env ...
```

`.env` 推荐内容如下。把所有“换成...”替换为真实值：

```dotenv
MYSQL_ROOT_PASSWORD=换成你的MySQL root密码
MYSQL_USER=novel
MYSQL_PASSWORD=换成你的MySQL业务库密码

REDIS_PASSWORD=换成你的Redis密码

APP_DOMAIN=www.panch.fun
ROOT_DOMAIN=panch.fun
SERVER_PUBLIC_IP=47.238.247.169
NGINX_SSL_DIR=/etc/nginx/ssl

JWT_SECRET=换成openssl生成的随机串1
CONFIG_SECRET_MASTER_KEY=换成openssl生成的随机串2
CRAWLER_INTERNAL_API_KEY=换成openssl生成的随机串3
AI_LANGGRAPH_WORKER_INTERNAL_API_KEY=换成openssl生成的随机串4

CLOUDFLARE_TURNSTILE_ENABLED=true
CLOUDFLARE_TURNSTILE_SITE_KEY=换成Cloudflare给你的site key
CLOUDFLARE_TURNSTILE_SECRET_KEY=换成Cloudflare给你的secret key
CLOUDFLARE_TURNSTILE_EXPECT_HOSTNAME=www.panch.fun

AI_OPENAI_COMPATIBLE_API_KEY_REF=DEEPSEEK_API_KEY
AI_OPENAI_COMPATIBLE_DEFAULT_MODEL=deepseek-chat
AI_OPENAI_COMPATIBLE_BASE_URL=https://api.deepseek.com/v1
DEEPSEEK_API_KEY=换成你的DeepSeek key

DIFY_BASE_URL=https://api.dify.ai/v1
DIFY_API_KEY_REF=DIFY_API_KEY
DIFY_API_KEY=

AI_LANGGRAPH_WORKER_BASE_URL=http://langgraph-worker:8001
AI_LANGGRAPH_WORKER_TIMEOUT_MILLIS=30000

ALIYUN_DYPNS_ENABLED=false
ALIYUN_DYPNS_ACCESS_KEY_ID=
ALIYUN_DYPNS_ACCESS_KEY_SECRET=
ALIYUN_DYPNS_SIGN_NAME=
ALIYUN_DYPNS_TEMPLATE_CODE_REGISTER=
ALIYUN_DYPNS_TEMPLATE_CODE_LOGIN=
ALIYUN_DYPNS_TEMPLATE_CODE_RESET_PASSWORD=
ALIYUN_DYPNS_SCHEME_NAME=noval-web
ALIYUN_DYPNS_COUNTRY_CODE=86
ALIYUN_DYPNS_VALID_TIME_MINUTES=5
ALIYUN_DYPNS_INTERVAL_SECONDS=60

SECURITY_PASSWORD_LOGIN_PHONE_WINDOW_SECONDS=600
SECURITY_PASSWORD_LOGIN_PHONE_MAX_FAILURES=6
SECURITY_PASSWORD_LOGIN_IP_WINDOW_SECONDS=600
SECURITY_PASSWORD_LOGIN_IP_MAX_FAILURES=20
SECURITY_PASSWORD_LOGIN_PHONE_IP_WINDOW_SECONDS=600
SECURITY_PASSWORD_LOGIN_PHONE_IP_MAX_FAILURES=4
SECURITY_PASSWORD_LOGIN_COOLDOWN_SECONDS=900
```

生成随机密钥示例：

```bash
openssl rand -base64 48
```

至少生成 4 个不同值，分别填入：

- `JWT_SECRET`
- `CONFIG_SECRET_MASTER_KEY`
- `CRAWLER_INTERNAL_API_KEY`
- `AI_LANGGRAPH_WORKER_INTERNAL_API_KEY`

## 5. 旧服务器备份

下面命令假设旧服务器项目目录是 `/opt/noval`，`.env` 是 `/etc/opt/noval/ssl/.env`。

### 5.1 确认当前服务状态

```bash
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env ps
docker compose --env-file /etc/opt/noval/ssl/.env logs backend --tail=80
docker compose --env-file /etc/opt/noval/ssl/.env logs nginx --tail=80
```

### 5.2 备份 compose 渲染结果

```bash
sudo mkdir -p /opt/noval-backup/compose
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env config > /opt/noval-backup/compose/docker-compose.rendered.yml
```

### 5.3 备份证书和 env

```bash
sudo mkdir -p /opt/noval-backup/ssl /opt/noval-backup/env
sudo cp /etc/nginx/ssl/cloudflare-origin-pull-ca.pem /opt/noval-backup/ssl/
sudo cp /etc/nginx/ssl/panch-origin.crt /opt/noval-backup/ssl/
sudo cp /etc/nginx/ssl/panch-origin.key /opt/noval-backup/ssl/
sudo cp /etc/opt/noval/ssl/.env /opt/noval-backup/env/noval.env
sudo chmod 600 /opt/noval-backup/env/noval.env /opt/noval-backup/ssl/panch-origin.key
```

### 5.4 备份 MySQL

推荐用 dump 迁移，避免直接拷 Docker volume 带来的 MySQL 版本和权限问题。

```bash
sudo mkdir -p /opt/noval-backup/mysql
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --databases novel_analyzer --add-drop-database' \
  > /opt/noval-backup/mysql/novel_analyzer.sql
gzip -f /opt/noval-backup/mysql/novel_analyzer.sql
```

校验备份文件：

```bash
ls -lh /opt/noval-backup/mysql/novel_analyzer.sql.gz
gzip -t /opt/noval-backup/mysql/novel_analyzer.sql.gz
```

### 5.5 备份 Redis，可选

Redis 主要用于缓存、限流、黑名单、会话映射和活跃时间。多数迁移可以不迁 Redis，让用户重新登录即可。

如果希望尽量保留 Redis 数据：

```bash
sudo mkdir -p /opt/noval-backup/redis
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env cp redis:/data /opt/noval-backup/redis/data
```

如果这个命令失败，可以跳过 Redis 迁移。

## 6. 新服务器部署

### 6.1 放置项目代码

方式一：Git 拉取。

```bash
sudo mkdir -p /opt/noval
sudo chown -R "$USER":"$USER" /opt/noval
git clone <你的仓库地址> /opt/noval
cd /opt/noval
```

方式二：上传部署包。

```bash
sudo mkdir -p /opt/noval
sudo tar -xzf noval-deploy-*.tgz -C /opt/noval --strip-components=1
cd /opt/noval
```

### 6.2 放置 SSL 文件

```bash
sudo mkdir -p /etc/nginx/ssl
sudo cp cloudflare-origin-pull-ca.pem /etc/nginx/ssl/
sudo cp panch-origin.crt /etc/nginx/ssl/
sudo cp panch-origin.key /etc/nginx/ssl/
sudo chmod 644 /etc/nginx/ssl/cloudflare-origin-pull-ca.pem
sudo chmod 644 /etc/nginx/ssl/panch-origin.crt
sudo chmod 600 /etc/nginx/ssl/panch-origin.key
```

证书文件名必须和 `docker/nginx/default.conf.template` 一致：

```text
/etc/nginx/ssl/panch-origin.crt
/etc/nginx/ssl/panch-origin.key
/etc/nginx/ssl/cloudflare-origin-pull-ca.pem
```

### 6.3 放置 `.env`

```bash
sudo mkdir -p /etc/opt/noval/ssl
sudo cp noval.env /etc/opt/noval/ssl/.env
sudo chmod 600 /etc/opt/noval/ssl/.env
```

确认关键路径：

```bash
grep '^NGINX_SSL_DIR=' /etc/opt/noval/ssl/.env
```

如果宿主机证书放在 `/etc/nginx/ssl`，必须看到：

```text
NGINX_SSL_DIR=/etc/nginx/ssl
```

### 6.4 先启动 MySQL 和 Redis

如果需要恢复旧数据库，建议先只启动 MySQL/Redis：

```bash
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env up -d mysql redis
docker compose --env-file /etc/opt/noval/ssl/.env ps
```

等待 MySQL 完成初始化：

```bash
docker compose --env-file /etc/opt/noval/ssl/.env logs mysql --tail=80
```

### 6.5 恢复 MySQL

把旧服务器备份的 `novel_analyzer.sql.gz` 上传到新服务器，例如 `/opt/noval-backup/mysql/novel_analyzer.sql.gz`。

```bash
gunzip -c /opt/noval-backup/mysql/novel_analyzer.sql.gz \
  | docker compose --env-file /etc/opt/noval/ssl/.env exec -T mysql sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

如果是全新部署，没有旧数据，可以跳过恢复。MySQL 容器首次启动时会自动执行：

```text
backend/sql/mysql/
```

### 6.6 启动全量服务

```bash
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env config >/tmp/noval-compose.rendered.yml
docker compose --env-file /etc/opt/noval/ssl/.env up -d --build
docker compose --env-file /etc/opt/noval/ssl/.env ps
```

查看日志：

```bash
docker compose --env-file /etc/opt/noval/ssl/.env logs nginx --tail=100
docker compose --env-file /etc/opt/noval/ssl/.env logs backend --tail=100
docker compose --env-file /etc/opt/noval/ssl/.env logs crawler --tail=100
docker compose --env-file /etc/opt/noval/ssl/.env logs langgraph-worker --tail=100
```

## 7. Cloudflare 切换

### 7.1 DNS

在 Cloudflare DNS 中确认：

- `www.panch.fun` 指向新服务器 IP。
- `panch.fun` 指向新服务器 IP，或按现有方式转到 `www.panch.fun`。
- 代理状态建议开启，也就是橙色云朵。

### 7.2 SSL/TLS

Cloudflare 后台建议：

- `SSL/TLS -> Overview`：`Full (strict)`。
- `SSL/TLS -> Origin Server`：确认证书覆盖 `www.panch.fun` 和 `panch.fun`。
- `SSL/TLS -> Origin Server -> Authenticated Origin Pulls`：开启。
- `Turnstile`：确认 site key/secret key 对应 `www.panch.fun`。

### 7.3 防火墙

云厂商安全组和服务器防火墙只开放：

```text
80/tcp
443/tcp
22/tcp
```

如需临时排障，避免直接开放应用端口到公网。优先使用：

```bash
docker compose --env-file /etc/opt/noval/ssl/.env logs <service>
docker compose --env-file /etc/opt/noval/ssl/.env exec <service> sh
```

## 8. 上线验证

### 8.1 容器状态

```bash
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env ps
```

应看到这些服务处于运行状态：

- `nginx`
- `backend`
- `crawler`
- `langgraph-worker`
- `mysql`
- `redis`

### 8.2 内部健康检查

```bash
docker compose --env-file /etc/opt/noval/ssl/.env exec backend sh -c 'echo backend container alive'
docker compose --env-file /etc/opt/noval/ssl/.env logs backend --tail=80
docker compose --env-file /etc/opt/noval/ssl/.env logs crawler --tail=80
docker compose --env-file /etc/opt/noval/ssl/.env logs langgraph-worker --tail=80
```

### 8.3 外部访问检查

```bash
curl -I https://www.panch.fun
curl https://www.panch.fun/api/system/health
curl -I https://panch.fun
```

预期：

- `https://www.panch.fun` 返回 `200` 或前端页面相关响应。
- `/api/system/health` 返回 `status=UP`。
- `https://panch.fun` 跳转到 `https://www.panch.fun`。

### 8.4 浏览器功能检查

上线后手工检查：

- 登录页能打开。
- 密码登录或短信登录流程正常。
- Turnstile 开启时，未完成人机校验不能发送短信。
- `/rank` 能加载榜单目录。
- 重新抓取榜单能产生快照。
- 书籍详情能打开。
- 抓章能返回正文。
- 单书分析能启动并返回流式内容或 fallback 内容。
- 趋势页能读取当前榜单可视化数据。

## 9. 回滚方案

迁移前不要立即清理旧服务器。建议至少保留：

- 旧服务器运行 1 到 3 天。
- 旧服务器数据库备份。
- 旧服务器 `.env` 和 SSL 文件备份。

如果新服务器异常：

1. Cloudflare DNS 改回旧服务器 IP。
2. 或把 `www.panch.fun` 记录回滚到旧服务器。
3. 确认旧服务器服务仍运行：

```bash
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env ps
```

## 10. 常见问题排查

| 现象 | 优先检查 |
| --- | --- |
| nginx 容器启动失败 | `/etc/nginx/ssl` 是否存在 3 个证书文件，`NGINX_SSL_DIR` 是否正确 |
| 浏览器证书错误 | Cloudflare 是否为 Full strict，Origin 证书是否覆盖域名 |
| 直接访问域名 525/526 | Origin 证书、Cloudflare SSL 模式、Authenticated Origin Pulls |
| `/api/system/health` 不通 | `backend` 日志、nginx 反代、容器网络 |
| 后端启动失败 | `JWT_SECRET`、`CRAWLER_INTERNAL_API_KEY`、数据库/Redis 密码 |
| crawler 启动失败 | `CRAWLER_INTERNAL_API_KEY` 是否为空或过短 |
| langgraph-worker 启动失败 | `AI_LANGGRAPH_WORKER_INTERNAL_API_KEY` 是否配置 |
| 登录后立刻掉线 | `JWT_SECRET` 变化、refresh cookie secure/sameSite、Redis/MySQL session 状态 |
| 模型调用失败 | `DEEPSEEK_API_KEY`、`AI_OPENAI_COMPATIBLE_BASE_URL`、后台模型注册表 |
| 趋势 JSON 为空 | prompt config、模型输出、`analysis_result.result_json`、`/api/data/visual` |
| 短信发不出 | `CLOUDFLARE_TURNSTILE_*`、`ALIYUN_DYPNS_*`、短信风控计数 |

## 11. 迁移交付清单

迁移完成后，至少保存以下信息到安全位置：

- 新服务器 IP。
- 新服务器登录方式。
- 项目目录：`/opt/noval`。
- env 文件路径：`/etc/opt/noval/ssl/.env`。
- SSL 文件路径：`/etc/nginx/ssl`。
- MySQL 备份文件路径。
- 当前 git commit 或部署包文件名。
- Cloudflare DNS 切换时间。
- 验证结果截图或命令输出摘要。

不要保存明文密钥到普通文档；只记录密钥存放位置和轮换方式。
