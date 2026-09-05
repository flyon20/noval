package com.novelanalyzer.modules.config;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.config.service.ProviderTierService;
import com.novelanalyzer.modules.config.service.SystemConfigService;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.config.vo.ProviderTierQueryModel;
import com.novelanalyzer.modules.config.vo.ProviderTierVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderTierServiceTest {

    @Test
    void shouldQueryWorkerWithRegistryTriplesAndIndexByModelKey() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getModelRegistry()).thenReturn(registry(
            model("gateway-primary", "openai", "gpt-5"),
            model("kimi-main", "moonshot", "kimi-k3")
        ));
        when(workerClient.resolveProviderTiers(anyList())).thenReturn(List.of(
            tier("gateway-primary", "openai", true, List.of("minimal", "low", "medium", "high")),
            tier("kimi-main", "moonshot", true, List.of("low", "high", "max"))
        ));

        ProviderTierService service = new ProviderTierService(workerClient, systemConfigService);
        Map<String, ProviderTierVO> tiers = service.resolveTiersByModelKey();

        assertThat(tiers.keySet()).containsExactly("gateway-primary", "kimi-main");
        assertThat(tiers.get("kimi-main").getReasoningTiers()).containsExactly("low", "high", "max");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProviderTierQueryModel>> captor = ArgumentCaptor.forClass(List.class);
        verify(workerClient).resolveProviderTiers(captor.capture());
        // modelName 必须一起送过去：OpenAI 家族的档位取决于具体模型（gpt-5 有推理契约，gpt-4o 没有）。
        assertThat(captor.getValue()).extracting(
            ProviderTierQueryModel::getModelKey,
            ProviderTierQueryModel::getProviderType,
            ProviderTierQueryModel::getModelName
        ).containsExactly(
            org.assertj.core.groups.Tuple.tuple("gateway-primary", "openai", "gpt-5"),
            org.assertj.core.groups.Tuple.tuple("kimi-main", "moonshot", "kimi-k3")
        );
    }

    @Test
    void shouldServeLastKnownSnapshotWhenWorkerBecomesUnreachable() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getModelRegistry())
            .thenReturn(registry(model("gateway-primary", "openai", "gpt-5")));
        when(workerClient.resolveProviderTiers(anyList()))
            .thenReturn(List.of(tier("gateway-primary", "openai", true, List.of("minimal", "low", "medium", "high"))))
            .thenThrow(new BusinessException(ResultCode.INTERNAL_ERROR, "langgraph worker provider tiers failed"));

        ProviderTierService service = new ProviderTierService(workerClient, systemConfigService);
        service.resolveTiersByModelKey();
        Map<String, ProviderTierVO> afterOutage = service.resolveTiersByModelKey();

        // worker 短暂离线时保留上一次成功快照，模型选择器照常渲染思考强度控件。
        assertThat(afterOutage.get("gateway-primary").getReasoningTiers())
            .containsExactly("minimal", "low", "medium", "high");
        verify(workerClient, times(2)).resolveProviderTiers(anyList());
    }

    @Test
    void shouldReturnEmptyTiersWhenWorkerFailsBeforeAnySuccess() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getModelRegistry())
            .thenReturn(registry(model("gateway-primary", "openai", "gpt-5")));
        when(workerClient.resolveProviderTiers(anyList()))
            .thenThrow(new BusinessException(ResultCode.INTERNAL_ERROR, "worker down"));

        ProviderTierService service = new ProviderTierService(workerClient, systemConfigService);

        assertThat(service.resolveTiersByModelKey()).isEmpty();
    }

    @Test
    void shouldSkipWorkerCallWhenRegistryHasNoUsableModelKey() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getModelRegistry()).thenReturn(registry(model("   ", "openai", "gpt-5")));

        ProviderTierService service = new ProviderTierService(workerClient, systemConfigService);

        assertThat(service.resolveTiersByModelKey()).isEmpty();
        verify(workerClient, times(0)).resolveProviderTiers(anyList());
    }

    @Test
    void shouldDropWorkerEntriesWithoutModelKey() {
        LangGraphWorkerClient workerClient = mock(LangGraphWorkerClient.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getModelRegistry())
            .thenReturn(registry(model("gateway-primary", "openai", "gpt-5")));
        when(workerClient.resolveProviderTiers(anyList())).thenReturn(java.util.Arrays.asList(
            null,
            tier(null, "openai", true, List.of("high")),
            tier("gateway-primary", "openai", true, List.of("minimal", "high"))
        ));

        ProviderTierService service = new ProviderTierService(workerClient, systemConfigService);

        assertThat(service.resolveTiersByModelKey()).containsOnlyKeys("gateway-primary");
    }

    private static AiModelRegistryVO registry(AiModelRegistryModelVO... models) {
        AiModelRegistryVO registry = new AiModelRegistryVO();
        registry.setModels(List.of(models));
        return registry;
    }

    private static AiModelRegistryModelVO model(String modelKey, String providerType, String modelName) {
        AiModelRegistryModelVO model = new AiModelRegistryModelVO();
        model.setModelKey(modelKey);
        model.setProviderType(providerType);
        model.setModelName(modelName);
        return model;
    }

    private static ProviderTierVO tier(String modelKey, String family, boolean supportsReasoning, List<String> tiers) {
        ProviderTierVO tier = new ProviderTierVO();
        tier.setModelKey(modelKey);
        tier.setFamily(family);
        tier.setSupportsReasoning(supportsReasoning);
        tier.setReasoningTiers(tiers);
        tier.setAcceptsTemperature(true);
        return tier;
    }
}
