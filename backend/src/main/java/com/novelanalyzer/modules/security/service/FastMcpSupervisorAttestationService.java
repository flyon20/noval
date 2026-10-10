package com.novelanalyzer.modules.security.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class FastMcpSupervisorAttestationService {

    private static final String EXPECTED_TOOL = "rank.refresh";
    private static final Set<String> ALLOWED_ROUTES = Set.of("market_scan", "mixed_creation_research");
    private static final Set<String> ALLOWED_PERMISSIONS = Set.of("rank.refresh", "tools:write", "admin:*");

    private final AiProperties aiProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public FastMcpSupervisorAttestationService(AiProperties aiProperties,
                                               StringRedisTemplate stringRedisTemplate) {
        this.aiProperties = aiProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void assertAuthorizedForceRefresh(CrawlerRankRequest request) {
        if (request == null
            || !CrawlerRankRequest.REFRESH_MODE_FORCE.equalsIgnoreCase(request.getRefreshMode())) {
            return;
        }
        CrawlerRankRequest.SupervisorAttestation attestation = request.getSupervisorAttestation();
        if (attestation == null) {
            throw forbidden("signed FastMCP supervisor attestation is required");
        }
        String signingKey = trimToNull(aiProperties.getMcpBackendAttestationKey());
        if (!configuredSecret(signingKey)) {
            throw new BusinessException(
                ResultCode.SERVICE_UNAVAILABLE,
                "FastMCP backend attestation is not configured"
            );
        }
        if (!EXPECTED_TOOL.equals(attestation.getTool())
            || !ALLOWED_ROUTES.contains(attestation.getRoute())
            || !ALLOWED_PERMISSIONS.contains(attestation.getPermission())
            || !stringValue(request.getUserId()).equals(attestation.getUserId())
            || !stringValue(request.getProjectId()).equals(attestation.getProjectId())) {
            throw forbidden("FastMCP supervisor attestation scope mismatch");
        }
        long timestamp = attestation.getTimestamp() == null ? 0L : attestation.getTimestamp();
        long now = Instant.now().getEpochSecond();
        int maxAge = Math.max(1, aiProperties.getMcpBackendAttestationMaxAgeSeconds());
        if (Math.abs(now - timestamp) > maxAge) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "expired FastMCP supervisor attestation");
        }
        String nonce = trimToNull(attestation.getNonce());
        String signature = trimToNull(attestation.getSignature());
        if (nonce == null || nonce.length() < 16 || nonce.length() > 128
            || signature == null || !signature.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "invalid FastMCP supervisor attestation");
        }
        byte[] expected = hmac(signingKey, canonical(request, attestation));
        byte[] actual = hex(signature);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "invalid FastMCP supervisor attestation");
        }
        consumeNonce(nonce, maxAge);
    }

    private void consumeNonce(String nonce, int maxAgeSeconds) {
        String key = "mcp:backend-attestation:nonce:" + sha256(nonce);
        try {
            Boolean consumed = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                "1",
                Math.max(1, maxAgeSeconds * 2L),
                TimeUnit.SECONDS
            );
            if (!Boolean.TRUE.equals(consumed)) {
                throw new BusinessException(ResultCode.UNAUTHORIZED, "replayed FastMCP supervisor attestation");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(
                ResultCode.SERVICE_UNAVAILABLE,
                "FastMCP supervisor nonce store is unavailable"
            );
        }
    }

    private String canonical(CrawlerRankRequest request,
                             CrawlerRankRequest.SupervisorAttestation attestation) {
        StringBuilder value = new StringBuilder(512);
        append(value, "tool", attestation.getTool());
        append(value, "route", attestation.getRoute());
        append(value, "permission", attestation.getPermission());
        append(value, "userId", attestation.getUserId());
        append(value, "projectId", attestation.getProjectId());
        append(value, "platform", request.getPlatform());
        append(value, "channelCode", request.getChannelCode());
        append(value, "boardCode", request.getBoardCode());
        append(value, "category", request.getCategory());
        append(value, "refreshMode", request.getRefreshMode());
        append(value, "forceReason", request.getForceReason());
        append(value, "rankFetchCount", request.getRankFetchCount());
        append(value, "idempotencyKey", request.getIdempotencyKey());
        append(value, "timestamp", attestation.getTimestamp());
        append(value, "nonce", attestation.getNonce());
        return value.toString();
    }

    private void append(StringBuilder target, String name, Object rawValue) {
        target.append(name.getBytes(StandardCharsets.UTF_8).length).append(':').append(name).append('=');
        if (rawValue == null) {
            target.append("-1:");
        } else {
            String text = String.valueOf(rawValue);
            target.append(text.getBytes(StandardCharsets.UTF_8).length).append(':').append(text);
        }
        target.append(';');
    }

    private byte[] hmac(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    private byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private boolean configuredSecret(String value) {
        String normalized = trimToNull(value);
        return normalized != null
            && normalized.length() >= 32
            && !normalized.toUpperCase().startsWith("CHANGE_ME");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private BusinessException forbidden(String message) {
        return new BusinessException(ResultCode.FORBIDDEN, message);
    }
}
