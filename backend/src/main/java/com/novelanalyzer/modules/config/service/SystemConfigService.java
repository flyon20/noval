package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import com.novelanalyzer.modules.config.repository.SystemConfigRepository;
import com.novelanalyzer.modules.config.vo.SystemConfigVO;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SystemConfigService {

    private final SystemConfigRepository systemConfigRepository;

    public SystemConfigService(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
    }

    public SystemConfigVO getByKey(String configKey) {
        SystemConfigEntity entity = systemConfigRepository.findByKey(configKey)
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
        return systemConfigRepository.findByKey(configKey)
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
}
