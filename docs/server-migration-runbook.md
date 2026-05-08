# 服务器迁移部署 Runbook

本文档用于把当前 Noval 服务迁移到新服务器，目标是让后续迁移时可以按清单备份、复制、启动和验证。

当前线上已知信息：

- 主域名：`www.panch.fun`
- 根域名：`panch.fun`
- 旧服务器公网 IP：`47.238.247.169`
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
      -> qdrant 容器: 6333
```

公网只应该暴露：

- `80`
- `443`
- `22`，建议限制来源 IP

不要向公网暴露：

- `8080`
- `5000`
- `8001`
- `6333`
- `3306`
- `6379`

## 2. 迁移前必须准备

### 2.0 推荐迁移模式

本次从旧阿里云账号的 ECS 迁移到新阿里云账号的 ECS，推荐采用低风险模式：

```text
域名注册商不动
Cloudflare 账号和 Zone 不动
只迁移服务器、应用文件、证书、env、MySQL dump
最后在 Cloudflare DNS 中把 A 记录从旧 IP 切到新 IP
```

这样做的好处是切换和回滚都简单：

- 切换：Cloudflare DNS 里的 `www` / `@` A 记录改成新服务器 IP。
- 回滚：Cloudflare DNS 里的 `www` / `@` A 记录改回旧服务器 IP。
- 不需要在上线当天同时做域名转移、NS 变更、Cloudflare 账号迁移等高风险动作。

只有在明确要更换域名注册商或 Cloudflare 账号时，才需要额外处理域名转移、重新添加 Cloudflare Zone、重新生成 Origin 证书和 Turnstile key。

迁移时建议先定义以下变量，后续命令按实际值替换：

```bash
OLD_SERVER_IP=47.238.247.169
NEW_SERVER_IP=换成新服务器公网IP
APP_DOMAIN=www.panch.fun
ROOT_DOMAIN=panch.fun
DEPLOY_DIR=/opt/noval
ENV_FILE=/etc/opt/noval/ssl/.env
SSL_DIR=/etc/nginx/ssl
```

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
Qdrant 数据，知识库/向量检索数据需要保留时迁移
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

如果新服务器来自新的阿里云账号，还要重新确认：

- ECS 实例公网 IP 或 EIP。
- 安全组规则，只放行 `22/80/443`。
- 如果是中国大陆 ECS，确认域名备案和接入要求；如果是香港/海外 ECS，通常不需要 ICP 备案。
- 服务器系统时区、SSH 登录方式、磁盘挂载和备份策略。
- 如果使用阿里云短信服务，旧账号下的 `ALIYUN_DYPNS_*` 不一定能在新账号复用，需要重新确认或继续留空关闭短信。
- 如果旧域名解析曾经在阿里云 DNS 中配置，需要在新账号或 Cloudflare 中补回同样的记录。

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
AI_BACKEND_TOOL_TIMEOUT_MILLIS=90000

KNOWLEDGE_QDRANT_BASE_URL=http://qdrant:6333
KNOWLEDGE_QDRANT_COLLECTION=novel_knowledge_chunks
KNOWLEDGE_EMBEDDING_PROVIDER=dashscope
KNOWLEDGE_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
KNOWLEDGE_EMBEDDING_MODEL=text-embedding-v4
KNOWLEDGE_EMBEDDING_DIMENSION=1024
KNOWLEDGE_EMBEDDING_API_KEY_REF=DASHSCOPE_API_KEY
KNOWLEDGE_EMBEDDING_API_KEY=
DASHSCOPE_API_KEY=换成你的DashScope API Key
KNOWLEDGE_INDEX_MAX_CHAPTERS=10
KNOWLEDGE_INDEX_MAX_ACTIVE_JOBS=1

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
SECURITY_TRUSTED_PROXY_IPS=172.16.0.0/12,127.0.0.1,::1
```

当前新版本的 RAG/知识库能力只额外要求一个新的第三方 key：

```dotenv
DASHSCOPE_API_KEY=换成你的DashScope API Key
```

`DEEPSEEK_API_KEY` 继续用于聊天/分析模型，`DASHSCOPE_API_KEY` 用于 embedding 向量化。

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

### 5.6 备份 Qdrant，可选

Qdrant 存放知识库向量数据。若线上已经使用知识库/小说研究功能，并且希望迁移后保留已有检索数据，建议备份 Qdrant 卷；如果这些向量可以重新生成，可以跳过 Qdrant 数据迁移，迁移后重新索引。

先确认真实卷名：

```bash
docker volume ls | grep qdrant
```

把 `<project>_qdrant_data` 替换成上一步看到的真实卷名：

```bash
sudo mkdir -p /opt/noval-backup/qdrant
docker run --rm \
  -v <project>_qdrant_data:/from:ro \
  -v /opt/noval-backup/qdrant:/to \
  alpine sh -c 'cp -a /from/. /to/'
```

