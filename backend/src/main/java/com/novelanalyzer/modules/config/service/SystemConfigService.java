package com.novelanalyzer.modules.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.AiModelRegistryModelRequest;
import com.novelanalyzer.modules.config.dto.AiModelRegistrySaveRequest;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import com.novelanalyzer.modules.config.repository.SystemConfigRepository;
import com.novelanalyzer.modules.config.vo.AiModelOptionVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.config.vo.SystemConfigVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SystemConfigService {

    private static final String MODEL_REGISTRY_CONFIG_KEY = "ai.model-registry.json";
    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";
    private static final String DEFAULT_MODEL_KEY = "deepseek-chat";
    private static final String DEFAULT_TEMPERATURE_SPEC_JSON = "{\"min\":0.0,\"max\":2.0,\"step\":0.1,\"default\":1.0}";

    private static final Map<String, DefaultSystemConfig> DEFAULT_SYSTEM_CONFIGS = Map.ofEntries(
        Map.entry("ai.provider.type", new DefaultSystemConfig("openai-compatible", "ai", "AI provider type", true)),
        Map.entry("ai.timeout.millis", new DefaultSystemConfig("15000", "ai", "AI request timeout in milliseconds", true)),
        Map.entry("analysis.runtime.mode", new DefaultSystemConfig("legacy", "analysis", "Analysis runtime mode: legacy or langgraph", true)),
        Map.entry("ai.openai-compatible.base-url", new DefaultSystemConfig("", "ai", "OpenAI compatible base URL, blank means fallback to application config", true)),
        Map.entry("ai.openai-compatible.default-model", new DefaultSystemConfig("deepseek-chat", "ai", "Default OpenAI compatible model name", true)),
        Map.entry("ai.openai-compatible.api-key", new DefaultSystemConfig("", "ai", "OpenAI compatible API key (stored in DB, takes precedence over env var)", true)),
        Map.entry("ai.openai-compatible.streaming-enabled", new DefaultSystemConfig("true", "ai", "Whether OpenAI compatible streaming is enabled", true)),
        Map.entry("ai.langgraph-worker.base-url", new DefaultSystemConfig("", "ai", "LangGraph worker base URL, blank means fallback to application config", true)),
        Map.entry("ai.langgraph-worker.internal-api-key", new DefaultSystemConfig("", "ai", "LangGraph worker internal service token", true)),
        Map.entry("ai.langgraph-worker.timeout-millis", new DefaultSystemConfig("30000", "ai", "LangGraph worker timeout in milliseconds", true)),
        Map.entry("ai.available-models", new DefaultSystemConfig("deepseek-chat", "ai", "Comma-separated list of available AI models for user selection", true)),
        Map.entry("crawler.default.chapter-count", new DefaultSystemConfig("3", "crawler", "Default crawler chapter count", true)),
        Map.entry("crawler.http.timeout-seconds", new DefaultSystemConfig("20", "crawler", "Python crawler page fetch timeout in seconds", true)),
        Map.entry("crawler.chapter.fetch-workers", new DefaultSystemConfig("3", "crawler", "Python crawler chapter fetch workers", true)),
        Map.entry("crawler.chapter.force-refresh.user-max-times", new DefaultSystemConfig("3", "crawler", "Maximum chapter force refresh times for normal users in current rank cache window", true)),
        Map.entry("crawler.rank.refresh-days", new DefaultSystemConfig("5", "crawler", "Rank refresh days", true)),
        Map.entry("crawler.rank.force-cooldown-days", new DefaultSystemConfig("2", "crawler", "Rank force refresh cooldown days", true)),
        Map.entry("crawler.rank.force-max-times", new DefaultSystemConfig("2", "crawler", "Rank force refresh max times", true)),
        Map.entry("crawler.book.refresh-days", new DefaultSystemConfig("7", "crawler", "Book refresh days", true)),
        Map.entry("analysis.reanalyze.cooldown-hours", new DefaultSystemConfig("0", "analysis", "Analysis reanalyze cooldown hours", true)),
        Map.entry("analysis.chunk.max-input-tokens", new DefaultSystemConfig("32000", "analysis", "Approximate max input tokens before analysis switches to chunk mode", true)),
        Map.entry("analysis.chunk.target-input-tokens", new DefaultSystemConfig("24000", "analysis", "Approximate target input tokens for each chunked analysis request", true)),
        Map.entry("analysis.chunk.parallelism", new DefaultSystemConfig("3", "analysis", "Maximum parallel chunk analysis requests", true)),
        Map.entry("security.audit.enabled", new DefaultSystemConfig("true", "security", "Whether audit logging is enabled", true))
    );

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;

    public SystemConfigService(SystemConfigRepository systemConfigRepository,
                               ObjectMapper objectMapper) {
        this.systemConfigRepository = systemConfigRepository;
        this.objectMapper = objectMapper;
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
        entity.setConfigValue(request.getConfigValue());
        entity.setConfigType(request.getConfigType());
        entity.setDescription(request.getDescription());
        SystemConfigEntity saved = systemConfigRepository.saveOrUpdate(entity);
        return toVO(saved);
    }

    public String getValueOrDefault(String configKey, String defaultValue) {
        return findOrCreateDefaultConfig(configKey)
            .map(SystemConfigEntity::getConfigValue)
            .filter(value -> value != null && !value.isBlank())
            .orElse(defaultValue);
    }

    public int getIntValueOrDefault(String configKey, int defaultValue) {
        return parseInteger(getValueOrDefault(configKey, null))
            .orElse(defaultValue);
    }

    public long getLongValueOrDefault(String configKey, long defaultValue) {
        return parseLong(getValueOrDefault(configKey, null))
            .orElse(defaultValue);
    }

    public boolean getBooleanValueOrDefault(String configKey, boolean defaultValue) {
        return parseBoolean(getValueOrDefault(configKey, null))
            .orElse(defaultValue);
    }

    public AiModelRegistryVO getModelRegistry() {
        return parseModelRegistry(getOrCreateModelRegistryEntity().getConfigValue());
    }

    public AiModelRegistryVO saveModelRegistry(AiModelRegistrySaveRequest request) {
        AiModelRegistryVO registry = normalizeModelRegistry(request.getDefaultModelKey(), request.getModels());
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setConfigKey(MODEL_REGISTRY_CONFIG_KEY);
        entity.setConfigType("ai");
        entity.setDescription("Structured AI model registry");
        entity.setConfigValue(writeJson(registry));
        systemConfigRepository.saveOrUpdate(entity);
        syncLegacyModelConfig(registry);
        return registry;
    }

    public List<AiModelOptionVO> getModelOptions() {
        return getModelRegistry().getModels().stream()
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
        AiModelRegistryVO registry = getModelRegistry();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Optional<AiModelRegistryModelVO> matched = registry.getModels().stream()
                .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
                .filter(model -> candidate.equals(model.getModelKey()) || candidate.equals(model.getModelName()))
                .findFirst();
            if (matched.isPresent()) {
                return matched;
            }
        }
        String defaultModelKey = registry.getDefaultModelKey();
        Optional<AiModelRegistryModelVO> defaultModel = registry.getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .filter(model -> defaultModelKey != null && defaultModelKey.equals(model.getModelKey()))
            .findFirst();
        if (defaultModel.isPresent()) {
            return defaultModel;
        }
        return registry.getModels().stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .findFirst();
    }

    private SystemConfigVO toVO(SystemConfigEntity entity) {
        SystemConfigVO vo = new SystemConfigVO();
        vo.setId(entity.getId());
        vo.setConfigKey(entity.getConfigKey());
        vo.setConfigValue(entity.getConfigValue());
        vo.setConfigType(entity.getConfigType());
        vo.setDescription(entity.getDescription());
        vo.setEditable(entity.getEditable() == null || entity.getEditable() == 1);
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
            return existing;
        }

        DefaultSystemConfig defaultConfig = DEFAULT_SYSTEM_CONFIGS.get(configKey);
        if (defaultConfig == null) {
            return Optional.empty();
        }

        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setConfigKey(configKey);
        entity.setConfigValue(defaultConfig.configValue());
        entity.setConfigType(defaultConfig.configType());
        entity.setDescription(defaultConfig.description());
        entity.setEditable(defaultConfig.editable() ? 1 : 0);
        return Optional.of(systemConfigRepository.saveOrUpdate(entity));
    }

    private SystemConfigEntity getOrCreateModelRegistryEntity() {
        return systemConfigRepository.findByKey(MODEL_REGISTRY_CONFIG_KEY)
            .filter(entity -> entity.getConfigValue() != null && !entity.getConfigValue().isBlank())
            .orElseGet(() -> {
                SystemConfigEntity entity = new SystemConfigEntity();
                entity.setConfigKey(MODEL_REGISTRY_CONFIG_KEY);
                entity.setConfigType("ai");
                entity.setDescription("Structured AI model registry");
                entity.setConfigValue(writeJson(buildDefaultModelRegistry()));
                entity.setEditable(1);
                return systemConfigRepository.saveOrUpdate(entity);
            });
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
        return normalizeModelRegistry(configuredDefaultModel, models);
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
                    request.setModelName(model.getModelName());
                    request.setBaseUrl(model.getBaseUrl());
                    request.setApiKey(model.getApiKey());
                    request.setEnabled(model.getEnabled());
                    request.setIsDefault(model.getIsDefault());
                    request.setDefaultTemperature(model.getDefaultTemperature());
                    request.setMaxTokens(model.getMaxTokens());
                    request.setTemperatureSpecJson(model.getTemperatureSpecJson());
                    requests.add(request);
                }
            }
            return normalizeModelRegistry(registry.getDefaultModelKey(), requests);
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "model registry config is invalid");
        }
    }

    private AiModelRegistryVO normalizeModelRegistry(String requestedDefaultModelKey,
                                                     List<AiModelRegistryModelRequest> modelRequests) {
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
                normalized.setModelName(model.getModelName());
                normalized.setBaseUrl(model.getBaseUrl());
                normalized.setApiKey(model.getApiKey());
                normalized.setEnabled(model.getEnabled());
                normalized.setIsDefault(defaultModelKey.equals(model.getModelKey()));
                normalized.setDefaultTemperature(model.getDefaultTemperature());
                normalized.setMaxTokens(model.getMaxTokens());
                normalized.setTemperatureSpecJson(model.getTemperatureSpecJson());
                return normalized;
            })
            .toList());
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
        model.setModelName(firstNonBlank(trimToNull(request.getModelName()), modelKey));
        model.setBaseUrl(trimToNull(request.getBaseUrl()));
        model.setApiKey(firstNonBlank(request.getApiKey(), ""));
        model.setEnabled(request.getEnabled() == null || request.getEnabled());
        model.setIsDefault(request.getIsDefault() != null && request.getIsDefault());
        model.setDefaultTemperature(request.getDefaultTemperature() == null ? 1.0 : request.getDefaultTemperature());
        model.setMaxTokens(request.getMaxTokens() == null ? 8192 : request.getMaxTokens());
        model.setTemperatureSpecJson(firstNonBlank(
            trimToNull(request.getTemperatureSpecJson()),
            buildDefaultTemperatureSpecJson(model.getDefaultTemperature())
        ));
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

        AiModelRegistryModelVO defaultModel = resolveEnabledModel(registry.getDefaultModelKey()).orElse(null);
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
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setConfigKey(configKey);
        entity.setConfigType("ai");
        entity.setDescription(description);
        entity.setConfigValue(configValue);
        systemConfigRepository.saveOrUpdate(entity);
    }

    private String getStoredConfigValue(String configKey) {
        return systemConfigRepository.findByKey(configKey)
            .map(SystemConfigEntity::getConfigValue)
            .map(this::trimToNull)
            .orElse(null);
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
