package com.novelanalyzer.modules.config.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.config.dto.AiModelRegistrySaveRequest;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.AiModelOptionVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.config.vo.SystemConfigVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
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
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    @GetMapping("/system")
    @RequireRole({"ADMIN"})
    public Result<SystemConfigVO> getSystemConfig(@RequestParam("configKey") @NotBlank String configKey) {
        return Result.success(systemConfigService.getByKey(configKey));
    }

    @PutMapping("/system")
    @RequireRole({"ADMIN"})
    public Result<SystemConfigVO> updateSystemConfig(@Valid @RequestBody SystemConfigUpdateRequest request) {
        return Result.success(systemConfigService.save(request));
    }

    @GetMapping("/system/model-registry")
    @RequireRole({"ADMIN"})
    public Result<AiModelRegistryVO> getModelRegistry() {
        return Result.success(systemConfigService.getModelRegistry());
    }

    @PutMapping("/system/model-registry")
    @RequireRole({"ADMIN"})
    public Result<AiModelRegistryVO> updateModelRegistry(@Valid @RequestBody AiModelRegistrySaveRequest request) {
        return Result.success(systemConfigService.saveModelRegistry(request));
    }

    @GetMapping("/system/model-options")
    @RequireRole({"ADMIN", "USER"})
    public Result<List<AiModelOptionVO>> getModelOptions() {
        return Result.success(systemConfigService.getModelOptions());
    }

    @GetMapping("/system/available-models")
    @RequireRole({"ADMIN", "USER"})
    public Result<List<String>> getAvailableModels() {
        return Result.success(systemConfigService.getAvailableModels());
    }
}
