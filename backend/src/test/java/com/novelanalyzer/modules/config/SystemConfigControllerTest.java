package com.novelanalyzer.modules.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.config.controller.SystemConfigController;
import com.novelanalyzer.modules.config.service.ProviderTierService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.AiModelOptionVO;
import com.novelanalyzer.modules.config.vo.ProviderTierVO;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentProviderProbeService;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderProbeVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemConfigControllerTest {

    @Test
    void shouldProbeSavedModelProfileWithoutCachingResponse() throws Exception {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        KnowledgeAgentProviderProbeService probeService = mock(KnowledgeAgentProviderProbeService.class);
        AgentProviderProbeVO probe = new AgentProviderProbeVO();
        probe.setStatus("SUCCEEDED");
        probe.setProfileKey("gateway-primary");
        probe.setProfileVersion("version-1");
        probe.setEndpointFingerprint("endpoint-sha256");
        probe.setModel("deepseek-chat");
        probe.setProtocol("responses");
        probe.setLatencyMillis(25L);
        probe.setUsageReported(true);
        probe.setCacheUsageReported(false);
        when(probeService.probe("gateway-primary")).thenReturn(probe);
        ProviderTierService providerTierService = mock(ProviderTierService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new SystemConfigController(systemConfigService, probeService, providerTierService)
        ).build();

        mockMvc.perform(post("/api/config/system/model-registry/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "modelKey", "gateway-primary"
                ))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.data.profileKey").value("gateway-primary"))
            .andExpect(jsonPath("$.data.protocol").value("responses"))
            .andExpect(jsonPath("$.data.apiKey").doesNotExist())
            .andExpect(jsonPath("$.data.content").doesNotExist());

        verify(probeService).probe("gateway-primary");
    }

    @Test
    void shouldMergeWorkerReasoningTiersAndFamilyIntoModelOptions() throws Exception {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        KnowledgeAgentProviderProbeService probeService = mock(KnowledgeAgentProviderProbeService.class);
        ProviderTierService providerTierService = mock(ProviderTierService.class);

        AiModelOptionVO kimi = new AiModelOptionVO();
        kimi.setModelKey("kimi-main");
        kimi.setDisplayName("Kimi K3");
        // 注册表里填的是默认的 openai-compatible，族要由 worker 判定后覆盖。
        kimi.setProviderType("openai-compatible");
        AiModelOptionVO claude = new AiModelOptionVO();
        claude.setModelKey("claude-main");
        claude.setDisplayName("Claude Sonnet");
        claude.setProviderType("anthropic");
        when(systemConfigService.getModelOptions()).thenReturn(java.util.List.of(kimi, claude));

        ProviderTierVO kimiTier = new ProviderTierVO();
        kimiTier.setModelKey("kimi-main");
        kimiTier.setFamily("moonshot");
        kimiTier.setSupportsReasoning(true);
        kimiTier.setReasoningTiers(java.util.List.of("low", "high", "max"));
        when(providerTierService.resolveTiersByModelKey())
            .thenReturn(java.util.Map.of("kimi-main", kimiTier));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new SystemConfigController(systemConfigService, probeService, providerTierService)
        ).build();

        mockMvc.perform(get("/api/config/system/model-options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].modelKey").value("kimi-main"))
            .andExpect(jsonPath("$.data[0].providerFamily").value("moonshot"))
            .andExpect(jsonPath("$.data[0].supportsReasoning").value(true))
            .andExpect(jsonPath("$.data[0].reasoningTiers").value(org.hamcrest.Matchers.contains("low", "high", "max")))
            // worker 没给档位的模型保持空档位，前端据此隐藏思考强度控件。
            .andExpect(jsonPath("$.data[1].modelKey").value("claude-main"))
            .andExpect(jsonPath("$.data[1].supportsReasoning").doesNotExist())
            .andExpect(jsonPath("$.data[1].providerFamily").doesNotExist());
    }
}
