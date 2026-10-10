package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSecretPreflightTest {

    private static final Path REPOSITORY_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path COMPOSE = REPOSITORY_ROOT.resolve("docker-compose.yml");
    private static final Path ENV_EXAMPLE = REPOSITORY_ROOT.resolve(".env.example");
    private static final Path PREFLIGHT = REPOSITORY_ROOT.resolve("docker/validate-production-secrets.sh");
    private static final List<String> REQUIRED_SECRETS = List.of(
        "MYSQL_ROOT_PASSWORD",
        "MYSQL_PASSWORD",
        "REDIS_PASSWORD",
        "RABBITMQ_DEFAULT_PASS",
        "JWT_SECRET",
        "CONFIG_SECRET_MASTER_KEY",
        "CRAWLER_INTERNAL_API_KEY",
        "AI_LANGGRAPH_WORKER_INTERNAL_API_KEY",
        "FASTMCP_INTERNAL_API_KEY",
        "MCP_CALL_SIGNING_KEY",
        "MCP_BACKEND_ATTESTATION_KEY"
    );
    private static final List<String> GUARDED_SERVICES = List.of(
        "mysql",
        "schema-migrate",
        "redis",
        "rabbitmq",
        "qdrant",
        "crawler",
        "backend",
        "langgraph-worker",
        "fastmcp-tools",
        "nginx"
    );
    private static final List<String> RESIDENT_SERVICES = List.of(
        "nginx",
        "backend",
        "crawler",
        "langgraph-worker",
        "fastmcp-tools",
        "mysql",
        "redis",
        "rabbitmq",
        "qdrant"
    );

    @Test
    void rejectsMissingAndPlaceholderProductionSecrets() throws Exception {
        String script = Files.readString(PREFLIGHT, StandardCharsets.UTF_8);
        String compose = Files.readString(COMPOSE, StandardCharsets.UTF_8);
        String envExample = Files.readString(ENV_EXAMPLE, StandardCharsets.UTF_8);
        String canonicalFastMcpMapping =
            "FASTMCP_INTERNAL_API_KEY: ${FASTMCP_INTERNAL_API_KEY:-${MCP_INTERNAL_API_KEY:-}}";

        assertThat(script)
            .contains("set -eu")
            .contains("MIN_MCP_SECRET_LENGTH=32")
            .contains("require_secret FASTMCP_INTERNAL_API_KEY \"$MIN_MCP_SECRET_LENGTH\"")
            .contains("require_secret MCP_CALL_SIGNING_KEY \"$MIN_MCP_SECRET_LENGTH\"")
            .contains("require_secret MCP_BACKEND_ATTESTATION_KEY \"$MIN_MCP_SECRET_LENGTH\"")
            .contains("NGINX_SSL_VERIFY_CLIENT=off requires NOVAL_CLOUDFLARE_TUNNEL_ONLY=true")
            .contains("NGINX_SSL_VERIFY_CLIENT must be on or off")
            .contains("exit 1");
        assertThat(serviceBlock(compose, "config-preflight"))
            .contains(canonicalFastMcpMapping)
            .contains("NGINX_SSL_VERIFY_CLIENT: ${NGINX_SSL_VERIFY_CLIENT:-on}")
            .contains("NOVAL_CLOUDFLARE_TUNNEL_ONLY: ${NOVAL_CLOUDFLARE_TUNNEL_ONLY:-false}");
        assertThat(serviceBlock(compose, "langgraph-worker")).contains(canonicalFastMcpMapping);
        assertThat(serviceBlock(compose, "fastmcp-tools")).contains(canonicalFastMcpMapping);
        assertThat(envExample)
            .contains("FASTMCP_INTERNAL_API_KEY=CHANGE_ME_")
            .contains("NOVAL_CLOUDFLARE_TUNNEL_ONLY=false")
            .doesNotContain("\nMCP_INTERNAL_API_KEY=");
        for (String secret : REQUIRED_SECRETS) {
            assertThat(script).contains(secret);
            if (!"FASTMCP_INTERNAL_API_KEY".equals(secret)) {
                assertThat(compose).contains(secret + ": ${" + secret + ":-}");
            }
        }
    }

    @Test
    void validatesCloudflareTlsFilesAndNginxConfiguration() throws Exception {
        String script = Files.readString(PREFLIGHT, StandardCharsets.UTF_8);
        String compose = Files.readString(COMPOSE, StandardCharsets.UTF_8);
        String preflight = serviceBlock(compose, "config-preflight");

        assertThat(script)
            .contains("panch-origin.crt")
            .contains("panch-origin.key")
            .contains("cloudflare-origin-pull-ca.pem")
            .contains("BEGIN CERTIFICATE")
            .contains("BEGIN (RSA |EC |ENCRYPTED )?PRIVATE KEY")
            .contains("command -v nginx")
            .contains("nginx -t");
        String nginxTemplate = Files.readString(
            REPOSITORY_ROOT.resolve("docker/nginx/default.conf.template"),
            StandardCharsets.UTF_8
        );
        assertThat(nginxTemplate.split(
            Pattern.quote("ssl_verify_client ${NGINX_SSL_VERIFY_CLIENT};"),
            -1
        ).length - 1)
            .isEqualTo(2);
        assertThat(nginxTemplate)
            .contains(
                "resolver 127.0.0.11 valid=10s ipv6=off;",
                "zone noval_backend 64k;",
                "server ${BACKEND_UPSTREAM_HOST}:${BACKEND_UPSTREAM_PORT} resolve;",
                "client_max_body_size 20m;",
                "location ~ ^/api/knowledge/projects/[0-9]+/works/[0-9]+/document-batches$ {",
                "client_max_body_size 100m;",
                "listen 8080;",
                "listen [::]:8080;",
                "listen 443 ssl;",
                "listen [::]:443 ssl;"
            )
            .doesNotContain("ssl http2;");
        assertThat(nginxTemplate.split(Pattern.quote("http2 on;"), -1).length - 1)
            .isEqualTo(2);
        assertThat(preflight)
            .contains("image: nginx:1.27-alpine")
            .contains("${NGINX_SSL_DIR:-/etc/nginx/ssl}:/etc/nginx/ssl:ro")
            .contains("./docker/nginx/default.conf.template:/etc/nginx/templates/default.conf.template:ro");
    }

    @Test
    void blocksProductionServicesUntilSecretPreflightSucceeds() throws Exception {
        String compose = Files.readString(COMPOSE, StandardCharsets.UTF_8);

        assertThat(serviceBlock(compose, "config-preflight"))
            .contains("validate-production-secrets.sh")
            .contains("restart: \"no\"");
        for (String service : GUARDED_SERVICES) {
            assertThat(serviceBlock(compose, service))
                .contains("config-preflight:")
                .contains("condition: service_completed_successfully");
        }
    }

    @Test
    void keepsResidentComposeMemoryHardLimitsWithinJ3160Envelope() throws Exception {
        String compose = Files.readString(COMPOSE, StandardCharsets.UTF_8);

        int totalMib = 0;
        for (String service : RESIDENT_SERVICES) {
            String block = serviceBlock(compose, service);
            int memoryMib = defaultMemoryMib(block, "mem_limit");
            int swapMib = defaultMemoryMib(block, "memswap_limit");
            assertThat(memoryMib).as("%s mem_limit", service).isPositive();
            assertThat(swapMib).as("%s memswap_limit", service).isEqualTo(memoryMib);
            totalMib += memoryMib;
        }

        assertThat(totalMib).isEqualTo(5_632);
        assertThat(defaultMemoryMib(serviceBlock(compose, "config-preflight"), "mem_limit"))
            .isEqualTo(64);
    }

    private String serviceBlock(String compose, String service) {
        Pattern pattern = Pattern.compile(
            "(?ms)^  " + Pattern.quote(service) + ":\\R(.*?)(?=^  [a-zA-Z0-9_-]+:|^volumes:|\\z)"
        );
        Matcher matcher = pattern.matcher(compose);
        assertThat(matcher.find()).as("compose service %s", service).isTrue();
        return matcher.group();
    }

    private int defaultMemoryMib(String serviceBlock, String property) {
        Pattern pattern = Pattern.compile("(?m)^    " + Pattern.quote(property) + ":\\s*([^\\r\\n]+)$");
        Matcher matcher = pattern.matcher(serviceBlock);
        assertThat(matcher.find()).as("compose property %s", property).isTrue();
        String value = matcher.group(1).trim();
        Matcher defaultMatcher = Pattern.compile(":-([^}]+)}").matcher(value);
        if (defaultMatcher.find()) {
            value = defaultMatcher.group(1).trim();
        }
        Matcher unitMatcher = Pattern.compile("(?i)^(\\d+)([mg])$").matcher(value);
        assertThat(unitMatcher.matches()).as("memory value %s", value).isTrue();
        int amount = Integer.parseInt(unitMatcher.group(1));
        return "g".equalsIgnoreCase(unitMatcher.group(2)) ? amount * 1024 : amount;
    }

}
