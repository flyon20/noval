package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.PromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.config.vo.PromptConfigVO;
import org.springframework.stereotype.Service;

@Service
public class PromptConfigService {

    private static final String REQUIRED_CONTENT_PLACEHOLDER = "{{content}}";

    private final PromptConfigRepository promptConfigRepository;

    public PromptConfigService(PromptConfigRepository promptConfigRepository) {
        this.promptConfigRepository = promptConfigRepository;
    }

    public PromptConfigVO getByType(String promptType) {
        PromptConfigEntity entity = promptConfigRepository.findDefaultByType(promptType)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "prompt config not found"));
        return toVO(entity);
    }

    public PromptConfigVO save(PromptConfigUpdateRequest request) {
        validatePromptContent(request.getPromptContent());
        PromptConfigEntity entity = new PromptConfigEntity();
        entity.setPromptType(request.getPromptType());
        entity.setPromptName(request.getPromptName());
        entity.setPromptContent(request.getPromptContent());
        entity.setModelName(request.getModelName());
        entity.setTemperature(request.getTemperature());
        entity.setMaxTokens(request.getMaxTokens());
        Long id = promptConfigRepository.saveOrUpdate(entity);
        PromptConfigEntity updated = promptConfigRepository.findByTypeAndName(request.getPromptType(), request.getPromptName())
            .orElseThrow(() -> new BusinessException(ResultCode.INTERNAL_ERROR, "prompt config save failed"));
        updated.setId(id);
        return toVO(updated);
    }

    private void validatePromptContent(String promptContent) {
        if (promptContent == null || !promptContent.contains(REQUIRED_CONTENT_PLACEHOLDER)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "promptContent must contain {{content}} placeholder");
        }
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
        return vo;
    }
}
