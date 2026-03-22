package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import com.novelanalyzer.modules.config.repository.SystemConfigRepository;
import com.novelanalyzer.modules.config.vo.SystemConfigVO;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SystemConfigService {

    private static final Map<String, DefaultSystemConfig> DEFAULT_SYSTEM_CONFIGS = Map.ofEntries(
        Map.entry("ai.provider.type", new DefaultSystemConfig("openai-compatible", "ai", "AI provider type", true)),
        Map.entry("ai.timeout.millis", new DefaultSystemConfig("15000", "ai", "AI request timeout in milliseconds", true)),
        Map.entry("ai.openai-compatible.base-url", new DefaultSystemConfig("", "ai", "OpenAI compatible base URL, blank means fallback to application config", true)),
        Map.entry("ai.openai-compatible.default-model", new DefaultSystemConfig("deepseek-chat", "ai", "Default OpenAI compatible model name", true)),
        Map.entry("ai.openai-compatible.api-key", new DefaultSystemConfig("", "ai", "OpenAI compatible API key (stored in DB, takes precedence over env var)", true)),
        Map.entry("ai.openai-compatible.streaming-enabled", new DefaultSystemConfig("false", "ai", "Whether OpenAI compatible streaming is enabled", true)),
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
        Map.entry("analysis.chunk.max-input-tokens", new DefaultSystemConfig("6000", "analysis", "Approximate max input tokens before analysis switches to chunk mode", true)),
        Map.entry("analysis.chunk.target-input-tokens", new DefaultSystemConfig("3500", "analysis", "Approximate target input tokens for each chunked analysis request", true)),
        Map.entry("security.audit.enabled", new DefaultSystemConfig("true", "security", "Whether audit logging is enabled", true))
    );

    private final SystemConfigRepository systemConfigRepository;

    public SystemConfigService(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
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

    public List<String> getAvailableModels() {
        String value = getValueOrDefault("ai.available-models", "");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
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

    private record DefaultSystemConfig(String configValue, String configType, String description, boolean editable) {
    }
}
