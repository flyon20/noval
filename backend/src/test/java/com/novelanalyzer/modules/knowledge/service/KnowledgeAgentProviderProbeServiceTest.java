package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderProbeVO;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentRuntimeConfigVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAgentProviderProbeServiceTest {

    @Test
    void shouldProbeExactSavedProfileIdentity() {
        KnowledgeAgentGovernanceService governanceService = mock(KnowledgeAgentGovernanceService.class);
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        when(governanceService.runtimeConfig()).thenReturn(runtimeConfig(profile(true)));

        AgentProviderProbeVO workerResult = probeResult("gateway-primary", "version-1", "SUCCEEDED");
        when(workerClient.probeAgentProvider("gateway-primary", "version-1")).thenReturn(workerResult);
        KnowledgeAgentProviderProbeService service = new KnowledgeAgentProviderProbeService(
            governanceService,
            workerClient
        );

        AgentProviderProbeVO result = service.probe(" gateway-primary ");

        assertThat(result).isSameAs(workerResult);
        verify(workerClient).probeAgentProvider("gateway-primary", "version-1");
    }

    @Test
    void shouldRejectMissingSavedCredentialBeforeWorkerCall() {
        KnowledgeAgentGovernanceService governanceService = mock(KnowledgeAgentGovernanceService.class);
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        when(governanceService.runtimeConfig()).thenReturn(runtimeConfig(profile(false)));
        KnowledgeAgentProviderProbeService service = new KnowledgeAgentProviderProbeService(
            governanceService,
            workerClient
        );

        assertThatThrownBy(() -> service.probe("gateway-primary"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("credential");
        verify(workerClient, never()).probeAgentProvider("gateway-primary", "version-1");
    }

    @Test
    void shouldFailClosedWhenWorkerReturnsDifferentProfileIdentity() {
        KnowledgeAgentGovernanceService governanceService = mock(KnowledgeAgentGovernanceService.class);
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        when(governanceService.runtimeConfig()).thenReturn(runtimeConfig(profile(true)));
        when(workerClient.probeAgentProvider("gateway-primary", "version-1"))
            .thenReturn(probeResult("other-profile", "version-2", "SUCCEEDED"));
        KnowledgeAgentProviderProbeService service = new KnowledgeAgentProviderProbeService(
            governanceService,
            workerClient
        );

        assertThatThrownBy(() -> service.probe("gateway-primary"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("identity");
    }

    private static AgentRuntimeConfigVO runtimeConfig(AgentProviderProfileVO profile) {
        AgentRuntimeConfigVO config = new AgentRuntimeConfigVO();
        config.setProviderProfiles(List.of(profile));
        return config;
    }

    private static AgentProviderProfileVO profile(boolean credentialConfigured) {
        AgentProviderProfileVO profile = new AgentProviderProfileVO();
        profile.setProfileKey("gateway-primary");
        profile.setProfileVersion("version-1");
        profile.setEndpoint("https://api.deepseek.com/v1");
        profile.setModel("deepseek-chat");
        profile.setProviderType("openai-compatible");
        profile.setProtocol("responses");
        profile.setEnabled(true);
        profile.setApiKeyConfigured(credentialConfigured);
        return profile;
    }

    private static AgentProviderProbeVO probeResult(String profileKey, String profileVersion, String status) {
        AgentProviderProbeVO result = new AgentProviderProbeVO();
        result.setStatus(status);
        result.setProfileKey(profileKey);
        result.setProfileVersion(profileVersion);
        result.setEndpointFingerprint("endpoint-sha256");
        result.setModel("deepseek-chat");
        result.setProtocol("responses");
        result.setLatencyMillis(25L);
        result.setUsageReported(true);
        result.setCacheUsageReported(false);
        return result;
    }
}
