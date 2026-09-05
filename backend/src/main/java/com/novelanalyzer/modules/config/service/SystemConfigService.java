package com.novelanalyzer.modules.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.AiModelRegistryModelRequest;
import com.novelanalyzer.modules.config.dto.AiModelRegistrySaveRequest;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.AiProviderCapabilities;
import com.novelanalyzer.modules.config.model.AiPromptCacheCapabilities;
import com.novelanalyzer.modules.config.model.AiProviderRoutingPolicy;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import com.novelanalyzer.modules.config.repository.SystemConfigRepository;
import com.novelanalyzer.modules.config.vo.AiModelOptionVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.config.vo.SystemConfigVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class SystemConfigService {

    private static final String MODEL_REGISTRY_CONFIG_KEY = "ai.model-registry.json";
    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";
    private static final String DEFAULT_MODEL_KEY = "deepseek-chat";
    private static final String DEFAULT_TEMPERATURE_SPEC_JSON = "{\"min\":0.0,\"max\":2.0,\"step\":0.1,\"default\":1.0}";
    private static final Set<String> SECRET_CONFIG_KEYS = Set.of(
        "ai.openai-compatible.api-key",
        "ai.langgraph-worker.internal-api-key"
    );

    private static final Map<String, DefaultSystemConfig> DEFAULT_SYSTEM_CONFIGS = Map.ofEntries(
        Map.entry("ai.provider.type", new DefaultSystemConfig("openai-compatible", "ai", "旧版兼容配置，模型注册表会优先决定实际请求方式。", true)),
        Map.entry("ai.timeout.millis", new DefaultSystemConfig("15000", "ai", "控制单书分析等常规 AI 请求超时时间。", true)),
        Map.entry("analysis.runtime.mode", new DefaultSystemConfig("langgraph", "analysis", "控制单书分析使用 legacy 还是 LangGraph 运行链路。", true)),
        Map.entry("ai.openai-compatible.base-url", new DefaultSystemConfig("", "ai", "旧版兼容地址，模型注册表为空时作为回落值。", true)),
        Map.entry("ai.openai-compatible.default-model", new DefaultSystemConfig("deepseek-chat", "ai", "旧版默认模型，模型注册表为空时作为回落值。", true)),
        Map.entry("ai.openai-compatible.api-key", new DefaultSystemConfig("", "ai", "旧版兼容密钥，留空或掩码会保留原密钥。", true)),
        Map.entry("ai.openai-compatible.streaming-enabled", new DefaultSystemConfig("true", "ai", "旧版兼容开关，当前主要由运行链路决定流式表现。", true)),
        Map.entry("ai.langgraph-worker.base-url", new DefaultSystemConfig("", "ai", "LangGraph worker 服务地址，留空时使用环境配置。", true)),
        Map.entry("ai.langgraph-worker.internal-api-key", new DefaultSystemConfig("", "ai", "后端调用 LangGraph worker 的内部鉴权令牌。", true)),
        Map.entry("ai.langgraph-worker.timeout-millis", new DefaultSystemConfig("30000", "ai", "后端等待 LangGraph worker 响应的超时时间。", true)),
        Map.entry("ai.knowledge.reasoning-mode.default", new DefaultSystemConfig("fast", "ai", "AI 问答默认推理模式：fast 为快速模式，deep 为 DeepSeek 思考模式并使用 max 强度。", true)),
        Map.entry("ai.conversation.read-rollout-percent", new DefaultSystemConfig("100", "ai", "Conversation/Message 新读路径灰度比例：0、10、50 或 100；生产默认使用新会话数据。", true)),
        Map.entry("ai.conversation.legacy-fallback-enabled", new DefaultSystemConfig("true", "ai", "Conversation 新读路径缺失时是否立即回退旧 Run 数据。", true)),
        Map.entry("ai.available-models", new DefaultSystemConfig("deepseek-chat", "ai", "旧版逗号分隔模型列表，模型注册表保存后会同步。", true)),
        Map.entry("auth.bootstrap-admin-phones", new DefaultSystemConfig("15599316908", "auth", "逗号分隔的管理员手机号列表，登录或刷新时自动补齐 ADMIN 角色。", true)),
        Map.entry("crawler.default.chapter-count", new DefaultSystemConfig("3", "crawler", "扫榜页默认抓取的章节数量。", true)),
        Map.entry("crawler.http.timeout-seconds", new DefaultSystemConfig("20", "crawler", "爬虫请求页面时的超时时间。", true)),
        Map.entry("crawler.chapter.fetch-workers", new DefaultSystemConfig("3", "crawler", "多章节抓取时的最大并发数。", true)),
        Map.entry("crawler.chapter.force-refresh.user-max-times", new DefaultSystemConfig("3", "crawler", "普通用户在当前窗口内的章节重抓上限。", true)),
        Map.entry("crawler.rank.refresh-days", new DefaultSystemConfig("3", "crawler", "榜单缓存期与章节重抓统计窗口。", true)),
        Map.entry("crawler.rank.force-cooldown-days", new DefaultSystemConfig("2", "crawler", "普通用户强制刷新榜单后的冷却天数。", true)),
        Map.entry("crawler.rank.force-max-times", new DefaultSystemConfig("2", "crawler", "普通用户在冷却窗口内可强制刷新榜单的次数。", true)),
        Map.entry("crawler.book.refresh-days", new DefaultSystemConfig("7", "crawler", "书籍详情和章节信息的缓存天数。", true)),
        Map.entry("analysis.reanalyze.cooldown-hours", new DefaultSystemConfig("0", "analysis", "已有成功结果且输入未变化时，普通用户的重新分析冷却时间。", true)),
        Map.entry("analysis.reanalyze.user-max-times", new DefaultSystemConfig("3", "analysis", "已有成功结果且输入未变化时，普通用户在冷却窗口内的次数上限。", true)),
        Map.entry("analysis.chunk.max-input-tokens", new DefaultSystemConfig("32000", "analysis", "单次分析允许的估算输入 Token 上限，超过后自动分段汇总。", true)),
        Map.entry("analysis.chunk.target-input-tokens", new DefaultSystemConfig("24000", "analysis", "分段分析时每段的目标输入 Token 大小。", true)),
        Map.entry("analysis.chunk.parallelism", new DefaultSystemConfig("3", "analysis", "分段分析时的最大并发段数。", true)),
        Map.entry("security.audit.enabled", new DefaultSystemConfig("true", "security", "控制后台操作审计日志是否启用。", true))
    );

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;
    private final ConfigSecretService configSecretService;

    public SystemConfigService(SystemConfigRepository systemConfigRepository,
                               ObjectMapper objectMapper,
                               ConfigSecretService configSecretService) {
        this.systemConfigRepository = systemConfigRepository;
        this.objectMapper = objectMapper;
        this.configSecretService = configSecretService;
    }

    public SystemConfigVO getByKey(String configKey) {
        SystemConfigEntity entity = findOrCreateDefaultConfig(configKey)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "system config not found"));
        return toVO(entity);
    }

    public SystemConfigVO save(SystemConfigUpdateRequest request) {
        SystemConfigEntity existing = systemConfigRepository.findByKey(request.getConfigKey()).orElse(null);
        if (existing != null && existing.getEditable() != null && existing.getEditable() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "system config is read only");
        }
        SystemConfigEntity entity = existing == null ? new SystemConfigEntity() : existing;
        entity.setConfigKey(request.getConfigKey());
        entity.setConfigValue(prepareConfigValueForStorage(
            request.getConfigKey(),
            request.getConfigValue(),
            existing == null ? null : existing.getConfigValue()
        ));
        entity.setConfigType(request.getConfigType());
        entity.setDescription(request.getDescription());
        return toVO(systemConfigRepository.saveOrUpdate(entity));
    }

    public List<SystemConfigVO> getKnownConfigs() {
        return DEFAULT_SYSTEM_CONFIGS.keySet().stream()
            .sorted(Comparator.naturalOrder())
            .map(this::findOrCreateDefaultConfig)
            .flatMap(Optional::stream)
            .map(this::toKnownConfigVO)
            .toList();
    }

    public String getValueOrDefault(String configKey, String defaultValue) {
        return findOrCreateDefaultConfig(configKey)
            .map(SystemConfigEntity::getConfigValue)
            .map(value -> isSecretConfigKey(configKey) ? configSecretService.decryptIfNecessary(value) : value)
            .filter(value -> value != null && !value.isBlank())
            .orElse(defaultValue);
    }

    public int getIntValueOrDefault(String configKey, int defaultValue) {
        return parseInteger(getValueOrDefault(configKey, null)).orElse(defaultValue);
    }

    public long getLongValueOrDefault(String configKey, long defaultValue) {
        return parseLong(getValueOrDefault(configKey, null)).orElse(defaultValue);
    }

    public boolean getBooleanValueOrDefault(String configKey, boolean defaultValue) {
        return parseBoolean(getValueOrDefault(configKey, null)).orElse(defaultValue);
    }

    public AiModelRegistryVO getModelRegistry() {
        return sanitizeModelRegistry(getModelRegistryInternal());
    }

    public boolean isPromptTemplateBound(String promptType, String promptName) {
        if (promptType == null || promptType.isBlank() || promptName == null || promptName.isBlank()) {
            return false;
        }
        String normalizedPromptName = promptName.trim();
        return getModelRegistryInternal().getModels().stream()
            .map(AiModelRegistryModelVO::getPromptBindings)
            .filter(bindings -> bindings != null)
            .map(bindings -> bindings.get(promptType))
            .filter(boundPromptName -> boundPromptName != null)
            .anyMatch(boundPromptName -> normalizedPromptName.equals(boundPromptName.trim()));
    }

    public AiModelRegistryVO saveModelRegistry(AiModelRegistrySaveRequest request) {
        AiModelRegistryVO existingRegistry = getModelRegistryInternal();
        AiModelRegistryVO registry = normalizeModelRegistry(
            request.getDefaultModelKey(),
            mergeModelRegistryRequests(existingRegistry, request.getModels()),
            request.getProviderRoutingPolicy() == null
                ? copyProviderRoutingPolicy(existingRegistry.getProviderRoutingPolicy())
                : copyProviderRoutingPolicy(request.getProviderRoutingPolicy())
        );
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setConfigKey(MODEL_REGISTRY_CONFIG_KEY);
        entity.setConfigType("ai");
        entity.setDescription("Structured AI model registry");
        entity.setConfigValue(writeJson(registry));
        systemConfigRepository.saveOrUpdate(entity);
        syncLegacyModelConfig(registry);
        return sanitizeModelRegistry(registry);
    }

    public List<AiModelOptionVO> getModelOptions() {
        return getModelRegistryInternal().getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .map(this::toModelOption)
            .toList();
    }

    public List<String> getAvailableModels() {
        List<String> registryModels = getModelOptions().stream()
            .map(AiModelOptionVO::getModelKey)
            .filter(modelKey -> modelKey != null && !modelKey.isBlank())
            .toList();
        if (!registryModels.isEmpty()) {
            return registryModels;
        }
        String value = getValueOrDefault("ai.available-models", "");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    public Optional<AiModelRegistryModelVO> resolveEnabledModel(String... candidates) {
        AiModelRegistryVO registry = getModelRegistryInternal();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Optional<AiModelRegistryModelVO> matched = registry.getModels().stream()
                .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
                .filter(model -> candidate.equals(model.getModelKey()) || candidate.equals(model.getModelName()))
                .findFirst();
            if (matched.isPresent()) {
                return matched.map(this::toRuntimeModel);
            }
        }
        String defaultModelKey = registry.getDefaultModelKey();
        Optional<AiModelRegistryModelVO> defaultModel = registry.getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .filter(model -> defaultModelKey != null && defaultModelKey.equals(model.getModelKey()))
            .findFirst();
        if (defaultModel.isPresent()) {
            return defaultModel.map(this::toRuntimeModel);
        }
        return registry.getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .findFirst()
            .map(this::toRuntimeModel);
    }

    public Optional<AiModelRegistryModelVO> resolveEnabledModelByKey(String modelKey) {
        String normalizedModelKey = trimToNull(modelKey);
        if (normalizedModelKey == null) {
            return Optional.empty();
        }
        return getModelRegistryInternal().getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .filter(model -> normalizedModelKey.equals(model.getModelKey()))
            .findFirst()
            .map(this::toRuntimeModel);
    }

    private AiModelRegistryModelVO toRuntimeModel(AiModelRegistryModelVO model) {
        AiModelRegistryModelVO runtime = new AiModelRegistryModelVO();
        runtime.setModelKey(model.getModelKey());
        runtime.setDisplayName(model.getDisplayName());
        runtime.setProviderType(model.getProviderType());
        runtime.setProtocol(model.getProtocol());
        runtime.setProviderCapabilities(copyProviderCapabilities(model.getProviderCapabilities()));
        runtime.setModelName(model.getModelName());
        runtime.setBaseUrl(model.getBaseUrl());
        runtime.setApiKey(configSecretService.decryptIfNecessary(model.getApiKey()));
        runtime.setApiKeyConfigured(model.getApiKeyConfigured());
        runtime.setApiKeyMasked(model.getApiKeyMasked());
        runtime.setEnabled(model.getEnabled());
        runtime.setIsDefault(model.getIsDefault());
        runtime.setDefaultTemperature(model.getDefaultTemperature());
        runtime.setMaxTokens(model.getMaxTokens());
        runtime.setTemperatureSpecJson(model.getTemperatureSpecJson());
        runtime.setPromptBindings(model.getPromptBindings());
        return runtime;
    }

    private SystemConfigVO toVO(SystemConfigEntity entity) {
        SystemConfigVO vo = new SystemConfigVO();
        vo.setId(entity.getId());
        vo.setConfigKey(entity.getConfigKey());
        vo.setConfigValue(isSecretConfigKey(entity.getConfigKey())
            ? configSecretService.maskValue(entity.getConfigValue())
            : entity.getConfigValue());
        vo.setConfigType(entity.getConfigType());
        vo.setDescription(entity.getDescription());
        vo.setEditable(entity.getEditable() == null || entity.getEditable() == 1);
        return vo;
    }

    private SystemConfigVO toKnownConfigVO(SystemConfigEntity entity) {
        SystemConfigVO vo = toVO(entity);
        DefaultSystemConfig known = DEFAULT_SYSTEM_CONFIGS.get(entity.getConfigKey());
        if (known != null) {
            vo.setConfigType(known.configType());
            vo.setDescription(known.description());
            vo.setEditable(known.editable());
        }
        return vo;
    }

    private Optional<Integer> parseInteger(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private Optional<Long> parseLong(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if ("true".equalsIgnoreCase(value) || "1".equals(value.trim())) {
            return Optional.of(Boolean.TRUE);
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value.trim())) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }

    private Optional<SystemConfigEntity> findOrCreateDefaultConfig(String configKey) {
        Optional<SystemConfigEntity> existing = systemConfigRepository.findByKey(configKey);
        if (existing.isPresent()) {
            return Optional.of(normalizeStoredSecretConfig(configKey, existing.get()));
        }
        DefaultSystemConfig defaultConfig = DEFAULT_SYSTEM_CONFIGS.get(configKey);
        if (defaultConfig == null) {
            return Optional.empty();
        }
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setConfigKey(configKey);
        entity.setConfigValue(prepareConfigValueForStorage(configKey, defaultConfig.configValue(), null));
        entity.setConfigType(defaultConfig.configType());
        entity.setDescription(defaultConfig.description());
        entity.setEditable(defaultConfig.editable() ? 1 : 0);
        return Optional.of(systemConfigRepository.saveOrUpdate(entity));
    }

    private SystemConfigEntity getOrCreateModelRegistryEntity() {
        SystemConfigEntity entity = systemConfigRepository.findByKey(MODEL_REGISTRY_CONFIG_KEY)
            .filter(current -> current.getConfigValue() != null && !current.getConfigValue().isBlank())
            .orElseGet(() -> {
                SystemConfigEntity created = new SystemConfigEntity();
                created.setConfigKey(MODEL_REGISTRY_CONFIG_KEY);
                created.setConfigType("ai");
                created.setDescription("Structured AI model registry");
                created.setConfigValue(writeJson(buildDefaultModelRegistry()));
                created.setEditable(1);
                return systemConfigRepository.saveOrUpdate(created);
            });
        return migratePlaintextModelSecrets(entity);
    }

    private AiModelRegistryVO getModelRegistryInternal() {
        return parseModelRegistry(getOrCreateModelRegistryEntity().getConfigValue());
    }

    private AiModelRegistryVO buildDefaultModelRegistry() {
        List<String> legacyAvailableModels = getLegacyAvailableModels();
        String configuredDefaultModel = firstNonBlank(
            getValueOrDefault("ai.openai-compatible.default-model", DEFAULT_MODEL_KEY),
            legacyAvailableModels.isEmpty() ? DEFAULT_MODEL_KEY : legacyAvailableModels.get(0)
        );
        String configuredBaseUrl = getStoredConfigValue("ai.openai-compatible.base-url");
        String configuredApiKey = firstNonBlank(getStoredConfigValue("ai.openai-compatible.api-key"), "");
        List<AiModelRegistryModelRequest> models = legacyAvailableModels.stream()
            .map(modelKey -> {
                AiModelRegistryModelRequest request = new AiModelRegistryModelRequest();
                request.setModelKey(modelKey);
                request.setDisplayName(prettyModelName(modelKey));
                request.setProviderType(OPENAI_COMPATIBLE_PROVIDER);
                request.setProtocol("unspecified");
                request.setModelName(modelKey);
                request.setBaseUrl(configuredBaseUrl);
                request.setApiKey(configuredApiKey);
                request.setEnabled(true);
                request.setIsDefault(modelKey.equals(configuredDefaultModel));
                request.setDefaultTemperature(1.0);
                request.setMaxTokens(8192);
                request.setTemperatureSpecJson(DEFAULT_TEMPERATURE_SPEC_JSON);
                return request;
            })
            .toList();
        return normalizeModelRegistry(configuredDefaultModel, models, new AiProviderRoutingPolicy());
    }

    private List<String> getLegacyAvailableModels() {
        String value = findOrCreateDefaultConfig("ai.available-models")
            .map(SystemConfigEntity::getConfigValue)
            .orElse(DEFAULT_MODEL_KEY);
        if (value == null || value.isBlank()) {
            return List.of(DEFAULT_MODEL_KEY);
        }
        List<String> models = Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
        return models.isEmpty() ? List.of(DEFAULT_MODEL_KEY) : models;
    }

    private AiModelRegistryVO parseModelRegistry(String rawJson) {
        try {
            AiModelRegistryVO registry = objectMapper.readValue(rawJson, AiModelRegistryVO.class);
            List<AiModelRegistryModelRequest> requests = new ArrayList<>();
            if (registry.getModels() != null) {
                for (AiModelRegistryModelVO model : registry.getModels()) {
                    AiModelRegistryModelRequest request = new AiModelRegistryModelRequest();
                    request.setModelKey(model.getModelKey());
                    request.setDisplayName(model.getDisplayName());
                    request.setProviderType(model.getProviderType());
                    request.setProtocol(model.getProtocol());
                    request.setProviderCapabilities(copyProviderCapabilities(model.getProviderCapabilities()));
                    request.setModelName(model.getModelName());
                    request.setBaseUrl(model.getBaseUrl());
                    request.setApiKey(model.getApiKey());
                    request.setEnabled(model.getEnabled());
                    request.setIsDefault(model.getIsDefault());
                    request.setDefaultTemperature(model.getDefaultTemperature());
                    request.setMaxTokens(model.getMaxTokens());
                    request.setTemperatureSpecJson(model.getTemperatureSpecJson());
                    request.setPromptBindings(model.getPromptBindings());
                    requests.add(request);
                }
            }
            return normalizeModelRegistry(
                registry.getDefaultModelKey(),
                requests,
                registry.getProviderRoutingPolicy()
            );
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "model registry config is invalid");
        }
    }

    private AiModelRegistryVO normalizeModelRegistry(String requestedDefaultModelKey,
                                                     List<AiModelRegistryModelRequest> modelRequests,
                                                     AiProviderRoutingPolicy requestedRoutingPolicy) {
        if (modelRequests == null || modelRequests.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "model registry must contain at least one model");
        }
        List<AiModelRegistryModelVO> models = modelRequests.stream()
            .map(this::toModelRegistryModel)
            .toList();
        long enabledCount = models.stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .count();
        if (enabledCount == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "model registry must contain at least one enabled model");
        }
        long distinctModelKeyCount = models.stream()
            .map(AiModelRegistryModelVO::getModelKey)
            .distinct()
            .count();
        if (distinctModelKeyCount != models.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "modelKey values must be unique");
        }

        String defaultModelKey = firstNonBlank(
            trimToNull(requestedDefaultModelKey),
            models.stream()
                .filter(model -> Boolean.TRUE.equals(model.getIsDefault()))
                .map(AiModelRegistryModelVO::getModelKey)
                .filter(modelKey -> modelKey != null && !modelKey.isBlank())
                .findFirst()
                .orElse(null),
            models.stream()
                .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
                .map(AiModelRegistryModelVO::getModelKey)
                .findFirst()
                .orElse(null)
        );
        boolean defaultExists = models.stream()
            .anyMatch(model -> Boolean.TRUE.equals(model.getEnabled()) && defaultModelKey.equals(model.getModelKey()));
        if (!defaultExists) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "defaultModelKey must match an enabled model");
        }

        AiModelRegistryVO registry = new AiModelRegistryVO();
        registry.setDefaultModelKey(defaultModelKey);
        registry.setModels(models.stream()
            .map(model -> {
                AiModelRegistryModelVO normalized = new AiModelRegistryModelVO();
                normalized.setModelKey(model.getModelKey());
                normalized.setDisplayName(model.getDisplayName());
                normalized.setProviderType(model.getProviderType());
                normalized.setProtocol(normalizeProtocol(model.getProtocol()));
                normalized.setProviderCapabilities(copyProviderCapabilities(model.getProviderCapabilities()));
                normalized.setModelName(model.getModelName());
                normalized.setBaseUrl(model.getBaseUrl());
                normalized.setApiKey(model.getApiKey());
                normalized.setEnabled(model.getEnabled());
                normalized.setIsDefault(defaultModelKey.equals(model.getModelKey()));
                normalized.setDefaultTemperature(model.getDefaultTemperature());
                normalized.setMaxTokens(model.getMaxTokens());
                normalized.setTemperatureSpecJson(model.getTemperatureSpecJson());
                normalized.setPromptBindings(model.getPromptBindings());
                return normalized;
            })
            .toList());
        registry.setProviderRoutingPolicy(normalizeProviderRoutingPolicy(requestedRoutingPolicy, registry.getModels()));
        return registry;
    }

    private AiModelRegistryModelVO toModelRegistryModel(AiModelRegistryModelRequest request) {
        String modelKey = trimToNull(request.getModelKey());
        if (modelKey == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "modelKey is required");
        }
        AiModelRegistryModelVO model = new AiModelRegistryModelVO();
        model.setModelKey(modelKey);
        model.setDisplayName(firstNonBlank(trimToNull(request.getDisplayName()), prettyModelName(modelKey)));
        model.setProviderType(firstNonBlank(trimToNull(request.getProviderType()), OPENAI_COMPATIBLE_PROVIDER));
        model.setProtocol(normalizeProtocol(request.getProtocol()));
        model.setProviderCapabilities(normalizeProviderCapabilities(request.getProviderCapabilities()));
        validateProviderPromptCacheProtocol(model.getProtocol(), model.getProviderCapabilities());
        model.setModelName(firstNonBlank(trimToNull(request.getModelName()), modelKey));
        model.setBaseUrl(trimToNull(request.getBaseUrl()));
        model.setApiKey(firstNonBlank(trimToNull(request.getApiKey()), ""));
        model.setEnabled(request.getEnabled() == null || request.getEnabled());
        model.setIsDefault(request.getIsDefault() != null && request.getIsDefault());
        model.setDefaultTemperature(request.getDefaultTemperature() == null ? 1.0 : request.getDefaultTemperature());
        model.setMaxTokens(request.getMaxTokens() == null ? 8192 : request.getMaxTokens());
        model.setTemperatureSpecJson(firstNonBlank(
            trimToNull(request.getTemperatureSpecJson()),
            buildDefaultTemperatureSpecJson(model.getDefaultTemperature())
        ));
        model.setPromptBindings(request.getPromptBindings());
        return model;
    }

    private AiModelOptionVO toModelOption(AiModelRegistryModelVO model) {
        AiModelOptionVO option = new AiModelOptionVO();
        option.setModelKey(model.getModelKey());
        option.setDisplayName(model.getDisplayName());
        option.setProviderType(model.getProviderType());
        option.setIsDefault(model.getIsDefault());
        option.setDefaultTemperature(model.getDefaultTemperature());
        option.setMaxTokens(model.getMaxTokens());
        option.setTemperatureSpecJson(model.getTemperatureSpecJson());
        return option;
    }

    private void syncLegacyModelConfig(AiModelRegistryVO registry) {
        String availableModels = registry.getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .map(AiModelRegistryModelVO::getModelKey)
            .filter(modelKey -> modelKey != null && !modelKey.isBlank())
            .distinct()
            .reduce((left, right) -> left + "," + right)
            .orElse("");
        saveSystemConfigValue("ai.available-models", availableModels, "Comma-separated list of available AI models for user selection");

        AiModelRegistryModelVO defaultModel = registry.getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .filter(model -> registry.getDefaultModelKey() != null && registry.getDefaultModelKey().equals(model.getModelKey()))
            .findFirst()
            .orElse(null);
        if (defaultModel != null) {
            saveSystemConfigValue("ai.openai-compatible.default-model",
                firstNonBlank(defaultModel.getModelName(), defaultModel.getModelKey()),
                "Default OpenAI compatible model name");
            saveSystemConfigValue("ai.openai-compatible.base-url",
                defaultModel.getBaseUrl(),
                "OpenAI compatible base URL, blank means fallback to application config");
            saveSystemConfigValue("ai.openai-compatible.api-key",
                firstNonBlank(defaultModel.getApiKey(), ""),
                "OpenAI compatible API key (stored in DB, takes precedence over env var)");
        }
    }

    private void saveSystemConfigValue(String configKey, String configValue, String description) {
        SystemConfigEntity entity = systemConfigRepository.findByKey(configKey).orElseGet(SystemConfigEntity::new);
        entity.setConfigKey(configKey);
        entity.setConfigType("ai");
        entity.setDescription(description);
        entity.setConfigValue(prepareConfigValueForStorage(configKey, configValue, entity.getConfigValue()));
        systemConfigRepository.saveOrUpdate(entity);
    }

    private String getStoredConfigValue(String configKey) {
        return findOrCreateDefaultConfig(configKey)
            .map(SystemConfigEntity::getConfigValue)
            .map(this::trimToNull)
            .orElse(null);
    }

    private AiModelRegistryVO sanitizeModelRegistry(AiModelRegistryVO registry) {
        AiModelRegistryVO sanitized = new AiModelRegistryVO();
        sanitized.setDefaultModelKey(registry.getDefaultModelKey());
        sanitized.setProviderRoutingPolicy(copyProviderRoutingPolicy(registry.getProviderRoutingPolicy()));
        sanitized.setModels(registry.getModels().stream()
            .map(model -> {
                AiModelRegistryModelVO copy = new AiModelRegistryModelVO();
                copy.setModelKey(model.getModelKey());
                copy.setDisplayName(model.getDisplayName());
                copy.setProviderType(model.getProviderType());
                copy.setProtocol(model.getProtocol());
                copy.setProviderCapabilities(copyProviderCapabilities(model.getProviderCapabilities()));
                copy.setModelName(model.getModelName());
                copy.setBaseUrl(model.getBaseUrl());
                copy.setApiKey(null);
                copy.setApiKeyConfigured(configSecretService.hasSecret(model.getApiKey()));
                copy.setApiKeyMasked(configSecretService.maskValue(model.getApiKey()));
                copy.setEnabled(model.getEnabled());
                copy.setIsDefault(model.getIsDefault());
                copy.setDefaultTemperature(model.getDefaultTemperature());
                copy.setMaxTokens(model.getMaxTokens());
                copy.setTemperatureSpecJson(model.getTemperatureSpecJson());
                copy.setPromptBindings(model.getPromptBindings());
                return copy;
            })
            .toList());
        return sanitized;
    }

    private List<AiModelRegistryModelRequest> mergeModelRegistryRequests(AiModelRegistryVO existingRegistry,
                                                                         List<AiModelRegistryModelRequest> requestedModels) {
        Map<String, AiModelRegistryModelVO> existingByModelKey = new HashMap<>();
        if (existingRegistry != null && existingRegistry.getModels() != null) {
            for (AiModelRegistryModelVO existing : existingRegistry.getModels()) {
                if (existing.getModelKey() != null && !existing.getModelKey().isBlank()) {
                    existingByModelKey.put(existing.getModelKey(), existing);
                }
            }
        }

        return requestedModels.stream()
            .map(request -> {
                AiModelRegistryModelRequest merged = new AiModelRegistryModelRequest();
                merged.setModelKey(request.getModelKey());
                merged.setDisplayName(request.getDisplayName());
                merged.setProviderType(request.getProviderType());
                merged.setModelName(request.getModelName());
                merged.setBaseUrl(request.getBaseUrl());
                merged.setEnabled(request.getEnabled());
                merged.setIsDefault(request.getIsDefault());
                merged.setDefaultTemperature(request.getDefaultTemperature());
                merged.setMaxTokens(request.getMaxTokens());
                merged.setTemperatureSpecJson(request.getTemperatureSpecJson());
                merged.setPromptBindings(request.getPromptBindings());

                AiModelRegistryModelVO existing = existingByModelKey.get(trimToNull(request.getModelKey()));
                // Older admin clients do not send protocol; preserve an existing explicit contract.
                merged.setProtocol(request.getProtocol() == null && existing != null
                    ? existing.getProtocol()
                    : request.getProtocol());
                // Capability metadata is additive; omission from an older client must not erase it.
                merged.setProviderCapabilities(request.getProviderCapabilities() == null && existing != null
                    ? copyProviderCapabilities(existing.getProviderCapabilities())
                    : copyProviderCapabilities(request.getProviderCapabilities()));
                String requestApiKey = trimToNull(request.getApiKey());
                if (configSecretService.isMaskedValue(requestApiKey)) {
                    merged.setApiKey(existing == null ? "" : existing.getApiKey());
                } else if (requestApiKey != null) {
                    merged.setApiKey(configSecretService.encryptIfNecessary(requestApiKey));
                } else if (existing != null && configSecretService.hasSecret(existing.getApiKey())) {
                    merged.setApiKey(existing.getApiKey());
                } else {
                    merged.setApiKey("");
                }
                return merged;
            })
            .toList();
    }

    private SystemConfigEntity normalizeStoredSecretConfig(String configKey, SystemConfigEntity entity) {
        if (!isSecretConfigKey(configKey) || entity == null) {
            return entity;
        }
        String configValue = entity.getConfigValue();
        if (!configSecretService.hasSecret(configValue) || configSecretService.isEncrypted(configValue)) {
            return entity;
        }
        entity.setConfigValue(configSecretService.encryptIfNecessary(configValue));
        return systemConfigRepository.saveOrUpdate(entity);
    }

    private SystemConfigEntity migratePlaintextModelSecrets(SystemConfigEntity entity) {
        AiModelRegistryVO registry = parseModelRegistry(entity.getConfigValue());
        boolean hasPlaintextSecret = registry.getModels().stream()
            .map(AiModelRegistryModelVO::getApiKey)
            .anyMatch(apiKey -> configSecretService.hasSecret(apiKey) && !configSecretService.isEncrypted(apiKey));
        if (!hasPlaintextSecret) {
            return entity;
        }

        List<AiModelRegistryModelRequest> migratedModels = registry.getModels().stream()
            .map(model -> {
                AiModelRegistryModelRequest request = new AiModelRegistryModelRequest();
                request.setModelKey(model.getModelKey());
                request.setDisplayName(model.getDisplayName());
                request.setProviderType(model.getProviderType());
                request.setProtocol(model.getProtocol());
                request.setProviderCapabilities(copyProviderCapabilities(model.getProviderCapabilities()));
                request.setModelName(model.getModelName());
                request.setBaseUrl(model.getBaseUrl());
                request.setApiKey(configSecretService.encryptIfNecessary(model.getApiKey()));
                request.setEnabled(model.getEnabled());
                request.setIsDefault(model.getIsDefault());
                request.setDefaultTemperature(model.getDefaultTemperature());
                request.setMaxTokens(model.getMaxTokens());
                request.setTemperatureSpecJson(model.getTemperatureSpecJson());
                request.setPromptBindings(model.getPromptBindings());
                return request;
            })
            .toList();
        entity.setConfigValue(writeJson(normalizeModelRegistry(
            registry.getDefaultModelKey(),
            migratedModels,
            registry.getProviderRoutingPolicy()
        )));
        return systemConfigRepository.saveOrUpdate(entity);
    }

    private AiProviderRoutingPolicy normalizeProviderRoutingPolicy(
        AiProviderRoutingPolicy requested,
        List<AiModelRegistryModelVO> models
    ) {
        AiProviderRoutingPolicy source = requested == null ? new AiProviderRoutingPolicy() : requested;
        int schemaVersion = source.getSchemaVersion() == null
            ? AiProviderRoutingPolicy.CURRENT_SCHEMA_VERSION
            : source.getSchemaVersion();
        if (schemaVersion != AiProviderRoutingPolicy.CURRENT_SCHEMA_VERSION) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported provider routing policy schema");
        }
        int cooldownSeconds = source.getCooldownSeconds() == null
            ? AiProviderRoutingPolicy.DEFAULT_COOLDOWN_SECONDS
            : source.getCooldownSeconds();
        if (cooldownSeconds < 30 || cooldownSeconds > 3600) {
            throw new BusinessException(
                ResultCode.BAD_REQUEST,
                "provider routing cooldownSeconds must be between 30 and 3600"
            );
        }

        List<String> orderedProfileKeys = new ArrayList<>();
        for (String rawKey : source.getOrderedProfileKeys() == null ? List.<String>of() : source.getOrderedProfileKeys()) {
            String profileKey = trimToNull(rawKey);
            if (profileKey == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "provider routing profile key is required");
            }
            orderedProfileKeys.add(profileKey);
        }
        if (orderedProfileKeys.stream().distinct().count() != orderedProfileKeys.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "provider routing profile keys must be unique");
        }

        // An omitted value means "walk the whole ordered list"; an explicit 0 keeps
        // routing pinned to the first key. The upper bound is the list length so a
        // long key chain is usable, not capped at a single hop.
        int maxFailovers = source.getMaxFailovers() == null
            ? Math.max(0, orderedProfileKeys.size() - 1)
            : source.getMaxFailovers();
        if (maxFailovers < 0 || maxFailovers > orderedProfileKeys.size()) {
            throw new BusinessException(
                ResultCode.BAD_REQUEST,
                "provider routing maxFailovers must be between 0 and the ordered profile count"
            );
        }

        boolean enabled = Boolean.TRUE.equals(source.getEnabled());
        if (enabled) {
            validateEnabledProviderRoutingPolicy(orderedProfileKeys, models);
        }

        AiProviderRoutingPolicy normalized = new AiProviderRoutingPolicy();
        normalized.setSchemaVersion(schemaVersion);
        normalized.setEnabled(enabled);
        normalized.setOrderedProfileKeys(orderedProfileKeys);
        normalized.setMaxFailovers(maxFailovers);
        normalized.setCooldownSeconds(cooldownSeconds);
        return normalized;
    }

    private void validateEnabledProviderRoutingPolicy(List<String> orderedProfileKeys,
                                                      List<AiModelRegistryModelVO> models) {
        if (orderedProfileKeys.size() < 2) {
            throw new BusinessException(
                ResultCode.BAD_REQUEST,
                "enabled provider routing requires at least two profiles"
            );
        }
        Map<String, AiModelRegistryModelVO> modelsByKey = new HashMap<>();
        for (AiModelRegistryModelVO model : models) {
            modelsByKey.put(model.getModelKey(), model);
        }
        AiModelRegistryModelVO baseline = null;
        for (String profileKey : orderedProfileKeys) {
            AiModelRegistryModelVO candidate = modelsByKey.get(profileKey);
            if (candidate == null || !Boolean.TRUE.equals(candidate.getEnabled())) {
                throw new BusinessException(
                    ResultCode.BAD_REQUEST,
                    "provider routing profile must exist and be enabled"
                );
            }
            if (!List.of("responses", "chat_completions").contains(candidate.getProtocol())) {
                throw new BusinessException(
                    ResultCode.BAD_REQUEST,
                    "provider routing profile protocol must be explicit"
                );
            }
            if (candidate.getProviderCapabilities() == null) {
                throw new BusinessException(
                    ResultCode.BAD_REQUEST,
                    "provider routing profile capabilities must be declared"
                );
            }
            if (baseline == null) {
                baseline = candidate;
                continue;
            }
            if (!java.util.Objects.equals(baseline.getProviderType(), candidate.getProviderType())
                || !java.util.Objects.equals(baseline.getProtocol(), candidate.getProtocol())
                || !providerCapabilitiesCanonical(baseline.getProviderCapabilities())
                    .equals(providerCapabilitiesCanonical(candidate.getProviderCapabilities()))) {
                throw new BusinessException(
                    ResultCode.BAD_REQUEST,
                    "provider routing profiles must have compatible provider type, protocol and capabilities"
                );
            }
        }
    }

    private String providerCapabilitiesCanonical(AiProviderCapabilities capabilities) {
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

    private AiProviderRoutingPolicy copyProviderRoutingPolicy(AiProviderRoutingPolicy policy) {
        return policy == null ? new AiProviderRoutingPolicy() : policy.copy();
    }

    private String prepareConfigValueForStorage(String configKey, String requestedValue, String existingValue) {
        if (!isSecretConfigKey(configKey)) {
            return requestedValue;
        }
        String normalized = trimToNull(requestedValue);
        if (normalized == null) {
            return "";
        }
        if (configSecretService.isMaskedValue(normalized)) {
            return existingValue == null ? "" : existingValue;
        }
        return configSecretService.encryptIfNecessary(normalized);
    }

    private boolean isSecretConfigKey(String configKey) {
        return configKey != null && SECRET_CONFIG_KEYS.contains(configKey);
    }

    private String writeJson(AiModelRegistryVO registry) {
        try {
            return objectMapper.writeValueAsString(registry);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "failed to write model registry config");
        }
    }

    private String buildDefaultTemperatureSpecJson(Double defaultTemperature) {
        double effectiveDefault = defaultTemperature == null ? 1.0 : defaultTemperature;
        return "{\"min\":0.0,\"max\":2.0,\"step\":0.1,\"default\":" + effectiveDefault + "}";
    }

    private String prettyModelName(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return "AI Model";
        }
        String[] parts = modelKey.split("[-_]");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.length() == 0 ? modelKey : builder.toString();
    }

    private String normalizeProtocol(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "unspecified";
        }
        normalized = normalized.toLowerCase().replace('-', '_');
        if (normalized.equals("chat") || normalized.equals("chat_completion")) {
            return "chat_completions";
        }
        if (normalized.equals("responses") || normalized.equals("chat_completions") || normalized.equals("unspecified")) {
            return normalized;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported AI model protocol");
    }

    private AiProviderCapabilities normalizeProviderCapabilities(AiProviderCapabilities capabilities) {
        if (capabilities == null) {
            return null;
        }
        if (!Integer.valueOf(AiProviderCapabilities.CURRENT_SCHEMA_VERSION)
            .equals(capabilities.getSchemaVersion())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported provider capabilities schema");
        }
        if (capabilities.getSupportsStreaming() == null
            || capabilities.getSupportsTools() == null
            || capabilities.getSupportsJsonObject() == null
            || capabilities.getSupportsReasoning() == null
            || capabilities.getReportsUsage() == null
            || capabilities.getReportsCacheUsage() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "provider capabilities v1 must be complete");
        }
        AiProviderCapabilities normalized = capabilities.copy();
        normalized.setPromptCache(normalizePromptCacheCapabilities(capabilities.getPromptCache()));
        return normalized;
    }

    private AiPromptCacheCapabilities normalizePromptCacheCapabilities(AiPromptCacheCapabilities promptCache) {
        if (promptCache == null) {
            return null;
        }
        String strategy = normalizePromptCacheValue(promptCache.getStrategy());
        String mode = normalizePromptCacheValue(promptCache.getMode());
        String retention = normalizePromptCacheValue(promptCache.getRetention());
        String breakpoint = normalizePromptCacheValue(promptCache.getBreakpoint());
        boolean valid = switch (strategy) {
            case "none" -> "disabled".equals(mode)
                && "provider_default".equals(retention)
                && "none".equals(breakpoint);
            case "deepseek_automatic" -> "provider_managed".equals(mode)
                && "provider_default".equals(retention)
                && "none".equals(breakpoint);
            case "openai_legacy" -> "implicit".equals(mode)
                && Set.of("provider_default", "in_memory", "24h").contains(retention)
                && "none".equals(breakpoint);
            case "openai_gpt_5_6" -> Set.of("implicit", "explicit").contains(mode)
                && Set.of("provider_default", "30m").contains(retention)
                && Set.of("none", "stable_prefix").contains(breakpoint);
            default -> false;
        };
        if (!valid) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "invalid Responses prompt cache capabilities");
        }
        AiPromptCacheCapabilities normalized = new AiPromptCacheCapabilities();
        normalized.setStrategy(strategy);
        normalized.setMode(mode);
        normalized.setRetention(retention);
        normalized.setBreakpoint(breakpoint);
        return normalized;
    }

    private void validateProviderPromptCacheProtocol(String protocol, AiProviderCapabilities capabilities) {
        AiPromptCacheCapabilities promptCache = capabilities == null ? null : capabilities.getPromptCache();
        if (promptCache != null
            && !"none".equals(promptCache.getStrategy())
            && !"responses".equals(protocol)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "prompt cache capabilities require Responses protocol");
        }
    }

    private String normalizePromptCacheValue(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? "" : normalized.toLowerCase().replace('-', '_');
    }

    private AiProviderCapabilities copyProviderCapabilities(AiProviderCapabilities capabilities) {
        return capabilities == null ? null : capabilities.copy();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record DefaultSystemConfig(String configValue, String configType, String description, boolean editable) {
    }
}
