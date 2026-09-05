package com.novelanalyzer.modules.knowledge.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAiCacheContinuityServiceTest {

    @Test
    void shouldClassifyStrictPrefixExtensionFromWireHashChain() {
        Map<String, Object> previous = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            2,
            "d".repeat(64),
            List.of("c".repeat(64), "d".repeat(64))
        );
        previous.put("eventId", 10L);
        Map<String, Object> current = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            3,
            "e".repeat(64),
            List.of("c".repeat(64), "d".repeat(64), "e".repeat(64))
        );

        KnowledgeAiCacheContinuityService.Continuity continuity =
            KnowledgeAiCacheContinuityService.classify(previous, current);

        assertThat(continuity.prefixExtended()).isTrue();
        assertThat(continuity.breakReason()).isEqualTo("prefix_extended");
        assertThat(continuity.previousEventId()).isEqualTo(10L);
    }

    @Test
    void shouldExplainStableSurfaceAndInputRewrites() {
        Map<String, Object> previous = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            2,
            "d".repeat(64),
            List.of("c".repeat(64), "d".repeat(64))
        );
        Map<String, Object> changedTools = snapshot(
            "a".repeat(64),
            "f".repeat(64),
            3,
            "e".repeat(64),
            List.of("c".repeat(64), "d".repeat(64), "e".repeat(64))
        );
        Map<String, Object> rewritten = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            3,
            "f".repeat(64),
            List.of("c".repeat(64), "f".repeat(64), "e".repeat(64))
        );

        assertThat(KnowledgeAiCacheContinuityService.classify(previous, changedTools).breakReason())
            .isEqualTo("tools_changed");
        assertThat(KnowledgeAiCacheContinuityService.classify(previous, rewritten).breakReason())
            .isEqualTo("input_rewritten");
    }

    @Test
    void shouldBreakContinuityWhenRequestFamilyRouteOrAffinityChanges() {
        Map<String, Object> previous = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            2,
            "d".repeat(64),
            List.of("c".repeat(64), "d".repeat(64))
        );
        previous.put("requestFamily", "answer");
        previous.put("routeFingerprint", "e".repeat(64));
        previous.put("affinityFingerprint", "f".repeat(64));

        Map<String, Object> changedFamily = new LinkedHashMap<>(previous);
        changedFamily.put("requestFamily", "review");
        assertThat(KnowledgeAiCacheContinuityService.classify(previous, changedFamily).breakReason())
            .isEqualTo("request_family_changed");

        Map<String, Object> changedRoute = new LinkedHashMap<>(previous);
        changedRoute.put("routeFingerprint", "1".repeat(64));
        assertThat(KnowledgeAiCacheContinuityService.classify(previous, changedRoute).breakReason())
            .isEqualTo("provider_route_changed");

        Map<String, Object> changedAffinity = new LinkedHashMap<>(previous);
        changedAffinity.put("affinityFingerprint", "2".repeat(64));
        assertThat(KnowledgeAiCacheContinuityService.classify(previous, changedAffinity).breakReason())
            .isEqualTo("cache_affinity_changed");

        previous.put("cacheIdentityMode", "prompt_cache_key");
        Map<String, Object> changedMode = new LinkedHashMap<>(previous);
        changedMode.put("cacheIdentityMode", "provider_user");
        assertThat(KnowledgeAiCacheContinuityService.classify(previous, changedMode).breakReason())
            .isEqualTo("cache_identity_mode_changed");

        Map<String, Object> changedSettings = new LinkedHashMap<>(previous);
        changedSettings.put("requestSettingsFingerprint", "3".repeat(64));
        assertThat(KnowledgeAiCacheContinuityService.classify(previous, changedSettings).breakReason())
            .isEqualTo("request_settings_changed");

        Map<String, Object> changedStrategy = new LinkedHashMap<>(previous);
        changedStrategy.put("promptCacheStrategy", "openai_gpt_5_6");
        assertThat(KnowledgeAiCacheContinuityService.classify(previous, changedStrategy).breakReason())
            .isEqualTo("prompt_cache_strategy_changed");
    }

    @Test
    void shouldUseSeparateRedisScopeForRequestFamilyAndRoute() {
        Map<String, Object> answer = snapshot(
            "a".repeat(64), "b".repeat(64), 1, "c".repeat(64), List.of("c".repeat(64))
        );
        answer.put("requestFamily", "answer");
        answer.put("routeFingerprint", "d".repeat(64));
        answer.put("affinityFingerprint", "e".repeat(64));
        answer.put("cacheIdentityMode", "prompt_cache_key");
        Map<String, Object> review = new LinkedHashMap<>(answer);
        review.put("requestFamily", "review");
        Map<String, Object> otherRoute = new LinkedHashMap<>(answer);
        otherRoute.put("routeFingerprint", "f".repeat(64));
        Map<String, Object> otherMode = new LinkedHashMap<>(answer);
        otherMode.put("cacheIdentityMode", "provider_user");
        Map<String, Object> otherSettings = new LinkedHashMap<>(answer);
        otherSettings.put("requestSettingsFingerprint", "f".repeat(64));
        Map<String, Object> otherStrategy = new LinkedHashMap<>(answer);
        otherStrategy.put("promptCacheStrategy", "openai_gpt_5_6");

        String answerKey = KnowledgeAiCacheContinuityService.scopeKey(7L, "conversation", answer);
        assertThat(answerKey).startsWith("noval:ai:cache-continuity:v4:");
        assertThat(answerKey)
            .isNotEqualTo(KnowledgeAiCacheContinuityService.scopeKey(7L, "conversation", review))
            .isNotEqualTo(KnowledgeAiCacheContinuityService.scopeKey(7L, "conversation", otherRoute))
            .isNotEqualTo(KnowledgeAiCacheContinuityService.scopeKey(7L, "conversation", otherMode))
            .isNotEqualTo(KnowledgeAiCacheContinuityService.scopeKey(7L, "conversation", otherSettings))
            .isNotEqualTo(KnowledgeAiCacheContinuityService.scopeKey(7L, "conversation", otherStrategy));
    }

    @Test
    void shouldClassifyEveryExistingPrefixBreakReasonDeterministically() {
        Map<String, Object> previous = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            2,
            "d".repeat(64),
            List.of("c".repeat(64), "d".repeat(64))
        );
        previous.put("eventId", 10L);

        Map<String, Object> changedProvider = new LinkedHashMap<>(previous);
        changedProvider.put("provider", "another_provider");
        assertClassification(previous, changedProvider, false, "provider_changed");

        Map<String, Object> changedWire = new LinkedHashMap<>(previous);
        changedWire.put("wireApi", "chat_completions");
        assertClassification(previous, changedWire, false, "wire_api_changed");

        Map<String, Object> changedModel = new LinkedHashMap<>(previous);
        changedModel.put("model", "deepseek-v4-flash");
        assertClassification(previous, changedModel, false, "model_changed");

        Map<String, Object> changedStablePrefix = new LinkedHashMap<>(previous);
        changedStablePrefix.put("stablePrefixFingerprint", "f".repeat(64));
        assertClassification(previous, changedStablePrefix, false, "stable_prefix_changed");

        Map<String, Object> exactRepeat = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            2,
            "d".repeat(64),
            List.of("c".repeat(64), "d".repeat(64))
        );
        assertClassification(previous, exactRepeat, false, "exact_repeat");

        Map<String, Object> shrunk = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            1,
            "c".repeat(64),
            List.of("c".repeat(64))
        );
        assertClassification(previous, shrunk, false, "input_shrunk");

        Map<String, Object> emptyPrefix = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            0,
            "0".repeat(64),
            List.of()
        );
        Map<String, Object> firstInput = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            1,
            "c".repeat(64),
            List.of("c".repeat(64))
        );
        assertClassification(emptyPrefix, firstInput, true, "prefix_extended");

        Map<String, Object> longPrevious = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            65,
            "d".repeat(64),
            List.of("c".repeat(64))
        );
        Map<String, Object> unavailableChain = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            66,
            "e".repeat(64),
            List.of("c".repeat(64))
        );
        longPrevious.put("eventId", 11L);
        longPrevious.put("chainComplete", false);
        unavailableChain.put("chainComplete", false);
        assertClassification(longPrevious, unavailableChain, false, "prefix_chain_unavailable");

        assertClassification(Map.of(), firstInput, false, "no_previous");
    }

    @Test
    void shouldKeepOnlyBoundedIrreversibleProjectionFields() {
        Map<String, Object> raw = snapshot(
            "a".repeat(64),
            "b".repeat(64),
            1,
            "c".repeat(64),
            List.of("c".repeat(64))
        );
        raw.put("unexpectedPrompt", "must never reach redis");

        Map<String, Object> sanitized = KnowledgeAiCacheContinuityService.sanitizeSnapshot(raw);
        String key = KnowledgeAiCacheContinuityService.scopeKey(
            7L,
            "private-conversation-id",
            sanitized
        );

        assertThat(sanitized).doesNotContainKey("unexpectedPrompt");
        assertThat(sanitized.get("bodyRedacted")).isEqualTo(true);
        assertThat(key).startsWith("noval:ai:cache-continuity:v4:");
        assertThat(key).doesNotContain("private-conversation-id");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldProjectOwnedConversationThroughVersionedRedisCas() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.execute("create table ai_chat_run ("
            + "run_id varchar(64) primary key, user_id bigint not null, "
            + "conversation_id varchar(80) not null, deleted int not null)");
        jdbcTemplate.update(
            "insert into ai_chat_run(run_id, user_id, conversation_id, deleted) values(?, ?, ?, 0)",
            "run-1",
            7L,
            "private:conversation"
        );
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(any(), any())).thenReturn(null);
        when(redisTemplate.execute(
            any(RedisScript.class),
            anyList(),
            any(),
            any(),
            any()
        )).thenReturn(1L);
        KnowledgeAiCacheContinuityService service = new KnowledgeAiCacheContinuityService(
            jdbcTemplate,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            redisTemplate
        );
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("cacheContinuity", snapshot(
            "a".repeat(64),
            "b".repeat(64),
            1,
            "c".repeat(64),
            List.of("c".repeat(64))
        ));
        checkpoint.put("cacheReadTokens", 17);
        checkpoint.put("cacheMissTokens", 3);

        boolean projected = service.project(7L, "run-1", 11L, checkpoint);

        assertThat(projected).isTrue();
        verify(redisTemplate).execute(
            any(RedisScript.class),
            anyList(),
            any(),
            any(),
            any()
        );
    }

    private static Map<String, Object> snapshot(String stablePrefix,
                                                String tools,
                                                int inputCount,
                                                String inputFingerprint,
                                                List<String> chain) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("provider", "openai_compatible");
        snapshot.put("wireApi", "responses");
        snapshot.put("model", "deepseek-v4-pro");
        snapshot.put("stablePrefixFingerprint", stablePrefix);
        snapshot.put("toolsFingerprint", tools);
        snapshot.put("surfaceGeneration", "9".repeat(64));
        snapshot.put("inputCount", inputCount);
        snapshot.put("inputFingerprint", inputFingerprint);
        snapshot.put("prefixChainFingerprints", chain);
        snapshot.put("chainComplete", true);
        snapshot.put("requestSettingsFingerprint", "7".repeat(64));
        snapshot.put("promptCacheStrategy", "legacy_model_policy");
        snapshot.put("bodyRedacted", true);
        return snapshot;
    }

    private static void assertClassification(Map<String, Object> previous,
                                             Map<String, Object> current,
                                             boolean prefixExtended,
                                             String breakReason) {
        KnowledgeAiCacheContinuityService.Continuity continuity =
            KnowledgeAiCacheContinuityService.classify(previous, current);
        assertThat(continuity.prefixExtended()).isEqualTo(prefixExtended);
        assertThat(continuity.breakReason()).isEqualTo(breakReason);
    }

    private static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:ai-cache-continuity-" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        return new JdbcTemplate(dataSource);
    }
}
