package com.novelanalyzer.modules.config.service;

import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.AiModelRegistryVO;
import com.novelanalyzer.modules.config.vo.ProviderTierQueryModel;
import com.novelanalyzer.modules.config.vo.ProviderTierVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reasoning tiers for registry models, resolved by the worker's dialect table.
 *
 * <p>The dialect table is the single source of truth, so the tiers are fetched rather than
 * restated here. It only changes when the worker is redeployed, which is why the last good
 * answer is held in {@code lastKnownTiers} and served when the worker is briefly unreachable:
 * the model picker keeps rendering instead of losing its thinking-effort control.
 */
@Service
public class ProviderTierService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderTierService.class);

    private final LangGraphWorkerClient langGraphWorkerClient;
    private final SystemConfigService systemConfigService;
    private final AtomicReference<Map<String, ProviderTierVO>> lastKnownTiers =
        new AtomicReference<>(Map.of());

    public ProviderTierService(LangGraphWorkerClient langGraphWorkerClient,
                               SystemConfigService systemConfigService) {
        this.langGraphWorkerClient = langGraphWorkerClient;
        this.systemConfigService = systemConfigService;
    }

    /**
     * Tiers keyed by {@code modelKey} for every model in the registry.
     *
     * <p>Never throws when the worker is down; falls back to the cached snapshot so callers can
     * always render. Models absent from the result should be treated as offering no tiers.
     */
    public Map<String, ProviderTierVO> resolveTiersByModelKey() {
        AiModelRegistryVO registry = systemConfigService.getModelRegistry();
        List<ProviderTierQueryModel> queries = toQueries(registry);
        if (queries.isEmpty()) {
            return Map.of();
        }
        try {
            List<ProviderTierVO> resolved = langGraphWorkerClient.resolveProviderTiers(queries);
            Map<String, ProviderTierVO> byModelKey = new LinkedHashMap<>();
            for (ProviderTierVO tier : resolved) {
                if (tier != null && tier.getModelKey() != null) {
                    byModelKey.put(tier.getModelKey(), tier);
                }
            }
            lastKnownTiers.set(Map.copyOf(byModelKey));
            return byModelKey;
        } catch (Exception ex) {
            Map<String, ProviderTierVO> cached = lastKnownTiers.get();
            LOGGER.warn("provider tier resolution failed, serving {} cached entries: {}",
                cached.size(), ex.getMessage());
            return cached;
        }
    }

    private List<ProviderTierQueryModel> toQueries(AiModelRegistryVO registry) {
        if (registry == null || registry.getModels() == null) {
            return List.of();
        }
        List<ProviderTierQueryModel> queries = new ArrayList<>();
        for (AiModelRegistryModelVO model : registry.getModels()) {
            if (model == null || model.getModelKey() == null || model.getModelKey().isBlank()) {
                continue;
            }
            queries.add(new ProviderTierQueryModel(
                model.getModelKey(),
                model.getProviderType(),
                model.getModelName()
            ));
        }
        return queries;
    }
}
