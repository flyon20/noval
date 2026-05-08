#!/usr/bin/env bash
set -euo pipefail

DOMAIN="${DOMAIN:-www.panch.fun}"
WWW_DOMAIN="${WWW_DOMAIN:-panch.fun}"
BACKEND_HOST="${BACKEND_HOST:-127.0.0.1}"
BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_DIST_DIR="${FRONTEND_DIST_DIR:-/srv/noval/frontend/dist}"
NGINX_CONF_PATH="${NGINX_CONF_PATH:-/etc/nginx/conf.d/www.panch.fun.conf}"
SSL_DIR="${SSL_DIR:-/etc/nginx/ssl}"
ORIGIN_CERT_PATH="${ORIGIN_CERT_PATH:-$SSL_DIR/panch-origin.crt}"
ORIGIN_KEY_PATH="${ORIGIN_KEY_PATH:-$SSL_DIR/panch-origin.key}"
ORIGIN_PULL_CA_PATH="${ORIGIN_PULL_CA_PATH:-$SSL_DIR/cloudflare-origin-pull-ca.pem}"

echo "[1/6] Checking required files and directories..."
if [[ ! -f "$ORIGIN_CERT_PATH" ]]; then
  echo "Missing origin certificate: $ORIGIN_CERT_PATH" >&2
  exit 1
fi
if [[ ! -f "$ORIGIN_KEY_PATH" ]]; then
  echo "Missing origin private key: $ORIGIN_KEY_PATH" >&2
  exit 1
fi
if [[ ! -d "$FRONTEND_DIST_DIR" ]]; then
  echo "Missing frontend dist directory: $FRONTEND_DIST_DIR" >&2
  echo "Set FRONTEND_DIST_DIR=/your/path/dist before running this script." >&2
  exit 1
fi

mkdir -p "$SSL_DIR"

echo "[2/6] Downloading Cloudflare Origin Pull CA if needed..."
if [[ ! -f "$ORIGIN_PULL_CA_PATH" ]]; then
  curl -fsSL https://developers.cloudflare.com/ssl/static/authenticated_origin_pull_ca.pem -o "$ORIGIN_PULL_CA_PATH"
fi
chmod 644 "$ORIGIN_PULL_CA_PATH"
chmod 644 "$ORIGIN_CERT_PATH"
chmod 600 "$ORIGIN_KEY_PATH"

echo "[3/6] Writing Nginx site config to $NGINX_CONF_PATH ..."
cat > "$NGINX_CONF_PATH" <<EOF
upstream noval_backend {
    server ${BACKEND_HOST}:${BACKEND_PORT};
    keepalive 32;
}

server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN} ${WWW_DOMAIN};

    return 301 https://${DOMAIN}\$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ${WWW_DOMAIN};

    ssl_certificate     ${ORIGIN_CERT_PATH};
    ssl_certificate_key ${ORIGIN_KEY_PATH};

    return 301 https://${DOMAIN}\$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ${DOMAIN};

    ssl_certificate     ${ORIGIN_CERT_PATH};
    ssl_certificate_key ${ORIGIN_KEY_PATH};

    ssl_client_certificate ${ORIGIN_PULL_CA_PATH};
    ssl_verify_client on;

    real_ip_header CF-Connecting-IP;
    real_ip_recursive on;

    set_real_ip_from 173.245.48.0/20;
    set_real_ip_from 103.21.244.0/22;
    set_real_ip_from 103.22.200.0/22;
    set_real_ip_from 103.31.4.0/22;
    set_real_ip_from 141.101.64.0/18;
    set_real_ip_from 108.162.192.0/18;
    set_real_ip_from 190.93.240.0/20;
    set_real_ip_from 188.114.96.0/20;
    set_real_ip_from 197.234.240.0/22;
    set_real_ip_from 198.41.128.0/17;
    set_real_ip_from 162.158.0.0/15;
    set_real_ip_from 104.16.0.0/13;
    set_real_ip_from 104.24.0.0/14;
    set_real_ip_from 172.64.0.0/13;
    set_real_ip_from 131.0.72.0/22;
    set_real_ip_from 2400:cb00::/32;
    set_real_ip_from 2606:4700::/32;
    set_real_ip_from 2803:f800::/32;
    set_real_ip_from 2405:b500::/32;
    set_real_ip_from 2405:8100::/32;
    set_real_ip_from 2a06:98c0::/29;
    set_real_ip_from 2c0f:f248::/32;

    charset utf-8;
    client_max_body_size 20m;
    server_tokens off;

    root ${FRONTEND_DIST_DIR};
    index index.html;

    add_header X-Frame-Options SAMEORIGIN always;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;

    location /assets/ {
        try_files \$uri =404;
        access_log off;
        expires 30d;
        add_header Cache-Control "public, max-age=2592000, immutable";
    }

    location ~ ^/api/analysis/.+/stream$ {
        proxy_pass http://noval_backend;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$realip_remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Connection "";

        proxy_buffering off;
        proxy_request_buffering off;
        proxy_cache off;
        gzip off;

        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location /api/ {
        proxy_pass http://noval_backend;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$realip_remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Connection "";

        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }
}
EOF

echo "[4/6] Validating Nginx config..."
nginx -t

echo "[5/6] Reloading Nginx..."
if command -v systemctl >/dev/null 2>&1; then
  systemctl reload nginx
else
  nginx -s reload
fi

echo "[6/6] Done."
echo "Nginx config installed at: $NGINX_CONF_PATH"
echo "Frontend dist directory used: $FRONTEND_DIST_DIR"
echo "Backend upstream: ${BACKEND_HOST}:${BACKEND_PORT}"