恢复时反向复制到新服务器的 qdrant volume 即可。恢复前确保 `qdrant` 容器未运行。

### 5.7 打包并传输备份

建议把备份打成一个归档包后通过 `scp` 或云厂商内网传输到新服务器：

```bash
cd /opt
sudo tar -czf noval-backup-$(date +%Y%m%d-%H%M%S).tgz noval-backup
```

传输后在新服务器校验大小和解压结果，不要把归档包放进公开 Web 目录。

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
sudo tar -xzf /opt/noval-deploy-20260505-current-rag-fanqie.tar.gz -C /opt
cd /opt/noval
```

当前部署包的顶层目录已经是 `noval/`，所以直接解压到 `/opt` 即可。不要再加 `--strip-components=1`，否则可能把目录结构拆错。

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

### 6.4 先启动 MySQL、Redis 和 Qdrant

如果需要恢复旧数据库，建议先只启动 MySQL/Redis/Qdrant：

```bash
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env up -d mysql redis qdrant
docker compose --env-file /etc/opt/noval/ssl/.env ps
```

等待 MySQL 完成初始化：

```bash
docker compose --env-file /etc/opt/noval/ssl/.env logs mysql --tail=80
```

如果有 Qdrant 备份，先停止 `qdrant` 后恢复数据卷，再重新启动：

```bash
docker compose --env-file /etc/opt/noval/ssl/.env stop qdrant
docker volume ls | grep qdrant
```

确认新服务器的 qdrant volume 名称后，把 `/opt/noval-backup/qdrant` 内容复制进去。

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

如果恢复的是旧版本数据库 dump，而新服务器运行的是当前新版本代码，恢复后需要确认新增 schema 已补齐。可直接补跑以下 SQL；这些脚本用于补表/补字段，避免删除旧业务数据：

```bash
cd /opt/noval

docker compose --env-file /etc/opt/noval/ssl/.env exec -T mysql \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer' \
  < backend/sql/mysql/phase5-schema.sql

docker compose --env-file /etc/opt/noval/ssl/.env exec -T mysql \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer' \
  < backend/sql/mysql/phase6-schema.sql

docker compose --env-file /etc/opt/noval/ssl/.env exec -T mysql \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" novel_analyzer' \
  < backend/sql/mysql/phase7-knowledge-schema.sql
```

如果 dump 已经来自当前新版本且这些表/字段已经存在，重复执行也应保持幂等；执行前仍建议保留 MySQL dump 备份。

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
docker compose --env-file /etc/opt/noval/ssl/.env logs qdrant --tail=100
```

## 7. Cloudflare 切换

### 7.1 切换前验证新服务器

在正式改 DNS 前，先用 `curl --resolve` 强制把域名解析到新服务器 IP，验证新机器的 nginx、证书、后端代理和健康接口。

把 `NEW_SERVER_IP` 换成新服务器公网 IP：

```bash
curl -k --resolve www.panch.fun:443:NEW_SERVER_IP https://www.panch.fun/
curl -k --resolve www.panch.fun:443:NEW_SERVER_IP https://www.panch.fun/api/system/health
curl -k --resolve panch.fun:443:NEW_SERVER_IP -I https://panch.fun/
```

如果启用了 Cloudflare Authenticated Origin Pulls，直接绕过 Cloudflare 访问源站时可能因为缺少 Cloudflare 客户端证书而失败。这时可以先以容器和日志检查为准：

```bash
cd /opt/noval
docker compose --env-file /etc/opt/noval/ssl/.env ps
docker compose --env-file /etc/opt/noval/ssl/.env logs nginx --tail=100
docker compose --env-file /etc/opt/noval/ssl/.env logs backend --tail=100
```

如果要做完全模拟 Cloudflare 的访问，需要让请求真实经过 Cloudflare，也就是进入下一步 DNS 切换。

### 7.2 DNS

在 Cloudflare DNS 中确认：

- `www.panch.fun` 指向新服务器 IP。
- `panch.fun` 指向新服务器 IP，或按现有方式转到 `www.panch.fun`。
- 代理状态建议开启，也就是橙色云朵。

如果 Cloudflare Zone 已经托管 `panch.fun`，通常只需要改这两条记录：

```text
Type  Name  Content
A     www   新服务器公网IP
A     @     新服务器公网IP
```

TTL 在橙云代理状态下由 Cloudflare 托管，切换通常很快生效。切换后用：

```bash
dig +short www.panch.fun
curl -I https://www.panch.fun
curl https://www.panch.fun/api/system/health
```

注意：开启橙云后，`dig` 看到的可能是 Cloudflare 边缘 IP，而不是新服务器真实 IP，这是正常现象。判断是否切到新服务器，应以新服务器 nginx/backend 日志是否出现请求为准。

