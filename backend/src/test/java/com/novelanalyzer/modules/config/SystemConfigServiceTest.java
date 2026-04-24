package com.novelanalyzer.modules.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.config.dto.AiModelRegistryModelRequest;
import com.novelanalyzer.modules.config.dto.AiModelRegistrySaveRequest;
import com.novelanalyzer.modules.config.model.SystemConfigEntity;
import com.novelanalyzer.modules.config.repository.SystemConfigRepository;
import com.novelanalyzer.modules.config.service.ConfigSecretService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemConfigServiceTest {

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
