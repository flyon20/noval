#!/bin/sh
set -eu

MIN_MCP_SECRET_LENGTH=32

require_secret() {
  name=$1
  minimum_length=$2
  eval "value=\${$name:-}"
  normalized=$(printf '%s' "$value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  uppercase=$(printf '%s' "$normalized" | tr '[:lower:]' '[:upper:]')
  case "$uppercase" in
    ""|CHANGE_ME*)
      echo "ERROR: required production secret is missing or still uses a placeholder: $name" >&2
      exit 1
      ;;
  esac
  if [ "${#normalized}" -lt "$minimum_length" ]; then
    echo "ERROR: production secret must be at least ${minimum_length} characters: $name" >&2
    exit 1
  fi
}

# Keep the old name as a one-release migration fallback. Compose applies the
# same mapping to Worker and FastMCP; normalize direct script invocation too.
FASTMCP_INTERNAL_API_KEY=${FASTMCP_INTERNAL_API_KEY:-${MCP_INTERNAL_API_KEY:-}}
export FASTMCP_INTERNAL_API_KEY

for name in \
  MYSQL_ROOT_PASSWORD \
  MYSQL_PASSWORD \
  REDIS_PASSWORD \
  RABBITMQ_DEFAULT_PASS \
  JWT_SECRET \
  CONFIG_SECRET_MASTER_KEY \
  CRAWLER_INTERNAL_API_KEY \
  AI_LANGGRAPH_WORKER_INTERNAL_API_KEY \
  FASTMCP_INTERNAL_API_KEY \
  MCP_CALL_SIGNING_KEY \
  MCP_BACKEND_ATTESTATION_KEY
do
  require_secret "$name" 0
done
require_secret FASTMCP_INTERNAL_API_KEY "$MIN_MCP_SECRET_LENGTH"
require_secret MCP_CALL_SIGNING_KEY "$MIN_MCP_SECRET_LENGTH"
require_secret MCP_BACKEND_ATTESTATION_KEY "$MIN_MCP_SECRET_LENGTH"

case "${NGINX_SSL_VERIFY_CLIENT:-on}" in
  on)
    ;;
  off)
    if [ "${NOVAL_CLOUDFLARE_TUNNEL_ONLY:-false}" != "true" ]; then
      echo "ERROR: NGINX_SSL_VERIFY_CLIENT=off requires NOVAL_CLOUDFLARE_TUNNEL_ONLY=true" >&2
      exit 1
    fi
    ;;
  *)
    echo "ERROR: NGINX_SSL_VERIFY_CLIENT must be on or off" >&2
    exit 1
    ;;
esac

NGINX_SSL_DIR=${NGINX_SSL_DIR:-/etc/nginx/ssl}
ORIGIN_CERT="$NGINX_SSL_DIR/panch-origin.crt"
ORIGIN_KEY="$NGINX_SSL_DIR/panch-origin.key"
CLOUDFLARE_ORIGIN_PULL_CA="$NGINX_SSL_DIR/cloudflare-origin-pull-ca.pem"

require_file() {
  path=$1
  label=$2
  if [ ! -f "$path" ] || [ ! -s "$path" ]; then
    echo "ERROR: required TLS file is missing or empty: $label ($path)" >&2
    exit 1
  fi
}

require_pem_block() {
  path=$1
  label=$2
  begin_pattern=$3
  end_pattern=$4
  if ! grep -Eq "$begin_pattern" "$path" || ! grep -Eq "$end_pattern" "$path"; then
    echo "ERROR: TLS file has an invalid PEM type: $label ($path)" >&2
    exit 1
  fi
}

require_file "$ORIGIN_CERT" "Cloudflare Origin certificate"
require_file "$ORIGIN_KEY" "Cloudflare Origin private key"
require_file "$CLOUDFLARE_ORIGIN_PULL_CA" "Cloudflare Origin Pull CA"
require_pem_block \
  "$ORIGIN_CERT" \
  "Cloudflare Origin certificate" \
  '^-----BEGIN CERTIFICATE-----[[:space:]]*$' \
  '^-----END CERTIFICATE-----[[:space:]]*$'
require_pem_block \
  "$ORIGIN_KEY" \
  "Cloudflare Origin private key" \
  '^-----BEGIN (RSA |EC |ENCRYPTED )?PRIVATE KEY-----[[:space:]]*$' \
  '^-----END (RSA |EC |ENCRYPTED )?PRIVATE KEY-----[[:space:]]*$'
require_pem_block \
  "$CLOUDFLARE_ORIGIN_PULL_CA" \
  "Cloudflare Origin Pull CA" \
  '^-----BEGIN CERTIFICATE-----[[:space:]]*$' \
  '^-----END CERTIFICATE-----[[:space:]]*$'

# The production image has nginx. Keep this conditional so the script remains
# usable in a minimal static-check image while still exercising nginx whenever
# the binary is available.
if command -v nginx >/dev/null 2>&1; then
  NGINX_CONFIG_TEMPLATE=${NGINX_CONFIG_TEMPLATE:-/etc/nginx/templates/default.conf.template}
  require_file "$NGINX_CONFIG_TEMPLATE" "Nginx production template"
  if ! command -v envsubst >/dev/null 2>&1; then
    echo "ERROR: envsubst is required to render the Nginx production template" >&2
    exit 1
  fi
  APP_DOMAIN=${APP_DOMAIN:-www.panch.fun}
  ROOT_DOMAIN=${ROOT_DOMAIN:-panch.fun}
  BACKEND_UPSTREAM_HOST=${BACKEND_UPSTREAM_HOST:-127.0.0.1}
  BACKEND_UPSTREAM_PORT=${BACKEND_UPSTREAM_PORT:-8080}
  export APP_DOMAIN ROOT_DOMAIN BACKEND_UPSTREAM_HOST BACKEND_UPSTREAM_PORT NGINX_SSL_VERIFY_CLIENT
  envsubst '${APP_DOMAIN} ${ROOT_DOMAIN} ${BACKEND_UPSTREAM_HOST} ${BACKEND_UPSTREAM_PORT} ${NGINX_SSL_VERIFY_CLIENT}' \
    < "$NGINX_CONFIG_TEMPLATE" \
    > /etc/nginx/conf.d/default.conf
  nginx -t
fi

echo "Noval production secret and TLS preflight passed."
