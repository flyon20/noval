package com.novelanalyzer.modules.security.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.config.AiProperties;
import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FastMcpSupervisorAttestationServiceTest {

    private static final String KEY = "mcp-backend-attestation-test-key-1234567890";

    @Test
    void acceptsSignedScopedForceRefreshAndConsumesNonce() throws Exception {
        Fixture fixture = fixture(true);
        CrawlerRankRequest request = request();
        sign(request, KEY);

        assertThatCode(() -> fixture.service().assertAuthorizedForceRefresh(request))
            .doesNotThrowAnyException();
    }

    @Test
    void normalizesConfiguredSigningKeyBeforeHmacVerification() throws Exception {
        Fixture fixture = fixture(true, "  " + KEY + "\r\n");
        CrawlerRankRequest request = request();
        sign(request, KEY);

        assertThatCode(() -> fixture.service().assertAuthorizedForceRefresh(request))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsForgedPayloadAndReplayedNonce() throws Exception {
        Fixture fixture = fixture(true);
        CrawlerRankRequest forged = request();
        sign(forged, KEY);
        forged.setBoardCode("forged-board");

        assertThatThrownBy(() -> fixture.service().assertAuthorizedForceRefresh(forged))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invalid FastMCP supervisor attestation");

        Fixture replayFixture = fixture(false);
        CrawlerRankRequest replay = request();
        sign(replay, KEY);
        assertThatThrownBy(() -> replayFixture.service().assertAuthorizedForceRefresh(replay))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("replayed FastMCP supervisor attestation");
    }

    @Test
    void rejectsForceRefreshWithoutAttestationButAllowsAuto() {
        Fixture fixture = fixture(true);
        CrawlerRankRequest force = request();

        assertThatThrownBy(() -> fixture.service().assertAuthorizedForceRefresh(force))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("attestation is required");

        force.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_AUTO);
        assertThatCode(() -> fixture.service().assertAuthorizedForceRefresh(force))
            .doesNotThrowAnyException();
    }

    @Test
    void failsClosedWhenAttestationKeyIsMissing() throws Exception {
        AiProperties properties = new AiProperties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FastMcpSupervisorAttestationService service = new FastMcpSupervisorAttestationService(properties, redis);
        CrawlerRankRequest request = request();
        sign(request, KEY);

        assertThatThrownBy(() -> service.assertAuthorizedForceRefresh(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("attestation is not configured");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsClosedWhenNonceStoreIsUnavailable() throws Exception {
        AiProperties properties = new AiProperties();
        properties.setMcpBackendAttestationKey(KEY);
        properties.setMcpBackendAttestationMaxAgeSeconds(60);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(),
            org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class)))
            .thenThrow(new IllegalStateException("redis unavailable"));
        FastMcpSupervisorAttestationService service = new FastMcpSupervisorAttestationService(properties, redis);
        CrawlerRankRequest request = request();
        sign(request, KEY);

        assertThatThrownBy(() -> service.assertAuthorizedForceRefresh(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("nonce store is unavailable");
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(boolean nonceAccepted) {
        return fixture(nonceAccepted, KEY);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(boolean nonceAccepted, String configuredKey) {
        AiProperties properties = new AiProperties();
        properties.setMcpBackendAttestationKey(configuredKey);
        properties.setMcpBackendAttestationMaxAgeSeconds(60);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(),
            org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class)))
            .thenReturn(nonceAccepted);
        return new Fixture(new FastMcpSupervisorAttestationService(properties, redis));
    }

    private CrawlerRankRequest request() {
        CrawlerRankRequest request = new CrawlerRankRequest();
        request.setUserId(7L);
        request.setProjectId(9L);
        request.setPlatform("fanqie");
        request.setChannelCode("male-new");
        request.setBoardCode("urban-brain");
        request.setCategory("urban-brain");
        request.setRefreshMode(CrawlerRankRequest.REFRESH_MODE_FORCE);
        request.setForceReason("refresh stale board");
        request.setRankFetchCount(30);
        request.setIdempotencyKey("rank-refresh-once");
        return request;
    }

    private void sign(CrawlerRankRequest request, String key) throws Exception {
        CrawlerRankRequest.SupervisorAttestation attestation = new CrawlerRankRequest.SupervisorAttestation();
        attestation.setTool("rank.refresh");
        attestation.setRoute("market_scan");
        attestation.setPermission("rank.refresh");
        attestation.setUserId(String.valueOf(request.getUserId()));
        attestation.setProjectId(String.valueOf(request.getProjectId()));
        attestation.setTimestamp(Instant.now().getEpochSecond());
        attestation.setNonce("0123456789abcdef0123456789abcdef");
        String canonical = canonical(request, attestation);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder signature = new StringBuilder();
        for (byte item : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) {
            signature.append(String.format("%02x", item));
        }
        attestation.setSignature(signature.toString());
        request.setSupervisorAttestation(attestation);
    }

    private String canonical(CrawlerRankRequest request,
                             CrawlerRankRequest.SupervisorAttestation attestation) {
        StringBuilder value = new StringBuilder();
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

    private record Fixture(FastMcpSupervisorAttestationService service) {
    }
}
