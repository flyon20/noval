package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.AdminPromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.dto.PromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.PromptConfigVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PromptConfigService {

    private static final String REQUIRED_CONTENT_PLACEHOLDER = "{{content}}";
    private static final String DEFAULT_TEMPLATE_NAME = "default";

    private final PromptConfigRepository promptConfigRepository;
    private final DefaultPromptContractCatalog defaultPromptContractCatalog;

    public PromptConfigService(PromptConfigRepository promptConfigRepository,
                               DefaultPromptContractCatalog defaultPromptContractCatalog) {
        this.promptConfigRepository = promptConfigRepository;
        this.defaultPromptContractCatalog = defaultPromptContractCatalog;
    }

    public PromptConfigVO getByType(String promptType, String promptName) {
        PromptConfigEntity entity = resolveTemplateForRead(promptType, promptName)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
        return toVO(backfillMissingContractFields(entity));
    }

    public PromptConfigVO getByType(String promptType) {
        return getByType(promptType, null);
    }

    public List<PromptConfigVO> listByType(String promptType) {
        return promptConfigRepository.findActiveByType(promptType).stream()
            .map(this::backfillMissingContractFields)
            .map(this::toVO)
            .toList();
    }

    public PromptConfigVO save(PromptConfigUpdateRequest request) {
        if (isDefaultTemplateAlias(request.getPromptType(), request.getPromptName())) {
            return saveDefaultTemplate(request.getPromptType(), request);
        }

        AdminPromptConfigUpdateRequest adminRequest = new AdminPromptConfigUpdateRequest();
        adminRequest.setPromptType(request.getPromptType());
        adminRequest.setPromptName(request.getPromptName());
        adminRequest.setPromptContent(request.getPromptContent());
        adminRequest.setModelName(request.getModelName());
        adminRequest.setTemperature(request.getTemperature());
        adminRequest.setMaxTokens(request.getMaxTokens());
        adminRequest.setInputJsonSchema(request.getInputJsonSchema());
        adminRequest.setInputExampleJson(request.getInputExampleJson());
        adminRequest.setOutputJsonSchema(request.getOutputJsonSchema());
        adminRequest.setOutputExampleJson(request.getOutputExampleJson());
        adminRequest.setPostProcessType(request.getPostProcessType());
        adminRequest.setParseConfigJson(request.getParseConfigJson());
        return saveSystemTemplate(adminRequest);
    }

    public PromptConfigVO saveSystemTemplate(AdminPromptConfigUpdateRequest request) {
        validatePromptContent(request.getPromptContent());

        String effectivePromptName = isDefaultTemplateAlias(request.getPromptType(), request.getPromptName())
            ? resolveDefaultTemplateName(request.getPromptType())
            : normalizePromptName(request.getPromptName());
        boolean defaultTemplate = isDefaultTemplateAlias(request.getPromptType(), effectivePromptName);

        PromptConfigEntity entity = defaultTemplate
            ? resolveDefaultTemplate(request.getPromptType()).orElseGet(PromptConfigEntity::new)
            : promptConfigRepository.findByTypeAndName(request.getPromptType(), effectivePromptName)
                .orElseGet(PromptConfigEntity::new);

        entity.setPromptType(request.getPromptType());
        entity.setPromptName(effectivePromptName);
        entity.setScopeType(PromptGovernanceService.SCOPE_SYSTEM);
        entity.setOwnerUserId(null);
        entity.setSourcePromptConfigId(null);
        entity.setPromptContent(request.getPromptContent());
        entity.setModelName(request.getModelName());
        if (request.getTemperature() != null) {
            entity.setTemperature(request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            entity.setMaxTokens(request.getMaxTokens());
        }
        if (request.getInputJsonSchema() != null) {
            entity.setInputJsonSchema(request.getInputJsonSchema());
        }
        if (request.getInputExampleJson() != null) {
            entity.setInputExampleJson(request.getInputExampleJson());
        }
        if (request.getOutputJsonSchema() != null) {
            entity.setOutputJsonSchema(request.getOutputJsonSchema());
        }
        if (request.getOutputExampleJson() != null) {
            entity.setOutputExampleJson(request.getOutputExampleJson());
        }
        if (request.getPostProcessType() != null) {
            entity.setPostProcessType(request.getPostProcessType());
        }
        if (request.getParseConfigJson() != null) {
            entity.setParseConfigJson(request.getParseConfigJson());
        }
        entity.setStatus(1);
        entity.setIsDefault(defaultTemplate ? 1 : 0);

        Long id = promptConfigRepository.saveOrUpdate(entity);
        PromptConfigEntity saved = promptConfigRepository.findByTypeAndName(request.getPromptType(), effectivePromptName)
            .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "prompt config save failed"));
        saved.setId(id);
        return toVO(backfillMissingContractFields(saved));
    }

    public PromptConfigVO saveDefaultTemplate(String promptType, PromptConfigUpdateRequest request) {
        if (!isDefaultTemplateAlias(promptType, request.getPromptName())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "default template name cannot be changed");
        }
        AdminPromptConfigUpdateRequest adminRequest = new AdminPromptConfigUpdateRequest();
        adminRequest.setPromptType(promptType);
        adminRequest.setPromptName(resolveDefaultTemplateName(promptType));
        adminRequest.setPromptContent(request.getPromptContent());
        adminRequest.setModelName(request.getModelName());
        adminRequest.setTemperature(request.getTemperature());
        adminRequest.setMaxTokens(request.getMaxTokens());
        adminRequest.setInputJsonSchema(request.getInputJsonSchema());
        adminRequest.setInputExampleJson(request.getInputExampleJson());
        adminRequest.setOutputJsonSchema(request.getOutputJsonSchema());
        adminRequest.setOutputExampleJson(request.getOutputExampleJson());
        adminRequest.setPostProcessType(request.getPostProcessType());
        adminRequest.setParseConfigJson(request.getParseConfigJson());
        return saveSystemTemplate(adminRequest);
    }

    public void deleteTemplate(String promptType, String promptName, List<AiModelRegistryModelVO> models) {
        PromptConfigEntity entity = resolveTemplateForRead(promptType, promptName)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
        if (isDefaultTemplate(entity)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "default template cannot be deleted");
        }
        boolean bound = models != null && models.stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .map(AiModelRegistryModelVO::getPromptBindings)
            .filter(bindings -> bindings != null)
            .anyMatch(bindings -> promptName.equals(bindings.get(promptType)));
        if (bound) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "template is bound to model");
        }
        promptConfigRepository.softDeleteById(entity.getId());
    }

    public void backfillMissingDefaultContracts() {
        promptConfigRepository.findAllActive().forEach(this::backfillMissingContractFields);
    }

    public RuntimePromptResolution wrapRuntimePrompt(PromptConfigEntity promptConfig,
                                                     Long userId,
                                                     String promptType,
                                                     String selectedModelKey,
                                                     String effectiveSource,
                                                     Long activePublishVersionId,
                                                     boolean fallback) {
        return new RuntimePromptResolution(
            promptConfig,
            userId,
            promptType,
            selectedModelKey,
            promptConfig == null ? null : promptConfig.getId(),
            effectiveSource,
            activePublishVersionId,
            fallback
        );
    }

    public PromptConfigEntity findDefaultTemplateForInheritance(String promptType) {
        return resolveDefaultTemplate(promptType)
            .map(this::backfillMissingContractFields)
            .orElse(null);
    }

    public PromptConfigEntity mergeInheritedContractFields(PromptConfigEntity selected, PromptConfigEntity fallbackDefault) {
        if (selected == null || fallbackDefault == null) {
            return selected;
        }
        PromptConfigEntity merged = copyPromptConfig(selected);
        if (isBlank(merged.getInputJsonSchema())) {
            merged.setInputJsonSchema(fallbackDefault.getInputJsonSchema());
        }
        if (isBlank(merged.getInputExampleJson())) {
            merged.setInputExampleJson(fallbackDefault.getInputExampleJson());
        }
        if (isBlank(merged.getOutputJsonSchema())) {
            merged.setOutputJsonSchema(fallbackDefault.getOutputJsonSchema());
        }
        if (isBlank(merged.getOutputExampleJson())) {
            merged.setOutputExampleJson(fallbackDefault.getOutputExampleJson());
        }
        if (isBlank(merged.getPostProcessType())) {
            merged.setPostProcessType(fallbackDefault.getPostProcessType());
        }
        if (isBlank(merged.getParseConfigJson())) {
            merged.setParseConfigJson(fallbackDefault.getParseConfigJson());
        }
        return merged;
    }

    public PromptConfigEntity resolveRuntimeCompatiblePrompt(String promptType, PromptConfigEntity governancePrompt) {
        if (governancePrompt == null) {
            return null;
        }
        if (!PromptGovernanceService.SCOPE_SYSTEM.equals(governancePrompt.getScopeType())) {
            return governancePrompt;
        }
        if (governancePrompt.getPromptName() == null || !"default".equalsIgnoreCase(governancePrompt.getPromptName().trim())) {
            return governancePrompt;
        }
        PromptConfigEntity legacyDefault = promptConfigRepository.findActiveByTypeAndName(promptType, resolveDefaultTemplateName(promptType))
            .orElse(null);
        if (legacyDefault == null) {
            return governancePrompt;
        }
        LocalDateTime legacyUpdatedAt = legacyDefault.getUpdateTime();
        LocalDateTime governanceUpdatedAt = governancePrompt.getUpdateTime();
        if (legacyUpdatedAt == null || governanceUpdatedAt == null) {
            return governancePrompt;
        }
        return legacyUpdatedAt.isAfter(governanceUpdatedAt) ? legacyDefault : governancePrompt;
    }

    public PromptConfigEntity backfillMissingContractFields(PromptConfigEntity entity) {
        if (entity == null) {
            return null;
        }
        if (defaultPromptContractCatalog.applyMissingDefaults(entity)) {
            promptConfigRepository.saveOrUpdate(entity);
        }
        return entity;
    }

    private Optional<PromptConfigEntity> resolveTemplateForRead(String promptType, String promptName) {
        String normalizedPromptName = normalizePromptName(promptName);
        if (normalizedPromptName == null || isDefaultTemplateAlias(promptType, normalizedPromptName)) {
            return resolveDefaultTemplate(promptType);
        }
        return promptConfigRepository.findActiveByTypeAndName(promptType, normalizedPromptName);
    }

    private Optional<PromptConfigEntity> resolveDefaultTemplate(String promptType) {
        String legacyDefaultName = resolveDefaultTemplateName(promptType);
        return promptConfigRepository.findActiveByTypeAndName(promptType, legacyDefaultName)
            .or(() -> promptConfigRepository.findActiveByTypeAndName(promptType, DEFAULT_TEMPLATE_NAME))
            .or(() -> promptConfigRepository.findDefaultByType(promptType));
    }

    public String resolveDefaultTemplateName(String promptType) {
        if (promptType == null || promptType.isBlank()) {
            return DEFAULT_TEMPLATE_NAME;
        }
        return "default-" + promptType.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isDefaultTemplateAlias(String promptType, String promptName) {
        if (promptName == null || promptName.isBlank()) {
            return false;
        }
        String normalized = promptName.trim();
        return DEFAULT_TEMPLATE_NAME.equalsIgnoreCase(normalized)
            || resolveDefaultTemplateName(promptType).equalsIgnoreCase(normalized);
    }

    private String normalizePromptName(String promptName) {
        return promptName == null ? null : promptName.trim();
    }

    private void validatePromptContent(String promptContent) {
        if (promptContent == null || !promptContent.contains(REQUIRED_CONTENT_PLACEHOLDER)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "promptContent must contain {{content}} placeholder");
        }
    }

    private PromptConfigEntity copyPromptConfig(PromptConfigEntity source) {
        PromptConfigEntity target = new PromptConfigEntity();
        target.setId(source.getId());
        target.setPromptType(source.getPromptType());
        target.setPromptName(source.getPromptName());
        target.setScopeType(source.getScopeType());
        target.setOwnerUserId(source.getOwnerUserId());
        target.setSourcePromptConfigId(source.getSourcePromptConfigId());
        target.setPromptContent(source.getPromptContent());
        target.setModelName(source.getModelName());
        target.setTemperature(source.getTemperature());
        target.setMaxTokens(source.getMaxTokens());
        target.setStatus(source.getStatus());
        target.setIsDefault(source.getIsDefault());
        target.setDifyWorkflowId(source.getDifyWorkflowId());
        target.setDifyApiKeyRef(source.getDifyApiKeyRef());
        target.setInputJsonSchema(source.getInputJsonSchema());
        target.setInputExampleJson(source.getInputExampleJson());
        target.setOutputJsonSchema(source.getOutputJsonSchema());
        target.setOutputExampleJson(source.getOutputExampleJson());
        target.setPostProcessType(source.getPostProcessType());
        target.setParseConfigJson(source.getParseConfigJson());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        target.setDeleted(source.getDeleted());
        return target;
    }

    private PromptConfigVO toVO(PromptConfigEntity entity) {
        PromptConfigVO vo = new PromptConfigVO();
        vo.setId(entity.getId());
        vo.setPromptType(entity.getPromptType());
        vo.setPromptName(entity.getPromptName());
        vo.setPromptContent(entity.getPromptContent());
        vo.setModelName(entity.getModelName());
        vo.setTemperature(entity.getTemperature());
        vo.setMaxTokens(entity.getMaxTokens());
        vo.setIsDefault(isDefaultTemplate(entity));
        vo.setInputJsonSchema(entity.getInputJsonSchema());
        vo.setInputExampleJson(entity.getInputExampleJson());
        vo.setOutputJsonSchema(entity.getOutputJsonSchema());
        vo.setOutputExampleJson(entity.getOutputExampleJson());
        vo.setPostProcessType(entity.getPostProcessType());
        vo.setParseConfigJson(entity.getParseConfigJson());
        vo.setScopeType(entity.getScopeType());
        vo.setOwnerUserId(entity.getOwnerUserId());
        vo.setSourcePromptConfigId(entity.getSourcePromptConfigId());
        vo.setEditableScope(PromptGovernanceService.SCOPE_SYSTEM.equals(entity.getScopeType()) ? "ADMIN" : "USER");
        vo.setCreatedAt(entity.getCreateTime());
        vo.setUpdatedAt(entity.getUpdateTime());
        return vo;
    }

    private boolean isDefaultTemplate(PromptConfigEntity entity) {
        return entity != null && (
            (entity.getIsDefault() != null && entity.getIsDefault() == 1)
                || isDefaultTemplateAlias(entity.getPromptType(), entity.getPromptName())
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class RuntimePromptResolution {
        private final PromptConfigEntity promptConfig;
        private final Long userId;
        private final String promptType;
        private final String selectedModelKey;
        private final Long effectivePromptConfigId;
        private final String effectiveSource;
        private final Long activePublishVersionId;
        private final boolean fallback;

        public RuntimePromptResolution(PromptConfigEntity promptConfig,
                                       Long userId,
                                       String promptType,
                                       String selectedModelKey,
                                       Long effectivePromptConfigId,
                                       String effectiveSource,
                                       Long activePublishVersionId,
                                       boolean fallback) {
            this.promptConfig = promptConfig;
            this.userId = userId;
            this.promptType = promptType;
            this.selectedModelKey = selectedModelKey;
            this.effectivePromptConfigId = effectivePromptConfigId;
            this.effectiveSource = effectiveSource;
            this.activePublishVersionId = activePublishVersionId;
            this.fallback = fallback;
        }

        public PromptConfigEntity getPromptConfig() {
            return promptConfig;
        }

        public Long getUserId() {
            return userId;
        }

        public String getPromptType() {
            return promptType;
        }

        public String getSelectedModelKey() {
            return selectedModelKey;
        }

        public Long getEffectivePromptConfigId() {
            return effectivePromptConfigId;
        }

        public String getEffectiveSource() {
            return effectiveSource;
        }

        public Long getActivePublishVersionId() {
            return activePublishVersionId;
        }

        public boolean isFallback() {
            return fallback;
        }
    }
}