### 7.3 SSL/TLS

Cloudflare 后台建议：

- `SSL/TLS -> Overview`：`Full (strict)`。
- `SSL/TLS -> Origin Server`：确认证书覆盖 `www.panch.fun` 和 `panch.fun`。
- `SSL/TLS -> Origin Server -> Authenticated Origin Pulls`：开启。
- `Turnstile`：确认 site key/secret key 对应 `www.panch.fun`。

如果 Cloudflare 账号和 Zone 不变，Origin 证书和 Turnstile 通常可以继续使用，只需要把原服务器上的 3 个 SSL 文件复制到新服务器同一路径：

```text
/etc/nginx/ssl/cloudflare-origin-pull-ca.pem
/etc/nginx/ssl/panch-origin.crt
/etc/nginx/ssl/panch-origin.key
```

只有更换 Cloudflare 账号或重建 Zone 时，才需要重新生成 Origin 证书、重新开启 Authenticated Origin Pulls，并更新 `.env` 里的 Turnstile key。

### 7.4 新阿里云账号与 DNS 关系

如果新服务器在新的阿里云账号下，阿里云侧需要重新配置：

- ECS 实例或 EIP，记录新的公网 IP。
- 安全组入站规则，只开放 `22/80/443`。
- 如果旧服务器做过快照、自动备份、监控告警，需要在新账号重新创建。
- 如果短信服务仍使用阿里云 DYPNS，确认新账号下的 `ALIYUN_DYPNS_*` 是否还能用；不能复用时要在 `.env` 中更新。

当前更推荐的域名切换方式是让 Cloudflare 托管 DNS。也就是说，阿里云只提供服务器公网 IP，真正的 `www.panch.fun` / `panch.fun` 解析在 Cloudflare DNS 中修改。

域名“转移到新阿里云账号”不是服务器迁移的前置条件。只要域名当前 NS 已经指向 Cloudflare，就可以先不转域名注册商，直接在 Cloudflare DNS 中把流量切到新服务器。

如果你之前是“阿里云 DNS 解析到 Cloudflare”，迁移时要重点确认两件事：

1. 域名的 NS 是否已经切到 Cloudflare。若已经切到 Cloudflare，阿里云 DNS 控制台里的旧解析通常不会再生效。
2. 若 NS 仍在阿里云，则需要在阿里云 DNS 中把指向旧服务器或旧 Cloudflare 目标的记录改为新方案；不要同时在两个 DNS 控制台维护互相冲突的 A 记录。

### 7.5 防火墙

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
- `qdrant`

### 8.2 内部健康检查

```bash
docker compose --env-file /etc/opt/noval/ssl/.env exec backend sh -c 'echo backend container alive'
docker compose --env-file /etc/opt/noval/ssl/.env logs backend --tail=80
docker compose --env-file /etc/opt/noval/ssl/.env logs crawler --tail=80
docker compose --env-file /etc/opt/noval/ssl/.env logs langgraph-worker --tail=80
docker compose --env-file /etc/opt/noval/ssl/.env logs qdrant --tail=80
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
- 如启用知识库功能，知识检索/知识聊天能返回带来源的结果。

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
| qdrant 启动失败 | qdrant volume 权限、磁盘空间、`docker compose logs qdrant` |
| 登录后立刻掉线 | `JWT_SECRET` 变化、refresh cookie secure/sameSite、Redis/MySQL session 状态 |
| 模型调用失败 | `DEEPSEEK_API_KEY`、`AI_OPENAI_COMPATIBLE_BASE_URL`、后台模型注册表 |
| 趋势 JSON 为空 | prompt config、模型输出、`analysis_result.result_json`、`/api/data/visual` |
| 知识检索/知识聊天为空 | qdrant 数据卷未迁移，或 `KNOWLEDGE_*` / `DASHSCOPE_API_KEY` 未配置 |
| 短信发不出 | `CLOUDFLARE_TURNSTILE_*`、`ALIYUN_DYPNS_*`、短信风控计数 |

## 11. 迁移交付清单

迁移完成后，至少保存以下信息到安全位置：

- 新服务器 IP。
- 新服务器登录方式。
- 项目目录：`/opt/noval`。
- env 文件路径：`/etc/opt/noval/ssl/.env`。
- SSL 文件路径：`/etc/nginx/ssl`。
- MySQL 备份文件路径。
- Redis/Qdrant 是否迁移，以及备份文件路径。
- 当前 git commit 或部署包文件名。
- Cloudflare DNS 切换时间。
- 阿里云新账号 ECS 实例 ID、安全组 ID、公网 IP 或 EIP。
- 验证结果截图或命令输出摘要。

不要保存明文密钥到普通文档；只记录密钥存放位置和轮换方式。
