package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.analysis.client.LangGraphWorkerClient;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderProbeVO;
import com.novelanalyzer.modules.knowledge.vo.AgentProviderProfileVO;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeAgentProviderProbeService {

    private final KnowledgeAgentGovernanceService governanceService;
    private final LangGraphWorkerClient workerClient;

    public KnowledgeAgentProviderProbeService(KnowledgeAgentGovernanceService governanceService,
                                              LangGraphWorkerClient workerClient) {
        this.governanceService = governanceService;
        this.workerClient = workerClient;
    }

    public AgentProviderProbeVO probe(String modelKey) {
        String normalizedModelKey = trimToNull(modelKey);
        if (normalizedModelKey == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "modelKey is required");
        }

        AgentProviderProfileVO profile = governanceService.runtimeConfig().getProviderProfiles().stream()
            .filter(candidate -> normalizedModelKey.equals(candidate.getProfileKey()))
            .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "provider profile is not available"));
        if (!Boolean.TRUE.equals(profile.getApiKeyConfigured())) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "provider credential is not configured");
        }
        String profileVersion = trimToNull(profile.getProfileVersion());
        if (profileVersion == null) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "provider profile is not dispatchable");
        }

        AgentProviderProbeVO result = workerClient.probeAgentProvider(normalizedModelKey, profileVersion);
        if (result == null
            || !normalizedModelKey.equals(result.getProfileKey())
            || !profileVersion.equals(result.getProfileVersion())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "provider probe identity mismatch");
        }
        return result;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
