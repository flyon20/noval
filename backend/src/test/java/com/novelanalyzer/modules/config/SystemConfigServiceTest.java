package com.novelanalyzer.modules.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.config.dto.AiModelRegistryModelRequest;
import com.novelanalyzer.modules.config.dto.AiModelRegistrySaveRequest;
import com.novelanalyzer.modules.config.model.AiProviderCapabilities;
import com.novelanalyzer.modules.config.model.AiPromptCacheCapabilities;
import com.novelanalyzer.modules.config.model.AiProviderRoutingPolicy;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import com.novelanalyzer.modules.config.repository.SystemConfigRepository;
import com.novelanalyzer.modules.config.service.ConfigSecretService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.config.vo.SystemConfigVO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemConfigServiceTest {

    @Test
    void shouldListKnownConfigsWithRuntimeKeysAndMaskedSecrets() {
        SystemConfigRepository systemConfigRepository = mock(SystemConfigRepository.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);
        Map<String, SystemConfigEntity> savedConfigs = new HashMap<>();

        SystemConfigEntity apiKeyConfig = new SystemConfigEntity();
        apiKeyConfig.setId(99L);
        apiKeyConfig.setConfigKey("ai.openai-compatible.api-key");
        apiKeyConfig.setConfigValue("encrypted-secret-value");
        apiKeyConfig.setConfigType("ai");
        apiKeyConfig.setDescription("OpenAI compatible API key");
        apiKeyConfig.setEditable(1);
        savedConfigs.put(apiKeyConfig.getConfigKey(), apiKeyConfig);

        when(systemConfigRepository.findByKey(any())).thenAnswer(invocation ->
            Optional.ofNullable(savedConfigs.get(invocation.getArgument(0)))
        );
        when(systemConfigRepository.saveOrUpdate(any(SystemConfigEntity.class))).thenAnswer(invocation -> {
            SystemConfigEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId((long) savedConfigs.size() + 1);
            }
            savedConfigs.put(entity.getConfigKey(), entity);
            return entity;
        });
        when(configSecretService.maskValue(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return "encrypted-secret-value".equals(value) ? "sk-****alue" : "";
        });
        when(configSecretService.hasSecret(any())).thenReturn(true);
        when(configSecretService.isEncrypted("encrypted-secret-value")).thenReturn(true);

        SystemConfigService service = new SystemConfigService(
            systemConfigRepository,
            new ObjectMapper(),
            configSecretService
        );

        List<SystemConfigVO> result = service.getKnownConfigs();

        assertThat(result).extracting(SystemConfigVO::getConfigKey)
            .contains(
                "ai.langgraph-worker.timeout-millis",
                "ai.knowledge.reasoning-mode.default",
                "crawler.rank.force-cooldown-days",
                "crawler.book.refresh-days"
            );
        assertThat(result).allSatisfy(config -> {
            assertThat(config.getConfigValue()).isNotNull();
            assertThat(config.getConfigType()).isNotBlank();
            assertThat(config.getDescription()).isNotBlank();
            assertThat(config.getEditable()).isNotNull();
        });
        assertThat(result)
            .filteredOn(config -> "ai.openai-compatible.api-key".equals(config.getConfigKey()))
            .singleElement()
            .extracting(SystemConfigVO::getConfigValue)
            .isEqualTo("sk-****alue");
        assertThat(result)
            .filteredOn(config -> "crawler.rank.refresh-days".equals(config.getConfigKey()))
            .singleElement()
            .extracting(SystemConfigVO::getConfigValue)
            .isEqualTo("3");
        assertThat(result)
            .filteredOn(config -> "ai.conversation.read-rollout-percent".equals(config.getConfigKey()))
            .singleElement()
            .extracting(SystemConfigVO::getConfigValue)
            .isEqualTo("100");
    }

    @Test
    void shouldTreatDisabledModelPromptBindingAsBound() {
        SystemConfigRepository systemConfigRepository = mock(SystemConfigRepository.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);
        when(configSecretService.hasSecret(any())).thenReturn(false);
        when(configSecretService.maskValue(any())).thenReturn("");

        SystemConfigEntity registryEntity = new SystemConfigEntity();
        registryEntity.setConfigKey("ai.model-registry.json");
        registryEntity.setConfigType("ai");
        registryEntity.setConfigValue("""
            {
              "defaultModelKey": "deepseek-chat",
              "models": [
                {
                  "modelKey": "deepseek-chat",
                  "displayName": "DeepSeek",
                  "providerType": "openai-compatible",
                  "modelName": "deepseek-chat",
                  "enabled": true
                },
                {
                  "modelKey": "kimi-k2.5",
                  "displayName": "Kimi",
                  "providerType": "openai-compatible",
                  "modelName": "kimi-k2.5",
                  "enabled": false,
                  "promptBindings": {
                    "deconstruct": "kimi-template "
                  }
                }
              ]
            }
            """);
        when(systemConfigRepository.findByKey("ai.model-registry.json")).thenReturn(Optional.of(registryEntity));

        SystemConfigService service = new SystemConfigService(
            systemConfigRepository,
            new ObjectMapper(),
            configSecretService
        );

        assertThat(service.isPromptTemplateBound("deconstruct", "kimi-template")).isTrue();
    }

    @Test
    void shouldRoundTripPromptBindingsInModelRegistry() {
        SystemConfigRepository systemConfigRepository = mock(SystemConfigRepository.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);

        when(configSecretService.hasSecret(any())).thenReturn(false);
        when(configSecretService.maskValue(any())).thenReturn("");
        when(configSecretService.encryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configSecretService.decryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configSecretService.isMaskedValue(any())).thenReturn(false);
        when(configSecretService.isEncrypted(any())).thenReturn(false);
        when(systemConfigRepository.findByKey("ai.model-registry.json")).thenReturn(Optional.empty());
        when(systemConfigRepository.saveOrUpdate(any(SystemConfigEntity.class))).thenAnswer(invocation -> {
            SystemConfigEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            return entity;
        });

        SystemConfigService service = new SystemConfigService(
            systemConfigRepository,
            new ObjectMapper(),
            configSecretService
        );

        AiModelRegistryModelRequest request = new AiModelRegistryModelRequest();
        request.setModelKey("kimi-k2.5");
        request.setDisplayName("kimi");
        request.setProviderType("openai-compatible");
        request.setModelName("kimi-k2.5");
        request.setBaseUrl("https://api.moonshot.cn/v1");
        request.setEnabled(true);
        request.setIsDefault(true);
        request.setDefaultTemperature(1.0);
        request.setMaxTokens(8192);
        request.setTemperatureSpecJson("{\"min\":0.0,\"max\":2.0,\"step\":0.1,\"default\":1.0}");
        request.setPromptBindings(Map.of(
            "deconstruct", "kimi-k2.5",
            "structure", "default"
        ));

        AiModelRegistrySaveRequest saveRequest = new AiModelRegistrySaveRequest();
        saveRequest.setDefaultModelKey("kimi-k2.5");
        saveRequest.setModels(List.of(request));

        AiModelRegistryVO result = service.saveModelRegistry(saveRequest);

        assertThat(result.getDefaultModelKey()).isEqualTo("kimi-k2.5");
        assertThat(result.getModels()).hasSize(1);
        assertThat(result.getModels().get(0).getPromptBindings())
            .containsEntry("deconstruct", "kimi-k2.5")
            .containsEntry("structure", "default");
    }

    @Test
    void shouldPreserveExistingProtocolWhenLegacyClientOmitsIt() {
        SystemConfigRepository systemConfigRepository = mock(SystemConfigRepository.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);
        Map<String, SystemConfigEntity> savedConfigs = new HashMap<>();
        when(configSecretService.hasSecret(any())).thenReturn(false);
        when(configSecretService.maskValue(any())).thenReturn("");
        when(configSecretService.encryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configSecretService.decryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configSecretService.isMaskedValue(any())).thenReturn(false);
        when(configSecretService.isEncrypted(any())).thenReturn(false);
        when(systemConfigRepository.findByKey(any())).thenAnswer(invocation ->
            Optional.ofNullable(savedConfigs.get(invocation.getArgument(0)))
        );
        when(systemConfigRepository.saveOrUpdate(any(SystemConfigEntity.class))).thenAnswer(invocation -> {
            SystemConfigEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            savedConfigs.put(entity.getConfigKey(), entity);
            return entity;
        });

        SystemConfigService service = new SystemConfigService(
            systemConfigRepository,
            new ObjectMapper(),
            configSecretService
        );

        AiModelRegistryModelRequest initial = new AiModelRegistryModelRequest();
        initial.setModelKey("gateway-model");
        initial.setDisplayName("Gateway");
        initial.setProviderType("openai-compatible");
        initial.setProtocol("responses");
        AiProviderCapabilities initialCapabilities = providerCapabilities();
        initialCapabilities.setPromptCache(promptCache(
            "deepseek_automatic",
            "provider_managed",
            "provider_default",
            "none"
        ));
        initial.setProviderCapabilities(initialCapabilities);
        initial.setModelName("gateway-model");
        initial.setBaseUrl("https://gateway.example/v1");
        initial.setEnabled(true);
        initial.setIsDefault(true);
        AiModelRegistrySaveRequest firstSave = new AiModelRegistrySaveRequest();
        firstSave.setDefaultModelKey("gateway-model");
        firstSave.setModels(List.of(initial));
        service.saveModelRegistry(firstSave);

        AiModelRegistryModelRequest legacyUpdate = new AiModelRegistryModelRequest();
        legacyUpdate.setModelKey("gateway-model");
        legacyUpdate.setDisplayName("Gateway renamed");
        legacyUpdate.setProviderType("openai-compatible");
        legacyUpdate.setModelName("gateway-model");
        legacyUpdate.setBaseUrl("https://gateway.example/v1");
        legacyUpdate.setEnabled(true);
        legacyUpdate.setIsDefault(true);
        AiModelRegistrySaveRequest secondSave = new AiModelRegistrySaveRequest();
        secondSave.setDefaultModelKey("gateway-model");
        secondSave.setModels(List.of(legacyUpdate));

        AiModelRegistryVO result = service.saveModelRegistry(secondSave);

        assertThat(result.getModels()).singleElement().satisfies(model -> {
            assertThat(model.getProtocol()).isEqualTo("responses");
            assertThat(model.getProviderCapabilities()).isNotNull();
            assertThat(model.getProviderCapabilities().getSchemaVersion()).isEqualTo(1);
            assertThat(model.getProviderCapabilities().getSupportsStreaming()).isTrue();
            assertThat(model.getProviderCapabilities().getSupportsTools()).isTrue();
            assertThat(model.getProviderCapabilities().getSupportsJsonObject()).isTrue();
            assertThat(model.getProviderCapabilities().getSupportsReasoning()).isTrue();
            assertThat(model.getProviderCapabilities().getReportsUsage()).isTrue();
            assertThat(model.getProviderCapabilities().getReportsCacheUsage()).isTrue();
            assertThat(model.getProviderCapabilities().getPromptCache().getStrategy())
                .isEqualTo("deepseek_automatic");
        });

        AiProviderCapabilities partial = new AiProviderCapabilities();
        partial.setSchemaVersion(1);
        partial.setSupportsStreaming(true);
        legacyUpdate.setProviderCapabilities(partial);

        assertThatThrownBy(() -> service.saveModelRegistry(secondSave))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST)
            );

        AiProviderCapabilities unsupported = providerCapabilities();
        unsupported.setSchemaVersion(2);
        legacyUpdate.setProviderCapabilities(unsupported);

        assertThatThrownBy(() -> service.saveModelRegistry(secondSave))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST)
            );
    }

    @Test
    void shouldRoundTripAndPreserveProviderRoutingPolicyWhenLegacyClientOmitsIt() {
        SystemConfigService service = newInMemorySystemConfigService();
        List<AiModelRegistryModelRequest> models = List.of(
            routingModel("primary", "openai-compatible", "responses", providerCapabilities(), true),
            routingModel("standby", "openai-compatible", "responses", providerCapabilities(), true)
        );
        AiModelRegistrySaveRequest initial = new AiModelRegistrySaveRequest();
        initial.setDefaultModelKey("primary");
        initial.setProviderRoutingPolicy(routingPolicy(List.of("primary", "standby"), 1, 90));
        initial.setModels(models);

        AiModelRegistryVO first = service.saveModelRegistry(initial);

        assertThat(first.getProviderRoutingPolicy()).satisfies(policy -> {
            assertThat(policy.getSchemaVersion()).isEqualTo(1);
            assertThat(policy.getEnabled()).isTrue();
            assertThat(policy.getOrderedProfileKeys()).containsExactly("primary", "standby");
            assertThat(policy.getMaxFailovers()).isEqualTo(1);
            assertThat(policy.getCooldownSeconds()).isEqualTo(90);
        });

        AiModelRegistrySaveRequest legacyUpdate = new AiModelRegistrySaveRequest();
        legacyUpdate.setDefaultModelKey("primary");
        legacyUpdate.setModels(models);

        AiModelRegistryVO preserved = service.saveModelRegistry(legacyUpdate);

        assertThat(preserved.getProviderRoutingPolicy().getEnabled()).isTrue();
        assertThat(preserved.getProviderRoutingPolicy().getOrderedProfileKeys())
            .containsExactly("primary", "standby");
        assertThat(preserved.getProviderRoutingPolicy().getCooldownSeconds()).isEqualTo(90);
    }

    @Test
    void shouldRoundTripResponsesPromptCacheCapabilitiesAndRejectChatProtocol() {
        SystemConfigService service = newInMemorySystemConfigService();
        AiProviderCapabilities capabilities = providerCapabilities();
        AiPromptCacheCapabilities promptCache = new AiPromptCacheCapabilities();
        promptCache.setStrategy("openai_gpt_5_6");
        promptCache.setMode("implicit");
        promptCache.setRetention("30m");
        promptCache.setBreakpoint("stable_prefix");
        capabilities.setPromptCache(promptCache);

        AiModelRegistryModelRequest model = new AiModelRegistryModelRequest();
        model.setModelKey("selected-gpt");
        model.setProviderType("openai-compatible");
        model.setProtocol("responses");
        model.setProviderCapabilities(capabilities);
        model.setModelName("gateway-gpt-current");
        model.setBaseUrl("https://gateway.example/v1");
        model.setEnabled(true);
        model.setIsDefault(true);
        AiModelRegistrySaveRequest request = new AiModelRegistrySaveRequest();
        request.setDefaultModelKey("selected-gpt");
        request.setModels(List.of(model));

        AiModelRegistryVO result = service.saveModelRegistry(request);

        assertThat(result.getModels()).singleElement().satisfies(saved -> {
            assertThat(saved.getProviderCapabilities().getPromptCache()).isNotSameAs(promptCache);
            assertThat(saved.getProviderCapabilities().getPromptCache().getStrategy()).isEqualTo("openai_gpt_5_6");
            assertThat(saved.getProviderCapabilities().getPromptCache().getMode()).isEqualTo("implicit");
            assertThat(saved.getProviderCapabilities().getPromptCache().getRetention()).isEqualTo("30m");
            assertThat(saved.getProviderCapabilities().getPromptCache().getBreakpoint()).isEqualTo("stable_prefix");
        });

        model.setProtocol("chat_completions");
        assertThatThrownBy(() -> service.saveModelRegistry(request))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST)
            );

        model.setProtocol("responses");
        promptCache.setStrategy("openai_legacy");
        promptCache.setMode("explicit");
        promptCache.setRetention("24h");
        assertThatThrownBy(() -> service.saveModelRegistry(request))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST)
            );
    }

    @Test
    void shouldRoundTripEnabledProviderRoutingPolicyWithoutAutomaticFailover() {
        SystemConfigService service = newInMemorySystemConfigService();
        AiModelRegistrySaveRequest request = new AiModelRegistrySaveRequest();
        request.setDefaultModelKey("primary");
        request.setProviderRoutingPolicy(routingPolicy(List.of("primary", "standby"), 0, 120));
        request.setModels(List.of(
            routingModel("primary", "openai-compatible", "responses", providerCapabilities(), true),
            routingModel("standby", "openai-compatible", "responses", providerCapabilities(), true)
        ));

        AiModelRegistryVO result = service.saveModelRegistry(request);

        assertThat(result.getProviderRoutingPolicy()).satisfies(policy -> {
            assertThat(policy.getEnabled()).isTrue();
            assertThat(policy.getOrderedProfileKeys()).containsExactly("primary", "standby");
            assertThat(policy.getMaxFailovers()).isZero();
            assertThat(policy.getCooldownSeconds()).isEqualTo(120);
        });
    }

    @Test
    void shouldAcceptChainFailoverAcrossEveryOrderedProfile() {
        SystemConfigService service = newInMemorySystemConfigService();
        AiModelRegistrySaveRequest request = new AiModelRegistrySaveRequest();
        request.setDefaultModelKey("primary");
        request.setProviderRoutingPolicy(
            routingPolicy(List.of("primary", "standby", "third"), 2, 120)
        );
        request.setModels(List.of(
            routingModel("primary", "openai-compatible", "responses", providerCapabilities(), true),
            routingModel("standby", "openai-compatible", "responses", providerCapabilities(), true),
            routingModel("third", "openai-compatible", "responses", providerCapabilities(), true)
        ));

        AiModelRegistryVO result = service.saveModelRegistry(request);

        assertThat(result.getProviderRoutingPolicy()).satisfies(policy -> {
            assertThat(policy.getOrderedProfileKeys()).containsExactly("primary", "standby", "third");
            assertThat(policy.getMaxFailovers()).isEqualTo(2);
        });
    }

    @Test
    void shouldDefaultOmittedMaxFailoversToTheWholeChain() {
        SystemConfigService service = newInMemorySystemConfigService();
        AiModelRegistrySaveRequest request = new AiModelRegistrySaveRequest();
        request.setDefaultModelKey("primary");
        AiProviderRoutingPolicy policy = routingPolicy(List.of("primary", "standby", "third"), 0, 120);
        policy.setMaxFailovers(null);
        request.setProviderRoutingPolicy(policy);
        request.setModels(List.of(
            routingModel("primary", "openai-compatible", "responses", providerCapabilities(), true),
            routingModel("standby", "openai-compatible", "responses", providerCapabilities(), true),
            routingModel("third", "openai-compatible", "responses", providerCapabilities(), true)
        ));

        AiModelRegistryVO result = service.saveModelRegistry(request);

        assertThat(result.getProviderRoutingPolicy().getMaxFailovers()).isEqualTo(2);
    }

    @Test
    void shouldRejectInvalidEnabledProviderRoutingPolicies() {
        AiModelRegistryModelRequest primary = routingModel(
            "primary", "openai-compatible", "responses", providerCapabilities(), true
        );
        AiModelRegistryModelRequest standby = routingModel(
            "standby", "openai-compatible", "responses", providerCapabilities(), true
        );

        assertRoutingPolicyRejected(
            List.of(primary, standby),
            routingPolicy(List.of("primary"), 1, 60)
        );
        assertRoutingPolicyRejected(
            List.of(primary, standby),
            routingPolicy(List.of("primary", "primary"), 1, 60)
        );
        assertRoutingPolicyRejected(
            List.of(primary, standby),
            routingPolicy(List.of("primary", "missing"), 1, 60)
        );
        assertRoutingPolicyRejected(
            List.of(primary, routingModel(
                "standby", "openai-compatible", "responses", providerCapabilities(), false
            )),
            routingPolicy(List.of("primary", "standby"), 1, 60)
        );
        assertRoutingPolicyRejected(
            List.of(primary, routingModel(
                "standby", "openai-compatible", "unspecified", providerCapabilities(), true
            )),
            routingPolicy(List.of("primary", "standby"), 1, 60)
        );
        assertRoutingPolicyRejected(
            List.of(primary, routingModel(
                "standby", "openai-compatible", "responses", null, true
            )),
            routingPolicy(List.of("primary", "standby"), 1, 60)
        );
        assertRoutingPolicyRejected(
            List.of(primary, routingModel(
                "standby", "other-compatible", "responses", providerCapabilities(), true
            )),
            routingPolicy(List.of("primary", "standby"), 1, 60)
        );
        assertRoutingPolicyRejected(
            List.of(primary, routingModel(
                "standby", "openai-compatible", "chat_completions", providerCapabilities(), true
            )),
            routingPolicy(List.of("primary", "standby"), 1, 60)
        );
        AiProviderCapabilities incompatibleCapabilities = providerCapabilities();
        incompatibleCapabilities.setSupportsTools(false);
        assertRoutingPolicyRejected(
            List.of(primary, routingModel(
                "standby", "openai-compatible", "responses", incompatibleCapabilities, true
            )),
            routingPolicy(List.of("primary", "standby"), 1, 60)
        );

        for (AiProviderRoutingPolicy invalid : List.of(
            // Two candidates allow at most two hops; three is out of bounds.
            routingPolicy(List.of("primary", "standby"), 3, 60),
            routingPolicy(List.of("primary", "standby"), 1, 29),
            routingPolicy(List.of("primary", "standby"), 1, 3601)
        )) {
            assertRoutingPolicyRejected(List.of(primary, standby), invalid);
        }
        AiProviderRoutingPolicy unsupportedSchema = routingPolicy(
            List.of("primary", "standby"), 1, 60
        );
        unsupportedSchema.setSchemaVersion(2);
        assertRoutingPolicyRejected(List.of(primary, standby), unsupportedSchema);
    }

    @Test
    void shouldRejectDuplicateModelKeysAfterNormalization() {
        SystemConfigRepository systemConfigRepository = mock(SystemConfigRepository.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);
        when(configSecretService.hasSecret(any())).thenReturn(false);
        when(configSecretService.maskValue(any())).thenReturn("");
        when(configSecretService.isMaskedValue(any())).thenReturn(false);
        when(systemConfigRepository.saveOrUpdate(any(SystemConfigEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        SystemConfigService service = new SystemConfigService(
            systemConfigRepository,
            new ObjectMapper(),
            configSecretService
        );
        AiModelRegistryModelRequest first = new AiModelRegistryModelRequest();
        first.setModelKey(" gateway-primary ");
        first.setModelName("model-a");
        first.setEnabled(true);
        first.setIsDefault(true);
        AiModelRegistryModelRequest duplicate = new AiModelRegistryModelRequest();
        duplicate.setModelKey("gateway-primary");
        duplicate.setModelName("model-b");
        duplicate.setEnabled(true);
        AiModelRegistrySaveRequest request = new AiModelRegistrySaveRequest();
        request.setDefaultModelKey("gateway-primary");
        request.setModels(List.of(first, duplicate));

        assertThatThrownBy(() -> service.saveModelRegistry(request))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST);
                assertThat(exception.getMessage()).contains("modelKey");
            });
    }

    @Test
    void shouldResolveEnabledRuntimeModelByExactModelKeyOnly() {
        SystemConfigRepository systemConfigRepository = mock(SystemConfigRepository.class);
        ConfigSecretService configSecretService = mock(ConfigSecretService.class);
        SystemConfigEntity registryEntity = new SystemConfigEntity();
        registryEntity.setConfigKey("ai.model-registry.json");
        registryEntity.setConfigType("ai");
        registryEntity.setConfigValue("""
            {
              "defaultModelKey": "default-profile",
              "models": [
                {
                  "modelKey": "default-profile",
                  "providerType": "openai-compatible",
                  "protocol": "responses",
                  "modelName": "shared-alias",
                  "baseUrl": "https://default.example/v1",
                  "apiKey": "enc::default",
                  "enabled": true
                },
                {
                  "modelKey": "disabled-profile",
                  "providerType": "openai-compatible",
                  "protocol": "responses",
                  "modelName": "disabled-model",
                  "baseUrl": "https://disabled.example/v1",
                  "apiKey": "enc::disabled",
                  "enabled": false
                }
              ]
            }
            """);
        when(systemConfigRepository.findByKey("ai.model-registry.json"))
            .thenReturn(Optional.of(registryEntity));
        when(configSecretService.hasSecret(any())).thenReturn(true);
        when(configSecretService.isEncrypted(any())).thenReturn(true);
        when(configSecretService.decryptIfNecessary("enc::default")).thenReturn("resolved-default-key");

        SystemConfigService service = new SystemConfigService(
            systemConfigRepository,
            new ObjectMapper(),
            configSecretService
        );

        assertThat(service.resolveEnabledModelByKey(" default-profile "))
            .get()
            .satisfies(model -> {
                assertThat(model.getModelKey()).isEqualTo("default-profile");
                assertThat(model.getApiKey()).isEqualTo("resolved-default-key");
                assertThat(model.getProviderCapabilities()).isNull();
            });
        assertThat(service.resolveEnabledModelByKey("shared-alias")).isEmpty();
        assertThat(service.resolveEnabledModelByKey("disabled-profile")).isEmpty();
        assertThat(service.resolveEnabledModelByKey("missing-profile")).isEmpty();
        assertThat(service.getModelRegistry().getProviderRoutingPolicy()).satisfies(policy -> {
            assertThat(policy.getSchemaVersion()).isEqualTo(1);
            assertThat(policy.getEnabled()).isFalse();
            assertThat(policy.getOrderedProfileKeys()).isEmpty();
            assertThat(policy.getMaxFailovers()).isZero();
            assertThat(policy.getCooldownSeconds()).isBetween(30, 3600);
        });
    }

    private static SystemConfigService newInMemorySystemConfigService() {
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
            configs.put(entity.getConfigKey(), entity);
            return entity;
        });
        when(secretService.hasSecret(any())).thenReturn(false);
        when(secretService.maskValue(any())).thenReturn("");
        when(secretService.encryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secretService.decryptIfNecessary(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secretService.isMaskedValue(any())).thenReturn(false);
        when(secretService.isEncrypted(any())).thenReturn(false);
        return new SystemConfigService(repository, new ObjectMapper(), secretService);
    }

    private static AiModelRegistryModelRequest routingModel(String key,
                                                             String providerType,
                                                             String protocol,
                                                             AiProviderCapabilities capabilities,
                                                             boolean enabled) {
        AiModelRegistryModelRequest model = new AiModelRegistryModelRequest();
        model.setModelKey(key);
        model.setProviderType(providerType);
        model.setProtocol(protocol);
        model.setProviderCapabilities(capabilities);
        model.setModelName(key + "-model");
        model.setBaseUrl("https://" + key + ".example/v1");
        model.setEnabled(enabled);
        model.setIsDefault("primary".equals(key));
        return model;
    }

    private static AiProviderRoutingPolicy routingPolicy(List<String> keys,
                                                          int maxFailovers,
                                                          int cooldownSeconds) {
        AiProviderRoutingPolicy policy = new AiProviderRoutingPolicy();
        policy.setSchemaVersion(1);
        policy.setEnabled(true);
        policy.setOrderedProfileKeys(keys);
        policy.setMaxFailovers(maxFailovers);
        policy.setCooldownSeconds(cooldownSeconds);
        return policy;
    }

    private static void assertRoutingPolicyRejected(List<AiModelRegistryModelRequest> models,
                                                     AiProviderRoutingPolicy policy) {
        AiModelRegistrySaveRequest request = new AiModelRegistrySaveRequest();
        request.setDefaultModelKey("primary");
        request.setProviderRoutingPolicy(policy);
        request.setModels(models);
        assertThatThrownBy(() -> newInMemorySystemConfigService().saveModelRegistry(request))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(ResultCode.BAD_REQUEST)
            );
    }

    private static AiProviderCapabilities providerCapabilities() {
        AiProviderCapabilities capabilities = new AiProviderCapabilities();
        capabilities.setSchemaVersion(1);
        capabilities.setSupportsStreaming(true);
        capabilities.setSupportsTools(true);
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
}
