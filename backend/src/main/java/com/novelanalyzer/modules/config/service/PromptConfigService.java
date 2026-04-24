package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.PromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.PromptConfigVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PromptConfigService {

    private static final String REQUIRED_CONTENT_PLACEHOLDER = "{{content}}";
    private static final String DEFAULT_TEMPLATE_NAME = "default";
    private static final String DEEPSEEK_MODEL_KEY = "deepseek-chat";

    private final PromptConfigRepository promptConfigRepository;
    private final DefaultPromptContractCatalog defaultPromptContractCatalog;

    public PromptConfigService(PromptConfigRepository promptConfigRepository,
                               DefaultPromptContractCatalog defaultPromptContractCatalog) {
        this.promptConfigRepository = promptConfigRepository;
        this.defaultPromptContractCatalog = defaultPromptContractCatalog;
    }

    public PromptConfigVO getByType(String promptType, String promptName) {
        PromptConfigEntity entity = (promptName == null || promptName.isBlank())
            ? promptConfigRepository.findDefaultByType(promptType).orElse(null)
            : promptConfigRepository.findActiveByTypeAndName(promptType, promptName).orElse(null);
        if (entity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "prompt config not found");
        }
        return toVO(backfillMissingContractFields(entity));
    }

    public PromptConfigVO getByType(String promptType) {
        PromptConfigEntity entity = promptConfigRepository.findDefaultByType(promptType)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
        return toVO(backfillMissingContractFields(entity));
    }

    public List<PromptConfigVO> listByType(String promptType) {
        return promptConfigRepository.findActiveByType(promptType).stream()
            .map(this::backfillMissingContractFields)
            .map(this::toVO)
            .toList();
    }

    public PromptConfigEntity resolveRuntimePromptConfig(String promptType,
                                                         String preferredModelKey,
                                                         List<AiModelRegistryModelVO> models) {
        PromptConfigEntity selected = findBoundTemplate(promptType, preferredModelKey, models)
            .or(() -> findBoundTemplate(promptType, DEEPSEEK_MODEL_KEY, models))
            .or(() -> promptConfigRepository.findActiveByTypeAndName(promptType, DEFAULT_TEMPLATE_NAME))
            .orElseGet(() -> promptConfigRepository.findDefaultByType(promptType)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found")));

        PromptConfigEntity merged = backfillMissingContractFields(selected);
        if (DEFAULT_TEMPLATE_NAME.equalsIgnoreCase(selected.getPromptName())) {
            return merged;
        }
        PromptConfigEntity defaultTemplate = promptConfigRepository.findActiveByTypeAndName(promptType, DEFAULT_TEMPLATE_NAME)
            .map(this::backfillMissingContractFields)
            .orElse(null);
        return mergeInheritedContractFields(merged, defaultTemplate);
    }

    public PromptConfigVO save(PromptConfigUpdateRequest request) {
        validatePromptContent(request.getPromptContent());
        if (DEFAULT_TEMPLATE_NAME.equalsIgnoreCase(request.getPromptName())) {
            return saveDefaultTemplate(request.getPromptType(), request);
        }
        PromptConfigEntity entity = new PromptConfigEntity();
        entity.setPromptType(request.getPromptType());
        entity.setPromptName(request.getPromptName());
        entity.setPromptContent(request.getPromptContent());
        entity.setModelName(request.getModelName());
        entity.setTemperature(request.getTemperature());
        entity.setMaxTokens(request.getMaxTokens());
        entity.setInputJsonSchema(request.getInputJsonSchema());
        entity.setInputExampleJson(request.getInputExampleJson());
        entity.setOutputJsonSchema(request.getOutputJsonSchema());
        entity.setOutputExampleJson(request.getOutputExampleJson());
        entity.setPostProcessType(request.getPostProcessType());
        entity.setParseConfigJson(request.getParseConfigJson());
        Long id = promptConfigRepository.saveOrUpdate(entity);
        PromptConfigEntity updated = promptConfigRepository.findByTypeAndName(request.getPromptType(), request.getPromptName())
            .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "prompt config save failed"));
        updated.setId(id);
        return toVO(updated);
    }

    public PromptConfigVO saveDefaultTemplate(String promptType, PromptConfigUpdateRequest request) {
        if (!DEFAULT_TEMPLATE_NAME.equalsIgnoreCase(request.getPromptName())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "default template name cannot be changed");
        }
        PromptConfigEntity existingDefault = promptConfigRepository.findActiveByTypeAndName(promptType, DEFAULT_TEMPLATE_NAME)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));

        PromptConfigEntity entity = copyPromptConfig(existingDefault);
        entity.setPromptContent(request.getPromptContent());
        entity.setModelName(request.getModelName());
        entity.setTemperature(request.getTemperature());
        entity.setMaxTokens(request.getMaxTokens());
        entity.setInputJsonSchema(request.getInputJsonSchema());
        entity.setInputExampleJson(request.getInputExampleJson());
        entity.setOutputJsonSchema(request.getOutputJsonSchema());
        entity.setOutputExampleJson(request.getOutputExampleJson());
        entity.setPostProcessType(request.getPostProcessType());
        entity.setParseConfigJson(request.getParseConfigJson());
        promptConfigRepository.saveOrUpdate(entity);
        return toVO(backfillMissingContractFields(
            promptConfigRepository.findActiveByTypeAndName(promptType, DEFAULT_TEMPLATE_NAME)
                .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "prompt config save failed"))
        ));
    }

    public void deleteTemplate(String promptType, String promptName, List<AiModelRegistryModelVO> models) {
        PromptConfigEntity entity = promptConfigRepository.findActiveByTypeAndName(promptType, promptName)
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

    private Optional<PromptConfigEntity> findBoundTemplate(String promptType,
                                                           String modelKey,
                                                           List<AiModelRegistryModelVO> models) {
        if (modelKey == null || modelKey.isBlank() || models == null || models.isEmpty()) {
            return Optional.empty();
        }
        return models.stream()
            .filter(model -> Boolean.TRUE.equals(model.getEnabled()))
            .filter(model -> modelKey.equals(model.getModelKey()))
            .findFirst()
            .map(AiModelRegistryModelVO::getPromptBindings)
            .map(bindings -> bindings == null ? null : bindings.get(promptType))
            .filter(templateName -> templateName != null && !templateName.isBlank())
            .flatMap(templateName -> promptConfigRepository.findActiveByTypeAndName(promptType, templateName));
    }

    private void validatePromptContent(String promptContent) {
        if (promptContent == null || !promptContent.contains(REQUIRED_CONTENT_PLACEHOLDER)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "promptContent must contain {{content}} placeholder");
        }
    }

    private PromptConfigEntity backfillMissingContractFields(PromptConfigEntity entity) {
        if (entity == null) {
            return null;
        }
        if (defaultPromptContractCatalog.applyMissingDefaults(entity)) {
            promptConfigRepository.saveOrUpdate(entity);
        }
        return entity;
    }

    private PromptConfigEntity mergeInheritedContractFields(PromptConfigEntity selected, PromptConfigEntity fallbackDefault) {
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

    private PromptConfigEntity copyPromptConfig(PromptConfigEntity source) {
        PromptConfigEntity target = new PromptConfigEntity();
        target.setId(source.getId());
        target.setPromptType(source.getPromptType());
        target.setPromptName(source.getPromptName());
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
        return vo;
    }

    private boolean isDefaultTemplate(PromptConfigEntity entity) {
        return entity != null && entity.getIsDefault() != null && entity.getIsDefault() == 1;
    }
}
