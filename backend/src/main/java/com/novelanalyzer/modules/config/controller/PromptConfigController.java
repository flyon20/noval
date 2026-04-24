package com.novelanalyzer.modules.config.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.config.dto.PromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.service.PromptConfigService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.PromptConfigVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/config")
@RequireRole({"ADMIN", "USER"})
public class PromptConfigController {

    private final PromptConfigService promptConfigService;
    private final SystemConfigService systemConfigService;

    public PromptConfigController(PromptConfigService promptConfigService,
                                  SystemConfigService systemConfigService) {
        this.promptConfigService = promptConfigService;
        this.systemConfigService = systemConfigService;
    }

    @GetMapping("/prompt")
    public Result<PromptConfigVO> getPromptConfig(@RequestParam("promptType") @NotBlank String promptType,
                                                   @RequestParam(value = "promptName", required = false) String promptName) {
        return Result.success(promptConfigService.getByType(promptType, promptName));
    }

    @GetMapping("/prompt/templates")
    public Result<List<PromptConfigVO>> listPromptTemplates(@RequestParam("promptType") @NotBlank String promptType) {
        return Result.success(promptConfigService.listByType(promptType));
    }

    @PutMapping("/prompt")
    public Result<PromptConfigVO> updatePromptConfig(@Valid @RequestBody PromptConfigUpdateRequest request) {
        return Result.success(promptConfigService.save(request));
    }

    @DeleteMapping("/prompt")
    public Result<Void> deletePromptTemplate(@RequestParam("promptType") @NotBlank String promptType,
                                             @RequestParam("promptName") @NotBlank String promptName) {
        promptConfigService.deleteTemplate(promptType, promptName, systemConfigService.getModelRegistry().getModels());
        return Result.success();
    }
}

