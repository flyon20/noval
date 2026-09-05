package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class KnowledgeAiCacheContinuityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeAiCacheContinuityService.class);
    // v4 adds prompt-cache strategy and prefix-sensitive request settings;
    // never mix older projections with the stricter continuity classifier.
    private static final String KEY_PREFIX = "noval:ai:cache-continuity:v4:";
    private static final Duration STATE_TTL = Duration.ofHours(6);
    private static final int MAX_CHAIN_ITEMS = 64;
    private static final DefaultRedisScript<Long> VERSIONED_HASH_SET_SCRIPT = new DefaultRedisScript<>(
        "local current = redis.call('hget', KEYS[1], 'eventId'); "
            + "if current and tonumber(current) >= tonumber(ARGV[1]) then return 0 end; "
            + "redis.call('hset', KEYS[1], 'eventId', ARGV[1], 'payload', ARGV[2]); "
            + "redis.call('expire', KEYS[1], ARGV[3]); return 1",
        Long.class
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;

    @Autowired
    public KnowledgeAiCacheContinuityService(JdbcTemplate jdbcTemplate,
                                             ObjectMapper objectMapper,
                                             ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                             @Value("${app.ai.cache-continuity-enabled:true}") boolean enabled) {
        this(jdbcTemplate, objectMapper, redisTemplateProvider.getIfAvailable(), enabled);
    }

    KnowledgeAiCacheContinuityService(JdbcTemplate jdbcTemplate,
                                      ObjectMapper objectMapper,
                                      StringRedisTemplate redisTemplate) {
        this(jdbcTemplate, objectMapper, redisTemplate, true);
    }

    private KnowledgeAiCacheContinuityService(JdbcTemplate jdbcTemplate,
                                              ObjectMapper objectMapper,
                                              StringRedisTemplate redisTemplate,
                                              boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean project(Long userId, String runId, Long eventId, Object checkpointPayload) {
        if (!enabled || redisTemplate == null || userId == null || userId <= 0 || eventId == null || eventId <= 0) {
            return false;
        }
        if (!(checkpointPayload instanceof Map<?, ?> checkpoint)) {
            return false;
        }
        Map<String, Object> snapshot = sanitizeSnapshot(checkpoint.get("cacheContinuity"));
        if (snapshot.isEmpty()) {
            return false;
        }
        try {
            List<String> conversationIds = jdbcTemplate.query(
                "select conversation_id from ai_chat_run "
                    + "where run_id = ? and user_id = ? and deleted = 0",
                (resultSet, rowNum) -> resultSet.getString("conversation_id"),
                runId,
                userId
            );
            if (conversationIds.isEmpty() || conversationIds.get(0) == null) {
                return false;
            }
            String key = scopeKey(userId, conversationIds.get(0), snapshot);
            Map<String, Object> previous = readPreviousState(key);
            Continuity continuity = classify(previous, snapshot);
            Map<String, Object> state = storedState(
                eventId,
                checkpoint,
                snapshot,
                continuity
            );
            String stateJson = objectMapper.writeValueAsString(state);
            Long updated = redisTemplate.execute(
                VERSIONED_HASH_SET_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(eventId),
                stateJson,
                String.valueOf(STATE_TTL.toSeconds())
            );
            return Long.valueOf(1L).equals(updated);
        } catch (Exception ex) {
            LOGGER.debug("AI cache continuity projection skipped: eventId={}, reason={}", eventId, ex.getMessage());
            return false;
        }
    }

    static Continuity classify(Map<String, Object> previous, Map<String, Object> current) {
        if (previous == null || previous.isEmpty()) {
            return new Continuity(false, "no_previous", null);
        }
        Long previousEventId = nullableLong(previous.get("eventId"));
        if (!Objects.equals(text(previous.get("provider")), text(current.get("provider")))) {
            return new Continuity(false, "provider_changed", previousEventId);
        }
        if (!Objects.equals(text(previous.get("wireApi")), text(current.get("wireApi")))) {
            return new Continuity(false, "wire_api_changed", previousEventId);
        }
        if (!Objects.equals(text(previous.get("model")), text(current.get("model")))) {
            return new Continuity(false, "model_changed", previousEventId);
        }
        if (!Objects.equals(
            text(previous.get("requestFamily")),
            text(current.get("requestFamily"))
        )) {
            return new Continuity(false, "request_family_changed", previousEventId);
        }
        if (!Objects.equals(
            text(previous.get("routeFingerprint")),
            text(current.get("routeFingerprint"))
        )) {
            return new Continuity(false, "provider_route_changed", previousEventId);
        }
        if (!Objects.equals(
            text(previous.get("affinityFingerprint")),
            text(current.get("affinityFingerprint"))
        )) {
            return new Continuity(false, "cache_affinity_changed", previousEventId);
        }
        if (!Objects.equals(
            text(previous.get("cacheIdentityMode")),
            text(current.get("cacheIdentityMode"))
        )) {
            return new Continuity(false, "cache_identity_mode_changed", previousEventId);
        }
        if (!Objects.equals(
            text(previous.get("promptCacheStrategy")),
            text(current.get("promptCacheStrategy"))
        )) {
            return new Continuity(false, "prompt_cache_strategy_changed", previousEventId);
        }
        if (!Objects.equals(
            text(previous.get("requestSettingsFingerprint")),
            text(current.get("requestSettingsFingerprint"))
        )) {
            return new Continuity(false, "request_settings_changed", previousEventId);
        }
        if (!Objects.equals(
            text(previous.get("stablePrefixFingerprint")),
            text(current.get("stablePrefixFingerprint"))
        )) {
            return new Continuity(false, "stable_prefix_changed", previousEventId);
        }
        if (!Objects.equals(
            text(previous.get("toolsFingerprint")),
            text(current.get("toolsFingerprint"))
        )) {
            return new Continuity(false, "tools_changed", previousEventId);
        }

        int previousCount = nonNegativeInt(previous.get("inputCount"));
        int currentCount = nonNegativeInt(current.get("inputCount"));
        String previousFingerprint = text(previous.get("inputFingerprint"));
        if (currentCount == previousCount) {
            String reason = Objects.equals(previousFingerprint, text(current.get("inputFingerprint")))
                ? "exact_repeat"
                : "input_rewritten";
            return new Continuity(false, reason, previousEventId);
        }
        if (currentCount < previousCount) {
            return new Continuity(false, "input_shrunk", previousEventId);
        }
        if (previousCount == 0) {
            return new Continuity(true, "prefix_extended", previousEventId);
        }
        List<String> currentChain = fingerprints(current.get("prefixChainFingerprints"));
        if (currentChain.size() < previousCount) {
            return new Continuity(false, "prefix_chain_unavailable", previousEventId);
        }
        if (Objects.equals(previousFingerprint, currentChain.get(previousCount - 1))) {
            return new Continuity(true, "prefix_extended", previousEventId);
        }
        return new Continuity(false, "input_rewritten", previousEventId);
    }

    static Map<String, Object> sanitizeSnapshot(Object rawSnapshot) {
        if (!(rawSnapshot instanceof Map<?, ?> raw) || !Boolean.TRUE.equals(raw.get("bodyRedacted"))) {
            return Map.of();
        }
        if (nonNegativeInt(raw.get("schemaVersion")) != 1) {
            return Map.of();
        }
        String provider = boundedText(raw.get("provider"), 64);
        String wireApi = boundedText(raw.get("wireApi"), 32);
        String model = boundedText(raw.get("model"), 128);
        String stablePrefix = fingerprint(raw.get("stablePrefixFingerprint"));
        String tools = fingerprint(raw.get("toolsFingerprint"));
        String requestSettings = fingerprint(raw.get("requestSettingsFingerprint"));
        String surfaceGeneration = fingerprint(raw.get("surfaceGeneration"));
        String inputFingerprint = fingerprint(raw.get("inputFingerprint"));
        if (provider == null || wireApi == null || model == null || stablePrefix == null
            || tools == null || requestSettings == null || surfaceGeneration == null
            || inputFingerprint == null) {
            return Map.of();
        }
        String requestFamily = optionalBoundedToken(raw, "requestFamily", 64);
        String routeFingerprint = optionalFingerprint(raw, "routeFingerprint");
        String affinityFingerprint = optionalFingerprint(raw, "affinityFingerprint");
        String cacheIdentityMode = optionalBoundedToken(raw, "cacheIdentityMode", 32);
        String promptCacheStrategy = optionalBoundedToken(raw, "promptCacheStrategy", 32);
        if ((raw.containsKey("requestFamily") && requestFamily == null)
            || (raw.containsKey("routeFingerprint") && routeFingerprint == null)
            || (raw.containsKey("affinityFingerprint") && affinityFingerprint == null)
            || (raw.containsKey("cacheIdentityMode")
                && !List.of("none", "prompt_cache_key", "provider_user").contains(cacheIdentityMode))
            || promptCacheStrategy == null
            || !List.of(
                "legacy_model_policy",
                "none",
                "deepseek_automatic",
                "openai_legacy",
                "openai_gpt_5_6"
            ).contains(promptCacheStrategy)) {
            return Map.of();
        }
        int inputCount = nonNegativeInt(raw.get("inputCount"));
        List<String> chain = fingerprints(raw.get("prefixChainFingerprints"));
        if (chain.size() > MAX_CHAIN_ITEMS || chain.size() > inputCount) {
            return Map.of();
        }
        boolean chainComplete = Boolean.TRUE.equals(raw.get("chainComplete"));
        if (chainComplete && chain.size() != inputCount) {
            return Map.of();
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        sanitized.put("schemaVersion", 1);
        sanitized.put("provider", provider);
        sanitized.put("wireApi", wireApi);
        sanitized.put("model", model);
        sanitized.put("stablePrefixFingerprint", stablePrefix);
        sanitized.put("toolsFingerprint", tools);
        sanitized.put("requestSettingsFingerprint", requestSettings);
        sanitized.put("surfaceGeneration", surfaceGeneration);
        sanitized.put("inputCount", inputCount);
        sanitized.put("inputFingerprint", inputFingerprint);
        sanitized.put("prefixChainFingerprints", chain);
        sanitized.put("chainComplete", chainComplete);
        if (requestFamily != null) {
            sanitized.put("requestFamily", requestFamily);
        }
        if (routeFingerprint != null) {
            sanitized.put("routeFingerprint", routeFingerprint);
        }
        if (affinityFingerprint != null) {
            sanitized.put("affinityFingerprint", affinityFingerprint);
        }
        if (cacheIdentityMode != null) {
            sanitized.put("cacheIdentityMode", cacheIdentityMode);
        }
        sanitized.put("promptCacheStrategy", promptCacheStrategy);
        sanitized.put("bodyRedacted", true);
        return sanitized;
    }

    static String scopeKey(Long userId, String conversationId, Map<String, Object> snapshot) {
        String identity = scopeComponent(String.valueOf(userId))
            + scopeComponent(conversationId)
            + scopeComponent(text(snapshot.get("provider")))
            + scopeComponent(text(snapshot.get("model")))
            + scopeComponent(text(snapshot.get("wireApi")))
            + scopeComponent(text(snapshot.get("requestFamily")))
            + scopeComponent(text(snapshot.get("routeFingerprint")))
            + scopeComponent(text(snapshot.get("affinityFingerprint")))
            + scopeComponent(text(snapshot.get("cacheIdentityMode")))
            + scopeComponent(text(snapshot.get("promptCacheStrategy")))
            + scopeComponent(text(snapshot.get("requestSettingsFingerprint")));
        return KEY_PREFIX + sha256(identity);
    }

    private static String scopeComponent(String value) {
        String normalized = value == null ? "" : value;
        return normalized.length() + ":" + normalized;
    }

    private Map<String, Object> readPreviousState(String key) throws JsonProcessingException {
        Object raw = redisTemplate.opsForHash().get(key, "payload");
        if (!(raw instanceof String json) || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private static Map<String, Object> storedState(Long eventId,
                                                   Map<?, ?> checkpoint,
                                                   Map<String, Object> snapshot,
                                                   Continuity continuity) {
        Map<String, Object> state = new LinkedHashMap<>(snapshot);
        state.remove("prefixChainFingerprints");
        state.put("eventId", eventId);
        state.put("cacheReadTokens", nonNegativeLong(checkpoint.get("cacheReadTokens")));
        state.put("cacheMissTokens", nonNegativeLong(checkpoint.get("cacheMissTokens")));
        state.put("cacheWriteTokens", nonNegativeLong(checkpoint.get("cacheWriteTokens")));
        state.put("cacheMissTokensDerived", Boolean.TRUE.equals(checkpoint.get("cacheMissTokensDerived")));
        state.put("cacheUsageReported", Boolean.TRUE.equals(checkpoint.get("cacheUsageReported")));
        state.put("prefixExtended", continuity.prefixExtended());
        state.put("prefixBreakReason", continuity.breakReason());
        if (continuity.previousEventId() != null) {
            state.put("previousEventId", continuity.previousEventId());
        }
        state.put("observedAtEpochSecond", Instant.now().getEpochSecond());
        return state;
    }

    private static List<String> fingerprints(Object value) {
        if (!(value instanceof List<?> values) || values.size() > MAX_CHAIN_ITEMS) {
            return List.of();
        }
        List<String> normalized = new java.util.ArrayList<>(values.size());
        for (Object item : values) {
            String fingerprint = fingerprint(item);
            if (fingerprint == null) {
                return List.of();
            }
            normalized.add(fingerprint);
        }
        return List.copyOf(normalized);
    }

    private static String fingerprint(Object value) {
        String normalized = text(value);
        return normalized != null && normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private static String boundedText(Object value, int maxLength) {
        String normalized = text(value);
        return normalized != null && normalized.length() <= maxLength ? normalized : null;
    }

    private static String optionalBoundedToken(Map<?, ?> raw, String key, int maxLength) {
        if (!raw.containsKey(key)) {
            return null;
        }
        String normalized = boundedText(raw.get(key), maxLength);
        return normalized != null && normalized.matches("[A-Za-z0-9_.:-]+") ? normalized : null;
    }

    private static String optionalFingerprint(Map<?, ?> raw, String key) {
        if (!raw.containsKey(key)) {
            return null;
        }
        return fingerprint(raw.get(key));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static int nonNegativeInt(Object value) {
        long parsed = nonNegativeLong(value);
        return parsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) parsed;
    }

    private static long nonNegativeLong(Object value) {
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    record Continuity(boolean prefixExtended, String breakReason, Long previousEventId) {
    }
}
