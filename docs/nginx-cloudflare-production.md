# Nginx + Cloudflare 上线模板

## Docker 部署优先

如果你使用 Docker Compose 部署，优先使用仓库中的容器化方案：

- `docker-compose.yml`
- `docker/nginx/Dockerfile`
- `docker/nginx/default.conf.template`

服务器迁移和全量部署步骤优先看 `docs/server-migration-runbook.md`；本文只保留 Nginx + Cloudflare 专项说明。

推荐形态：

- `nginx` 容器暴露 `80/443`
- `backend` 仅容器内开放 `8080`
- `mysql` 仅容器内开放 `3306`
- `redis` 仅容器内开放 `6379`
- `crawler` / `langgraph-worker` 仅容器网络访问

这样 Cloudflare 只回源到 `nginx`，不会直接碰到应用和数据库。

## 1. 目标

- `www.panch.fun` 作为主站，`panch.fun` 301 跳转到 `www.panch.fun`
- Nginx 直接托管前端静态文件
- `/api` 反代到本机 Spring Boot `127.0.0.1:8080`
- Cloudflare 回源启用 `Full (strict)` + `Authenticated Origin Pulls`
- `crawler` / `langgraph-worker` / `redis` 不暴露公网

对应配置模板：

- `tools/nginx/panch.fun.cloudflare.conf.example`

## 2. 证书文件准备

你需要在服务器上准备这 3 个文件：

1. Origin CA 证书
   - `/etc/nginx/ssl/panch-origin.crt`
2. Origin CA 私钥
   - `/etc/nginx/ssl/panch-origin.key`
3. Cloudflare Origin Pull CA
   - `/etc/nginx/ssl/cloudflare-origin-pull-ca.pem`

下载 Origin Pull CA：

```bash
sudo mkdir -p /etc/nginx/ssl
sudo curl -o /etc/nginx/ssl/cloudflare-origin-pull-ca.pem https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem
sudo chmod 644 /etc/nginx/ssl/cloudflare-origin-pull-ca.pem
```

Origin CA 证书与私钥来自 Cloudflare Dashboard:

- `SSL/TLS`
- `Origin Server`
- `Create Certificate`

## 3. 目录建议

推荐：

- 前端静态文件：`/srv/noval/frontend/dist`
- Nginx 配置：`/etc/nginx/conf.d/www.panch.fun.conf`
- SSL 文件：`/etc/nginx/ssl`

## 4. 服务监听建议

生产上建议：

- backend 只监听 `127.0.0.1:8080`
- crawler 只监听 `127.0.0.1:5000`
- langgraph-worker 只监听 `127.0.0.1:8001`
- redis 只监听 `127.0.0.1:6379`

公网只放：

- `80`
- `443`
- `22`（尽量限制来源 IP）

## 5. Cloudflare 后台对应项

1. `SSL/TLS -> Overview`
   - 设为 `Full (strict)`
2. `SSL/TLS -> Origin Server`
   - 开启 `Authenticated Origin Pulls`
3. `Turnstile`
   - 创建 widget
4. `WAF`
   - 配短信发送与登录的规则

## 6. Nginx 配置安装

### 6.1 Docker Compose 方式

1. 将证书文件放到宿主机目录，例如：

- `/etc/nginx/ssl/panch-origin.crt`
- `/etc/nginx/ssl/panch-origin.key`
- `/etc/nginx/ssl/cloudflare-origin-pull-ca.pem`

2. 在项目根目录准备 `.env`

3. 启动：

```bash
docker compose up -d --build
```

4. 检查：

```bash
docker compose ps
docker compose logs nginx --tail=100
```

### 6.2 宿主机 Nginx 方式

1. 将模板复制到服务器：

```bash
sudo cp /path/to/panch.fun.cloudflare.conf.example /etc/nginx/conf.d/www.panch.fun.conf
```

2. 确认以下值已替换正确：

- `server_name`
- `root /srv/noval/frontend/dist`
- `ssl_certificate`
- `ssl_certificate_key`
- `ssl_client_certificate`

3. 校验配置：

```bash
sudo nginx -t
```

4. 重载：

```bash
sudo systemctl reload nginx
```

## 7. 应用环境变量

后端最少补这些：

```bash
export JWT_SECRET='replace-with-strong-secret'
export CONFIG_SECRET_MASTER_KEY='replace-with-strong-config-secret'
export CLOUDFLARE_TURNSTILE_ENABLED='true'
export CLOUDFLARE_TURNSTILE_SITE_KEY='replace-with-site-key'
export CLOUDFLARE_TURNSTILE_SECRET_KEY='replace-with-secret-key'
export CLOUDFLARE_TURNSTILE_EXPECT_HOSTNAME='www.panch.fun'
```

## 8. 上线后检查

1. `https://www.panch.fun` 可访问
2. `https://panch.fun` 正确跳转到主域名
3. 登录页可加载
4. `/api/system/health` 正常
5. 短信发送在未完成人机校验时被拦截
6. 直接访问源站 IP 不能正常访问业务站点

## 9. 安全提醒

- 不要把 Origin 私钥、Turnstile secret key、JWT secret 提交到仓库
- 如果敏感值已在聊天或不安全渠道暴露，建议上线后立即轮换
