package com.novelanalyzer.modules.config.controller;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.modules.config.dto.AiModelRegistrySaveRequest;
import com.novelanalyzer.modules.config.dto.AiModelProviderProbeRequest;
import com.novelanalyzer.modules.config.dto.SystemConfigUpdateRequest;
import com.novelanalyzer.modules.config.service.ProviderTierService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.AiModelOptionVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.config.vo.ProviderTierVO;
import com.novelanalyzer.modules.config.vo.SystemConfigVO;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentProviderProbeService;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderProbeVO;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/config")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;
    private final KnowledgeAgentProviderProbeService providerProbeService;
    private final ProviderTierService providerTierService;

    public SystemConfigController(SystemConfigService systemConfigService,
                                  KnowledgeAgentProviderProbeService providerProbeService,
                                  ProviderTierService providerTierService) {
        this.systemConfigService = systemConfigService;
        this.providerProbeService = providerProbeService;
        this.providerTierService = providerTierService;
    }

    @GetMapping("/system")
    @RequireRole({"ADMIN"})
    public Result<SystemConfigVO> getSystemConfig(@RequestParam("configKey") @NotBlank String configKey) {
        return Result.success(systemConfigService.getByKey(configKey));
    }

    @GetMapping("/system/known")
    @RequireRole({"ADMIN"})
    public Result<List<SystemConfigVO>> getKnownSystemConfigs() {
        return Result.success(systemConfigService.getKnownConfigs());
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

    @PostMapping("/system/model-registry/probe")
    @RequireRole({"ADMIN"})
    public Result<AgentProviderProbeVO> probeModelProvider(
        @Valid @RequestBody AiModelProviderProbeRequest request,
        HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(providerProbeService.probe(request.getModelKey()));
    }

    @GetMapping("/system/model-options")
    @RequireRole({"ADMIN", "USER"})
    public Result<List<AiModelOptionVO>> getModelOptions() {
        List<AiModelOptionVO> options = systemConfigService.getModelOptions();
        // 档位来自 worker 的方言表，这里只做合并：worker 不可达时 tiers 为空，
        // 前端按「无档位」渲染（隐藏思考强度控件），模型列表本身照常可用。
        Map<String, ProviderTierVO> tiersByModelKey = providerTierService.resolveTiersByModelKey();
        for (AiModelOptionVO option : options) {
            ProviderTierVO tier = tiersByModelKey.get(option.getModelKey());
            if (tier == null) {
                continue;
            }
            option.setSupportsReasoning(tier.getSupportsReasoning());
            option.setReasoningTiers(tier.getReasoningTiers());
            // 用 worker 判定的族做前端分栏：注册表里绝大多数模型都填默认的
            // openai-compatible，只按 providerType 分组会挤成一栏。
            option.setProviderFamily(tier.getFamily());
        }
        return Result.success(options);
    }

    @GetMapping("/system/available-models")
    @RequireRole({"ADMIN", "USER"})
    public Result<List<String>> getAvailableModels() {
        return Result.success(systemConfigService.getAvailableModels());
    }
}
