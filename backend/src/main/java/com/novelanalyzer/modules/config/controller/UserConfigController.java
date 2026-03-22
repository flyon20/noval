package com.novelanalyzer.modules.config.controller;

import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.config.dto.UserConfigUpdateRequest;
import com.novelanalyzer.modules.config.service.UserConfigService;
import com.novelanalyzer.modules.config.vo.UserConfigVO;
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
@RequireRole({"ADMIN", "USER"})
public class UserConfigController {

    private final UserConfigService userConfigService;

    public UserConfigController(UserConfigService userConfigService) {
        this.userConfigService = userConfigService;
    }

    @GetMapping("/user")
    public Result<UserConfigVO> getUserConfig(@RequestParam("configKey") @NotBlank String configKey) {
        Long userId = AuthUserHolder.get().getUserId();
        return Result.success(userConfigService.getVO(userId, configKey));
    }

    @PutMapping("/user")
    public Result<UserConfigVO> updateUserConfig(@Valid @RequestBody UserConfigUpdateRequest request) {
        Long userId = AuthUserHolder.get().getUserId();
        return Result.success(userConfigService.setValueForUser(userId, request));
    }
}
