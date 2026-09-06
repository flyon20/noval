package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.model.AiProviderCapabilities;
import com.novelanalyzer.modules.config.model.AiPromptCacheCapabilities;
import com.novelanalyzer.modules.config.model.AiProviderRoutingPolicy;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import com.novelanalyzer.modules.config.dto.AiModelRegistryModelRequest;
import com.novelanalyzer.modules.config.dto.AiModelRegistrySaveRequest;
import com.novelanalyzer.modules.config.repository.SystemConfigRepository;
import com.novelanalyzer.modules.config.service.ConfigSecretService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.knowledge.dto.AgentExpertProfileUpdateRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentProviderRoutingOutcomeRequest;
import com.novelanalyzer.modules.knowledge.dto.AgentRuntimeConfigUpdateRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentGovernanceService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeAgentProviderCircuitShadowService;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderCircuitStateVO;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentExpertProfileVO;
import com.novelanalyzer.modules.knowledge.vo.AgentCacheTokenStatsVO;
import com.novelanalyzer.modules.knowledge.vo.AgentRuntimeConfigVO;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderRuntimeVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAgentGovernanceServiceTest {

    @Test
    void shouldReturnDefaultRuntimePolicy() {
        KnowledgeAgentGovernanceService service = newService();

        AgentRuntimeConfigVO config = service.runtimeConfig();

        assertThat(config.getReasoningModeDefault()).isEqualTo("fast");
        assertThat(config.getMaxParallelSpecialists()).isEqualTo(1);
        assertThat(config.getMaxTotalInputTokens()).isEqualTo(300_000);
        assertThat(config.getMaxFinalOutputTokensFast()).isZero();
        assertThat(config.getMaxFinalOutputTokensDeep()).isZero();
        assertThat(config.getMaxPromptCharsPerExpert()).isZero();
        assertThat(config.getMaxSkillPromptChars()).isZero();
        assertThat(config.getMaxEvidenceItems()).isEqualTo(30);
        assertThat(config.getEnableIntentCache()).isTrue();
        assertThat(config.getEnableSpecialistCache()).isFalse();
        assertThat(config.getSpecialistMcpEnabled()).isFalse();
    }

    @Test
    void shouldExposeStableSecretFreeResponsesProviderProfiles() throws Exception {
        SystemConfigService systemConfigService = newSystemConfigService();
        AiModelRegistryModelRequest enabled = new AiModelRegistryModelRequest();
        enabled.setModelKey("gateway-deepseek");
        enabled.setProviderType("openai-compatible");
        enabled.setProtocol("responses");
        enabled.setProviderCapabilities(providerCapabilities(true));
        enabled.setModelName("deepseek-v4-pro");
        enabled.setBaseUrl("https://gateway.example/v1");
        enabled.setApiKey("test-provider-key-never-project");
        enabled.setEnabled(true);
        enabled.setIsDefault(true);
        AiModelRegistryModelRequest disabled = new AiModelRegistryModelRequest();
        disabled.setModelKey("disabled-profile");
        disabled.setProtocol("responses");
        disabled.setModelName("disabled-model");
        disabled.setEnabled(false);
        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("gateway-deepseek");
        saveRequest.setModels(List.of(enabled, disabled));
        systemConfigService.saveModelRegistry(saveRequest);
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper()
        );

        AgentRuntimeConfigVO first = service.runtimeConfig();
        AgentRuntimeConfigVO second = service.runtimeConfig();

        assertThat(first.getProviderProfiles()).singleElement().satisfies(profile -> {
            assertThat(profile.getProfileKey()).isEqualTo("gateway-deepseek");
            assertThat(profile.getEndpoint()).isEqualTo("https://gateway.example/v1");
            assertThat(profile.getModel()).isEqualTo("deepseek-v4-pro");
            assertThat(profile.getProtocol()).isEqualTo("responses");
            assertThat(profile.getProviderCapabilities().getSchemaVersion()).isEqualTo(1);
            assertThat(profile.getProviderCapabilities().getSupportsTools()).isTrue();
            assertThat(profile.getProviderCapabilities().getReportsCacheUsage()).isTrue();
            assertThat(profile.getApiKeyConfigured()).isTrue();
            assertThat(profile.getProfileVersion()).hasSize(64);
        });
        assertThat(second.getProviderProfiles().get(0).getProfileVersion())
            .isEqualTo(first.getProviderProfiles().get(0).getProfileVersion());
        AgentProviderRuntimeVO runtime = service.resolveProviderRuntime(
            "gateway-deepseek",
            first.getProviderProfiles().get(0).getProfileVersion()
        );
        assertThat(runtime.getProfileKey()).isEqualTo("gateway-deepseek");
        assertThat(runtime.getProfileVersion()).isEqualTo(first.getProviderProfiles().get(0).getProfileVersion());
        assertThat(runtime.getEndpoint()).isEqualTo("https://gateway.example/v1");
        assertThat(runtime.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(runtime.getProviderType()).isEqualTo("openai-compatible");
        assertThat(runtime.getProtocol()).isEqualTo("responses");
        assertThat(runtime.getProviderCapabilities().getSupportsTools()).isTrue();
        assertThat(runtime.getProviderCapabilities().getReportsCacheUsage()).isTrue();
        assertThat(runtime.getApiKey()).isEqualTo("test-provider-key-never-project");
        runtime.getProviderCapabilities().setSupportsTools(false);
        assertThat(service.runtimeConfig().getProviderProfiles().get(0)
            .getProviderCapabilities().getSupportsTools()).isTrue();
        String json = new ObjectMapper().writeValueAsString(first);
        assertThat(json)
            .doesNotContain("test-provider-key-never-project")
            .doesNotContain("apiKeyMasked")
            .doesNotContain("Authorization");

        String firstVersion = first.getProviderProfiles().get(0).getProfileVersion();
        AiModelRegistryModelRequest changed = new AiModelRegistryModelRequest();
        changed.setModelKey("gateway-deepseek");
        changed.setProviderType("openai-compatible");
        changed.setProtocol("responses");
        changed.setProviderCapabilities(providerCapabilities(false));
        changed.setModelName("deepseek-v4-pro");
        changed.setBaseUrl("https://gateway.example/v1");
        changed.setEnabled(true);
        changed.setIsDefault(true);
        AiModelRegistrySaveRequest changedRequest = new AiModelRegistrySaveRequest();
        changedRequest.setDefaultModelKey("gateway-deepseek");
        changedRequest.setModels(List.of(changed));
        systemConfigService.saveModelRegistry(changedRequest);

        AgentRuntimeConfigVO changedConfig = service.runtimeConfig();
        assertThat(changedConfig.getProviderProfiles()).singleElement().satisfies(profile -> {
            assertThat(profile.getProfileVersion()).isNotEqualTo(firstVersion);
            assertThat(profile.getProviderCapabilities().getSupportsTools()).isFalse();
        });
    }

    @Test
    void shouldExposeSecretFreeRoutingPolicyWithFailOpenCircuitStates() throws Exception {
        SystemConfigService systemConfigService = newSystemConfigService();
        AiModelRegistryModelRequest primary = providerModel(
            "primary", "responses", "https://primary.example/v1", "primary-key", true
        );
        primary.setProviderCapabilities(providerCapabilities(true));
        primary.setIsDefault(true);
        AiModelRegistryModelRequest standby = providerModel(
            "standby", "responses", "https://standby.example/v1", "standby-key", true
        );
        standby.setProviderCapabilities(providerCapabilities(true));
        AiProviderRoutingPolicy policy = new AiProviderRoutingPolicy();
        policy.setSchemaVersion(1);
        policy.setEnabled(true);
        policy.setOrderedProfileKeys(List.of("primary", "standby"));
        policy.setMaxFailovers(1);
        policy.setCooldownSeconds(120);
        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("primary");
        saveRequest.setProviderRoutingPolicy(policy);
        saveRequest.setModels(List.of(primary, standby));
        systemConfigService.saveModelRegistry(saveRequest);
        KnowledgeAgentProviderCircuitShadowService circuitShadow = mock(
            KnowledgeAgentProviderCircuitShadowService.class
        );
        when(circuitShadow.readState(any(), any(), anyInt())).thenAnswer(invocation -> {
            AgentProviderCircuitStateVO state = new AgentProviderCircuitStateVO();
            state.setProfileKey(invocation.getArgument(0));
            state.setProfileVersion(invocation.getArgument(1));
            state.setState("standby".equals(invocation.getArgument(0)) ? "OPEN" : "CLOSED");
            state.setFailureCount("standby".equals(invocation.getArgument(0)) ? 2L : 0L);
            return state;
        });
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper(),
            null,
            circuitShadow
        );

        AgentRuntimeConfigVO runtime = service.runtimeConfig();

        assertThat(runtime.getProviderRoutingPolicy()).satisfies(projected -> {
            assertThat(projected.getSchemaVersion()).isEqualTo(1);
            assertThat(projected.getEnabled()).isTrue();
            assertThat(projected.getOrderedProfileKeys()).containsExactly("primary", "standby");
            assertThat(projected.getMaxFailovers()).isEqualTo(1);
            assertThat(projected.getCooldownSeconds()).isEqualTo(120);
            assertThat(projected.getCircuitStates().values()).extracting(AgentProviderCircuitStateVO::getState)
                .containsExactly("CLOSED", "OPEN");
        });
        String serializedPolicy = new ObjectMapper().writeValueAsString(runtime.getProviderRoutingPolicy());
        assertThat(serializedPolicy)
            .contains("\"circuitStates\":{\"primary\"")
            .doesNotContain("primary-key")
            .doesNotContain("standby-key")
            .doesNotContain("endpoint")
            .doesNotContain("body");
    }

    @Test
    void shouldProjectHalfOpenVerbatimAndAnythingUnrecognizedAsClosed() {
        SystemConfigService systemConfigService = newSystemConfigService();
        AiModelRegistryModelRequest primary = providerModel(
            "primary", "responses", "https://primary.example/v1", "primary-key", true
        );
        primary.setProviderCapabilities(providerCapabilities(true));
        primary.setIsDefault(true);
        AiModelRegistryModelRequest standby = providerModel(
            "standby", "responses", "https://standby.example/v1", "standby-key", true
        );
        standby.setProviderCapabilities(providerCapabilities(true));
        AiProviderRoutingPolicy policy = new AiProviderRoutingPolicy();
        policy.setSchemaVersion(1);
        policy.setEnabled(true);
        policy.setOrderedProfileKeys(List.of("primary", "standby"));
        policy.setMaxFailovers(1);
        policy.setCooldownSeconds(120);
        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("primary");
        saveRequest.setProviderRoutingPolicy(policy);
        saveRequest.setModels(List.of(primary, standby));
        systemConfigService.saveModelRegistry(saveRequest);
        KnowledgeAgentProviderCircuitShadowService circuitShadow = mock(
            KnowledgeAgentProviderCircuitShadowService.class
        );
        when(circuitShadow.readState(any(), any(), anyInt())).thenAnswer(invocation -> {
            AgentProviderCircuitStateVO state = new AgentProviderCircuitStateVO();
            // A null state is what a fail-open read produces; it must not blow up here.
            state.setState("primary".equals(invocation.getArgument(0)) ? "HALF_OPEN" : null);
            return state;
        });
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper(),
            null,
            circuitShadow
        );

        // The worker needs HALF_OPEN intact: it admits the probe that OPEN would refuse.
        assertThat(service.runtimeConfig().getProviderRoutingPolicy().getCircuitStates())
            .extractingByKeys("primary", "standby")
            .extracting(AgentProviderCircuitStateVO::getState)
            .containsExactly("HALF_OPEN", "CLOSED");
    }

    @Test
    void shouldSettleOnlyCurrentExplicitProviderRoutingOutcomes() {
        SystemConfigService systemConfigService = newSystemConfigService();
        AiModelRegistryModelRequest primary = providerModel(
            "primary", "responses", "https://primary.example/v1", "primary-key", true
        );
        primary.setProviderCapabilities(providerCapabilities(true));
        primary.setIsDefault(true);
        AiModelRegistryModelRequest standby = providerModel(
            "standby", "responses", "https://standby.example/v1", "standby-key", true
        );
        standby.setProviderCapabilities(providerCapabilities(true));
        AiProviderRoutingPolicy policy = new AiProviderRoutingPolicy();
        policy.setSchemaVersion(1);
        policy.setEnabled(true);
        policy.setOrderedProfileKeys(List.of("primary", "standby"));
        policy.setMaxFailovers(1);
        policy.setCooldownSeconds(120);
        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("primary");
        saveRequest.setProviderRoutingPolicy(policy);
        saveRequest.setModels(List.of(primary, standby));
        systemConfigService.saveModelRegistry(saveRequest);

        String profileVersion = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper()
        ).runtimeConfig().getProviderProfiles().stream()
            .filter(profile -> "primary".equals(profile.getProfileKey()))
            .findFirst()
            .orElseThrow()
            .getProfileVersion();
        KnowledgeAgentProviderCircuitShadowService circuitShadow = mock(
            KnowledgeAgentProviderCircuitShadowService.class
        );
        AgentProviderCircuitStateVO open = new AgentProviderCircuitStateVO();
        open.setProfileKey("primary");
        open.setProfileVersion(profileVersion);
        open.setState("OPEN");
        open.setFailureCount(1L);
        when(circuitShadow.recordTransientFailure("primary", profileVersion, 120)).thenReturn(open);
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper(),
            null,
            circuitShadow
        );

        AgentProviderRoutingOutcomeRequest transientFailure = routingOutcome(
            "primary", profileVersion, "TRANSIENT_FAILURE", "HTTP_503"
        );
        assertThat(service.recordProviderRoutingOutcome(transientFailure).getState()).isEqualTo("OPEN");
        verify(circuitShadow).recordTransientFailure("primary", profileVersion, 120);

        AgentProviderRoutingOutcomeRequest success = routingOutcome(
            "primary", profileVersion, "SUCCEEDED", null
        );
        service.recordProviderRoutingOutcome(success);
        verify(circuitShadow).recordSuccess("primary", profileVersion);

        // 502/504 are ordinary relay jitter, so the breaker has to accept them now.
        AgentProviderRoutingOutcomeRequest badGateway = routingOutcome(
            "primary", profileVersion, "TRANSIENT_FAILURE", "HTTP_502"
        );
        assertThat(service.recordProviderRoutingOutcome(badGateway)).isNotNull();

        // 400 is a malformed request: the worker never reports it and retrying cannot help.
        AgentProviderRoutingOutcomeRequest invalid = routingOutcome(
            "primary", profileVersion, "TRANSIENT_FAILURE", "HTTP_400"
        );
        assertBusinessCode(
            () -> service.recordProviderRoutingOutcome(invalid),
            ResultCode.BAD_REQUEST
        );
        AgentProviderRoutingOutcomeRequest stale = routingOutcome(
            "primary", "0".repeat(64), "TRANSIENT_FAILURE", "TIMEOUT"
        );
        assertBusinessCode(
            () -> service.recordProviderRoutingOutcome(stale),
            ResultCode.CONFLICT
        );
    }

    @Test
    void shouldIncludeEveryProviderCapabilityInProfileVersion() {
        String baselineVersion = projectCapabilitiesToProfileVersion(providerCapabilities(true));
        List<Consumer<AiProviderCapabilities>> mutations = List.of(
            capabilities -> capabilities.setSchemaVersion(2),
            capabilities -> capabilities.setSupportsStreaming(false),
            capabilities -> capabilities.setSupportsTools(false),
            capabilities -> capabilities.setSupportsJsonObject(false),
            capabilities -> capabilities.setSupportsReasoning(false),
            capabilities -> capabilities.setReportsUsage(false),
            capabilities -> capabilities.setReportsCacheUsage(false)
        );

        for (Consumer<AiProviderCapabilities> mutation : mutations) {
            AiProviderCapabilities capabilities = providerCapabilities(true);
            mutation.accept(capabilities);

            assertThat(projectCapabilitiesToProfileVersion(capabilities))
                .isNotEqualTo(baselineVersion);
        }
    }

    @Test
    void shouldIncludeEveryPromptCacheCapabilityInProfileVersion() {
        List<AiPromptCacheCapabilities> promptCaches = List.of(
            promptCache("openai_gpt_5_6", "implicit", "30m", "stable_prefix"),
            promptCache("openai_gpt_5_6", "explicit", "30m", "stable_prefix"),
            promptCache("openai_gpt_5_6", "implicit", "provider_default", "stable_prefix"),
            promptCache("openai_gpt_5_6", "implicit", "30m", "none"),
            promptCache("openai_legacy", "implicit", "24h", "none")
        );

        List<String> versions = promptCaches.stream().map(promptCache -> {
            AiProviderCapabilities capabilities = providerCapabilities(true);
            capabilities.setPromptCache(promptCache);
            return projectCapabilitiesToProfileVersion(capabilities);
        }).toList();

        assertThat(versions).doesNotHaveDuplicates();
    }

    @Test
    void shouldExposeMissingProtocolAsUnspecifiedWithoutModelNameGuessing() {
        SystemConfigService systemConfigService = newSystemConfigService();
        AiModelRegistryModelRequest legacy = new AiModelRegistryModelRequest();
        legacy.setModelKey("legacy-deepseek");
        legacy.setProviderType("openai-compatible");
        legacy.setModelName("deepseek-chat");
        legacy.setBaseUrl("https://legacy.example/v1");
        legacy.setEnabled(true);
        legacy.setIsDefault(true);

        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("legacy-deepseek");
        saveRequest.setModels(List.of(legacy));
        systemConfigService.saveModelRegistry(saveRequest);

        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper()
        );

        assertThat(service.runtimeConfig().getProviderProfiles())
            .singleElement()
            .satisfies(profile -> {
                assertThat(profile.getProtocol()).isEqualTo("unspecified");
                assertThat(profile.getProfileVersion()).hasSize(64);
            });
    }

    @Test
    void shouldFailClosedWhenProviderRuntimeIsNotDispatchable() {
        SystemConfigService systemConfigService = newSystemConfigService();
        AiModelRegistryModelRequest valid = providerModel(
            "valid-profile", "responses", "https://valid.example/v1", "valid-key", true
        );
        AiModelRegistryModelRequest noKey = providerModel(
            "missing-key", "responses", "https://missing-key.example/v1", null, true
        );
        AiModelRegistryModelRequest unspecified = providerModel(
            "unspecified-profile", null, "https://unspecified.example/v1", "unspecified-key", true
        );
        AiModelRegistryModelRequest noEndpoint = providerModel(
            "missing-endpoint", "responses", null, "missing-endpoint-key", true
        );
        AiModelRegistryModelRequest disabled = providerModel(
            "disabled-profile", "responses", "https://disabled.example/v1", "disabled-key", false
        );
        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("valid-profile");
        saveRequest.setModels(List.of(valid, noKey, unspecified, noEndpoint, disabled));
        systemConfigService.saveModelRegistry(saveRequest);
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper()
        );
        Map<String, String> versions = service.runtimeConfig().getProviderProfiles().stream()
            .collect(java.util.stream.Collectors.toMap(
                profile -> profile.getProfileKey(),
                profile -> profile.getProfileVersion()
            ));

        assertBusinessCode(
            () -> service.resolveProviderRuntime("missing-profile", versions.get("valid-profile")),
            ResultCode.NOT_FOUND
        );
        assertBusinessCode(
            () -> service.resolveProviderRuntime("disabled-profile", versions.get("valid-profile")),
            ResultCode.NOT_FOUND
        );
        assertBusinessCode(
            () -> service.resolveProviderRuntime("valid-profile", "0".repeat(64)),
            ResultCode.CONFLICT
        );
        assertBusinessCode(
            () -> service.resolveProviderRuntime("unspecified-profile", "0".repeat(64)),
            ResultCode.CONFLICT
        );
        assertBusinessCode(
            () -> service.resolveProviderRuntime("missing-key", versions.get("missing-key")),
            ResultCode.SERVICE_UNAVAILABLE
        );
        assertBusinessCode(
            () -> service.resolveProviderRuntime("unspecified-profile", versions.get("unspecified-profile")),
            ResultCode.SERVICE_UNAVAILABLE
        );
        assertBusinessCode(
            () -> service.resolveProviderRuntime("missing-endpoint", versions.get("missing-endpoint")),
            ResultCode.SERVICE_UNAVAILABLE
        );
    }

    @Test
    void shouldRefuseDispatchWhileTheBreakerIsOpenAndAdmitTheNextCandidate() {
        SystemConfigService systemConfigService = newSystemConfigService();
        AiModelRegistryModelRequest primary = providerModel(
            "primary", "responses", "https://primary.example/v1", "primary-key", true
        );
        primary.setProviderCapabilities(providerCapabilities(true));
        primary.setIsDefault(true);
        AiModelRegistryModelRequest standby = providerModel(
            "standby", "responses", "https://standby.example/v1", "standby-key", true
        );
        standby.setProviderCapabilities(providerCapabilities(true));
        AiProviderRoutingPolicy policy = new AiProviderRoutingPolicy();
        policy.setSchemaVersion(1);
        policy.setEnabled(true);
        policy.setOrderedProfileKeys(List.of("primary", "standby"));
        policy.setMaxFailovers(1);
        policy.setCooldownSeconds(120);
        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("primary");
        saveRequest.setProviderRoutingPolicy(policy);
        saveRequest.setModels(List.of(primary, standby));
        systemConfigService.saveModelRegistry(saveRequest);
        Map<String, String> versions = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper()
        ).runtimeConfig().getProviderProfiles().stream()
            .collect(java.util.stream.Collectors.toMap(
                AgentProviderProfileVO::getProfileKey,
                AgentProviderProfileVO::getProfileVersion
            ));
        KnowledgeAgentProviderCircuitShadowService circuitShadow = mock(
            KnowledgeAgentProviderCircuitShadowService.class
        );
        when(circuitShadow.tryAcquireDispatchPermit(eq("primary"), any(), eq(120)))
            .thenReturn(false);
        when(circuitShadow.tryAcquireDispatchPermit(eq("standby"), any(), eq(120)))
            .thenReturn(true);
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper(),
            null,
            circuitShadow
        );

        // The breaker is a real gate now: an OPEN primary must not hand out its credential.
        assertBusinessCode(
            () -> service.resolveProviderRuntime("primary", versions.get("primary")),
            ResultCode.SERVICE_UNAVAILABLE
        );
        assertThat(service.resolveProviderRuntime("standby", versions.get("standby")).getApiKey())
            .isEqualTo("standby-key");
    }

    @Test
    void shouldNotGateDispatchWhileRoutingIsDisabled() {
        SystemConfigService systemConfigService = newSystemConfigService();
        AiModelRegistryModelRequest only = providerModel(
            "primary", "responses", "https://primary.example/v1", "primary-key", true
        );
        only.setIsDefault(true);
        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("primary");
        saveRequest.setModels(List.of(only));
        systemConfigService.saveModelRegistry(saveRequest);
        KnowledgeAgentProviderCircuitShadowService circuitShadow = mock(
            KnowledgeAgentProviderCircuitShadowService.class
        );
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper(),
            null,
            circuitShadow
        );
        String version = service.runtimeConfig().getProviderProfiles().get(0).getProfileVersion();

        assertThat(service.resolveProviderRuntime("primary", version).getApiKey())
            .isEqualTo("primary-key");

        // With no alternative to fall back to, gating would turn a blip into an outage.
        verify(circuitShadow, never()).tryAcquireDispatchPermit(any(), any(), anyInt());
    }

    @Test
    void shouldUpdateRuntimePolicyWithValidation() {
        KnowledgeAgentGovernanceService service = newService();
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("1");

        AgentRuntimeConfigVO updated = service.updateRuntimeConfig("maxParallelSpecialists", request);

        assertThat(updated.getMaxParallelSpecialists()).isEqualTo(1);
        assertThat(service.runtimeConfig().getMaxParallelSpecialists()).isEqualTo(1);

        AgentRuntimeConfigUpdateRequest invalid = new AgentRuntimeConfigUpdateRequest();
        invalid.setValue("2");
        assertThatThrownBy(() -> service.updateRuntimeConfig("maxParallelSpecialists", invalid))
            .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> service.updateRuntimeConfig("unknownKey", request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldPersistDefaultReasoningModeToTheChatRuntimeConfigKey() {
        SystemConfigService systemConfigService = newSystemConfigService();
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper()
        );
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("deep");

        AgentRuntimeConfigVO updated = service.updateRuntimeConfig("reasoningModeDefault", request);

        assertThat(updated.getReasoningModeDefault()).isEqualTo("deep");
        assertThat(systemConfigService.getValueOrDefault("ai.knowledge.reasoning-mode.default", "fast"))
            .isEqualTo("deep");
        assertThat(systemConfigService.getValueOrDefault("ai.agent.runtime.reasoning-mode-default", "missing"))
            .isEqualTo("missing");
    }

    @Test
    void shouldRejectRemovedControlPlaneMode() {
        SystemConfigService systemConfigService = newSystemConfigService();
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper()
        );
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("shadow");

        assertThatThrownBy(() -> service.updateRuntimeConfig("controlPlaneMode", request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldAllowZeroFinalOutputTokensToSelectAutomaticBudget() {
        KnowledgeAgentGovernanceService service = newService();
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("0");

        AgentRuntimeConfigVO updated = service.updateRuntimeConfig("maxFinalOutputTokensDeep", request);

        assertThat(updated.getMaxFinalOutputTokensDeep()).isZero();
        assertThat(service.runtimeConfig().getMaxFinalOutputTokensDeep()).isZero();
    }

    @Test
    void shouldExposeUnifiedContextCeilingAndPercentKnobsWithBounds() {
        KnowledgeAgentGovernanceService service = newService();

        AgentRuntimeConfigVO defaults = service.runtimeConfig();
        assertThat(defaults.getMaxTotalInputTokens()).isEqualTo(300_000);
        assertThat(defaults.getContextCompactionThresholdPercent()).isEqualTo(85);
        assertThat(defaults.getRunTokenBudgetPercent()).isEqualTo(150);

        AgentRuntimeConfigUpdateRequest threshold = new AgentRuntimeConfigUpdateRequest();
        threshold.setValue("70");
        assertThat(service.updateRuntimeConfig("contextCompactionThresholdPercent", threshold)
            .getContextCompactionThresholdPercent()).isEqualTo(70);

        AgentRuntimeConfigUpdateRequest budget = new AgentRuntimeConfigUpdateRequest();
        budget.setValue("200");
        assertThat(service.updateRuntimeConfig("runTokenBudgetPercent", budget)
            .getRunTokenBudgetPercent()).isEqualTo(200);

        // 比例越界必须挡住：阈值 <50 会让压缩过早触发丢证据，>95 等于不压缩。
        AgentRuntimeConfigUpdateRequest tooLow = new AgentRuntimeConfigUpdateRequest();
        tooLow.setValue("40");
        assertThatThrownBy(() -> service.updateRuntimeConfig("contextCompactionThresholdPercent", tooLow))
            .isInstanceOf(BusinessException.class);

        AgentRuntimeConfigUpdateRequest tooHigh = new AgentRuntimeConfigUpdateRequest();
        tooHigh.setValue("500");
        assertThatThrownBy(() -> service.updateRuntimeConfig("runTokenBudgetPercent", tooHigh))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldAllowZeroExpertPromptCharsToSelectAutomaticBudget() {
        KnowledgeAgentGovernanceService service = newService();
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("0");

        AgentRuntimeConfigVO updated = service.updateRuntimeConfig("maxPromptCharsPerExpert", request);

        assertThat(updated.getMaxPromptCharsPerExpert()).isZero();
        assertThat(service.runtimeConfig().getMaxPromptCharsPerExpert()).isZero();
    }

    @Test
    void shouldAllowZeroSkillPromptCharsToDisableDedicatedCap() {
        KnowledgeAgentGovernanceService service = newService();
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("0");

        AgentRuntimeConfigVO updated = service.updateRuntimeConfig("maxSkillPromptChars", request);

        assertThat(updated.getMaxSkillPromptChars()).isZero();
        assertThat(service.runtimeConfig().getMaxSkillPromptChars()).isZero();
    }

    @Test
    void shouldPersistSpecialistMcpRuntimePolicy() {
        KnowledgeAgentGovernanceService service = newService();
        AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
        request.setValue("true");

        AgentRuntimeConfigVO updated = service.updateRuntimeConfig("specialistMcpEnabled", request);

        assertThat(updated.getSpecialistMcpEnabled()).isTrue();
        assertThat(service.runtimeConfig().getSpecialistMcpEnabled()).isTrue();
    }

    @Test
    void shouldKeepHarnessIntelligenceDefaultOffAndValidateUpdates() {
        KnowledgeAgentGovernanceService service = newService();
        for (String key : List.of("harnessEvidenceRepairEnabled", "harnessAnswerValidationEnabled",
            "harnessTaskCheckpointEnabled", "harnessStageSkillsEnabled")) {
            assertThat(new ObjectMapper().valueToTree(service.runtimeConfig()).path(key).asText()).isEqualTo("false");
            AgentRuntimeConfigUpdateRequest request = new AgentRuntimeConfigUpdateRequest();
            request.setValue("true");
            assertThat(new ObjectMapper().valueToTree(service.updateRuntimeConfig(key, request)).path(key).asText()).isEqualTo("true");
            request.setValue("unbounded");
            assertThatThrownBy(() -> service.updateRuntimeConfig(key, request)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void shouldListDefaultExpertProfiles() {
        KnowledgeAgentGovernanceService service = newService();

        List<AgentExpertProfileVO> profiles = service.listExpertProfiles();

        assertThat(profiles).extracting(AgentExpertProfileVO::getExpertName)
            .contains(
                "market_scan",
                "author_strategy",
                "opening_strategy",
                "outline",
                "reader_risk",
                "editor",
                "supervisor"
            );
        assertThat(profiles)
            .filteredOn(profile -> "market_scan".equals(profile.getExpertName()))
            .singleElement()
            .satisfies(profile -> {
                assertThat(profile.getEnabled()).isTrue();
                assertThat(profile.getMaxTokens()).isGreaterThan(0);
                assertThat(profile.getTriggerIntents()).contains("market_scan");
                assertThat(profile.getCategory()).isEqualTo("Skill");
                assertThat(profile.getExecutionKind()).isEqualTo("INLINE");
                assertThat(profile.getRequestedToolCapabilities()).contains("market.read");
            });
    }

    @Test
    void shouldUpdateExpertProfileAndRejectUnknownExpert() {
        KnowledgeAgentGovernanceService service = newService();
        AgentExpertProfileUpdateRequest request = new AgentExpertProfileUpdateRequest();
        request.setEnabled(false);
        request.setPriority(10);
        request.setMaxTokens(1200);
        request.setMaxToolCalls(2);
        request.setTriggerIntents(List.of("market_scan"));
        request.setTriggerTasks(List.of("market_scan"));
        request.setRequestedToolCapabilities(List.of("market.read", "book.read"));
        request.setPromptVersion("v2");
        request.setEvalSuiteId("market-suite");
        request.setCategory("Delegated");
        request.setExpectedQualityGain(0.0);
        request.setLatencyCost(0.10);
        request.setTokenCost(0.05);
        request.setResourceCost(0.05);

        AgentExpertProfileVO updated = service.updateExpertProfile("market_scan", request);

        assertThat(updated.getEnabled()).isFalse();
        assertThat(updated.getPriority()).isEqualTo(10);
        assertThat(updated.getMaxTokens()).isEqualTo(1200);
        assertThat(updated.getMaxToolCalls()).isEqualTo(2);
        assertThat(updated.getPromptVersion()).isEqualTo("v2");
        assertThat(updated.getEvalSuiteId()).isEqualTo("market-suite");
        assertThat(updated.getCategory()).isEqualTo("Delegated");
        assertThat(updated.getExecutionKind()).isEqualTo("DELEGATED");
        assertThat(updated.getRequestedToolCapabilities()).containsExactly("market.read", "book.read");
        assertThat(updated.getExpectedQualityGain()).isZero();
        assertThat(updated.getQualityGainVerified()).isFalse();
        assertThat(updated.getQualityGainSource()).isEqualTo("unverified");
        assertThat(updated.getLatencyCost()).isEqualTo(0.10);
        assertThat(updated.getTokenCost()).isEqualTo(0.05);
        assertThat(updated.getResourceCost()).isEqualTo(0.05);

        assertThat(service.listExpertProfiles())
            .filteredOn(profile -> "market_scan".equals(profile.getExpertName()))
            .singleElement()
            .extracting(AgentExpertProfileVO::getEnabled)
            .isEqualTo(false);

        assertThatThrownBy(() -> service.updateExpertProfile("unknown_expert", request))
            .isInstanceOf(BusinessException.class);

        AgentExpertProfileUpdateRequest invalid = new AgentExpertProfileUpdateRequest();
        invalid.setCategory("Unknown");
        assertThatThrownBy(() -> service.updateExpertProfile("market_scan", invalid))
            .isInstanceOf(BusinessException.class);

        AgentExpertProfileUpdateRequest invalidKind = new AgentExpertProfileUpdateRequest();
        invalidKind.setExecutionKind("SOMETHING_ELSE");
        assertThatThrownBy(() -> service.updateExpertProfile("market_scan", invalidKind))
            .isInstanceOf(BusinessException.class);

        AgentExpertProfileUpdateRequest forgedGain = new AgentExpertProfileUpdateRequest();
        forgedGain.setExpectedQualityGain(0.55);
        assertThatThrownBy(() -> service.updateExpertProfile("market_scan", forgedGain))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("admin-configured eval");
    }

    @Test
    void shouldDeriveDelegatedQualityGainFromLatestPassedEval() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.execute("create table ai_eval_run (" +
            "id bigint auto_increment primary key," +
            "suite_name varchar(100)," +
            "status varchar(20)," +
            "failed_cases int," +
            "metrics_json clob," +
            "deleted tinyint default 0)");
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );
        AgentExpertProfileVO expectedProfile = service.listExpertProfiles().stream()
            .filter(profile -> "market_scan".equals(profile.getExpertName()))
            .findFirst()
            .orElseThrow();
        expectedProfile.setCategory("Delegated");
        expectedProfile.setExecutionKind("DELEGATED");
        expectedProfile.setEvalSuiteId("market-suite");
        String evalConfigFingerprint = service.expertEvalConfigFingerprint(expectedProfile);
        jdbcTemplate.update(
            "insert into ai_eval_run(suite_name, status, failed_cases, metrics_json, deleted) values(?, ?, ?, ?, 0)",
            "market-suite",
            "PASSED",
            0,
            """
                {"faithfulness_pass_rate":1.0,"required_tool_pass_rate":1.0,
                 "trace_completeness_rate":1.0,"answer_boundary_pass_rate":1.0,
                 "delegation_policy_pass_rate":1.0,
                 "delegated_eval_config_presence_rates":{"%s":1.0},
                 "delegated_eval_config_gains":{"%s":0.25},
                 "delegated_eval_config_fingerprints":["%s"]}
                """.formatted(evalConfigFingerprint, evalConfigFingerprint, evalConfigFingerprint)
        );
        AgentExpertProfileUpdateRequest request = new AgentExpertProfileUpdateRequest();
        request.setCategory("Delegated");
        request.setEvalSuiteId("market-suite");

        AgentExpertProfileVO updated = service.updateExpertProfile("market_scan", request);

        assertThat(updated.getExpectedQualityGain()).isEqualTo(0.25);
        assertThat(updated.getQualityGainVerified()).isTrue();
        assertThat(updated.getQualityGainSource()).isEqualTo("admin_configured_eval");
        assertThat(updated.getQualityGainEvalRunId()).isPositive();
    }

    @Test
    void shouldUseTheSameUtf8CanonicalEvalConfigFingerprintAsWorker() {
        AgentExpertProfileVO profile = new AgentExpertProfileVO();
        profile.setExpertName("market_scan");
        profile.setCategory("Delegated");
        profile.setDefaultMode("deep");
        profile.setEnabled(true);
        profile.setEvalSuiteId("市场套件");
        profile.setGuardrail(false);
        profile.setMaxTokens(1200);
        profile.setMaxToolCalls(4);
        profile.setRequestedToolCapabilities(List.of("skill.activate", "market.read"));
        profile.setPriority(10);
        profile.setPromptVersion("版本二");
        profile.setTriggerIntents(List.of("mixed_creation_research", "market_scan"));
        profile.setTriggerTasks(List.of("topic_strategy", "market_scan"));
        profile.setDisplayName("\u5e02\u573a\u5206\u6790");
        profile.setCostClass("high");
        profile.setCapabilityIds(List.of("market.read", "market.analyze"));
        profile.setDefaultSkillIds(List.of("webnovel-market-scan", "webnovel-evidence"));
        profile.setOutputContract("market-analysis-v2");
        profile.setExecutionKind("DELEGATED");
        profile.setPromptVersion("\u7248\u672c\u4e8c");
        profile.setEvalSuiteId("\u5e02\u573a\u5957\u4ef6");
        profile.setGuardrail(true);
        profile.setLatencyCost(0.10);
        profile.setTokenCost(0.05);
        profile.setResourceCost(0.02);

        KnowledgeAgentGovernanceService service = newService();
        String fingerprint = service.expertEvalConfigFingerprint(profile);

        assertThat(fingerprint)
            .isEqualTo("4a3f99253e8491662cfaf0b7b1f23d1bc4a3dc712068b3cc409632746d731002");
        profile.setExpectedQualityGain(0.45);
        profile.setQualityGainVerified(true);
        profile.setQualityGainSource("admin_configured_eval");
        profile.setQualityGainEvalRunId(42L);
        assertThat(service.expertEvalConfigFingerprint(profile)).isEqualTo(fingerprint);
    }

    @Test
    void shouldRequireAdminDelegatedCategoryAndBoundSuiteBeforeEvalCanAuthorizeProfile() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.execute("create table ai_eval_run (" +
            "id bigint auto_increment primary key," +
            "suite_name varchar(100)," +
            "status varchar(20)," +
            "failed_cases int," +
            "metrics_json clob," +
            "deleted tinyint default 0)");
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );
        AgentExpertProfileVO delegatedProfile = service.listExpertProfiles().stream()
            .filter(profile -> "market_scan".equals(profile.getExpertName()))
            .findFirst()
            .orElseThrow();
        delegatedProfile.setCategory("Delegated");
        delegatedProfile.setExecutionKind("DELEGATED");
        delegatedProfile.setEvalSuiteId("market-suite");
        String evalConfigFingerprint = service.expertEvalConfigFingerprint(delegatedProfile);
        jdbcTemplate.update(
            "insert into ai_eval_run(suite_name, status, failed_cases, metrics_json, deleted) values(?, ?, ?, ?, 0)",
            "market-suite",
            "PASSED",
            0,
            """
                {"faithfulness_pass_rate":1.0,"required_tool_pass_rate":1.0,
                 "trace_completeness_rate":1.0,"answer_boundary_pass_rate":1.0,
                 "delegation_policy_pass_rate":1.0,
                 "delegated_eval_config_presence_rates":{"%s":1.0},
                 "delegated_eval_config_gains":{"%s":0.25},
                 "delegated_eval_config_fingerprints":["%s"]}
                """.formatted(evalConfigFingerprint, evalConfigFingerprint, evalConfigFingerprint)
        );

        AgentExpertProfileUpdateRequest skillRequest = new AgentExpertProfileUpdateRequest();
        skillRequest.setEvalSuiteId("market-suite");
        AgentExpertProfileVO skillProfile = service.updateExpertProfile("market_scan", skillRequest);

        assertThat(skillProfile.getCategory()).isEqualTo("Skill");
        assertThat(skillProfile.getExpectedQualityGain()).isZero();
        assertThat(skillProfile.getQualityGainVerified()).isFalse();
        assertThat(skillProfile.getQualityGainSource()).isEqualTo("not_required");

        AgentExpertProfileUpdateRequest delegatedRequest = new AgentExpertProfileUpdateRequest();
        delegatedRequest.setCategory("Delegated");
        AgentExpertProfileVO authorizedProfile = service.updateExpertProfile("market_scan", delegatedRequest);

        assertThat(authorizedProfile.getExpectedQualityGain()).isEqualTo(0.25);
        assertThat(authorizedProfile.getQualityGainVerified()).isTrue();
        assertThat(authorizedProfile.getQualityGainSource()).isEqualTo("admin_configured_eval");
    }

    @Test
    void shouldKeepDelegatedQualityGainUnverifiedWhenAnyApprovalGateFails() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.execute("create table ai_eval_run (" +
            "id bigint auto_increment primary key," +
            "suite_name varchar(100)," +
            "status varchar(20)," +
            "failed_cases int," +
            "metrics_json clob," +
            "deleted tinyint default 0)");
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );
        AgentExpertProfileVO expectedProfile = service.listExpertProfiles().stream()
            .filter(profile -> "market_scan".equals(profile.getExpertName()))
            .findFirst()
            .orElseThrow();
        expectedProfile.setCategory("Delegated");
        expectedProfile.setExecutionKind("DELEGATED");
        expectedProfile.setEvalSuiteId("market-suite-failed-gate");
        String evalConfigFingerprint = service.expertEvalConfigFingerprint(expectedProfile);
        jdbcTemplate.update(
            "insert into ai_eval_run(suite_name, status, failed_cases, metrics_json, deleted) values(?, ?, ?, ?, 0)",
            "market-suite-failed-gate",
            "PASSED",
            0,
            """
                {"faithfulness_pass_rate":1.0,"required_tool_pass_rate":1.0,
                 "trace_completeness_rate":1.0,"answer_boundary_pass_rate":1.0,
                 "delegation_policy_pass_rate":0.0,
                 "delegated_eval_config_presence_rates":{"%s":1.0},
                 "delegated_eval_config_gains":{"%s":0.25},
                 "delegated_eval_config_fingerprints":["%s"]}
                """.formatted(evalConfigFingerprint, evalConfigFingerprint, evalConfigFingerprint)
        );
        AgentExpertProfileUpdateRequest request = new AgentExpertProfileUpdateRequest();
        request.setCategory("Delegated");
        request.setEvalSuiteId("market-suite-failed-gate");

        AgentExpertProfileVO updated = service.updateExpertProfile("market_scan", request);

        assertThat(updated.getExpectedQualityGain()).isZero();
        assertThat(updated.getQualityGainVerified()).isFalse();
        assertThat(updated.getQualityGainSource()).isEqualTo("unverified");
    }

    @Test
    void shouldKeepDelegatedQualityGainUnverifiedWhenEvalPolicyMetricIsMissing() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.execute("create table ai_eval_run (" +
            "id bigint auto_increment primary key," +
            "suite_name varchar(100)," +
            "status varchar(20)," +
            "failed_cases int," +
            "metrics_json clob," +
            "deleted tinyint default 0)");
        jdbcTemplate.update(
            "insert into ai_eval_run(suite_name, status, failed_cases, metrics_json, deleted) values(?, ?, ?, ?, 0)",
            "legacy-suite",
            "PASSED",
            0,
            """
                {"faithfulness_pass_rate":1.0,"required_tool_pass_rate":1.0,
                 "trace_completeness_rate":1.0,"answer_boundary_pass_rate":1.0}
                """
        );
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );
        AgentExpertProfileUpdateRequest request = new AgentExpertProfileUpdateRequest();
        request.setCategory("Delegated");
        request.setEvalSuiteId("legacy-suite");

        AgentExpertProfileVO updated = service.updateExpertProfile("market_scan", request);

        assertThat(updated.getExpectedQualityGain()).isZero();
        assertThat(updated.getQualityGainVerified()).isFalse();
        assertThat(updated.getQualityGainSource()).isEqualTo("unverified");
        assertThat(updated.getQualityGainEvalRunId()).isNull();
    }

    @Test
    void shouldRejectLegacyProfileMetricsEvenForCurrentEvalConfigFingerprint() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.execute("create table ai_eval_run (" +
            "id bigint auto_increment primary key," +
            "suite_name varchar(100)," +
            "status varchar(20)," +
            "failed_cases int," +
            "metrics_json clob," +
            "deleted tinyint default 0)");
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );
        AgentExpertProfileVO expectedProfile = service.listExpertProfiles().stream()
            .filter(profile -> "market_scan".equals(profile.getExpertName()))
            .findFirst()
            .orElseThrow();
        expectedProfile.setCategory("Delegated");
        expectedProfile.setExecutionKind("DELEGATED");
        expectedProfile.setEvalSuiteId("legacy-profile-suite");
        String evalConfigFingerprint = service.expertEvalConfigFingerprint(expectedProfile);
        jdbcTemplate.update(
            "insert into ai_eval_run(suite_name, status, failed_cases, metrics_json, deleted) values(?, ?, ?, ?, 0)",
            "legacy-profile-suite",
            "PASSED",
            0,
            """
                {"faithfulness_pass_rate":1.0,"required_tool_pass_rate":1.0,
                 "trace_completeness_rate":1.0,"answer_boundary_pass_rate":1.0,
                 "delegation_policy_pass_rate":1.0,
                 "delegated_profile_presence_rates":{"%s":1.0},
                 "delegated_profile_gains":{"%s":0.25},
                 "delegated_profile_hashes":["%s"]}
                """.formatted(evalConfigFingerprint, evalConfigFingerprint, evalConfigFingerprint)
        );
        AgentExpertProfileUpdateRequest request = new AgentExpertProfileUpdateRequest();
        request.setCategory("Delegated");
        request.setEvalSuiteId("legacy-profile-suite");

        AgentExpertProfileVO updated = service.updateExpertProfile("market_scan", request);

        assertThat(updated.getExpectedQualityGain()).isZero();
        assertThat(updated.getQualityGainVerified()).isFalse();
        assertThat(updated.getQualityGainSource()).isEqualTo("unverified");
        assertThat(updated.getQualityGainEvalRunId()).isNull();
    }

    @Test
    void shouldAggregateCacheAndTokenStatsFromTraceJson() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update(
            "insert into ai_agent_trace(trace_id, user_id, question, status, result_json) values(?, ?, ?, ?, ?)",
            "trace-a",
            1L,
            "market",
            "answered",
            """
                {
                  "tokenUsed": 120,
                  "cacheEvents": [
                    {"status": "HIT"},
                    {"status": "MISS"}
                  ],
                  "trace": {
                    "tokenUsage": {
                      "byNode": {"route_experts": 11},
                      "byExpert": {"market_scan": 22}
                    }
                  }
                }
                """
        );
        jdbcTemplate.update(
            "insert into ai_agent_trace(trace_id, user_id, question, status, result_json) values(?, ?, ?, ?, ?)",
            "trace-b",
            1L,
            "outline",
            "answered",
            """
                {
                  "tokenUsed": 80,
                  "cacheStats": {"hits": 2, "misses": 1},
                  "tokenUsage": {
                    "byNode": {"compose_answer": 33},
                    "byExpert": {"outline": 44}
                  }
                }
                """
        );
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );

        AgentCacheTokenStatsVO stats = service.cacheTokenStats();

        assertThat(stats.getTraceCount()).isEqualTo(2);
        assertThat(stats.getCacheHits()).isEqualTo(3);
        assertThat(stats.getCacheMisses()).isEqualTo(2);
        assertThat(stats.getTotalTokens()).isEqualTo(200);
        assertThat(stats.getTokenByNode()).containsEntry("route_experts", 11L).containsEntry("compose_answer", 33L);
        assertThat(stats.getTokenByExpert()).containsEntry("market_scan", 22L).containsEntry("outline", 44L);
    }

    @Test
    void shouldPreferPersistedCacheAndTokenTelemetryWhenPresent() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createTelemetryTables(jdbcTemplate);
        jdbcTemplate.update(
            "insert into ai_agent_trace(trace_id, user_id, question, status, result_json) values(?, ?, ?, ?, ?)",
            "trace-a",
            1L,
            "market",
            "answered",
            """
                {
                  "tokenUsed": 999,
                  "cacheEvents": [{"status": "HIT"}],
                  "trace": {"tokenUsage": {"byNode": {"stale": 999}, "byExpert": {"stale": 999}}}
                }
                """
        );
        jdbcTemplate.update(
            "insert into ai_agent_cache_event(trace_id, cache_scope, node_name, expert_name, cache_status, prompt_prefix_stable) values(?, ?, ?, ?, ?, ?)",
            "trace-a",
            "intent",
            "route_intent",
            null,
            "HIT",
            true
        );
        jdbcTemplate.update(
            "insert into ai_agent_cache_event(trace_id, cache_scope, node_name, expert_name, cache_status, prompt_prefix_stable) values(?, ?, ?, ?, ?, ?)",
            "trace-a",
            "tool",
            "retrieve_evidence",
            "market_scan",
            "MISS",
            false
        );
        jdbcTemplate.update(
            "insert into ai_agent_cache_event(trace_id, cache_scope, node_name, expert_name, cache_status, prompt_prefix_stable) values(?, ?, ?, ?, ?, ?)",
            "trace-b",
            "specialist",
            "run_specialists",
            "outline",
            "HIT",
            true
        );
        jdbcTemplate.update(
            "insert into ai_agent_token_metric(trace_id, node_name, expert_name, token_count) values(?, ?, ?, ?)",
            "trace-a",
            "route_experts",
            "market_scan",
            11
        );
        jdbcTemplate.update(
            "insert into ai_agent_token_metric(trace_id, node_name, expert_name, token_count) values(?, ?, ?, ?)",
            "trace-a",
            "compose_answer",
            null,
            33
        );
        jdbcTemplate.update(
            "insert into ai_agent_token_metric(trace_id, node_name, expert_name, token_count) values(?, ?, ?, ?)",
            "trace-b",
            "run_specialists",
            "outline",
            22
        );
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            newSystemConfigService(),
            new ObjectMapper(),
            jdbcTemplate
        );

        AgentCacheTokenStatsVO stats = service.cacheTokenStats();

        assertThat(stats.getTraceCount()).isEqualTo(2);
        assertThat(stats.getCacheHits()).isEqualTo(2);
        assertThat(stats.getCacheMisses()).isEqualTo(1);
        assertThat(stats.getTotalTokens()).isEqualTo(66);
        assertThat(stats.getPromptPrefixStableRate()).isEqualTo(0.6667);
        assertThat(stats.getTokenByNode())
            .containsEntry("route_experts", 11L)
            .containsEntry("compose_answer", 33L)
            .containsEntry("run_specialists", 22L)
            .doesNotContainEntry("stale", 999L);
        assertThat(stats.getTokenByExpert())
            .containsEntry("market_scan", 11L)
            .containsEntry("outline", 22L)
            .doesNotContainEntry("stale", 999L);
    }

    private static KnowledgeAgentGovernanceService newService() {
        return new KnowledgeAgentGovernanceService(newSystemConfigService(), new ObjectMapper());
    }

    private static AiModelRegistryModelRequest providerModel(String modelKey,
                                                             String protocol,
                                                             String baseUrl,
                                                             String apiKey,
                                                             boolean enabled) {
        AiModelRegistryModelRequest model = new AiModelRegistryModelRequest();
        model.setModelKey(modelKey);
        model.setProviderType("openai-compatible");
        model.setProtocol(protocol);
        model.setModelName(modelKey + "-model");
        model.setBaseUrl(baseUrl);
        model.setApiKey(apiKey);
        model.setEnabled(enabled);
        model.setIsDefault("valid-profile".equals(modelKey));
        return model;
    }

    private static AiProviderCapabilities providerCapabilities(boolean supportsTools) {
        AiProviderCapabilities capabilities = new AiProviderCapabilities();
        capabilities.setSchemaVersion(1);
        capabilities.setSupportsStreaming(true);
        capabilities.setSupportsTools(supportsTools);
        capabilities.setSupportsJsonObject(true);
        capabilities.setSupportsReasoning(true);
        capabilities.setReportsUsage(true);
        capabilities.setReportsCacheUsage(true);
        return capabilities;
    }

    private static AiPromptCacheCapabilities promptCache(String strategy,
                                                          String mode,
                                                          String retention,
                                                          String breakpoint) {
        AiPromptCacheCapabilities promptCache = new AiPromptCacheCapabilities();
        promptCache.setStrategy(strategy);
        promptCache.setMode(mode);
        promptCache.setRetention(retention);
        promptCache.setBreakpoint(breakpoint);
        return promptCache;
    }

    private static AgentProviderRoutingOutcomeRequest routingOutcome(
        String profileKey,
        String profileVersion,
        String outcome,
        String failureClass
    ) {
        AgentProviderRoutingOutcomeRequest request = new AgentProviderRoutingOutcomeRequest();
        request.setProfileKey(profileKey);
        request.setProfileVersion(profileVersion);
        request.setOutcome(outcome);
        request.setFailureClass(failureClass);
        request.setSwitched(false);
        return request;
    }

    private static String projectCapabilitiesToProfileVersion(AiProviderCapabilities capabilities) {
        AiModelRegistryModelVO model = new AiModelRegistryModelVO();
        model.setModelKey("capability-profile");
        model.setProviderType("openai-compatible");
        model.setProtocol("responses");
        model.setProviderCapabilities(capabilities);
        model.setModelName("capability-model");
        model.setBaseUrl("https://capability.example/v1");
        model.setEnabled(true);
        model.setIsDefault(true);
        AiModelRegistryVO registry = new AiModelRegistryVO();
        registry.setDefaultModelKey("capability-profile");
        registry.setModels(List.of(model));
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getModelRegistry()).thenReturn(registry);
        KnowledgeAgentGovernanceService service = new KnowledgeAgentGovernanceService(
            systemConfigService,
            new ObjectMapper()
        );
        return service.runtimeConfig().getProviderProfiles().get(0).getProfileVersion();
    }

    private static void assertBusinessCode(Runnable operation, ResultCode expectedCode) {
        assertThatThrownBy(operation::run)
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(expectedCode)
            );
    }

    private static SystemConfigService newSystemConfigService() {
        SystemConfigRepository repository = mock(SystemConfigRepository.class);
        ConfigSecretService secretService = mock(ConfigSecretService.class);
        Map<String, SystemConfigEntity> configs = new HashMap<>();

        when(repository.findByKey(any())).thenAnswer(invocation ->
            Optional.ofNullable(configs.get(invocation.getArgument(0)))
        );
        when(repository.saveOrUpdate(any(SystemConfigEntity.class))).thenAnswer(invocation -> {
            SystemConfigEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId((long) configs.size() + 1);
            }
            if (entity.getEditable() == null) {
                entity.setEditable(1);
            }
            configs.put(entity.getConfigKey(), entity);
            return entity;
        });
        when(secretService.hasSecret(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value != null && !value.isBlank();
        });
        when(secretService.decryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secretService.encryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secretService.isMaskedValue(any())).thenReturn(false);
        when(secretService.maskValue(any())).thenReturn("");

        ObjectMapper objectMapper = new ObjectMapper();
        return new SystemConfigService(repository, objectMapper, secretService);
    }

    private static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:agent-governance-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_agent_trace (" +
            "id bigint auto_increment primary key," +
            "trace_id varchar(120)," +
            "user_id bigint," +
            "question varchar(500)," +
            "status varchar(30)," +
            "result_json clob," +
            "created_at timestamp default current_timestamp)");
        return jdbcTemplate;
    }

    private static void createTelemetryTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("create table ai_agent_cache_event (" +
            "id bigint auto_increment primary key," +
            "trace_id varchar(120)," +
            "cache_scope varchar(60)," +
            "node_name varchar(120)," +
            "expert_name varchar(120)," +
            "cache_status varchar(20)," +
            "prompt_prefix_stable boolean," +
            "created_at timestamp default current_timestamp)");
        jdbcTemplate.execute("create table ai_agent_token_metric (" +
            "id bigint auto_increment primary key," +
            "trace_id varchar(120)," +
            "node_name varchar(120)," +
            "expert_name varchar(120)," +
            "token_count bigint," +
            "created_at timestamp default current_timestamp)");
    }
}
