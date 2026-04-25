package com.novelanalyzer.modules.config.controller;

import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.config.dto.AdminPromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.dto.PromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.dto.PromptPublishRequest;
import com.novelanalyzer.modules.config.dto.UserPromptBindingUpdateRequest;
import com.novelanalyzer.modules.config.dto.UserPromptCopyCreateRequest;
import com.novelanalyzer.modules.config.dto.UserPromptCopyUpdateRequest;
import com.novelanalyzer.modules.config.service.PromptConfigService;
import com.novelanalyzer.modules.config.service.PromptGovernanceService;
import com.novelanalyzer.modules.config.vo.PromptConfigVO;
import com.novelanalyzer.modules.config.vo.PromptPublishVersionVO;
import com.novelanalyzer.modules.config.vo.UserPromptBindingVO;
import com.novelanalyzer.modules.config.vo.UserPromptEffectiveHistoryVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/config")
public class PromptConfigController {

    private final PromptConfigService promptConfigService;
    private final PromptGovernanceService promptGovernanceService;

    public PromptConfigController(PromptConfigService promptConfigService,
                                  PromptGovernanceService promptGovernanceService) {
        this.promptConfigService = promptConfigService;
        this.promptGovernanceService = promptGovernanceService;
    }

    // Legacy compatibility endpoints

    @GetMapping("/prompt")
    @RequireRole({"ADMIN", "USER"})
    public Result<PromptConfigVO> getPromptConfig(@RequestParam("promptType") @NotBlank String promptType,
                                                  @RequestParam(value = "promptName", required = false) String promptName) {
        return Result.success(promptConfigService.getByType(promptType, promptName));
    }

    @GetMapping("/prompt/templates")
    @RequireRole({"ADMIN", "USER"})
    public Result<List<PromptConfigVO>> listPromptTemplates(@RequestParam("promptType") @NotBlank String promptType) {
        return Result.success(promptConfigService.listByType(promptType));
    }

    @PutMapping("/prompt")
    @RequireRole({"ADMIN"})
    public Result<PromptConfigVO> updatePromptConfig(@Valid @RequestBody PromptConfigUpdateRequest request) {
        return Result.success(promptConfigService.save(request));
    }

    @DeleteMapping("/prompt")
    @RequireRole({"ADMIN"})
    public Result<Void> deletePromptTemplate(@RequestParam("promptType") @NotBlank String promptType,
                                             @RequestParam("promptName") @NotBlank String promptName) {
        promptConfigService.deleteTemplate(promptType, promptName, null);
        return Result.success();
    }

    // Admin governance endpoints

    @GetMapping("/prompt/system/template")
    @RequireRole({"ADMIN"})
    public Result<PromptConfigVO> getSystemTemplate(@RequestParam("promptType") @NotBlank String promptType,
                                                    @RequestParam(value = "promptName", required = false) String promptName) {
        return Result.success(promptGovernanceService.getSystemTemplate(promptType, promptName == null || promptName.isBlank() ? "default" : promptName));
    }

    @GetMapping("/prompt/system/templates")
    @RequireRole({"ADMIN"})
    public Result<List<PromptConfigVO>> listSystemTemplates(@RequestParam("promptType") @NotBlank String promptType) {
        return Result.success(promptGovernanceService.listPublishedSystemTemplates(promptType));
    }

    @PutMapping("/prompt/system")
    @RequireRole({"ADMIN"})
    public Result<PromptConfigVO> updateSystemTemplate(@Valid @RequestBody AdminPromptConfigUpdateRequest request) {
        return Result.success(promptGovernanceService.saveSystemTemplate(request));
    }

    @DeleteMapping("/prompt/system")
    @RequireRole({"ADMIN"})
    public Result<Void> deleteSystemTemplate(@RequestParam("promptType") @NotBlank String promptType,
                                             @RequestParam("promptName") @NotBlank String promptName) {
        promptGovernanceService.deleteSystemTemplate(promptType, promptName);
        return Result.success();
    }

    @PostMapping("/prompt/system/publish")
    @RequireRole({"ADMIN"})
    public Result<PromptPublishVersionVO> publishSystemDraft(@Valid @RequestBody PromptPublishRequest request) {
        return Result.success(promptGovernanceService.publish(request, currentUserId()));
    }

    @GetMapping("/prompt/system/publish/current")
    @RequireRole({"ADMIN"})
    public Result<PromptPublishVersionVO> getCurrentPublishedVersion() {
        return Result.success(promptGovernanceService.getCurrentPublishedVersion());
    }

    @GetMapping("/prompt/system/publish/history")
    @RequireRole({"ADMIN"})
    public Result<List<PromptPublishVersionVO>> listPublishHistory() {
        return Result.success(promptGovernanceService.listPublishHistory());
    }

    // User endpoints

    @GetMapping("/prompt/user/templates")
    @RequireRole({"ADMIN", "USER"})
    public Result<PromptGovernanceService.UserPromptTemplatesResponse> getUserTemplates(@RequestParam("promptType") @NotBlank String promptType) {
        return Result.success(promptGovernanceService.getUserTemplates(currentUserId(), promptType));
    }

    @PostMapping("/prompt/user/copy-from-global")
    @RequireRole({"ADMIN", "USER"})
    public Result<PromptConfigVO> copyGlobalToUser(@Valid @RequestBody UserPromptCopyCreateRequest request) {
        return Result.success(promptGovernanceService.createUserCopy(currentUserId(), request));
    }

    @PutMapping("/prompt/user/template")
    @RequireRole({"ADMIN", "USER"})
    public Result<PromptConfigVO> updateUserTemplate(@Valid @RequestBody UserPromptCopyUpdateRequest request) {
        return Result.success(promptGovernanceService.updateUserCopy(currentUserId(), request));
    }

    @DeleteMapping("/prompt/user/template")
    @RequireRole({"ADMIN", "USER"})
    public Result<Void> deleteUserTemplate(@RequestParam("promptType") @NotBlank String promptType,
                                           @RequestParam("promptConfigId") @NotNull Long promptConfigId) {
        promptGovernanceService.deleteUserCopy(currentUserId(), promptType, promptConfigId);
        return Result.success();
    }

    @GetMapping("/prompt/user/binding")
    @RequireRole({"ADMIN", "USER"})
    public Result<UserPromptBindingVO> getUserBinding(@RequestParam("promptType") @NotBlank String promptType) {
        return Result.success(promptGovernanceService.getUserBinding(currentUserId(), promptType, resolveSelectedModelKey()));
    }

    @PutMapping("/prompt/user/binding")
    @RequireRole({"ADMIN", "USER"})
    public Result<UserPromptBindingVO> updateUserBinding(@Valid @RequestBody UserPromptBindingUpdateRequest request) {
        return Result.success(promptGovernanceService.updateUserBinding(currentUserId(), request, resolveSelectedModelKey()));
    }

    @GetMapping("/prompt/user/effective-history")
    @RequireRole({"ADMIN", "USER"})
    public Result<List<UserPromptEffectiveHistoryVO>> getUserEffectiveHistory(@RequestParam("promptType") @NotBlank String promptType) {
        return Result.success(promptGovernanceService.getUserEffectiveHistory(currentUserId(), promptType));
    }

    private Long currentUserId() {
        return AuthUserHolder.get() == null ? null : AuthUserHolder.get().getUserId();
    }

    private String resolveSelectedModelKey() {
        return null;
    }
}
