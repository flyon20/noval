package com.novelanalyzer.modules.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.AiProviderCapabilities;
import com.novelanalyzer.modules.config.model.AiPromptCacheCapabilities;
import com.novelanalyzer.modules.config.model.AiProviderRoutingPolicy;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.knowledge.dto.AgentTelemetryRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentExpertProfileUpdateRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentProviderRoutingOutcomeRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentRuntimeConfigUpdateRequest;
import com.novelanalyzer.modules.knowledge.vo.AgentCacheTokenStatsVO;
import com.novelanalyzer.modules.knowledge.vo.AgentExpertProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentRuntimeConfigVO;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderCircuitStateVO;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderRoutingPolicyVO;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderRuntimeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeAgentGovernanceService {

    private static final String CONFIG_TYPE = "ai-agent";
    private static final Map<String, RuntimeSetting> RUNTIME_SETTINGS = runtimeSettings();
    private static final List<AgentExpertProfileVO> DEFAULT_EXPERTS = defaultExperts();
    /**
     * Failure classes the worker's classifier can emit. 401/403 (credential rejected),
     * 402 (quota) and 404 (model missing) belong here even though they never recover on
     * the same key: the breaker still has to learn that this profile is unusable.
     */
    private static final List<String> ACCEPTED_FAILURE_CLASSES = List.of(
        "CONNECT_ERROR",
        "TIMEOUT",
        "HTTP_401",
        "HTTP_402",
        "HTTP_403",
        "HTTP_404",
        "HTTP_429",
        "HTTP_500",
        "HTTP_502",
        "HTTP_503",
        "HTTP_504"
    );

    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeAgentProviderCircuitShadowService providerCircuitShadowService;

    public KnowledgeAgentGovernanceService(SystemConfigService systemConfigService,
                                           ObjectMapper objectMapper) {
        this(systemConfigService, objectMapper, null, null);
    }

    public KnowledgeAgentGovernanceService(SystemConfigService systemConfigService,
                                           ObjectMapper objectMapper,
                                           JdbcTemplate jdbcTemplate) {
        this(systemConfigService, objectMapper, jdbcTemplate, null);
    }

    @Autowired
    public KnowledgeAgentGovernanceService(
        SystemConfigService systemConfigService,
        ObjectMapper objectMapper,
        JdbcTemplate jdbcTemplate,
        KnowledgeAgentProviderCircuitShadowService providerCircuitShadowService
    ) {
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.providerCircuitShadowService = providerCircuitShadowService;
    }

    public AgentRuntimeConfigVO runtimeConfig() {
        AgentRuntimeConfigVO vo = new AgentRuntimeConfigVO();
        vo.setReasoningModeDefault(readString("reasoningModeDefault"));
        vo.setMaxParallelSpecialists(readInt("maxParallelSpecialists"));
        vo.setMaxTotalInputTokens(readInt("maxTotalInputTokens"));
        vo.setContextCompactionThresholdPercent(readInt("contextCompactionThresholdPercent"));
        vo.setRunTokenBudgetPercent(readInt("runTokenBudgetPercent"));
        vo.setMaxFinalOutputTokensFast(readInt("maxFinalOutputTokensFast"));
        vo.setMaxFinalOutputTokensDeep(readInt("maxFinalOutputTokensDeep"));
        vo.setEnableIntentCache(readBoolean("enableIntentCache"));
        vo.setEnableTaskGraphCache(readBoolean("enableTaskGraphCache"));
        vo.setEnableToolCache(readBoolean("enableToolCache"));
        vo.setEnableEvidenceCache(readBoolean("enableEvidenceCache"));
        vo.setEnableSpecialistCache(readBoolean("enableSpecialistCache"));
        vo.setSpecialistMcpEnabled(readBoolean("specialistMcpEnabled"));
        vo.setMaxPromptCharsPerExpert(readInt("maxPromptCharsPerExpert"));
        vo.setMaxSkillPromptChars(readInt("maxSkillPromptChars"));
        vo.setMaxEvidenceItems(readInt("maxEvidenceItems"));
        AiModelRegistryVO registry = systemConfigService.getModelRegistry();
        List<AgentProviderProfileVO> providerProfiles = providerProfiles(registry);
        vo.setProviderProfiles(providerProfiles);
        vo.setProviderRoutingPolicy(providerRoutingPolicy(registry.getProviderRoutingPolicy(), providerProfiles));
        return vo;
    }

    private List<AgentProviderProfileVO> providerProfiles(AiModelRegistryVO registry) {
        return registry.getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .map(this::toProviderProfile)
            .toList();
    }

    private AgentProviderRoutingPolicyVO providerRoutingPolicy(
        AiProviderRoutingPolicy policy,
        List<AgentProviderProfileVO> profiles
    ) {
        AiProviderRoutingPolicy source = policy == null ? new AiProviderRoutingPolicy() : policy;
        AgentProviderRoutingPolicyVO vo = new AgentProviderRoutingPolicyVO();
        vo.setSchemaVersion(source.getSchemaVersion());
        vo.setEnabled(Boolean.TRUE.equals(source.getEnabled()));
        vo.setOrderedProfileKeys(source.getOrderedProfileKeys());
        vo.setMaxFailovers(source.getMaxFailovers());
        vo.setCooldownSeconds(source.getCooldownSeconds());
        if (!Boolean.TRUE.equals(source.getEnabled())) {
            vo.setCircuitStates(Map.of());
            return vo;
        }
        Map<String, AgentProviderProfileVO> profilesByKey = new LinkedHashMap<>();
        for (AgentProviderProfileVO profile : profiles) {
            profilesByKey.put(profile.getProfileKey(), profile);
        }
        Map<String, AgentProviderCircuitStateVO> states = new LinkedHashMap<>();
        int cooldownSeconds = source.getCooldownSeconds() == null
            ? AiProviderRoutingPolicy.DEFAULT_COOLDOWN_SECONDS
            : source.getCooldownSeconds();
        for (String profileKey : source.getOrderedProfileKeys()) {
            AgentProviderProfileVO profile = profilesByKey.get(profileKey);
            if (profile == null) {
                continue;
            }
            AgentProviderCircuitStateVO observed = providerCircuitShadowService == null
                ? null
                : providerCircuitShadowService.readState(
                    profileKey,
                    profile.getProfileVersion(),
                    cooldownSeconds
                );
            AgentProviderCircuitStateVO state = new AgentProviderCircuitStateVO();
            state.setProfileKey(profileKey);
            state.setProfileVersion(profile.getProfileVersion());
            state.setState(normalizeCircuitState(observed == null ? null : observed.getState()));
            state.setFailureCount(observed != null && observed.getFailureCount() != null
                ? Math.max(0L, observed.getFailureCount())
                : 0L);
            states.put(profileKey, state);
        }
        vo.setCircuitStates(states);
        return vo;
    }

    private static String normalizeCircuitState(String state) {
        // A missing projection means "no evidence of failure", and List.of rejects null.
        if (state == null) {
            return "CLOSED";
        }
        return "OPEN".equals(state) || "HALF_OPEN".equals(state) ? state : "CLOSED";
    }

    private AgentProviderProfileVO toProviderProfile(AiModelRegistryModelVO model) {
        AgentProviderProfileVO profile = new AgentProviderProfileVO();
        String profileKey = defaultIfBlank(model.getModelKey(), model.getModelName());
        String endpoint = defaultIfBlank(model.getBaseUrl(), "");
        String modelName = defaultIfBlank(model.getModelName(), model.getModelKey());
        String providerType = defaultIfBlank(model.getProviderType(), "openai-compatible");
        String protocol = defaultIfBlank(model.getProtocol(), "unspecified");
        profile.setProfileKey(profileKey);
        profile.setEndpoint(endpoint);
        profile.setModel(modelName);
        profile.setProviderType(providerType);
        profile.setProtocol(protocol);
        profile.setProviderCapabilities(copyProviderCapabilities(model.getProviderCapabilities()));
        profile.setEnabled(model.getEnabled());
        profile.setIsDefault(model.getIsDefault());
        profile.setApiKeyConfigured(model.getApiKeyConfigured());
        profile.setProfileVersion(providerProfileVersion(
            profileKey,
            endpoint,
            modelName,
            providerType,
            protocol,
            profile.getProviderCapabilities()
        ));
        return profile;
    }

    private String providerProfileVersion(String profileKey, String endpoint, String model,
                                          String providerType, String protocol,
                                          AiProviderCapabilities capabilities) {
        String canonical = String.join(
            "\u001f",
            profileKey,
            endpoint,
            model,
            providerType,
            protocol,
            providerCapabilitiesCanonical(capabilities)
        );
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public AgentProviderRuntimeVO resolveProviderRuntime(String profileKey, String expectedProfileVersion) {
        String normalizedProfileKey = trimToNull(profileKey);
        String normalizedExpectedVersion = trimToNull(expectedProfileVersion);
        if (normalizedProfileKey == null || normalizedExpectedVersion == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "provider profile identity is required");
        }

        AiModelRegistryModelVO runtimeModel = systemConfigService.resolveEnabledModelByKey(normalizedProfileKey)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "provider profile is not available"));
        AgentProviderProfileVO profile = toProviderProfile(runtimeModel);
        String endpoint = trimToNull(profile.getEndpoint());
        String model = trimToNull(profile.getModel());
        String protocol = trimToNull(profile.getProtocol());
        if (!normalizedExpectedVersion.equals(profile.getProfileVersion())) {
            throw new BusinessException(ResultCode.CONFLICT, "provider profile version changed");
        }
        if (endpoint == null || model == null || !List.of("responses", "chat_completions").contains(protocol)) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "provider profile is not dispatchable");
        }
        String apiKey = trimToNull(runtimeModel.getApiKey());
        if (apiKey == null) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "provider credential is not configured");
        }
        assertCircuitAllowsDispatch(profile.getProfileKey(), profile.getProfileVersion());

        AgentProviderRuntimeVO runtime = new AgentProviderRuntimeVO();
        runtime.setProfileKey(profile.getProfileKey());
        runtime.setProfileVersion(profile.getProfileVersion());
        runtime.setEndpoint(endpoint);
        runtime.setModel(model);
        runtime.setProviderType(profile.getProviderType());
        runtime.setProtocol(protocol);
        runtime.setProviderCapabilities(copyProviderCapabilities(profile.getProviderCapabilities()));
        runtime.setApiKey(apiKey);
        return runtime;
    }

    /**
     * Refuses to hand out a credential for a profile whose breaker is OPEN.
     *
     * <p>Only enforced while routing is enabled and the profile is one of the ordered
     * candidates: without an alternative to fall back to, gating the single configured
     * profile would turn a transient upstream blip into a hard outage. A HALF_OPEN
     * profile is admitted for exactly one probe per cooldown half-life.
     */
    private void assertCircuitAllowsDispatch(String profileKey, String profileVersion) {
        if (providerCircuitShadowService == null) {
            return;
        }
        AiProviderRoutingPolicy policy = systemConfigService.getModelRegistry().getProviderRoutingPolicy();
        if (policy == null
            || !Boolean.TRUE.equals(policy.getEnabled())
            || !policy.getOrderedProfileKeys().contains(profileKey)) {
            return;
        }
        int cooldownSeconds = policy.getCooldownSeconds() == null
            ? AiProviderRoutingPolicy.DEFAULT_COOLDOWN_SECONDS
            : policy.getCooldownSeconds();
        if (!providerCircuitShadowService.tryAcquireDispatchPermit(
            profileKey,
            profileVersion,
            cooldownSeconds
        )) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "provider circuit is open");
        }
    }

    public AgentProviderCircuitStateVO recordProviderRoutingOutcome(
        AgentProviderRoutingOutcomeRequest request
    ) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "provider routing outcome is required");
        }
        String profileKey = trimToNull(request.getProfileKey());
        String profileVersion = trimToNull(request.getProfileVersion());
        String outcome = trimToNull(request.getOutcome());
        String failureClass = trimToNull(request.getFailureClass());
        if (profileKey == null || profileVersion == null || outcome == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "provider routing outcome is invalid");
        }

        AiModelRegistryVO registry = systemConfigService.getModelRegistry();
        AiProviderRoutingPolicy policy = registry.getProviderRoutingPolicy();
        if (policy == null
            || !Boolean.TRUE.equals(policy.getEnabled())
            || !policy.getOrderedProfileKeys().contains(profileKey)) {
            throw new BusinessException(ResultCode.CONFLICT, "provider routing profile is not active");
        }
        AgentProviderProfileVO profile = registry.getModels().stream()
            .filter(model -> profileKey.equals(model.getModelKey()) && Boolean.TRUE.equals(model.getEnabled()))
            .findFirst()
            .map(this::toProviderProfile)
            .orElseThrow(() -> new BusinessException(
                ResultCode.CONFLICT,
                "provider routing profile is not active"
            ));
        if (!profileVersion.equals(profile.getProfileVersion())) {
            throw new BusinessException(ResultCode.CONFLICT, "provider profile version changed");
        }
        if (providerCircuitShadowService == null) {
            return closedProviderCircuit(profileKey, profileVersion);
        }
        if ("SUCCEEDED".equals(outcome)) {
            if (failureClass != null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "successful provider outcome cannot have failureClass");
            }
            return providerCircuitShadowService.recordSuccess(profileKey, profileVersion);
        }
        if (!"TRANSIENT_FAILURE".equals(outcome)
            || failureClass == null
            || !ACCEPTED_FAILURE_CLASSES.contains(failureClass)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "provider routing failure class is invalid");
        }
        int cooldownSeconds = policy.getCooldownSeconds() == null
            ? AiProviderRoutingPolicy.DEFAULT_COOLDOWN_SECONDS
            : policy.getCooldownSeconds();
        return providerCircuitShadowService.recordTransientFailure(
            profileKey,
            profileVersion,
            cooldownSeconds
        );
    }

    private AgentProviderCircuitStateVO closedProviderCircuit(String profileKey, String profileVersion) {
        AgentProviderCircuitStateVO state = new AgentProviderCircuitStateVO();
        state.setProfileKey(profileKey);
        state.setProfileVersion(profileVersion);
        state.setState("CLOSED");
        state.setFailureCount(0L);
        return state;
    }

    private String providerCapabilitiesCanonical(AiProviderCapabilities capabilities) {
        if (capabilities == null) {
            return "legacy_unknown";
        }
        return String.join(
            ",",
            String.valueOf(capabilities.getSchemaVersion()),
            String.valueOf(capabilities.getSupportsStreaming()),
            String.valueOf(capabilities.getSupportsTools()),
            String.valueOf(capabilities.getSupportsJsonObject()),
            String.valueOf(capabilities.getSupportsReasoning()),
            String.valueOf(capabilities.getReportsUsage()),
            String.valueOf(capabilities.getReportsCacheUsage()),
            promptCacheCapabilitiesCanonical(capabilities.getPromptCache())
        );
    }

    private String promptCacheCapabilitiesCanonical(AiPromptCacheCapabilities promptCache) {
        if (promptCache == null) {
            return "legacy_model_policy";
        }
        return String.join(
            ":",
            String.valueOf(promptCache.getStrategy()),
            String.valueOf(promptCache.getMode()),
            String.valueOf(promptCache.getRetention()),
            String.valueOf(promptCache.getBreakpoint())
        );
    }

    private AiProviderCapabilities copyProviderCapabilities(AiProviderCapabilities capabilities) {
        return capabilities == null ? null : capabilities.copy();
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
            .map(profile -> {
                normalizeExecutionKind(profile);
                return profile;
            })
            .map(this::applyEvalDerivedQualityGain)
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
        if (request.getCapabilityIds() != null) {
            current.setCapabilityIds(cleanList(request.getCapabilityIds()));
        }
        if (request.getDefaultSkillIds() != null) {
            current.setDefaultSkillIds(cleanList(request.getDefaultSkillIds()));
        }
        if (request.getRequestedToolCapabilities() != null) {
            current.setRequestedToolCapabilities(cleanList(request.getRequestedToolCapabilities()));
        }
        if (request.getOutputContract() != null) {
            current.setOutputContract(trimToNull(request.getOutputContract()));
        }
        if (request.getExecutionKind() != null) {
            current.setExecutionKind(requireExecutionKind(request.getExecutionKind()));
        }
        if (request.getPromptVersion() != null) {
            current.setPromptVersion(defaultIfBlank(request.getPromptVersion(), "default"));
        }
        if (request.getEvalSuiteId() != null) {
            current.setEvalSuiteId(trimToNull(request.getEvalSuiteId()));
        }
        if (request.getCategory() != null) {
            current.setCategory(requireExpertCategory(request.getCategory()));
            if (request.getExecutionKind() == null) {
                current.setExecutionKind(executionKindFromCategory(current.getCategory()));
            }
        }
        normalizeExecutionKind(current);
        if (request.getExpectedQualityGain() != null) {
            double requestedGain = requireNonNegative("expectedQualityGain", request.getExpectedQualityGain());
            if (requestedGain > 0) {
                throw new BusinessException(
                    ResultCode.BAD_REQUEST,
                    "expectedQualityGain is derived from the latest admin-configured eval run"
                );
            }
        }
        if (request.getLatencyCost() != null) {
            current.setLatencyCost(requireNonNegative("latencyCost", request.getLatencyCost()));
        }
        if (request.getTokenCost() != null) {
            current.setTokenCost(requireNonNegative("tokenCost", request.getTokenCost()));
        }
        if (request.getResourceCost() != null) {
            current.setResourceCost(requireNonNegative("resourceCost", request.getResourceCost()));
        }

        SystemConfigUpdateRequest saveRequest = new SystemConfigUpdateRequest();
        saveRequest.setConfigKey(expertConfigKey(current.getExpertName()));
        saveRequest.setConfigValue(writeProfile(current));
        saveRequest.setConfigType(CONFIG_TYPE);
        saveRequest.setDescription("Agent expert profile: " + current.getExpertName());
        systemConfigService.save(saveRequest);
        return applyEvalDerivedQualityGain(current);
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
        if (stored.getCapabilityIds() != null) {
            merged.setCapabilityIds(stored.getCapabilityIds());
        }
        if (stored.getDefaultSkillIds() != null) {
            merged.setDefaultSkillIds(stored.getDefaultSkillIds());
        }
        if (stored.getRequestedToolCapabilities() != null) {
            merged.setRequestedToolCapabilities(stored.getRequestedToolCapabilities());
        }
        if (stored.getOutputContract() != null) {
            merged.setOutputContract(stored.getOutputContract());
        }
        if (stored.getExecutionKind() != null) {
            merged.setExecutionKind(stored.getExecutionKind());
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
        if (stored.getCategory() != null) {
            merged.setCategory(stored.getCategory());
        }
        if (stored.getLatencyCost() != null) {
            merged.setLatencyCost(stored.getLatencyCost());
        }
        if (stored.getTokenCost() != null) {
            merged.setTokenCost(stored.getTokenCost());
        }
        if (stored.getResourceCost() != null) {
            merged.setResourceCost(stored.getResourceCost());
        }
        normalizeExecutionKind(merged);
        return merged;
    }

    private AgentExpertProfileVO applyEvalDerivedQualityGain(AgentExpertProfileVO profile) {
        profile.setExpectedQualityGain(0.0);
        profile.setQualityGainVerified(Boolean.FALSE);
        profile.setQualityGainSource("unverified");
        profile.setQualityGainEvalRunId(null);
        if (!"Delegated".equals(profile.getCategory())) {
            profile.setQualityGainSource("not_required");
            return profile;
        }
        if (jdbcTemplate == null || profile.getEvalSuiteId() == null || profile.getEvalSuiteId().isBlank()) {
            return profile;
        }
        try {
            List<EvalQualityRow> rows = jdbcTemplate.query("""
                    SELECT id, metrics_json
                    FROM ai_eval_run
                    WHERE suite_name = ?
                      AND status = 'PASSED'
                      AND failed_cases = 0
                      AND metrics_json IS NOT NULL
                      AND deleted = 0
                    ORDER BY id DESC
                    LIMIT 1
                    """,
                (rs, rowNum) -> new EvalQualityRow(rs.getLong("id"), rs.getString("metrics_json")),
                profile.getEvalSuiteId()
            );
            if (rows.isEmpty()) {
                return profile;
            }
            Map<String, Object> metrics = parseJsonMap(rows.get(0).metricsJson());
            List<Double> requiredScores = new ArrayList<>(List.of(
                0.0,
                0.0,
                0.0,
                0.0,
                0.0
            ));
            requiredScores.set(0, metric(metrics, "faithfulness_pass_rate"));
            requiredScores.set(1, metric(metrics, "required_tool_pass_rate"));
            requiredScores.set(2, metric(metrics, "trace_completeness_rate"));
            requiredScores.set(3, metric(metrics, "answer_boundary_pass_rate"));
            requiredScores.set(4, metric(metrics, "delegation_policy_pass_rate"));
            String evalConfigFingerprint = expertEvalConfigFingerprint(profile);
            Double delegatedPresence = evalConfigMetric(
                metrics,
                "delegated_eval_config_presence_rates",
                evalConfigFingerprint
            );
            Double delegatedQualityGain = evalConfigMetric(
                metrics,
                "delegated_eval_config_gains",
                evalConfigFingerprint
            );
            if (requiredScores.stream().anyMatch(value -> value == null || value < 1.0)
                || delegatedPresence == null
                || delegatedPresence < 1.0
                || delegatedQualityGain == null
                || delegatedQualityGain <= 0.0
                || !containsEvalConfigFingerprint(
                    metrics.get("delegated_eval_config_fingerprints"),
                    evalConfigFingerprint
                )) {
                return profile;
            }
            profile.setExpectedQualityGain(delegatedQualityGain);
            profile.setQualityGainVerified(Boolean.TRUE);
            profile.setQualityGainSource("admin_configured_eval");
            profile.setQualityGainEvalRunId(rows.get(0).id());
            return profile;
        } catch (DataAccessException ex) {
            return profile;
        }
    }

    private Double metric(Map<String, Object> metrics, String key) {
        Object raw = metrics.get(key);
        if (!(raw instanceof Number number)) {
            return null;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0 ? value : null;
    }

    private Double evalConfigMetric(Map<String, Object> metrics, String key, String evalConfigFingerprint) {
        Object raw = metrics.get(key);
        if (!(raw instanceof Map<?, ?> values)) {
            return null;
        }
        Object configValue = values.get(evalConfigFingerprint);
        if (!(configValue instanceof Number number)) {
            return null;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0 ? value : null;
    }

    public String expertEvalConfigFingerprint(AgentExpertProfileVO profile) {
        String category = profile.getCategory() == null || profile.getCategory().isBlank() ? "Skill" : profile.getCategory().trim();
        String executionKind = profile.getExecutionKind();
        if (executionKind == null || executionKind.isBlank()) {
            executionKind = executionKindFromCategory(category);
        } else {
            executionKind = executionKind.trim().toUpperCase();
        }
        // TreeMap keeps key order identical to worker json.dumps(sort_keys=True).
        Map<String, Object> payload = new java.util.TreeMap<>();
        payload.put("capabilityIds", profile.getCapabilityIds() == null
            ? List.of()
            : profile.getCapabilityIds().stream().sorted().toList());
        payload.put("category", category);
        payload.put("costClass", profile.getCostClass());
        payload.put("defaultMode", profile.getDefaultMode());
        payload.put("defaultSkillIds", profile.getDefaultSkillIds() == null
            ? List.of()
            : profile.getDefaultSkillIds().stream().sorted().toList());
        payload.put("displayName", profile.getDisplayName());
        payload.put("enabled", profile.getEnabled());
        payload.put("evalSuite", profile.getEvalSuiteId());
        payload.put("executionKind", executionKind);
        payload.put("guardrail", profile.getGuardrail());
        payload.put("latencyCost", profile.getLatencyCost());
        payload.put("maxTokens", profile.getMaxTokens());
        payload.put("maxToolCalls", profile.getMaxToolCalls());
        payload.put("name", profile.getExpertName());
        payload.put("outputContract", profile.getOutputContract());
        payload.put("priority", profile.getPriority());
        payload.put("promptVersion", profile.getPromptVersion());
        payload.put("requestedToolCapabilities", profile.getRequestedToolCapabilities() == null
            ? List.of()
            : profile.getRequestedToolCapabilities().stream().sorted().toList());
        payload.put("resourceCost", profile.getResourceCost());
        payload.put("tokenCost", profile.getTokenCost());
        payload.put("triggerIntents", profile.getTriggerIntents() == null
            ? List.of()
            : profile.getTriggerIntents().stream().sorted().toList());
        payload.put("triggerTaskTypes", profile.getTriggerTasks() == null
            ? List.of()
            : profile.getTriggerTasks().stream().sorted().toList());
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("failed to fingerprint expert eval configuration", ex);
        }
    }

    private boolean containsEvalConfigFingerprint(Object value, String expected) {
        if (!(value instanceof Iterable<?> iterable)) {
            return false;
        }
        for (Object item : iterable) {
            if (expected.equals(String.valueOf(item))) {
                return true;
            }
        }
        return false;
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

    private double requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, name + " must be a finite non-negative number");
        }
        return value;
    }

    private String requireExpertCategory(String value) {
        String normalized = defaultIfBlank(value, "");
        if (!List.of("Skill", "Deterministic", "Delegated").contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "category must be Skill, Deterministic or Delegated");
        }
        return normalized;
    }

    private String requireExecutionKind(String value) {
        String normalized = defaultIfBlank(value, "").toUpperCase();
        if (!List.of("INLINE", "DETERMINISTIC", "DELEGATED").contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "executionKind must be INLINE, DETERMINISTIC or DELEGATED");
        }
        return normalized;
    }

    private static String executionKindFromCategory(String category) {
        String normalized = category == null ? "" : category.trim();
        if (normalized.isEmpty()) {
            normalized = "Skill";
        }
        if ("Delegated".equals(normalized)) {
            return "DELEGATED";
        }
        if ("Deterministic".equals(normalized)) {
            return "DETERMINISTIC";
        }
        return "INLINE";
    }

    private void normalizeExecutionKind(AgentExpertProfileVO profile) {
        if (profile == null) {
            return;
        }
        if (profile.getExecutionKind() == null || profile.getExecutionKind().isBlank()) {
            profile.setExecutionKind(executionKindFromCategory(profile.getCategory()));
        } else {
            profile.setExecutionKind(requireExecutionKind(profile.getExecutionKind()));
        }
        if (profile.getCapabilityIds() == null) {
            profile.setCapabilityIds(new ArrayList<>());
        }
        if (profile.getDefaultSkillIds() == null) {
            profile.setDefaultSkillIds(new ArrayList<>());
        }
        if (profile.getRequestedToolCapabilities() == null) {
            profile.setRequestedToolCapabilities(new ArrayList<>());
        }
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
        add(settings, "reasoningModeDefault", "ai.knowledge.reasoning-mode.default", "fast", RuntimeValueType.STRING, 0, 0, "Default AI Q&A reasoning mode");
        add(settings, "maxParallelSpecialists", "ai.agent.runtime.max-parallel-specialists", "1", RuntimeValueType.INTEGER, 1, 1, "Maximum specialist concurrency on the J3160 profile");
        add(settings, "maxTotalInputTokens", "ai.agent.runtime.max-total-input-tokens", "300000", RuntimeValueType.INTEGER, 4096, 1200000, "Provider/model context ceiling; Worker derives a bounded per-turn working budget");
        add(settings, "contextCompactionThresholdPercent", "ai.agent.runtime.context-compaction-threshold-percent", "85", RuntimeValueType.INTEGER, 50, 95, "Context compaction trigger as a percent of the context ceiling");
        add(settings, "runTokenBudgetPercent", "ai.agent.runtime.run-token-budget-percent", "150", RuntimeValueType.INTEGER, 50, 400, "Whole-run token budget as a percent of the context ceiling");
        add(settings, "maxFinalOutputTokensFast", "ai.agent.runtime.max-final-output-tokens.fast", "0", RuntimeValueType.INTEGER, 0, 500000, "Fast mode output ceiling; zero selects model-aware automatic sizing");
        add(settings, "maxFinalOutputTokensDeep", "ai.agent.runtime.max-final-output-tokens.deep", "0", RuntimeValueType.INTEGER, 0, 500000, "Deep mode output ceiling; zero selects model-aware automatic sizing");
        add(settings, "enableIntentCache", "ai.agent.runtime.cache.intent.enabled", "true", RuntimeValueType.BOOLEAN, 0, 0, "Enable intent cache");
        add(settings, "enableTaskGraphCache", "ai.agent.runtime.cache.task-graph.enabled", "true", RuntimeValueType.BOOLEAN, 0, 0, "Enable TaskGraph cache");
        add(settings, "enableToolCache", "ai.agent.runtime.cache.tool.enabled", "true", RuntimeValueType.BOOLEAN, 0, 0, "Enable tool result cache");
        add(settings, "enableEvidenceCache", "ai.agent.runtime.cache.evidence.enabled", "true", RuntimeValueType.BOOLEAN, 0, 0, "Enable EvidencePack cache");
        add(settings, "enableSpecialistCache", "ai.agent.runtime.cache.specialist.enabled", "false", RuntimeValueType.BOOLEAN, 0, 0, "Enable specialist report cache");
        add(settings, "specialistMcpEnabled", "ai.agent.runtime.specialist-mcp.enabled", "false", RuntimeValueType.BOOLEAN, 0, 0, "Allow eligible delegated specialists to call MCP tools");
        add(settings, "maxPromptCharsPerExpert", "ai.agent.runtime.max-prompt-chars-per-expert", "0", RuntimeValueType.INTEGER, 0, 1000000, "Per-expert prompt ceiling; zero selects model-aware automatic sizing");
        add(settings, "maxSkillPromptChars", "ai.agent.runtime.max-skill-prompt-chars", "0", RuntimeValueType.INTEGER, 0, 1000000, "Dedicated Skill prompt character cap; zero disables the dedicated cap");
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
            expert("market_scan", "Market Agent", true, "both", "high", 1200, 4, List.of("market.read"), List.of("market_scan"), List.of("market_scan"), 10, "default", "market", false),
            expert("author_strategy", "Author Strategy Agent", true, "both", "medium", 900, 3, List.of("skill.activate", "memory.project.read"), List.of(), List.of("topic_strategy"), 20, "default", "mixed_creation", false),
            expert("opening_strategy", "Opening Strategy Agent", true, "both", "medium", 900, 3, List.of("skill.activate"), List.of("opening_strategy"), List.of("opening_strategy", "topic_strategy"), 30, "default", null, false),
            expert("book_breakdown", "Book Analyst Agent", true, "both", "high", 1200, 4, List.of("book.read"), List.of("book_breakdown"), List.of("book_breakdown"), 40, "default", null, false),
            expert("outline", "Outline Agent", true, "both", "medium", 1000, 3, List.of("skill.activate", "memory.project.read"), List.of("outline_building"), List.of("outline_building"), 50, "default", null, false),
            expert("chapter_outline", "Chapter Outline Agent", true, "both", "medium", 1000, 3, List.of("skill.activate", "memory.project.read"), List.of("chapter_outline"), List.of("chapter_outline"), 60, "default", null, false),
            expert("inspiration", "Inspiration Agent", true, "both", "low", 800, 2, List.of("skill.activate"), List.of("inspiration_expand"), List.of("inspiration_expand", "topic_strategy"), 70, "default", null, false),
            expert("character", "Character Agent", true, "both", "medium", 900, 2, List.of("skill.activate", "memory.project.read"), List.of("character_design"), List.of("character_design"), 80, "default", null, false),
            expert("worldbuilding", "Worldbuilding Agent", true, "both", "medium", 900, 2, List.of("skill.activate", "memory.project.read"), List.of("worldbuilding"), List.of("worldbuilding"), 90, "default", null, false),
            expert("revision", "Revision Agent", true, "both", "medium", 900, 2, List.of("skill.activate", "book.read"), List.of("revision_advice"), List.of("revision_advice"), 100, "default", null, false),
            expert("reader_risk", "Reader Risk Agent", true, "both", "medium", 800, 2, List.of("skill.activate"), List.of(), List.of("reader_risk"), 900, "default", null, true),
            expert("editor", "Editor Agent", true, "both", "medium", 800, 2, List.of("skill.activate"), List.of(), List.of("editor_risk"), 910, "default", null, true),
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
                                               List<String> requestedToolCapabilities,
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
        vo.setTriggerIntents(triggerIntents);
        vo.setTriggerTasks(triggerTasks);
        vo.setPriority(priority);
        vo.setPromptVersion(promptVersion);
        vo.setEvalSuiteId(evalSuiteId);
        vo.setGuardrail(guardrail);
        vo.setCategory("supervisor".equals(expertName) ? "Delegated" : (guardrail ? "Deterministic" : "Skill"));
        vo.setExecutionKind(executionKindFromCategory(vo.getCategory()));
        vo.setCapabilityIds(new ArrayList<>());
        vo.setDefaultSkillIds(new ArrayList<>());
        vo.setRequestedToolCapabilities(
            requestedToolCapabilities == null
                ? new ArrayList<>()
                : new ArrayList<>(requestedToolCapabilities)
        );
        vo.setOutputContract(null);
        vo.setExpectedQualityGain(0.0);
        vo.setQualityGainVerified(Boolean.FALSE);
        vo.setQualityGainSource("unverified");
        vo.setQualityGainEvalRunId(null);
        vo.setLatencyCost(0.0);
        vo.setTokenCost(0.0);
        vo.setResourceCost(0.0);
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
        copy.setCapabilityIds(source.getCapabilityIds());
        copy.setDefaultSkillIds(source.getDefaultSkillIds());
        copy.setRequestedToolCapabilities(source.getRequestedToolCapabilities());
        copy.setOutputContract(source.getOutputContract());
        copy.setExecutionKind(source.getExecutionKind());
        copy.setTriggerIntents(source.getTriggerIntents());
        copy.setTriggerTasks(source.getTriggerTasks());
        copy.setPriority(source.getPriority());
        copy.setPromptVersion(source.getPromptVersion());
        copy.setEvalSuiteId(source.getEvalSuiteId());
        copy.setGuardrail(source.getGuardrail());
        copy.setCategory(source.getCategory());
        copy.setExpectedQualityGain(source.getExpectedQualityGain());
        copy.setQualityGainVerified(source.getQualityGainVerified());
        copy.setQualityGainSource(source.getQualityGainSource());
        copy.setQualityGainEvalRunId(source.getQualityGainEvalRunId());
        copy.setLatencyCost(source.getLatencyCost());
        copy.setTokenCost(source.getTokenCost());
        copy.setResourceCost(source.getResourceCost());
        return copy;
    }

    private record EvalQualityRow(Long id, String metricsJson) {
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
