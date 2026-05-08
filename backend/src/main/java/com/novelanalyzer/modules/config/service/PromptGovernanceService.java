package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.AdminPromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.dto.PromptPublishRequest;
import com.novelanalyzer.modules.config.dto.UserPromptBindingUpdateRequest;
import com.novelanalyzer.modules.config.dto.UserPromptCopyCreateRequest;
import com.novelanalyzer.modules.config.dto.UserPromptCopyUpdateRequest;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.model.PromptPublishItemEntity;
import com.novelanalyzer.modules.config.model.PromptPublishVersionEntity;
import com.novelanalyzer.modules.config.model.UserPromptBindingEntity;
import com.novelanalyzer.modules.config.model.UserPromptEffectiveHistoryEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.config.repository.PromptPublishRepository;
import com.novelanalyzer.modules.config.repository.UserPromptBindingRepository;
import com.novelanalyzer.modules.config.repository.UserPromptEffectiveHistoryRepository;
import com.novelanalyzer.modules.config.vo.PromptConfigVO;
import com.novelanalyzer.modules.config.vo.PromptPublishVersionVO;
import com.novelanalyzer.modules.config.vo.UserPromptBindingVO;
import com.novelanalyzer.modules.config.vo.UserPromptEffectiveHistoryVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PromptGovernanceService {

    public static final String SCOPE_SYSTEM = "SYSTEM";
    public static final String SCOPE_USER_COPY = "USER_COPY";
    public static final String BINDING_MODE_GLOBAL = "GLOBAL";
    public static final String BINDING_MODE_USER_COPY = "USER_COPY";
    public static final String EFFECTIVE_SOURCE_GLOBAL_PUBLISHED = "GLOBAL_PUBLISHED";
    public static final String EFFECTIVE_SOURCE_USER_COPY = "USER_COPY";
    public static final String EFFECTIVE_SOURCE_USER_COPY_FALLBACK_TO_GLOBAL = "USER_COPY_FALLBACK_TO_GLOBAL";
    public static final String EFFECTIVE_SOURCE_SYSTEM_DEFAULT_FALLBACK = "SYSTEM_DEFAULT_FALLBACK";
    private static final String DEFAULT_TEMPLATE_NAME = "default";

    private final PromptConfigRepository promptConfigRepository;
    private final PromptPublishRepository promptPublishRepository;
    private final UserPromptBindingRepository userPromptBindingRepository;
    private final UserPromptEffectiveHistoryRepository userPromptEffectiveHistoryRepository;

    public PromptGovernanceService(PromptConfigRepository promptConfigRepository,
                                   PromptPublishRepository promptPublishRepository,
                                   UserPromptBindingRepository userPromptBindingRepository,
                                   UserPromptEffectiveHistoryRepository userPromptEffectiveHistoryRepository) {
        this.promptConfigRepository = promptConfigRepository;
        this.promptPublishRepository = promptPublishRepository;
        this.userPromptBindingRepository = userPromptBindingRepository;
        this.userPromptEffectiveHistoryRepository = userPromptEffectiveHistoryRepository;
    }

    public List<PromptConfigVO> listPublishedSystemTemplates(String promptType) {
        PublishedPromptVersion currentVersion = getCurrentPublishedPromptVersion()
            .orElse(null);
        List<Long> publishedIds = currentVersion == null
            ? List.of()
            : currentVersion.items().stream()
                .map(PromptPublishItemEntity::getPromptConfigId)
                .toList();
        return promptConfigRepository.findAllActive().stream()
            .filter(entity -> promptType.equals(entity.getPromptType()))
            .filter(entity -> SCOPE_SYSTEM.equals(entity.getScopeType()))
            .map(entity -> toPromptConfigVO(entity, publishedIds.contains(entity.getId()), "ADMIN"))
            .toList();
    }

    public PromptConfigVO getSystemTemplate(String promptType, String promptName) {
        PromptConfigEntity entity = resolveSystemTemplate(promptType, promptName)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
        boolean published = getCurrentPublishedPromptVersion()
            .map(version -> version.items().stream().anyMatch(item -> entity.getId().equals(item.getPromptConfigId())))
            .orElse(false);
        return toPromptConfigVO(entity, published, "ADMIN");
    }

    @Transactional
    public PromptConfigVO saveSystemTemplate(AdminPromptConfigUpdateRequest request) {
        validatePromptContent(request.getPromptContent());
        String effectivePromptName = isDefaultTemplateAlias(request.getPromptType(), request.getPromptName())
            ? DEFAULT_TEMPLATE_NAME
            : normalizePromptName(request.getPromptName());
        boolean defaultTemplate = isDefaultTemplateAlias(request.getPromptType(), effectivePromptName);

        PromptConfigEntity entity = defaultTemplate
            ? resolveSystemTemplate(request.getPromptType(), effectivePromptName).orElseGet(PromptConfigEntity::new)
            : promptConfigRepository.findByTypeAndName(request.getPromptType(), effectivePromptName)
                .orElseGet(PromptConfigEntity::new);
        entity.setPromptType(request.getPromptType());
        entity.setPromptName(effectivePromptName);
        entity.setScopeType(SCOPE_SYSTEM);
        entity.setOwnerUserId(null);
        entity.setSourcePromptConfigId(null);
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
        entity.setStatus(1);
        entity.setIsDefault(defaultTemplate ? 1 : 0);

        Long id = promptConfigRepository.saveOrUpdate(entity);
        PromptConfigEntity saved = promptConfigRepository.findByTypeAndName(request.getPromptType(), effectivePromptName)
            .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "prompt config save failed"));
        saved.setId(id);
        boolean published = getCurrentPublishedPromptVersion()
            .map(version -> version.items().stream().anyMatch(item -> id.equals(item.getPromptConfigId())))
            .orElse(false);
        return toPromptConfigVO(saved, published, "ADMIN");
    }

    @Transactional
    public void deleteSystemTemplate(String promptType, String promptName) {
        PromptConfigEntity entity = resolveSystemTemplate(promptType, promptName)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
        if (isDefaultTemplate(entity)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "default template cannot be deleted");
        }
        promptConfigRepository.softDeleteById(entity.getId());
    }

    @Transactional
    public PromptPublishVersionVO publish(PromptPublishRequest request, Long publishedBy) {
        if (request.getSelections() == null || request.getSelections().size() != 4) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "publish request must contain four prompt type selections");
        }

        List<String> requiredTypes = List.of("deconstruct", "structure", "plot", "theme");
        for (String promptType : requiredTypes) {
            boolean present = request.getSelections().stream().anyMatch(item -> promptType.equals(item.getPromptType()));
            if (!present) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "missing promptType selection: " + promptType);
            }
        }

        long nextVersionNo = promptPublishRepository.findLatestVersion()
            .map(PromptPublishVersionEntity::getVersionNo)
            .orElse(0L) + 1L;

        PromptPublishVersionEntity version = new PromptPublishVersionEntity();
        version.setVersionNo(nextVersionNo);
        version.setPublishedBy(publishedBy);
        version.setPublishNote(request.getPublishNote());
        Long versionId = promptPublishRepository.saveVersion(version);

        List<PromptPublishVersionVO.PromptPublishItemVO> itemVos = new ArrayList<>();
        for (PromptPublishRequest.PromptPublishSelectionItem selection : request.getSelections()) {
            PromptConfigEntity entity = promptConfigRepository.findByTypeAndName(selection.getPromptType(), selection.getPromptName())
                .filter(item -> SCOPE_SYSTEM.equals(item.getScopeType()))
                .filter(item -> selection.getPromptConfigId().equals(item.getId()))
                .orElseThrow(() -> new BusinessException(ResultCode.BAD_REQUEST, "invalid publish selection for " + selection.getPromptType()));

            PromptPublishItemEntity publishItem = new PromptPublishItemEntity();
            publishItem.setPublishVersionId(versionId);
            publishItem.setPromptType(selection.getPromptType());
            publishItem.setPromptConfigId(entity.getId());
            publishItem.setPromptName(entity.getPromptName());
            promptPublishRepository.saveItem(publishItem);

            PromptPublishVersionVO.PromptPublishItemVO itemVO = new PromptPublishVersionVO.PromptPublishItemVO();
            itemVO.setPromptType(selection.getPromptType());
            itemVO.setPromptConfigId(entity.getId());
            itemVO.setPromptName(entity.getPromptName());
            itemVos.add(itemVO);
        }

        PromptPublishVersionVO vo = new PromptPublishVersionVO();
        vo.setId(versionId);
        vo.setVersionNo(nextVersionNo);
        vo.setPublishedBy(publishedBy);
        vo.setPublishNote(request.getPublishNote());
        vo.setCreatedAt(LocalDateTime.now());
        vo.setItems(itemVos.stream()
            .sorted(Comparator.comparing(PromptPublishVersionVO.PromptPublishItemVO::getPromptType))
            .toList());
        return vo;
    }

    public PromptPublishVersionVO getCurrentPublishedVersion() {
        PublishedPromptVersion version = getCurrentPublishedPromptVersion()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt publish version not found"));
        return toPublishVersionVO(version);
    }

    public List<PromptPublishVersionVO> listPublishHistory() {
        return promptPublishRepository.findAllVersions().stream()
            .map(version -> new PublishedPromptVersion(version, promptPublishRepository.findItemsByVersionId(version.getId())))
            .map(this::toPublishVersionVO)
            .toList();
    }

    public UserPromptTemplatesResponse getUserTemplates(Long userId, String promptType) {
        PublishedPromptVersion version = getCurrentPublishedPromptVersion()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt publish version not found"));
        List<Long> publishedIds = version.items().stream()
            .map(PromptPublishItemEntity::getPromptConfigId)
            .toList();

        List<PromptConfigVO> globalTemplates = promptConfigRepository.findAllActive().stream()
            .filter(entity -> promptType.equals(entity.getPromptType()))
            .filter(entity -> SCOPE_SYSTEM.equals(entity.getScopeType()))
            .filter(entity -> publishedIds.contains(entity.getId()))
            .map(entity -> toPromptConfigVO(entity, true, "GLOBAL"))
            .toList();

        List<PromptConfigVO> personalCopies = promptConfigRepository.findAllActive().stream()
            .filter(entity -> promptType.equals(entity.getPromptType()))
            .filter(entity -> SCOPE_USER_COPY.equals(entity.getScopeType()))
            .filter(entity -> userId.equals(entity.getOwnerUserId()))
            .map(entity -> toPromptConfigVO(entity, false, "USER"))
            .toList();

        UserPromptTemplatesResponse response = new UserPromptTemplatesResponse();
        response.setGlobalTemplates(globalTemplates);
        response.setPersonalCopies(personalCopies);
        response.setPublishedGlobalTemplateId(version.findItem(promptType).map(PromptPublishItemEntity::getPromptConfigId).orElse(null));
        return response;
    }

    @Transactional
    public PromptConfigVO createUserCopy(Long userId, UserPromptCopyCreateRequest request) {
        PromptConfigEntity source = promptConfigRepository.findAllActive().stream()
            .filter(entity -> entity.getId().equals(request.getSourcePromptConfigId()))
            .filter(entity -> request.getPromptType().equals(entity.getPromptType()))
            .filter(entity -> SCOPE_SYSTEM.equals(entity.getScopeType()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "source prompt template not found"));

        PromptConfigEntity copy = new PromptConfigEntity();
        copy.setPromptType(source.getPromptType());
        copy.setPromptName((request.getCopyName() == null || request.getCopyName().isBlank())
            ? ("copy-" + System.currentTimeMillis())
            : request.getCopyName().trim());
        copy.setScopeType(SCOPE_USER_COPY);
        copy.setOwnerUserId(userId);
        copy.setSourcePromptConfigId(source.getId());
        copy.setPromptContent(source.getPromptContent());
        copy.setModelName(source.getModelName());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setInputJsonSchema(source.getInputJsonSchema());
        copy.setInputExampleJson(source.getInputExampleJson());
        copy.setOutputJsonSchema(source.getOutputJsonSchema());
        copy.setOutputExampleJson(source.getOutputExampleJson());
        copy.setPostProcessType(source.getPostProcessType());
        copy.setParseConfigJson(source.getParseConfigJson());
        copy.setStatus(1);
        copy.setIsDefault(0);

        Long id = promptConfigRepository.saveOrUpdate(copy);
        PromptConfigEntity saved = promptConfigRepository.findByTypeAndName(copy.getPromptType(), copy.getPromptName())
            .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "user prompt copy save failed"));
        saved.setId(id);
        return toPromptConfigVO(saved, false, "USER");
    }

    @Transactional
    public PromptConfigVO updateUserCopy(Long userId, UserPromptCopyUpdateRequest request) {
        validatePromptContent(request.getPromptContent());
        PromptConfigEntity entity = promptConfigRepository.findAllActive().stream()
            .filter(item -> item.getId().equals(request.getPromptConfigId()))
            .filter(item -> request.getPromptType().equals(item.getPromptType()))
            .filter(item -> SCOPE_USER_COPY.equals(item.getScopeType()))
            .filter(item -> userId.equals(item.getOwnerUserId()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "user prompt copy not found"));

        entity.setPromptName(request.getPromptName().trim());
        entity.setPromptContent(request.getPromptContent());
        entity.setModelName(request.getModelName().trim());
        entity.setTemperature(request.getTemperature());
        entity.setMaxTokens(request.getMaxTokens());
        promptConfigRepository.saveOrUpdate(entity);
        return toPromptConfigVO(entity, false, "USER");
    }

    @Transactional
    public void deleteUserCopy(Long userId, String promptType, Long promptConfigId) {
        PromptConfigEntity entity = promptConfigRepository.findAllActive().stream()
            .filter(item -> item.getId().equals(promptConfigId))
            .filter(item -> promptType.equals(item.getPromptType()))
            .filter(item -> SCOPE_USER_COPY.equals(item.getScopeType()))
            .filter(item -> userId.equals(item.getOwnerUserId()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "user prompt copy not found"));
        promptConfigRepository.softDeleteById(entity.getId());
    }

    public UserPromptBindingVO getUserBinding(Long userId, String promptType, String selectedModelKey) {
        Resolution resolution = resolveEffectivePrompt(userId, promptType, selectedModelKey);
        return toUserBindingVO(resolution.binding(), resolution);
    }

    @Transactional
    public UserPromptBindingVO updateUserBinding(Long userId, UserPromptBindingUpdateRequest request, String selectedModelKey) {
        UserPromptBindingEntity binding = userPromptBindingRepository.findActiveBinding(userId, request.getPromptType())
            .orElseGet(UserPromptBindingEntity::new);
        binding.setUserId(userId);
        binding.setPromptType(request.getPromptType());
        binding.setBindingMode(request.getBindingMode());
        binding.setBoundPromptConfigId(BINDING_MODE_USER_COPY.equals(request.getBindingMode()) ? request.getBoundPromptConfigId() : null);
        binding.setLastSelectedPromptConfigId(binding.getBoundPromptConfigId());
        binding.setStatus(1);
        userPromptBindingRepository.saveOrUpdate(binding);

        Resolution resolution = resolveEffectivePrompt(userId, request.getPromptType(), selectedModelKey);
        return toUserBindingVO(resolution.binding(), resolution);
    }

    public List<UserPromptEffectiveHistoryVO> getUserEffectiveHistory(Long userId, String promptType) {
        return userPromptEffectiveHistoryRepository.findByUserIdAndPromptType(userId, promptType).stream()
            .map(this::toUserPromptEffectiveHistoryVO)
            .toList();
    }

    public Resolution resolveEffectivePrompt(Long userId, String promptType, String selectedModelKey) {
        PublishedPromptVersion published = getCurrentPublishedPromptVersion()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt publish version not found"));
        PromptPublishItemEntity publishedItem = published.findItem(promptType)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "published prompt item not found"));
        PromptConfigEntity publishedPrompt = promptConfigRepository.findAllActive().stream()
            .filter(entity -> entity.getId().equals(publishedItem.getPromptConfigId()))
            .findFirst()
            .orElseGet(() -> resolveSystemDefault(promptType));

        UserPromptBindingEntity binding = userPromptBindingRepository.findActiveBinding(userId, promptType)
            .orElse(null);
        if (binding == null || BINDING_MODE_GLOBAL.equals(binding.getBindingMode())) {
            recordEffectiveHistory(userId, promptType, binding, publishedPrompt.getId(), EFFECTIVE_SOURCE_GLOBAL_PUBLISHED,
                published.version().getId(), selectedModelKey, false);
            return new Resolution(publishedPrompt, binding, EFFECTIVE_SOURCE_GLOBAL_PUBLISHED, published.version().getId(), false, null);
        }

        PromptConfigEntity userCopy = promptConfigRepository.findAllActive().stream()
            .filter(entity -> entity.getId().equals(binding.getBoundPromptConfigId()))
            .filter(entity -> SCOPE_USER_COPY.equals(entity.getScopeType()))
            .filter(entity -> userId.equals(entity.getOwnerUserId()))
            .findFirst()
            .orElse(null);

        if (userCopy != null) {
            recordEffectiveHistory(userId, promptType, binding, userCopy.getId(), EFFECTIVE_SOURCE_USER_COPY,
                published.version().getId(), selectedModelKey, false);
            return new Resolution(userCopy, binding, EFFECTIVE_SOURCE_USER_COPY, published.version().getId(), false, null);
        }

        String fallbackWarning = "当前个人模板已失效，已自动回退到最新全局模板";
        if (binding != null) {
            binding.setEffectivePromptConfigId(publishedPrompt.getId());
            binding.setPublishVersionId(published.version().getId());
            binding.setFallbackWarning(fallbackWarning);
            userPromptBindingRepository.saveOrUpdate(binding);
        }
        recordEffectiveHistory(userId, promptType, binding, publishedPrompt.getId(), EFFECTIVE_SOURCE_USER_COPY_FALLBACK_TO_GLOBAL,
            published.version().getId(), selectedModelKey, true);
        return new Resolution(publishedPrompt, binding, EFFECTIVE_SOURCE_USER_COPY_FALLBACK_TO_GLOBAL,
            published.version().getId(), true, fallbackWarning);
    }

    public PromptConfigEntity resolveSystemDefault(String promptType) {
        return resolveSystemTemplate(promptType, null)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "default prompt config not found"));
    }

    private Optional<PromptConfigEntity> resolveSystemTemplate(String promptType, String promptName) {
        String normalizedPromptName = normalizePromptName(promptName);
        if (normalizedPromptName == null || isDefaultTemplateAlias(promptType, normalizedPromptName)) {
            return promptConfigRepository.findActiveByTypeAndName(promptType, DEFAULT_TEMPLATE_NAME)
                .filter(item -> SCOPE_SYSTEM.equals(item.getScopeType()))
                .or(() -> promptConfigRepository.findActiveByTypeAndName(promptType, resolveDefaultTemplateName(promptType))
                    .filter(item -> SCOPE_SYSTEM.equals(item.getScopeType())));
        }
        return promptConfigRepository.findActiveByTypeAndName(promptType, normalizedPromptName)
            .filter(item -> SCOPE_SYSTEM.equals(item.getScopeType()));
    }

    private String resolveDefaultTemplateName(String promptType) {
        if (promptType == null || promptType.isBlank()) {
            return DEFAULT_TEMPLATE_NAME;
        }
        return DEFAULT_TEMPLATE_NAME + "-" + promptType.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDefaultTemplateAlias(String promptType, String promptName) {
        if (promptName == null || promptName.isBlank()) {
            return false;
        }
        String normalized = promptName.trim();
        return DEFAULT_TEMPLATE_NAME.equalsIgnoreCase(normalized)
            || resolveDefaultTemplateName(promptType).equalsIgnoreCase(normalized);
    }

    private boolean isDefaultTemplate(PromptConfigEntity entity) {
        return entity != null && (
            (entity.getIsDefault() != null && entity.getIsDefault() == 1)
                || isDefaultTemplateAlias(entity.getPromptType(), entity.getPromptName())
        );
    }

    private String normalizePromptName(String promptName) {
        return promptName == null ? null : promptName.trim();
    }

    public Optional<PublishedPromptVersion> getCurrentPublishedPromptVersion() {
        return promptPublishRepository.findLatestVersion()
            .map(version -> new PublishedPromptVersion(version, promptPublishRepository.findItemsByVersionId(version.getId())));
    }

    private void recordEffectiveHistory(Long userId,
                                        String promptType,
                                        UserPromptBindingEntity currentBinding,
                                        Long effectivePromptConfigId,
                                        String effectiveSource,
                                        Long publishVersionId,
                                        String selectedModelKey,
                                        boolean fallback) {
        Long previousEffectivePromptConfigId = userPromptEffectiveHistoryRepository.findLatestByUserIdAndPromptType(userId, promptType)
            .map(UserPromptEffectiveHistoryEntity::getEffectivePromptConfigId)
            .orElse(null);

        UserPromptEffectiveHistoryEntity history = new UserPromptEffectiveHistoryEntity();
        history.setUserId(userId);
        history.setPromptType(promptType);
        history.setPublishVersionId(publishVersionId);
        history.setBindingMode(currentBinding == null ? BINDING_MODE_GLOBAL : currentBinding.getBindingMode());
        history.setBoundPromptConfigId(currentBinding == null ? null : currentBinding.getBoundPromptConfigId());
        history.setEffectivePromptConfigId(effectivePromptConfigId);
        history.setEffectiveSource(effectiveSource);
        history.setPreviousEffectivePromptConfigId(previousEffectivePromptConfigId);
        history.setSelectedModelKey(selectedModelKey);
        history.setFallback(fallback ? 1 : 0);
        userPromptEffectiveHistoryRepository.save(history);
    }

    private PromptPublishVersionVO toPublishVersionVO(PublishedPromptVersion publishedPromptVersion) {
        PromptPublishVersionVO vo = new PromptPublishVersionVO();
        vo.setId(publishedPromptVersion.version().getId());
        vo.setVersionNo(publishedPromptVersion.version().getVersionNo());
        vo.setPublishedBy(publishedPromptVersion.version().getPublishedBy());
        vo.setPublishNote(publishedPromptVersion.version().getPublishNote());
        vo.setCreatedAt(publishedPromptVersion.version().getCreateTime());
        List<PromptPublishVersionVO.PromptPublishItemVO> items = publishedPromptVersion.items().stream()
            .map(item -> {
                PromptPublishVersionVO.PromptPublishItemVO itemVO = new PromptPublishVersionVO.PromptPublishItemVO();
                itemVO.setPromptType(item.getPromptType());
                itemVO.setPromptConfigId(item.getPromptConfigId());
                itemVO.setPromptName(item.getPromptName());
                return itemVO;
            })
            .sorted(Comparator.comparing(PromptPublishVersionVO.PromptPublishItemVO::getPromptType))
            .toList();
        vo.setItems(items);
        return vo;
    }

    private PromptConfigVO toPromptConfigVO(PromptConfigEntity entity, boolean published, String editableScope) {
        PromptConfigVO vo = new PromptConfigVO();
        vo.setId(entity.getId());
        vo.setPromptType(entity.getPromptType());
        vo.setPromptName(entity.getPromptName());
        vo.setPromptContent(entity.getPromptContent());
        vo.setModelName(entity.getModelName());
        vo.setTemperature(entity.getTemperature());
        vo.setMaxTokens(entity.getMaxTokens());
        vo.setIsDefault(entity.getIsDefault() != null && entity.getIsDefault() == 1);
        vo.setInputJsonSchema(entity.getInputJsonSchema());
        vo.setInputExampleJson(entity.getInputExampleJson());
        vo.setOutputJsonSchema(entity.getOutputJsonSchema());
        vo.setOutputExampleJson(entity.getOutputExampleJson());
        vo.setPostProcessType(entity.getPostProcessType());
        vo.setParseConfigJson(entity.getParseConfigJson());
        vo.setScopeType(entity.getScopeType());
        vo.setOwnerUserId(entity.getOwnerUserId());
        vo.setSourcePromptConfigId(entity.getSourcePromptConfigId());
        vo.setIsPublished(published);
        vo.setEditableScope(editableScope);
        vo.setCreatedAt(entity.getCreateTime());
        vo.setUpdatedAt(entity.getUpdateTime());
        return vo;
    }

    private UserPromptBindingVO toUserBindingVO(UserPromptBindingEntity binding, Resolution resolution) {
        UserPromptBindingVO vo = new UserPromptBindingVO();
        vo.setPromptType(resolution.promptConfig().getPromptType());
        vo.setBindingMode(binding == null ? BINDING_MODE_GLOBAL : binding.getBindingMode());
        vo.setBoundPromptConfigId(binding == null ? null : binding.getBoundPromptConfigId());
        vo.setLastSelectedPromptConfigId(binding == null ? null : binding.getLastSelectedPromptConfigId());
        vo.setEffectivePromptConfigId(resolution.promptConfig().getId());
        vo.setEffectiveSource(resolution.effectiveSource());
        vo.setFallbackWarning(resolution.fallbackWarning());
        return vo;
    }

    private UserPromptEffectiveHistoryVO toUserPromptEffectiveHistoryVO(UserPromptEffectiveHistoryEntity entity) {
        UserPromptEffectiveHistoryVO vo = new UserPromptEffectiveHistoryVO();
        vo.setId(entity.getId());
        vo.setPromptType(entity.getPromptType());
        vo.setEffectivePromptConfigId(entity.getEffectivePromptConfigId());
        vo.setEffectiveSource(entity.getEffectiveSource());
        vo.setPublishVersionId(entity.getPublishVersionId());
        vo.setFallback(entity.getFallback() != null && entity.getFallback() == 1);
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    private void validatePromptContent(String promptContent) {
        if (promptContent == null || !promptContent.contains("{{content}}")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "promptContent must contain {{content}} placeholder");
        }
    }

    public record PublishedPromptVersion(PromptPublishVersionEntity version, List<PromptPublishItemEntity> items) {
        public Optional<PromptPublishItemEntity> findItem(String promptType) {
            return items.stream()
                .filter(item -> promptType.equals(item.getPromptType()))
                .findFirst();
        }
    }

    public record Resolution(PromptConfigEntity promptConfig,
                             UserPromptBindingEntity binding,
                             String effectiveSource,
                             Long publishVersionId,
                             boolean fallback,
                             String fallbackWarning) {
    }

    public static class UserPromptTemplatesResponse {
        private List<PromptConfigVO> globalTemplates = new ArrayList<>();
        private List<PromptConfigVO> personalCopies = new ArrayList<>();
        private Long publishedGlobalTemplateId;

        public List<PromptConfigVO> getGlobalTemplates() {
            return globalTemplates;
        }

        public void setGlobalTemplates(List<PromptConfigVO> globalTemplates) {
            this.globalTemplates = globalTemplates;
        }

        public List<PromptConfigVO> getPersonalCopies() {
            return personalCopies;
        }

        public void setPersonalCopies(List<PromptConfigVO> personalCopies) {
            this.personalCopies = personalCopies;
        }

        public Long getPublishedGlobalTemplateId() {
            return publishedGlobalTemplateId;
        }

        public void setPublishedGlobalTemplateId(Long publishedGlobalTemplateId) {
            this.publishedGlobalTemplateId = publishedGlobalTemplateId;
        }
    }
}
