package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.knowledge.dto.AgentTelemetryRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentExpertProfileUpdateRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentRuntimeConfigUpdateRequest;
import com.novelanalyzer.modules.knowledge.vo.AgentCacheTokenStatsVO;
import com.novelanalyzer.modules.knowledge.vo.AgentExpertProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentRuntimeConfigVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeAgentGovernanceService {

    private static final String CONFIG_TYPE = "ai-agent";
    private static final Map<String, RuntimeSetting> RUNTIME_SETTINGS = runtimeSettings();
    private static final List<AgentExpertProfileVO> DEFAULT_EXPERTS = defaultExperts();

    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeAgentGovernanceService(SystemConfigService systemConfigService,
                                           ObjectMapper objectMapper) {
        this(systemConfigService, objectMapper, null);
    }

    @Autowired
    public KnowledgeAgentGovernanceService(SystemConfigService systemConfigService,
                                           ObjectMapper objectMapper,
                                           JdbcTemplate jdbcTemplate) {
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public AgentRuntimeConfigVO runtimeConfig() {
        AgentRuntimeConfigVO vo = new AgentRuntimeConfigVO();
        vo.setReasoningModeDefault(readString("reasoningModeDefault"));
        vo.setMaxParallelSpecialists(readInt("maxParallelSpecialists"));
        vo.setMaxTotalInputTokens(readInt("maxTotalInputTokens"));
        vo.setMaxFinalOutputTokensFast(readInt("maxFinalOutputTokensFast"));
        vo.setMaxFinalOutputTokensDeep(readInt("maxFinalOutputTokensDeep"));
        vo.setEnableIntentCache(readBoolean("enableIntentCache"));
        vo.setEnableTaskGraphCache(readBoolean("enableTaskGraphCache"));
        vo.setEnableToolCache(readBoolean("enableToolCache"));
        vo.setEnableEvidenceCache(readBoolean("enableEvidenceCache"));
        vo.setEnableSpecialistCache(readBoolean("enableSpecialistCache"));
        vo.setMaxPromptCharsPerExpert(readInt("maxPromptCharsPerExpert"));
        vo.setMaxSkillPromptChars(readInt("maxSkillPromptChars"));
        vo.setMaxEvidenceItems(readInt("maxEvidenceItems"));
        return vo;
    }

    public AgentRuntimeConfigVO updateRuntimeConfig(String key, AgentRuntimeConfigUpdateRequest request) {
        RuntimeSetting setting = RUNTIME_SETTINGS.get(key);
        if (setting == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "runtime config key not found");
        }
        String value = validateRuntimeValue(setting, request == null ? null : request.getValue());
        SystemConfigUpdateRequest saveRequest = new SystemConfigUpdateRequest();
        saveRequest.setConfigKey(setting.configKey());
        saveRequest.setConfigValue(value);
        saveRequest.setConfigType(CONFIG_TYPE);
        saveRequest.setDescription(setting.description());
        systemConfigService.save(saveRequest);
        return runtimeConfig();
    }

    public List<AgentExpertProfileVO> listExpertProfiles() {
        return DEFAULT_EXPERTS.stream()
            .map(defaultProfile -> readExpertProfile(defaultProfile.getExpertName(), defaultProfile))
            .sorted(Comparator.comparing(AgentExpertProfileVO::getPriority))
            .toList();
    }

    public AgentExpertProfileVO updateExpertProfile(String expertName, AgentExpertProfileUpdateRequest request) {
        AgentExpertProfileVO current = findExpertProfile(expertName);
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "expert profile update body is required");
        }
        if (request.getEnabled() != null) {
            current.setEnabled(request.getEnabled());
        }
        if (request.getPriority() != null) {
            current.setPriority(requirePositive("priority", request.getPriority()));
        }
        if (request.getMaxTokens() != null) {
            current.setMaxTokens(requirePositive("maxTokens", request.getMaxTokens()));
        }
        if (request.getMaxToolCalls() != null) {
            current.setMaxToolCalls(requireNonNegative("maxToolCalls", request.getMaxToolCalls()));
        }
        if (request.getTriggerIntents() != null) {
            current.setTriggerIntents(cleanList(request.getTriggerIntents()));
        }
        if (request.getTriggerTasks() != null) {
            current.setTriggerTasks(cleanList(request.getTriggerTasks()));
        }
        if (request.getAllowedTools() != null) {
            current.setAllowedTools(cleanList(request.getAllowedTools()));
        }
        if (request.getPromptVersion() != null) {
            current.setPromptVersion(defaultIfBlank(request.getPromptVersion(), "default"));
        }
        if (request.getEvalSuiteId() != null) {
            current.setEvalSuiteId(trimToNull(request.getEvalSuiteId()));
        }

        SystemConfigUpdateRequest saveRequest = new SystemConfigUpdateRequest();
        saveRequest.setConfigKey(expertConfigKey(current.getExpertName()));
        saveRequest.setConfigValue(writeProfile(current));
        saveRequest.setConfigType(CONFIG_TYPE);
        saveRequest.setDescription("Agent expert profile: " + current.getExpertName());
        systemConfigService.save(saveRequest);
        return current;
    }

    public AgentCacheTokenStatsVO cacheTokenStats() {
        AgentCacheTokenStatsVO stats = new AgentCacheTokenStatsVO();
        if (jdbcTemplate == null) {
            return stats;
        }
        if (aggregatePersistedTelemetry(stats)) {
            return stats;
        }
        List<String> resultJsonRows = jdbcTemplate.query(
            "select result_json from ai_agent_trace where result_json is not null order by id desc limit 500",
            (rs, rowNum) -> rs.getString("result_json")
        );
        stats.setTraceCount(resultJsonRows.size());
        for (String rawJson : resultJsonRows) {
            Map<String, Object> result = parseJsonMap(rawJson);
            if (result.isEmpty()) {
                continue;
            }
            stats.setTotalTokens(stats.getTotalTokens() + longValue(firstPresent(result, "tokenUsed", "token_used", "totalTokens")));
            aggregateCacheEvents(stats, result.get("cacheEvents"));
            aggregateCacheStats(stats, firstPresent(result, "cacheStats", "cache"));
            Object tokenUsage = firstPresent(result, "tokenUsage");
            if (tokenUsage == null) {
                tokenUsage = nested(result, "trace", "tokenUsage");
            }
            aggregateTokenUsage(stats, tokenUsage);
        }
        return stats;
    }

    public Map<String, Integer> ingestTelemetry(AgentTelemetryRequest request) {
        if (jdbcTemplate == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "telemetry store is unavailable");
        }
        String traceId = trimToNull(request == null ? null : request.getTraceId());
        if (traceId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "traceId is required");
        }
        int cacheCount = 0;
        for (AgentTelemetryRequest.CacheEvent event : request.getCacheEvents()) {
            String cacheScope = defaultIfBlank(event.getCacheScope(), "runtime");
            String cacheStatus = normalizeCacheStatus(event.getCacheStatus());
            jdbcTemplate.update("""
                    insert into ai_agent_cache_event(trace_id, cache_scope, node_name, expert_name,
                        cache_key_hash, cache_status, prompt_prefix_hash, prompt_prefix_stable, duration_ms)
                    values(?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                traceId,
                cacheScope,
                trimToNull(event.getNodeName()),
                trimToNull(event.getExpertName()),
                trimToNull(event.getCacheKeyHash()),
                cacheStatus,
                trimToNull(event.getPromptPrefixHash()),
                event.getPromptPrefixStable(),
                nonNegative(event.getDurationMs())
            );
            cacheCount++;
        }
        int tokenCount = 0;
        for (AgentTelemetryRequest.TokenMetric metric : request.getTokenMetrics()) {
            int promptTokens = nonNegative(metric.getPromptTokens());
            int completionTokens = nonNegative(metric.getCompletionTokens());
            int totalTokens = metric.getTokenCount() == null
                ? promptTokens + completionTokens
                : nonNegative(metric.getTokenCount());
            jdbcTemplate.update("""
                    insert into ai_agent_token_metric(trace_id, node_name, expert_name, model_name,
                        prompt_tokens, completion_tokens, token_count)
                    values(?, ?, ?, ?, ?, ?, ?)
                    """,
                traceId,
                trimToNull(metric.getNodeName()),
                trimToNull(metric.getExpertName()),
                trimToNull(metric.getModelName()),
                promptTokens,
                completionTokens,
                totalTokens
            );
            tokenCount++;
        }
        return Map.of("cacheEvents", cacheCount, "tokenMetrics", tokenCount);
    }

    private boolean aggregatePersistedTelemetry(AgentCacheTokenStatsVO stats) {
        try {
            List<String> cacheTraceIds = jdbcTemplate.query(
                "select trace_id from ai_agent_cache_event where trace_id is not null order by id desc limit 5000",
                (rs, rowNum) -> rs.getString("trace_id")
            );
            List<String> tokenTraceIds = jdbcTemplate.query(
                "select trace_id from ai_agent_token_metric where trace_id is not null order by id desc limit 5000",
                (rs, rowNum) -> rs.getString("trace_id")
            );
            if (cacheTraceIds.isEmpty() && tokenTraceIds.isEmpty()) {
                return false;
            }
            Map<String, Boolean> traceIds = new LinkedHashMap<>();
            cacheTraceIds.forEach(traceId -> traceIds.put(traceId, true));
            tokenTraceIds.forEach(traceId -> traceIds.put(traceId, true));
            stats.setTraceCount(traceIds.size());
            aggregatePersistedCacheEvents(stats);
            aggregatePersistedTokenMetrics(stats);
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    private String normalizeCacheStatus(String value) {
        String status = defaultIfBlank(value, "BYPASS").toUpperCase(java.util.Locale.ROOT);
        if (!List.of("HIT", "MISS", "BYPASS").contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported cache status");
        }
        return status;
    }

    private int nonNegative(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }

    private void aggregatePersistedCacheEvents(AgentCacheTokenStatsVO stats) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select cache_status, prompt_prefix_stable from ai_agent_cache_event order by id desc limit 5000"
        );
        long stableKnown = 0;
        long stableCount = 0;
        for (Map<String, Object> row : rows) {
            String status = stringValue(firstPresent(row, "cache_status", "CACHE_STATUS"));
            if ("HIT".equalsIgnoreCase(status)) {
                stats.setCacheHits(stats.getCacheHits() + 1);
            } else if ("MISS".equalsIgnoreCase(status)) {
                stats.setCacheMisses(stats.getCacheMisses() + 1);
            }
            Object stableValue = firstPresent(row, "prompt_prefix_stable", "PROMPT_PREFIX_STABLE");
            if (stableValue != null) {
                stableKnown++;
                if (booleanValue(stableValue)) {
                    stableCount++;
                }
            }
        }
        if (stableKnown > 0) {
            stats.setPromptPrefixStableRate(round4((double) stableCount / (double) stableKnown));
        }
    }

    private void aggregatePersistedTokenMetrics(AgentCacheTokenStatsVO stats) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select node_name, expert_name, token_count from ai_agent_token_metric order by id desc limit 5000"
        );
        for (Map<String, Object> row : rows) {
            long tokenCount = longValue(firstPresent(row, "token_count", "TOKEN_COUNT"));
            stats.setTotalTokens(stats.getTotalTokens() + tokenCount);
            String nodeName = stringValue(firstPresent(row, "node_name", "NODE_NAME"));
            if (nodeName != null) {
                stats.getTokenByNode().merge(nodeName, tokenCount, Long::sum);
            }
            String expertName = stringValue(firstPresent(row, "expert_name", "EXPERT_NAME"));
            if (expertName != null) {
                stats.getTokenByExpert().merge(expertName, tokenCount, Long::sum);
            }
        }
    }

    private AgentExpertProfileVO findExpertProfile(String expertName) {
        AgentExpertProfileVO defaultProfile = DEFAULT_EXPERTS.stream()
            .filter(profile -> profile.getExpertName().equals(expertName))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "expert profile not found"));
        return readExpertProfile(expertName, defaultProfile);
    }

    private AgentExpertProfileVO readExpertProfile(String expertName, AgentExpertProfileVO defaultProfile) {
        String raw = systemConfigService.getValueOrDefault(expertConfigKey(expertName), "");
        if (raw == null || raw.isBlank()) {
            return copyProfile(defaultProfile);
        }
        try {
            AgentExpertProfileVO stored = objectMapper.readValue(raw, AgentExpertProfileVO.class);
            return mergeProfile(defaultProfile, stored);
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "expert profile config is invalid");
        }
    }

    private AgentExpertProfileVO mergeProfile(AgentExpertProfileVO defaults, AgentExpertProfileVO stored) {
        AgentExpertProfileVO merged = copyProfile(defaults);
        if (stored.getEnabled() != null) {
            merged.setEnabled(stored.getEnabled());
        }
        if (stored.getDefaultMode() != null) {
            merged.setDefaultMode(stored.getDefaultMode());
        }
        if (stored.getCostClass() != null) {
            merged.setCostClass(stored.getCostClass());
        }
        if (stored.getMaxTokens() != null) {
            merged.setMaxTokens(stored.getMaxTokens());
        }
        if (stored.getMaxToolCalls() != null) {
            merged.setMaxToolCalls(stored.getMaxToolCalls());
        }
        if (stored.getAllowedTools() != null) {
            merged.setAllowedTools(stored.getAllowedTools());
        }
        if (stored.getTriggerIntents() != null) {
            merged.setTriggerIntents(stored.getTriggerIntents());
        }
        if (stored.getTriggerTasks() != null) {
            merged.setTriggerTasks(stored.getTriggerTasks());
        }
        if (stored.getPriority() != null) {
            merged.setPriority(stored.getPriority());
        }
        if (stored.getPromptVersion() != null) {
            merged.setPromptVersion(stored.getPromptVersion());
        }
        if (stored.getEvalSuiteId() != null) {
            merged.setEvalSuiteId(stored.getEvalSuiteId());
        }
        if (stored.getGuardrail() != null) {
            merged.setGuardrail(stored.getGuardrail());
        }
        return merged;
    }

    private String readString(String key) {
        RuntimeSetting setting = RUNTIME_SETTINGS.get(key);
        return systemConfigService.getValueOrDefault(setting.configKey(), setting.defaultValue());
    }

    private Integer readInt(String key) {
        RuntimeSetting setting = RUNTIME_SETTINGS.get(key);
        return systemConfigService.getIntValueOrDefault(setting.configKey(), Integer.parseInt(setting.defaultValue()));
    }

    private Boolean readBoolean(String key) {
        RuntimeSetting setting = RUNTIME_SETTINGS.get(key);
        return systemConfigService.getBooleanValueOrDefault(setting.configKey(), Boolean.parseBoolean(setting.defaultValue()));
    }

    private String validateRuntimeValue(RuntimeSetting setting, String rawValue) {
        String value = trimToNull(rawValue);
        if (value == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "runtime config value is required");
        }
        return switch (setting.valueType()) {
            case STRING -> validateStringSetting(setting, value);
            case INTEGER -> String.valueOf(validateIntegerSetting(setting, value));
            case BOOLEAN -> String.valueOf(validateBooleanSetting(value));
        };
    }

    private String validateStringSetting(RuntimeSetting setting, String value) {
        if ("reasoningModeDefault".equals(setting.key()) && !List.of("fast", "deep").contains(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "reasoning mode must be fast or deep");
        }
        return value;
    }

    private int validateIntegerSetting(RuntimeSetting setting, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "runtime config value must be integer");
        }
        if (parsed < setting.minValue() || parsed > setting.maxValue()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "runtime config value is out of range");
        }
        return parsed;
    }

    private boolean validateBooleanSetting(String value) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "runtime config value must be boolean");
    }

    private int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, name + " must be positive");
        }
        return value;
    }

    private int requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, name + " must be non-negative");
        }
        return value;
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream()
            .map(this::trimToNull)
            .filter(value -> value != null)
            .distinct()
            .toList();
    }

    private String writeProfile(AgentExpertProfileVO profile) {
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "failed to write expert profile config");
        }
    }

    private void aggregateCacheEvents(AgentCacheTokenStatsVO stats, Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return;
        }
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> event)) {
                continue;
            }
            String status = stringValue(firstPresent(event, "status", "result"));
            if ("HIT".equalsIgnoreCase(status) || "hit".equalsIgnoreCase(status)) {
                stats.setCacheHits(stats.getCacheHits() + 1);
            } else if ("MISS".equalsIgnoreCase(status) || "miss".equalsIgnoreCase(status)) {
                stats.setCacheMisses(stats.getCacheMisses() + 1);
            }
        }
    }

    private void aggregateCacheStats(AgentCacheTokenStatsVO stats, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        stats.setCacheHits(stats.getCacheHits() + longValue(firstPresent(map, "hits", "cacheHits")));
        stats.setCacheMisses(stats.getCacheMisses() + longValue(firstPresent(map, "misses", "cacheMisses")));
    }

    private void aggregateTokenUsage(AgentCacheTokenStatsVO stats, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        addTokenMap(stats.getTokenByNode(), firstPresent(map, "byNode", "node"));
        addTokenMap(stats.getTokenByExpert(), firstPresent(map, "byExpert", "expert"));
    }

    private void addTokenMap(Map<String, Long> target, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = stringValue(entry.getKey());
            if (key == null) {
                continue;
            }
            target.merge(key, longValue(entry.getValue()), Long::sum);
        }
    }

    private Map<String, Object> parseJsonMap(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Object nested(Map<String, Object> source, String parentKey, String childKey) {
        Object parent = source.get(parentKey);
        if (parent instanceof Map<?, ?> map) {
            return map.get(childKey);
        }
        return null;
    }

    private Object firstPresent(Map<?, ?> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String expertConfigKey(String expertName) {
        return "ai.agent.expert." + expertName;
    }

    private static Map<String, RuntimeSetting> runtimeSettings() {
        Map<String, RuntimeSetting> settings = new LinkedHashMap<>();
        add(settings, "reasoningModeDefault", "ai.agent.runtime.reasoning-mode-default", "fast", RuntimeValueType.STRING, 0, 0, "Default AI Q&A reasoning mode");
        add(settings, "maxParallelSpecialists", "ai.agent.runtime.max-parallel-specialists", "3", RuntimeValueType.INTEGER, 1, 16, "Maximum specialist agents that may run in parallel");
        add(settings, "maxTotalInputTokens", "ai.agent.runtime.max-total-input-tokens", "1000000", RuntimeValueType.INTEGER, 4096, 1200000, "Maximum total input token budget per request");
        add(settings, "maxFinalOutputTokensFast", "ai.agent.runtime.max-final-output-tokens.fast", "4000", RuntimeValueType.INTEGER, 0, 32000, "Fast mode final output token budget; 0 disables worker-side cap");
        add(settings, "maxFinalOutputTokensDeep", "ai.agent.runtime.max-final-output-tokens.deep", "8000", RuntimeValueType.INTEGER, 0, 64000, "Deep mode final output token budget; 0 disables worker-side cap");
        add(settings, "enableIntentCache", "ai.agent.runtime.cache.intent.enabled", "true", RuntimeValueType.BOOLEAN, 0, 0, "Enable intent cache");
        add(settings, "enableTaskGraphCache", "ai.agent.runtime.cache.task-graph.enabled", "true", RuntimeValueType.BOOLEAN, 0, 0, "Enable TaskGraph cache");
        add(settings, "enableToolCache", "ai.agent.runtime.cache.tool.enabled", "true", RuntimeValueType.BOOLEAN, 0, 0, "Enable tool result cache");
        add(settings, "enableEvidenceCache", "ai.agent.runtime.cache.evidence.enabled", "true", RuntimeValueType.BOOLEAN, 0, 0, "Enable EvidencePack cache");
        add(settings, "enableSpecialistCache", "ai.agent.runtime.cache.specialist.enabled", "false", RuntimeValueType.BOOLEAN, 0, 0, "Enable specialist report cache");
        add(settings, "maxPromptCharsPerExpert", "ai.agent.runtime.max-prompt-chars-per-expert", "24000", RuntimeValueType.INTEGER, 1000, 200000, "Maximum dynamic prompt characters per expert");
        add(settings, "maxSkillPromptChars", "ai.agent.runtime.max-skill-prompt-chars", "12000", RuntimeValueType.INTEGER, 1000, 100000, "Maximum injected skill prompt characters");
        add(settings, "maxEvidenceItems", "ai.agent.runtime.max-evidence-items", "30", RuntimeValueType.INTEGER, 1, 200, "Maximum evidence items exposed to answer synthesis");
        return settings;
    }

    private static void add(Map<String, RuntimeSetting> settings,
                            String key,
                            String configKey,
                            String defaultValue,
                            RuntimeValueType valueType,
                            int minValue,
                            int maxValue,
                            String description) {
        settings.put(key, new RuntimeSetting(key, configKey, defaultValue, valueType, minValue, maxValue, description));
    }

    private static List<AgentExpertProfileVO> defaultExperts() {
        return List.of(
            expert("market_scan", "Market Agent", true, "both", "high", 1200, 4, List.of("rank.lookup", "rank.research_pack"), List.of("market_scan"), List.of("market_scan"), 10, "default", "market", false),
            expert("author_strategy", "Author Strategy Agent", true, "both", "medium", 900, 3, List.of("skill.lookup", "memory.project_context"), List.of(), List.of("topic_strategy"), 20, "default", "mixed_creation", false),
            expert("opening_strategy", "Opening Strategy Agent", true, "both", "medium", 900, 3, List.of("skill.lookup"), List.of("opening_strategy"), List.of("opening_strategy", "topic_strategy"), 30, "default", null, false),
            expert("book_breakdown", "Book Analyst Agent", true, "both", "high", 1200, 4, List.of("research_pack.book", "knowledge.vector_search"), List.of("book_breakdown"), List.of("book_breakdown"), 40, "default", null, false),
            expert("outline", "Outline Agent", true, "both", "medium", 1000, 3, List.of("skill.lookup", "memory.project_context"), List.of("outline_building"), List.of("outline_building"), 50, "default", null, false),
            expert("chapter_outline", "Chapter Outline Agent", true, "both", "medium", 1000, 3, List.of("skill.lookup", "memory.project_context"), List.of("chapter_outline"), List.of("chapter_outline"), 60, "default", null, false),
            expert("inspiration", "Inspiration Agent", true, "both", "low", 800, 2, List.of("skill.lookup"), List.of("inspiration_expand"), List.of("inspiration_expand", "topic_strategy"), 70, "default", null, false),
            expert("character", "Character Agent", true, "both", "medium", 900, 2, List.of("skill.lookup", "memory.project_context"), List.of("character_design"), List.of("character_design"), 80, "default", null, false),
            expert("worldbuilding", "Worldbuilding Agent", true, "both", "medium", 900, 2, List.of("skill.lookup", "memory.project_context"), List.of("worldbuilding"), List.of("worldbuilding"), 90, "default", null, false),
            expert("revision", "Revision Agent", true, "both", "medium", 900, 2, List.of("skill.lookup", "knowledge.vector_search"), List.of("revision_advice"), List.of("revision_advice"), 100, "default", null, false),
            expert("reader_risk", "Reader Risk Agent", true, "both", "medium", 800, 2, List.of("skill.lookup"), List.of(), List.of("reader_risk"), 900, "default", null, true),
            expert("editor", "Editor Agent", true, "both", "medium", 800, 2, List.of("skill.lookup"), List.of(), List.of("editor_risk"), 910, "default", null, true),
            expert("supervisor", "Supervisor Agent", true, "both", "low", 700, 1, List.of(), List.of(), List.of(), 920, "default", null, true)
        );
    }

    private static AgentExpertProfileVO expert(String expertName,
                                               String displayName,
                                               boolean enabled,
                                               String defaultMode,
                                               String costClass,
                                               int maxTokens,
                                               int maxToolCalls,
                                               List<String> allowedTools,
                                               List<String> triggerIntents,
                                               List<String> triggerTasks,
                                               int priority,
                                               String promptVersion,
                                               String evalSuiteId,
                                               boolean guardrail) {
        AgentExpertProfileVO vo = new AgentExpertProfileVO();
        vo.setExpertName(expertName);
        vo.setDisplayName(displayName);
        vo.setEnabled(enabled);
        vo.setDefaultMode(defaultMode);
        vo.setCostClass(costClass);
        vo.setMaxTokens(maxTokens);
        vo.setMaxToolCalls(maxToolCalls);
        vo.setAllowedTools(allowedTools);
        vo.setTriggerIntents(triggerIntents);
        vo.setTriggerTasks(triggerTasks);
        vo.setPriority(priority);
        vo.setPromptVersion(promptVersion);
        vo.setEvalSuiteId(evalSuiteId);
        vo.setGuardrail(guardrail);
        return vo;
    }

    private static AgentExpertProfileVO copyProfile(AgentExpertProfileVO source) {
        AgentExpertProfileVO copy = new AgentExpertProfileVO();
        copy.setExpertName(source.getExpertName());
        copy.setDisplayName(source.getDisplayName());
        copy.setEnabled(source.getEnabled());
        copy.setDefaultMode(source.getDefaultMode());
        copy.setCostClass(source.getCostClass());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setMaxToolCalls(source.getMaxToolCalls());
        copy.setAllowedTools(source.getAllowedTools());
        copy.setTriggerIntents(source.getTriggerIntents());
        copy.setTriggerTasks(source.getTriggerTasks());
        copy.setPriority(source.getPriority());
        copy.setPromptVersion(source.getPromptVersion());
        copy.setEvalSuiteId(source.getEvalSuiteId());
        copy.setGuardrail(source.getGuardrail());
        return copy;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private record RuntimeSetting(
        String key,
        String configKey,
        String defaultValue,
        RuntimeValueType valueType,
        int minValue,
        int maxValue,
        String description
    ) {
    }

    private enum RuntimeValueType {
        STRING,
        INTEGER,
        BOOLEAN
    }
}
