package com.novelanalyzer.modules.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.config.dto.AiModelRegistryModelRequest;
import com.novelanalyzer.modules.config.dto.AiModelRegistrySaveRequest;
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
}
