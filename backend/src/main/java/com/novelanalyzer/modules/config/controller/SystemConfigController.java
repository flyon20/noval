package com.novelanalyzer.modules.config.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.service.SystemConfigService;
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

@Validated
@RestController
@RequestMapping("/api/config")
@RequireRole({"ADMIN"})
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    @GetMapping("/system")
    public Result<SystemConfigVO> getSystemConfig(@RequestParam("configKey") @NotBlank String configKey) {
        return Result.success(systemConfigService.getByKey(configKey));
    }

    @PutMapping("/system")
    public Result<SystemConfigVO> updateSystemConfig(@Valid @RequestBody SystemConfigUpdateRequest request) {
        return Result.success(systemConfigService.save(request));
    }
}
