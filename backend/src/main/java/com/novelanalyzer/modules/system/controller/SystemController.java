package com.novelanalyzer.modules.system.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import com.novelanalyzer.modules.system.service.AuthPublicConfigService;
import com.novelanalyzer.modules.system.service.LoginBootstrapService;
import com.novelanalyzer.modules.system.vo.AuthPublicConfigVO;
import com.novelanalyzer.modules.system.vo.LoginBootstrapVO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final LoginBootstrapService loginBootstrapService;
    private final AuthPublicConfigService authPublicConfigService;

    public SystemController(LoginBootstrapService loginBootstrapService,
                            AuthPublicConfigService authPublicConfigService) {
        this.loginBootstrapService = loginBootstrapService;
        this.authPublicConfigService = authPublicConfigService;
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", "novel-analyzer-backend");
        return Result.success(data);
    }

    @GetMapping("/auth-public-config")
    public Result<AuthPublicConfigVO> authPublicConfig() {
        return Result.success(authPublicConfigService.getPublicConfig());
    }

    @PostMapping("/login-bootstrap")
    @RequireRole({"ADMIN", "USER"})
    public Result<LoginBootstrapVO> loginBootstrap(@RequestParam(value = "platform", defaultValue = "fanqie") @NotBlank String platform) {
        return Result.success(loginBootstrapService.bootstrap(platform));
    }
}
